package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import iped.semantic.FakeEmbedder;
import iped.semantic.SyntheticCase;
import iped.semantic.search.Ordering;
import iped.semantic.search.OrderingEntry;
import iped.semantic.search.ReferenceNotCoveredException;
import iped.semantic.search.SemanticRequest;
import iped.semantic.search.SemanticRequest.Reference;
import iped.semantic.search.SemanticSearcher;
import iped.semantic.store.CoverageReason;
import iped.semantic.task.TextItem;
import iped.semantic.task.TextPreparation;

/** US4, "more like this" (FR-013). */
public class ReferenceSearchTest {

    private static final FakeEmbedder E = new FakeEmbedder();
    private static SyntheticCase c;
    private static final String PAYMENT = "O pagamento em dinheiro foi combinado para amanhã na portaria do prédio.";
    private static final String CAKE = "A receita leva farinha, ovos, açúcar e cenoura; asse por quarenta minutos.";

    @BeforeClass
    public static void prepare() throws Exception {
        c = new SyntheticCase("reference");
        TextPreparation p = c.open(E, null);
        List<TextItem> items = new ArrayList<>();
        // item 1: two passages about different subjects (passage size 1000, so pad each half)
        StringBuilder two = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            two.append(PAYMENT).append(' ');
        }
        two.append("\n\n");
        for (int i = 0; i < 12; i++) {
            two.append(CAKE).append(' ');
        }
        items.add(c.doc(1, two.toString()));
        items.add(c.doc(2, PAYMENT + " O acerto foi feito em espécie."));
        items.add(c.doc(3, CAKE + " Receita de bolo da avó."));
        items.add(c.doc(4, "Relatório do tempo: chuva forte no fim de semana."));
        items.add(c.noText(5, "application/octet-stream"));
        c.prepareAll(p, items);
        p.finish();
    }

    private static Ordering like(Reference ref) throws Exception {
        try (SemanticSearcher s = c.searcher(E)) {
            return s.search(SemanticRequest.builder().reference(ref).build(c.config)).get(0);
        }
    }

    @Test
    public void theReferenceItemIsNeverInTheOrdering() throws Exception {
        Ordering o = like(Reference.item(2));
        for (OrderingEntry e : o.getAll()) {
            assertFalse(e.getItemId() == 2);
        }
        assertEquals(3, o.getAll().size());
    }

    @Test
    public void aReferenceNotCoveredIsRefusedWithTheReasonOfTheItem() throws Exception {
        try {
            like(Reference.item(5));
            fail("an item without text cannot be a reference");
        } catch (ReferenceNotCoveredException e) {
            assertEquals(5, e.getItemId());
            assertEquals(CoverageReason.NO_TEXT, e.getReason());
        }
    }

    @Test
    public void aRangeUsesThatPassageNotTheWholeItem() throws Exception {
        int cakeStart = 13 * (PAYMENT.length() + 1); // after the payment half
        // the stored passages cover the whole text; a range in the cake half must point at item 3
        Ordering cake = like(Reference.range(1, cakeStart, cakeStart + 300));
        assertEquals(3, cake.getAll().get(0).getItemId());
        Ordering payment = like(Reference.range(1, 0, 300));
        assertEquals(2, payment.getAll().get(0).getItemId());
    }

    /**
     * A message is compared by what is said, not by who says it: the same people talking about
     * something else come after a document on the message's subject (research D22).
     */
    @Test
    public void aMessageIsComparedByWhatIsSaidNotByTheSenders() throws Exception {
        SyntheticCase chats = new SyntheticCase("reference-chat");
        TextPreparation p = chats.open(E, null);
        String[] people = { "Baiano Tavares Moreira", "Neguinho Albuquerque Siqueira" };
        String[] payment = { "5 mil em dinheiro", "fechado na portaria", "amanhã cedo o acerto", "leva o dinheiro" };
        String[] weather = { "chuva forte hoje", "vento na serra", "frio de noite", "leva o casaco" };
        List<TextItem> items = new ArrayList<>();
        items.add(chats.doc(10, "chat"));
        items.add(chats.doc(20, "chat"));
        for (int m = 0; m < 4; m++) {
            items.add(chats.message(11 + m, 10, m, people[m % 2], payment[m]));
            items.add(chats.message(21 + m, 20, 100 + m, people[m % 2], weather[m]));
        }
        items.add(chats.doc(30, "Recibo: 5 mil em dinheiro, acerto na portaria amanhã cedo."));
        chats.prepareAll(p, items);
        p.finish();
        try (SemanticSearcher s = chats.searcher(E)) {
            Ordering o = s.search(SemanticRequest.builder().reference(Reference.item(11)).build(chats.config)).get(0);
            assertEquals("the receipt, not the same people's other chat", 30, o.getAll().get(0).getItemId());
        }
    }

    @Test
    public void aSelectedTextIsTheReference() throws Exception {
        Ordering o = like(Reference.selectedText(4, "chuva forte e tempo ruim"));
        assertEquals(3, o.getAll().size());
        for (OrderingEntry e : o.getAll()) {
            assertFalse("the item holding the selection is the reference", e.getItemId() == 4);
        }
    }
}
