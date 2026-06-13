package iped.rcp.tests.swtbot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.MUILabel;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.IModelResourceHandler;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swtbot.swt.finder.finders.UIThreadRunnable;
import org.eclipse.swtbot.swt.finder.results.Result;
import org.eclipse.swtbot.swt.finder.results.VoidResult;
import org.junit.Test;

import iped.rcp.app.WorkspaceLocationResolver;
import iped.rcp.app.WorkspaceLocationResolver.StateCheck;

/**
 * Workspace persistence and restoration test (task T042, US5, FR-017,
 * SC-005). Covers the {@link WorkspaceLocationResolver} contract (T043):
 *
 * <ul>
 * <li>per-case workspace area under {@code ~/.iped/ui-workspaces/<case-id>}
 * with {@code case-id} stable, order-insensitive for multicase and distinct
 * per case path (research R5, data-model {@code WorkspaceState});</li>
 * <li>corrupted {@code workbench.xmi} or incompatible layout version reset
 * automatically to the default layout, never failing the boot;</li>
 * <li>live layout state persisted by the e4 mechanism into the resolved
 * area and restored on the next launch.</li>
 * </ul>
 *
 * <p>
 * Restart semantics under the tycho-surefire UI harness: the workbench boots
 * once per {@code mvn verify} invocation, so the cross-restart assertion uses
 * a marker-file protocol — the first run tags the window model, saves it
 * through the same {@link IModelResourceHandler} used at shutdown and records
 * the marker; the next run (same work area, no {@code clean}) asserts the tag
 * was restored into the LIVE model. A single CI run exercises the write leg
 * and the full restore evidence comes from two consecutive local runs plus
 * the manual smoke on the real product (parity inventory, SC-005).
 *
 * <p>
 * The true "corrupted state at boot" leg cannot be staged in-harness (the
 * workbench is already up when tests run); it is covered at the file level
 * here ({@link WorkspaceLocationResolver#validateOrResetState}) — exactly the
 * code path the lifecycle runs before the model loads.
 */
public class WorkspacePersistenceTest {

    /** Tag prefix used to mark the window model across restarts. */
    private static final String MARKER_TAG_PREFIX = "iped.rcp.test.t042.";

    /** Marker file (in the instance area) carrying uuid + layout version. */
    private static final String MARKER_FILE = "t042.marker";

    // ------------------------------------------------------------------
    // Resolver: per-case workspace area (T043, research R5)
    // ------------------------------------------------------------------

    @Test
    public void workspaceAreaIsStablePerCaseAndDistinctAcrossCases() throws Exception {
        Path caseA = Files.createTempDirectory("t042-caseA");
        Path caseB = Files.createTempDirectory("t042-caseB");
        try {
            Path wsA = WorkspaceLocationResolver.resolveWorkspaceDir(List.of(caseA));
            assertEquals("same case must always map to the same workspace", wsA,
                    WorkspaceLocationResolver.resolveWorkspaceDir(List.of(caseA)));

            Path wsB = WorkspaceLocationResolver.resolveWorkspaceDir(List.of(caseB));
            assertNotEquals("different cases must not share layout state", wsA, wsB);

            // multicase: order-insensitive identity, distinct from singles
            Path wsAB = WorkspaceLocationResolver.resolveWorkspaceDir(List.of(caseA, caseB));
            Path wsBA = WorkspaceLocationResolver.resolveWorkspaceDir(List.of(caseB, caseA));
            assertEquals("multicase id must not depend on argument order", wsAB, wsBA);
            assertNotEquals(wsA, wsAB);
            assertNotEquals(wsB, wsAB);
        } finally {
            Files.deleteIfExists(caseA);
            Files.deleteIfExists(caseB);
        }
    }

