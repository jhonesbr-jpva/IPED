package iped.rcp.app;

import java.io.File;
import java.io.InputStream;
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
import org.eclipse.e4.ui.model.application.ui.menu.MToolItem;
import org.eclipse.e4.ui.workbench.lifecycle.PostContextCreate;
import org.eclipse.e4.ui.workbench.lifecycle.PreSave;
import org.eclipse.e4.ui.workbench.lifecycle.ProcessAdditions;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
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
import iped.utils.ExternalImageConverter;

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

    /**
     * Window/taskbar icon sizes (the analysis UI reuses the legacy
     * {@code search*} icon set shipped in iped-app under {@code iped/app/icon/};
     * the processing window uses the {@code process*} variant). SWT picks the
     * best match per surface from the supplied set.
     */
    private static final String[] APP_ICON_NAMES = { "search16.png", "search20.png", "search24.png", "search32.png",
            "search48.png", "search64.png" };

    @PostContextCreate
    void postContextCreate(IApplicationContext appContext, IEclipseContext context) {
        Display display = Display.getDefault();

        // T065: point the legacy engine localization resolver
        // (iped.localization.Messages) at the release localization/ folder
        // before any engine i18n runs (case open loads engine configs). From
        // inside the OSGi wrapper its jar-relative/working-dir heuristics fail
        // for an installed product; this reuses the RCP resolver (T009).
        Messages.exportEngineLocalizationDir();

        // Wire the bundled ImageMagick for the bridged image viewer/gallery
        // BEFORE the content viewers initialize (the preview viewer part builds
        // the ExternalImageConverter on its init thread at startup, before any
        // case opens, and the converter freezes its command line on first
        // construction).
        configureExternalImageConverter();

        // T050 (US6, FR-022): install drop-in third-party extensions BEFORE
        // the application model loads, so their fragment.e4xmi contributions
        // are in the extension registry when e4 processes model fragments. A
        // bad drop-in is logged and skipped — the product always boots.
        DropinBundleLoader.loadDropins(FrameworkUtil.getBundle(getClass()).getBundleContext());

        List<Path> casePaths = parseCaseArgs(appContext);
        boolean hasCase = !casePaths.isEmpty();

        // Feature 005 (T005): when no case is passed at startup, boot the
        // workbench WITHOUT a case — the File menu (New/Open Case) is the entry
        // point — instead of forcing a folder dialog and exiting on cancel.

        // T043 (FR-017, research R5): per-user, per-case workspace area — only
        // when a case is given at startup; the menu-driven flow uses the
        // product's default instance area (osgi.instance.area.default). Either
        // way the persisted layout is validated against LAYOUT_VERSION so an
        // incompatible Application.e4xmi change (new toolbar/menus) resets the
        // stale workbench.xmi instead of hiding the new model elements.
        if (hasCase) {
            WorkspaceLocationResolver.applyTo(context, casePaths);
        } else {
            WorkspaceLocationResolver.validateDefaultArea(context);
        }

        // T044 (FR-018, research R8): theme before the workbench shells are
        // created (win32 dark chrome is fixed at widget creation)
        ThemeManager.applyAtStartup(display, context);

        // T045 (FR-019): the SWT side of the user scale is applied by
        // EarlyStartup before the display exists; this aligns the bridged
        // AWT/Swing viewers with the same ~/.iped/UiScale.txt setting
        UiScale.loadUserSetting();

        if (hasCase) {
            ICaseSessionManager sessionManager = context.get(ICaseSessionManager.class);
            try {
                // Blocking on purpose: there is no workbench yet and the native
                // splash gives the startup feedback (FR-027). Runtime open of a
                // different case goes through the File menu and a Job (US1/US2).
                sessionManager.open(casePaths);
            } catch (CaseOpenException e) {
                LOGGER.error("Could not open case session", e);
                showError(display, e.getMessage());
                System.exit(1);
            }
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
        MODEL_LABEL_KEYS.put("iped.rcp.viewers.part.preview", "RcpParts.Preview");
        MODEL_LABEL_KEYS.put("iped.rcp.viewers.part.text", "ContentViewer.TabText");
        MODEL_LABEL_KEYS.put("iped.rcp.viewers.part.metadata", "App.Metadata");
        MODEL_LABEL_KEYS.put("iped.rcp.viewers.part.hex", "RcpParts.Hex");
        MODEL_LABEL_KEYS.put("iped.rcp.specialized.part.map", "App.Map");
        MODEL_LABEL_KEYS.put("iped.rcp.specialized.part.graph", "App.Links");
        MODEL_LABEL_KEYS.put("iped.rcp.specialized.part.timeline", "RcpParts.Timeline");
        MODEL_LABEL_KEYS.put("iped.rcp.views.part.auxtables", "RcpParts.RelatedItems");
        MODEL_LABEL_KEYS.put("iped.rcp.views.part.metadata", "App.Metadata");
        MODEL_LABEL_KEYS.put("iped.rcp.views.part.filterspanel", "App.appliedFilters");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menu.file", "RcpMenu.File");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menuitem.newcase", "RcpMenu.NewCase");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menuitem.opencase", "RcpMenu.OpenCase");
        // Top toolbar items reuse the File-menu labels (same commands)
        MODEL_LABEL_KEYS.put("iped.rcp.app.toolitem.newcase", "RcpMenu.NewCase");
        MODEL_LABEL_KEYS.put("iped.rcp.app.toolitem.opencase", "RcpMenu.OpenCase");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menuitem.manageprofiles", "RcpMenu.ManageProfiles");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menuitem.exit", "RcpMenu.Exit");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menu.view", "RcpMenu.View");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menu.theme", "RcpMenu.Theme");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menuitem.theme.system", "RcpMenu.Theme.System");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menuitem.theme.light", "RcpMenu.Theme.Light");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menuitem.theme.dark", "RcpMenu.Theme.Dark");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menuitem.uiscale", "RcpMenu.UiScale");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menu.help", "RcpMenu.Help");
        MODEL_LABEL_KEYS.put("iped.rcp.app.menuitem.about", "RcpMenu.About");
    }

    @ProcessAdditions
    void processAdditions(MApplication application, EModelService modelService) {
        localizeModelLabels(application, modelService);
        ThemeManager.syncMenuSelection(application, modelService);
        applyWindowIcons(application);

        ICaseSessionManager sessionManager = application.getContext().get(ICaseSessionManager.class);
        if (sessionManager == null) {
            // The OSGi DS services are not registered — typically the Service-Component
            // header was stripped from a bundle manifest (see iped.rcp.core/META-INF).
            // Don't crash the whole workbench on boot; log so it stays diagnosable.
            LOGGER.error("ICaseSessionManager not available (OSGi DS not registered?); skipping window title setup");
            return;
        }
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
            // The main menu lives on the window outside any perspective, so the
            // default search scope (ANYWHERE, which covers perspectives -> parts)
            // misses it; IN_MAIN_MENU is required or the menu labels stay in the
            // e4xmi (English) default regardless of locale.
            for (MMenuElement menu : modelService.findElements(application, entry.getKey(), MMenuElement.class, null,
                    EModelService.ANYWHERE | EModelService.IN_MAIN_MENU)) {
                if (menu instanceof MUILabel labeled) {
                    labeled.setLabel(label);
                }
            }
            // Toolbar items live in the window trim, outside perspectives and
            // the main menu — IN_TRIM is required to reach them.
            for (MToolItem item : modelService.findElements(application, entry.getKey(), MToolItem.class, null,
                    EModelService.ANYWHERE | EModelService.IN_TRIM)) {
                item.setLabel(label);
                item.setTooltip(label);
            }
        }
    }

    /**
     * Sets the IPED icon on the workbench window(s) — title bar and taskbar.
     * Pure e4 has no application icon; the window {@code iconURI} mechanism
     * cannot reach the {@code iped/app/icon/} art nested in the wrapper bundle's
     * Bundle-ClassPath (no {@code platform:} URL resolves into a nested jar), so
     * the icons are loaded through the wrapper class loader ({@link Version}) and
     * applied directly to the Shell. The window widget is created after this
     * lifecycle hook, so the application is applied on the UI thread once the
     * event loop starts.
     */
    private void applyWindowIcons(MApplication application) {
        Display display = Display.getDefault();
        Image[] icons = loadAppIcons(display);
        if (icons.length == 0) {
            return;
        }
        display.asyncExec(() -> {
            boolean applied = false;
            for (MWindow window : application.getChildren()) {
                if (window.getWidget() instanceof Shell shell && !shell.isDisposed()) {
                    shell.setImages(icons);
                    shell.addDisposeListener(e -> disposeImages(icons));
                    applied = true;
                }
            }
            if (!applied) {
                // window not rendered yet (unexpected ordering): fall back to
                // the top-level shells present, then avoid leaking the images
                Shell[] shells = display.getShells();
                if (shells.length > 0) {
                    shells[0].setImages(icons);
                    shells[0].addDisposeListener(e -> disposeImages(icons));
                } else {
                    disposeImages(icons);
                }
            }
        });
    }

    /** Loads the {@link #APP_ICON_NAMES} set from the wrapper bundle. */
    private Image[] loadAppIcons(Display display) {
        List<Image> images = new ArrayList<>();
        for (String name : APP_ICON_NAMES) {
            // Version is loaded by the wrapper class loader, whose
            // Bundle-ClassPath includes the embedded iped-app icon resources.
            try (InputStream is = Version.class.getResourceAsStream("/iped/app/icon/" + name)) {
                if (is != null) {
                    images.add(new Image(display, is));
                }
            } catch (Exception e) {
                LOGGER.debug("Could not load application icon '{}'", name, e);
            }
        }
        return images.toArray(new Image[0]);
    }

    private static void disposeImages(Image[] images) {
        for (Image image : images) {
            if (image != null && !image.isDisposed()) {
                image.dispose();
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

    /**
     * Wires the {@link ExternalImageConverter} system properties for the
     * bridged image viewer and gallery thumbnailer. During processing this is
     * done by {@code ImageThumbTask.init()}, which never runs in the analysis
     * UI; without it the converter is built with an empty tool path, so bare
     * {@code magick} (not on the Equinox launcher PATH) fails and every image
     * conversion errors out. Must run here — before the content viewers build
     * their converter at startup (it freezes its command line on first
     * construction) — so it cannot rely on an open case; the bundled tool is
     * resolved relative to the Equinox install area. Density/timeout defaults
     * already match {@code ImageThumbTaskConfig}; existing {@code -DextImgConv.*}
     * overrides are preserved.
     */
    private void configureExternalImageConverter() {
        if (System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            File root = resolveBundledToolsRoot();
            if (root != null) {
                setIfAbsent(ExternalImageConverter.winToolPathPrefixProp, root.getAbsolutePath());
                setIfAbsent(ExternalImageConverter.enabledProp, "true");
                LOGGER.info("ExternalImageConverter tool path prefix: {}", root.getAbsolutePath());
            } else {
                // bundled ImageMagick not found: leave the converter disabled so
                // the viewers fall back to ImageIO silently instead of flooding
                // the log trying to run a missing command
                LOGGER.warn("Bundled tools/imagemagick not found from the install area; "
                        + "the image viewer/gallery will use ImageIO only");
            }
        } else {
            // Linux ships ImageMagick as a system package on PATH (no tool path
            // prefix), mirroring ImageThumbTask which only sets the prefix on Windows
            setIfAbsent(ExternalImageConverter.enabledProp, "true");
        }
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    /**
     * Resolves the install root that ships {@code tools/imagemagick}, relative
     * to the Equinox install area: in the product the tools folder is a sibling
     * of the {@code ui/} install dir, and in the self-contained case
     * ({@code <case>/iped/ui}) the parent {@code <case>/iped} also holds it.
     * Returns the first candidate where the folder actually exists, or
     * {@code null} when neither does.
     */
    private File resolveBundledToolsRoot() {
        String installArea = System.getProperty("osgi.install.area");
        File install = installArea != null ? areaUrlToFile(installArea) : null;
        if (install == null) {
            return null;
        }
        String toolSubDir = "tools" + File.separator + "imagemagick";
        for (File candidate : new File[] { install.getParentFile(), install }) {
            if (candidate != null && new File(candidate, toolSubDir).isDirectory()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Converts an Equinox area URL ({@code osgi.install.area}) to a file.
     * Equinox does not URL-encode these values, so they are parsed as plain
     * paths after the {@code file:} scheme (mirrors the localization resolver).
     */
    private static File areaUrlToFile(String areaUrl) {
        String path = areaUrl.trim();
        if (path.startsWith("file:")) {
            path = path.substring("file:".length());
        }
        // file:/C:/... form — strip the leading slash before the drive letter
        if (path.length() > 2 && path.charAt(0) == '/' && path.charAt(2) == ':') {
            path = path.substring(1);
        }
        File file = new File(path);
        return file.isDirectory() ? file : null;
    }
}
