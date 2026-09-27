package iped.semantic.search;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.regex.Pattern;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.VectorValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;

import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.Source;
import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.model.Embedder;
import iped.semantic.model.EmbedderProvider;
import iped.semantic.model.EmbeddingException;
import iped.semantic.model.VectorMath;
import iped.semantic.search.OrderingEntry.ProximityKind;
import iped.semantic.search.SemanticRequest.Target;
import iped.semantic.store.SemanticFields;
import iped.semantic.store.SemanticIndexReader;
import iped.semantic.store.SemanticStore;

/**
 * <b>The</b> semantic searcher of a case. The analysis UI and the MCP server both call this class,
 * with the same request; that is what makes their orderings identical (FR-028, SC-011). It never
 * writes to the case.
 *
 * <p>
 * One instance is one search session: availability, including the model fingerprint check, is
 * evaluated once per modality and kept (research D10), and the index readers and coverage tables
 * are opened on first use.
 * </p>
 *
 * <p>
 * The semantic list is computed once, up to {@code maxDepth x oversampling} units, then consolidated
 * up to the ceiling (research D5). A scope of up to {@code exactSearchMaxItems} items is compared
 * exhaustively ({@link Ordering.Method#EXACT}); above it the HNSW graph answers
 * ({@link Ordering.Method#APPROXIMATE}). The method used is declared in the result (amendment A3).
 * </p>
 */
public class SemanticSearcher implements Closeable {

    /**
     * A question that is, in full, digits with the usual punctuation of a CPF, CNPJ, phone or account
     * number, or an e-mail address. Proper names are not detected (spec, edge cases).
     */
    private static final Pattern IDENTIFIER_LIKE = Pattern
            .compile("^\\s*(\\+?[\\d][\\d .\\-/()]{5,}\\d|[\\w.+\\-]+@[\\w\\-]+(\\.[\\w\\-]+)+)\\s*$");

    private final SemanticStore store;
    private final SemanticSearchConfig config;
    private final EmbedderProvider provider;
    private final BitSet caseItems;
    private final int caseIndex;
    private final String caseName;
    private final Compatibility compatibility = new Compatibility();
    private final Map<Modality, Availability> availability = new EnumMap<>(Modality.class);
    private final Map<Modality, SemanticIndexReader> readers = new EnumMap<>(Modality.class);
    private final Map<Modality, Coverage.Table> tables = new EnumMap<>(Modality.class);

    /**
     * @param moduleDir
     *            the case's {@code iped} folder
     * @param caseItems
     *            ids present in the case's main index ({@link CaseItems})
     */
    public SemanticSearcher(File moduleDir, BitSet caseItems, SemanticSearchConfig config, EmbedderProvider provider) {
        this(moduleDir, caseItems, config, provider, 0);
    }

    SemanticSearcher(File moduleDir, BitSet caseItems, SemanticSearchConfig config, EmbedderProvider provider,
            int caseIndex) {
        this.store = SemanticStore.of(moduleDir);
        this.config = config;
        this.provider = provider;
        this.caseItems = caseItems;
        this.caseIndex = caseIndex;
        File caseDir = moduleDir.getAbsoluteFile().getParentFile();
        this.caseName = caseDir != null ? caseDir.getName() : moduleDir.getName();
    }

    /** Name of the case folder, used to name a case left out of a multi-case question. */
    public String getCaseName() {
        return caseName;
    }

    public SemanticSearchConfig getConfig() {
        return config;
    }

    public SemanticStore getStore() {
        return store;
    }

    /** Availability of a modality in this session; evaluated once (fingerprint included). */
    public synchronized Availability availability(Modality m) {
        Availability a = availability.get(m);
        if (a == null) {
            a = Availability.evaluate(store, m, config, provider, compatibility);
            availability.put(m, a);
        }
        return a;
    }

    /** Forgets availability and fingerprint results (e.g. after the source came back). */
    public synchronized void resetSession() {
        availability.clear();
        compatibility.reset();
    }

