package iped.rcp.app.theme;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.menu.MMenuItem;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import iped.rcp.app.theme.ThemePreferences.Mode;
import iped.rcp.core.i18n.Messages;

/**
 * Theme radio menu handlers (task T044, FR-018): persist the chosen mode,
 * live-switch the CSS side when possible and inform that the native chrome
 * applies fully on the next start.
 *
 * <p>
 * SWT radio menus fire selection on both the selected and the deselected
 * item; only the handler whose item ends up selected applies the change.
 */
public abstract class SetThemeHandler {

    private final Mode mode;
    private final String menuItemId;

    protected SetThemeHandler(Mode mode, String menuItemId) {
        this.mode = mode;
        this.menuItemId = menuItemId;
    }

    @Execute
    public void execute(MApplication application, EModelService modelService, IEclipseContext context) {
        MMenuItem item = firstItem(application, modelService);
        if (item != null && !item.isSelected()) {
            return; // deselection event of the radio group
        }
        if (ThemePreferences.load() == mode) {
            return; // no-op reselect
        }
        Display display = Display.getDefault();
        boolean restartNeeded = ThemeManager.switchTo(mode, context, display);
        ThemeManager.syncMenuSelection(application, modelService);
        if (restartNeeded) {
            Shell shell = display.getActiveShell();
            MessageDialog.openInformation(shell, Messages.getString("RcpTheme.RestartInfo.title"),
                    Messages.getString("RcpTheme.RestartInfo.message"));
        }
    }

    private MMenuItem firstItem(MApplication application, EModelService modelService) {
        var items = modelService.findElements(application, menuItemId, MMenuItem.class, null);
        return items.isEmpty() ? null : items.get(0);
    }

    /** Follow the operating system theme (default). */
    public static class System extends SetThemeHandler {
        public System() {
            super(Mode.SYSTEM, ThemeManager.MENU_SYSTEM_ID);
        }
    }

    /** Force the light/native theme. */
    public static class Light extends SetThemeHandler {
        public Light() {
            super(Mode.LIGHT, ThemeManager.MENU_LIGHT_ID);
        }
    }

    /** Force the dark theme. */
    public static class Dark extends SetThemeHandler {
        public Dark() {
            super(Mode.DARK, ThemeManager.MENU_DARK_ID);
        }
    }
}
