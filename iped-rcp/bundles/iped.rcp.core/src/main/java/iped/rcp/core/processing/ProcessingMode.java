package iped.rcp.core.processing;

/**
 * Processing mode of a case-creation request (feature 005, FR-009).
 *
 * <p>
 * Maps to the mutually exclusive Bootstrap flags: {@link #NEW} adds no flag,
 * {@link #APPEND} → {@code --append}, {@link #CONTINUE} → {@code --continue},
 * {@link #RESTART} → {@code --restart}. {@code APPEND}/{@code CONTINUE}/
 * {@code RESTART} require an existing case in {@code outputDir/iped}.
 */
public enum ProcessingMode {

    /** Create a new case (no mode flag). */
    NEW,

    /** Add evidence to an existing case ({@code --append}). */
    APPEND,

    /** Continue a stopped/aborted processing ({@code --continue}). */
    CONTINUE,

    /** Discard the last aborted processing and start over ({@code --restart}). */
    RESTART;

    /** @return true when the mode operates on a pre-existing case folder. */
    public boolean requiresExistingCase() {
        return this != NEW;
    }
}
