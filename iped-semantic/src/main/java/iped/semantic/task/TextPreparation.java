package iped.semantic.task;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.Source;
import iped.semantic.model.Embedder;
import iped.semantic.model.EmbeddingException;
import iped.semantic.model.EmbeddingService;
import iped.semantic.model.Fingerprint;
import iped.semantic.model.RemoteEmbedders;
import iped.semantic.prep.ChatSequenceBuilder;
import iped.semantic.prep.PassageChunker;
import iped.semantic.store.BuildRecord;
import iped.semantic.store.BuildRecord.ModalityRecord;
import iped.semantic.store.BuildRecord.ModelRecord;
import iped.semantic.store.BuildRecord.State;
import iped.semantic.store.CoverageReason;
import iped.semantic.store.SemanticFields;
import iped.semantic.store.SemanticIndexWriter;
import iped.semantic.store.SemanticStore;
import iped.semantic.store.SequenceLine;
import iped.semantic.store.StagingStore;

/**
 * Preparation of the text modality for one processing (research D6, D7, D15, D16). The task's
 * instances, one per worker, share one of these per JVM.
 *
 * <p>
 * Every item that reaches it ends with a status document naming a true reason: covered, covered up
 * to the limit, no text, analysis not run, category not selected, covered by its messages, model
 * mismatch, remote unavailable or preparation failure. A failure never stops the processing
 * (FR-022). Items are written whole - passages and status in one {@code updateDocuments} - only when
 * every passage is embedded, so an item is never half in the index.
 * </p>
 */
