package iped.rcp.app;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.lifecycle.PostContextCreate;
import org.eclipse.e4.ui.workbench.lifecycle.PreSave;
import org.eclipse.e4.ui.workbench.lifecycle.ProcessAdditions;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.Version;
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
        List<Path> casePaths = parseCaseArgs(appContext);

        if (casePaths.isEmpty()) {
            Path chosen = askCaseFolder(display);
            if (chosen == null) {
                LOGGER.info("No case selected, exiting");
                System.exit(0);
            }
            casePaths = List.of(chosen);
        }

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

    @ProcessAdditions
    void processAdditions(MApplication application) {
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
                casePaths.add(new File(arg).getAbsoluteFile().toPath());
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
