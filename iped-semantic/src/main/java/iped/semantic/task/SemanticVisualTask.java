package iped.semantic.task;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.XMPDM;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.configuration.Configurable;
import iped.data.IItem;
import iped.engine.Version;
import iped.engine.config.ConfigurationManager;
import iped.engine.config.EnableTaskProperty;
import iped.engine.config.ImageThumbTaskConfig;
import iped.engine.config.VideoThumbsConfig;
import iped.engine.task.AbstractTask;
import iped.engine.util.UIPropertyListenerProvider;
import iped.parsers.util.MetadataUtil;
import iped.properties.ExtraProperties;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.model.EmbedderFactory;
import iped.semantic.prep.VideoMosaicSplitter;

/**
 * Prepares the visual modality of semantic search during processing (story 6, research D8):
 * images by the thumbnail {@code ImageThumbTask} made, videos by the frames of the mosaic
 * {@code VideoThumbTask} made - no picture is decoded from the evidence again. Registered in
 * {@code TaskInstaller.xml} right after {@code ImageSimilarityTask}, where both are available.
 *
 * <p>
 * Two independent keys in {@code IPEDConfig.txt}, {@code enableSemanticImages} and
 * {@code enableSemanticVideos}, both off by default; the task is on when either is. Each key
 * governs only what it names: with images on and videos off, videos are recorded as
 * {@code MODALITY_OFF} and nothing of theirs is read. On, {@link #init} validates the visual model
 * source before any evidence is read and refuses to start without one (FR-042). The work itself is
 * in {@link VisualPreparation}, shared by the instances of every worker.
 * </p>
 */
