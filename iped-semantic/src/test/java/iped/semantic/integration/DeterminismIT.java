package iped.semantic.integration;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import iped.semantic.FakeEmbedder;
import iped.semantic.SyntheticCase;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.search.Ordering;
import iped.semantic.search.OrderingEntry;
import iped.semantic.search.SemanticRequest;
import iped.semantic.search.SemanticSearcher;
import iped.semantic.task.TextItem;
import iped.semantic.task.TextPreparation;
import iped.utils.UTF8Properties;

/**
 * SC-005: the same question on the same case gives the same ordering, also after closing and
 * reopening the case, with the exact method (small scope) and the approximate one (large scope).
 * Hermetic: the case is prepared in the test JVM by the processing's own code.
 */
public class DeterminismIT {

    private static final FakeEmbedder E = new FakeEmbedder();
    private static SyntheticCase exactCase;
    private static SyntheticCase approxCase;

    private static SyntheticCase build(String name, SemanticSearchConfig cfg) throws Exception {
        SyntheticCase c = new SyntheticCase(name, cfg);
        TextPreparation p = c.open(E, null);
        String[] topics = { "pagamento", "entrega", "viagem", "contrato", "ameaça", "portaria", "carga", "dinheiro" };
        List<TextItem> items = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            // many near-ties on purpose: the tie-break must decide, deterministically
            String t = "Documento " + (i % 7) + " sobre " + topics[i % topics.length] + " e "
                    + topics[(i / 3) % topics.length] + ". Texto de apoio número " + (i % 5) + ".";
            items.add(c.doc(i, t));
        }
        c.prepareAll(p, items);
        p.finish();
        return c;
    }

    @BeforeClass
    public static void prepare() throws Exception {
        exactCase = build("det-exact", new SemanticSearchConfig());
        SemanticSearchConfig approx = new SemanticSearchConfig();
        UTF8Properties props = new UTF8Properties();
        props.setProperty("exactSearchMaxItems", "10"); // 300 items: approximate
        approx.processProperties(props);
        approxCase = build("det-approx", approx);
    }

    private static List<String> signature(Ordering o) {
        List<String> out = new ArrayList<>();
        for (OrderingEntry e : o.getAll()) {
            out.add(e.getItemId() + "/" + e.getOrdinal() + "/" + e.getProximity());
        }
        return out;
    }

    private static Ordering ask(SyntheticCase c, String q, BitSet scope) throws Exception {
        try (SemanticSearcher s = c.searcher(E)) {
            SemanticRequest r = SemanticRequest.builder().query(q).mode(TextMode.SEMANTIC).scope(scope).build(c.config);
            return s.search(r).get(0);
        }
    }

    @Test
    public void sameQuestionSameListExact() throws Exception {
        Ordering a = ask(exactCase, "pagamento na portaria", null);
        Ordering b = ask(exactCase, "pagamento na portaria", null); // closed and reopened
        assertEquals(Ordering.Method.EXACT, a.getMethod());
        assertEquals(signature(a), signature(b));
        assertEquals(300, a.getAll().size());
    }

    @Test
    public void sameQuestionSameListApproximate() throws Exception {
        Ordering a = ask(approxCase, "viagem com dinheiro", null);
        Ordering b = ask(approxCase, "viagem com dinheiro", null);
        assertEquals(Ordering.Method.APPROXIMATE, a.getMethod());
        assertEquals(signature(a), signature(b));
    }

    @Test
    public void smallScopeOfTheLargeCaseIsExactAndStable() throws Exception {
        BitSet scope = new BitSet();
        scope.set(0, 8);
        Ordering a = ask(approxCase, "contrato de carga", scope);
        Ordering b = ask(approxCase, "contrato de carga", scope);
        assertEquals(Ordering.Method.EXACT, a.getMethod());
        assertEquals(signature(a), signature(b));
        assertEquals(8, a.getAll().size());
    }
}
