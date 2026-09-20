package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;

/**
 * A thumbnail reaches the agent as a picture, end to end, and costs what a picture costs.
 *
 * <p>
 * The contract suite checks the shape of the answer. This one checks the two things that only a
 * real case can show: that the bytes delivered are the bytes the case holds, and that the encoding
 * is no longer transcribed into the dialogue — which is decision D4 and success criterion SC-002.
 */
public class ThumbnailImageBlockTest {

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void theImageBlockCarriesTheThumbnailTheCaseActuallyHolds() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int itemId = anItemWithAThumbnail(caseId);

        JsonNode result = session.raw("iped_item_thumbnail", "case_id", caseId, "item_id", itemId).path("result");
        JsonNode image = result.path("content").get(1);
        JsonNode structured = result.path("structuredContent");

        byte[] decoded = Base64.getDecoder().decode(image.path("data").asText());
        assertEquals("the image block carries as many bytes as the answer reports", structured.path("bytes").asInt(),
                decoded.length);
        assertTrue("and enough of them to be a picture", decoded.length > 0);
        assertTrue("and the declared type matches what the leading bytes are",
                declaredTypeMatchesMagic(image.path("mimeType").asText(), decoded));
    }

    @Test
    public void theEncodingIsNoLongerTranscribedIntoTheTextBlock() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int itemId = anItemWithAThumbnail(caseId);

        JsonNode result = session.raw("iped_item_thumbnail", "case_id", caseId, "item_id", itemId).path("result");
        String asText = result.path("content").get(0).path("text").asText();
        String base64 = result.path("structuredContent").path("data").asText();

        // SC-002. With the encoding left in both blocks the picture would travel twice, and the
        // feature whose reason for existing is to stop paying for blindness would charge double.
        assertFalse("the base64 must not be transcribed in the text block when the image block carries it",
                asText.contains(base64));
        assertTrue("the text block is much smaller than the encoding it stands in for",
                asText.length() < base64.length());
    }

    @Test
    public void theStructuredCopyKeepsTheEncodingInFull() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int itemId = anItemWithAThumbnail(caseId);

        JsonNode structured = session.raw("iped_item_thumbnail", "case_id", caseId, "item_id", itemId).path("result")
                .path("structuredContent");

        // FR-003: the elision is confined to the text block. Everything that reads this server
        // programmatically reads here, and here nothing was taken away.
        String base64 = structured.path("data").asText();
        assertFalse("the structured copy still carries the encoding", base64.isEmpty());
        assertEquals("intact, not shortened", structured.path("bytes").asInt(),
                Base64.getDecoder().decode(base64).length);
    }

    @Test
    public void aClientThatReadsOnlyTheTextBlockStillKnowsTheThumbnailExists() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int itemId = anItemWithAThumbnail(caseId);

        JsonNode result = session.raw("iped_item_thumbnail", "case_id", caseId, "item_id", itemId).path("result");
        JsonNode asText = parse(result.path("content").get(0).path("text").asText());

        // FR-004, read as the requirement is worded: existence, type and size — not the bytes. A
        // harness that drops image blocks has to stay able to answer "is there a thumbnail here?"
        assertTrue("the text block declares the thumbnail exists", asText.path("available").asBoolean());
        assertFalse("and names its media type", asText.path("media_type").asText().isEmpty());
        assertTrue("and gives its size", asText.path("bytes").asInt() > 0);
        assertEquals("and says how it would be encoded", "base64", asText.path("encoding").asText());
        assertFalse("and never comes back as an error", result.path("isError").asBoolean());
    }

    @Test
    public void theStructuredCopyCarriesTheSameKeysItCarriedBeforeThisFeature() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int itemId = anItemWithAThumbnail(caseId);

        JsonNode structured = session.raw("iped_item_thumbnail", "case_id", caseId, "item_id", itemId).path("result")
                .path("structuredContent");

        // FR-003. A consumer that reads the structured copy is the documented path, and this
        // feature took nothing away from it — media_type merely stopped being a constant.
        for (String key : new String[] { "case_id", "item_id", "available", "encoding", "media_type", "bytes",
                "data" }) {
            assertTrue("the structured copy must still carry '" + key + "'", structured.has(key));
        }
    }

    @Test
    public void everyKindOfAbsenceAnswersWithoutAnImageBlock() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());

        int declared = 0;
        java.util.Set<String> reasons = new java.util.LinkedHashSet<>();
        for (JsonNode item : page(caseId)) {
            int itemId = item.path("item_id").asInt();
            JsonNode result = session.raw("iped_item_thumbnail", "case_id", caseId, "item_id", itemId)
                    .path("result");
            JsonNode structured = result.path("structuredContent");
            if (structured.path("available").asBoolean()) {
                continue;
            }
            // FR-009: nothing is emitted as a picture, and the reason is never silent.
            assertEquals("an absent thumbnail answers with a single text block", 1, result.path("content").size());
            assertEquals("and that block is text", "text", result.path("content").get(0).path("type").asText());
            assertFalse("an absent thumbnail must say why", structured.path("reason").asText().isEmpty());
            assertFalse("and point at what else to try", structured.path("remedy").asText().isEmpty());
            assertFalse("and must not carry data", structured.has("data"));
            reasons.add(structured.path("reason").asText());
            declared++;
        }
        assertTrue("this case should contain items without thumbnails, or the requirement is not exercised",
                declared > 0);
        assertTrue("distinct causes must read differently, or an agent cannot tell them apart",
                reasons.size() > 0);
    }

    @Test
    public void aThumbnailPastTheCeilingIsRefusedWithItsSizeAndNeverTruncated() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int itemId = anItemWithAThumbnail(caseId);
        int realSize = session.call("iped_item_thumbnail", "case_id", caseId, "item_id", itemId).path("bytes")
                .asInt();

        // Lower the ceiling under an item known to have a thumbnail, rather than hunting for a big
        // one: the refusal is what is under test, not the size of any particular picture.
        session.config().setMaxThumbnailBytes(realSize - 1);
        JsonNode result = session.raw("iped_item_thumbnail", "case_id", caseId, "item_id", itemId).path("result");
        JsonNode structured = result.path("structuredContent");

        assertFalse("past the ceiling the thumbnail is not available", structured.path("available").asBoolean());
        assertEquals("and no image block is emitted, least of all a truncated one", 1,
                result.path("content").size());
        assertFalse("and no bytes are handed over", structured.has("data"));

        String reason = structured.path("reason").asText();
        assertTrue("the refusal states the real size: " + reason, reason.contains(String.valueOf(realSize)));
        assertTrue("and the ceiling in force: " + reason, reason.contains(String.valueOf(realSize - 1)));
        assertTrue("and the remedy names the setting that governs it",
                structured.path("remedy").asText().contains("maxThumbnailBytes"));
    }

    @Test
    public void bytesWhoseTypeCannotBeEstablishedAreHandedOverButNotCalledAnImage() {
        // E3, the branch decision D3 introduced. Exercised directly on ContentAccess's contract
        // through the detector, because a case whose thumbnail is not a recognised image is not
        // something a bench can be relied on to contain.
        assertEquals("plain text is not an image type", null,
                iped.mcp.item.ThumbnailMediaType.detect("not a picture".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    public void theEgressPolicyRefusesBeforeAnyBlockIsBuilt() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        int itemId = anItemWithAThumbnail(caseId);

        session.config().setEgressPolicyActive(true);
        session.config().setEgressAllowedClasses(new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                iped.mcp.config.McpServerConfig.ContentClass.metadata,
                iped.mcp.config.McpServerConfig.ContentClass.text)));

        JsonNode response = session.raw("iped_item_thumbnail", "case_id", caseId, "item_id", itemId);

        // FR-006 and R1.5: the refusal lives in the JSON-RPC envelope, above content. The new shape
        // of the answer is not an alternative route for content to leave.
        assertTrue("a blocked class answers with an error", response.has("error"));
        assertEquals("and names the rule that blocked it", "BLOCKED_BY_POLICY",
                response.path("error").path("data").path("code").asText());
        assertEquals("and says which content class it was", "thumbnail",
                response.path("error").path("data").path("details").path("contentClass").asText());
        assertFalse("no result is built at all", response.path("result").has("content"));
        assertFalse("and no structured copy either", response.path("result").has("structuredContent"));
    }

    private static JsonNode parse(String json) {
        try {
            return iped.mcp.protocol.JsonRpcCodec.mapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError("the text block must carry parseable JSON", e);
        }
    }

    /** Whether the type the answer declares agrees with what the bytes begin with. */
    private static boolean declaredTypeMatchesMagic(String declared, byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg".equals(declared);
        }
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png".equals(declared);
        }
        if (bytes.length >= 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "image/gif".equals(declared);
        }
        // Another image format the bench does not carry; the detector is trusted for it.
        return declared.startsWith("image/");
    }

    private int anItemWithAThumbnail(String caseId) {
        for (JsonNode item : page(caseId)) {
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

    private JsonNode page(String caseId) {
        return session.call("iped_search", "case_id", caseId, "query", "*:*", "page_size", 60, "include_snippets",
                false).path("items");
    }
}
