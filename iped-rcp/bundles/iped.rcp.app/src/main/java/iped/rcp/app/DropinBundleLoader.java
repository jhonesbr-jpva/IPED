package iped.rcp.app;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.service.datalocation.Location;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boot-time loader of drop-in third-party extensions (task T050, US6, FR-022).
 * Installs and starts every {@code .jar} found in the {@code plugins-ext/}
 * directory of the installation (and of a self-contained {@code <case>/iped/ui/})
 * so a bundle contributing a {@code fragment.e4xmi} appears in the workbench
 * without a fork (ui-extension-api contract, SC-007).
 *
 * <p>
 * Failure tolerance is the contract (US6 scenario 2): a malformed or
 * incompatible jar is logged and skipped — the product always boots, and
 * removing a previously installed jar never breaks the next launch. There is
 * no p2 / auto-update (out of scope by Clarifications): this is a plain
 * install-and-start at boot.
 *
 * <p>
 * Timing: invoked from {@link LifeCycle#postContextCreate} BEFORE the e4
 * application model is loaded, so the extension registry already carries the
 * drop-in's {@code org.eclipse.e4.workbench.model} fragment contribution when
 * the {@code ModelAssembler} processes fragments (same window the workspace
 * resolver uses, T043).
 */
public final class DropinBundleLoader {

    /** System property overriding the drop-in directory (used by tests). */
    public static final String DROPIN_DIR_PROP = "iped.rcp.dropins.dir";

    /** Drop-in directory name under the installation root (the {@code ui/} folder). */
    public static final String DROPIN_DIR_NAME = "plugins-ext";

    private static final Logger LOGGER = LoggerFactory.getLogger(DropinBundleLoader.class);

    private DropinBundleLoader() {
    }

    /**
     * Installs and starts every {@code .jar} in the resolved drop-in directory.
     * Each jar is handled independently; a failure on one never stops the
     * others nor the boot.
     *
     * @param context the bundle context used to install the drop-ins
     * @return the number of bundles that were successfully made available
     *         (newly installed and started, or already present)
     */
    public static int loadDropins(BundleContext context) {
        if (context == null) {
            return 0;
        }
        Path dir = resolveDropinDir();
        if (dir == null || !Files.isDirectory(dir)) {
            LOGGER.debug("No drop-in directory to scan ({})", dir);
            return 0;
        }
        List<Path> jars = listJars(dir);
        if (jars.isEmpty()) {
            LOGGER.debug("Drop-in directory {} has no jars", dir);
            return 0;
        }
        int loaded = 0;
        for (Path jar : jars) {
            if (installAndStart(context, jar)) {
                loaded++;
            }
        }
        LOGGER.info("Loaded {} of {} drop-in extension bundle(s) from {}", loaded, jars.size(), dir);
        return loaded;
    }

    private static boolean installAndStart(BundleContext context, Path jar) {
        String location = jar.toUri().toString();
        try {
            Bundle bundle = context.getBundle(location);
            if (bundle == null) {
                try (InputStream in = Files.newInputStream(jar)) {
                    bundle = context.installBundle(location, in);
                }
                LOGGER.info("Installed drop-in extension bundle {} from {}", bundle.getSymbolicName(), jar);
            }
            // Fragments-by-OSGi-host would fail to start; e4 model fragments
            // are regular bundles, so starting is the normal path. Tolerate a
            // bundle that resolves but cannot be started (still contributes
            // its registry extensions once resolved).
            if (bundle.getHeaders().get("Fragment-Host") == null) {
                bundle.start(Bundle.START_TRANSIENT);
            }
            return true;
        } catch (Exception e) {
            // US6 scenario 2: never break the boot over a bad drop-in.
            LOGGER.warn("Could not load drop-in extension {}: {}", jar.getFileName(), e.toString());
            return false;
        }
    }

    private static List<Path> listJars(Path dir) {
        List<Path> jars = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) {
                    jars.add(p);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not scan drop-in directory {}: {}", dir, e.toString());
        }
        jars.sort(null); // deterministic order
        return jars;
    }

    /**
     * Resolves the drop-in directory: an explicit {@link #DROPIN_DIR_PROP}
     * wins (tests / custom layouts); otherwise {@code plugins-ext/} under the
     * installation area (the {@code ui/} folder of the release or of the
     * self-contained {@code <case>/iped/ui/}). Returns {@code null} when no
     * directory can be determined.
     */
    public static Path resolveDropinDir() {
        String override = System.getProperty(DROPIN_DIR_PROP);
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        Location install = Platform.getInstallLocation();
        File base = install != null ? urlToFile(install.getURL()) : null;
        return base == null ? null : base.toPath().resolve(DROPIN_DIR_NAME);
    }

    /** file: URL → File, tolerating the {@code /C:/} Windows form. */
    private static File urlToFile(URL url) {
        if (url == null || !"file".equals(url.getProtocol())) {
            return null;
        }
        String path = url.getPath();
        if (path.length() > 2 && path.charAt(0) == '/' && path.charAt(2) == ':'
                && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            path = path.substring(1);
        }
        return new File(path);
    }
}
