package iped.rcp.progress;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.config.Configuration;

/**
 * Resolution and launch of the RCP analysis product from the processing JVM
 * (task T040, contract case-launcher-packaging): the "open analysis" button
 * starts the e4 product as a SEPARATE process over the case folder — during
 * the processing this is the near-live mode (FR-029, the product detects the
 * in-progress case on disk and enables the commit monitor), and after the end
 * it is the regular case opening (FR-026).
 *
 * <p>
 * Launcher resolution order:
 * <ol>
 * <li>{@code -Diped.rcp.ui.home} / {@code IPED_RCP_UI_HOME} env var — dev
 * runs against the materialized product
 * ({@code iped-rcp/products/.../target/products/...}) until the packaging
 * tasks T052/T054 land;</li>
 * <li>{@code <case>/iped/ui} — self-contained case (T053);</li>
 * <li>{@code <install root>/ui} — the release the processing is running
 * from (T052).</li>
 * </ol>
 */
public class AnalysisUiLauncher {

    /** System property pointing at the product folder (dev override). */
    public static final String UI_HOME_PROP = "iped.rcp.ui.home";

    /** Environment variable alternative to {@link #UI_HOME_PROP}. */
    public static final String UI_HOME_ENV = "IPED_RCP_UI_HOME";

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalysisUiLauncher.class);

    private static final String LAUNCHER_NAME = System.getProperty("os.name").toLowerCase().contains("windows")
            ? "iped-ui.exe"
            : "iped-ui";

    private AnalysisUiLauncher() {
    }

    /**
     * @return the product launcher executable for this case, or {@code null}
     *         when no product installation could be found
     */
    public static File resolveLauncher(File caseRoot) {
        List<File> candidates = new ArrayList<>();
        String override = System.getProperty(UI_HOME_PROP, System.getenv(UI_HOME_ENV));
        if (override != null && !override.isBlank()) {
            candidates.add(new File(override));
        }
        if (caseRoot != null) {
            candidates.add(new File(caseRoot, "iped" + File.separator + "ui"));
        }
        String appRoot = Configuration.getInstance().appRoot;
        if (appRoot != null) {
            candidates.add(new File(appRoot, "ui"));
        }
        for (File dir : candidates) {
            File launcher = new File(dir, LAUNCHER_NAME);
            if (launcher.isFile()) {
                return launcher;
            }
        }
        LOGGER.warn("Analysis UI product not found; tried: {}", candidates);
        return null;
    }

    /** Starts the product detached, with the case folder as the argument. */
    public static Process launch(File launcher, File caseRoot) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(launcher.getAbsolutePath(), caseRoot.getAbsolutePath());
        pb.directory(launcher.getParentFile());
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }
}
