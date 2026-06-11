package iped.rcp.viewers.bridge;

import java.awt.BorderLayout;
import java.awt.Frame;
import java.awt.Panel;

import javax.swing.JComponent;
import javax.swing.JRootPane;

import org.eclipse.swt.SWT;
import org.eclipse.swt.awt.SWT_AWT;
import org.eclipse.swt.widgets.Composite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single SWT_AWT bridge per part (task T010, research R4): hosts the legacy
 * Swing/JavaFX content viewers inside an SWT composite without rewriting
 * them (FR-028).
 *
 * <p>
 * Known SWT_AWT risks and the mitigations applied here:
 * <ul>
 * <li><b>one embedded composite per part</b>, created once and never
 * reparented — reparenting embedded frames is the main source of z-order
 * glitches on GTK;</li>
 * <li><b>heavyweight root</b>: the classic {@code Frame > Panel > JRootPane}
 * sandwich, which avoids repaint and focus anomalies of lightweight-only
 * hierarchies inside embedded frames;</li>
 * <li><b>focus forwarding</b>: SWT focus events are forwarded to the AWT
 * side, where GTK does not always deliver them on its own;</li>
 * <li><b>XEmbed</b>: {@code sun.awt.xembedserver} is enabled before the
 * first frame on GTK (also set in the product launcher ini — T011 — in case
 * AWT initializes earlier elsewhere).</li>
 * </ul>
 *
 * <p>
 * Threading: the constructor must run on the SWT UI thread. Swing content is
 * always touched on the EDT internally; callers pass components and this
 * class marshals (constitution principle V).
 */
public final class SwtAwtBridgeHost {

    private static final Logger LOGGER = LoggerFactory.getLogger(SwtAwtBridgeHost.class);

    static {
        if ("gtk".equals(SWT.getPlatform())) {
            System.setProperty("sun.awt.xembedserver", "true");
        }
    }

    private final Composite embedded;
    private volatile Frame frame;
    private volatile JRootPane rootPane;

    /**
     * Creates the bridge under the given parent. Must be called on the SWT
     * UI thread; the Swing side is initialized asynchronously on the EDT.
     */
    public SwtAwtBridgeHost(Composite parent) {
        embedded = new Composite(parent, SWT.EMBEDDED | SWT.NO_BACKGROUND);
        Frame newFrame = SWT_AWT.new_Frame(embedded);
        frame = newFrame;
        UiThreads.onEdtAsync(() -> {
            Panel heavyweightRoot = new Panel(new BorderLayout());
            JRootPane pane = new JRootPane();
            heavyweightRoot.add(pane, BorderLayout.CENTER);
            newFrame.add(heavyweightRoot);
            rootPane = pane;
        });
        embedded.addListener(SWT.FocusIn, event -> forwardFocusToAwt());
        embedded.addListener(SWT.Dispose, event -> disposeAwt());
    }

    /**
     * Sets (replacing any previous one) the Swing content of this host. Safe
     * from any thread.
     */
    public void setContent(JComponent content) {
        UiThreads.onEdtAsync(() -> {
            JRootPane pane = rootPane;
            if (pane == null || frame == null) {
                return; // disposed before the EDT got to it
            }
            pane.getContentPane().removeAll();
            pane.getContentPane().add(content, BorderLayout.CENTER);
            pane.revalidate();
            pane.repaint();
        });
    }

    /** The SWT side of the bridge, for layouting inside the part. */
    public Composite getComposite() {
        return embedded;
    }

    /**
     * The embedded AWT frame — EDT use only, for viewers that need the
     * heavyweight ancestor (e.g. LibreOffice/NOA native windows).
     */
    public Frame getFrame() {
        return frame;
    }

    /** The Swing root pane — EDT use only. Null until the EDT init ran. */
    public JRootPane getRootPane() {
        return rootPane;
    }

    private void forwardFocusToAwt() {
        Frame current = frame;
        if (current == null) {
            return;
        }
        UiThreads.onEdtAsync(() -> {
            JRootPane pane = rootPane;
            if (pane != null && !pane.hasFocus()) {
                current.requestFocus();
                pane.requestFocusInWindow();
            }
        });
    }

    private void disposeAwt() {
        Frame current = frame;
        frame = null;
        rootPane = null;
        if (current != null) {
            // dispose on the EDT after pending events, avoiding X errors on
            // GTK when the embedded window dies mid-event
            UiThreads.onEdtAsync(() -> {
                try {
                    current.dispose();
                } catch (RuntimeException e) {
                    LOGGER.warn("Error disposing embedded AWT frame", e);
                }
            });
        }
    }
}
