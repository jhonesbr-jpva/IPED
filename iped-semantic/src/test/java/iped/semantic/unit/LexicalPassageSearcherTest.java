package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.ScoreDoc;
import org.junit.BeforeClass;
import org.junit.Test;

import iped.engine.lucene.analysis.AppAnalyzer;
import iped.semantic.FakeEmbedder;
import iped.semantic.SyntheticCase;
import iped.semantic.TestSupport;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.search.LexicalPassageSearcher;
import iped.semantic.search.Notice;
import iped.semantic.search.Ordering;
import iped.semantic.search.OrderingEntry;
import iped.semantic.search.SemanticRequest;
import iped.semantic.search.SemanticSearcher;
import iped.semantic.store.SemanticFields;
import iped.semantic.store.SemanticIndexReader;
import iped.semantic.task.TextItem;
import iped.semantic.task.TextPreparation;

/** The word part of the hybrid mode, and the hybrid ordering it feeds (US7). */
public class LexicalPassageSearcherTest {

    private static final FakeEmbedder E = new FakeEmbedder();
    private static SyntheticCase c;
    private static Analyzer analyzer;

    @BeforeClass
    public static void prepare() throws Exception {
        TestSupport.ensureConfiguration();
        analyzer = AppAnalyzer.get();
        c = new SyntheticCase("lexical");
        TextPreparation p = c.open(E, null);
        List<TextItem> items = new ArrayList<>();
        items.add(c.doc(1, "A ação de cobrança foi ajuizada ontem."));
        items.add(c.doc(2, "Relatório sem relação com o assunto."));
        items.add(c.doc(3, "O pagamento foi combinado para sexta-feira."));
        // conversations: payment with and without the nickname
        items.add(c.doc(10, "chat 1"));
        items.add(c.message(11, 10, 0, "Ana", "o pagamento do Baiano sai amanhã"));
        items.add(c.message(12, 10, 1, "Beto", "combinado, levo em espécie"));
        items.add(c.doc(20, "chat 2"));
        items.add(c.message(21, 20, 0, "Carla", "o pagamento do mês sai amanhã"));
        items.add(c.message(22, 20, 1, "Davi", "combinado, levo em espécie"));
        c.prepareAll(p, items);
        p.finish();
    }

    @Test
    public void questionBecomesEscapedTermsWithoutIpedSyntax() throws Exception {
        assertEquals(List.of("acao", "and", "or", "field", "valor"),
                new ArrayList<>(LexicalPassageSearcher.terms(analyzer, "ação AND OR field:valor")));
    }

    @Test
    public void accentsFoldAsInKeywordSearch() throws Exception {
        try (SemanticIndexReader r = new SemanticIndexReader(c.semanticDir().resolve(Modality.TEXT.key()))) {
            List<ScoreDoc> hits = LexicalPassageSearcher.search(r.searcher(), analyzer, "acao", null, 100);
            assertEquals(1, hits.size());
            assertEquals(1, SemanticIndexReader.intField(r.document(hits.get(0).doc), SemanticFields.ITEM_ID, -1));
        }
    }

    @Test
    public void theScopeAndTheUnitAreThoseOfTheSemanticPart() throws Exception {
        try (SemanticIndexReader r = new SemanticIndexReader(c.semanticDir().resolve(Modality.TEXT.key()))) {
            BitSet scope = new BitSet();
            scope.set(21); // a message of chat 2 only
            List<ScoreDoc> hits = LexicalPassageSearcher.search(r.searcher(), analyzer, "pagamento", scope, 100);
            assertEquals(1, hits.size());
            assertEquals("sequence", r.document(hits.get(0).doc).get(SemanticFields.KIND));
            assertEquals(20, SemanticIndexReader.intField(r.document(hits.get(0).doc), SemanticFields.ITEM_ID, -1));
        }
    }

    @Test
    public void hybridPutsTheNicknameAndTheSubjectFirst() throws Exception {
        try (SemanticSearcher s = c.searcher(E)) {
            Ordering o = s.search(SemanticRequest.builder().query("pagamento combinado com o Baiano")
                    .mode(TextMode.HYBRID).build(c.config)).get(0);
            assertEquals(TextMode.HYBRID, o.getMode());
            assertTrue(o.getLexicalMatches() > 0);
            // story 7, scenario 1: the conversation with the subject AND the nickname comes before
            // the one with the subject only
            int withNickname = -1;
            int withoutNickname = -1;
            for (OrderingEntry e : o.getAll()) {
                if (e.getItemId() == 10) {
                    withNickname = e.getRank();
                    assertEquals(OrderingEntry.ProximityKind.COMBINED_RELATIVE, e.getProximityKind());
                    assertEquals(Integer.valueOf(1), e.getLexicalRank());
                } else if (e.getItemId() == 20) {
                    withoutNickname = e.getRank();
                }
            }
            assertTrue(withNickname > 0 && withoutNickname > 0);
            assertTrue(withNickname + " vs " + withoutNickname, withNickname < withoutNickname);
        }
    }

    @Test
    public void noWordMatchDeclaresTheSemanticOrdering() throws Exception {
        try (SemanticSearcher s = c.searcher(E)) {
            SemanticRequest hybrid = SemanticRequest.builder().query("xyzzy quux").mode(TextMode.HYBRID).build(c.config);
            SemanticRequest semantic = SemanticRequest.builder().query("xyzzy quux").mode(TextMode.SEMANTIC)
                    .build(c.config);
            Ordering h = s.search(hybrid).get(0);
            Ordering sem = s.search(semantic).get(0);
            assertEquals(0, h.getLexicalMatches());
            assertTrue(h.hasNotice(Notice.Code.LEXICAL_NONE));
            List<Integer> a = new ArrayList<>();
            List<Integer> b = new ArrayList<>();
            h.getAll().forEach(e -> a.add(e.getItemId()));
            sem.getAll().forEach(e -> b.add(e.getItemId()));
            assertEquals(b, a);
        }
    }
}
