package iped.mcp.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpTestSupport;
import iped.mcp.integration.McpSessionRule;
import iped.mcp.protocol.McpError;
import iped.mcp.protocol.ToolDescriptor;

/**
 * The semantic tools against specs/009-semantic-search/contracts/mcp-tools.md (US5).
 *
 * <p>
 * The surface - the four tools listed with or without the capability, their content classes, the
 * refusal of an argument they do not declare - is checked on every build. What an answer carries
 * needs a case with semantic search prepared ({@code -Diped.mcp.test.semanticCase}); without one
 * those tests are skipped, and a skipped test is not a passing test.
 */
public class SemanticToolsContractTest {

    private static final String[] TOOLS = { "iped_semantic_status", "iped_semantic_search", "iped_semantic_similar",
            "iped_semantic_context" };

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    // ---------------------------------------------------------------------------------------------
    // Surface
    // ---------------------------------------------------------------------------------------------

    @Test
    public void theToolsAreListedEvenWithoutTheCapability() {
        // No case, no model, no semantic configuration: the agent must still find the tools and learn
        // from them why a case cannot answer, not infer it from their absence.
        Set<String> listed = new HashSet<>();
        for (ToolDescriptor tool : session.server().getDispatcher().getTools()) {
            listed.add(tool.getName());
        }
        for (String name : TOOLS) {
            assertTrue(name + " must always be listed", listed.contains(name));
        }
    }

    @Test
    public void theRankingToolsDeclareMetadataAndTheContextToolText() {
        // metadata, so that with text blocked the ordering still comes back without its excerpts;
        // the context tool is all text, so the boundary decides first.
        assertEquals("metadata", descriptor("iped_semantic_status").getContentClass());
        assertEquals("metadata", descriptor("iped_semantic_search").getContentClass());
        assertEquals("metadata", descriptor("iped_semantic_similar").getContentClass());
        assertEquals("text", descriptor("iped_semantic_context").getContentClass());
    }

    @Test
    public void thereIsNoDepthArgument() {
        // The agent pages; the ceiling is the server's (contracts/mcp-tools.md).
        for (String name : new String[] { "iped_semantic_search", "iped_semantic_similar" }) {
            for (ToolDescriptor.Parameter p : parameters(name)) {
                assertFalse(name + " must not take a depth", parameterName(p).contains("depth"));
            }
        }
    }

    @Test
    public void anUnknownArgumentIsRefused() {
        // Before the case is even looked up: an ignored argument would read as honoured.
        JsonNode error = session.expectError(McpError.INVALID_ARGUMENT, "iped_semantic_search", "case_id", "none",
                "question", "payment", "depth", 50);
        assertTrue(error.path("details").path("unknown").toString().contains("depth"));
        session.expectError(McpError.INVALID_ARGUMENT, "iped_semantic_context", "case_id", "none", "item_id", 1,
                "ordinal", 0, "text", "x");
    }

    @Test
    public void anUnknownModalityOrModeIsRefused() {
        String caseId = session.openCase(McpTestSupport.requireSemanticCase());
        session.expectError(McpError.INVALID_ARGUMENT, "iped_semantic_search", "case_id", caseId, "question", "x",
                "modality", "audio");
        session.expectError(McpError.INVALID_ARGUMENT, "iped_semantic_search", "case_id", caseId, "question", "x",
                "mode", "keyword");
    }

    // ---------------------------------------------------------------------------------------------
    // Answers
    // ---------------------------------------------------------------------------------------------

    @Test
    public void statusDeclaresWhatTheCaseOffersWithoutVectors() {
        String caseId = session.openCase(McpTestSupport.requireSemanticCase());
        JsonNode status = session.call("iped_semantic_status", "case_id", caseId);

        assertOrderingNotice(status);
        JsonNode text = status.path("modalities").path("text");
        assertTrue("the text modality of the semantic case must be available: " + text, text.path("available")
                .asBoolean());
        assertFalse(text.path("model").asText().isEmpty());
        assertEquals("local", text.path("source").asText());
        assertFalse("where the case was prepared", text.path("prepared_with").asText().isEmpty());
        assertFalse("build details (FR-014)", text.path("state").asText().isEmpty());
        assertTrue(text.path("params").isObject());
        assertTrue(text.path("covered").asLong() > 0);
        assertTrue(text.path("not_covered").isObject());
        assertTrue(status.path("max_depth").asInt() >= status.path("default_depth").asInt());
        JsonNode visual = status.path("modalities").path("visual");
        if (!visual.path("available").asBoolean()) {
            assertFalse("an unavailable modality says why", visual.path("reason").asText().isEmpty());
            assertFalse("and what to do", visual.path("remedy").asText().isEmpty());
        }
        assertNoForbiddenContent(status);
    }

