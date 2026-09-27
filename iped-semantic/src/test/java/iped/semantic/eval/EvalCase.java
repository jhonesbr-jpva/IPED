package iped.semantic.eval;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.junit.Assume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import iped.engine.lucene.analysis.AppAnalyzer;
import iped.engine.task.index.IndexItem;
import iped.properties.BasicProps;
import iped.properties.ExtraProperties;
import iped.semantic.TestSupport;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.model.EmbedderFactory;
import iped.semantic.search.CaseItems;
import iped.semantic.search.LexicalPassageSearcher;
import iped.semantic.search.Ordering;
import iped.semantic.search.OrderingEntry;
import iped.semantic.search.SemanticSearcher;
import iped.utils.UTF8Properties;

/**
 * The eval case (research D18): a case processed from the corpus of {@code tools/eval-corpus}, with
 * semantic text, image and video search on, and the gabarito ({@code eval/questions.json}) resolved
 * against it. Needs {@code -Diped.semantic.test.evalCase=<case>}, and the models it was built with:
 * {@code -Diped.semantic.test.textModel} and, for the visual harness,
 * {@code -Diped.semantic.test.visualModel}.
 */
public final class EvalCase implements AutoCloseable {

    /** One question of the gabarito, with its pertinent items resolved to ids of the case. */
    public static final class Question {
        public final String id;
        public final String type;
        public final String lang;
        public final String text;
        /** Version 2: the subject of a text question; the question's id when it has none. */
        public final String subject;
        public final Set<Integer> relevant;
        /** Pertinent items grouped by unit: a chat (its messages) or a file. */
        public final Map<Integer, Set<Integer>> units;
        /** Version 3: words the excerpt shown for the pertinent item must contain; {@code null} if none. */
        public final String excerpt;

        Question(String id, String type, String lang, String text, String subject, Set<Integer> relevant,
                Map<Integer, Set<Integer>> units, String excerpt) {
            this.id = id;
            this.type = type;
            this.lang = lang;
            this.text = text;
            this.subject = subject;
            this.relevant = relevant;
            this.units = units;
            this.excerpt = excerpt;
        }
    }

    /** Prefix of the system properties that override configuration keys in a calibration run. */
    public static final String CONFIG_OVERRIDE = "iped.semantic.test.config.";

    public final File caseDir;
    public final SemanticSearchConfig config;
    public final SemanticSearcher searcher;
    public final List<Question> questions = new ArrayList<>();
    private final IndexReader main;
    private final Analyzer analyzer;

    private EvalCase(File caseDir, SemanticSearchConfig config) throws Exception {
        this.caseDir = caseDir;
        this.config = config;
        this.main = DirectoryReader.open(FSDirectory.open(new File(caseDir, "iped/index").toPath()));
        this.analyzer = AppAnalyzer.get();
        this.searcher = new SemanticSearcher(new File(caseDir, "iped"), CaseItems.of(main), config,
                new EmbedderFactory(config));
        resolve();
    }