    /**
     * Answers a question: one ordering per modality asked for ({@link Target#BOTH} gives two, never
     * merged, FR-034).
     *
     * @throws SemanticUnavailableException
     *             for the first modality asked for that cannot answer
     */
    public List<Ordering> search(SemanticRequest request) throws SemanticUnavailableException, IOException {
        List<Ordering> out = new ArrayList<>();
        if (request.getTarget() != Target.VISUAL) {
            out.add(search(request, Modality.TEXT));
        }
        if (request.getTarget() != Target.TEXT) {
            out.add(search(request, Modality.VISUAL));
        }
        return out;
    }

    /** Answers a question in one modality. */
    public Ordering search(SemanticRequest request, Modality modality)
            throws SemanticUnavailableException, IOException {
        return SearchRun.execute(List.of(this), List.of(caseName), modality, i -> request);
    }

    // ---------------------------------------------------------------------------------------------
    // Planning, collection and declaration are split so a multi-case search can collect per case
    // and consolidate once.
    // ---------------------------------------------------------------------------------------------

    /** Everything decided before touching the index. */
    final class Plan {
        final SemanticRequest request;
        final Modality modality;
        final Availability availability;
        final TextMode mode;
        final BitSet scope;
        final int scopeSize;
        final Ordering.Method method;
        final List<Notice> notices = new ArrayList<>();
        int lexicalMatches = -1;
        float[] queryVector;

        Plan(SemanticRequest request, Modality modality, Availability availability) {
            this.request = request;
            this.modality = modality;
            this.availability = availability;
            this.mode = modality == Modality.VISUAL ? TextMode.SEMANTIC : request.getMode();
            this.scope = request.getScope();
            this.scopeSize = scope != null ? scope.cardinality() : caseItems.cardinality();
            this.method = scopeSize <= config.getExactSearchMaxItems() ? Ordering.Method.EXACT
                    : Ordering.Method.APPROXIMATE;
        }

        ProximityKind proximityKind() {
            return mode == TextMode.HYBRID ? ProximityKind.COMBINED_RELATIVE : ProximityKind.RELATIVE;
        }

        SemanticSearcher searcher() {
            return SemanticSearcher.this;
        }
    }

    Plan plan(SemanticRequest request, Modality modality, Availability a) {
        Plan plan = new Plan(request, modality, a);
        if (request.isQueryTruncated()) {
            plan.notices.add(new Notice(Notice.Code.QUERY_TRUNCATED, config.getMaxQueryChars(),
                    request.getOriginalQueryLength()));
        }
        if (request.getQuery() != null && IDENTIFIER_LIKE.matcher(request.getQuery()).matches()) {
            plan.notices.add(new Notice(Notice.Code.IDENTIFIER_LIKE));
        }
        if (plan.scope != null && plan.scopeSize == 0) {
            plan.notices.add(new Notice(Notice.Code.RECORTE_EMPTY));
        }
        if (a.isPartial()) {
            plan.notices.add(new Notice(Notice.Code.PARTIAL_PREPARATION, String.valueOf(a.getRecord().state)));
        }
        return plan;
    }

    /** Scored units of this case for a plan, before consolidation. */
    List<Hit> collect(Plan plan) throws IOException, SemanticUnavailableException {
        if (plan.scope != null && plan.scopeSize == 0) {
            return new ArrayList<>();
        }
        SemanticIndexReader index = reader(plan.modality);
        Integer exclude = null;
        float[] q;
        try {
            q = queryVector(plan, index);
        } catch (EmbeddingException e) {
            // the configured source failed now: unavailable, and no other model is tried (FR-044)
            throw new SemanticUnavailableException(Availability.failedNow(plan.availability, e.getMessage()));
        }
        if (plan.request.getReference() != null) {
            exclude = plan.request.getReference().getItemId();
        }
        plan.queryVector = q;
        int k = config.getMaxDepth() * config.getOversampling();
        List<Hit> semantic = semanticHits(index, q, plan.scope, k, plan.method, exclude);
        if (plan.mode == TextMode.HYBRID && plan.modality == Modality.TEXT) {
            return hybrid(plan, index, semantic, exclude);
        }
        return semantic;
    }

    /** Hook for the hybrid mode (US7); the plain semantic list otherwise. */
    List<Hit> hybrid(Plan plan, SemanticIndexReader index, List<Hit> semantic, Integer exclude) throws IOException {
        return semantic;
    }