    @Test
    public void searchReturnsARankingNotASet() {
        String caseId = session.openCase(McpTestSupport.requireSemanticCase());
        JsonNode answer = session.call("iped_semantic_search", "case_id", caseId, "question",
                "combinar um pagamento em dinheiro", "mode", "semantic", "page_size", 5);

        assertOrderingNotice(answer);
        assertNoForbiddenContent(answer);
        JsonNode text = answer.path("orderings").path("text");
        assertEquals("semantic", text.path("mode").asText());
        assertTrue(text.path("method").asText().matches("exact|approximate"));
        assertTrue(text.path("ceiling").asInt() > 0);
        assertTrue(text.path("returned").asInt() <= 5);
        assertFalse("no lexical count outside the hybrid mode", text.has("lexical_matches"));
        JsonNode coverage = text.path("coverage");
        assertEquals("coverage invariant: covered + not covered = scope", coverage.path("recorte_size").asLong(),
                coverage.path("covered").asLong() + sum(coverage.path("not_covered")));
        assertFalse(text.path("model").path("id").asText().isEmpty());
        assertTrue(answer.path("notices").isArray());
        assertTrue("the first page of a case has entries", text.path("entries").size() > 0);
        for (JsonNode entry : text.path("entries")) {
            assertEquals("relative", entry.path("proximity_kind").asText());
            assertTrue(entry.has("proximity"));
            assertTrue(entry.path("rank").asInt() >= 1);
            assertCitable(entry);
        }
    }

    @Test
    public void hybridDeclaresItsModeAndWhereEachEntryCameFrom() {
        String caseId = session.openCase(McpTestSupport.requireSemanticCase());
        JsonNode answer = session.call("iped_semantic_search", "case_id", caseId, "question",
                "pacote na portaria", "mode", "hybrid", "page_size", 10);

        assertOrderingNotice(answer);
        assertNoForbiddenContent(answer);
        JsonNode text = answer.path("orderings").path("text");
        assertEquals("hybrid", text.path("mode").asText());
        assertTrue("lexical_matches is declared in the hybrid mode", text.has("lexical_matches"));
        for (JsonNode entry : text.path("entries")) {
            assertEquals("combined_relative", entry.path("proximity_kind").asText());
            assertTrue("ranks shows the list the entry came from", entry.path("ranks").isObject());
            assertTrue(entry.path("ranks").has("semantic") || entry.path("ranks").has("lexical"));
        }
    }

    @Test
    public void pagingWalksTheCeilingListOnceAndDeclaresItsEnd() {
        String caseId = session.openCase(McpTestSupport.requireSemanticCase());
        List<Integer> ranks = new ArrayList<>();
        String cursor = null;
        JsonNode last;
        int pages = 0;
        do {
            JsonNode answer = session.call("iped_semantic_search", "case_id", caseId, "question", "documento",
                    "page_size", 7, "cursor", cursor);
            assertOrderingNotice(answer);
            last = answer.path("orderings").path("text");
            for (JsonNode entry : last.path("entries")) {
                ranks.add(entry.path("rank").asInt());
            }
            cursor = last.has("next_cursor") ? last.path("next_cursor").asText() : null;
            if (cursor != null) {
                assertFalse("ceiling_reached only on the last page", last.has("ceiling_reached"));
            }
            pages++;
        } while (cursor != null && pages < 500);

        assertFalse("the last page has no cursor", last.has("next_cursor"));
        assertTrue("the last page says whether the ceiling cut the list", last.has("ceiling_reached"));
        assertTrue("and what its end means", last.has(last.path("ceiling_reached").asBoolean() ? "ceiling_note"
                : "end_note"));
        for (int i = 0; i < ranks.size(); i++) {
            assertEquals("ranks run 1..n without gaps or repeats", i + 1, (int) ranks.get(i));
        }
    }

    @Test
    public void aCursorFromAnotherQuestionIsRefused() {
        String caseId = session.openCase(McpTestSupport.requireSemanticCase());
        JsonNode first = session.call("iped_semantic_search", "case_id", caseId, "question", "documento",
                "page_size", 1);
        String cursor = first.path("orderings").path("text").path("next_cursor").asText(null);
        org.junit.Assume.assumeTrue("the case has a single unit", cursor != null);
        session.expectError(McpError.INVALID_CURSOR, "iped_semantic_search", "case_id", caseId, "question",
                "outra pergunta", "page_size", 1, "cursor", cursor);
    }

