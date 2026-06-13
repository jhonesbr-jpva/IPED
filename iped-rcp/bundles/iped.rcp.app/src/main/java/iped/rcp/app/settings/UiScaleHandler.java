package iped.rcp.app.settings;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import iped.app.ui.utils.UiScale;
import iped.rcp.core.i18n.Messages;

/**
 * "UI Scale..." menu handler (task T045, FR-019): parity of the legacy
 * {@code MenuListener} dialog over the same {@code ~/.iped/UiScale.txt}
 * setting (read at the next start by {@link iped.rcp.app.startup.EarlyStartup}
 * for SWT and by the legacy {@code UiScale} for the bridged AWT viewers).
 * Reuses the legacy {@code UiScale} reader/writer so the file format stays
 * identical for both UIs.
 */
public class UiScaleHandler {

    @Execute
    public void execute() {
        Display display = Display.getDefault();
        Shell shell = display.getActiveShell();
        String current = UiScale.loadUserSetting();
        // same text/placeholder contract as the legacy dialog (i18n key reused)
        String message = Messages.getString("MenuListener.UiScaleDialog").replace("{}", UiScale.AUTO);

        IInputValidator validator = value -> {
            if (UiScale.AUTO.equalsIgnoreCase(value.trim())) {
                return null;
            }
            try {
                return Double.parseDouble(value.trim()) > 0 ? null : message;
            } catch (NumberFormatException e) {
                return message;
            }
        };
        InputDialog dialog = new InputDialog(shell, Messages.getString("RcpMenu.UiScale"), message, current,
                validator);
        if (dialog.open() == Window.OK) {
            String value = dialog.getValue().trim();
            UiScale.saveUserSetting(UiScale.AUTO.equalsIgnoreCase(value) ? UiScale.AUTO : value);
        }
    }
}