public class SemanticVisualTask extends AbstractTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(SemanticVisualTask.class);

    /**
     * Extra attribute {@code VideoThumbTask} sets on the frames it extracts as subitems (its constant
     * is private there).
     */
    static final String VIDEO_FRAME_SUBITEM_ATTR = "videoThumbnail";

    private static final Object INIT_LOCK = new Object();
    private static final AtomicBoolean FINISHED = new AtomicBoolean(false);
    private static volatile VisualPreparation preparation;

    private boolean enabled;

    @Override
    public List<Configurable<?>> getConfigurables() {
        return List.of(new SemanticSearchConfig(), new EnableTaskProperty(SemanticSearchConfig.ENABLE_IMAGES),
                new EnableTaskProperty(SemanticSearchConfig.ENABLE_VIDEOS));
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void init(ConfigurationManager configurationManager) throws Exception {
        boolean images = configurationManager.getEnableTaskProperty(SemanticSearchConfig.ENABLE_IMAGES);
        boolean videos = configurationManager.getEnableTaskProperty(SemanticSearchConfig.ENABLE_VIDEOS);
        enabled = images || videos;
        if (!enabled) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (preparation != null) {
                return;
            }
            FINISHED.set(false);
            SemanticSearchConfig config = configurationManager.findObject(SemanticSearchConfig.class);
            SourceValidator.Validated source = SourceValidator.validate(config, Modality.VISUAL,
                    images ? SemanticSearchConfig.ENABLE_IMAGES : SemanticSearchConfig.ENABLE_VIDEOS,
                    new EmbedderFactory(config));
            ImageThumbTaskConfig imageThumbs = configurationManager.findObject(ImageThumbTaskConfig.class);
            VideoThumbsConfig videoThumbs = configurationManager.findObject(VideoThumbsConfig.class);
            VisualPreparation.Settings settings = new VisualPreparation.Settings().images(images).videos(videos)
                    .imageThumbs(imageThumbs != null && imageThumbs.isEnabled())
                    .videoThumbs(videoThumbs != null && videoThumbs.isEnabled())
                    .capabilityStartId(SemanticTextTask.capabilityStartId(output)).ipedVersion(Version.APP_VERSION);
            VisualPreparation prep = VisualPreparation.open(output, config, source, settings);
            CaseModels.copyIntoCase(config, Modality.VISUAL, output);
            preparation = prep;
            String commitStarted = iped.engine.localization.Messages.getString("Manager.CommitStarted");
            UIPropertyListenerProvider.getInstance().addPropertyChangeListener(evt -> {
                VisualPreparation p = preparation;
                if (p != null && "mensagem".equals(evt.getPropertyName()) && commitStarted.equals(evt.getNewValue())) {
                    p.commitWithMainIndex();
                }
            }, false);
            LOGGER.info("Semantic visual search is on (images: {}, videos: {}): model {}", images, videos,
                    source.embedder().identity().describe());
        }
    }

    @Override
    protected void process(IItem evidence) throws Exception {
        VisualPreparation prep = preparation;
        if (prep == null || !evidence.isToAddToCase() || evidence.isQueueEnd()) {
            return;
        }
        prep.prepare(toVisualItem(evidence, output));
    }

    static VisualItem toVisualItem(IItem evidence, java.io.File moduleDir) {
        String mediaType = evidence.getMediaType() == null ? "" : evidence.getMediaType().toString();
        Set<String> categories = evidence.getCategorySet();
        Metadata md = evidence.getMetadata();
        if (MetadataUtil.isVideoType(mediaType)) {
            return VisualItem.video(evidence.getId(), categories, () -> VideoMosaicSplitter.framesOf(moduleDir, evidence),
                    durationMs(md), fps(md));
        }
        if (MetadataUtil.isImageType(mediaType)) {
            Integer w = dimension(md, ExtraProperties.IMAGE_META_PREFIX + "Width");
            Integer h = dimension(md, ExtraProperties.IMAGE_META_PREFIX + "Height");
            if (evidence.getExtraAttribute(VIDEO_FRAME_SUBITEM_ATTR) != null) {
                return VisualItem.videoFrameSubitem(evidence.getId(), categories, w, h, evidence::getThumb);
            }
            return VisualItem.image(evidence.getId(), categories, w, h, evidence::getThumb);
        }
        return VisualItem.other(evidence.getId(), categories);
    }

    /** Leading integer of a dimension value ("640", "640 pixels"); {@code null} when absent. */
    static Integer dimension(Metadata md, String key) {
        String v = md == null ? null : md.get(key);
        if (v == null) {
            return null;
        }
        int end = 0;
        String s = v.trim();
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        try {
            return Integer.valueOf(s.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Duration of a video in milliseconds; {@code null} when absent. {@code VideoThumbTask} records
     * it in seconds as {@code xmpDM:duration}, and the metadata normalisation of parsing, which runs
     * after it, moves video metadata under the {@code video:} prefix.
     */
    static Long durationMs(Metadata md) {
        if (md == null) {
            return null;
        }
        String v = md.get(ExtraProperties.VIDEO_META_PREFIX + XMPDM.DURATION.getName());
        if (v == null) {
            v = md.get(XMPDM.DURATION.getName());
        }
        if (v == null) {
            return null;
        }
        try {
            double seconds = Double.parseDouble(v.trim());
            return seconds > 0 ? Math.round(seconds * 1000) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Frame rate VideoThumbTask recorded ({@code framerate}, moved under {@code video:}); or {@code null}. */
    static Double fps(Metadata md) {
        if (md == null) {
            return null;
        }
        String v = md.get(ExtraProperties.VIDEO_META_PREFIX + "framerate");
        if (v == null) {
            v = md.get("framerate");
        }
        if (v == null) {
            return null;
        }
        try {
            double f = Double.parseDouble(v.trim());
            return f > 0 ? f : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void finish() throws Exception {
        synchronized (INIT_LOCK) {
            VisualPreparation prep = preparation;
            if (prep == null || FINISHED.getAndSet(true)) {
                return;
            }
            try {
                prep.finish();
            } finally {
                preparation = null;
            }
        }
    }

    /** Test hook: whether a preparation is open in this JVM. */
    static boolean isPreparing() {
        return preparation != null;
    }
}