public final class TextPreparation implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextPreparation.class);

    /** What the preparation needs from the processing environment. */
    public static final class Settings {
        Analyzer analyzer;
        String metadataHeader;
        boolean ocrEnabled;
        boolean transcriptionEnabled;
        /** First item id of this processing when the case already had items; {@code null} for a new case. */
        Integer capabilityStartId;
        String ipedVersion;

        public Settings analyzer(Analyzer a) {
            this.analyzer = a;
            return this;
        }

        public Settings metadataHeader(String h) {
            this.metadataHeader = h;
            return this;
        }

        public Settings ocrEnabled(boolean b) {
            this.ocrEnabled = b;
            return this;
        }

        public Settings transcriptionEnabled(boolean b) {
            this.transcriptionEnabled = b;
            return this;
        }

        public Settings capabilityStartId(Integer id) {
            this.capabilityStartId = id;
            return this;
        }

        public Settings ipedVersion(String v) {
            this.ipedVersion = v;
            return this;
        }
    }

    /** Something waiting for a vector: a passage of an item, or a window of a chat. */
    private interface Pending {
        void vector(float[] v);

        void failed(Exception e);
    }

    private final SemanticSearchConfig config;
    private final SemanticStore store;
    private final Settings settings;
    private final SemanticIndexWriter writer;
    private final StagingStore staging;
    private final EmbeddingService<Pending> service;
    private final PassageChunker chunker;
    private final ChatSequenceBuilder sequences;
    private final boolean mismatch;
    private final Set<String> categories = new HashSet<>();
    private final Set<String> messageTypes = new HashSet<>();
    private final long startedAtMillis = System.currentTimeMillis();
    private final AtomicLong taskNanos = new AtomicLong();
    private final AtomicLong itemsSeen = new AtomicLong();
    private final ScheduledExecutorService committer;
    private volatile boolean closed;

    /**
     * Opens (or resumes) the preparation of a case: writes the build record before the first
     * document, then opens the index, the staging area and the embedding service.
     *
     * @param moduleDir
     *            the case's {@code iped} folder
     * @param source
     *            the validated model source (FR-042)
     */
    public static TextPreparation open(File moduleDir, SemanticSearchConfig config, SourceValidator.Validated source,
            Settings settings) throws IOException {
        return new TextPreparation(moduleDir, config, source, settings);
    }

    private TextPreparation(File moduleDir, SemanticSearchConfig config, SourceValidator.Validated source,
            Settings settings) throws IOException {
        this.config = config;
        this.settings = settings;
        this.store = SemanticStore.of(moduleDir);
        Embedder embedder = source.embedder();
        for (String c : config.getTextCategories()) {
            categories.add(c.toLowerCase(Locale.ROOT));
        }
        for (String t : config.getMessageMediaTypes()) {
            messageTypes.add(t.toLowerCase(Locale.ROOT));
        }

        BuildRecord existing = BuildRecord.read(store.buildRecord());
        ModalityRecord previous = existing == null ? null : existing.get(Modality.TEXT);
        String mismatchReason = null;
        if (previous != null) {
            // --continue or --append on a case that already has the modality (research D16)
            Fingerprint recorded = previous.fingerprint;
            Fingerprint.Comparison cmp = recorded == null ? null
                    : recorded.compare(previous.identity(), source.fingerprint(), embedder.identity(),
                            config.getFingerprintTolerance());
            if (cmp == null || !cmp.isCompatible()) {
                mismatchReason = cmp == null ? "the case records no fingerprint for the text modality"
                        : cmp.reason();
            }
        }
        this.mismatch = mismatchReason != null;
        final String reason = mismatchReason;
        BuildRecord.update(store.buildRecord(), r -> {
            r.ipedVersion = settings.ipedVersion;
            ModalityRecord mr = r.get(Modality.TEXT);
            if (mr == null) {
                mr = r.getOrCreate(Modality.TEXT);
                mr.source = config.getModelSource(Modality.TEXT) == Source.REMOTE ? "remote" : "local";
                mr.model = ModelRecord.of(embedder.identity());
                mr.fingerprint = source.fingerprint();
                mr.params = params(config);
                mr.capabilityStartId = settings.capabilityStartId;
                mr.conflicts = new ArrayList<>();
                if (config.getModelSource(Modality.TEXT) == Source.REMOTE) {
                    mr.remote = new BuildRecord.RemoteRecord(config.getRemoteProtocol(Modality.TEXT),
                            RemoteEmbedders.safeDestination(config.getRemoteEndpoint(Modality.TEXT)));
                }
            } else if (mr.state == State.PREPARING) {
                LOGGER.warn("Semantic text: the previous preparation of this case did not finish; resuming it "
                        + "(state was PREPARING, now treated as PARTIAL until this processing finishes)");
            }
            if (reason != null) {
                mr.state = State.DISABLED_MISMATCH;
                mr.disabledReason = reason;
            } else {
                mr.state = State.PREPARING;
                mr.disabledReason = null;
            }
            Map<String, Object> timing = mr.timing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mr.timing);
            timing.put("startedAt", Instant.ofEpochMilli(startedAtMillis).toString());
            mr.timing = timing;
        });
        if (mismatch) {
            LOGGER.error("Semantic text search is DISABLED for this processing: {}. Items added now are recorded as "
                    + "not covered ({}); the rest of the processing goes on (research D16).", mismatchReason,
                    CoverageReason.MODEL_MISMATCH);
        }

        this.writer = new SemanticIndexWriter(store.index(Modality.TEXT), settings.analyzer, config.getHnswMaxConn(),
                config.getHnswBeamWidth());
        this.staging = new StagingStore(store.staging());
        this.chunker = new PassageChunker(config.getPassageChars(), config.getPassageOverlapChars(),
                config.getMaxTextCharsPerItem());
        this.sequences = new ChatSequenceBuilder(config.getChatWindowMessages(), config.getChatWindowStride(),
                config.getChatWindowMaxChars(), config.getChatMaxGapMinutes(), config.getChatShortMessageChars());
        this.service = new EmbeddingService<>("semantic-text", embedder, Embedder.Role.PASSAGE,
                config.getInferenceThreads(), config.getBatchSize(), config.getQueueCapacity(),
                new EmbeddingService.Callback<Pending>() {
                    @Override
                    public void onEmbedded(List<Pending> payloads, float[][] vectors) {
                        for (int i = 0; i < payloads.size(); i++) {
                            payloads.get(i).vector(vectors[i]);
                        }
                    }

                    @Override
                    public void onFailure(List<Pending> payloads, Exception error) {
                        for (Pending p : payloads) {
                            p.failed(error);
                        }
                    }
                });
        this.committer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "semantic-text-commit");
            t.setDaemon(true);
            return t;
        });
        long interval = TimeUnit.MINUTES.toMillis(config.getCommitIntervalMinutes());
        committer.scheduleWithFixedDelay(() -> {
            try {
                if (!closed && writer.commitIfDue(interval)) {
                    staging.commit();
                }
            } catch (Exception e) {
                LOGGER.warn("Periodic commit of the semantic text index failed", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    static Map<String, Object> params(SemanticSearchConfig c) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("passageChars", c.getPassageChars());
        p.put("passageOverlapChars", c.getPassageOverlapChars());
        p.put("maxTextCharsPerItem", c.getMaxTextCharsPerItem());
        p.put("chatWindowMessages", c.getChatWindowMessages());
        p.put("chatWindowStride", c.getChatWindowStride());
        p.put("chatWindowMaxChars", c.getChatWindowMaxChars());
        p.put("chatMaxGapMinutes", c.getChatMaxGapMinutes());
        p.put("chatShortMessageChars", c.getChatShortMessageChars());
        p.put("categories", new ArrayList<>(c.getTextCategories()));
        p.put("messageMediaTypes", new ArrayList<>(c.getMessageMediaTypes()));
        Map<String, Object> hnsw = new LinkedHashMap<>();
        hnsw.put("maxConn", c.getHnswMaxConn());
        hnsw.put("beamWidth", c.getHnswBeamWidth());
        p.put("hnsw", hnsw);
        return p;
    }

    public boolean isDisabledByMismatch() {
        return mismatch;
    }

    // ---------------------------------------------------------------------------------------------
    // Per item (worker threads)
    // ---------------------------------------------------------------------------------------------

    /** Prepares one item; never throws for an item-level problem (FR-022). */
    public void prepare(TextItem item) throws InterruptedException {
        long t0 = System.nanoTime();
        itemsSeen.incrementAndGet();
        try {
            if (mismatch) {
                status(item.id, CoverageReason.MODEL_MISMATCH, null);
                return;
            }
            if (!categorySelected(item)) {
                status(item.id, CoverageReason.CATEGORY_NOT_SELECTED, null);
                return;
            }
            if (item.isMessage() && messageTypes.contains(item.mediaType.toLowerCase(Locale.ROOT))) {
                staging.put(new StagingStore.Message(item.chatId, item.id, item.date, item.position, item.sender,
                        item.body));
                return;
            }
            ItemJob job = passages(item);
            if (job == null) {
                status(item.id, analysisNotRun(item) ? CoverageReason.ANALYSIS_NOT_RUN : CoverageReason.NO_TEXT, null);
                return;
            }
            for (Unit u : job.units) {
                service.submit(u, u.text);
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            LOGGER.warn("Semantic text preparation failed for item {}: {}", item.id, e.toString());
            safeStatus(item.id, CoverageReason.PREP_FAILURE, e.toString());
        } finally {
            taskNanos.addAndGet(System.nanoTime() - t0);
        }
    }

    private boolean categorySelected(TextItem item) {
        if (categories.isEmpty()) {
            return true;
        }
        for (String c : item.categories) {
            if (categories.contains(c.toLowerCase(Locale.ROOT).trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The reason names something true about the item: it would have text if an analysis that is
     * off in this processing had run - OCR for images and PDFs, transcription for audio and video.
     */
    private boolean analysisNotRun(TextItem item) {
        String mt = item.mediaType.toLowerCase(Locale.ROOT);
        if ((mt.startsWith("image/") || mt.equals("application/pdf")) && !settings.ocrEnabled) {
            return true;
        }
        return (mt.startsWith("audio/") || mt.startsWith("video/")) && !settings.transcriptionEnabled;
    }

    private ItemJob passages(TextItem item) throws IOException {
        List<Unit> units = new ArrayList<>();
        long excess = 0;
        ItemJob job = new ItemJob(item.id);
        if (item.text != null) {
            try (Reader r = item.text.open()) {
                if (r != null) {
                    PassageChunker.Result res = chunker.chunk(r, settings.metadataHeader);
                    excess = res.excessChars();
                    for (PassageChunker.Passage p : res.passages()) {
                        units.add(new Unit(job, units.size(), SemanticFields.KIND_PASSAGE, p.ordinal(), p.start(),
                                p.end(), p.text()));
                    }
                }
            }
        }
        if (item.transcript != null && !item.transcript.isBlank()) {
            int offset = units.size();
            String t = item.transcript.length() > config.getMaxTextCharsPerItem()
                    ? item.transcript.substring(0, config.getMaxTextCharsPerItem())
                    : item.transcript;
            excess += Math.max(0, item.transcript.length() - t.length());
            for (PassageChunker.Passage p : chunker.split(t)) {
                units.add(new Unit(job, units.size(), SemanticFields.KIND_TRANSCRIPT, offset + p.ordinal(), p.start(),
                        p.end(), p.text()));
            }
        }
        if (units.isEmpty()) {
            return null;
        }
        job.init(units, excess);
        return job;
    }

    private void status(int id, CoverageReason reason, String detail) throws IOException {
        writer.replaceItem(id, List.of(SemanticIndexWriter.status(id, reason, detail)));
    }

    private void safeStatus(int id, CoverageReason reason, String detail) {
        try {
            status(id, reason, detail);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not record the semantic status of item {}", id, e);
        }
    }

    private static CoverageReason failureReason(Exception e) {
        return e instanceof EmbeddingException && ((EmbeddingException) e).isRemoteUnavailable()
                ? CoverageReason.REMOTE_UNAVAILABLE
                : CoverageReason.PREP_FAILURE;
    }

    /** All passages of one item; written in one operation when the last vector arrives. */
    private final class ItemJob {
        final int itemId;
        List<Unit> units;
        float[][] vectors;
        AtomicInteger remaining;
        volatile Exception failure;
        long excess;

        ItemJob(int itemId) {
            this.itemId = itemId;
        }

        void init(List<Unit> units, long excess) {
            this.units = units;
            this.vectors = new float[units.size()][];
            this.remaining = new AtomicInteger(units.size());
            this.excess = excess;
        }

        void done() {
            if (remaining.decrementAndGet() > 0) {
                return;
            }
            try {
                if (failure != null) {
                    status(itemId, failureReason(failure), failure.toString());
                    return;
                }
                List<Document> docs = new ArrayList<>(units.size() + 1);
                for (Unit u : units) {
                    docs.add(SemanticIndexWriter.passage(u.kind, itemId, u.ordinal, u.start, u.end, u.text,
                            vectors[u.index]));
                }
                docs.add(excess > 0
                        ? SemanticIndexWriter.status(itemId, CoverageReason.COVERED_TRUNCATED, String.valueOf(excess))
                        : SemanticIndexWriter.status(itemId, CoverageReason.COVERED, null));
                writer.replaceItem(itemId, docs);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not write the semantic passages of item {}", itemId, e);
                safeStatus(itemId, CoverageReason.PREP_FAILURE, e.toString());
            }
        }
    }

    private final class Unit implements Pending {
        final ItemJob job;
        final int index;
        final String kind;
        final int ordinal;
        final int start;
        final int end;
        final String text;

        Unit(ItemJob job, int index, String kind, int ordinal, int start, int end, String text) {
            this.job = job;
            this.index = index;
            this.kind = kind;
            this.ordinal = ordinal;
            this.start = start;
            this.end = end;
            this.text = text;
        }

        @Override
        public void vector(float[] v) {
            job.vectors[index] = v;
            job.done();
        }

        @Override
        public void failed(Exception e) {
            job.failure = e;
            job.done();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Commit alignment with the main index
    // ---------------------------------------------------------------------------------------------

    /**
     * Makes everything submitted so far durable. Called when the engine starts committing the main
     * index, before it prepares that commit: an item the main commit contains then has its semantic
     * documents committed too, so {@code --continue}, which never revisits committed items, finds
     * them. What still slips through a crash in between is counted as a preparation failure.
     */
    public void commitWithMainIndex() {
        if (closed) {
            return;
        }
        try {
            service.awaitSubmittedSoFar();
            writer.commit();
            staging.commit();
            BuildRecord.update(store.buildRecord(), r -> {
                ModalityRecord mr = r.get(Modality.TEXT);
                if (mr != null) {
                    Map<String, Object> timing = mr.timing == null ? new LinkedHashMap<>()
                            : new LinkedHashMap<>(mr.timing);
                    timing.put("lastCommitAt", Instant.now().toString());
                    mr.timing = timing;
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Semantic text index commit alongside the main index failed", e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // End of processing
    // ---------------------------------------------------------------------------------------------

    /**
     * Drains the queue, builds the conversation sequences, commits, and records the result:
     * {@code COMPLETE} (or {@code DISABLED_MISMATCH}), counts per reason and timing (FR-021).
     */
    public void finish() throws IOException, InterruptedException {
        long f0 = System.currentTimeMillis();
        service.drain();
        buildSequences();
        service.drain();
        writer.commit();
        staging.commit();

        Map<String, Long> counts = countReasons();
        long now = System.currentTimeMillis();
        long taskMs = TimeUnit.NANOSECONDS.toMillis(taskNanos.get());
        long inferenceMs = service.getInferenceMillis();
        long finishMs = now - f0;
        BuildRecord.update(store.buildRecord(), r -> {
            ModalityRecord mr = r.getOrCreate(Modality.TEXT);
            mr.state = mismatch ? State.DISABLED_MISMATCH : State.COMPLETE;
            mr.counts = counts;
            Map<String, Object> timing = mr.timing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mr.timing);
            timing.put("finishedAt", Instant.ofEpochMilli(now).toString());
            timing.put("durationMs", now - startedAtMillis);
            timing.put("taskMs", taskMs);
            timing.put("inferenceMs", inferenceMs);
            timing.put("finishMs", finishMs);
            mr.timing = timing;
        });
        long covered = counts.getOrDefault(CoverageReason.COVERED.code(), 0L)
                + counts.getOrDefault(CoverageReason.COVERED_TRUNCATED.code(), 0L);
        Map<String, Long> notCovered = new TreeMap<>(counts);
        notCovered.remove(CoverageReason.COVERED.code());
        notCovered.remove(CoverageReason.COVERED_TRUNCATED.code());
        LOGGER.info("Semantic text preparation {}: {} items covered, not covered by reason {}; items seen in this "
                + "processing: {}; time {} ms (worker {} ms, model {} ms summed over threads, sequences and commit "
                + "{} ms)", mismatch ? "DISABLED (model mismatch)" : "complete", covered, notCovered, itemsSeen.get(),
                now - startedAtMillis, taskMs, inferenceMs, finishMs);
        close();
        staging.closeAndDelete();
    }

    private void buildSequences() throws IOException, InterruptedException {
        List<Integer> chats = staging.chats();
        LOGGER.info("Semantic text: building conversation sequences of {} chats ({} messages)", chats.size(),
                staging.size());
        for (int chatId : chats) {
            List<StagingStore.Message> messages = staging.messagesOf(chatId);
            if (mismatch) {
                for (StagingStore.Message m : messages) {
                    safeStatus(m.itemId, CoverageReason.MODEL_MISMATCH, null);
                }
                continue;
            }
            List<ChatSequenceBuilder.Window> windows = sequences.build(messages);
            if (windows.isEmpty()) {
                // no message with text: the chat keeps its document passages
                for (StagingStore.Message m : messages) {
                    safeStatus(m.itemId, CoverageReason.NO_TEXT, null);
                }
                continue;
            }
            ChatJob job = new ChatJob(chatId, messages, windows);
            for (WindowUnit w : job.units) {
                service.submit(w, w.window.embeddingText());
            }
        }
    }

    /** All windows of one chat; the chat is rewritten when the last vector arrives. */
    private final class ChatJob {
        final int chatId;
        final List<StagingStore.Message> messages;
        final List<WindowUnit> units = new ArrayList<>();
        final float[][] vectors;
        final AtomicInteger remaining;
        volatile Exception failure;

        ChatJob(int chatId, List<StagingStore.Message> messages, List<ChatSequenceBuilder.Window> windows) {
            this.chatId = chatId;
            this.messages = messages;
            for (int i = 0; i < windows.size(); i++) {
                units.add(new WindowUnit(this, i, windows.get(i)));
            }
            this.vectors = new float[windows.size()][];
            this.remaining = new AtomicInteger(windows.size());
        }

        void done() {
            if (remaining.decrementAndGet() > 0) {
                return;
            }
            try {
                if (failure != null) {
                    // the chat keeps its document passages; its messages are not covered
                    for (StagingStore.Message m : messages) {
                        status(m.itemId, failureReason(failure), failure.toString());
                    }
                    return;
                }
                List<Document> docs = new ArrayList<>();
                Set<Integer> inSequences = new HashSet<>();
                for (WindowUnit u : units) {
                    docs.add(SemanticIndexWriter.sequence(chatId, u.window.ordinal(), u.window.lines(),
                            vectors[u.index]));
                    for (SequenceLine l : u.window.lines()) {
                        inSequences.add(l.getItemId());
                    }
                }
                docs.add(SemanticIndexWriter.status(chatId, CoverageReason.COVERED_BY_MESSAGES, null));
                // one operation: the chat's document passages become its sequences (case-storage.md)
                writer.replaceItem(chatId, docs);
                for (StagingStore.Message m : messages) {
                    status(m.itemId, inSequences.contains(m.itemId) ? CoverageReason.COVERED : CoverageReason.NO_TEXT,
                            null);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not write the conversation sequences of chat {}", chatId, e);
                for (StagingStore.Message m : messages) {
                    safeStatus(m.itemId, CoverageReason.PREP_FAILURE, e.toString());
                }
            }
        }
    }

    private final class WindowUnit implements Pending {
        final ChatJob job;
        final int index;
        final ChatSequenceBuilder.Window window;

        WindowUnit(ChatJob job, int index, ChatSequenceBuilder.Window window) {
            this.job = job;
            this.index = index;
            this.window = window;
        }

        @Override
        public void vector(float[] v) {
            job.vectors[index] = v;
            job.done();
        }

        @Override
        public void failed(Exception e) {
            job.failure = e;
            job.done();
        }
    }

    /** Status documents per reason code, over the whole committed index (so appends add up). */
    private Map<String, Long> countReasons() throws IOException {
        Map<CoverageReason, Long> byReason = new EnumMap<>(CoverageReason.class);
        try (DirectoryReader reader = DirectoryReader.open(FSDirectory.open(store.index(Modality.TEXT)))) {
            for (LeafReaderContext ctx : reader.leaves()) {
                SortedDocValues dv = ctx.reader().getSortedDocValues(SemanticFields.REASON);
                if (dv == null) {
                    continue;
                }
                Bits live = ctx.reader().getLiveDocs();
                for (int doc = dv.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = dv.nextDoc()) {
                    if (live != null && !live.get(doc)) {
                        continue;
                    }
                    CoverageReason r = CoverageReason.fromCode(dv.lookupOrd(dv.ordValue()).utf8ToString());
                    if (r != null) {
                        byReason.merge(r, 1L, Long::sum);
                    }
                }
            }
        }
        Map<String, Long> out = new LinkedHashMap<>();
        byReason.forEach((k, v) -> out.put(k.code(), v));
        return out;
    }

    /** Stops the threads and closes the index; uncommitted changes are discarded. */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        committer.shutdownNow();
        service.close();
        writer.close();
    }
}
