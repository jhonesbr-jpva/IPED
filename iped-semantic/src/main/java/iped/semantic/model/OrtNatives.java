package iped.semantic.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unpacks the ONNX Runtime and ONNX Runtime Extensions native libraries into a directory the
 * installation owns ({@code <install>/tools/onnxruntime/<os-arch>/}), instead of the system temp
 * folder both libraries would otherwise use (research D17).
 *
 * <p>
 * ONNX Runtime honours the {@code onnxruntime.native.path} system property, which this class sets
 * before the runtime is first touched. The extensions library is never loaded through its own
 * {@code OrtxLibrary} (which always extracts to {@code java.io.tmpdir}); its path is handed to
 * {@code SessionOptions.registerCustomOpLibrary} instead.
 * </p>
 *
 * <p>
 * Extraction is idempotent: a file already present with the same SHA-256 as the jar resource is
 * left alone, which matters on Windows where a library loaded by another IPED process (the UI and
 * the processing JVM at the same time) cannot be overwritten.
 * </p>
 */
public final class OrtNatives {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrtNatives.class);

    /** System property ONNX Runtime reads to load its libraries from a directory. */
    public static final String ORT_NATIVE_PATH_PROPERTY = "onnxruntime.native.path";

    private static final String ORT_RESOURCE_DIR = "/ai/onnxruntime/native/";
    private static final String ORTX_RESOURCE_DIR = "/ai/onnxruntime/extensions/native/";

    private static final String[] ORT_LIBRARIES = { "onnxruntime", "onnxruntime4j_jni" };
    /** Present only in some builds (Windows since 1.2x); extracted when the jar carries it. */
    private static final String[] ORT_OPTIONAL_LIBRARIES = { "onnxruntime_providers_shared" };
    private static final String ORTX_LIBRARY = "onnxruntime_extensions4j_jni";

    private static volatile Path extensionsLibrary;

    private OrtNatives() {
    }

    /**
     * Unpacks the native libraries under {@code baseDir/<os-arch>/}, points ONNX Runtime at them
     * and returns the path of the extensions library, to be registered as a custom op library.
     * Safe to call more than once; later calls return the first result.
     *
     * @param baseDir
     *            usually {@code <install>/tools/onnxruntime}
     * @return absolute path of the ONNX Runtime Extensions native library
     * @throws IOException
     *             if a library is missing from the jars or cannot be written
     */
    public static synchronized Path prepare(Path baseDir) throws IOException {
        if (extensionsLibrary != null) {
            return extensionsLibrary;
        }
        String osArch = osArch();
        Path dir = baseDir.resolve(osArch).toAbsolutePath();
        Files.createDirectories(dir);

        String configured = System.getProperty(ORT_NATIVE_PATH_PROPERTY);
        if (configured == null || configured.isBlank()) {
            for (String lib : ORT_LIBRARIES) {
                extract(ORT_RESOURCE_DIR + osArch + "/", System.mapLibraryName(lib), dir, true);
            }
            for (String lib : ORT_OPTIONAL_LIBRARIES) {
                extract(ORT_RESOURCE_DIR + osArch + "/", System.mapLibraryName(lib), dir, false);
            }
            System.setProperty(ORT_NATIVE_PATH_PROPERTY, dir.toString());
        } else {
            LOGGER.info("ONNX Runtime native path already set to {}; not unpacking the runtime", configured);
        }
        extensionsLibrary = extract(ORTX_RESOURCE_DIR + osArch + "/", System.mapLibraryName(ORTX_LIBRARY), dir, true);
        LOGGER.info("ONNX Runtime native libraries ready in {}", dir);
        return extensionsLibrary;
    }

    /** The platform directory name both jars use, such as {@code win-x64} or {@code linux-x64}. */
    public static String osArch() {
        String os = System.getProperty("os.name", "generic").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "generic").toLowerCase(Locale.ROOT);
        String detectedOs;
        if (os.contains("win")) {
            detectedOs = "win";
        } else if (os.contains("mac") || os.contains("darwin")) {
            detectedOs = "osx";
        } else if (os.contains("nux")) {
            detectedOs = "linux";
        } else {
            throw new IllegalStateException("Unsupported operating system for ONNX Runtime: " + os);
        }
        String detectedArch;
        if (arch.startsWith("amd64") || arch.startsWith("x86_64")) {
            detectedArch = "x64";
        } else if (arch.startsWith("aarch64")) {
            detectedArch = "aarch64";
        } else {
            throw new IllegalStateException("Unsupported architecture for ONNX Runtime: " + arch);
        }
        return detectedOs + '-' + detectedArch;
    }

    private static Path extract(String resourceDir, String fileName, Path dir, boolean required) throws IOException {
        String resource = resourceDir + fileName;
        byte[] expected = resourceDigest(resource);
        if (expected == null) {
            if (required) {
                throw new IOException("Native library " + resource + " is not inside the ONNX Runtime jars");
            }
            return null;
        }
        Path target = dir.resolve(fileName);
        if (Files.isRegularFile(target) && Arrays.equals(expected, fileDigest(target))) {
            return target;
        }
        Path tmp = Files.createTempFile(dir, fileName, ".part");
        try (InputStream in = OrtNatives.class.getResourceAsStream(resource)) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    private static byte[] resourceDigest(String resource) throws IOException {
        try (InputStream in = OrtNatives.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return digest(in);
        }
    }

    private static byte[] fileDigest(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return digest(in);
        }
    }

    private static byte[] digest(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream din = new DigestInputStream(in, md)) {
                byte[] buf = new byte[1 << 16];
                while (din.read(buf) != -1) {
                    // digest only
                }
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
