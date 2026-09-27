package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.BeforeClass;
import org.junit.Test;

import iped.semantic.FakeEmbedder;
import iped.semantic.SyntheticCase;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.search.ContextReader;
import iped.semantic.store.SemanticIndexReader;
import iped.semantic.store.SequenceLine;
import iped.semantic.task.TextItem;
import iped.semantic.task.TextPreparation;

/** The context an agent asks for around an entry (data-model E12, FR-048). */
public class ContextReaderTest {

    private static final FakeEmbedder E = new FakeEmbedder();
    private static SyntheticCase c;
    private static String longText;

    @BeforeClass
    public static void prepare() throws Exception {
        c = new SyntheticCase("context");
        TextPreparation p = c.open(E, null);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            sb.append("Frase de número ").append(i).append(" do relatório sobre contratos de frete. ");
        }
        longText = sb.toString().strip();
        List<TextItem> items = new ArrayList<>();
        items.add(c.doc(1, longText));
        items.add(c.doc(20, "chat"));
        for (int m = 0; m < 24; m++) {
            items.add(c.message(21 + m, 20, m, m % 2 == 0 ? "Ana" : "Beto", "mensagem número " + m + " da conversa"));
        }
        c.prepareAll(p, items);
        p.finish();
    }

    private static SemanticIndexReader reader() throws Exception {
        return new SemanticIndexReader(c.semanticDir().resolve(Modality.TEXT.key()));
    }

    @Test
    public void stitchedTextIsTheExactIntervalWithoutRepeatingTheOverlap() throws Exception {
        try (SemanticIndexReader r = reader()) {
            ContextReader.Context ctx = ContextReader.around(r, 1, 4, 2, 2, 100_000);
            assertEquals(longText.substring(ctx.getCharStart(), ctx.getCharEnd()), ctx.getText());
            assertTrue(ctx.getText().length() > 3000); // five passages, overlap removed
            assertFalse(ctx.isTruncated());
        }
    }

    @Test
    public void theCeilingTruncatesAndSaysSo() throws Exception {
        try (SemanticIndexReader r = reader()) {
            ContextReader.Context ctx = ContextReader.around(r, 1, 4, 2, 2, 1500);
            assertTrue(ctx.isTruncated());
            assertEquals(1500, ctx.getText().length());
            assertEquals(longText.substring(ctx.getCharStart(), ctx.getCharEnd()), ctx.getText());
        }
    }

    @Test
    public void aSequenceGivesTheNeighbouringMessagesInOrder() throws Exception {
        try (SemanticIndexReader r = reader()) {
            ContextReader.Context ctx = ContextReader.around(r, 20, 2, 1, 1, 100_000);
            List<Integer> ids = ctx.getMessages().stream().map(SequenceLine::getItemId).collect(Collectors.toList());
            assertTrue(ids.size() >= 12);
            for (int i = 1; i < ids.size(); i++) {
                assertTrue(ids.get(i) > ids.get(i - 1));
            }
        }
    }

    @Test
    public void anUnknownEntryIsAbsent() throws Exception {
        try (SemanticIndexReader r = reader()) {
            assertNull(ContextReader.around(r, 1, 999, 1, 1, 8000));
            assertNull(ContextReader.around(r, 777, 0, 1, 1, 8000));
        }
    }

    @Test
    public void aRangeIsReadFromTheStoredPassages() throws Exception {
        try (SemanticIndexReader r = reader()) {
            assertEquals(longText.substring(2500, 3700), ContextReader.textRange(r, 1, 2500, 3700));
        }
    }
}
