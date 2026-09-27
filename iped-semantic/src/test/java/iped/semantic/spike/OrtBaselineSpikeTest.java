package iped.semantic.spike;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketPermission;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.extensions.OrtxPackage;
import iped.semantic.model.OrtNatives;

/**
 * Dependency gate D2 (research.md). Verifies, on the exact jars the build resolves:
 * <ol type="a">
 * <li>no class above class file 55 (Java 11) in either jar;</li>
 * <li>Windows x64 and Linux x64 natives present in both jars;</li>
 * <li>a session is created and the extensions library registered with the network denied;</li>
 * <li>{@code run()} is stable when one session is shared by four threads.</li>
 * </ol>
 * Network denial is enforced at the Java level by a security manager that refuses every socket
 * permission while the sessions load; native code cannot be observed from here, so the quickstart
 * (Scenario 0) repeats the check with the machine offline.
 */
public class OrtBaselineSpikeTest {

    private static final int JAVA_11_CLASS_FILE = 55;
    private static final String[] REQUIRED_PLATFORMS = { "win-x64", "linux-x64" };

    private static final AtomicInteger socketAttempts = new AtomicInteger();
    private static volatile Throwable firstAttempt;
    private static SecurityManager previous;

    private static Path extensionsLibrary;

    @BeforeClass
    public static void denyNetworkAndPrepareNatives() throws IOException {
        // Log4j resolves the local host name when its context starts. That is the logging stack IPED
        // already ships, not the runtime under test, so it is initialized before the guard goes up.
        LoggerFactory.getLogger(OrtBaselineSpikeTest.class).info("D2 gate: denying network from here on");
        previous = System.getSecurityManager();
        System.setSecurityManager(new SecurityManager() {
            @Override
            public void checkPermission(Permission perm) {
                if (perm instanceof SocketPermission) {
                    if (socketAttempts.getAndIncrement() == 0) {
                        firstAttempt = new Throwable(perm.toString());
                    }
                    throw new SecurityException("network denied by the D2 gate: " + perm);
                }
            }

            @Override
            public void checkPermission(Permission perm, Object context) {
                checkPermission(perm);
            }
        });
        String dir = System.getProperty("iped.semantic.test.nativeDir", "target/onnxruntime");
        extensionsLibrary = OrtNatives.prepare(Paths.get(dir));
    }

    @AfterClass
    public static void restore() {
        System.setSecurityManager(previous);
        if (firstAttempt != null) {
            StringWriter sw = new StringWriter();
            firstAttempt.printStackTrace(new PrintWriter(sw));
            throw new AssertionError(
                    "no socket permission may be requested while loading the runtime; first request:\n" + sw);
        }
    }

    @Test
    public void a_everyClassRunsOnJava11() throws Exception {
        for (File jar : jars()) {
            Map<Integer, Integer> histogram = classFileHistogram(jar);
            assertTrue("no classes found in " + jar, !histogram.isEmpty());
            int max = ((TreeMap<Integer, Integer>) histogram).lastKey();
            assertTrue(jar.getName() + " has class files " + histogram + ", above Java 11 (55)",
                    max <= JAVA_11_CLASS_FILE);
        }
    }

    @Test
    public void b_nativesForWindowsAndLinuxAreInsideTheJars() throws Exception {
        for (String platform : REQUIRED_PLATFORMS) {
            String ext = platform.startsWith("win") ? ".dll" : ".so";
            String prefix = platform.startsWith("win") ? "" : "lib";
            assertEntry(jarOf(OrtEnvironment.class), "ai/onnxruntime/native/" + platform + "/" + prefix + "onnxruntime" + ext);
            assertEntry(jarOf(OrtEnvironment.class),
                    "ai/onnxruntime/native/" + platform + "/" + prefix + "onnxruntime4j_jni" + ext);
            assertEntry(jarOf(OrtxPackage.class),
                    "ai/onnxruntime/extensions/native/" + platform + "/" + prefix + "onnxruntime_extensions4j_jni" + ext);
        }
    }

    @Test
    public void c_sessionsLoadWithoutNetwork() throws Exception {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession session = env.createSession(TinyOnnx.relu(), new OrtSession.SessionOptions());
                OnnxTensor x = OnnxTensor.createTensor(env, new float[] { -1f, 2f, -3f, 4f });
                OrtSession.Result r = session.run(Map.of("x", x))) {
            assertArrayEquals(new float[] { 0f, 2f, 0f, 4f }, (float[]) r.get(0).getValue(), 0f);
        }
        try (OrtSession session = contribSession(env);
                OnnxTensor x = OnnxTensor.createTensor(env, new String[] { "quanto?", "fechado" });
                OrtSession.Result r = session.run(Map.of("x", x))) {
            assertArrayEquals(new String[] { "QUANTO?", "FECHADO" }, (String[]) r.get(0).getValue());
        }
        assertTrue("extensions library must live outside the system temp folder: " + extensionsLibrary,
                !extensionsLibrary.startsWith(Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath()));
    }

    @Test
    public void d_runIsStableFromFourThreads() throws Exception {
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession session = contribSession(env)) {
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<Integer>> futures = new ArrayList<>();
                for (int t = 0; t < 4; t++) {
                    final int thread = t;
                    futures.add(pool.submit(() -> {
                        int ok = 0;
                        for (int i = 0; i < 500; i++) {
                            String in = "thread" + thread + "-mensagem-" + i;
                            try (OnnxTensor x = OnnxTensor.createTensor(env, new String[] { in });
                                    OrtSession.Result r = session.run(Map.of("x", x))) {
                                String out = ((String[]) r.get(0).getValue())[0];
                                if (out.equals(in.toUpperCase(Locale.ROOT))) {
                                    ok++;
                                }
                            }
                        }
                        return ok;
                    }));
                }
                for (Future<Integer> f : futures) {
                    assertEquals(500, (int) f.get());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static OrtSession contribSession(OrtEnvironment env) throws Exception {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.registerCustomOpLibrary(extensionsLibrary.toString());
        return env.createSession(TinyOnnx.contribStringOp("StringUpper"), opts);
    }

    private static List<File> jars() throws Exception {
        List<File> jars = new ArrayList<>();
        jars.add(jarOf(OrtEnvironment.class));
        jars.add(jarOf(OrtxPackage.class));
        return jars;
    }

    private static File jarOf(Class<?> c) throws Exception {
        File f = new File(c.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(f + " is not a jar", f.isFile());
        return f;
    }

    private static void assertEntry(File jar, String entry) throws IOException {
        try (ZipFile z = new ZipFile(jar)) {
            assertNotNull(entry + " missing from " + jar.getName(), z.getEntry(entry));
        }
    }

    private static Map<Integer, Integer> classFileHistogram(File jar) throws IOException {
        TreeMap<Integer, Integer> hist = new TreeMap<>();
        try (ZipFile z = new ZipFile(jar)) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                ZipEntry en = e.nextElement();
                if (!en.getName().endsWith(".class")) {
                    continue;
                }
                try (DataInputStream in = new DataInputStream(z.getInputStream(en))) {
                    in.readInt(); // magic
                    in.readUnsignedShort(); // minor
                    hist.merge(in.readUnsignedShort(), 1, Integer::sum);
                }
            }
        }
        return hist;
    }
}
