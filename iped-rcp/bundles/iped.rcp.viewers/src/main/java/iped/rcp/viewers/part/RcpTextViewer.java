package iped.rcp.viewers.part;

import java.awt.BorderLayout;
import java.awt.Font;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItem;
import iped.engine.config.ConfigurationManager;
import iped.engine.data.IPEDSource;
import iped.engine.io.ParsingReader;
import iped.engine.task.ParsingTask;
import iped.io.IStreamSource;
import iped.parsers.standard.StandardParser;
import iped.parsers.util.MetadataUtil;
import iped.rcp.core.i18n.Messages;
import iped.viewers.api.AbstractViewer;

/**
 * Lightweight extracted-text viewer for the RCP content area. Reproduces the
 * purpose of the legacy {@code iped.app.ui.viewers.TextViewer} (the "Texto"
 * tab) — showing the parsed text of any item, e.g. plain text files such as
 * {@code .bashrc} — but without the Swing {@code App} singleton coupling that
 * keeps the legacy {@code TextParser} from running outside the old UI
 * ({@code App.get().getTextViewer()}, {@code getAutoParser()}, {@code hitsDock}
 * …). That coupling is the reason the text viewer was deferred in the RCP
 * migration (see {@link TextViewerPart} javadoc).
 *
 * <p>
 * Text is extracted with the engine's {@link StandardParser} +
 * {@link ParsingReader} (same pipeline the legacy viewer uses) on a background
 * thread, capped at {@link #MAX_CHARS}, and rendered in a read-only monospace
 * {@link JTextArea}. In-viewer hit highlighting/navigation is intentionally out
 * of scope here (a separate parity item) — {@link #scrollToNextHit(boolean)} is
 * a no-op.
 */
public class RcpTextViewer extends AbstractViewer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RcpTextViewer.class);

    /** Upper bound of extracted characters kept in memory / shown. */
    private static final int MAX_CHARS = 2_000_000;

    private final JTextArea textArea;
    private final AtomicLong loadStamp = new AtomicLong();

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
        // Content is driven through loadItem(IItem, IPEDSource); the standard
        // viewer contract is honored by clearing when there is no selection.
        if (content == null) {
            clear();
        }
    }

    @Override
    public void scrollToNextHit(boolean forward) {
        // in-text hit navigation not supported by this viewer (see class javadoc)
    }

    /** Clears the text area (no active item). */
    public void clear() {
        long stamp = loadStamp.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            if (stamp == loadStamp.get()) {
                setTextInternal(""); //$NON-NLS-1$
            }
        });
    }

    /**
     * Loads the parsed text of {@code item} into the viewer. Extraction runs
     * off both UI threads; stale loads (superseded by a newer selection) are
     * discarded.
     *
     * @param item   the resolved engine item (must not be {@code null})
     * @param source the atomic source backing the item, used to build the Tika
     *               parsing context (recursive searcher); must not be
     *               {@code null}
     */
    public void loadItem(IItem item, IPEDSource source) {
        if (item == null) {
            clear();
            return;
        }
        long stamp = loadStamp.incrementAndGet();
        Thread worker = new Thread(() -> {
            String text;
            try {
                text = extractText(item, source);
            } catch (Throwable e) {
                LOGGER.warn("Error extracting text of item {} for the content viewer", item.getId(), e);
                text = ""; //$NON-NLS-1$
            }
            if (stamp != loadStamp.get()) {
                return; // a newer selection won
            }
            final String result = text;
            SwingUtilities.invokeLater(() -> {
                if (stamp == loadStamp.get()) {
                    setTextInternal(result);
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
     * Extracts up to {@link #MAX_CHARS} characters of text from the item using
     * the engine parsing pipeline (mirrors the legacy
     * {@code TextParser.parseText()} without the App/hit-highlight machinery).
     */
    private String extractText(IItem item, IPEDSource source) throws Exception {
        StandardParser autoParser = new StandardParser();
        Metadata metadata = MetadataUtil.clone(item.getMetadata());
        ParsingTask.fillMetadata(item, metadata);

        ParsingTask expander = new ParsingTask(item, autoParser);
        expander.init(ConfigurationManager.get());
        ParseContext context = expander.getTikaContext(source);
        expander.setExtractEmbedded(false);

        StringBuilder sb = new StringBuilder();
        try (InputStream is = item.getTikaStream()) {
            ParsingReader reader = new ParsingReader(autoParser, is, metadata, context);
            reader.startBackgroundParsing();
            try {
                char[] buf = new char[8192];
                int read;
                while (sb.length() < MAX_CHARS && (read = reader.read(buf, 0, buf.length)) != -1) {
                    sb.append(buf, 0, read);
                }
            } finally {
                reader.close();
            }
        }
        if (sb.length() >= MAX_CHARS) {
            sb.append("\n\n").append(Messages.getString("ContentViewer.TextTruncated")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }
}
