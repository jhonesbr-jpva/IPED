package iped.rcp.casecreation.handlers;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;

import iped.rcp.casecreation.CaseOpener;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.session.ICaseSessionManager;

/**
 * Handler of the File ▸ Open Case command (feature 005, US2, T019). Picks a
 * processed case folder with a native dialog and opens it via
 * {@link CaseOpener} (off the UI thread). The injected
 * {@link ICaseSessionManager} is the same DS service the lifecycle uses.
 */
public class OpenCaseHandler {

    @Execute
    public void execute(ICaseSessionManager sessionManager) {
        DirectoryDialog dialog = new DirectoryDialog(Display.getDefault().getActiveShell(), SWT.OPEN);
        dialog.setText(Messages.getString("OpenCase.dialog.title"));
        String dir = dialog.open();
        if (dir != null) {
            CaseOpener.open(sessionManager, List.of(Path.of(dir)));
        }
    }
}
