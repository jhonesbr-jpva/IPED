package iped.semantic.task;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
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

import javax.imageio.ImageIO;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.Source;
import iped.semantic.model.Embedder;
import iped.semantic.model.EmbeddingService;
import iped.semantic.model.Fingerprint;
import iped.semantic.model.RemoteEmbedders;
import iped.semantic.prep.FrameInstant;
import iped.semantic.store.BuildRecord;
import iped.semantic.store.BuildRecord.ModalityRecord;
import iped.semantic.store.BuildRecord.ModelRecord;
import iped.semantic.store.BuildRecord.State;
import iped.semantic.store.CoverageReason;
import iped.semantic.store.SemanticFields;
import iped.semantic.store.SemanticIndexWriter;
import iped.semantic.store.SemanticStore;

/**
 * The visual modality of one case being processed (research D8): images by their thumbnail, videos
 * by the frames of the mosaic {@code VideoThumbTask} already made - nothing is decoded again - each
 * key governing only what it names (story 6).
 *
 * <p>
 * Every item the task sees gets a status, so the coverage of any scope adds up: an item of a key
 * that is off is {@code MODALITY_OFF}, an item that is neither image nor video is
 * {@code CATEGORY_NOT_SELECTED} (the visual categories are images and videos unless configured
 * otherwise), and a frame extracted as a subitem while videos are on is
 * {@code COVERED_BY_SOURCE_VIDEO}. A video is stored as one document per frame; the search shows it
 * once, by its best frame.
 * </p>
 *
 * <p>
 * The build record keeps the keys, the configuration conflicts it detected, and the time spent on
 * images and on videos separately (FR-021, SC-015). Both go through the same image encoder at the
 * same input size, so the model time is apportioned between them by the number of pictures each
 * sent, which the record says.
 * </p>
 */
