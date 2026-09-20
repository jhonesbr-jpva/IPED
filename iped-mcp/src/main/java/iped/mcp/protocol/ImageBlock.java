package iped.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the {@code image} content block that carries a thumbnail, and the elided text rendering
 * that goes beside it.
 *
 * <p>
 * <b>Why the gate is the declared content class and not the tool's name.</b> The egress policy
 * already decides by content class: a tool says what kind of evidence content it returns, and the
 * dispatcher blocks or allows on that declaration. Reusing the same marking to decide the shape of
 * the answer is what gives FR-005 for free — no tool can acquire an image block as a side effect,
 * because acquiring one would mean having declared {@code thumbnail}, which is a deliberate act. A
 * second marking, or a list of tool names, would be a second place to disagree with the first, and
 * that disagreement is exactly the failure FR-005 describes.
 *
 * <p>
 * <b>Why the text block gives up the encoding.</b> The image block and the structured copy both
 * carry the bytes. Leaving them in the text rendering as well would send the picture twice — once
 * unreadable and once readable — and a feature whose reason for existing is that looking at an
 * image costs context proportional to the image would then charge double for it. So, and only when
 * an image block is actually emitted, the {@code data} value in the text rendering is replaced by a
 * note saying where the bytes are. Everything a client needs to know that the thumbnail exists —
 * {@code available}, {@code media_type}, {@code bytes}, {@code encoding} — stays exactly where it
 * was, which is what FR-004 requires, and {@code structuredContent} is untouched.
 */
public final class ImageBlock {

    /**
     * The content class whose results are delivered as pictures. Raw content is deliberately not
     * included: it has its own egress class, its own purpose — establishing what a file is — and it
     * is truncated by design, and a truncated image is what this feature refuses to emit.
     */
    private static final String THUMBNAIL_CLASS = "thumbnail";

    private static final String IMAGE_PREFIX = "image/";

    /** Stands in for the encoding in the text rendering, and says where the encoding went. */
    static final String ELIDED_NOTE = "<delivered as an image block in this same response; "
            + "structuredContent carries the full base64>";

    private ImageBlock() {
        // Utility class.
    }

    /**
     * Whether this result is to be delivered as a picture.
     *
     * <p>
     * Every condition has to hold: the tool declared the thumbnail class, the thumbnail is present,
     * it has bytes, and its media type was established as an image type. A missing type is not a
     * reason to guess one — it is a reason not to emit (FR-002).
     *
     * @param contentClass
     *            the content class the tool declared, or {@code null} when it declared none
     */
    public static boolean isEmittable(JsonNode payload, String contentClass) {
        if (!THUMBNAIL_CLASS.equals(contentClass) || payload == null || !payload.isObject()) {
            return false;
        }
        if (!payload.path("available").asBoolean()) {
            return false;
        }
        String data = payload.path("data").asText("");
        String mediaType = payload.path("media_type").asText("");
        return !data.isEmpty() && mediaType.startsWith(IMAGE_PREFIX);
    }

    /**
     * Builds the image block from a payload {@link #isEmittable} accepted.
     *
     * <p>
     * The bytes and the type are taken from the payload as they are. Re-encoding them would be a
     * second chance to differ from what the structured copy reports, and the structured copy is
     * what an examiner may cite.
     */
    public static ObjectNode of(JsonNode payload) {
        ObjectNode image = JsonRpcCodec.mapper().createObjectNode();
        image.put("type", "image");
        image.put("data", payload.path("data").asText());
        image.put("mimeType", payload.path("media_type").asText());
        return image;
    }

    /**
     * The payload as it is rendered into the text block: the same object with the {@code data}
     * value replaced by {@link #ELIDED_NOTE}.
     *
     * <p>
     * Only the top-level {@code data} key is touched, and the original payload is not modified —
     * the caller still serializes it whole into {@code structuredContent}.
     */
    public static JsonNode elided(JsonNode payload) {
        ObjectNode copy = payload.deepCopy();
        copy.put("data", ELIDED_NOTE);
        return copy;
    }
}
