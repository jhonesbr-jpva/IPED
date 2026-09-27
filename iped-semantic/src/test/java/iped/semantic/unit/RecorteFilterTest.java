package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import iped.semantic.FakeEmbedder;
import iped.semantic.SyntheticCase;
import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.search.Notice;
import iped.semantic.search.Ordering;
import iped.semantic.search.OrderingEntry;
import iped.semantic.search.OrderingEntry.MessageLine;
import iped.semantic.search.SemanticRequest;
import iped.semantic.search.SemanticSearcher;
import iped.semantic.task.TextItem;
import iped.semantic.task.TextPreparation;

/** US3: the question runs inside the scope the examiner built (FR-012). */
public class RecorteFilterTest {

    private static final FakeEmbedder E = new FakeEmbedder();
    private static SyntheticCase c;

    @BeforeClass
    public static void prepare() throws Exception {
        c = new SyntheticCase("recorte");
        TextPreparation p = c.open(E, null);
        List<TextItem> items = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            items.add(c.doc(i, "Documento " + i + " sobre pagamento em dinheiro na portaria."));
        }
        items.add(c.doc(100, "chat"));
        for (int m = 0; m < 6; m++) {
            items.add(c.message(101 + m, 100, m, m % 2 == 0 ? "Ana" : "Beto",
                    "mensagem " + m + " sobre o pagamento combinado na portaria"));
        }
        c.prepareAll(p, items);
        p.finish();
    }

    private static Ordering ask(BitSet scope) throws Exception {
        try (SemanticSearcher s = c.searcher(E)) {
            return s.search(SemanticRequest.builder().query("pagamento na portaria").mode(TextMode.SEMANTIC)
                    .scope(scope).build(c.config)).get(0);
        }
    }

    @Test
    public void onlyItemsOfTheScopeAppear() throws Exception {
        BitSet scope = new BitSet();
        scope.set(3);
        scope.set(7);
        scope.set(11);
        Ordering o = ask(scope);
        assertEquals(3, o.getAll().size());
        for (OrderingEntry e : o.getAll()) {
            assertTrue(scope.get(e.getItemId()));
        }
        assertEquals(3, o.getCoverage().getRecorteSize());
        assertEquals(Ordering.Method.EXACT, o.getMethod());
    }

    @Test
    public void anEmptyScopeIsDeclaredEmptyNeverAsNothingFound() throws Exception {
        Ordering o = ask(new BitSet());
        assertTrue(o.getAll().isEmpty());
        assertEquals(0, o.getCoverage().getRecorteSize());
        assertTrue(o.hasNotice(Notice.Code.RECORTE_EMPTY));
    }

    @Test
    public void aSequenceHalfInTheScopeEntersWithTheOthersMarkedAsContext() throws Exception {
        BitSet scope = new BitSet();
        scope.set(102); // one message of the conversation
        Ordering o = ask(scope);
        assertEquals(1, o.getAll().size());
        OrderingEntry e = o.getAll().get(0);
        assertEquals("sequence", e.getKind());
        int inScope = 0;
        for (MessageLine m : e.getSequence().getMessages()) {
            if (m.getItemId() == 102) {
                assertTrue(m.isInScope());
                inScope++;
            } else {
                assertFalse(m.isInScope());
            }
        }
        assertEquals(1, inScope);
        assertEquals(List.of(102), e.targetItemIds());
    }
}
