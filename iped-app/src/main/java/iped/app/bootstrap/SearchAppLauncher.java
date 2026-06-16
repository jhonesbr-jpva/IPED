package iped.app.bootstrap;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Case-root shim for the Eclipse RCP analysis UI (feature 004, task T054;
 * case-launcher-packaging contract). This is the tiny program wrapped by
 * {@code IPED-SearchApp.exe} (launch4j) at the root of a processed case: it
 * starts the bundled RCP launcher {@code <case>/iped/ui/iped-ui.exe} on the
 * bundled JRE ({@code <case>/iped/jre/bin}), passes the case folder as the
 * application argument, then exits — the native equinox launcher hosts the UI
 * from then on. The Linux equivalent is the {@code IPED-SearchApp.sh} script.
 *
 * <p>
 * Replaces the pre-cut-over wiring in which {@code IPED-SearchApp.exe} wrapped
 * {@code lib/iped-search-app.jar} ({@code BootstrapUI}/{@code AppMain}).
 * Deliberately depends on JDK classes only: it runs from a standalone jar with
 * no class path.
 */
public class SearchAppLauncher {

    public static void main(String[] args) throws Exception {
        File caseRoot = resolveCaseRoot();
        File uiLauncher = new File(caseRoot, "iped/ui/iped-ui.exe"); //$NON-NLS-1$
        File jreBin = new File(caseRoot, "iped/jre/bin"); //$NON-NLS-1$

        List<String> cmd = new ArrayList<>();
        cmd.add(uiLauncher.getAbsolutePath());
        cmd.add("-vm"); //$NON-NLS-1$
        cmd.add(jreBin.getAbsolutePath());
        cmd.add(caseRoot.getAbsolutePath());
        // Pass through any extra arguments (e.g. -multicases <file>).
        for (String arg : args) {
            cmd.add(arg);
        }

        new ProcessBuilder(cmd)
                .directory(caseRoot)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        // Detach on purpose: the equinox launcher keeps running; this shim JVM
        // returns from main and exits immediately.
    }

    /**
     * The case root is the parent of {@code iped/} — i.e. three levels above
     * this jar at {@code <case>/iped/lib/iped-searchapp-launcher.jar}. Falls
     * back to the working directory (launch4j {@code chdir=.} keeps it at the
     * case root) if the jar location cannot be resolved.
     */
    private static File resolveCaseRoot() {
        try {
            File jar = new File(SearchAppLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File root = jar.getParentFile().getParentFile().getParentFile();
            if (root != null && root.isDirectory()) {
                return root.getAbsoluteFile();
            }
        } catch (Exception e) {
            // fall through to the working directory
        }
        return new File(System.getProperty("user.dir")).getAbsoluteFile(); //$NON-NLS-1$
    }
}
