package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.StringReader;
import java.util.List;

import org.junit.Test;

import iped.semantic.prep.PassageChunker;
import iped.semantic.prep.PassageChunker.Passage;
import iped.semantic.prep.PassageChunker.Result;

public class PassageChunkerTest {

    private static final String HEADER = "METADATA:";

    private static String sentences(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("Esta é a frase número ").append(i).append(" do documento, com algum conteúdo. ");
        }
        return sb.toString();
    }

    private static Result chunk(String text, int max) throws Exception {
        return new PassageChunker(1000, 200, max).chunk(new StringReader(text), HEADER);
    }

    @Test
    public void everyPassageIsTheLiteralIntervalOfTheItemText() throws Exception {
        String body = sentences(120); // ~7,000 chars
        Result r = chunk(body + "\n" + HEADER + "\nAuthor: someone\nContent-Type: x\n", 200_000);
        assertTrue(r.passages().size() > 5);
        for (Passage p : r.passages()) {
            assertEquals("invariant E3", body.substring(p.start(), p.end()), p.text());
            assertTrue(p.text().length() <= 1000);
        }
        assertFalse(r.truncated());
    }

    @Test
    public void windowsOverlapAndEndAtSentenceBoundaries() throws Exception {
        String body = sentences(120);
        List<Passage> ps = chunk(body, 200_000).passages();
        for (int i = 0; i < ps.size(); i++) {
            Passage p = ps.get(i);
            assertEquals(i, p.ordinal());
            if (i < ps.size() - 1) {
                assertTrue("ends at a sentence: '" + p.text().substring(Math.max(0, p.text().length() - 10)) + "'",
                        p.text().endsWith("."));
                Passage next = ps.get(i + 1);
                int overlap = p.end() - next.start();
                assertTrue("overlap " + overlap, overlap > 0 && overlap <= 200);
                assertTrue(next.start() > p.start());
            }
        }
        assertEquals(body.trim().length() + body.indexOf(body.trim()), ps.get(ps.size() - 1).end());
    }

    @Test
    public void metadataHeaderIsNotContent() throws Exception {
        String body = "Conteúdo real do documento.";
        Result r = chunk(body + "\n" + HEADER + "\ndc:title: Relatório secreto\n", 200_000);
        assertEquals(1, r.passages().size());
        assertEquals(body, r.passages().get(0).text());
        assertFalse(r.passages().get(0).text().contains("Relatório"));
    }

    @Test
    public void onlyTheLastHeaderCounts() throws Exception {
        // an embedded document may quote the header word; the parser appends the real one last
        String body = "Linha que cita METADATA: dentro do texto. Mais texto.";
        Result r = chunk(body + "\n" + HEADER + "\nx: y\n", 200_000);
        assertEquals(body, r.passages().get(0).text());
    }

    @Test
    public void textAboveTheLimitIsTruncatedAndTheExcessDeclared() throws Exception {
        String body = sentences(200); // ~11,800 chars
        Result r = chunk(body + "\n" + HEADER + "\nk: v\n", 5000);
        assertTrue(r.truncated());
        assertEquals(body.stripTrailing().length() - 5000, r.excessChars());
        for (Passage p : r.passages()) {
            assertTrue(p.end() <= 5000);
            assertEquals(body.substring(p.start(), p.end()), p.text());
        }
    }

    @Test
    public void emptyOrBlankTextGivesNoPassage() throws Exception {
        assertTrue(chunk("", 1000).passages().isEmpty());
        assertTrue(chunk("   \n\t  \n" + HEADER + "\nk: v", 1000).passages().isEmpty());
    }

    @Test
    public void aLongWordWithoutSpacesIsCutAtTheWindow() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append((char) ('a' + i % 26));
        }
        String body = sb.toString();
        List<Passage> ps = chunk(body, 200_000).passages();
        assertTrue(ps.size() >= 3);
        for (Passage p : ps) {
            assertEquals(body.substring(p.start(), p.end()), p.text());
        }
        assertEquals(3000, ps.get(ps.size() - 1).end());
    }
}
