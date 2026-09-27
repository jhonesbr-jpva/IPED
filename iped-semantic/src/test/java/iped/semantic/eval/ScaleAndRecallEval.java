package iped.semantic.eval;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import org.junit.Assume;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import iped.engine.lucene.analysis.AppAnalyzer;
import iped.semantic.SyntheticCase;
import iped.semantic.TestSupport;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.model.Embedder;
import iped.semantic.model.EmbedderFactory;
import iped.semantic.model.EmbeddingException;
import iped.semantic.model.FingerprintProbes;
import iped.semantic.model.ModelIdentity;
import iped.semantic.search.Ordering;
import iped.semantic.search.OrderingEntry;
import iped.semantic.search.SemanticRequest;
import iped.semantic.search.SemanticSearcher;
import iped.semantic.task.SourceValidator;
import iped.semantic.task.TextItem;
import iped.semantic.task.TextPreparation;
import iped.utils.UTF8Properties;

/**
 * SC-004 and the recall of the approximate search (quickstart, scenario 9; T095), on a synthetic
 * semantic text index of {@code -Diped.semantic.test.scaleItems} covered items (1 M for SC-004).
 *
 * <p>
 * The index is built through the production path - {@code TextPreparation}, the HNSW codec, the
 * build record and one status document per item - with an embedder that answers the fingerprint
 * probes with the real local model and every other passage with a clustered synthetic vector:
 * latency depends on the size of the index, not on its content, and a million real inferences
 * would take hours. The questions are embedded by the real model. Recall compares, for the text
 * questions of the gabarito, the first 100 entries of the approximate search with those of the
 * exact one over the same million units.
 * </p>
 *
 * <p>
 * "Cold" is the first question of a new search session on the case (index opening, coverage table,
 * fingerprint check); the model itself is already loaded in this JVM, and its load time is reported
 * apart. Skipped without the property.
 * </p>
 */
public class ScaleAndRecallEval {

    public static final String ITEMS_PROPERTY = "iped.semantic.test.scaleItems";
    /** Optional: where the synthetic case lives, to reuse it across runs. */
    public static final String DIR_PROPERTY = "iped.semantic.test.scaleDir";

    private static final int CLUSTERS = 1000;

