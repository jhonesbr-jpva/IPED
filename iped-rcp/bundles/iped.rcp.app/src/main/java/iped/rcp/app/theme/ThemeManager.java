package iped.rcp.app.theme;

import java.lang.reflect.Method;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.css.swt.theme.IThemeEngine;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.menu.MMenuItem;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Display;
import org.osgi.service.prefs.BackingStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.rcp.app.theme.ThemePreferences.Mode;

/**
 * Theme application (task T044, FR-018/FR-025, research R8).
 *
 * <p>
 * Default is native widgets with NO CSS engine at all — the e4 styling is
 * only initialized when the effective theme is dark, and even then the CSS is
 * minimal (workbench chrome and containers; see {@code css/dark.css}). Dark
 * follows the OS by default ({@link Display#isSystemDarkTheme()}) with a
 * manual override persisted by {@link ThemePreferences}.
 *
 * <p>
 * Platform notes: on win32 the dark window chrome (title bar, menu bar,
 * scrollbars) comes from SWT display data that must be set before the
 * workbench shells are created — hence {@link #applyAtStartup}, called from
 * the lifecycle; switching at runtime only takes full effect after a restart
 * (the handlers inform the user). On GTK the system theme applies natively;
 * forced dark additionally asks GTK to prefer the dark variant.
 */
public final class ThemeManager {

    /** CSS theme ids registered in {@code plugin.xml}. */
    public static final String NATIVE_THEME_ID = "iped.rcp.theme.native";
    public static final String DARK_THEME_ID = "iped.rcp.theme.dark";

    /** Menu item ids of the theme radio group ({@code Application.e4xmi}). */
    public static final String MENU_SYSTEM_ID = "iped.rcp.app.menuitem.theme.system";
    public static final String MENU_LIGHT_ID = "iped.rcp.app.menuitem.theme.light";
    public static final String MENU_DARK_ID = "iped.rcp.app.menuitem.theme.dark";

    private static final Logger LOGGER = LoggerFactory.getLogger(ThemeManager.class);

    /** ThemeEngine preference node/key (InstanceScope, verified). */
    private static final String THEME_ENGINE_NODE = "org.eclipse.e4.ui.css.swt.theme";
    private static final String THEME_ENGINE_KEY = "themeid";

    private ThemeManager() {
    }