    @Test
    public void anUnpreparedModalityIsARefusalAndNeverAnEmptyOrdering() {
        String caseId = session.openCase(McpTestSupport.requireSemanticCase());
        JsonNode status = session.call("iped_semantic_status", "case_id", caseId);
        org.junit.Assume.assumeFalse("the semantic case has the visual modality prepared",
                status.path("modalities").path("visual").path("available").asBoolean());

        JsonNode refusal = session.expectError(visualCode(status), "iped_semantic_search", "case_id", caseId,
                "question", "uma praia", "modality", "visual");
        assertEquals("visual", refusal.path("details").path("modality").asText());

        // with both, the text ordering comes back and the visual one is declared unavailable
        JsonNode both = session.call("iped_semantic_search", "case_id", caseId, "question", "uma praia",
                "modality", "both");
        assertOrderingNotice(both);
        assertTrue(both.path("orderings").path("text").path("entries").isArray());
        assertFalse(both.path("orderings").path("visual").path("available").asBoolean(true));
        assertFalse(both.path("orderings").path("visual").path("remedy").asText().isEmpty());
    }

    @Test
    public void similarLeavesTheReferenceOut() {
        String caseId = session.openCase(McpTestSupport.requireSemanticCase());
        JsonNode passage = firstEntryOfKind(caseId, "passage");
        org.junit.Assume.assumeTrue("no passage entry in the semantic case", passage != null);
        int reference = passage.path("item_id").asInt();

        JsonNode answer = session.call("iped_semantic_similar", "case_id", caseId, "item_id", reference);
        assertOrderingNotice(answer);
        assertNoForbiddenContent(answer);
        assertEquals(reference, answer.path("reference").path("item_id").asInt());
        for (JsonNode entry : answer.path("orderings").path("text").path("entries")) {
            assertFalse("the reference item must not rank itself", entry.path("item_id").asInt() == reference
                    && !"sequence".equals(entry.path("kind").asText()));
        }
    }

    @Test
    public void contextReturnsTheLiteralRangeAroundAnEntry() {
        String caseId = session.openCase(McpTestSupport.requireSemanticCase());
        JsonNode passage = firstEntryOfKind(caseId, "passage");
        org.junit.Assume.assumeTrue("no passage entry in the semantic case", passage != null);
        JsonNode excerpt = passage.path("excerpts").get(0);

        JsonNode context = session.call("iped_semantic_context", "case_id", caseId, "item_id",
                passage.path("item_id").asInt(), "ordinal", excerpt.path("ordinal").asInt());
        String text = context.path("text").asText();
        assertTrue("the context includes the excerpt itself", text.contains(excerpt.path("text").asText()));
        assertTrue(context.path("char_start").asInt() <= excerpt.path("char_start").asInt());
        assertTrue(context.path("char_end").asInt() >= excerpt.path("char_end").asInt());
        if (!context.path("truncated").asBoolean()) {
            assertEquals("stitched by offsets: exactly [char_start, char_end), overlap not repeated",
                    context.path("char_end").asInt() - context.path("char_start").asInt(), text.length());
        }

        session.expectError(McpError.ENTRY_NOT_FOUND, "iped_semantic_context", "case_id", caseId, "item_id",
                passage.path("item_id").asInt(), "ordinal", 99999);
    }

    @Test
    public void contextOfASequenceReturnsTheNeighbouringMessages() {
        String caseId = session.openCase(McpTestSupport.requireSemanticCase());
        JsonNode sequence = firstEntryOfKind(caseId, "sequence");
        org.junit.Assume.assumeTrue("no conversation in the semantic case", sequence != null);

        JsonNode context = session.call("iped_semantic_context", "case_id", caseId, "item_id",
                sequence.path("item_id").asInt(), "ordinal", sequence.path("ordinal").asInt());
        assertEquals("sequence", context.path("kind").asText());
        assertTrue(context.path("messages").size() >= sequence.path("sequence").path("messages").size());
        for (JsonNode message : context.path("messages")) {
            assertTrue(message.has("item_id"));
            assertTrue(message.has("text"));
        }
    }

