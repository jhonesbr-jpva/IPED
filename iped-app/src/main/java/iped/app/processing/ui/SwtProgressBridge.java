package iped.app.processing.ui;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.util.UIPropertyListenerProvider;

/**
 * Reflective bridge to the standalone SWT progress window
 * ({@code iped.rcp.progress.ProgressWindow}, task T039/FR-026). The default
 * (non-RCP) build of iped-app has NO compile or runtime dependency on SWT:
 * the window classes are discovered at runtime and, when absent, the caller
 * falls back to the legacy Swing frame or to the console
 * ({@link ProgressUiChooser}).
 *
 * <p>
 * Discovery order for the window jars (the progress jar plus the
 * platform-specific SWT jar, side by side in one folder):
 * <ol>
 * <li>already on the classpath ({@code Class.forName}) — post cut-over
 * layout;</li>
 * <li>{@code -Diped.progress.ui.dir} / {@code IPED_PROGRESS_UI_DIR} env var
 * (dev validation; env vars propagate to the processing child JVM);</li>
 * <li>{@code <install root>/ui/progress} (release layout — packaging task
 * T052).</li>
 * </ol>
 */
public class SwtProgressBridge {

    /** System property pointing at the folder with the progress UI jars. */
    public static final String PROGRESS_UI_DIR_PROP = "iped.progress.ui.dir";

    /** Environment variable alternative to {@link #PROGRESS_UI_DIR_PROP}. */
    public static final String PROGRESS_UI_DIR_ENV = "IPED_PROGRESS_UI_DIR";

    private static final String WINDOW_CLASS = "iped.rcp.progress.ProgressWindow";

    private static final Logger LOGGER = LoggerFactory.getLogger(SwtProgressBridge.class);

    private static Class<?> windowClass;

    private SwtProgressBridge() {
    }

    /**
     * Tries to open the SWT progress window.
     *
     * @return a handle whose {@code close()} disposes the window, or
     *         {@code null} when the window is not deployed or no display is
     *         available (callers decide the fallback)
     */
    public static AutoCloseable tryOpen(UIPropertyListenerProvider provider, String rootPath) {
        try {
            Class<?> cls = loadWindowClass(rootPath);
            if (cls == null) {
                return null;
            }
            Object window = cls.getMethod("open", UIPropertyListenerProvider.class).invoke(null, provider);
            return (AutoCloseable) window;
        } catch (Throwable t) {
            LOGGER.warn("SWT progress window unavailable ({}), using fallback progress UI", t.toString());
            return null;
        }
    }

    /**
     * Best-effort SWT dialog for initializer errors (task T041, FR-027).
     *
     * @return true if a dialog was shown
     */
    public static boolean showStartupError(String title, String message, String rootPath) {
        try {
            Class<?> cls = loadWindowClass(rootPath);
            if (cls == null) {
                return false;
            }
            return (Boolean) cls.getMethod("showStartupError", String.class, String.class).invoke(null, title,
                    message);
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized Class<?> loadWindowClass(String rootPath) {
        if (windowClass != null) {
            return windowClass;
        }
        try {
            return windowClass = Class.forName(WINDOW_CLASS);
        } catch (ClassNotFoundException e) {
            // not on the flat classpath, try the deployed folder below
        }
        File dir = resolveJarsDir(rootPath);
        if (dir == null) {
            return null;
        }
        File[] jars = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            return null;
        }
        try {
            List<URL> urls = new ArrayList<>();
            for (File jar : jars) {
                urls.add(jar.toURI().toURL());
            }
            // the loader stays alive with the window for the whole processing
            URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]),
                    SwtProgressBridge.class.getClassLoader());
            windowClass = Class.forName(WINDOW_CLASS, true, loader);
            LOGGER.info("SWT progress window loaded from {}", dir.getAbsolutePath());
            return windowClass;
        } catch (Throwable t) {
            LOGGER.warn("Error loading the SWT progress window from {} ({})", dir, t.toString());
            return null;
        }
    }

    private static File resolveJarsDir(String rootPath) {
        String override = System.getProperty(PROGRESS_UI_DIR_PROP, System.getenv(PROGRESS_UI_DIR_ENV));
        if (override != null && !override.isBlank()) {
            File dir = new File(override);
            if (dir.isDirectory()) {
                return dir;
            }
            LOGGER.warn("Configured progress UI dir not found: {}", dir.getAbsolutePath());
        }
        if (rootPath != null) {
            File dir = new File(rootPath, "ui" + File.separator + "progress");
            if (dir.isDirectory()) {
                return dir;
            }
        }
        return null;
    }
}
