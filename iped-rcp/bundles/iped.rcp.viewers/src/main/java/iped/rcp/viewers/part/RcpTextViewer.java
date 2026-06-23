package iped.rcp.viewers.part;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItem;
import iped.engine.data.IPEDSource;
import iped.io.IStreamSource;
import iped.rcp.core.i18n.Messages;
import iped.viewers.api.AbstractViewer;

/**
 * Lightweight extracted-text viewer for the RCP content area. Reproduces the
 * purpose of the legacy {@code iped.app.ui.viewers.TextViewer} (the "Texto"
 * tab) — showing the parsed text of any item, e.g. plain text files such as
 * {@code .bashrc} — but without the Swing {@code App} singleton coupling that
 * keeps the legacy {@code TextParser} from running outside the old UI. That
 * coupling is the reason the text viewer was deferred in the RCP migration (see
 * {@link TextViewerPart} javadoc).
 *
 * <p>
 * Text is extracted with {@link TextExtraction} (the engine parsing pipeline) on
 * a background thread, capped at {@link TextExtraction#MAX_CHARS}, and rendered
 * read-only. Query terms are highlighted in-place via {@link TextHitFinder} and
 * {@link #scrollToNextHit(boolean)} cycles between occurrences (VW-01); the
 * separate {@link HitsViewerPart} drives {@link #scrollToOffset(int)} to jump to
 * a clicked occurrence — both use the shared finder so offsets are identical.
 */
