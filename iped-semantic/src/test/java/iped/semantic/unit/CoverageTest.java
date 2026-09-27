package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import iped.semantic.FakeEmbedder;
import iped.semantic.SyntheticCase;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.search.Coverage;
import iped.semantic.search.SemanticSearcher;
import iped.semantic.store.CoverageReason;
import iped.semantic.task.TextItem;
import iped.semantic.task.TextPreparation;
import iped.utils.UTF8Properties;

/**
 * Coverage (data-model E9): covered + sum of not covered = scope size, with a true reason per item,
 * stored or derived from the build record (research D12).
 */
public class CoverageTest {

    private static final FakeEmbedder E = new FakeEmbedder();

    private static SemanticSearchConfig smallLimit() {
        SemanticSearchConfig c = new SemanticSearchConfig();
        UTF8Properties p = new UTF8Properties();
        p.setProperty("maxTextCharsPerItem", "300");
        c.processProperties(p);
        return c;
    }

    private static void assertInvariant(Coverage c) {
        long sum = c.getCovered();
        for (long n : c.getNotCovered().values()) {
            sum += n;
        }
        assertEquals("covered + sum(notCovered) = recorteSize", c.getRecorteSize(), sum);
    }

    @Test
    public void everyItemIsCountedWithATrueReason() throws Exception {
        SyntheticCase c = new SyntheticCase("cov", smallLimit());
        TextPreparation p = c.open(E, null);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longText.append("Frase longa número ").append(i).append(" sobre transporte de carga. ");
        }
        List<TextItem> items = List.of(
                c.doc(1, "Contrato de frete assinado em março, com cláusula de multa."),
                c.doc(2, longText.toString()),
                c.noText(3, "application/octet-stream"),
                c.noText(4, "image/jpeg"), // OCR off in this processing
                c.noText(5, "audio/ogg"), // transcription off
                c.doc(10, "chat renderizado"),
                c.message(11, 10, 0, "Ana", "quanto fica a entrega da carga toda?"),
                c.message(12, 10, 1, "Beto", "cinco mil, fechado para amanhã cedo"),
                c.message(13, 10, 2, "Ana", ""));
        c.prepareAll(p, items);
        p.finish();
        c.items.set(50); // in the main index, never prepared: an interrupted preparation

        try (SemanticSearcher s = c.searcher(E)) {
            Coverage all = s.coverage(Modality.TEXT, null);
            assertInvariant(all);
            assertEquals(10, all.getRecorteSize());
            Map<CoverageReason, Long> nc = all.getNotCovered();
            assertEquals(Long.valueOf(2), nc.get(CoverageReason.NO_TEXT)); // item 3 and message 13
            assertEquals(Long.valueOf(2), nc.get(CoverageReason.ANALYSIS_NOT_RUN));
            assertEquals(Long.valueOf(1), nc.get(CoverageReason.COVERED_BY_MESSAGES));
            assertEquals(Long.valueOf(1), nc.get(CoverageReason.PREP_FAILURE));
            assertEquals(4, all.getCovered()); // 1, 2 (truncated), 11, 12
            assertEquals(CoverageReason.COVERED_TRUNCATED, s.table(Modality.TEXT).reasonOf(2));
            assertFalse(all.isPartial());

            BitSet scope = new BitSet();
            scope.set(1);
            scope.set(3);
            scope.set(50);
            Coverage part = s.coverage(Modality.TEXT, scope);
            assertInvariant(part);
            assertEquals(3, part.getRecorteSize());
            assertEquals(1, part.getCovered());
        }
    }

    @Test
    public void itemsBeforeTheCapabilityAreDeclaredSoAndPartialIsFlagged() throws Exception {
        SyntheticCase c = new SyntheticCase("covappend");
        // a case processed without the capability had items 0..99; the append starts at 100
        for (int id = 0; id < 100; id++) {
            c.items.set(id);
        }
        TextPreparation p = c.open(E, 100);
        c.prepareAll(p, List.of(c.doc(100, "Documento acrescentado depois."), c.doc(101, "Outro documento novo.")));
        // no finish(): the record stays PREPARING, i.e. partial
        p.close();
        try (SemanticSearcher s = c.searcher(E)) {
            Coverage all = s.coverage(Modality.TEXT, null);
            assertInvariant(all);
            assertEquals(Long.valueOf(100), all.getNotCovered().get(CoverageReason.BEFORE_CAPABILITY));
            assertTrue(all.isPartial());
        }
    }
}
