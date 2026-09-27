package iped.semantic.unit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

import iped.semantic.FakeEmbedder;
import iped.semantic.TestSupport;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.model.Fingerprint;
import iped.semantic.store.BuildRecord;
import iped.semantic.store.BuildRecord.ModalityRecord;
import iped.semantic.store.BuildRecord.ModelRecord;
import iped.semantic.store.BuildRecord.RemoteRecord;
import iped.semantic.store.BuildRecord.State;

public class BuildRecordTest {

    private static BuildRecord sample() throws Exception {
        FakeEmbedder e = new FakeEmbedder();
        BuildRecord r = new BuildRecord();
        r.ipedVersion = "4.3.1";
        ModalityRecord t = r.getOrCreate(Modality.TEXT);
        t.state = State.COMPLETE;
        t.source = "remote";
        t.model = ModelRecord.of(e.identity());
        t.remote = new RemoteRecord("iped-embed/1", "https://gpu-lab.example/iped");
        t.fingerprint = Fingerprint.compute(e, false, 0.9995);
        t.params = Map.of("passageChars", 1000, "passageOverlapChars", 200);
        t.timing = BuildRecord.timing("2026-09-26T10:00:00Z", "2026-09-26T10:05:00Z", 300000);
        t.counts = Map.of("COVERED", 10L, "NO_TEXT", 3L);
        t.conflicts = List.of();
        return r;
    }

    @Test
    public void roundTripsThroughJson() throws Exception {
        Path dir = TestSupport.tempDir("br");
        Path file = dir.resolve("build-record.json");
        BuildRecord r = sample();
        r.write(file);
        BuildRecord back = BuildRecord.read(file);
        assertEquals(BuildRecord.SCHEMA, back.schema);
        ModalityRecord t = back.get(Modality.TEXT);
        assertEquals(State.COMPLETE, t.state);
        assertEquals("remote", t.source);
        assertEquals("preparation", t.remote.phase);
        assertEquals(r.get(Modality.TEXT).identity(), t.identity());
        assertEquals("model", t.model.reportedBy);
        assertEquals(16, t.fingerprint.size());
        for (int i = 0; i < 16; i++) {
            assertArrayEquals(r.get(Modality.TEXT).fingerprint.getVectors().get(i), t.fingerprint.getVectors().get(i),
                    0f);
        }
        assertEquals(Long.valueOf(3), t.counts.get("NO_TEXT"));
        assertEquals(300000, ((Number) t.timing.get("durationMs")).intValue());
        String json = Files.readString(file);
        assertTrue(json, json.contains("\"schema\" : 1"));
    }

    @Test
    public void writeIsAtomicAndLeavesNoTemporaryFile() throws Exception {
        Path dir = TestSupport.tempDir("br");
        Path file = dir.resolve("build-record.json");
        sample().write(file);
        sample().write(file); // overwrite
        try (Stream<Path> s = Files.list(dir)) {
            List<String> names = s.map(p -> p.getFileName().toString()).collect(Collectors.toList());
            assertEquals(List.of("build-record.json"), names);
        }
    }

    @Test
    public void concurrentUpdatesOfTheTwoModalitiesKeepBoth() throws Exception {
        Path file = TestSupport.tempDir("br").resolve("build-record.json");
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        for (Modality m : Modality.values()) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        final int n = i;
                        BuildRecord.update(file, r -> r.getOrCreate(m).counts = Map.of("COVERED", (long) n));
                    }
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });
            threads.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }
        assertEquals(List.of(), errors);
        BuildRecord r = BuildRecord.read(file);
        assertEquals(Long.valueOf(49), r.get(Modality.TEXT).counts.get("COVERED"));
        assertEquals(Long.valueOf(49), r.get(Modality.VISUAL).counts.get("COVERED"));
    }

    @Test
    public void noCredentialFieldExists() throws Exception {
        for (Class<?> c : new Class<?>[] { BuildRecord.class, ModalityRecord.class, ModelRecord.class,
                RemoteRecord.class, Fingerprint.class }) {
            for (Field f : c.getDeclaredFields()) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                assertFalse(c.getSimpleName() + "." + f.getName() + " looks like a credential",
                        n.contains("credential") || n.contains("secret") || n.contains("token")
                                || n.contains("password") || n.contains("authorization") || n.contains("apikey")
                                || n.contains("bearer"));
            }
        }
        String json = sample().toJson().toLowerCase(Locale.ROOT);
        assertFalse(json.contains("credential"));
        assertFalse(json.contains("bearer"));
    }

    @Test
    public void readingAMissingFileGivesNull() throws Exception {
        assertEquals(null, BuildRecord.read(TestSupport.tempDir("br").resolve("none.json")));
        assertNotNull(BuildRecord.update(TestSupport.tempDir("br").resolve("new.json"), r -> {
        }));
    }
}
