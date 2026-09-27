package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import org.junit.Test;

import iped.localization.Messages;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.search.Coverage;
import iped.semantic.search.DeclarationText;
import iped.semantic.search.Notice;
import iped.semantic.search.Ordering;
import iped.semantic.search.OrderingEntry;
import iped.semantic.store.CoverageReason;

/**
 * The declaration texts, built in the module so they can be checked here (the analysis UI has no
 * test folder). None, in PT-BR or EN, reads as a set; method, depth, ceiling, model and mode are
 * always declared (FR-007, FR-010, SC-006).
 */
public class DeclarationTextTest {

    private static final Locale PT = Locale.forLanguageTag("pt-BR");
    private static final Locale[] LOCALES = { PT, Locale.ENGLISH };

    private static final String[] FORBIDDEN = { "resultados encontrados", "nenhum resultado", "0 ocorrências",
            "results found", "no results", "0 occurrences", "%", "confian", "confidence", "probabilidade",
            "probability" };

    private static Ordering ordering(TextMode mode, Ordering.Method method, int lexical, List<Notice> notices) {
        Map<CoverageReason, Long> nc = new EnumMap<>(CoverageReason.class);
        nc.put(CoverageReason.NO_TEXT, 402L);
        nc.put(CoverageReason.CATEGORY_NOT_SELECTED, 49L);
        Coverage c = new Coverage(5321, 4870, nc, false);
        Ordering.ModelInfo m = new Ordering.ModelInfo("intfloat/multilingual-e5-small@rev+onnx-int8-ortx", "abc",
                "remote", "local", "https://gpu-lab.example/iped");
        return new Ordering(Modality.TEXT, mode, method, lexical, 1000, 100, new ArrayList<OrderingEntry>(), c, m,
                notices);
    }

    private static List<Notice> allNotices() {
        List<Notice> n = new ArrayList<>();
        n.add(new Notice(Notice.Code.QUERY_TRUNCATED, 2000, 2500));
        n.add(new Notice(Notice.Code.IDENTIFIER_LIKE));
        n.add(new Notice(Notice.Code.RECORTE_EMPTY));
        n.add(new Notice(Notice.Code.LEXICAL_NONE));
        n.add(new Notice(Notice.Code.CASES_EXCLUDED, "case-b", "MODEL_INCOMPATIBLE"));
        n.add(new Notice(Notice.Code.PARTIAL_PREPARATION, "PARTIAL"));
        return n;
    }

    private static void assertNoSetWording(String s, Locale l) {
        String lower = s.toLowerCase(l);
        for (String f : FORBIDDEN) {
            assertFalse("'" + f + "' in [" + l + "]: " + s, lower.contains(f));
        }
    }

    @Test
    public void everySemanticMessageAvoidsSetWording() {
        for (Locale l : LOCALES) {
            ResourceBundle b = Messages.getExternalBundle(DeclarationText.BUNDLE, l == PT ? PT : Locale.ROOT);
            int checked = 0;
            for (Enumeration<String> keys = b.getKeys(); keys.hasMoreElements();) {
                String k = keys.nextElement();
                if (k.startsWith("SemanticSearch.")) {
                    assertNoSetWording(b.getString(k), l);
                    checked++;
                }
            }
            assertTrue("semantic keys found in " + l, checked > 90);
        }
    }

    @Test
    public void everyKeyExistsInBothLanguagesAndPtIsTranslated() {
        ResourceBundle en = Messages.getExternalBundle(DeclarationText.BUNDLE, Locale.ROOT);
        ResourceBundle pt = Messages.getExternalBundle(DeclarationText.BUNDLE, PT);
        int translated = 0;
        for (Enumeration<String> keys = en.getKeys(); keys.hasMoreElements();) {
            String k = keys.nextElement();
            if (k.startsWith("SemanticSearch.")) {
                assertTrue("missing in pt-BR: " + k, pt.containsKey(k));
                if (!pt.getString(k).equals(en.getString(k))) {
                    translated++;
                }
            }
        }
        assertTrue(translated > 80);
    }

    @Test
    public void declarationAlwaysNamesMethodDepthCeilingModelAndMode() {
        for (Locale l : LOCALES) {
            DeclarationText t = DeclarationText.of(l);
            for (TextMode mode : TextMode.values()) {
                for (Ordering.Method method : Ordering.Method.values()) {
                    Ordering o = ordering(mode, method, mode == TextMode.HYBRID ? 212 : -1, allNotices());
                    String all = String.join("\n", t.declaration(o));
                    assertNoSetWording(all, l);
                    assertTrue(all, all.contains(t.get("SemanticSearch.Declaration.Method." + method.name())));
                    assertTrue(all, all.contains("1000")); // ceiling
                    assertTrue(all, all.contains("intfloat/multilingual-e5-small@rev+onnx-int8-ortx"));
                    assertTrue(all, all.contains("https://gpu-lab.example/iped"));
                    String modeLine = mode == TextMode.HYBRID
                            ? t.get("SemanticSearch.Declaration.Mode.Hybrid", "212")
                            : t.get("SemanticSearch.Declaration.Mode.Semantic");
                    assertTrue(all, all.contains(modeLine));
                    assertTrue(all, all.contains("4870") && all.contains("5321"));
                    assertTrue(all, all.contains(t.reason(CoverageReason.NO_TEXT) + ": 402"));
                }
            }
            // hybrid with no word match declares that the ordering is the semantic one
            String none = String.join("\n", t.declaration(ordering(TextMode.HYBRID, Ordering.Method.EXACT, 0,
                    List.of(new Notice(Notice.Code.LEXICAL_NONE)))));
            assertTrue(none, none.contains(t.get("SemanticSearch.Declaration.Mode.HybridNone")));
        }
    }

    @Test
    public void proximityIsLabelledRelativeNeverAsAPercentage() {
        for (Locale l : LOCALES) {
            DeclarationText t = DeclarationText.of(l);
            for (OrderingEntry.ProximityKind k : OrderingEntry.ProximityKind.values()) {
                String s = t.proximityKind(k);
                assertTrue(s, s.toLowerCase(l).contains(l == PT ? "relativa" : "relative"));
                assertNoSetWording(s, l);
            }
        }
    }

    @Test
    public void theOrderingNoticeIsTheContractText() {
        assertEquals("Semantic search returns a ranking, not a set. Items further down or absent are not evidence "
                + "that the subject does not occur in the case; low proximity is not absence. Use iped_search for "
                + "identifiers and for claims of absence.", DeclarationText.english().orderingNotice());
    }

    @Test
    public void ceilingHintNamesTheKeyWithoutSuggestingNothingIsBeyond() {
        for (Locale l : LOCALES) {
            String s = DeclarationText.of(l).ceilingHint(1000);
            assertTrue(s, s.contains("maxDepth") && s.contains("1000"));
            assertNoSetWording(s, l);
        }
    }
}
