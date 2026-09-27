package iped.semantic.model;

import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves embedding off the processing workers (research D15). Workers {@link #submit} and return; a
 * few inference threads take batches from a bounded queue, embed them and hand the vectors to a
 * callback, which writes the index. When the queue is full, {@code submit} blocks: that is how the
 * capability bounds its memory during processing.
 *
 * <p>
 * One service per model and per JVM, shared by the task instances of every worker. The callback
 * runs on the inference threads and must be thread-safe.
 * </p>
 *
 * @param <T>
 *            what the caller needs back with each vector (a passage, a frame, ...)
 */
public final class EmbeddingService<T> implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingService.class);

    /** Receives the results; called on an inference thread. */
    public interface Callback<T> {
        void onEmbedded(List<T> payloads, float[][] vectors);

        void onFailure(List<T> payloads, Exception error);
    }

    private static final class Request<T> {
        final T payload;
        final String text;
        final BufferedImage image;

        Request(T payload, String text, BufferedImage image) {
            this.payload = payload;
            this.text = text;
            this.image = image;
        }
    }

    private final Embedder embedder;
    private final Embedder.Role role;
    private final int batchSize;
    private final Callback<T> callback;
    private final BlockingQueue<Request<T>> queue;
    private final List<Thread> threads = new ArrayList<>();
    private final Object idleLock = new Object();
    private final AtomicLong inferenceNanos = new AtomicLong();
    private final AtomicLong embedded = new AtomicLong();
    private int inFlight;
    private volatile boolean closed;

    /**
     * @param role
     *            role of submitted texts; ignored for images
     */
    public EmbeddingService(String name, Embedder embedder, Embedder.Role role, int threads, int batchSize,
            int capacity, Callback<T> callback) {
        this.embedder = embedder;
        this.role = role;
        this.batchSize = Math.max(1, batchSize);
        this.callback = callback;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, capacity));
        for (int i = 0; i < Math.max(1, threads); i++) {
            Thread t = new Thread(this::loop, name + "-" + i);
            t.setDaemon(true);
            this.threads.add(t);
            t.start();
        }
    }

    /** Queues a text; blocks while the queue is full. */
    public void submit(T payload, String text) throws InterruptedException {
        put(new Request<>(payload, text, null));
    }

    /** Queues an image; blocks while the queue is full. */
    public void submitImage(T payload, BufferedImage image) throws InterruptedException {
        put(new Request<>(payload, null, image));
    }

    private void put(Request<T> r) throws InterruptedException {
        if (closed) {
            throw new IllegalStateException("embedding service closed");
        }
        synchronized (idleLock) {
            inFlight++;
        }
        try {
            queue.put(r);
        } catch (InterruptedException e) {
            done(1);
            throw e;
        }
    }

    /** Waits until every submitted item has been embedded and handed to the callback. */
    public void drain() throws InterruptedException {
        synchronized (idleLock) {
            while (inFlight > 0) {
                idleLock.wait(1000);
            }
        }
    }

    private void done(int n) {
        synchronized (idleLock) {
            inFlight -= n;
            if (inFlight <= 0) {
                idleLock.notifyAll();
            }
        }
    }

    private void loop() {
        List<Request<T>> batch = new ArrayList<>(batchSize);
        while (!closed || !queue.isEmpty()) {
            try {
                Request<T> first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                process(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void process(List<Request<T>> batch) {
        // A batch mixes texts and images only if the caller mixes them; handle each kind apart.
        List<T> textPayloads = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<T> imagePayloads = new ArrayList<>();
        List<BufferedImage> images = new ArrayList<>();
        for (Request<T> r : batch) {
            if (r.image != null) {
                imagePayloads.add(r.payload);
                images.add(r.image);
            } else {
                textPayloads.add(r.payload);
                texts.add(r.text);
            }
        }
        try {
            if (!texts.isEmpty()) {
                run(textPayloads, () -> embedder.embedTexts(role, texts));
            }
            if (!images.isEmpty()) {
                run(imagePayloads, () -> embedder.embedImages(images));
            }
        } finally {
            done(batch.size());
        }
    }

    private interface Call {
        float[][] get() throws EmbeddingException;
    }

    private void run(List<T> payloads, Call call) {
        float[][] vectors;
        long t0 = System.nanoTime();
        try {
            vectors = call.get();
        } catch (Exception | LinkageError e) {
            inferenceNanos.addAndGet(System.nanoTime() - t0);
            LOGGER.debug("Embedding batch of {} failed", payloads.size(), e);
            safeFailure(payloads, e instanceof Exception ? (Exception) e : new RuntimeException(e));
            return;
        }
        inferenceNanos.addAndGet(System.nanoTime() - t0);
        embedded.addAndGet(payloads.size());
        try {
            callback.onEmbedded(payloads, vectors);
        } catch (RuntimeException e) {
            LOGGER.warn("Semantic index callback failed for a batch of {}", payloads.size(), e);
            safeFailure(payloads, e);
        }
    }

    private void safeFailure(List<T> payloads, Exception e) {
        try {
            callback.onFailure(payloads, e);
        } catch (RuntimeException e2) {
            LOGGER.error("Semantic failure callback threw", e2);
        }
    }

    /** Time spent inside the model, summed over threads (FR-021). */
    public long getInferenceMillis() {
        return TimeUnit.NANOSECONDS.toMillis(inferenceNanos.get());
    }

    public long getEmbeddedCount() {
        return embedded.get();
    }

    /** Pending plus in-progress items. */
    public int getInFlight() {
        synchronized (idleLock) {
            return inFlight;
        }
    }

    /** Drains, then stops the threads. */
    @Override
    public void close() {
        try {
            drain();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closed = true;
        for (Thread t : threads) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
