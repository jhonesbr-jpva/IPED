package iped.engine.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.MessageDigestSpi;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import iped.data.IItem;
import iped.engine.config.ConfigurationManager;
import iped.engine.config.HashTaskConfig;
import iped.engine.core.Statistics;
import iped.parsers.whatsapp.WhatsAppParser;

/**
 * Tests of {@link HashTask}. A worker reuses the same task instance for all its items, so besides the hash values
 * these tests check that an item whose hashing failed or was interrupted leaves no partial digest behind for the next
 * item hashed by the same instance.
 */
public class HashTaskTest {

    private static final String[] ALGORITHMS = { "md5", "sha-1", "sha-256", "edonkey" };

    /** Algorithm provided by {@link FaultyDigestProvider}; no letter i, so it upper-cases the same in any locale. */
    private static final String FAULTY_ALGORITHM = "faulty-test-hash";

    /** Data on which {@link FaultyDigestSpi} throws an exception. */
    private static final byte[] FAULT_TRIGGER = "FAULT".getBytes(StandardCharsets.US_ASCII);

    /** Data on which {@link FaultyDigestSpi} throws an error. */
    private static final byte[] ERROR_TRIGGER = "ERROR".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] PARTIAL = "first chunk of an item that is not read to the end"
            .getBytes(StandardCharsets.US_ASCII);

    private static final byte[] FOX = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.US_ASCII);

    private static boolean bouncyCastleAdded;

    private String savedSha256Property;
    private String savedHashTaskProperty;
    private Statistics stats;

    @BeforeClass
    public static void addProviders() {
        // MD4, used by eDonkey, comes from Bouncy Castle, which Manager registers in production; decided on every
        // class run, so a repeated run never removes a provider that someone else registered in between
        bouncyCastleAdded = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null;
        if (bouncyCastleAdded) {
            Security.addProvider(new BouncyCastleProvider());
        }
        Security.addProvider(new FaultyDigestProvider());
    }

    @AfterClass
    public static void removeProviders() {
        Security.removeProvider(FaultyDigestProvider.NAME);
        if (bouncyCastleAdded) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        }
    }

    @Before
    public void setUp() {
        // HashTask.init() sets these properties
        savedSha256Property = System.getProperty(WhatsAppParser.SHA256_ENABLED_SYSPROP);
        savedHashTaskProperty = System.getProperty(WhatsAppParser.HASH_TASK_ENABLED_SYSPROP);
        stats = mock(Statistics.class);
    }

    @After
    public void tearDown() {
        restoreProperty(WhatsAppParser.SHA256_ENABLED_SYSPROP, savedSha256Property);
        restoreProperty(WhatsAppParser.HASH_TASK_ENABLED_SYSPROP, savedHashTaskProperty);
        // never leak an interrupt status to the next test, even after a failed assertion
        Thread.interrupted();
    }

    @Test
    public void ioErrorAfterFirstChunkDoesNotChangeHashesOfNextItem() throws Exception {
        HashTask task = newTask(ALGORITHMS);

        TestItem broken = new TestItem(2 * PARTIAL.length, chunk(PARTIAL), (b, off, len) -> {
            throw new IOException("simulated read error");
        });
        task.process(broken.item);

        assertTrue("no hash expected", broken.hashes.isEmpty());
        assertEquals(Collections.singletonMap("ioError", "true"), broken.attributes);
        verify(stats).incIoErrors();

        assertReferenceHashes(task, FOX);
    }

    @Test
    public void ioErrorAfterFirstEdonkeyChunkDoesNotChangeHashesOfNextItem() throws Exception {
        HashTask task = newTask(ALGORITHMS);

        // 10 reads of 1 MiB complete the first eDonkey chunk (9500 KiB), then the 11th read fails
        byte[] mebibyte = new byte[1024 * 1024];
        new Random(2).nextBytes(mebibyte);
        ReadStep[] steps = new ReadStep[11];
        Arrays.fill(steps, 0, 10, chunk(mebibyte));
        steps[10] = (b, off, len) -> {
            throw new IOException("simulated read error");
        };
        TestItem broken = new TestItem(11L * mebibyte.length, steps);
        task.process(broken.item);

        assertTrue("no hash expected", broken.hashes.isEmpty());
        assertEquals(Collections.singletonMap("ioError", "true"), broken.attributes);

        assertReferenceHashes(task, FOX);
    }

    @Test
    public void runtimeExceptionAfterFirstChunkDoesNotChangeHashesOfNextItem() throws Exception {
        HashTask task = newTask(ALGORITHMS);

        TestItem broken = new TestItem(2 * PARTIAL.length, chunk(PARTIAL), (b, off, len) -> {
            throw new IllegalStateException("simulated failure");
        });
        task.process(broken.item);

        assertTrue("no hash expected", broken.hashes.isEmpty());
        assertTrue("no attribute expected", broken.attributes.isEmpty());
        verify(stats, never()).incIoErrors();

        assertReferenceHashes(task, FOX);
    }

    @Test
    public void errorAfterFirstChunkIsPropagatedWithoutChangingHashesOfNextItem() throws Exception {
        HashTask task = newTask(ALGORITHMS);

        SimulatedError error = new SimulatedError();
        TestItem broken = new TestItem(2 * PARTIAL.length, chunk(PARTIAL), (b, off, len) -> {
            throw error;
        });
        try {
            task.process(broken.item);
            fail("the error must be propagated");
        } catch (SimulatedError e) {
            assertSame(error, e);
        }

        assertTrue("no hash expected", broken.hashes.isEmpty());
        assertTrue("no attribute expected", broken.attributes.isEmpty());

        assertReferenceHashes(task, FOX);
    }

    @Test
    public void interruptionAfterFirstChunkLeavesNoHashAndKeepsInterruptStatus() throws Exception {
        HashTask task = newTask(ALGORITHMS);

        TestItem interrupted = new TestItem(2 * PARTIAL.length, chunk(PARTIAL), (b, off, len) -> {
            Thread.currentThread().interrupt();
            return chunk(PARTIAL).read(b, off, len);
        });
        task.process(interrupted.item);

        boolean interruptStatus = Thread.currentThread().isInterrupted();
        Thread.interrupted();
        assertTrue("the interrupt status must be preserved", interruptStatus);
        assertTrue("no hash expected", interrupted.hashes.isEmpty());
        assertTrue("no attribute expected", interrupted.attributes.isEmpty());

        assertReferenceHashes(task, FOX);
    }

    @Test
    public void interruptionWhileReadingFirstChunkLeavesNoHashAndKeepsInterruptStatus() throws Exception {
        HashTask task = newTask(ALGORITHMS);

        TestItem interrupted = new TestItem(PARTIAL.length, (b, off, len) -> {
            Thread.currentThread().interrupt();
            return chunk(PARTIAL).read(b, off, len);
        });
        task.process(interrupted.item);

        boolean interruptStatus = Thread.currentThread().isInterrupted();
        Thread.interrupted();
        assertTrue("the interrupt status must be preserved", interruptStatus);
        assertTrue("no hash expected", interrupted.hashes.isEmpty());
        assertTrue("no attribute expected", interrupted.attributes.isEmpty());

        assertReferenceHashes(task, FOX);
    }

    @Test
    public void exceptionInHashingJobOfLastChunkLeavesNoHash() throws Exception {
        assertFailedHashingJobLeavesNoHash(FAULT_TRIGGER);
    }

    @Test
    public void errorInHashingJobOfLastChunkLeavesNoHash() throws Exception {
        assertFailedHashingJobLeavesNoHash(ERROR_TRIGGER);
    }

    @Test
    public void emptyAndKnownContentsGetReferenceHashes() throws Exception {
        HashTask task = newTask(ALGORITHMS);

        TestItem empty = new TestItem(new byte[0]);
        task.process(empty.item);
        assertEquals(hashes("D41D8CD98F00B204E9800998ECF8427E", "DA39A3EE5E6B4B0D3255BFEF95601890AFD80709",
                "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855",
                "31D6CFE0D16AE931B73C59D7E0C089C0"), empty.attributes);
        assertEquals(Collections.singletonList("D41D8CD98F00B204E9800998ECF8427E"), empty.hashes);

        TestItem fox = new TestItem(FOX);
        task.process(fox.item);
        assertEquals(hashes("9E107D9D372BB6826BD81D3542A419D6", "2FD4E1C67A2D28FCED849EE1BB76E7391B93EB12",
                "D7A8FBB307D7809469CA9ABCB0082E4F8D5651E46D3CDB762D02D0BF37C9E592",
                "1BEE69A46BA811185C194762ABAEAE90"), fox.attributes);
        assertEquals(Collections.singletonList("9E107D9D372BB6826BD81D3542A419D6"), fox.hashes);
    }

    @Test
    public void contentSpanningManyReadsAndTwoEdonkeyChunksGetsReferenceHashes() throws Exception {
        HashTask task = newTask(ALGORITHMS);

        // 10 reads with the 1 MiB buffer of HashTask, 2 eDonkey chunks of 9500 KiB
        byte[] content = new byte[9500 * 1024 + 12345];
        new Random(93).nextBytes(content);
        assertReferenceHashes(task, content);
        assertReferenceHashes(task, FOX);
    }

    /**
     * Creates a task initialized like in production, through {@link HashTask#init(ConfigurationManager)}.
     */
    private HashTask newTask(String... algorithms) throws Exception {
        HashTaskConfig config = mock(HashTaskConfig.class);
        when(config.getAlgorithms()).thenReturn(new ArrayList<>(Arrays.asList(algorithms)));
        when(config.isEnabled()).thenReturn(true);
        ConfigurationManager configurationManager = mock(ConfigurationManager.class);
        when(configurationManager.findObject(HashTaskConfig.class)).thenReturn(config);

        HashTask task = new HashTask();
        task.init(configurationManager);
        task.stats = stats;
        return task;
    }

    /**
     * Hashes an item whose only chunk, so the last one, makes the job of {@link #FAULTY_ALGORITHM} fail.
     */
    private void assertFailedHashingJobLeavesNoHash(byte[] content) throws Exception {
        HashTask task = newTask("md5", "sha-1", "sha-256", "edonkey", FAULTY_ALGORITHM);

        TestItem failed = new TestItem(content);
        task.process(failed.item);

        assertTrue("no hash expected", failed.hashes.isEmpty());
        assertTrue("no attribute expected", failed.attributes.isEmpty());
        verify(stats, never()).incIoErrors();

        Map<String, String> expected = referenceHashes(FOX);
        expected.put(FAULTY_ALGORITHM, expected.get("md5"));
        assertHashes(task, FOX, expected);
    }

    /**
     * Hashes a readable item with the given content and checks that it gets the reference hashes.
     */
    private static void assertReferenceHashes(HashTask task, byte[] content) throws Exception {
        assertHashes(task, content, referenceHashes(content));
    }

    /**
     * Hashes a readable item with the given content and checks its hash attributes, and its md5 as item hash.
     */
    private static void assertHashes(HashTask task, byte[] content, Map<String, String> expected) throws Exception {
        TestItem item = new TestItem(content);
        task.process(item.item);

        assertEquals(expected, item.attributes);
        assertEquals(Collections.singletonList(expected.get("md5")), item.hashes);
    }

    /**
     * Reference values of {@link #ALGORITHMS}, computed directly with MessageDigest.
     */
    private static Map<String, String> referenceHashes(byte[] content) throws NoSuchAlgorithmException {
        return hashes(hex(MessageDigest.getInstance("MD5").digest(content)),
                hex(MessageDigest.getInstance("SHA-1").digest(content)),
                hex(MessageDigest.getInstance("SHA-256").digest(content)), hex(edonkey(content)));
    }

    private static Map<String, String> hashes(String md5, String sha1, String sha256, String edonkey) {
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("md5", md5);
        hashes.put("sha-1", sha1);
        hashes.put("sha-256", sha256);
        hashes.put("edonkey", edonkey);
        return hashes;
    }

    /**
     * eDonkey hash: MD4 of the content when it fits in one chunk of 9500 KiB, otherwise MD4 of the MD4s of its
     * chunks (no hash of an empty last chunk when the length is a multiple of the chunk size).
     */
    private static byte[] edonkey(byte[] content) throws NoSuchAlgorithmException {
        final int chunkSize = 9500 * 1024;
        MessageDigest md4 = MessageDigest.getInstance("MD4");
        if (content.length <= chunkSize) {
            return md4.digest(content);
        }
        for (int offset = 0; offset < content.length; offset += chunkSize) {
            MessageDigest chunkMd4 = MessageDigest.getInstance("MD4");
            chunkMd4.update(content, offset, Math.min(chunkSize, content.length - offset));
            md4.update(chunkMd4.digest());
        }
        return md4.digest();
    }

    /** Uppercase hex, like {@link HashTask#getHashString(byte[])}. */
    private static String hex(byte[] bytes) {
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    /** Read step returning the whole chunk (HashTask reads with a 1 MiB buffer, no chunk used here is larger). */
    private static ReadStep chunk(byte[] data) {
        return (b, off, len) -> {
            System.arraycopy(data, 0, b, off, data.length);
            return data.length;
        };
    }

    /**
     * Mocked item, recording the hash and the attributes set by HashTask.
     */
    private static class TestItem {

        final IItem item = mock(IItem.class);
        final List<String> hashes = new ArrayList<>();
        final Map<String, Object> attributes = new LinkedHashMap<>();

        /** Readable item with the given content. */
        TestItem(byte[] content) throws IOException {
            this(content.length, new ByteArrayInputStream(content));
        }

        /** Item whose read calls follow the given steps. */
        TestItem(long length, ReadStep... steps) throws IOException {
            this(length, new ScriptedInputStream(steps));
        }

        private TestItem(long length, InputStream content) throws IOException {
            when(item.isQueueEnd()).thenReturn(false);
            when(item.getHash()).thenReturn(null);
            when(item.getExtraAttribute(IgnoreHardLinkTask.IGNORE_HARDLINK_ATTR)).thenReturn(null);
            when(item.getPath()).thenReturn("test-item");
            when(item.getLength()).thenReturn(length);
            when(item.getBufferedInputStream()).thenReturn(new BufferedInputStream(content));
            doAnswer(invocation -> {
                hashes.add(invocation.getArgument(0));
                return null;
            }).when(item).setHash(anyString());
            doAnswer(invocation -> {
                attributes.put(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(item).setExtraAttribute(anyString(), any());
        }
    }

    /** One read call of a {@link ScriptedInputStream}. */
    @FunctionalInterface
    private interface ReadStep {
        int read(byte[] b, int off, int len) throws IOException;
    }

    /**
     * Stream running one step per read call, then reporting the end of the stream. As it reports no available
     * bytes, the BufferedInputStream wrapping it returns the result of each step in a separate read call.
     */
    private static class ScriptedInputStream extends InputStream {

        private final Deque<ReadStep> steps;

        ScriptedInputStream(ReadStep... steps) {
            this.steps = new ArrayDeque<>(Arrays.asList(steps));
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            ReadStep step = steps.poll();
            return step != null ? step.read(b, off, len) : -1;
        }

        @Override
        public int read() throws IOException {
            throw new UnsupportedOperationException("HashTask reads in chunks");
        }
    }

    private static class SimulatedError extends Error {

        private static final long serialVersionUID = 1L;

        SimulatedError() {
            super("simulated error");
        }
    }

    /**
     * Provider of {@link #FAULTY_ALGORITHM}, so that a hashing job can fail with a task initialized as in production.
     */
    private static class FaultyDigestProvider extends Provider {

        private static final long serialVersionUID = 1L;

        static final String NAME = "HashTaskTestProvider";

        FaultyDigestProvider() {
            super(NAME, "1.0", "Digest failing on demand, for HashTaskTest");
            put("MessageDigest." + FAULTY_ALGORITHM.toUpperCase(Locale.ROOT), FaultyDigestSpi.class.getName());
        }
    }

    /**
     * MD5 whose update fails on data starting with {@link #FAULT_TRIGGER} or {@link #ERROR_TRIGGER}. Public, as the
     * JCA instantiates it.
     */
    public static class FaultyDigestSpi extends MessageDigestSpi {

        private final MessageDigest md5;

        public FaultyDigestSpi() throws NoSuchAlgorithmException {
            md5 = MessageDigest.getInstance("MD5");
        }

        @Override
        protected void engineUpdate(byte input) {
            engineUpdate(new byte[] { input }, 0, 1);
        }

        @Override
        protected void engineUpdate(byte[] input, int offset, int len) {
            if (startsWith(input, offset, len, FAULT_TRIGGER)) {
                throw new IllegalStateException("simulated hashing failure");
            }
            if (startsWith(input, offset, len, ERROR_TRIGGER)) {
                throw new SimulatedError();
            }
            md5.update(input, offset, len);
        }

        private static boolean startsWith(byte[] input, int offset, int len, byte[] prefix) {
            return len >= prefix.length
                    && Arrays.equals(input, offset, offset + prefix.length, prefix, 0, prefix.length);
        }

        @Override
        protected byte[] engineDigest() {
            return md5.digest();
        }

        @Override
        protected void engineReset() {
            md5.reset();
        }
    }
}
