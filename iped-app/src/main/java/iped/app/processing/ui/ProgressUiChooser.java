package iped.app.processing.ui;

/**
 * Selection of the processing progress UI (task T039, FR-026 and the
 * progress-ui-events contract):
 *
 * <ul>
 * <li>{@code --nogui} always selects the console — its output is unchanged
 * (contract criterion 3);</li>
 * <li>the standalone SWT window ({@code iped.rcp.progress}) replaces the
 * Swing {@code ProgressFrame} whenever it is deployed and a display is
 * available;</li>
 * <li>no display means automatic console fallback, never a failure
 * (criterion 4);</li>
 * <li>until the cut-over (T059) retires it, the legacy Swing frame remains
 * the default when the SWT window is not deployed.</li>
 * </ul>
 *
 * Kept as a pure function so the headless parity harness (T037) can assert
 * the decision matrix.
 */
public class ProgressUiChooser {

    public enum ProgressUi {
        SWT_WINDOW, LEGACY_FRAME, CONSOLE
    }

    private ProgressUiChooser() {
    }

    public static ProgressUi choose(boolean nogui, boolean swtWindowOpened, boolean headless) {
        if (nogui) {
            return ProgressUi.CONSOLE;
        }
        if (swtWindowOpened) {
            return ProgressUi.SWT_WINDOW;
        }
        return headless ? ProgressUi.CONSOLE : ProgressUi.LEGACY_FRAME;
    }
}
