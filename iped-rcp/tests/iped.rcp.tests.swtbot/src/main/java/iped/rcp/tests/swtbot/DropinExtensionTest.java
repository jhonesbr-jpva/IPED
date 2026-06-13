package iped.rcp.tests.swtbot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swtbot.swt.finder.finders.UIThreadRunnable;
import org.eclipse.swtbot.swt.finder.results.Result;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import iped.rcp.app.DropinBundleLoader;

/**
 * Drop-in extension install/removal test (task T048, US6, SC-007). Validates
 * the {@link DropinBundleLoader} contract (T050) and the sample extension
 * (T051) against the {@code ui-extension-api} contract:
 *
 * <ul>
 * <li><b>boot integrity</b> — a missing drop-in directory or a malformed jar
 * is tolerated (logged and skipped); the loader never throws (US6 scenario 2,
 * acceptance criterion 4 of the case-launcher-packaging contract);</li>
 * <li><b>install</b> — a valid extension jar placed in the drop-in directory
 * is installed and started by the loader (assumption-guarded leg, opt-in with
 * {@code -Diped.rcp.sample.jar=<path>});</li>
 * <li><b>contribution</b> — when the sample was dropped in at BOOT (opt-in with
 * {@code -Diped.rcp.dropins.dir=<dir>} on the harness, see the pom), its part
 * appears in the live e4 model and its bundle is active.</li>
 * </ul>
 *
 * <p>
 * The loader-tolerance legs are deterministic and always run (file level,
 * exactly the code path the lifecycle runs at boot). The install/contribution
 * legs need the built sample jar and/or a boot-time drop-in, so they are
 * {@link org.junit.Assume assumption-guarded} — green on the real product when
 * run with the flags above (same discipline as T014/T042).
 */
public class DropinExtensionTest {

    private static final String SAMPLE_BSN = "iped.rcp.sample.view";
    private static final String SAMPLE_PART_ID = "iped.rcp.sample.view.part";

    /** Optional path to the built sample jar (install leg). */
    private static final String SAMPLE_JAR_PROP = "iped.rcp.sample.jar";

    private static BundleContext context() {
        return FrameworkUtil.getBundle(DropinExtensionTest.class).getBundleContext();
    }

    // ------------------------------------------------------------------
    // Boot integrity: the loader tolerates bad / absent drop-ins (US6 #2)
    // ------------------------------------------------------------------

    @Test
    public void loaderToleratesMissingDirectory() {
        Path missing = Path.of(System.getProperty("java.io.tmpdir"), "iped-t048-does-not-exist-" + System.nanoTime());
        withDropinDir(missing.toString(), () -> {
            int loaded = DropinBundleLoader.loadDropins(context());
            assertEquals("missing drop-in dir must load nothing and not throw", 0, loaded);
        });
    }

