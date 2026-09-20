package iped.mcp.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.integration.McpSessionRule;

/**
 * The shape of a tool result that carries a thumbnail (FR-001 to FR-005).
 *
 * <p>
 * These assertions are the contract in {@code contracts/tool-result-image-block.md}, C1 to C7, and
 * they are worth stating in a suite of their own because the shape of the answer is the whole
 * feature. A thumbnail that arrives as base64 inside text is not a picture, however capable of
 * vision the model is: it costs context proportional to the image and shows nothing.
 *
 * <p>
 * Everything here goes through {@code tools/call} and reads the raw {@code result}, because
 * {@link McpSessionRule#call} returns only {@code structuredContent} — which is exactly the part
 * this feature does not change, and therefore the part that cannot prove it works.
 */
public class ImageBlockContractTest {

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void aThumbnailArrivesAsAnImageBlockBesideTheTextBlock() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        JsonNode result = thumbnailOfAnItemThatHasOne(caseId);

        JsonNode content = result.path("content");
        assertEquals("a readable thumbnail answers with a text block and an image block", 2, content.size());

        // C1: position 0 never changes type. Clients that read content[0].text exist.
        assertEquals("the first block is always text", "text", content.get(0).path("type").asText());
        // C2: the image block follows it.
        assertEquals("the image block comes second", "image", content.get(1).path("type").asText());
        // C7.
        assertFalse("a delivered thumbnail is not an error", result.path("isError").asBoolean());
    }

    @Test
    public void theImageBlockCarriesExactlyWhatTheStructuredCopySays() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        JsonNode result = thumbnailOfAnItemThatHasOne(caseId);

        JsonNode image = result.path("content").get(1);
        JsonNode structured = result.path("structuredContent");

        // C3 and C4. Re-encoding the bytes would be a second chance to disagree with what the
        // structured copy reports, and the structured copy is what an examiner may end up citing.
        assertEquals("the image block carries the same bytes as the structured copy",
                structured.path("data").asText(), image.path("data").asText());
        assertEquals("and declares the same media type", structured.path("media_type").asText(),
                image.path("mimeType").asText());
        assertTrue("the declared media type is an image type",
                image.path("mimeType").asText().startsWith("image/"));
    }

    @Test
    public void theTextBlockStillSaysTheThumbnailExistsWithItsTypeAndSize() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        JsonNode result = thumbnailOfAnItemThatHasOne(caseId);

        JsonNode asText = parse(result.path("content").get(0).path("text").asText());

        // C6: this is FR-004 for a client that reads nothing but the text block.
        assertTrue("the text block declares the thumbnail exists", asText.path("available").asBoolean());
        assertFalse("and its media type", asText.path("media_type").asText().isEmpty());
        assertTrue("and its size in bytes", asText.path("bytes").asInt() > 0);
        assertFalse("and how it is encoded", asText.path("encoding").asText().isEmpty());
    }

    @Test
    public void noOtherToolAcquiresAnImageBlock() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int itemId = anItemWithAThumbnail(caseId);

        // FR-005. The gate is the declared content class, so this is what proves that nothing else
        // changed shape as a side effect — including iped_item_content, whose exclusion is D2.
        for (String tool : new String[] { "iped_item_metadata", "iped_item_text", "iped_item_content" }) {
            JsonNode result = session.raw(tool, "case_id", caseId, "item_id", itemId).path("result");
            assertEquals(tool + " must keep answering with a single text block", 1, result.path("content").size());
            assertEquals(tool + " must keep answering with text", "text",
                    result.path("content").get(0).path("type").asText());
        }
    }

    /** The raw {@code result} of a thumbnail call on an item that actually has one. */
    private JsonNode thumbnailOfAnItemThatHasOne(String caseId) {
        return session.raw("iped_item_thumbnail", "case_id", caseId, "item_id", anItemWithAThumbnail(caseId))
                .path("result");
    }

    /**
     * An item of this case whose thumbnail is readable and of a known image type.
     *
     * <p>
     * Asked of the case rather than hard-coded: item ids are local to a case, and a suite that
     * assumed one would fail on any bench but the one it was written against.
     */
    private int anItemWithAThumbnail(String caseId) {
        JsonNode page = session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 60,
                "include_snippets", false);
        for (JsonNode item : page.path("items")) {
            int itemId = item.path("item_id").asInt();
            JsonNode thumb = session.call("iped_item_thumbnail", "case_id", caseId, "item_id", itemId);
            if (thumb.path("available").asBoolean() && thumb.path("media_type").asText().startsWith("image/")) {
                return itemId;
            }
        }
        throw new AssertionError("No item in this case has a readable thumbnail, so this suite proves nothing. "
                + "Build the bench with a profile that generates thumbnails; fastmode does not — see "
                + "specs/008-thumbnail-image-block/quickstart.md.");
    }

    private static JsonNode parse(String json) {
        try {
            return iped.mcp.protocol.JsonRpcCodec.mapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError("the text block must carry parseable JSON", e);
        }
    }
}