public class RcpTextViewer extends AbstractViewer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RcpTextViewer.class);

    private final JTextArea textArea;
    private final AtomicLong loadStamp = new AtomicLong();
    /** Stamp whose text has finished loading into the area (EDT-confined). */
    private long loadedStamp = -1;
    /** A goto offset received while a load was still in progress (EDT-confined). */
    private int pendingOffset = -1;

    private final Highlighter.HighlightPainter allPainter = new DefaultHighlighter.DefaultHighlightPainter(
            new Color(255, 255, 80));
    private final Highlighter.HighlightPainter currentPainter = new DefaultHighlighter.DefaultHighlightPainter(
            new Color(255, 160, 40));
    /** Match ranges {start,end} and their highlighter tags (parallel lists). */
    private final List<int[]> matches = new ArrayList<>();
    private final List<Object> highlightTags = new ArrayList<>();
    private int currentMatch = -1;
    private volatile Set<String> highlightTerms = Collections.emptySet();

    public RcpTextViewer() {
        super(new BorderLayout());
        textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setLineWrap(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(textArea, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        getPanel().add(scroll, BorderLayout.CENTER);
    }

    @Override
    public String getName() {
        return Messages.getString("ContentViewer.TabText"); //$NON-NLS-1$
    }

    @Override
    public boolean isSupportedType(String contentType) {
        return true;
    }

    @Override
    public void init() {
        // nothing heavy to initialize
    }

    @Override
    public void dispose() {
        loadStamp.incrementAndGet();
    }

    @Override
    public void loadFile(IStreamSource content, Set<String> highlightTerms) {
        // Content is driven through loadItem(IItem, IPEDSource, Set); the standard
        // viewer contract is honored by clearing when there is no selection.
        if (content == null) {
            clear();
        }
    }

    @Override
    public void scrollToNextHit(boolean forward) {
        SwingUtilities.invokeLater(() -> {
            if (matches.isEmpty()) {
                return;
            }
            int previous = currentMatch;
            if (forward) {
                currentMatch = (currentMatch + 1) % matches.size();
            } else {
                currentMatch = (currentMatch - 1 + matches.size()) % matches.size();
            }
            if (previous != currentMatch) {
                swapPainter(previous, allPainter);
            }
            swapPainter(currentMatch, currentPainter);
            revealCurrent();
        });
    }

    /**
     * Jumps to the occurrence at character {@code offset} (the hits-list
     * navigation, {@link HitsViewerPart}). If the text for the current selection
     * is still being extracted, the jump is deferred until it loads.
     */
    public void scrollToOffset(int offset) {
        SwingUtilities.invokeLater(() -> {
            if (offset < 0) {
                return;
            }
            if (loadedStamp == loadStamp.get()) {
                applyOffset(offset); // text is loaded and current
                pendingOffset = -1;
            } else {
                pendingOffset = offset; // load in progress — apply once it finishes
            }
        });
    }

    /** Clears the text area and any highlights (no active item). */
    public void clear() {
        long stamp = loadStamp.incrementAndGet();
        highlightTerms = Collections.emptySet();
        SwingUtilities.invokeLater(() -> {
            if (stamp == loadStamp.get()) {
                pendingOffset = -1;
                setTextInternal(""); //$NON-NLS-1$
                applyHighlights(""); //$NON-NLS-1$
                loadedStamp = stamp;
            }
        });
    }

    /**
     * Loads the parsed text of {@code item} into the viewer and highlights the
     * given query terms. Extraction runs off both UI threads; stale loads
     * (superseded by a newer selection) are discarded.
     *
     * @param item   the resolved engine item (must not be {@code null})
     * @param source the atomic source backing the item, for the Tika context
     * @param terms  query terms to highlight (may be {@code null}/empty)
     */
    public void loadItem(IItem item, IPEDSource source, Set<String> terms) {
        this.highlightTerms = terms == null ? Collections.emptySet() : terms;
        if (item == null) {
            clear();
            return;
        }
        long stamp = loadStamp.incrementAndGet();
        Thread worker = new Thread(() -> {
            String text;
            try {
                text = TextExtraction.extract(item, source, TextExtraction.MAX_CHARS);
            } catch (Throwable e) {
                LOGGER.warn("Error extracting text of item {} for the content viewer", item.getId(), e);
                text = ""; //$NON-NLS-1$
            }
            if (stamp != loadStamp.get()) {
                return; // a newer selection won
            }
            final String result = text;
            SwingUtilities.invokeLater(() -> {
                if (stamp != loadStamp.get()) {
                    return;
                }
                setTextInternal(result);
                applyHighlights(result);
                loadedStamp = stamp;
                if (pendingOffset >= 0) {
                    applyOffset(pendingOffset); // a hit was clicked while loading
                    pendingOffset = -1;
                }
            });
        }, "rcp-text-extract"); //$NON-NLS-1$
        worker.setDaemon(true);
        worker.start();
    }

    private void setTextInternal(String text) {
        textArea.setText(text);
        textArea.setCaretPosition(0);
    }

    /**
     * Recomputes the highlights for {@code text} against {@link #highlightTerms}
     * (EDT). The first occurrence becomes the current hit and is revealed.
     */
    private void applyHighlights(String text) {
        Highlighter h = textArea.getHighlighter();
        h.removeAllHighlights();
        matches.clear();
        highlightTags.clear();
        currentMatch = -1;
        matches.addAll(TextHitFinder.findMatches(text, highlightTerms, TextHitFinder.MAX_MATCHES,
                TextHitFinder.MIN_TERM_LEN));
        for (int[] m : matches) {
            try {
                highlightTags.add(h.addHighlight(m[0], m[1], allPainter));
            } catch (BadLocationException e) {
                highlightTags.add(null); // keep tags aligned with matches
            }
        }
        if (!matches.isEmpty()) {
            currentMatch = 0;
            swapPainter(0, currentPainter);
            revealCurrent();
        }
    }

    /** Selects the match starting at {@code offset} (or scrolls there). EDT. */
    private void applyOffset(int offset) {
        int idx = indexOfMatchAt(offset);
        if (idx >= 0) {
            if (currentMatch != idx) {
                if (currentMatch >= 0) {
                    swapPainter(currentMatch, allPainter);
                }
                currentMatch = idx;
                swapPainter(currentMatch, currentPainter);
            }
            revealCurrent();
            return;
        }
        int len = textArea.getDocument().getLength();
        int pos = Math.max(0, Math.min(offset, len));
        try {
            textArea.setCaretPosition(pos);
            Rectangle2D r = textArea.modelToView2D(pos);
            if (r != null) {
                textArea.scrollRectToVisible(r.getBounds());
            }
        } catch (BadLocationException e) {
            // offset no longer valid: ignore
        }
    }

    private int indexOfMatchAt(int offset) {
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i)[0] == offset) {
                return i;
            }
        }
        return -1;
    }

    /** Replaces the highlight of one match with a different painter (EDT). */
    private void swapPainter(int index, Highlighter.HighlightPainter painter) {
        if (index < 0 || index >= matches.size() || index >= highlightTags.size()) {
            return;
        }
        Highlighter h = textArea.getHighlighter();
        Object tag = highlightTags.get(index);
        if (tag != null) {
            h.removeHighlight(tag);
        }
        int[] m = matches.get(index);
        try {
            highlightTags.set(index, h.addHighlight(m[0], m[1], painter));
        } catch (BadLocationException e) {
            highlightTags.set(index, null);
        }
    }

    /** Scrolls the current match into view and parks the caret on it (EDT). */
    private void revealCurrent() {
        if (currentMatch < 0 || currentMatch >= matches.size()) {
            return;
        }
        int[] m = matches.get(currentMatch);
        try {
            textArea.setCaretPosition(m[0]);
            Rectangle2D r = textArea.modelToView2D(m[0]);
            if (r != null) {
                textArea.scrollRectToVisible(r.getBounds());
            }
        } catch (BadLocationException e) {
            // the match offset is no longer valid (document changed): ignore
        }
    }
}
