package iped.mcp.item;

import java.io.ByteArrayInputStream;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The media type of a thumbnail, read from its own bytes (FR-002).
 *
 * <p>
 * What this replaces was the literal {@code "image/jpeg"}, and the literal was wrong for a real
 * part of the collection. Thumbnails reach a case by two routes:
 *
 * <ul>
 * <li>the thumbnail tasks — {@code ImageThumbTask}, {@code DocThumbTask}, {@code VideoThumbTask} —
 * which encode to JPEG and always will;</li>
 * <li>{@code ParsingTask}, by way of {@code ExtraProperties.THUMBNAIL_BASE64}, which stores exactly
 * what the parser put there. The chat and vCard parsers feed it, and for most of them the bytes
 * come straight out of the source database. A contact avatar is routinely PNG.</li>
 * </ul>
 *
 * <p>
 * Announcing JPEG over PNG bytes makes a client decode the wrong format, and writes a false type
 * into the structured copy — which is material an examiner may end up citing. So the type is
 * detected, and when detection does not yield an image type the answer says the type could not be
 * established rather than guessing one. Guessing is precisely what FR-002 forbids, and a guess
 * here would travel into the image block as a declared fact.
 *
 * <p>
 * Detection uses Tika's default registry, already on the classpath and already what IPED trusts to
 * recognise a format. Nothing is re-implemented here.
 */
public final class ThumbnailMediaType {

    private static final Logger LOGGER = LoggerFactory.getLogger(ThumbnailMediaType.class);

    private static final String IMAGE_PREFIX = "image/";

    private ThumbnailMediaType() {
        // Utility class.
    }

    /**
     * Detects the media type of thumbnail bytes.
     *
     * @param thumb
     *            the stored thumbnail, which may be {@code null} or empty
     * @return the detected type when it is an {@code image/*} type, or {@code null} when there are
     *         no bytes to read or the bytes are not a recognised image. {@code null} means "not
     *         established", never "assume the usual one".
     */
    public static String detect(byte[] thumb) {
        if (thumb == null || thumb.length == 0) {
            return null;
        }
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(thumb)) {
            MediaType detected = MimeTypes.getDefaultMimeTypes().detect(bytes, new Metadata());
            if (detected == null) {
                return null;
            }
            String type = detected.getBaseType().toString();
            return type.startsWith(IMAGE_PREFIX) ? type : null;
        } catch (Exception e) {
            // A detector that fails says nothing about the bytes, and answering with a type anyway
            // would be the guess this class exists to avoid.
            LOGGER.debug("Media type detection failed for a thumbnail of {} bytes", thumb.length, e);
            return null;
        }
    }
}
