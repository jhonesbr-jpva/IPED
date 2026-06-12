package iped.rcp.views.gallery;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.apache.lucene.document.Document;
import org.apache.lucene.util.BytesRef;
import org.eclipse.swt.graphics.ImageData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItemId;
import iped.engine.config.ConfigurationManager;
import iped.engine.config.ImageThumbTaskConfig;
import iped.engine.data.IPEDMultiSource;
import iped.engine.preview.PreviewConstants;
import iped.engine.preview.PreviewKey;
import iped.engine.preview.PreviewRepositoryManager;
import iped.engine.task.ImageThumbTask;
import iped.engine.task.ThumbTask;
import iped.engine.task.index.IndexItem;
import iped.engine.task.video.VideoThumbTask;
import iped.engine.util.Util;
import iped.parsers.util.MetadataUtil;
import iped.properties.ExtraProperties;
import iped.utils.ExternalImageConverter;
import iped.utils.HashValue;
import iped.utils.ImageUtil;
import iped.viewers.util.ImageMetadataUtil;

/**
 * Thumbnail production for the gallery (task T026, FR-008, research R12):
 * faithful port of the decode pipeline of the legacy {@code GalleryModel}
 * (index THUMB field, view/preview repository, embedded jpeg thumb,
 * subsampled decode, external converter fallback), decoupled from Swing —
 * the output is an SWT {@code ImageData} instead of an {@code ImageIcon}.
 * Instances are driven by the gallery's worker pool, never by the UI thread.
 */
class GalleryThumbProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(GalleryThumbProvider.class);

    /**
     * Max Sleuthkit connection pool size — same legacy cap to avoid TSK
     * deadlocks when many streams are requested at once.
     */
    static final int MAX_TSK_POOL_SIZE = 20;

    /** Decode outcome: display name + optional image (null = icon/unsupported). */
    record Thumb(String name, ImageData image, boolean unsupported) {
    }

    private final IPEDMultiSource source;
    private ImageThumbTask imgThumbTask;
    private ExternalImageConverter externalImageConverter;
    private int galleryThreads = 1;
    private boolean logRendering = false;

    /** Video thumbs in gallery tiles (legacy {@code useVideoThumbsInGallery}). */
    private volatile boolean useVideoThumbsInGallery = false;

    GalleryThumbProvider(IPEDMultiSource source) {
        this.source = source;
        try {
            imgThumbTask = new ImageThumbTask();
            imgThumbTask.init(ConfigurationManager.get());
            galleryThreads = Math.min(imgThumbTask.getImageThumbConfig().getGalleryThreads(), MAX_TSK_POOL_SIZE);
            logRendering = imgThumbTask.getImageThumbConfig().isLogGalleryRendering();
        } catch (Exception e) {
            LOGGER.warn("Could not init image thumb config, using defaults", e);
        }
    }

    int getGalleryThreads() {
        return galleryThreads;
    }

    int getThumbSize() {
        return imgThumbTask != null ? imgThumbTask.getThumbSize() : ImageThumbTaskConfig.DEFAULT_THUMB_SIZE;
    }

    /** Decodes the thumbnail of an item (worker thread). */
    Thumb decode(IItemId id) {
        BufferedImage image = null;
        InputStream stream = null;
        String name = "";
        boolean unsupported = false;
        try {
            int docId = source.getLuceneId(id);
            Document doc = source.getSearcher().doc(docId);
            name = String.valueOf(doc.get(IndexItem.NAME));

            if (logRendering) {
                LOGGER.info("Gallery rendering {}", doc.get(IndexItem.PATH));
            }

            String mediaType = doc.get(IndexItem.CONTENTTYPE);

            BytesRef bytesRef = doc.getBinaryValue(IndexItem.THUMB);
            if (bytesRef != null
                    && ((!isSupportedVideo(mediaType) && !isAnimationImage(doc, mediaType))
                            || useVideoThumbsInGallery)) {
                byte[] thumb = bytesRef.bytes;
                if (thumb.length > 0) {
                    image = ImageIO.read(new ByteArrayInputStream(thumb));
                }
            }

            if (image == null) {
                String hash = doc.get(IndexItem.HASH);
                if (hash != null && !hash.isEmpty()) {
                    image = getViewImage(docId, hash, isSupportedVideo(mediaType) || isAnimationImage(doc, mediaType));
                }

                if (Boolean.parseBoolean(doc.get(IndexItem.ISDIR))) {
                    unsupported = true;
                } else if (image == null && !isSupportedImage(mediaType) && !isSupportedVideo(mediaType)) {
                    unsupported = true;
                }

                if (image == null && !unsupported && isSupportedImage(mediaType)) {
                    stream = source.getItemByLuceneID(docId).getBufferedInputStream();
                }

                if (stream != null) {
                    stream.mark(10000000);
                }

                if (image == null && stream != null && imgThumbTask != null
                        && imgThumbTask.getImageThumbConfig().isExtractThumb() && "image/jpeg".equals(mediaType)) {
                    image = ImageMetadataUtil.getThumb(closeShield(stream));
                    stream.reset();
                }

                if (image == null && stream != null) {
                    image = ImageUtil.getSubSampledImage(stream, getThumbSize());
                    stream.reset();
                }

                if (image == null && stream != null) {
                    String sizeStr = doc.get(IndexItem.LENGTH);
                    Long size = sizeStr == null ? null : Long.parseLong(sizeStr);
                    image = externalConverter().getImage(stream, getThumbSize(), false, size);
                }
            }

            if (image != null) {
                // resize only if too large (> 2x the desired thumb size)
                if (image.getWidth() > getThumbSize() * 2 || image.getHeight() > getThumbSize() * 2) {
                    image = ImageUtil.resizeImage(image, getThumbSize(), getThumbSize());
                }
                return new Thumb(name, SwtImages.toImageData(image), false);
            }
        } catch (Throwable e) {
            LOGGER.warn("Error rendering gallery thumb of item {}", id, e);
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    LOGGER.warn("Error closing thumb stream", e);
                }
            }
        }
        return new Thumb(name, null, unsupported);
    }

    /** Legacy {@code GalleryModel.getViewImage}: thumb/preview from the case. */
    private BufferedImage getViewImage(int docId, String hash, boolean isVideo) throws IOException {
        File modulesDir = source.getAtomicSource(docId).getModuleDir();
        File baseFolder;
        String ext;
        if (isVideo) {
            baseFolder = new File(modulesDir, PreviewConstants.VIEW_FOLDER_NAME);
            ext = VideoThumbTask.PREVIEW_EXT;
        } else {
            // for old cases, when image thumbs were not stored in the index
            baseFolder = new File(modulesDir, ImageThumbTask.THUMBS_FOLDER_NAME);
            ext = ThumbTask.THUMB_EXT;
        }

        File hashFile = Util.getFileFromHash(baseFolder, hash, ext);
        if (hashFile.exists()) {
            return ImageIO.read(hashFile);
        }
        try {
            PreviewKey key = new PreviewKey(new HashValue(hash).getBytes());
            AtomicReference<BufferedImage> result = new AtomicReference<>();
            PreviewRepositoryManager.get(baseFolder.getParentFile()).consumePreview(key, inputStream -> {
                result.set(ImageIO.read(inputStream));
            });
            return result.get();
        } catch (Exception e) {
            LOGGER.warn("Error reading preview of hash {}", hash, e);
            return null;
        }
    }

    void setUseVideoThumbsInGallery(boolean useVideoThumbs) {
        this.useVideoThumbsInGallery = useVideoThumbs;
    }

    private synchronized ExternalImageConverter externalConverter() {
        if (externalImageConverter == null) {
            externalImageConverter = new ExternalImageConverter();
        }
        return externalImageConverter;
    }

    private static boolean isSupportedImage(String mediaType) {
        return MetadataUtil.isImageType(mediaType);
    }

    private static boolean isAnimationImage(Document doc, String mediaType) {
        return MetadataUtil.isImageSequence(mediaType) || doc.get(ExtraProperties.ANIMATION_FRAMES_PROP) != null;
    }

    private static boolean isSupportedVideo(String mediaType) {
        return MetadataUtil.isVideoType(mediaType);
    }

    /** commons-io is not exported by the wrapper: minimal close shield. */
    private static InputStream closeShield(InputStream stream) {
        return new FilterInputStream(stream) {
            @Override
            public void close() {
                // shield: the caller resets and reuses the underlying stream
            }
        };
    }
}