    @Test
    public void workspaceRootIsOverridableForTests() throws Exception {
        Path root = Files.createTempDirectory("t042-root");
        Path caseDir = Files.createTempDirectory("t042-case");
        System.setProperty(WorkspaceLocationResolver.WORKSPACE_ROOT_PROP, root.toString());
        try {
            Path ws = WorkspaceLocationResolver.resolveWorkspaceDir(List.of(caseDir));
            assertTrue("workspace must live under the configured root: " + ws,
                    ws.toAbsolutePath().startsWith(root.toAbsolutePath()));
        } finally {
            System.clearProperty(WorkspaceLocationResolver.WORKSPACE_ROOT_PROP);
            Files.deleteIfExists(caseDir);
        }
    }

    // ------------------------------------------------------------------
    // Corrupted / incompatible persisted state → silent reset (FR-017)
    // ------------------------------------------------------------------

    @Test
    public void freshWorkspacePassesValidationAndIsStamped() throws Exception {
        Path ws = Files.createTempDirectory("t042-fresh");
        assertEquals(StateCheck.FRESH, WorkspaceLocationResolver.validateOrResetState(ws));
        // second boot on the same untouched area: compatible, not reset
        assertEquals(StateCheck.COMPATIBLE, WorkspaceLocationResolver.validateOrResetState(ws));
    }

    @Test
    public void corruptedWorkbenchXmiIsResetToDefaultLayout() throws Exception {
        Path ws = Files.createTempDirectory("t042-corrupt");
        // a first boot stamps the area as current
        assertEquals(StateCheck.FRESH, WorkspaceLocationResolver.validateOrResetState(ws));

        Path xmi = WorkspaceLocationResolver.workbenchXmi(ws);
        Files.createDirectories(xmi.getParent());
        Files.writeString(xmi, "<application:Application truncated-by-a-crash", StandardCharsets.UTF_8);

        assertEquals(StateCheck.RESET_CORRUPTED, WorkspaceLocationResolver.validateOrResetState(ws));
        assertFalse("invalid workbench.xmi must be moved aside so e4 boots the default layout",
                Files.exists(xmi));
        // the bad state is preserved for diagnostics, not lost
        assertTrue(Files.exists(xmi.resolveSibling(xmi.getFileName() + ".bak")));
        // and the next boot is a plain compatible one
        assertEquals(StateCheck.COMPATIBLE, WorkspaceLocationResolver.validateOrResetState(ws));
    }

    @Test
    public void incompatibleLayoutVersionIsReset() throws Exception {
        Path ws = Files.createTempDirectory("t042-version");
        assertEquals(StateCheck.FRESH, WorkspaceLocationResolver.validateOrResetState(ws));

        Path xmi = WorkspaceLocationResolver.workbenchXmi(ws);
        Files.createDirectories(xmi.getParent());
        Files.writeString(xmi, "<?xml version=\"1.0\"?><application/>", StandardCharsets.UTF_8);
        // simulate state written by an older/newer product generation
        Path stamp = WorkspaceLocationResolver.layoutVersionStamp(ws);
        Files.writeString(stamp, "0", StandardCharsets.UTF_8);

        assertEquals(StateCheck.RESET_INCOMPATIBLE, WorkspaceLocationResolver.validateOrResetState(ws));
        assertFalse(Files.exists(xmi));
        assertEquals(WorkspaceLocationResolver.LAYOUT_VERSION,
                Files.readString(stamp, StandardCharsets.UTF_8).trim());
    }

    // ------------------------------------------------------------------
    // Live persistence + restore across launches (SC-005)
    // ------------------------------------------------------------------