    float[] queryVector(Plan plan, SemanticIndexReader index) throws EmbeddingException, IOException {
        Embedder e = plan.availability.getEmbedder();
        return e.embedTexts(Embedder.Role.QUERY, List.of(plan.request.getQuery()))[0];
    }

    Ordering ordering(Plan plan, List<OrderingEntry> entries) throws IOException {
        Coverage coverage = coverage(plan.modality, plan.scope);
        Availability a = plan.availability;
        Ordering.ModelInfo model = new Ordering.ModelInfo(a.getCurrentModel().getId(), a.getCurrentModel().getSha256(),
                a.getSource() == Source.REMOTE ? "remote" : "local", a.getPreparedWith(), a.getDestination());
        if (plan.mode == TextMode.HYBRID && plan.lexicalMatches == 0) {
            plan.notices.add(new Notice(Notice.Code.LEXICAL_NONE));
        }
        return new Ordering(plan.modality, plan.mode, plan.method,
                plan.mode == TextMode.HYBRID ? Math.max(0, plan.lexicalMatches) : -1, config.getMaxDepth(),
                plan.request.getDepth(), entries, coverage, model, plan.notices);
    }

    /** Coverage of a scope ({@code null} = whole case) in a modality. */
    public Coverage coverage(Modality m, BitSet scope) throws IOException {
        return table(m).count(scope, caseItems);
    }

    /** Status table of a modality, loaded once per session. */
    public synchronized Coverage.Table table(Modality m) throws IOException {
        Coverage.Table t = tables.get(m);
        if (t == null) {
            Availability a = availability(m);
            t = Coverage.Table.load(reader(m), a.getRecord(), caseItems.length());
            tables.put(m, t);
        }
        return t;
    }

    synchronized SemanticIndexReader reader(Modality m) throws IOException {
        SemanticIndexReader r = readers.get(m);
        if (r == null) {
            r = new SemanticIndexReader(store.index(m));
            readers.put(m, r);
        }
        return r;
    }

    int getCaseIndex() {
        return caseIndex;
    }

    // ---------------------------------------------------------------------------------------------
    // The semantic list
    // ---------------------------------------------------------------------------------------------

    /** A unit found by the scan, before its stored fields are read. */
    private static final class Scored {
        final int doc;
        final double score;
        final int itemId;
        final int ordinal;

        Scored(int doc, double score, int itemId, int ordinal) {
            this.doc = doc;
            this.score = score;
            this.itemId = itemId;
            this.ordinal = ordinal;
        }
    }

    /** Worst first, so the queue's head is the one to evict. */
    private static final java.util.Comparator<Scored> WORST_FIRST = java.util.Comparator
            .comparingDouble((Scored s) -> s.score).thenComparing((Scored s) -> -s.itemId)
            .thenComparing((Scored s) -> -s.ordinal).thenComparing((Scored s) -> -s.doc);

    List<Hit> semanticHits(SemanticIndexReader index, float[] q, BitSet scope, int k, Ordering.Method method,
            Integer exclude) throws IOException {
        List<Scored> top = method == Ordering.Method.EXACT ? exactScan(index, q, scope, k, exclude)
                : approximate(index, q, scope, k, exclude);
        List<Hit> hits = new ArrayList<>(top.size());
        for (Scored s : top) {
            hits.add(load(index, s.doc, s.score));
        }
        int rank = 1;
        hits.sort(Consolidator.ORDER);
        for (Hit h : hits) {
            h.semanticRank = rank++;
        }
        return hits;
    }

