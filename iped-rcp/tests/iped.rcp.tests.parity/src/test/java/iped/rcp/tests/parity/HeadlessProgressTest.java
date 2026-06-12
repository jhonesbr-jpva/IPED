package iped.rcp.tests.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import iped.app.processing.ui.ProgressConsole;
import iped.app.processing.ui.ProgressUiChooser;
import iped.app.processing.ui.ProgressUiChooser.ProgressUi;
import iped.engine.core.Worker;
import iped.engine.util.UIPropertyListenerProvider;
import iped.rcp.progress.ProgressWindow;

/**
 * T037 (US4) — headless fallback contract of the processing progress UI
 * (contracts/progress-ui-events.contract.md, FR-026):
 *
 * <ul>
 * <li>without a display the SWT progress window must NOT open — processing
 * falls back to {@link ProgressConsole} and completes (acceptance criterion 4
 * of the contract);</li>
 * <li>{@code --nogui} always selects the console, regardless of display
 * availability (criterion 3 — console output itself is unchanged);</li>
 * <li>the console consumes the full event set published by
 * {@link UIPropertyListenerProvider} without breaking the processing
 * thread.</li>
 * </ul>
 *
 * <p>
 * A real no-display environment cannot be simulated on a Windows desktop
 * (SWT always finds the interactive window station), so the deterministic
 * leg uses the {@link ProgressWindow#FORCE_HEADLESS_PROP} test hook; the
 * true no-X path is exercised by the Linux CI leg, where
 * {@code GraphicsEnvironment.isHeadless()} is naturally true.
 */
class HeadlessProgressTest {

    @AfterEach
    void clearForcedHeadless() {
        System.clearProperty(ProgressWindow.FORCE_HEADLESS_PROP);
    }

    @Test
    void windowDoesNotOpenWithoutDisplay() {
        System.setProperty(ProgressWindow.FORCE_HEADLESS_PROP, "true");
        UIPropertyListenerProvider provider = UIPropertyListenerProvider.getInstance();
        ProgressWindow window = ProgressWindow.open(provider);
        assertNull(window, "ProgressWindow must not open in a headless environment");
    }

    @Test
    void chooserMatrixMatchesContract() {
        // --nogui always wins (criterion 3), even with a display available
        assertEquals(ProgressUi.CONSOLE, ProgressUiChooser.choose(true, true, false));
        assertEquals(ProgressUi.CONSOLE, ProgressUiChooser.choose(true, false, true));
        // SWT window opened -> it is the progress UI (FR-026)
        assertEquals(ProgressUi.SWT_WINDOW, ProgressUiChooser.choose(false, true, false));
        // no SWT window + headless -> automatic console fallback (criterion 4)
        assertEquals(ProgressUi.CONSOLE, ProgressUiChooser.choose(false, false, true));
        // no SWT window + display present -> legacy Swing frame (until the
        // cut-over T059 retires it)
        assertEquals(ProgressUi.LEGACY_FRAME, ProgressUiChooser.choose(false, false, false));
    }

    @Test
    void consoleConsumesEventStormAndProcessingCompletes() {
        UIPropertyListenerProvider provider = UIPropertyListenerProvider.getInstance();
        ProgressConsole console = new ProgressConsole();
        provider.addPropertyChangeListener(console, false);

        // synthetic processing: the exact property set fired by the engine
        // (Manager/Statistics) towards progress consumers
        provider.firePropertyChange("mensagem", "", "Synthetic processing started");
        provider.firePropertyChange("workers", null, new Worker[0]);
        provider.firePropertyChange("decodingDir", null, "evidence1");
        for (int i = 0; i < 5; i++) {
            provider.firePropertyChange("update", null, null);
        }
        provider.firePropertyChange("discoverEnded", null, null);
        provider.firePropertyChange("update", null, null);
        provider.firePropertyChange("mensagem", "", "Synthetic processing finished");

        assertFalse(provider.isCancelled(), "event consumption must not cancel the processing");
    }

    @Test
    void windowOpensAndConsumesEventsWhenDisplayAvailable() {
        UIPropertyListenerProvider provider = UIPropertyListenerProvider.getInstance();
        ProgressWindow window = ProgressWindow.open(provider);
        // headless CI: open() correctly refuses, nothing else to assert here
        assumeTrue(window != null, "no display available, window leg skipped");
        try {
            provider.firePropertyChange("workers", null, new Worker[0]);
            provider.firePropertyChange("mensagem", "", "Probe message");
            provider.firePropertyChange("update", null, null);
            // updates are marshaled via Display.asyncExec; probe runs syncExec
            assertEquals("Probe message", window.probeMessageText());
            assertFalse(provider.isCancelled());
        } finally {
            window.close();
        }
    }
}
