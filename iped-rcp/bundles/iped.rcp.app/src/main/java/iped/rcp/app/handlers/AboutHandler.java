package iped.rcp.app.handlers;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.swt.widgets.Display;

import iped.rcp.app.about.AboutDialog;

/**
 * Handler of the Help ▸ About IPED command. Opens the {@link AboutDialog},
 * a pure SWT/JFace dialog: this is a pure e4 product, so the 3.x
 * {@code org.eclipse.ui} About / Installation Details dialogs are not on the
 * classpath and the equivalent UI is rebuilt here.
 */
public class AboutHandler {

    @Execute
    public void execute() {
        new AboutDialog(Display.getDefault().getActiveShell()).open();
    }
}