    /** Exhaustive comparison over the vectors of the scope (research D5). */
    private List<Scored> exactScan(SemanticIndexReader index, float[] q, BitSet scope, int k, Integer exclude)
            throws IOException {
        PriorityQueue<Scored> queue = new PriorityQueue<>(k + 1, WORST_FIRST);
        IndexSearcher searcher = index.searcher();
        Weight filter = scope == null ? null
                : searcher.createWeight(searcher.rewrite(new ItemIdFilterQuery(scope)),
                        ScoreMode.COMPLETE_NO_SCORES, 1f);
        for (LeafReaderContext ctx : index.reader().leaves()) {
            LeafReader leaf = ctx.reader();
            VectorValues vectors = leaf.getVectorValues(SemanticFields.VECTOR);
            if (vectors == null) {
                continue;
            }
            NumericDocValues itemIds = leaf.getNumericDocValues(SemanticFields.ITEM_ID);
            NumericDocValues ordinals = leaf.getNumericDocValues(SemanticFields.ORDINAL);
            Bits live = leaf.getLiveDocs();
            DocIdSetIterator candidates;
            if (filter != null) {
                Scorer s = filter.scorer(ctx);
                if (s == null) {
                    continue;
                }
                candidates = s.iterator();
            } else {
                candidates = vectors;
            }
            for (int doc = candidates.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = candidates.nextDoc()) {
                if (live != null && !live.get(doc)) {
                    continue;
                }
                if (candidates != vectors) {
                    int at = vectors.docID() < doc ? vectors.advance(doc) : vectors.docID();
                    if (at != doc) {
                        continue; // a status document: no vector
                    }
                }
                int itemId = value(itemIds, doc);
                if (exclude != null && itemId == exclude) {
                    continue;
                }
                double score = VectorMath.dot(q, vectors.vectorValue());
                Scored sc = new Scored(ctx.docBase + doc, score, itemId, value(ordinals, doc));
                queue.offer(sc);
                if (queue.size() > k) {
                    queue.poll();
                }
            }
        }
        return new ArrayList<>(queue);
    }

    private static int value(NumericDocValues dv, int doc) throws IOException {
        return dv != null && dv.advanceExact(doc) ? (int) dv.longValue() : -1;
    }

    /** The HNSW graph, filtered by the scope (research D5). */
    private List<Scored> approximate(SemanticIndexReader index, float[] q, BitSet scope, int k, Integer exclude)
            throws IOException {
        BitSet effective = scope;
        if (exclude != null) {
            effective = (BitSet) (scope != null ? scope.clone() : caseItems.clone());
            effective.clear(exclude);
        }
        KnnVectorQuery query = effective == null ? new KnnVectorQuery(SemanticFields.VECTOR, q, k)
                : new KnnVectorQuery(SemanticFields.VECTOR, q, k, new ItemIdFilterQuery(effective));
        TopDocs td = index.searcher().search(query, k);
        List<Scored> out = new ArrayList<>(td.scoreDocs.length);
        for (ScoreDoc sd : td.scoreDocs) {
            // DOT_PRODUCT scores are (1 + cos) / 2
            double cos = 2.0 * sd.score - 1.0;
            out.add(new Scored(sd.doc, cos, -1, -1));
        }
        return out;
    }

    /** Reads a unit's stored fields. */
    Hit load(SemanticIndexReader index, int doc, double score) throws IOException {
        Document d = index.document(doc);
        String kind = d.get(SemanticFields.KIND);
        int itemId = SemanticIndexReader.intField(d, SemanticFields.ITEM_ID, -1);
        int ordinal = SemanticIndexReader.intField(d, SemanticFields.ORDINAL, 0);
        if (SemanticFields.KIND_SEQUENCE.equals(kind)) {
            return new Hit(caseIndex, doc, score, kind, itemId, ordinal, null, null, null,
                    SemanticIndexReader.lines(d), null, null, null);
        }
        if (SemanticFields.KIND_IMAGE.equals(kind) || SemanticFields.KIND_VIDEO_FRAME.equals(kind)) {
            Integer fi = d.getField(SemanticFields.FRAME_INDEX) == null ? null
                    : SemanticIndexReader.intField(d, SemanticFields.FRAME_INDEX, 0);
            Integer fc = d.getField(SemanticFields.FRAME_COUNT) == null ? null
                    : SemanticIndexReader.intField(d, SemanticFields.FRAME_COUNT, 0);
            return new Hit(caseIndex, doc, score, kind, itemId, ordinal, null, null, null, null, fi, fc,
                    SemanticIndexReader.longField(d, SemanticFields.INSTANT_APPROX_MS));
        }
        return new Hit(caseIndex, doc, score, kind, itemId, ordinal,
                SemanticIndexReader.intField(d, SemanticFields.CHAR_START, 0),
                SemanticIndexReader.intField(d, SemanticFields.CHAR_END, 0), d.get(SemanticFields.TEXT), null, null,
                null, null);
    }

    @Override
    public synchronized void close() throws IOException {
        for (SemanticIndexReader r : readers.values()) {
            r.close();
        }
        readers.clear();
        tables.clear();
    }
}
