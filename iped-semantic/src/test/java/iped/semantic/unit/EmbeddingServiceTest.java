package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import iped.semantic.FakeEmbedder;
import iped.semantic.model.Embedder;
import iped.semantic.model.EmbeddingException;
import iped.semantic.model.EmbeddingService;

public class EmbeddingServiceTest {

    @Test
    public void submitBlocksWhileTheQueueIsFull() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeEmbedder base = new FakeEmbedder();
        Embedder blocking = new Embedder() {
            @Override
            public float[][] embedTexts(Role role, List<String> texts) throws EmbeddingException {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new EmbeddingException("interrupted", e);
                }
                return base.embedTexts(role, texts);
            }

            @Override
            public iped.semantic.model.ModelIdentity identity() {
                return base.identity();
            }
        };
        Set<Integer> delivered = ConcurrentHashMap.newKeySet();
        EmbeddingService<Integer> svc = new EmbeddingService<>("t", blocking, Embedder.Role.PASSAGE, 1, 1, 2,
                new EmbeddingService.Callback<Integer>() {
                    public void onEmbedded(List<Integer> p, float[][] v) {
                        delivered.addAll(p);
                    }

                    public void onFailure(List<Integer> p, Exception e) {
                    }
                });
        svc.submit(1, "um"); // taken by the inference thread, which blocks inside the model
        Thread.sleep(200);
        svc.submit(2, "dois");
        svc.submit(3, "três"); // queue (capacity 2) is now full
        AtomicBoolean fourthReturned = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                svc.submit(4, "quatro");
                fourthReturned.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        worker.join(500);
        assertFalse("submit must block while the queue is full", fourthReturned.get());
        release.countDown();
        worker.join(5000);
        assertTrue(fourthReturned.get());
        svc.drain();
        assertEquals(Set.of(1, 2, 3, 4), delivered);
        svc.close();
    }

    @Test
    public void drainDeliversEverythingInBatches() throws Exception {
        FakeEmbedder e = new FakeEmbedder();
        Set<Integer> delivered = ConcurrentHashMap.newKeySet();
        AtomicInteger maxBatch = new AtomicInteger();
        EmbeddingService<Integer> svc = new EmbeddingService<>("t", e, Embedder.Role.PASSAGE, 4, 32, 64,
                new EmbeddingService.Callback<Integer>() {
                    public void onEmbedded(List<Integer> p, float[][] v) {
                        assertEquals(p.size(), v.length);
                        maxBatch.accumulateAndGet(p.size(), Math::max);
                        delivered.addAll(p);
                    }

                    public void onFailure(List<Integer> p, Exception ex) {
                    }
                });
        for (int i = 0; i < 1000; i++) {
            svc.submit(i, "texto número " + i);
        }
        svc.drain();
        assertEquals(1000, delivered.size());
        assertEquals(0, svc.getInFlight());
        assertTrue(maxBatch.get() <= 32);
        assertEquals(1000, svc.getEmbeddedCount());
        svc.close();
    }

    @Test
    public void aFailingBatchReachesTheFailureCallbackAndTheOthersGoOn() throws Exception {
        FakeEmbedder base = new FakeEmbedder();
        Embedder flaky = new Embedder() {
            @Override
            public float[][] embedTexts(Role role, List<String> texts) throws EmbeddingException {
                if (texts.contains("quebra")) {
                    throw new EmbeddingException("boom");
                }
                return base.embedTexts(role, texts);
            }

            @Override
            public iped.semantic.model.ModelIdentity identity() {
                return base.identity();
            }
        };
        Set<Integer> ok = ConcurrentHashMap.newKeySet();
        Set<Integer> failed = ConcurrentHashMap.newKeySet();
        EmbeddingService<Integer> svc = new EmbeddingService<>("t", flaky, Embedder.Role.PASSAGE, 1, 1, 8,
                new EmbeddingService.Callback<Integer>() {
                    public void onEmbedded(List<Integer> p, float[][] v) {
                        ok.addAll(p);
                    }

                    public void onFailure(List<Integer> p, Exception ex) {
                        failed.addAll(p);
                    }
                });
        svc.submit(1, "bom");
        svc.submit(2, "quebra");
        svc.submit(3, "bom de novo");
        svc.drain();
        assertEquals(Set.of(1, 3), ok);
        assertEquals(Set.of(2), failed);
        svc.close();
        assertTrue(TimeUnit.MILLISECONDS.toMillis(svc.getInferenceMillis()) >= 0);
    }
}