public final class VisualPreparation implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisualPreparation.class);

    /** Videos on while video thumbnails, whose mosaic is the source of the frames, are off. */
    public static final String CONFLICT_VIDEOS_WITHOUT_THUMBS = "VIDEOS_ON_WITHOUT_VIDEO_THUMBS";
    /** Images on while image thumbnails, the source of the pictures, are off. */
    public static final String CONFLICT_IMAGES_WITHOUT_THUMBS = "IMAGES_ON_WITHOUT_IMAGE_THUMBS";

    /** What the task knows about the processing, beyond the semantic configuration. */
    public static final class Settings {
        boolean images;
        boolean videos;
        boolean imageThumbs = true;
        boolean videoThumbs = true;
        Integer capabilityStartId;
        String ipedVersion;

        public Settings images(boolean b) {
            this.images = b;
            return this;
        }

        public Settings videos(boolean b) {
            this.videos = b;
            return this;
        }

        /** Whether {@code ImageThumbTask} runs in this processing. */
        public Settings imageThumbs(boolean b) {
            this.imageThumbs = b;
            return this;
        }

        /** Whether {@code VideoThumbTask} runs in this processing. */
        public Settings videoThumbs(boolean b) {
            this.videoThumbs = b;
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

    private interface Pending {
        void vector(float[] v);

        void failed(Exception e);
    }

    /** Time and volume of one key. */
    private static final class Tally {
        final AtomicLong taskNanos = new AtomicLong();
        final AtomicLong items = new AtomicLong();
        final AtomicLong pictures = new AtomicLong();

        Map<String, Object> toRecord(long inferenceMs, long allPictures) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("items", items.get());
            m.put("pictures", pictures.get());
            m.put("taskMs", TimeUnit.NANOSECONDS.toMillis(taskNanos.get()));
            m.put("inferenceMsApportioned", allPictures == 0 ? 0 : inferenceMs * pictures.get() / allPictures);
            m.put("durationMs", (Long) m.get("taskMs") + (Long) m.get("inferenceMsApportioned"));
            return m;
        }
    }

    private final SemanticSearchConfig config;
    private final SemanticStore store;
    private final Settings settings;
    private final SemanticIndexWriter writer;
    private final EmbeddingService<Pending> service;
    private final boolean mismatch;
    private final Set<String> categories = new HashSet<>();
    private final long startedAtMillis = System.currentTimeMillis();
    private final Tally images = new Tally();
    private final Tally videos = new Tally();
    private final AtomicLong itemsSeen = new AtomicLong();
    private final ScheduledExecutorService committer;
    private volatile boolean closed;
    private final PreparationSupport.Stop stop = new PreparationSupport.Stop();

    /**
     * Opens (or resumes) the visual preparation of a case: writes the build record before the
     * first document, then opens the index and the embedding service.
     */
    public static VisualPreparation open(File moduleDir, SemanticSearchConfig config,
            SourceValidator.Validated source, Settings settings) throws IOException {
        return new VisualPreparation(moduleDir, config, source, settings);
    }

    private VisualPreparation(File moduleDir, SemanticSearchConfig config, SourceValidator.Validated source,
            Settings settings) throws IOException {
        this.config = config;
        this.settings = settings;
        this.store = SemanticStore.of(moduleDir);
        Embedder embedder = source.embedder();
        for (String c : config.getVisualCategories()) {
            categories.add(c.toLowerCase(Locale.ROOT).trim());
        }

        BuildRecord existing = BuildRecord.read(store.buildRecord());
        ModalityRecord previous = existing == null ? null : existing.get(Modality.VISUAL);
        String mismatchReason = null;
        if (previous != null) {
            Fingerprint recorded = previous.fingerprint;
            Fingerprint.Comparison cmp = recorded == null ? null
                    : recorded.compare(previous.identity(), source.fingerprint(), embedder.identity(),
                            config.getFingerprintTolerance());
            if (cmp == null || !cmp.isCompatible()) {
                mismatchReason = cmp == null ? "the case records no fingerprint for the visual modality"
                        : cmp.reason();
            }
        }
        this.mismatch = mismatchReason != null;
        final String reason = mismatchReason;
        final List<String> conflicts = conflicts(settings);
        BuildRecord.update(store.buildRecord(), r -> {
            r.ipedVersion = settings.ipedVersion;
            ModalityRecord mr = r.get(Modality.VISUAL);
            if (mr == null) {
                mr = r.getOrCreate(Modality.VISUAL);
                mr.source = config.getModelSource(Modality.VISUAL) == Source.REMOTE ? "remote" : "local";
                mr.model = ModelRecord.of(embedder.identity());
                mr.fingerprint = source.fingerprint();
                mr.params = params(config);
                mr.capabilityStartId = settings.capabilityStartId;
                if (config.getModelSource(Modality.VISUAL) == Source.REMOTE) {
                    mr.remote = new BuildRecord.RemoteRecord(config.getRemoteProtocol(Modality.VISUAL),
                            RemoteEmbedders.safeDestination(config.getRemoteEndpoint(Modality.VISUAL)));
                }
            } else if (mr.state == State.PREPARING) {
                LOGGER.warn("Semantic visual: the previous preparation of this case did not finish; resuming it");
            }
            Map<String, Boolean> keys = new LinkedHashMap<>();
            keys.put("images", settings.images);
            keys.put("videos", settings.videos);
            mr.keys = keys;
            mr.conflicts = conflicts;
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
        for (String c : conflicts) {
            LOGGER.warn("Semantic visual search: configuration conflict {}: {}", c,
                    CONFLICT_VIDEOS_WITHOUT_THUMBS.equals(c)
                            ? "videos are on but video thumbnails (enableVideoThumbs) are off, so no video has "
                                    + "frames and every one is recorded as " + CoverageReason.VIDEO_NO_FRAMES
                            : "images are on but image thumbnails (enableImageThumbs) are off, so no image has a "
                                    + "picture to embed and every one is recorded as " + CoverageReason.NO_THUMBNAIL);
        }
        if (mismatch) {
            LOGGER.error("Semantic visual search is DISABLED for this processing: {}. Items added now are recorded as "
                    + "not covered ({}); the rest of the processing goes on (research D16).", mismatchReason,
                    CoverageReason.MODEL_MISMATCH);
        }

        this.writer = new SemanticIndexWriter(store.index(Modality.VISUAL), new StandardAnalyzer(),
                config.getHnswMaxConn(), config.getHnswBeamWidth());
        this.service = new EmbeddingService<>("semantic-visual", embedder, Embedder.Role.PASSAGE,
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
                        if (stop.record(error)) {
                            LOGGER.error("Semantic visual: the model source refused the request ({}); the modality "
                                    + "stops sending, and the remaining items are recorded as {}", error.getMessage(),
                                    CoverageReason.REMOTE_UNAVAILABLE);
                        }
                        for (Pending p : payloads) {
                            p.failed(error);
                        }
                    }
                });
        this.committer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "semantic-visual-commit");
            t.setDaemon(true);
            return t;
        });
        long interval = TimeUnit.MINUTES.toMillis(config.getCommitIntervalMinutes());
        committer.scheduleWithFixedDelay(() -> {
            try {
                if (!closed) {
                    writer.commitIfDue(interval);
                }
            } catch (Exception e) {
                LOGGER.warn("Periodic commit of the semantic visual index failed", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    static List<String> conflicts(Settings s) {
        List<String> out = BuildRecord.newConflicts();
        if (s.videos && !s.videoThumbs) {
            out.add(CONFLICT_VIDEOS_WITHOUT_THUMBS);
        }
        if (s.images && !s.imageThumbs) {
            out.add(CONFLICT_IMAGES_WITHOUT_THUMBS);
        }
        return out;
    }

    static Map<String, Object> params(SemanticSearchConfig c) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("minImageSize", c.getMinImageSize());
        p.put("categories", new ArrayList<>(c.getVisualCategories()));
        p.put("frameSource", "videoThumbMosaic");
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
    public void prepare(VisualItem item) throws InterruptedException {
        long t0 = System.nanoTime();
        itemsSeen.incrementAndGet();
        Tally tally = item.kind == VisualItem.Kind.VIDEO ? videos
                : item.kind == VisualItem.Kind.OTHER ? null : images;
        try {
            if (mismatch) {
                status(item.id, CoverageReason.MODEL_MISMATCH, null);
                return;
            }
            switch (item.kind) {
                case OTHER:
                    status(item.id, CoverageReason.CATEGORY_NOT_SELECTED, "not an image or a video");
                    return;
                case VIDEO_FRAME_SUBITEM:
                    if (settings.videos) {
                        status(item.id, CoverageReason.COVERED_BY_SOURCE_VIDEO, null);
                        return;
                    }
                    prepareImage(item);
                    return;
                case IMAGE:
                    prepareImage(item);
                    return;
                case VIDEO:
                    prepareVideo(item);
                    return;
                default:
                    throw new IllegalStateException("unknown kind " + item.kind);
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            LOGGER.warn("Semantic visual preparation failed for item {}: {}", item.id, e.toString());
            safeStatus(item.id, CoverageReason.PREP_FAILURE, e.toString());
        } finally {
            if (tally != null) {
                tally.taskNanos.addAndGet(System.nanoTime() - t0);
            }
        }
    }

    private boolean selected(VisualItem item) {
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

    private void prepareImage(VisualItem item) throws IOException, InterruptedException {
        if (!selected(item)) {
            status(item.id, CoverageReason.CATEGORY_NOT_SELECTED, null);
            return;
        }
        if (!settings.images) {
            status(item.id, CoverageReason.MODALITY_OFF, "enableSemanticImages = false");
            return;
        }
        if (stop.reason() != null) {
            status(item.id, CoverageReason.REMOTE_UNAVAILABLE, "modality stopped: " + stop.reason());
            return;
        }
        int min = config.getMinImageSize();
        if (item.width != null && item.height != null && (item.width < min || item.height < min)) {
            status(item.id, CoverageReason.IMAGE_TOO_SMALL, item.width + "x" + item.height);
            return;
        }
        byte[] thumb = item.thumb == null ? null : item.thumb.read();
        if (thumb == null || thumb.length == 0) {
            status(item.id, CoverageReason.NO_THUMBNAIL, null);
            return;
        }
        BufferedImage picture;
        try {
            picture = ImageIO.read(new ByteArrayInputStream(thumb));
        } catch (IOException | RuntimeException e) {
            status(item.id, CoverageReason.IMAGE_UNDECODABLE, e.toString());
            return;
        }
        if (picture == null) {
            status(item.id, CoverageReason.IMAGE_UNDECODABLE, "no reader for the thumbnail");
            return;
        }
        images.items.incrementAndGet();
        service.submitImage(new ImageUnit(item.id), picture);
    }

    private void prepareVideo(VisualItem item) throws IOException, InterruptedException {
        if (!selected(item)) {
            status(item.id, CoverageReason.CATEGORY_NOT_SELECTED, null);
            return;
        }
        if (!settings.videos) {
            status(item.id, CoverageReason.MODALITY_OFF, "enableSemanticVideos = false");
            return;
        }
        if (stop.reason() != null) {
            status(item.id, CoverageReason.REMOTE_UNAVAILABLE, "modality stopped: " + stop.reason());
            return;
        }
        List<BufferedImage> frames = item.frames == null ? null : item.frames.read();
        if (frames == null || frames.isEmpty()) {
            status(item.id, CoverageReason.VIDEO_NO_FRAMES, null);
            return;
        }
        videos.items.incrementAndGet();
        VideoJob job = new VideoJob(item.id, frames.size(), item.durationMs, item.fps);
        for (int k = 0; k < frames.size(); k++) {
            service.submitImage(new FrameUnit(job, k), frames.get(k));
        }
    }

    private void status(int id, CoverageReason reason, String detail) throws IOException {
        writer.replaceItem(id, List.of(SemanticIndexWriter.status(id, reason, detail)));
    }

    private void safeStatus(int id, CoverageReason reason, String detail) {
        try {
            status(id, reason, detail);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not record the semantic visual status of item {}", id, e);
        }
    }

    private final class ImageUnit implements Pending {
        final int itemId;

        ImageUnit(int itemId) {
            this.itemId = itemId;
        }

        @Override
        public void vector(float[] v) {
            try {
                List<Document> docs = new ArrayList<>(2);
                docs.add(SemanticIndexWriter.visual(SemanticFields.KIND_IMAGE, itemId, null, null, null, v));
                docs.add(SemanticIndexWriter.status(itemId, CoverageReason.COVERED, null));
                writer.replaceItem(itemId, docs);
                images.pictures.incrementAndGet();
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not write the semantic visual documents of item {}", itemId, e);
                safeStatus(itemId, CoverageReason.PREP_FAILURE, e.toString());
            }
        }

        @Override
        public void failed(Exception e) {
            safeStatus(itemId, PreparationSupport.failureReason(e), e.toString());
        }
    }

    /** All frames of one video; written in one operation when the last vector arrives. */
    private final class VideoJob {
        final int itemId;
        final int frameCount;
        final Long durationMs;
        final Double fps;
        final float[][] vectors;
        final AtomicInteger remaining;
        volatile Exception failure;

        VideoJob(int itemId, int frameCount, Long durationMs, Double fps) {
            this.itemId = itemId;
            this.frameCount = frameCount;
            this.durationMs = durationMs;
            this.fps = fps;
            this.vectors = new float[frameCount][];
            this.remaining = new AtomicInteger(frameCount);
        }

        void done() {
            if (remaining.decrementAndGet() > 0) {
                return;
            }
            try {
                if (failure != null) {
                    status(itemId, PreparationSupport.failureReason(failure), failure.toString());
                    return;
                }
                List<Document> docs = new ArrayList<>(frameCount + 1);
                for (int k = 0; k < frameCount; k++) {
                    docs.add(SemanticIndexWriter.visual(SemanticFields.KIND_VIDEO_FRAME, itemId, k, frameCount,
                            FrameInstant.approxMillis(durationMs, fps, k, frameCount), vectors[k]));
                }
                docs.add(SemanticIndexWriter.status(itemId, CoverageReason.COVERED, "frames: " + frameCount));
                writer.replaceItem(itemId, docs);
                videos.pictures.addAndGet(frameCount);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not write the semantic visual documents of video {}", itemId, e);
                safeStatus(itemId, CoverageReason.PREP_FAILURE, e.toString());
            }
        }
    }

    private final class FrameUnit implements Pending {
        final VideoJob job;
        final int index;

        FrameUnit(VideoJob job, int index) {
            this.job = job;
            this.index = index;
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
    // Commit alignment and end of processing
    // ---------------------------------------------------------------------------------------------

    /** As {@link TextPreparation#commitWithMainIndex()}: durable before the main commit. */
    public void commitWithMainIndex() {
        if (closed) {
            return;
        }
        try {
            service.awaitSubmittedSoFar();
            writer.commit();
            BuildRecord.update(store.buildRecord(), r -> {
                ModalityRecord mr = r.get(Modality.VISUAL);
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
            LOGGER.warn("Semantic visual index commit alongside the main index failed", e);
        }
    }

    /**
     * Drains the queue, commits, and records the result: {@code COMPLETE} (or
     * {@code DISABLED_MISMATCH}), counts per reason and the timing of images and videos (FR-021).
     */
    public void finish() throws IOException, InterruptedException {
        service.drain();
        writer.commit();

        Map<String, Long> counts = PreparationSupport.countReasons(store.index(Modality.VISUAL));
        long now = System.currentTimeMillis();
        long inferenceMs = service.getInferenceMillis();
        long allPictures = images.pictures.get() + videos.pictures.get();
        Map<String, Object> imageTiming = images.toRecord(inferenceMs, allPictures);
        Map<String, Object> videoTiming = videos.toRecord(inferenceMs, allPictures);
        BuildRecord.update(store.buildRecord(), r -> {
            ModalityRecord mr = r.getOrCreate(Modality.VISUAL);
            String stopped = stop.reason();
            mr.state = mismatch ? State.DISABLED_MISMATCH : stopped != null ? State.PARTIAL : State.COMPLETE;
            if (!mismatch && stopped != null) {
                mr.disabledReason = "stopped during processing: " + stopped;
            }
            mr.counts = counts;
            Map<String, Object> timing = mr.timing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mr.timing);
            timing.put("finishedAt", Instant.ofEpochMilli(now).toString());
            timing.put("durationMs", now - startedAtMillis);
            timing.put("inferenceMs", inferenceMs);
            timing.put("images", imageTiming);
            timing.put("videos", videoTiming);
            mr.timing = timing;
        });
        Map<String, Long> notCovered = new TreeMap<>(counts);
        notCovered.remove(CoverageReason.COVERED.code());
        LOGGER.info("Semantic visual preparation {}: {} items covered, not covered by reason {}; items seen in this "
                + "processing: {}; images {}, videos {}", mismatch ? "DISABLED (model mismatch)" : "complete",
                counts.getOrDefault(CoverageReason.COVERED.code(), 0L), notCovered, itemsSeen.get(), imageTiming,
                videoTiming);
        close();
    }

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