    @Test
    public void aCaseWithoutTheCapabilityDeclaresItAndIsRefused() {
        String caseId = session.openCase(McpTestSupport.requireReferenceCase());
        JsonNode status = session.call("iped_semantic_status", "case_id", caseId);
        org.junit.Assume.assumeFalse("the reference case was processed with semantic search",
                status.path("modalities").path("text").path("available").asBoolean());
        assertOrderingNotice(status);
        assertEquals("CASE_WITHOUT_CAPABILITY", status.path("modalities").path("text").path("reason").asText());

        JsonNode refusal = session.expectError(McpError.SEMANTIC_UNAVAILABLE, "iped_semantic_search", "case_id",
                caseId, "question", "pagamento");
        assertEquals("CASE_WITHOUT_CAPABILITY", refusal.path("details").path("reason").asText());
        assertTrue("keyword search is named as unaffected", refusal.path("remedy").asText().contains("iped_search"));
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private JsonNode firstEntryOfKind(String caseId, String kind) {
        String cursor = null;
        int pages = 0;
        do {
            JsonNode answer = session.call("iped_semantic_search", "case_id", caseId, "question",
                    "conversa sobre pagamento e entrega", "mode", "semantic", "page_size", 50, "cursor", cursor);
            JsonNode text = answer.path("orderings").path("text");
            for (JsonNode entry : text.path("entries")) {
                if (kind.equals(entry.path("kind").asText())) {
                    return entry;
                }
            }
            cursor = text.has("next_cursor") ? text.path("next_cursor").asText() : null;
            pages++;
        } while (cursor != null && pages < 100);
        return null;
    }

    private static String visualCode(JsonNode status) {
        String reason = status.path("modalities").path("visual").path("reason").asText();
        return "MODALITY_NOT_PREPARED".equals(reason) ? McpError.MODALITY_NOT_PREPARED
                : "MODEL_INCOMPATIBLE".equals(reason) ? McpError.MODEL_INCOMPATIBLE : McpError.SEMANTIC_UNAVAILABLE;
    }

    /** FR-040: in every response, not only when proximity is low. */
    private static void assertOrderingNotice(JsonNode answer) {
        String notice = answer.path("ordering_notice").asText();
        assertTrue("every response carries the ordering notice: " + answer, notice.contains("not a set"));
        assertTrue(notice.contains("iped_search"));
    }

    /** Every text entry can be cited: item, and ordinal plus range of each excerpt or each message id. */
    private static void assertCitable(JsonNode entry) {
        assertTrue(entry.path("item_id").isInt());
        String kind = entry.path("kind").asText();
        if ("sequence".equals(kind)) {
            assertTrue(entry.has("ordinal"));
            for (JsonNode message : entry.path("sequence").path("messages")) {
                assertTrue(message.path("item_id").isInt());
                assertTrue(message.has("in_recorte"));
            }
        } else if ("passage".equals(kind) || "transcript".equals(kind)) {
            assertTrue(entry.has("ordinal"));
            assertTrue(entry.path("excerpts").size() > 0);
            for (JsonNode excerpt : entry.path("excerpts")) {
                assertTrue(excerpt.has("ordinal"));
                assertTrue(excerpt.path("char_end").asInt() > excerpt.path("char_start").asInt());
            }
        }
    }

    /**
     * No total and no set semantics (FR-007); no vector, fingerprint or raw score (FR-025). A vector
     * would show up as a long array of numbers, whatever key it hid under.
     */
    private static void assertNoForbiddenContent(JsonNode node) {
        walk(node, "$");
    }

    private static void walk(JsonNode node, String path) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase();
                assertFalse("no total at " + path + "." + field.getKey(), key.contains("total"));
                assertFalse("no vector at " + path + "." + field.getKey(), key.contains("vector")
                        || key.equals("v") || key.contains("fingerprint") || key.contains("embedding"));
                assertFalse("no raw score at " + path + "." + field.getKey(), key.contains("score")
                        || key.contains("cosine") || key.contains("similarity_raw"));
                walk(field.getValue(), path + "." + field.getKey());
            }
        } else if (node.isArray()) {
            int numbers = 0;
            for (JsonNode element : node) {
                if (element.isNumber()) {
                    numbers++;
                }
            }
            assertFalse("an array of " + numbers + " numbers at " + path + " looks like a vector", numbers >= 16);
            int i = 0;
            for (JsonNode element : node) {
                walk(element, path + "[" + i++ + "]");
            }
        }
    }

    private static long sum(JsonNode counts) {
        long total = 0;
        for (JsonNode n : counts) {
            total += n.asLong();
        }
        return total;
    }

    private ToolDescriptor descriptor(String name) {
        for (ToolDescriptor tool : session.server().getDispatcher().getTools()) {
            if (tool.getName().equals(name)) {
                return tool;
            }
        }
        throw new AssertionError(name + " is not registered");
    }

    private List<ToolDescriptor.Parameter> parameters(String name) {
        List<ToolDescriptor.Parameter> out = descriptor(name).getParameters();
        assertNotNull(out);
        return out;
    }

    private static String parameterName(ToolDescriptor.Parameter p) {
        // the listing entry is the public view of a parameter
        try {
            java.lang.reflect.Field f = ToolDescriptor.Parameter.class.getDeclaredField("name");
            f.setAccessible(true);
            return (String) f.get(p);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
