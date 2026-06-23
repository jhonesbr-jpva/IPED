package iped.rcp.viewers.part;

import java.io.InputStream;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;

import iped.data.IItem;
import iped.engine.config.ConfigurationManager;
import iped.engine.data.IPEDSource;
import iped.engine.io.ParsingReader;
import iped.engine.task.ParsingTask;
import iped.parsers.standard.StandardParser;
import iped.parsers.util.MetadataUtil;
import iped.rcp.core.i18n.Messages;

/**
 * App-free extraction of an item's text (the legacy {@code TextParser} pipeline:
 * {@link StandardParser} + {@link ParsingReader}). Shared by {@link RcpTextViewer}
 * and {@link HitsViewerPart} so both operate on the <b>exact same text</b> — and
 * therefore the same match offsets, which the hits-list navigation relies on.
 */
final class TextExtraction {

    /** Upper bound of extracted characters kept in memory / shown. */
    static final int MAX_CHARS = 2_000_000;

    private TextExtraction() {
    }

    /**
     * Extracts up to {@code maxChars} characters of text from the item using the
     * engine parsing pipeline. Must run off the UI threads.
     */
    static String extract(IItem item, IPEDSource source, int maxChars) throws Exception {
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
                while (sb.length() < maxChars && (read = reader.read(buf, 0, buf.length)) != -1) {
                    sb.append(buf, 0, read);
                }
            } finally {
                reader.close();
            }
        }
        if (sb.length() >= maxChars) {
            sb.append("\n\n").append(Messages.getString("ContentViewer.TextTruncated")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return sb.toString();
    }
}