    /** {@code true} when the given mode renders dark on this system. */
    public static boolean isDarkEffective(Mode mode, Display display) {
        return switch (mode) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> display.isSystemDarkTheme();
        };
    }

    /**
     * Applies the persisted theme before the workbench renders. Must run
     * after the workspace location is resolved (T043): the CSS theme id is
     * pinned in the InstanceScope preference the ThemeEngine restores from.
     */
    public static void applyAtStartup(Display display, IEclipseContext context) {
        Mode mode = ThemePreferences.load();
        boolean dark = isDarkEffective(mode, display);
        LOGGER.info("UI theme: {} (effective {})", mode, dark ? "dark" : "light");
        // E4Application overwrites the "cssTheme" context key AFTER the
        // lifecycle runs (verified against the target platform), so the only
        // reliable channel is the InstanceScope preference ThemeEngine
        // prefers on restore(). Pin it on EVERY boot: dark gets the minimal
        // dark CSS, light gets the empty native theme — this also clears a
        // stale dark pin when the OS theme flips back in SYSTEM mode (R8).
        pinThemeEnginePreference(dark ? DARK_THEME_ID : NATIVE_THEME_ID);
        if (!dark) {
            return; // pure native rendering (FR-025)
        }
        applyWin32DarkChrome(display);
        applyGtkDarkPreference();
    }

    /**
     * Persists a new mode and applies what can be applied live (CSS theme
     * switch when the engine is up).
     *
     * @return {@code true} when a restart is needed for full effect
     */
    public static boolean switchTo(Mode mode, IEclipseContext context, Display display) {
        ThemePreferences.save(mode);
        boolean dark = isDarkEffective(mode, display);
        // the e4 CSS engine is always initialized by the platform; pin +
        // live-switch the CSS side here, the native chrome (win32 title bar,
        // GTK variant) is fixed at startup — hence the restart notice
        pinThemeEnginePreference(dark ? DARK_THEME_ID : NATIVE_THEME_ID);
        IThemeEngine engine = context.get(IThemeEngine.class);
        if (engine != null) {
            engine.setTheme(dark ? DARK_THEME_ID : NATIVE_THEME_ID, true);
        }
        return true;
    }

    /** Synchronizes the selected flags of the theme radio menu items. */
    public static void syncMenuSelection(MApplication application, EModelService modelService) {
        Mode mode = ThemePreferences.load();
        setSelected(application, modelService, MENU_SYSTEM_ID, mode == Mode.SYSTEM);
        setSelected(application, modelService, MENU_LIGHT_ID, mode == Mode.LIGHT);
        setSelected(application, modelService, MENU_DARK_ID, mode == Mode.DARK);
    }

    private static void setSelected(MApplication application, EModelService modelService, String id,
            boolean selected) {
        for (MMenuItem item : modelService.findElements(application, id, MMenuItem.class, null)) {
            item.setSelected(selected);
        }
    }

    // ------------------------------------------------------------------

    /**
     * SWT win32 dark chrome (title bar, menus, scrollbars, headers): display
     * data consulted at widget creation — the same set the Eclipse IDE uses.
     * Unknown keys are ignored by other platforms/versions.
     */
    private static void applyWin32DarkChrome(Display display) {
        if (!"win32".equals(SWT.getPlatform())) {
            return;
        }
        display.setData("org.eclipse.swt.internal.win32.useDarkModeExplorerTheme", Boolean.TRUE);
        display.setData("org.eclipse.swt.internal.win32.useShellTitleColoring", Boolean.TRUE);
        display.setData("org.eclipse.swt.internal.win32.menuBarForegroundColor", new Color(0xD0, 0xD0, 0xD0));
        display.setData("org.eclipse.swt.internal.win32.menuBarBackgroundColor", new Color(0x30, 0x30, 0x30));
        display.setData("org.eclipse.swt.internal.win32.menuBarBorderColor", new Color(0x50, 0x50, 0x50));
        display.setData("org.eclipse.swt.internal.win32.all.use_WS_BORDER", Boolean.TRUE);
        display.setData("org.eclipse.swt.internal.win32.Table.headerLineColor", new Color(0x50, 0x50, 0x50));
        display.setData("org.eclipse.swt.internal.win32.Label.disabledForegroundColor", new Color(0x80, 0x80, 0x80));
        display.setData("org.eclipse.swt.internal.win32.Combo.useDarkTheme", Boolean.TRUE);
        display.setData("org.eclipse.swt.internal.win32.ProgressBar.useColors", Boolean.TRUE);
        display.setData("org.eclipse.swt.internal.win32.Text.useDarkThemeIcons", Boolean.TRUE);
    }

    /**
     * Asks GTK to prefer the dark variant of the current theme (best effort,
     * reflective: internal SWT API, absent on other platforms).
     */
    private static void applyGtkDarkPreference() {
        if (!"gtk".equals(SWT.getPlatform())) {
            return;
        }
        try {
            Class<?> os = Class.forName("org.eclipse.swt.internal.gtk.OS");
            Method setDark = os.getMethod("setDarkThemePreferred", boolean.class);
            setDark.invoke(null, true);
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.warn("Could not request the GTK dark variant; theme follows the system", e);
        }
    }

    private static void pinThemeEnginePreference(String themeId) {
        try {
            var node = InstanceScope.INSTANCE.getNode(THEME_ENGINE_NODE);
            node.put(THEME_ENGINE_KEY, themeId);
            node.flush();
        } catch (BackingStoreException | RuntimeException e) {
            LOGGER.warn("Could not pin CSS theme preference", e);
        }
    }
}
