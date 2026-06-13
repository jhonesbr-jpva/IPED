package iped.rcp.app;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.MUILabel;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.model.application.ui.menu.MMenuElement;
import org.eclipse.e4.ui.workbench.lifecycle.PostContextCreate;
import org.eclipse.e4.ui.workbench.lifecycle.PreSave;
import org.eclipse.e4.ui.workbench.lifecycle.ProcessAdditions;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.app.ui.utils.UiScale;
import iped.engine.Version;
import iped.rcp.app.theme.ThemeManager;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.session.CaseOpenException;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;

/**
 * e4 lifecycle of the analysis product (T011): resolves which case to open
 * (program arguments or native folder dialog), opens the session before the
 * workbench renders — the native splash stays up during the load, replacing
 * the legacy {@code SplashScreenManager}/{@code StartUpControl} (FR-027) —
 * and disposes the session on shutdown.
 *
 * <p>
 * Accepted program arguments (parity with the current UI launch contract):
 * one or more case folder paths, and/or {@code -multicases <dir-or-txt>}
 * exactly like {@code AppMain}. Unknown {@code -xxx} options are ignored
 * (the Equinox launcher passes through its own).
 */
public class LifeCycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(LifeCycle.class);

    private static final String MULTICASES_ARG = "-multicases";

    @PostContextCreate
    void postContextCreate(IApplicationContext appContext, IEclipseContext context) {
        Display display = Display.getDefault();

        // T050 (US6, FR-022): install drop-in third-party extensions BEFORE
        // the application model loads, so their fragment.e4xmi contributions
        // are in the extension registry when e4 processes model fragments. A
        // bad drop-in is logged and skipped — the product always boots.
        DropinBundleLoader.loadDropins(FrameworkUtil.getBundle(getClass()).getBundleContext());

        List<Path> casePaths = parseCaseArgs(appContext);

        if (casePaths.isEmpty()) {
            Path chosen = askCaseFolder(display);
            if (chosen == null) {
                LOGGER.info("No case selected, exiting");
                System.exit(0);
            }
            casePaths = List.of(chosen);
        }

        // T043 (FR-017, research R5): per-user, per-case workspace area —
        // must happen before the application model is loaded
        WorkspaceLocationResolver.applyTo(context, casePaths);

        // T044 (FR-018, research R8): theme before the workbench shells are
        // created (win32 dark chrome is fixed at widget creation); needs the
        // instance area resolved above for the CSS preference pin
        ThemeManager.applyAtStartup(display, context);

        // T045 (FR-019): the SWT side of the user scale is applied by
        // EarlyStartup before the display exists; this aligns the bridged
        // AWT/Swing viewers with the same ~/.iped/UiScale.txt setting
        UiScale.loadUserSetting();

        ICaseSessionManager sessionManager = context.get(ICaseSessionManager.class);
        try {
            // Blocking on purpose: there is no workbench yet and the native
            // splash gives the startup feedback (FR-027). In-session reloads
            // and long operations use Jobs once parts exist (US1+).
            sessionManager.open(casePaths);
        } catch (CaseOpenException e) {
            LOGGER.error("Could not open case session", e);
            showError(display, e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Localized labels of the static model elements (task T047, FR-020,
     * research R7): the e4 model cannot use the central catalogs through
     * {@code %key} (that mechanism reads per-bundle OSGI-INF/l10n), so labels
     * are applied here — also fixing labels persisted by a previous session
     * in another language (the restored model wins over Application.e4xmi).
     */
    private static final Map<String, String> MODEL_LABEL_KEYS = new LinkedHashMap<>();
    static {
        MODEL_LABEL_KEYS.put("iped.rcp.views.part.evidencetree", "TreeViewModel.RootName");
        MODEL_LABEL_KEYS.put("iped.rcp.views.part.categorytree", "CategoryTreeModel.RootName");
        MODEL_LABEL_KEYS.put("iped.rcp.views.part.bookmarktree", "BookmarksTreeModel.RootName");
        MODEL_LABEL_KEYS.put("iped.rcp.views.part.aifilters", "App.AIFilters");
        MODEL_LABEL_KEYS.put("iped.rcp.views.part.searchbar", "App.Search");
        MODEL_LABEL_KEYS.put("iped.rcp.views.part.results", "App.Table");
        MODEL_LABEL_KEYS.put("iped.rcp.views.part.gallery", "App.Gallery");
        MODEL_LABEL_KEYS.put("iped.rcp.viewers.part.content", "RcpParts.Viewer");
        MODEL_LABEL_KEYS.put("iped.rcp.specialized.part.map", "App.Map");
        MODEL_LABEL_KEYS.put("iped.rcp.specialized.part.graph", "App.Links");
        MODEL_LABEL_KEYS.put("iped.rcp.specialized.part.timeline", "RcpParts.Timeline");
        MODEL_LABEL_KEYS.put("iped.rcp.views.part.auxtables", "RcpParts.RelatedItems");
        MODEL_LABEL_KEYS.put("iped.rcp.views.part.metadata", "App.Metadata");
        MODEL_LABEL_KEYS.put("iped.rcp.views.part.filterspanel", "App.appliedFilters");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menu.view", "RcpMenu.View");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menu.theme", "RcpMenu.Theme");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menuitem.theme.system", "RcpMenu.Theme.System");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menuitem.theme.light", "RcpMenu.Theme.Light");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menuitem.theme.dark", "RcpMenu.Theme.Dark");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menuitem.uiscale", "RcpMenu.UiScale");
    }

    @ProcessAdditions
    void processAdditions(MApplication application, EModelService modelService) {
        localizeModelLabels(application, modelService);
        ThemeManager.syncMenuSelection(application, modelService);

        ICaseSessionManager sessionManager = application.getContext().get(ICaseSessionManager.class);
        CaseSession session = sessionManager.getSession();
        if (session == null) {
            return;
        }
        String cases = session.getCasePaths().stream().map(p -> p.getFileName().toString())
                .collect(Collectors.joining(", "));
        for (MWindow window : application.getChildren()) {
            window.setLabel(Version.APP_NAME + " - " + cases);
        }
    }

    private void localizeModelLabels(MApplication application, EModelService modelService) {
        for (Map.Entry<String, String> entry : MODEL_LABEL_KEYS.entrySet()) {
            String label = Messages.getString(entry.getValue());
            for (MPart part : modelService.findElements(application, entry.getKey(), MPart.class, null)) {
                part.setLabel(label);
            }
            for (MMenuElement menu : modelService.findElements(application, entry.getKey(), MMenuElement.class,
                    null)) {
                if (menu instanceof MUILabel labeled) {
                    labeled.setLabel(label);
                }
            }
        }
    }

    @PreSave
    void preSave(IEclipseContext context) {
        // Runs when the model is persisted on shutdown. Good enough while no
        // part holds the session; T012 moves disposal after workbench stop
        // with the case/CLOSED event ordering.
        ICaseSessionManager sessionManager = context.get(ICaseSessionManager.class);
        if (sessionManager != null) {
            sessionManager.close();
        }
    }

    private List<Path> parseCaseArgs(IApplicationContext appContext) {
        String[] args = (String[]) appContext.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        List<Path> casePaths = new ArrayList<>();
        if (args == null) {
            return casePaths;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (MULTICASES_ARG.equals(arg) && i + 1 < args.length) {
                casePaths.add(new File(args[++i]).getAbsoluteFile().toPath());
            } else if (arg.startsWith("-")) {
                LOGGER.debug("Ignoring launcher argument {}", arg);
            } else {
                File candidate = new File(arg).getAbsoluteFile();
                // same shapes AppMain accepts: a folder (case or tree of
                // cases) or a .txt multicase list. Anything else is a
                // launcher/test-harness artifact (e.g. tycho-surefire passes
                // its surefire.properties as a program argument)
                if (candidate.isDirectory() || arg.toLowerCase().endsWith(".txt")) {
                    casePaths.add(candidate.toPath());
                } else {
                    LOGGER.debug("Ignoring non-case program argument {}", arg);
                }
            }
        }
        return casePaths;
    }

    private Path askCaseFolder(Display display) {
        Shell shell = new Shell(display);
        try {
            DirectoryDialog dialog = new DirectoryDialog(shell, SWT.OPEN);
            dialog.setText(Messages.getString("AppLifeCycle.selectCase.title"));
            dialog.setMessage(Messages.getString("AppLifeCycle.selectCase.message"));
            String dir = dialog.open();
            return dir != null ? Path.of(dir) : null;
        } finally {
            shell.dispose();
        }
    }

    private void showError(Display display, String message) {
        Shell shell = new Shell(display);
        try {
            MessageBox box = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
            box.setText(Messages.getString("AppLifeCycle.openError.title"));
            box.setMessage(message != null ? message : "");
            box.open();
        } finally {
            shell.dispose();
        }
    }
}