    @Test
    public void loaderToleratesCorruptJar() throws IOException {
        Path dir = Files.createTempDirectory("iped-t048-corrupt");
        Path badJar = dir.resolve("not-really-a-bundle.jar");
        Files.writeString(badJar, "this is not a zip archive", StandardCharsets.UTF_8);
        String location = badJar.toUri().toString();
        try {
            withDropinDir(dir.toString(), () -> {
                int loaded = DropinBundleLoader.loadDropins(context());
                assertEquals("a malformed jar must be skipped, boot survives", 0, loaded);
            });
            assertNull("a malformed jar must not leave a bundle installed", context().getBundle(location));
        } finally {
            Files.deleteIfExists(badJar);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void resolveDropinDirDefaultsToInstallAreaPluginsExt() {
        String saved = System.getProperty(DropinBundleLoader.DROPIN_DIR_PROP);
        System.clearProperty(DropinBundleLoader.DROPIN_DIR_PROP);
        try {
            Path dir = DropinBundleLoader.resolveDropinDir();
            // null only when no install area is exposed; otherwise it is the
            // plugins-ext sibling of the product's plugins/ (contract layout)
            if (dir != null) {
                assertEquals(DropinBundleLoader.DROPIN_DIR_NAME, dir.getFileName().toString());
            }
        } finally {
            restore(DropinBundleLoader.DROPIN_DIR_PROP, saved);
        }
    }

    // ------------------------------------------------------------------
    // Install leg (opt-in: -Diped.rcp.sample.jar=<built sample jar>)
    // ------------------------------------------------------------------

    @Test
    public void validSampleInstallsAndStartsViaLoader() throws IOException {
        String jarPath = System.getProperty(SAMPLE_JAR_PROP);
        assumeTrue("set -D" + SAMPLE_JAR_PROP + "=<built sample jar> to run this leg",
                jarPath != null && !jarPath.isBlank() && Files.isRegularFile(Path.of(jarPath)));

        Path dir = Files.createTempDirectory("iped-t048-install");
        Path staged = dir.resolve("iped.rcp.sample.view.jar");
        Files.copy(Path.of(jarPath), staged, StandardCopyOption.REPLACE_EXISTING);
        String location = staged.toUri().toString();
        Bundle installed = null;
        try {
            int loaded = withDropinDirReturning(dir.toString(), () -> DropinBundleLoader.loadDropins(context()));
            assertEquals("the valid sample bundle must be loaded", 1, loaded);

            installed = context().getBundle(location);
            assertNotNull("sample bundle must be installed by the loader", installed);
            assertEquals(SAMPLE_BSN, installed.getSymbolicName());
            assertTrue("sample bundle must be at least resolved (started when activatable)",
                    installed.getState() >= Bundle.RESOLVED);
        } finally {
            if (installed != null) {
                try {
                    installed.uninstall(); // keep the framework clean for the other tests
                } catch (Exception ignore) {
                    // best effort
                }
            }
            Files.deleteIfExists(staged);
            Files.deleteIfExists(dir);
        }
    }

    // ------------------------------------------------------------------
    // Contribution leg (opt-in: sample dropped in at BOOT via the pom's
    // -Diped.rcp.dropins.dir — then its fragment.e4xmi part is in the model)
    // ------------------------------------------------------------------

    @Test
    public void sampleViewContributedWhenDroppedInAtBoot() {
        Bundle sample = findBundle(SAMPLE_BSN);
        assumeTrue("sample not dropped in at boot (set -Diped.rcp.dropins.dir on the harness)", sample != null);

        assertTrue("a boot-time drop-in must be active", sample.getState() == Bundle.ACTIVE);

        MWindow window = findMainWindow();
        assertNotNull("main window model not found", window);
        EModelService modelService = window.getContext().get(EModelService.class);
        MApplication application = window.getContext().get(MApplication.class);
        assertTrue("the sample fragment.e4xmi part must be contributed to the model",
                !modelService.findElements(application, SAMPLE_PART_ID, MPart.class, null).isEmpty());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Bundle findBundle(String symbolicName) {
        for (Bundle b : context().getBundles()) {
            if (symbolicName.equals(b.getSymbolicName())) {
                return b;
            }
        }
        return null;
    }

    private static MWindow findMainWindow() {
        return UIThreadRunnable.syncExec((Result<MWindow>) () -> {
            for (Shell shell : Display.getDefault().getShells()) {
                if (shell.getData("modelElement") instanceof MWindow w) {
                    return w;
                }
            }
            return null;
        });
    }

    private static void withDropinDir(String dir, Runnable body) {
        String saved = System.getProperty(DropinBundleLoader.DROPIN_DIR_PROP);
        System.setProperty(DropinBundleLoader.DROPIN_DIR_PROP, dir);
        try {
            body.run();
        } finally {
            restore(DropinBundleLoader.DROPIN_DIR_PROP, saved);
        }
    }

    private static int withDropinDirReturning(String dir, java.util.function.IntSupplier body) {
        String saved = System.getProperty(DropinBundleLoader.DROPIN_DIR_PROP);
        System.setProperty(DropinBundleLoader.DROPIN_DIR_PROP, dir);
        try {
            return body.getAsInt();
        } finally {
            restore(DropinBundleLoader.DROPIN_DIR_PROP, saved);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
