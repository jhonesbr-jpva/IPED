package iped.rcp.core.processing;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches a processing job out-of-process by spawning the same
 * {@code Bootstrap}/{@code iped.jar} the CLI uses (feature 005, research R1,
 * contracts/processing-launch.contract.md). The RCP (Equinox) process only
 * builds the command, starts the subprocess and observes its lifecycle —
 * {@code Bootstrap} owns classpath assembly, the child JVM, the JVM module
 * opens and the SWT progress window (feature 004). This preserves process
 * isolation (Princípio V) with zero engine changes (FR-028).
 *
 * <p>
 * Registered as an OSGi Declarative Service so e4 handlers can inject it.
 * Toolkit-free; the install-layout resolution and command assembly are
 * package-visible static methods so they can be unit-tested without spawning a
 * real process.
 */
@Component(service = ProcessingLaunchService.class)
public class ProcessingLaunchService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingLaunchService.class);

    /** Explicit install-root override (release root containing {@code iped.jar}); tests/dev. */
    public static final String INSTALL_DIR_PROP = "iped.install.dir";

    private static final String OSGI_INSTALL_AREA_PROP = "osgi.install.area";
    private static final String IPED_JAR = "iped.jar";

    /** Output folders with an active job — the FR-024 same-output conflict guard. */
    private final Set<Path> activeOutputs = ConcurrentHashMap.newKeySet();

    /**
     * Validates {@code request}, resolves the install layout and spawns the
     * processing subprocess.
     *
     * @return a handle to the running job (state {@code RUNNING})
     * @throws LaunchException if the request is invalid, the layout cannot be
     *             resolved, an output conflict exists (FR-024), or the OS fails
     *             to start the process
     */
    public ProcessingJobHandle launch(NewCaseRequest request) throws LaunchException {
        List<String> errors = BootstrapCommandBuilder.validate(request);
        if (!errors.isEmpty()) {
            throw new LaunchException("Invalid case-creation request: " + String.join("; ", errors));
        }

        Path root = resolveInstallRoot();
        if (root == null) {
            throw new LaunchException("Could not resolve the IPED install folder (no " + INSTALL_DIR_PROP
                    + ", no " + OSGI_INSTALL_AREA_PROP + ", and " + IPED_JAR + " not found).");
        }
        Path ipedJar = root.resolve(IPED_JAR);
        if (!Files.isRegularFile(ipedJar)) {
            throw new LaunchException(IPED_JAR + " not found in install folder: " + root);
        }
        Path javaExe = resolveJava(root);

        Path outputKey = request.outputDir().toAbsolutePath().normalize();
        if (!activeOutputs.add(outputKey)) {
            throw new LaunchException("A processing job is already running for this output folder: " + outputKey);
        }

        List<String> command = buildFullCommand(javaExe, ipedJar, request);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(root.toFile());
            // Bootstrap manages its own logging and progress window; drop the
            // parent-side streams so the launcher never blocks on a full pipe.
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            LOGGER.info("Launching processing for {} ({} args)", outputKey, command.size());
            Process process = pb.start();
            ProcessingJobHandle handle = new ProcessingJobHandle(request.outputDir(), request.caseDir(), command,
                    process);
            process.onExit().whenComplete((p, t) -> activeOutputs.remove(outputKey));
            return handle;
        } catch (IOException e) {
            activeOutputs.remove(outputKey);
            throw new LaunchException("Failed to start the processing subprocess: " + e.getMessage(), e);
        }
    }

    /** Best-effort cancellation of a running job. */
    public void cancel(ProcessingJobHandle handle) {
        if (handle != null) {
            handle.cancel();
        }
    }

    /**
     * @return the release {@code profiles/} folder (built-in + user profiles,
     *         where {@code -profile <name>} resolves; research R3), or
     *         {@code null} when the install root cannot be resolved
     */
    public Path profilesDir() {
        Path root = resolveInstallRoot();
        return root == null ? null : root.resolve("profiles");
    }

    /**
     * Assembles the full command line: {@code <java> -jar <iped.jar> <args>}
     * (the {@code <args>} from {@link BootstrapCommandBuilder}). Pure — testable.
     */
    static List<String> buildFullCommand(Path javaExe, Path ipedJar, NewCaseRequest request) {
        List<String> command = new ArrayList<>();
        command.add(javaExe.toString());
        // Required on Java 21+: the engine installs a SecurityManager (see
        // Configuration.loadConfigurables) to sandbox the HTML viewers, and
        // System.setSecurityManager throws UnsupportedOperationException without
        // this flag. iped.bat / iped.exe pass it to the Bootstrap JVM, which
        // forwards it to the forked processing JVM (Bootstrap.getCurrentJVMArgs);
        // we bypass those launchers (java -jar iped.jar), so we must pass it.
        command.add("-Djava.security.manager=allow");
        command.add("-jar");
        command.add(ipedJar.toString());
        command.addAll(BootstrapCommandBuilder.buildArgs(request));
        return command;
    }

    /**
     * Resolves the release root (the folder containing {@code iped.jar}) from,
     * in order: the {@link #INSTALL_DIR_PROP} override, then the Equinox
     * {@code osgi.install.area} (product at {@code <root>/ui/}, so the root is
     * the area's parent or the area itself — whichever holds {@code iped.jar}).
     *
     * @return the install root, or {@code null} when it cannot be resolved
     */
    static Path resolveInstallRoot() {
        String override = System.getProperty(INSTALL_DIR_PROP);
        if (override != null && !override.isBlank()) {
            Path dir = Path.of(override);
            return Files.isDirectory(dir) ? dir : null;
        }
        String area = System.getProperty(OSGI_INSTALL_AREA_PROP);
        File areaDir = area == null ? null : areaUrlToFile(area);
        if (areaDir != null) {
            // Product lives at <root>/ui/ — check the parent first, then the area.
            for (File candidate : new File[] { areaDir.getParentFile(), areaDir }) {
                if (candidate != null && new File(candidate, IPED_JAR).isFile()) {
                    return candidate.toPath();
                }
            }
        }
        return null;
    }

    /**
     * Resolves the java launcher: the bundled JRE ({@code <root>/jre/bin/java}),
     * falling back to the JVM running the UI ({@code java.home}).
     */
    static Path resolveJava(Path root) {
        String exe = isWindows() ? "java.exe" : "java";
        Path bundled = root.resolve("jre").resolve("bin").resolve(exe);
        if (Files.isRegularFile(bundled)) {
            return bundled;
        }
        return Path.of(System.getProperty("java.home"), "bin", exe);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Converts an Equinox area URL ({@code osgi.install.area}) to a directory.
     * Mirrors {@code iped.rcp.core.i18n.Messages#areaUrlToFile} — Equinox does
     * not URL-encode these values.
     */
    private static File areaUrlToFile(String areaUrl) {
        String path = areaUrl.trim();
        if (path.startsWith("file:")) {
            path = path.substring("file:".length());
        }
        if (path.length() > 2 && path.charAt(0) == '/' && path.charAt(2) == ':') {
            path = path.substring(1);
        }
        File file = new File(path);
        return file.isDirectory() ? file : null;
    }
}