    @Test
    public void layoutStatePersistsAndIsRestoredAcrossLaunches() throws Exception {
        MWindow window = findMainWindow();
        assertNotNull("main window model not found", window);
        File instanceArea = instanceArea();
        assertNotNull("harness must run with an instance area", instanceArea);

        File markerFile = new File(instanceArea, MARKER_FILE);
        Properties marker = new Properties();
        if (markerFile.isFile()) {
            try (var in = Files.newInputStream(markerFile.toPath())) {
                marker.load(in);
            }
        }
        String previousUuid = marker.getProperty("uuid");
        String previousVersion = marker.getProperty("layoutVersion");

        if (previousUuid != null && WorkspaceLocationResolver.LAYOUT_VERSION.equals(previousVersion)) {
            // RESTORE leg: a previous launch tagged the model and saved it —
            // the live model of THIS launch must carry the restored tag
            assertTrue("layout state from the previous launch was not restored (SC-005)",
                    window.getTags().contains(MARKER_TAG_PREFIX + previousUuid));
        }

        // WRITE leg (always): tag the window — model state, persisted exactly
        // like part stack arrangement/sash weights — and run the same save
        // path the workbench runs at shutdown
        String uuid = UUID.randomUUID().toString();
        IModelResourceHandler handler = window.getContext().get(MApplication.class).getContext()
                .get(IModelResourceHandler.class);
        assertNotNull("IModelResourceHandler must be available in the application context", handler);

        UIThreadRunnable.syncExec((VoidResult) () -> {
            window.getTags().removeIf(t -> t.startsWith(MARKER_TAG_PREFIX));
            window.getTags().add(MARKER_TAG_PREFIX + uuid);
            try {
                handler.save();
            } catch (IOException e) {
                throw new IllegalStateException("workbench model save failed", e);
            }
        });

        Path xmi = instanceArea.toPath()
                .resolve(".metadata/.plugins/org.eclipse.e4.workbench/workbench.xmi");
        assertTrue("workbench.xmi must be written into the instance area", Files.exists(xmi));
        String persisted = Files.readString(xmi, StandardCharsets.UTF_8);
        assertTrue("persisted model must contain the layout marker tag",
                persisted.contains(MARKER_TAG_PREFIX + uuid));

        marker.setProperty("uuid", uuid);
        marker.setProperty("layoutVersion", WorkspaceLocationResolver.LAYOUT_VERSION);
        try (var out = Files.newOutputStream(markerFile.toPath())) {
            marker.store(out, "T042 cross-launch layout restoration marker");
        }
    }

    // ------------------------------------------------------------------
    // SC-006 support: no raw !key! anywhere on the rendered model (T047)
    // ------------------------------------------------------------------

    @Test
    public void noRawI18nKeysOnWorkbenchSurfaces() {
        MWindow window = findMainWindow();
        assertNotNull(window);
        assertNoRawKey("window", window);

        EModelService modelService = window.getContext().get(EModelService.class);
        MApplication application = window.getContext().get(MApplication.class);
        for (MPart part : modelService.findElements(application, null, MPart.class, null)) {
            assertNoRawKey(part.getElementId(), part);
        }
    }

    private static void assertNoRawKey(String what, MUILabel labeled) {
        String label = labeled.getLabel();
        assertFalse("raw i18n key leaked into " + what + ": " + label,
                label != null && label.length() > 2 && label.startsWith("!") && label.endsWith("!"));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** The MWindow of the main product window (renderer puts it in shell data). */
    private static MWindow findMainWindow() {
        return UIThreadRunnable.syncExec((Result<MWindow>) () -> {
            for (Shell shell : Display.getDefault().getShells()) {
                Object model = shell.getData("modelElement");
                if (model instanceof MWindow w) {
                    return w;
                }
            }
            return null;
        });
    }

    private static File instanceArea() {
        AtomicReference<File> dir = new AtomicReference<>();
        URL url = Platform.getInstanceLocation() != null ? Platform.getInstanceLocation().getURL() : null;
        if (url != null && "file".equals(url.getProtocol())) {
            String path = url.getPath();
            // file:/C:/... form on Windows
            if (path.length() > 2 && path.charAt(0) == '/' && path.charAt(2) == ':') {
                path = path.substring(1);
            }
            dir.set(new File(path));
        }
        return dir.get();
    }
}
