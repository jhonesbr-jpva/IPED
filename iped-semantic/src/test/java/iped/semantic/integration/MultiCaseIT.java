package iped.semantic.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import iped.semantic.FakeEmbedder;
import iped.semantic.SyntheticCase;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.search.MultiCaseSearch;
import iped.semantic.search.Notice;
import iped.semantic.search.Ordering;
import iped.semantic.search.OrderingEntry;
import iped.semantic.search.SemanticRequest;
import iped.semantic.task.TextItem;
import iped.semantic.task.TextPreparation;

/**
 * FR-017: three cases open together, two built with the model in use and one with another. The
 * ordering covers the two compatible cases, interleaved by proximity with the same tie-break; the
 * third is declared left out with its reason; coverage sums the included cases.
 */
public class MultiCaseIT {

    private static final FakeEmbedder MODEL = new FakeEmbedder();

    private static SyntheticCase build(String name, FakeEmbedder e, String prefix) throws Exception {
        SyntheticCase c = new SyntheticCase(name);
        TextPreparation p = c.open(e, null);
        List<TextItem> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(c.doc(i, prefix + " documento " + i + " sobre pagamento e entrega na portaria " + (i % 4)));
        }
        c.prepareAll(p, items);
        p.finish();
        return c;
    }

    @Test
    public void onlyCompatibleCasesTakePartAndTheOthersAreDeclared() throws Exception {
        SyntheticCase a = build("mc-a", MODEL, "Caso A:");
        SyntheticCase b = build("mc-b", MODEL, "Caso B:");
        SyntheticCase c = build("mc-c", new FakeEmbedder("other/model@9", "99", 5, 0, false), "Caso C:");

        List<MultiCaseSearch.CaseRef> refs = List.of(new MultiCaseSearch.CaseRef("A", a.moduleDir, a.items),
                new MultiCaseSearch.CaseRef("B", b.moduleDir, b.items),
                new MultiCaseSearch.CaseRef("C", c.moduleDir, c.items));
        SemanticSearchConfig cfg = new SemanticSearchConfig();
        try (MultiCaseSearch search = new MultiCaseSearch(refs, cfg, SyntheticCase.provider(MODEL))) {
            Ordering o = search.search(Modality.TEXT, i -> SemanticRequest.builder().query("pagamento na portaria")
                    .mode(TextMode.SEMANTIC).build(cfg));
            Set<Integer> cases = new HashSet<>();
            double previous = Double.MAX_VALUE;
            for (OrderingEntry e : o.getAll()) {
                cases.add(e.getCaseIndex());
                assertTrue("ordered by proximity", e.getProximity() <= previous);
                previous = e.getProximity();
            }
            assertEquals(Set.of(0, 1), cases);
            assertEquals(40, o.getAll().size());
            assertEquals(40, o.getCoverage().getRecorteSize());
            boolean excluded = false;
            for (Notice n : o.getNotices()) {
                if (n.getCode() == Notice.Code.CASES_EXCLUDED) {
                    excluded = true;
                    assertEquals("C", n.getArgs().get(0));
                    assertEquals("MODEL_INCOMPATIBLE", n.getArgs().get(1));
                }
            }
            assertTrue(excluded);

            // with one case, the path is the searcher's own: same ordering (SC-011 by construction)
            Ordering single = search.member(0).search(
                    SemanticRequest.builder().query("pagamento na portaria").mode(TextMode.SEMANTIC).build(cfg))
                    .get(0);
            try (MultiCaseSearch only = new MultiCaseSearch(List.of(refs.get(0)), cfg, SyntheticCase.provider(MODEL))) {
                Ordering viaMulti = only.search(Modality.TEXT, i -> SemanticRequest.builder()
                        .query("pagamento na portaria").mode(TextMode.SEMANTIC).build(cfg));
                assertEquals(ids(single), ids(viaMulti));
            }
            BitSet none = new BitSet();
            assertEquals(0, none.cardinality());
        }
    }

    private static List<String> ids(Ordering o) {
        List<String> out = new ArrayList<>();
        for (OrderingEntry e : o.getAll()) {
            out.add(e.getCaseIndex() + ":" + e.getItemId() + ":" + e.getProximity());
        }
        return out;
    }
}