    @Test
    public void latencyAndRecallAtScale() throws Exception {
        int n = Integer.getInteger(ITEMS_PROPERTY, 0);
        Assume.assumeTrue("set -D" + ITEMS_PROPERTY + " to run the scale evaluation", n > 0);
        Path model = TestSupport.requireTextModel();
        TestSupport.ensureConfiguration();
        SemanticSearchConfig cfg = config(model, null);

        long t0 = System.nanoTime();
        Embedder real = new EmbedderFactory(cfg).open(Modality.TEXT);
        real.embedTexts(Embedder.Role.QUERY, List.of("warm up"));
        long modelLoadMs = ms(t0);

        String dir = System.getProperty(DIR_PROPERTY);
        File moduleDir = dir != null ? new File(dir, "iped")
                : TestSupport.tempDir("scale-" + n).resolve("iped").toFile();
        long buildMs = -1;
        if (!new File(moduleDir, "semantic/build-record.json").exists()) {
            moduleDir.mkdirs();
            t0 = System.nanoTime();
            build(moduleDir, cfg, real, n);
            buildMs = ms(t0);
        }
        BitSet all = new BitSet(n);
        all.set(0, n);
        BitSet scope = new BitSet(n);
        for (int i = 0; i < 10_000; i++) {
            scope.set((int) ((long) i * n / 10_000));
        }
        List<String> questions = questions();

        // unscoped: approximate (more units than exactSearchMaxItems)
        Timing unscoped = time(moduleDir, all, cfg, questions, null);
        // scope of 10 thousand items: exact scan over their vectors
        Timing scoped = time(moduleDir, all, cfg, questions, scope);

        // recall of the approximate search against the exact one over the same units
        SemanticSearchConfig exactCfg = config(model, n + 1);
        double recallSum = 0;
        double worst = 1;
        int compared = 0;
        try (SemanticSearcher approx = new SemanticSearcher(moduleDir, all, cfg, new EmbedderFactory(cfg));
                SemanticSearcher exact = new SemanticSearcher(moduleDir, all, exactCfg, new EmbedderFactory(exactCfg))) {
            for (String q : questions.subList(0, Math.min(20, questions.size()))) {
                Ordering a = approx.search(request(q, null, cfg), Modality.TEXT);
                Ordering e = exact.search(request(q, null, exactCfg), Modality.TEXT);
                assertTrue(a.getMethod() == Ordering.Method.APPROXIMATE && e.getMethod() == Ordering.Method.EXACT);
                Set<String> top = keys(e, 100);
                Set<String> got = keys(a, 100);
                got.retainAll(top);
                double r = top.isEmpty() ? 1 : (double) got.size() / top.size();
                recallSum += r;
                worst = Math.min(worst, r);
                compared++;
            }
        }
        double recall = recallSum / compared;

        List<String> log = new ArrayList<>();
        log.add(String.format(Locale.ROOT, "Synthetic text index of %,d covered items (one passage each, %d clusters of "
                + "synthetic vectors, built by TextPreparation with the HNSW codec); questions of the gabarito embedded by "
                + "the local model; %d cores.", n, CLUSTERS, Runtime.getRuntime().availableProcessors()));
        if (buildMs >= 0) {
            log.add(String.format(Locale.ROOT, "- Build: %.1f min (reused on later runs).", buildMs / 60000.0));
        }
        log.add(String.format(Locale.ROOT, "- Model load in this JVM: %d ms (not part of the times below).", modelLoadMs));
        log.add(String.format(Locale.ROOT, "- **SC-004, no scope** (≤ 5 s, %s): cold %d ms; warm median %d ms, max %d ms "
                + "(%d questions).", unscoped.method, unscoped.coldMs, unscoped.medianMs, unscoped.maxMs, unscoped.count));
        log.add(String.format(Locale.ROOT, "- **SC-004, scope of 10,000 items** (≤ 2 s, %s): cold %d ms; warm median %d ms, "
                + "max %d ms (%d questions).", scoped.method, scoped.coldMs, scoped.medianMs, scoped.maxMs, scoped.count));
        log.add(String.format(Locale.ROOT, "- **Recall of the approximate search**, first 100 entries against the exact "
                + "search over the same %,d units: mean %.3f, worst %.3f (%d questions; HNSW maxConn %d, beamWidth %d, "
                + "oversampling %d).", n, recall, worst, compared, cfg.getHnswMaxConn(), cfg.getHnswBeamWidth(),
                cfg.getOversampling()));
        append("ScaleAndRecallEval — SC-004 and recall", log);

        assertTrue("SC-004 no scope: " + unscoped, unscoped.coldMs <= 5000 && unscoped.maxMs <= 5000);
        assertTrue("SC-004 scope of 10k: " + scoped, scoped.coldMs <= 2000 && scoped.maxMs <= 2000);
    }

    private static SemanticSearchConfig config(Path model, Integer exactSearchMaxItems) {
        UTF8Properties p = new UTF8Properties();
        p.setProperty("textLocalModel", model.toString());
        if (exactSearchMaxItems != null) {
            p.setProperty("exactSearchMaxItems", exactSearchMaxItems.toString());
        }
        SemanticSearchConfig c = new SemanticSearchConfig();
        c.processProperties(p);
        return c;
    }

    private static SemanticRequest request(String q, BitSet scope, SemanticSearchConfig cfg) {
        SemanticRequest.Builder b = SemanticRequest.builder().query(q).mode(TextMode.SEMANTIC);
        if (scope != null) {
            b.scope(scope);
        }
        return b.build(cfg);
    }

    private static final class Timing {
        long coldMs;
        long medianMs;
        long maxMs;
        int count;
        String method;

        @Override
        public String toString() {
            return method + " cold " + coldMs + " ms, warm median " + medianMs + " ms, max " + maxMs + " ms";
        }
    }

    /** The first question on a new session (cold), then the others (warm). */
    private static Timing time(File moduleDir, BitSet all, SemanticSearchConfig cfg, List<String> questions,
            BitSet scope) throws Exception {
        Timing t = new Timing();
        List<Long> warm = new ArrayList<>();
        long t0 = System.nanoTime();
        try (SemanticSearcher s = new SemanticSearcher(moduleDir, all, cfg, new EmbedderFactory(cfg))) {
            Ordering first = s.search(request(questions.get(0), scope, cfg), Modality.TEXT);
            t.coldMs = ms(t0);
            t.method = first.getMethod().name().toLowerCase(Locale.ROOT);
            for (String q : questions.subList(1, Math.min(21, questions.size()))) {
                long t1 = System.nanoTime();
                s.search(request(q, scope, cfg), Modality.TEXT);
                warm.add(ms(t1));
            }
        }
        warm.sort(null);
        t.count = warm.size();
        t.medianMs = warm.get(warm.size() / 2);
        t.maxMs = warm.get(warm.size() - 1);
        return t;
    }

