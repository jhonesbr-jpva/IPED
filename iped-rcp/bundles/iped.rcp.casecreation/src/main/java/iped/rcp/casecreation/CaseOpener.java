package iped.rcp.casecreation;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;

import iped.rcp.core.i18n.Messages;
import iped.rcp.core.session.CaseOpenException;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.core.session.SessionState;

/**
 * Opens a case at runtime off the UI thread (feature 005, US2). Closes any open
 * session first, then opens the new one via {@link ICaseSessionManager}; the
 * parts refresh through the {@code case/OPENED} event they already subscribe
 * to. {@code open()} is blocking (index load), so it always runs in an e4 Job.
 * Near-live mode is enabled automatically by the session service when the case
 * is still being processed (FR-029).
 */
public final class CaseOpener {

    private CaseOpener() {
    }

    /** Schedules a Job that (re)opens {@code casePaths}; errors surface as a dialog. */
    public static void open(ICaseSessionManager sessionManager, List<Path> casePaths) {
        Job job = Job.create(Messages.getString("OpenCase.job"), monitor -> {
            try {
                if (sessionManager.getState() != SessionState.CLOSED) {
                    sessionManager.close();
                }
                sessionManager.open(casePaths);
            } catch (CaseOpenException e) {
                Display.getDefault().asyncExec(() -> MessageDialog.openError(Display.getDefault().getActiveShell(),
                        Messages.getString("OpenCase.error.title"), e.getMessage()));
            }
        });
        job.setUser(true);
        job.schedule();
    }
}
