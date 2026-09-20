package iped.mcp.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import iped.mcp.item.ThumbnailMediaType;

/**
 * The media type of a thumbnail is read from its bytes, never presumed (FR-002, decision D3).
 *
 * <p>
 * What this replaces was the constant {@code "image/jpeg"}, and the constant was wrong for a real
 * part of the collection. Thumbnails reach the index by two routes: the thumbnail tasks, which
 * always write JPEG, and {@code ParsingTask} by way of {@code ExtraProperties.THUMBNAIL_BASE64},
 * which stores whatever the parser put there. That second route is fed by the chat and vCard
 * parsers, and for most of them the bytes come straight out of the source database — a contact
 * avatar is routinely PNG. Declaring JPEG over PNG bytes makes a client decode the wrong format,
 * and writes a false type into the structured copy, which is material an examiner may cite.
 *
 * <p>
 * The bench built for this feature has such an item: a vCard whose photo is PNG, whose thumbnail is
 * therefore PNG. See {@code specs/008-thumbnail-image-block/validation-log.md}.
 */
public class MediaTypeDetectionTest {

    /** Smallest byte sequences that carry each format's signature. */
    private static final byte[] JPEG_HEADER = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J',
            'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00 };

    private static final byte[] PNG_HEADER = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00,
            0x0D, 'I', 'H', 'D', 'R', 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00 };

    private static final byte[] GIF_HEADER = { 'G', 'I', 'F', '8', '9', 'a', 0x01, 0x00, 0x01, 0x00, (byte) 0x80,
            0x00, 0x00 };

    @Test
    public void jpegBytesAreReportedAsJpeg() {
        assertEquals("image/jpeg", ThumbnailMediaType.detect(JPEG_HEADER));
    }

    @Test
    public void pngBytesAreReportedAsPngRatherThanAsTheOldConstant() {
        // The regression this feature exists to close: before it, these bytes were announced as
        // image/jpeg because the field was a literal.
        assertEquals("image/png", ThumbnailMediaType.detect(PNG_HEADER));
    }

    @Test
    public void gifBytesAreReportedAsGif() {
        assertEquals("image/gif", ThumbnailMediaType.detect(GIF_HEADER));
    }

    @Test
    public void bytesThatAreNotAnImageYieldNoTypeAtAll() {
        byte[] notAnImage = "this is plain text, not a picture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // Not "image/jpeg as a fallback": presuming a type by omission is precisely what FR-002
        // forbids, and a wrong type here would travel into the image block as a declared fact.
        assertNull("an undetermined type is reported as absent, never guessed", ThumbnailMediaType.detect(notAnImage));
    }

    @Test
    public void emptyOrMissingBytesYieldNoType() {
        assertNull(ThumbnailMediaType.detect(null));
        assertNull(ThumbnailMediaType.detect(new byte[0]));
    }

    @Test
    public void everyTypeItReportsIsAnImageType() {
        for (byte[] bytes : new byte[][] { JPEG_HEADER, PNG_HEADER, GIF_HEADER }) {
            String detected = ThumbnailMediaType.detect(bytes);
            assertFalse("a detected type is never blank", detected == null || detected.isEmpty());
            assertTrue("and is always an image type, or it would not be emitted as an image: " + detected,
                    detected.startsWith("image/"));
        }
    }
}
