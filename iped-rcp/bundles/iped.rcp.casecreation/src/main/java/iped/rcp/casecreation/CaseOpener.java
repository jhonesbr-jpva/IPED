package iped.rcp.casecreation;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import iped.rcp.core.i18n.Messages;
import iped.rcp.core.session.CaseOpenException;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.core.session.SessionState;

/**
 * Opens a case at runtime off the UI thread (feature 005, US2). Closes any open
 * session first, then opens the new one via {@link ICaseSessionManager}; the
 * parts refresh through the {@code case/OPENED} event they already subscribe
 * to. {@code open()} is blocking (index load), so it always runs in a forked
 * thread while a modal {@link ProgressMonitorDialog} shows an indeterminate
 * gauge — in the pure-e4 product a bare {@code Job} never surfaces its progress,
 * so a large-case open would otherwise look like nothing happened. Near-live
 * mode is enabled automatically by the session service when the case is still
 * being processed (FR-029).
 */
public final class CaseOpener {

    private CaseOpener() {
    }

    /**
     * Opens {@code casePaths} behind a modal progress gauge. Must be called from
     * the UI thread (command handlers and wizard {@code performFinish} both are);
     * the blocking index load runs in the dialog's forked worker thread and
     * errors surface as a dialog once it returns.
     */
    public static void open(ICaseSessionManager sessionManager, List<Path> casePaths) {
        Shell shell = Display.getDefault().getActiveShell();
        IRunnableWithProgress runnable = monitor -> {
            // open() reports no granular progress, so show an animated (indeterminate) bar.
            monitor.beginTask(Messages.getString("OpenCase.job"), IProgressMonitor.UNKNOWN);
            try {
                if (sessionManager.getState() != SessionState.CLOSED) {
                    sessionManager.close();
                }
                sessionManager.open(casePaths);
            } catch (CaseOpenException e) {
                throw new InvocationTargetException(e);
            } finally {
                monitor.done();
            }
        };
        try {
            new ProgressMonitorDialog(shell).run(true, false, runnable);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            MessageDialog.openError(shell, Messages.getString("OpenCase.error.title"), cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