    private static Set<String> keys(Ordering o, int k) {
        Set<String> out = new HashSet<>();
        for (OrderingEntry e : o.getAll()) {
            if (out.size() == k) {
                break;
            }
            out.add(e.getItemId() + "#" + e.getOrdinal());
        }
        return out;
    }

    private static List<String> questions() throws Exception {
        List<String> out = new ArrayList<>();
        try (InputStream in = ScaleAndRecallEval.class.getResourceAsStream("/eval/questions.json")) {
            for (JsonNode q : new ObjectMapper().readTree(in).path("questions")) {
                String type = q.path("type").asText();
                if (type.equals("conceptual") || type.equals("crosslingual") || type.equals("mixed")) {
                    out.add(q.path("question").asText());
                }
            }
        }
        return out;
    }

    /**
     * Prepares {@code n} one-passage items through the production path. The fingerprint probes go to
     * the real model, so the case is compatible with it at search time; every other passage gets a
     * deterministic vector around one of {@link #CLUSTERS} centres.
     */
    private static void build(File moduleDir, SemanticSearchConfig cfg, Embedder real, int n) throws Exception {
        int dims = real.dimensions();
        float[][] centres = new float[CLUSTERS][];
        Random r = new Random(42);
        for (int c = 0; c < CLUSTERS; c++) {
            centres[c] = unit(gaussian(r, dims));
        }
        Set<String> probes = new HashSet<>(FingerprintProbes.texts());
        Embedder synthetic = new Embedder() {
            @Override
            public float[][] embedTexts(Role role, List<String> texts) throws EmbeddingException {
                if (texts.stream().allMatch(probes::contains)) {
                    return real.embedTexts(role, texts);
                }
                float[][] out = new float[texts.size()][];
                for (int i = 0; i < texts.size(); i++) {
                    int id = Integer.parseInt(texts.get(i).substring("synthetic ".length()).trim());
                    Random g = new Random(id * 31L + 7);
                    float[] v = gaussian(g, dims);
                    float[] c = centres[id % CLUSTERS];
                    for (int d = 0; d < dims; d++) {
                        v[d] = c[d] + 0.08f * v[d];
                    }
                    out[i] = unit(v);
                }
                return out;
            }

            @Override
            public ModelIdentity identity() {
                return real.identity();
            }
        };
        SourceValidator.Validated v = SourceValidator.validate(cfg, Modality.TEXT, SemanticSearchConfig.ENABLE_TEXT,
                SyntheticCase.provider(synthetic));
        TextPreparation.Settings s = new TextPreparation.Settings().analyzer(AppAnalyzer.get()).ocrEnabled(false)
                .transcriptionEnabled(false).ipedVersion("scale-eval");
        TextPreparation prep = TextPreparation.open(moduleDir, cfg, v, s);
        Set<String> categories = Set.of("Documentos");
        for (int i = 0; i < n; i++) {
            final int id = i;
            prep.prepare(TextItem.document(id, "text/plain", categories, () -> new StringReader("synthetic " + id), null));
        }
        prep.finish();
    }

    private static float[] gaussian(Random r, int dims) {
        float[] v = new float[dims];
        for (int d = 0; d < dims; d++) {
            v[d] = (float) r.nextGaussian();
        }
        return v;
    }

    private static float[] unit(float[] v) {
        double s = 0;
        for (float x : v) {
            s += x * x;
        }
        float inv = (float) (1 / Math.sqrt(s));
        for (int d = 0; d < v.length; d++) {
            v[d] *= inv;
        }
        return v;
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static void append(String title, List<String> lines) throws Exception {
        Path logFile = TestSupport.repoRoot().resolve("specs/009-semantic-search/validation-log.md");
        StringBuilder sb = new StringBuilder("\n### ").append(title).append(" — ").append(LocalDate.now()).append("\n\n");
        for (String l : lines) {
            sb.append(l).append("\n");
        }
        Files.write(logFile, sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
