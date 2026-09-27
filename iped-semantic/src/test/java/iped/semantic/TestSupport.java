package iped.semantic;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Assume;

import iped.engine.config.ConfigurationDirectory;
import iped.engine.config.ConfigurationManager;
import iped.engine.config.IndexTaskConfig;
import iped.semantic.config.SemanticSearchConfig;

/**
 * Test helpers: the repository root, the distributed configuration, and the parameterised suites'
 * skip rules. A parameterised suite that cannot run is skipped through {@link Assume}, never passed
 * silently, so the Surefire summary counts it as skipped.
 */
public final class TestSupport {

    public static final String TEXT_MODEL_PROPERTY = "iped.semantic.test.textModel";
    public static final String VISUAL_MODEL_PROPERTY = "iped.semantic.test.visualModel";
    public static final String REFERENCE_CASE_PROPERTY = "iped.semantic.test.referenceCase";
    public static final String EVAL_CASE_PROPERTY = "iped.semantic.test.evalCase";

    private TestSupport() {
    }

    /** The repository root: the first ancestor of the working directory holding iped-app/resources. */
    public static Path repoRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("iped-app/resources/config"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("repository root not found from " + System.getProperty("user.dir"));
        }
        return p;
    }

    public static Path distributedConfig() {
        return repoRoot().resolve("iped-app/resources/config");
    }

    /**
     * Initializes the engine's ConfigurationManager from the distributed configuration, once per
     * JVM, with the configurables this module's code reads ({@code AppAnalyzer} needs
     * {@link IndexTaskConfig}).
     */
    public static synchronized void ensureConfiguration() {
        if (ConfigurationManager.get() != null) {
            return;
        }
        Path cfg = distributedConfig();
        ConfigurationDirectory dir = new ConfigurationDirectory(cfg.resolve("LocalConfig.txt"));
        dir.addPath(cfg.resolve("IPEDConfig.txt"));
        dir.addPath(cfg.resolve("LocalConfig.txt"));
        dir.addPath(cfg.resolve("conf"));
        ConfigurationManager cm = ConfigurationManager.createInstance(dir);
        cm.addObject(new IndexTaskConfig());
        cm.addObject(new SemanticSearchConfig());
        try {
            cm.loadConfigs();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The exported text model folder, or skips the test when not given. */
    public static Path requireTextModel() {
        String dir = System.getProperty(TEXT_MODEL_PROPERTY);
        Assume.assumeTrue("text model not given; pass -D" + TEXT_MODEL_PROPERTY + "=<model folder>", dir != null);
        Path p = Paths.get(dir);
        Assume.assumeTrue("no model.json in " + p, Files.isRegularFile(p.resolve("model.json")));
        return p;
    }

    /** The exported visual model folder, or skips the test when not given. */
    public static Path requireVisualModel() {
        String dir = System.getProperty(VISUAL_MODEL_PROPERTY);
        Assume.assumeTrue("visual model not given; pass -D" + VISUAL_MODEL_PROPERTY + "=<model folder>", dir != null);
        Path p = Paths.get(dir);
        Assume.assumeTrue("no model.json in " + p, Files.isRegularFile(p.resolve("model.json")));
        return p;
    }

    public static File requireCase(String property) {
        String dir = System.getProperty(property);
        Assume.assumeTrue("case not given; pass -D" + property + "=<case folder>", dir != null);
        File f = new File(dir);
        Assume.assumeTrue(f + " is not a case folder", new File(f, "iped").isDirectory());
        return f;
    }

    public static Path tempDir(String prefix) {
        try {
            return Files.createTempDirectory(Paths.get(System.getProperty("user.dir"), "target"), prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