    /** The eval case, or skips the calling test. */
    public static EvalCase open(boolean visual) throws Exception {
        File c = TestSupport.requireCase(TestSupport.EVAL_CASE_PROPERTY);
        Path text = TestSupport.requireTextModel();
        UTF8Properties p = new UTF8Properties();
        p.setProperty("textLocalModel", text.toString());
        if (visual) {
            p.setProperty("visualLocalModel", TestSupport.requireVisualModel().toString());
        }
        // calibration runs: -Diped.semantic.test.config.<key>=<value> overrides a configuration key
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(CONFIG_OVERRIDE)) {
                p.setProperty(name.substring(CONFIG_OVERRIDE.length()), System.getProperty(name));
            }
        }
        TestSupport.ensureConfiguration();
        SemanticSearchConfig cfg = new SemanticSearchConfig();
        cfg.processProperties(p);
        return new EvalCase(c, cfg);
    }

    private void resolve() throws IOException {
        Map<String, Integer> byPath = new HashMap<>();
        Map<String, List<Integer>> byBody = new HashMap<>();
        Map<Integer, Integer> parentOf = new HashMap<>();
        for (LeafReaderContext ctx : main.leaves()) {
            NumericDocValues ids = ctx.reader().getNumericDocValues(IndexItem.ID);
            if (ids == null) {
                continue;
            }
            Bits live = ctx.reader().getLiveDocs();
            for (int doc = ids.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = ids.nextDoc()) {
                if (live != null && !live.get(doc)) {
                    continue;
                }
                int id = (int) ids.longValue();
                Document d = ctx.reader().document(doc);
                String path = d.get(BasicProps.PATH);
                if (path != null) {
                    byPath.putIfAbsent(path.replace('\\', '/'), id);
                }
                String parent = d.get(BasicProps.PARENTID);
                if (parent != null) {
                    try {
                        parentOf.put(id, Integer.valueOf(parent.trim()));
                    } catch (NumberFormatException e) {
                        // not a number: no parent
                    }
                }
                for (String body : d.getValues(ExtraProperties.MESSAGE_BODY)) {
                    byBody.computeIfAbsent(body.strip(), k -> new ArrayList<>()).add(id);
                }
            }
        }
        JsonNode doc;
        try (InputStream in = EvalCase.class.getResourceAsStream("/eval/questions.json")) {
            doc = new ObjectMapper().readTree(in);
        }
        List<String> unresolved = new ArrayList<>();
        for (JsonNode q : doc.path("questions")) {
            Set<Integer> relevant = new LinkedHashSet<>();
            Map<Integer, Set<Integer>> units = new HashMap<>();
            for (JsonNode r : q.path("relevant")) {
                if (r.has("path")) {
                    Integer id = null;
                    for (Map.Entry<String, Integer> e : byPath.entrySet()) {
                        if (e.getKey().endsWith("/" + r.path("path").asText())) {
                            id = e.getValue();
                            break;
                        }
                    }
                    if (id == null) {
                        unresolved.add(q.path("id").asText() + ": " + r);
                        continue;
                    }
                    relevant.add(id);
                    units.computeIfAbsent(-1 - id, k -> new HashSet<>()).add(id);
                } else {
                    List<Integer> ids = byBody.get(r.path("message").asText().strip());
                    if (ids == null || ids.isEmpty()) {
                        unresolved.add(q.path("id").asText() + ": " + r);
                        continue;
                    }
                    for (int id : ids) {
                        relevant.add(id);
                        units.computeIfAbsent(parentOf.getOrDefault(id, id), k -> new HashSet<>()).add(id);
                    }
                }
            }
            questions.add(new Question(q.path("id").asText(), q.path("type").asText(), q.path("lang").asText(),
                    q.path("question").asText(), q.path("subject").asText(q.path("id").asText()), relevant, units,
                    q.hasNonNull("excerpt") ? q.path("excerpt").asText() : null));
        }
        if (!unresolved.isEmpty()) {
            throw new AssertionError("the gabarito does not resolve in " + caseDir + ": " + unresolved);
        }
    }

    public List<Question> ofType(String type) {
        List<Question> out = new ArrayList<>();
        for (Question q : questions) {
            if (q.type.equals(type)) {
                out.add(q);
            }
        }
        return out;
    }

    /** Whether any of the first {@code k} entries targets a pertinent item. */
    public static boolean hitInTop(Ordering o, Set<Integer> relevant, int k) {
        int n = 0;
        for (OrderingEntry e : o.getAll()) {
            if (n++ >= k) {
                break;
            }
            for (int id : e.targetItemIds()) {
                if (relevant.contains(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Keyword search, for the SC-001 condition
    // ---------------------------------------------------------------------------------------------

    private static final Set<String> STOPWORDS = new HashSet<>(List.of(("a o os as um uma uns umas de do da dos "
            + "das em no na nos nas num numa por pelo pela para pra com sem sob sobre entre e ou que se ao aos à às "
            + "é foi ser como mais menos muito já não sim seu sua seus suas meu minha tua teu isso esse essa este "
            + "esta aquele aquela ele ela eles elas lhe the of and or to in on at for with by from an is are was "
            + "el la los las un una y o del al en con por para que se").split(" ")));

    /** The content words of a question, analysed as the main index analyses them. */
    public Set<String> keywords(String question) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (String t : LexicalPassageSearcher.terms(analyzer, question)) {
            if (!STOPWORDS.contains(t) && t.length() > 1) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Whether keyword search finds a pertinent item: the disjunction of the question's content words
     * over the fields IPED's keyword search reads by default ({@code name} and {@code content}).
     */
    public boolean keywordFinds(Question q) throws IOException {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        for (String t : keywords(q.text)) {
            b.add(new TermQuery(new Term(IndexItem.CONTENT, t)), Occur.SHOULD);
            b.add(new TermQuery(new Term(BasicProps.NAME, t)), Occur.SHOULD);
        }
        IndexSearcher s = new IndexSearcher(main);
        BitSet found = new BitSet();
        for (ScoreDoc sd : s.search(b.build(), Integer.MAX_VALUE).scoreDocs) {
            NumericDocValues ids = null;
            for (LeafReaderContext ctx : main.leaves()) {
                if (sd.doc >= ctx.docBase && sd.doc < ctx.docBase + ctx.reader().maxDoc()) {
                    ids = ctx.reader().getNumericDocValues(IndexItem.ID);
                    if (ids != null && ids.advanceExact(sd.doc - ctx.docBase)) {
                        found.set((int) ids.longValue());
                    }
                }
            }
        }
        for (int id : q.relevant) {
            if (found.get(id)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Reporting
    // ---------------------------------------------------------------------------------------------

    /** Appends a section to specs/009-semantic-search/validation-log.md. */
    public void log(String title, List<String> lines) throws IOException {
        Path logFile = TestSupport.repoRoot().resolve("specs/009-semantic-search/validation-log.md");
        StringBuilder sb = new StringBuilder();
        sb.append("\n### ").append(title).append(" — ").append(LocalDate.now()).append("\n\n");
        sb.append("Case `").append(caseDir.getName()).append("`; ").append(String.format(Locale.ROOT,
                "gabarito %d questions.", questions.size()));
        List<String> overrides = new ArrayList<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(CONFIG_OVERRIDE)) {
                overrides.add(name.substring(CONFIG_OVERRIDE.length()) + " = " + System.getProperty(name));
            }
        }
        sb.append(overrides.isEmpty() ? " Configuration as distributed." : " Configuration overridden: "
                + String.join(", ", overrides) + ".").append("\n\n");
        for (String l : lines) {
            sb.append(l).append("\n");
        }
        Files.write(logFile, sb.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    public static String pct(int hits, int total) {
        return total == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%% (%d/%d)", 100.0 * hits / total, hits, total);
    }

    @Override
    public void close() throws Exception {
        searcher.close();
        main.close();
    }
}
