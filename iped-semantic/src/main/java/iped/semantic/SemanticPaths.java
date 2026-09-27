package iped.semantic;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import iped.configuration.IConfigurationDirectory;

/**
 * Where this module finds installation files: models and the unpacked ONNX Runtime natives. All
 * paths resolve against the IPED installation root, which the engine publishes as the
 * {@link IConfigurationDirectory#IPED_APP_ROOT} system property.
 */
public final class SemanticPaths {

    /** Overrides the native library folder (tests use {@code target/onnxruntime}). */
    public static final String NATIVE_DIR_PROPERTY = "iped.semantic.nativeDir";

    private SemanticPaths() {
    }

    /** The installation root, or the working directory when none was published. */
    public static Path appRoot() {
        String root = System.getProperty(IConfigurationDirectory.IPED_APP_ROOT);
        return root != null ? Paths.get(root) : Paths.get(System.getProperty("user.dir"));
    }

    /** Resolves a configured path: absolute as given, relative against the installation root. */
    public static Path resolve(String configured) {
        File f = new File(configured);
        return f.isAbsolute() ? f.toPath() : appRoot().resolve(configured);
    }

    /** {@code <install>/tools/onnxruntime}, where the natives are unpacked (research D17). */
    public static Path nativeDir() {
        String override = System.getProperty(NATIVE_DIR_PROPERTY);
        if (override == null) {
            override = System.getProperty("iped.semantic.test.nativeDir");
        }
        return override != null ? Paths.get(override) : appRoot().resolve("tools").resolve("onnxruntime");
    }
}
