package iped.rcp.viewers.bridge;

import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Marshaling helpers between the three UI threads coexisting in the bridged
 * UI (task T010, research R4, constitution principle V): the SWT UI thread
 * ({@code Display.asyncExec}), the AWT/Swing EDT (legacy viewers) and the
 * JavaFX Application Thread (JFXPanel-based viewers).
 *
 * <p>
 * Deadlock discipline: never block one UI thread waiting on another in both
 * directions. Prefer the async variants; the sync variants are for short
 * read-mostly calls and must never be nested (e.g. EDT sync call made while
 * the SWT thread is itself blocked on the EDT).
 */
public final class UiThreads {

    private static final Logger LOGGER = LoggerFactory.getLogger(UiThreads.class);

    private static volatile Method fxRunLater;

    private UiThreads() {
    }

    /** Runs on the SWT UI thread, asynchronously. */
    public static void onSwtAsync(Display display, Runnable runnable) {
        display.asyncExec(runnable);
    }

    /** Runs on the SWT UI thread, blocking (runs inline when already there). */
    public static void onSwtSync(Display display, Runnable runnable) {
        display.syncExec(runnable);
    }

    /** Runs on the AWT EDT, asynchronously (inline when already there). */
    public static void onEdtAsync(Runnable runnable) {
        if (EventQueue.isDispatchThread()) {
            runnable.run();
        } else {
            EventQueue.invokeLater(runnable);
        }
    }

    /**
     * Runs on the AWT EDT, blocking (runs inline when already there). See the
     * class Javadoc for the deadlock discipline before using this from the
     * SWT thread.
     */
    public static void onEdtSync(Runnable runnable) {
        if (EventQueue.isDispatchThread()) {
            runnable.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(runnable);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("EDT task failed", e.getCause());
        }
    }

    /**
     * Runs on the JavaFX Application Thread, asynchronously.
     *
     * <p>
     * Resolved reflectively through the system class loader: JavaFX ships as
     * JDK modules of the bundled full JRE, not as OSGi bundles, so
     * {@code javafx.*} is not visible to bundle class loaders at compile
     * time. The FX toolkit must already be initialized (the legacy viewers
     * do that by instantiating their {@code JFXPanel}s).
     *
     * @throws IllegalStateException if JavaFX is not available in the
     *             runtime or the toolkit was not initialized yet
     */
    public static void onFxAsync(Runnable runnable) {
        try {
            runLater().invoke(null, runnable);
        } catch (IllegalAccessException | InvocationTargetException e) {
            Throwable cause = e instanceof InvocationTargetException ite ? ite.getCause() : e;
            throw new IllegalStateException("Could not run on the JavaFX thread", cause);
        }
    }

    /** @return true when the JavaFX runtime is present in this JVM */
    public static boolean isFxAvailable() {
        try {
            runLater();
            return true;
        } catch (IllegalStateException e) {
            LOGGER.debug("JavaFX not available", e);
            return false;
        }
    }

    private static Method runLater() {
        Method method = fxRunLater;
        if (method == null) {
            try {
                Class<?> platform = Class.forName("javafx.application.Platform", false,
                        ClassLoader.getSystemClassLoader());
                method = platform.getMethod("runLater", Runnable.class);
                fxRunLater = method;
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                throw new IllegalStateException("JavaFX runtime not found (a full JRE with JavaFX is required)", e);
            }
        }
        return method;
    }
}
