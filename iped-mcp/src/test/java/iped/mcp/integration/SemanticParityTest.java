package iped.mcp.integration;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;

import iped.engine.config.ConfigurationManager;
import iped.engine.data.IPEDSource;
import iped.engine.search.IPEDSearcher;
import iped.mcp.McpTestSupport;
import iped.search.SearchResult;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.model.EmbedderFactory;
import iped.semantic.search.CaseItems;
import iped.semantic.search.MultiCaseSearch;
import iped.semantic.search.Ordering;
import iped.semantic.search.OrderingEntry;
import iped.semantic.search.SemanticRequest;
import iped.semantic.search.SemanticRequest.Target;

/**
 * SC-011: for the same question over the same scope, the ordering the agent receives is the
 * ordering the examiner sees.
 *
 * <p>
 * The UI side is built here exactly as {@code iped.app.ui.semantic.SemanticSession} builds it - one
 * {@link MultiCaseSearch} over the open case, the installation's configuration, a fresh
 * {@link EmbedderFactory} - and its scope is what the UI's table holds after the same query, which
 * {@link IPEDSearcher} computes. The MCP side is paged to the end through {@code tools/call}.
 *
 * <p>
 * The questions are ten of the evaluation gabarito (T005, {@code iped-semantic}'s
 * {@code eval/questions.json}): the first four conceptual, three cross-lingual and three mixed, in
 * the gabarito's order. Parity does not depend on relevance, only on both sides asking the same
 * thing, so the case need not be the eval case.
 */
public class SemanticParityTest {

    private static final String GABARITO = "../iped-semantic/src/test/resources/eval/questions.json";

    private static final String[] QUESTIONS = gabarito();

    private static String[] gabarito() {
        java.nio.file.Path p = java.nio.file.Paths.get(System.getProperty("basedir", "."), GABARITO);
        try {
            JsonNode doc = new com.fasterxml.jackson.databind.ObjectMapper().readTree(p.toFile());
            java.util.Map<String, Integer> wanted = new java.util.LinkedHashMap<>();
            wanted.put("conceptual", 4);
            wanted.put("crosslingual", 3);
            wanted.put("mixed", 3);
            List<String> out = new ArrayList<>();
            for (JsonNode q : doc.path("questions")) {
                String type = q.path("type").asText();
                int left = wanted.getOrDefault(type, 0);
                if (left > 0) {
                    wanted.put(type, left - 1);
                    out.add(q.path("question").asText());
                }
            }
            return out.toArray(new String[0]);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("the gabarito is read from " + p, e);
        }
    }

    private static final String[] SCOPES = { "*:*", "pagamento OR pacote OR entrega OR portaria" };

    private final TemporaryFolder temp = new TemporaryFolder();
    private final McpSessionRule session = new McpSessionRule(temp);

    @Rule
    public RuleChain chain = RuleChain.outerRule(temp).around(session);

    @Test
    public void theAgentAndTheExaminerSeeTheSameOrdering() throws Exception {
        String caseId = session.openCase(McpTestSupport.requireSemanticCase());
        IPEDSource source = session.server().getSession().getCaseRegistry().require(caseId).getSource();
        SemanticSearchConfig cfg = ConfigurationManager.get().findObject(SemanticSearchConfig.class);
        org.junit.Assume.assumeTrue("the installation has no semantic configuration", cfg != null);

        List<MultiCaseSearch.CaseRef> refs = new ArrayList<>();
        refs.add(new MultiCaseSearch.CaseRef(source.getCaseDir().getName(), source.getModuleDir(),
                CaseItems.of(source)));
        try (MultiCaseSearch ui = new MultiCaseSearch(refs, cfg, new EmbedderFactory(cfg))) {
            int compared = 0;
            for (String scopeQuery : SCOPES) {
                BitSet scope = tableOf(source, scopeQuery);
                for (TextMode mode : TextMode.values()) {
                    for (String question : QUESTIONS) {
                        Ordering expected = ui.search(Target.TEXT,
                                i -> SemanticRequest.builder().target(Target.TEXT).query(question).mode(mode)
                                        .scope(scope).build(cfg)).get(0);
                        List<String> viaMcp = new ArrayList<>();
                        long recorte = mcp(caseId, question, mode, scopeQuery, viaMcp);

                        String what = "'" + question + "', " + mode + ", scope " + scopeQuery;
                        assertEquals("scope of " + what, expected.getCoverage().getRecorteSize(), recorte);
                        assertEquals("ordering of " + what, render(expected), viaMcp);
                        compared++;
                    }
                }
            }
            assertEquals(QUESTIONS.length * SCOPES.length * TextMode.values().length, compared);
        }
    }

    /** What the UI's table holds after this query: the scope it hands to the searcher. */
    private static BitSet tableOf(IPEDSource source, String query) throws Exception {
        SearchResult result = new IPEDSearcher(source, query).search();
        BitSet ids = new BitSet();
        for (int id : result.getIds()) {
            ids.set(id);
        }
        return ids;
    }

    /** Pages the MCP ordering to its end; returns the scope size it declared. */
    private long mcp(String caseId, String question, TextMode mode, String scopeQuery, List<String> out) {
        String cursor = null;
        long recorte = -1;
        int pages = 0;
        do {
            JsonNode answer = session.call("iped_semantic_search", "case_id", caseId, "question", question, "mode",
                    mode.name().toLowerCase(), "query", scopeQuery, "page_size", 200, "cursor", cursor);
            JsonNode text = answer.path("orderings").path("text");
            recorte = text.path("coverage").path("recorte_size").asLong();
            for (JsonNode entry : text.path("entries")) {
                out.add(entry.path("rank").asInt() + " " + entry.path("kind").asText() + " "
                        + entry.path("item_id").asInt() + "#" + entry.path("ordinal").asInt(0) + " "
                        + entry.path("proximity").asDouble());
            }
            cursor = text.has("next_cursor") ? text.path("next_cursor").asText() : null;
            pages++;
        } while (cursor != null && pages < 100);
        return recorte;
    }

    private static List<String> render(Ordering ordering) {
        List<String> out = new ArrayList<>();
        for (OrderingEntry e : ordering.getAll()) {
            boolean hasOrdinal = e.getSequence() != null || !e.getExcerpts().isEmpty();
            out.add(e.getRank() + " " + e.getKind() + " " + e.getItemId() + "#" + (hasOrdinal ? e.getOrdinal() : 0)
                    + " " + Math.round(e.getProximity() * 10000d) / 10000d);
        }
        return out;
    }
}
