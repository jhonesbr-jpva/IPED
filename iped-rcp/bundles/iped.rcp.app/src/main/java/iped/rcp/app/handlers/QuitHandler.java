package iped.rcp.app.handlers;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.workbench.IWorkbench;

/**
 * Handler of the File ▸ Exit command. Closes the workbench through the e4
 * {@link IWorkbench} service, which runs the normal shutdown sequence: the
 * application model is persisted and the lifecycle's {@code @PreSave} disposes
 * the open case session ({@link iped.rcp.app.LifeCycle}). Equivalent to closing
 * the main window, exposed as an explicit menu entry (and Ctrl+Q).
 */
public class QuitHandler {

    @Execute
    public void execute(IWorkbench workbench) {
        workbench.close();
    }
}
