package iped.mcp.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpServerMain;
import iped.mcp.McpTestSupport;
import iped.mcp.audit.AuditRecord;
import iped.mcp.audit.AuditTrail;
import iped.mcp.config.McpServerConfig;
import iped.mcp.config.McpServerConfig.ContentClass;
import iped.mcp.protocol.JsonRpcCodec;
import iped.mcp.protocol.McpError;

/**
 * The egress policy applied to the semantic tools, excerpt by excerpt (US5, story 5 scenario 2).
 *
 * <p>
 * With text outside the allowed classes, the ordering still comes back - ranks, items, offsets,
 * coverage - and every excerpt is withheld in place, with the rule that withheld it. The strongest
 * form of the check is the one done here: the literal texts of an unrestricted answer are collected
 * first, and none of them may appear anywhere in the restricted one, under any key.
 */
public class SemanticEgressTest {

    private static final String QUESTION = "conversa sobre pagamento e entrega";

    /** The fields an audit record had before this feature. The semantic tools add none. */
    private static final Set<String> AUDIT_FIELDS = new HashSet<>(Arrays.asList("seq", "timestamp", "operator",
            "sessionId", "caseId", "caseBinding", "operation", "parameters", "refSeq", "resultVolume", "outcome",
            "priorState", "blockedByPolicy", "prevHash", "hash"));

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void withTextBlockedEveryExcerptIsWithheldAndNoTextLeaves() throws Exception {
        File semanticCase = McpTestSupport.requireSemanticCase();

        List<String> texts = new ArrayList<>();
        try (McpServerMain open = new McpServerMain(McpTestSupport.configWithTempAudit(temp.newFolder("open")))) {
            String caseId = openCase(open, semanticCase);
            JsonNode answer = result(call(open, "iped_semantic_search", "case_id", caseId, "question", QUESTION,
                    "page_size", 50));
            collectTexts(answer.path("orderings").path("text").path("entries"), texts);
        }
        org.junit.Assume.assumeFalse("the unrestricted answer carried no text to withhold", texts.isEmpty());

        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.newFolder("restricted"));
        config.setEgressPolicyActive(true);
        config.setEgressAllowedClasses(new LinkedHashSet<>(Arrays.asList(ContentClass.metadata)));
        File trail;
        try (McpServerMain restricted = new McpServerMain(config)) {
            trail = restricted.getSession().getAuditTrail().getFile();
            String caseId = openCase(restricted, semanticCase);
            JsonNode response = call(restricted, "iped_semantic_search", "case_id", caseId, "question", QUESTION,
                    "page_size", 50);
            JsonNode answer = result(response);

            JsonNode entries = answer.path("orderings").path("text").path("entries");
            assertTrue("the ordering itself still comes back", entries.size() > 0);
            assertTrue("and still says what it is", answer.path("ordering_notice").asText().contains("not a set"));
            for (JsonNode entry : entries) {
                if (entry.has("excerpts") || entry.has("sequence")) {
                    JsonNode withheld = entry.path("excerpt_withheld");
                    assertEquals("each text entry says which rule withheld it: " + entry, "allowedClasses",
                            withheld.path("policy_rule").asText());
                    assertFalse(withheld.path("reason").asText().isEmpty());
                }
                for (JsonNode excerpt : entry.path("excerpts")) {
                    assertFalse("no excerpt text", excerpt.has("text"));
                    assertTrue("the citation stays: offsets are not content", excerpt.has("char_start"));
                }
                for (JsonNode message : entry.path("sequence").path("messages")) {
                    assertFalse("no message text", message.has("text"));
                    assertTrue(message.has("text_withheld"));
                }
            }
            // nowhere, under any key, in either rendering of the answer
            String whole = response.toString();
            for (String text : texts) {
                if (text.trim().length() >= 12) {
                    assertFalse("excerpt text leaked: " + text, whole.contains(JsonRpcCodec.mapper()
                            .writeValueAsString(text).replaceAll("^\"|\"$", "")));
                }
            }

            // the context tool is text through and through: refused at the boundary
            JsonNode refusal = call(restricted, "iped_semantic_context", "case_id", caseId, "item_id",
                    entries.get(0).path("item_id").asInt(), "ordinal", entries.get(0).path("ordinal").asInt());
            assertEquals(McpError.BLOCKED_BY_POLICY, refusal.path("error").path("data").path("code").asText());
        }

        boolean sawSearch = false;
        for (AuditRecord record : AuditTrail.read(trail)) {
            Set<String> fields = new HashSet<>();
            record.toNode().fieldNames().forEachRemaining(fields::add);
            fields.removeAll(AUDIT_FIELDS);
            assertTrue("the audit record gains no field: " + fields, fields.isEmpty());
            if ("iped_semantic_search".equals(record.getOperation())
                    && record.getOutcome() == AuditRecord.Outcome.STARTED) {
                // the write-ahead entry carries the arguments; the closing one points back to it
                sawSearch = true;
                assertEquals("the question is recorded as the argument it is", QUESTION,
                        record.getParameters().get("question"));
            }
        }
        assertTrue("the semantic search was audited", sawSearch);
    }

    private static void collectTexts(JsonNode entries, List<String> out) {
        for (JsonNode entry : entries) {
            for (JsonNode excerpt : entry.path("excerpts")) {
                out.add(excerpt.path("text").asText());
            }
            for (JsonNode message : entry.path("sequence").path("messages")) {
                out.add(message.path("text").asText());
            }
        }
    }

    private static String openCase(McpServerMain server, File casePath) throws Exception {
        return result(call(server, "iped_open_case", "case_path", casePath.getAbsolutePath())).path("case_id")
                .asText();
    }

    private static JsonNode result(JsonNode response) {
        if (response.has("error")) {
            throw new AssertionError(response.path("error").toString());
        }
        return response.path("result").path("structuredContent");
    }

    private static JsonNode call(McpServerMain server, String tool, Object... keyValues) throws Exception {
        ObjectNode arguments = JsonRpcCodec.mapper().createObjectNode();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value instanceof Integer) {
                arguments.put(String.valueOf(keyValues[i]), (Integer) value);
            } else {
                arguments.put(String.valueOf(keyValues[i]), String.valueOf(value));
            }
        }
        ObjectNode params = JsonRpcCodec.mapper().createObjectNode();
        params.put("name", tool);
        params.set("arguments", arguments);
        ObjectNode request = JsonRpcCodec.mapper().createObjectNode();
        request.put("jsonrpc", JsonRpcCodec.VERSION);
        request.put("id", 1);
        request.put("method", "tools/call");
        request.set("params", params);
        try (JsonRpcCodec codec = new JsonRpcCodec(new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream())) {
            return server.getDispatcher().dispatch(request, codec);
        }
    }
}
