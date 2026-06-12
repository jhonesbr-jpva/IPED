package iped.rcp.specialized.bridge;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.app.ui.App;
import iped.rcp.core.session.CaseSession;
import iped.viewers.bookmarks.IBookmarksController;

/**
 * Binds the minimal state of the legacy Swing {@code App} singleton that the
 * bridged specialized views read at runtime (tasks T035/T036, FR-028 - the
 * legacy code is hosted, not rewritten).
 *
 * <p>
 * {@code App.get()} only lazily instantiates an EMPTY {@code App} object (its
 * constructor is trivial; the heavy legacy UI is built by {@code App.init},
 * which is never called here). The bridged graph and timeline read exactly
 * two public fields from it:
 * <ul>
 * <li>{@code App.appCase} - the open {@code IPEDMultiSource} (graph database
 * location, timeline cache identity, query building);</li>
 * <li>{@code App.casesPathFile} - base folder used for the multicase graph
 * and the timeline cache tree.</li>
 * </ul>
 * Anything else of the legacy {@code App} stays untouched; legacy code paths
 * that would need the full Swing UI (e.g. opening an evidence from a graph
 * node) degrade with a logged error and are tracked as divergences in the
 * parity inventory.
 */
public final class LegacyApp {

    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyApp.class);

    private LegacyApp() {
    }

    /**
     * Idempotent: binds the legacy singleton to the session. Must run before
     * any bridged view class is constructed - the timeline cache
     * ({@code CachePersistance}) reads {@code App.appCase} from a STATIC
     * initializer.
     */
    public static synchronized void bind(CaseSession session) {
        if (session == null) {
            return;
        }
        App app = App.get();
        app.appCase = session.getSource();
        app.casesPathFile = resolveCasesPathFile(session.getCasePaths());
        ensureBookmarksController();
        LOGGER.info("Legacy App singleton bound to session ({} case folder(s))", session.getCasePaths().size());
    }

    /**
     * Mirrors the legacy meaning of {@code casesPathFile}: the case folder
     * for a single case; for multicase, a virtual file whose PARENT is where
     * shared artifacts ({@code iped-multicases/...}) are created - the legacy
     * UI used the {@code -multicases} txt location for that.
     */
    private static File resolveCasesPathFile(List<Path> casePaths) {
        if (casePaths.size() == 1) {
            return casePaths.get(0).toFile();
        }
        return new File(casePaths.get(0).toFile().getParentFile(), "multicase");
    }

    /**
     * The map and timeline check {@code IBookmarksController.get()} around
     * checkbox bulk updates; without the legacy Swing controller the slot is
     * empty and they would NPE. Registers a minimal controller unless one is
     * already present (the legacy {@code BookmarksController} replaces it if
     * a legacy worker insists on the concrete type - acceptable, its extra
     * UI refresh calls only log on the EDT).
     */
    private static void ensureBookmarksController() {
        if (IBookmarksController.get() == null) {
            IBookmarksController.registerBookmarksController(new IBookmarksController() {
                private volatile boolean multiSetting;

                @Override
                public void setMultiSetting(boolean value) {
                    multiSetting = value;
                }

                @Override
                public boolean isMultiSetting() {
                    return multiSetting;
                }
            });
        }
    }
}
