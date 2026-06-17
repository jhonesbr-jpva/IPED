package iped.rcp.core.processing;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Handle to a processing job launched out-of-process by
 * {@link ProcessingLaunchService} (feature 005, data-model §2). The UI uses it
 * to offer the near-live open of {@link #outputCaseDir()} (research R7) and to
 * reflect the outcome; it never commands the pipeline — only observes the
 * spawned {@code Bootstrap} process.
 */
public final class ProcessingJobHandle {

    /** Lifecycle derived from the subprocess exit code (0 = SUCCEEDED). */
    public enum State {
        STARTING, RUNNING, SUCCEEDED, FAILED, CANCELED
    }

    private final Path outputDir;
    private final Path outputCaseDir;
    private final List<String> commandLine;
    private final Process process;
    private volatile State state = State.RUNNING;
    private volatile boolean canceled;

    ProcessingJobHandle(Path outputDir, Path outputCaseDir, List<String> commandLine, Process process) {
        this.outputDir = outputDir;
        this.outputCaseDir = outputCaseDir;
        this.commandLine = List.copyOf(commandLine);
        this.process = process;
        process.onExit().whenComplete((p, t) -> updateTerminalState());
    }

    private void updateTerminalState() {
        if (canceled) {
            state = State.CANCELED;
        } else {
            state = process.exitValue() == 0 ? State.SUCCEEDED : State.FAILED;
        }
    }

    /** @return the output folder passed to processing ({@code -o}). */
    public Path outputDir() {
        return outputDir;
    }

    /** @return the case folder being created/updated ({@code outputDir/iped}) — near-live target. */
    public Path outputCaseDir() {
        return outputCaseDir;
    }

    /** @return the full command line (java -jar iped.jar …), for logging/audit. */
    public List<String> commandLine() {
        return commandLine;
    }

    /** @return the current lifecycle state. */
    public State state() {
        return state;
    }

    /** @return true while the subprocess is still running. */
    public boolean isAlive() {
        return process.isAlive();
    }

    /** @return a future completing when the job terminates (with the final state). */
    public CompletableFuture<State> onExit() {
        return process.onExit().thenApply(p -> state);
    }

    /** Requests cancellation: destroys the subprocess (best-effort) and marks it CANCELED. */
    public void cancel() {
        canceled = true;
        state = State.CANCELED;
        process.destroy();
    }
}
