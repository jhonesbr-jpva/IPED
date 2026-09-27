package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.BeforeClass;
import org.junit.Test;

import iped.semantic.FakeEmbedder;
import iped.semantic.SyntheticCase;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.search.ContextReader;
import iped.semantic.search.Ordering;
import iped.semantic.search.OrderingEntry;
import iped.semantic.search.SemanticRequest;
import iped.semantic.search.SemanticSearcher;
import iped.semantic.store.SemanticFields;
import iped.semantic.store.SemanticIndexReader;
import iped.semantic.task.TextItem;
import iped.semantic.task.TextPreparation;

/**
 * Focus windows (research D23, data-model E3b): in a long item, the excerpt shown is the window
 * closest to the question, a literal interval of the item's text; the ordering is still made of
 * passages, and an item of one passage gets no windows.
 */
public class FocusWindowTest {

    private static final FakeEmbedder E = new FakeEmbedder();
    private static final String KEY = "O fiscal da prefeitura cobrou propina em envelope para assinar o laudo.";
    private static SyntheticCase c;
    private static String longText;

    @BeforeClass
    public static void prepare() throws Exception {
        c = new SyntheticCase("focus");
        TextPreparation p = c.open(E, null);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            if (i == 21) {
                sb.append(KEY).append(' ');
            }
            sb.append("Revisão ").append(i).append(" dos elevadores e da bomba da piscina, sem pendência técnica. ");
        }
        longText = sb.toString().trim();
        List<TextItem> items = new ArrayList<>();
        items.add(c.doc(1, longText));
        items.add(c.doc(2, "Relatório curto do jardim: poda das palmeiras e adubação dos canteiros."));
        c.prepareAll(p, items);
        p.finish();
    }

    private static Ordering ask(String q, TextMode mode) throws Exception {
        try (SemanticSearcher s = c.searcher(E)) {
            return s.search(SemanticRequest.builder().query(q).mode(mode).build(c.config), Modality.TEXT);
        }
    }

    @Test
    public void theExcerptShownIsTheWindowOfTheParagraph() throws Exception {
        for (TextMode mode : TextMode.values()) {
            Ordering o = ask("propina fiscal prefeitura envelope laudo", mode);
            OrderingEntry first = o.getAll().get(0);
            assertEquals(1, first.getItemId());
            OrderingEntry.Excerpt shown = first.getExcerpts().get(0);
            assertTrue(mode + ": the paragraph is in the excerpt shown", shown.getText().contains("propina em envelope"));
            assertTrue(mode + ": a focus window, not a whole passage",
                    shown.getText().length() <= c.config.getFocusChars());
            assertEquals("literal interval of the item's text (SC-006)",
                    longText.substring(shown.getCharStart(), shown.getCharEnd()), shown.getText());
            assertTrue(first.getExcerpts().size() <= 3);
        }
    }

    @Test
    public void theExcerptOrdinalOpensTheContextAroundIt() throws Exception {
        OrderingEntry.Excerpt shown = ask("propina fiscal prefeitura envelope laudo", TextMode.SEMANTIC).getAll().get(0)
                .getExcerpts().get(0);
        try (SemanticSearcher s = c.searcher(E)) {
            ContextReader.Context ctx = s.context(1, shown.getOrdinal(), 0, 0, 100_000);
            assertTrue("the passage of that ordinal holds the window's start",
                    ctx.getCharStart() <= shown.getCharStart() && shown.getCharStart() < ctx.getCharEnd());
            assertEquals(SemanticFields.KIND_PASSAGE, ctx.getKind());
        }
    }

    @Test
    public void onlyItemsLongerThanOnePassageGetFocusWindows() throws Exception {
        try (SemanticIndexReader r = new SemanticIndexReader(c.semanticDir().resolve(Modality.TEXT.key()))) {
            TermQuery focus = new TermQuery(new Term(SemanticFields.KIND, SemanticFields.KIND_FOCUS));
            int total = r.searcher().count(focus);
            assertTrue("the long item has windows", total > 5);
            org.apache.lucene.search.BooleanQuery shortOnes = new org.apache.lucene.search.BooleanQuery.Builder()
                    .add(focus, org.apache.lucene.search.BooleanClause.Occur.FILTER)
                    .add(new TermQuery(new Term(SemanticFields.ITEM_ID_KEY, "2")),
                            org.apache.lucene.search.BooleanClause.Occur.FILTER)
                    .build();
            assertEquals("one passage is its own excerpt", 0, r.searcher().count(shortOnes));
        }
    }

    /** The viewer goes to a phrase's first occurrence: the phrase chosen is one the item does not repeat. */
    @Test
    public void theHighlightPhraseOccursOnceInTheItem() throws Exception {
        int start = longText.indexOf(" dos elevadores", longText.indexOf("Revisão 30 ")) + 1;
        int end = start + 200;
        try (SemanticSearcher s = c.searcher(E)) {
            String phrase = s.distinctPhrase(1, SemanticFields.KIND_PASSAGE, start, end, 4);
            String folded = java.text.Normalizer.normalize(longText, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ");
            assertEquals("pendencia tecnica revisao 31", phrase);
            assertEquals(1, (folded.length() - folded.replace(phrase, "").length()) / phrase.length());
            assertEquals("an item without passages of that kind", null,
                    s.distinctPhrase(1, SemanticFields.KIND_TRANSCRIPT, start, end, 4));
        }
    }

    @Test
    public void focusWindowsNeverBecomeEntries() throws Exception {
        for (TextMode mode : TextMode.values()) {
            for (OrderingEntry e : ask("revisão elevadores piscina", mode).getAll()) {
                assertTrue(SemanticFields.KIND_PASSAGE.equals(e.getKind()));
            }
            assertEquals("one entry per item", 2, ask("revisão elevadores piscina", mode).getAll().size());
        }
    }
}
