package iped.semantic.task;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.apache.tika.metadata.Message;
import org.apache.tika.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.configuration.Configurable;
import iped.data.IItem;
import iped.engine.Version;
import iped.engine.config.AudioTranscriptConfig;
import iped.engine.config.ConfigurationManager;
import iped.engine.config.OCRConfig;
import iped.engine.data.Item;
import iped.engine.lucene.analysis.AppAnalyzer;
import iped.engine.task.AbstractTask;
import iped.engine.util.UIPropertyListenerProvider;
import iped.parsers.standard.StandardParser;
import iped.properties.ExtraProperties;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.model.EmbedderFactory;
import iped.semantic.search.CaseItems;

/**
 * Prepares the text modality of semantic search during processing (research D6): extracted text
 * (OCR included, which parsing already appends), audio transcripts, and chat messages staged for
 * conversation sequences. Registered in {@code TaskInstaller.xml} right after
 * {@code NamedEntityTask}, where text and transcript are available and the text cache is still
 * there.
 *
 * <p>
 * Off by default ({@code enableSemanticText = false}): then {@link #isEnabled()} is false, nothing
 * is loaded and no native class is touched (FR-020, SC-008). On, {@link #init} validates the model
 * source before any evidence is read and refuses to start without one (FR-042). The work itself is in
 * {@link TextPreparation}, shared by the instances of every worker.
 * </p>
 */
public class SemanticTextTask extends AbstractTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(SemanticTextTask.class);

    private static final Object INIT_LOCK = new Object();
    private static final AtomicBoolean FINISHED = new AtomicBoolean(false);
    private static volatile TextPreparation preparation;

    private boolean enabled;

    @Override
    public List<Configurable<?>> getConfigurables() {
        return List.of(new SemanticSearchConfig());
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void init(ConfigurationManager configurationManager) throws Exception {
        SemanticSearchConfig config = configurationManager.findObject(SemanticSearchConfig.class);
        enabled = config != null && config.isEnabled();
        if (!enabled) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (preparation != null) {
                return;
            }
            FINISHED.set(false);
            SourceValidator.Validated source = SourceValidator.validate(config, Modality.TEXT,
                    SemanticSearchConfig.ENABLE_TEXT, new EmbedderFactory(config));
            TextPreparation.Settings settings = new TextPreparation.Settings().analyzer(AppAnalyzer.get())
                    .metadataHeader(StandardParser.METADATA_HEADER).ocrEnabled(ocrEnabled(configurationManager))
                    .transcriptionEnabled(transcriptionEnabled(configurationManager))
                    .capabilityStartId(capabilityStartId(output)).ipedVersion(Version.APP_VERSION);
            TextPreparation prep = TextPreparation.open(output, config, source, settings);
            preparation = prep;
            String commitStarted = iped.engine.localization.Messages.getString("Manager.CommitStarted");
            UIPropertyListenerProvider.getInstance().addPropertyChangeListener(evt -> {
                TextPreparation p = preparation;
                if (p != null && "mensagem".equals(evt.getPropertyName()) && commitStarted.equals(evt.getNewValue())) {
                    p.commitWithMainIndex();
                }
            }, false);
            LOGGER.info("Semantic text search is on: model {}", source.embedder().identity().describe());
        }
    }

    private static boolean ocrEnabled(ConfigurationManager cm) {
        OCRConfig ocr = cm.findObject(OCRConfig.class);
        return ocr != null && Boolean.TRUE.equals(ocr.isOCREnabled());
    }

    private static boolean transcriptionEnabled(ConfigurationManager cm) {
        AudioTranscriptConfig t = cm.findObject(AudioTranscriptConfig.class);
        return t != null && t.isEnabled();
    }

    /**
     * When the case already has items (an append to a case processed without the capability), the
     * first id of this processing; items below it predate the capability (research D16). Read from
     * the committed main index, never written.
     */
    static Integer capabilityStartId(File moduleDir) {
        File index = new File(moduleDir, "index");
        try {
            if (!index.isDirectory() || !DirectoryReader.indexExists(FSDirectory.open(index.toPath()))) {
                return null;
            }
            try (DirectoryReader r = DirectoryReader.open(FSDirectory.open(index.toPath()))) {
                int next = CaseItems.of(r).length();
                return next > 0 ? next : null;
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read the main index to find items that predate semantic search", e);
            return null;
        }
    }

    @Override
    protected void process(IItem evidence) throws Exception {
        TextPreparation prep = preparation;
        if (prep == null || !evidence.isToAddToCase() || evidence.isQueueEnd()) {
            return;
        }
        prep.prepare(toTextItem(evidence));
    }

    static TextItem toTextItem(IItem evidence) {
        String mediaType = evidence.getMediaType() == null ? "" : evidence.getMediaType().toString();
        Set<String> categories = evidence.getCategorySet();
        Metadata md = evidence.getMetadata();
        String body = md == null ? null : messageBody(md);
        Integer parent = evidence.getParentId();
        if (body != null && parent != null) {
            Date date = md.getDate(ExtraProperties.COMMUNICATION_DATE);
            String sender = md.get(ExtraProperties.COMMUNICATION_FROM);
            if (sender == null) {
                sender = md.get(Message.MESSAGE_FROM);
            }
            Integer position = null;
            String pos = md.get(ExtraProperties.PARENT_VIEW_POSITION);
            if (pos != null) {
                try {
                    position = Integer.valueOf(pos.trim());
                } catch (NumberFormatException e) {
                    position = null;
                }
            }
            // Only types listed in messageMediaTypes become sequences; TextPreparation decides, and
            // prepares any other item with a body as a document.
            return TextItem.message(evidence.getId(), mediaType, categories, textSource(evidence),
                    transcript(evidence), parent, date == null ? null : date.getTime(), position, sender, body);
        }
        return TextItem.document(evidence.getId(), mediaType, categories, textSource(evidence), transcript(evidence));
    }

    /**
     * Parser-generated marker the chat parsers write as the body of a message that has no text,
     * {@code "! " + message type} (WhatsApp, Threema; Telegram uses it for attachments). It names
     * the type of the event, it is not something anyone wrote.
     */
    private static final java.util.regex.Pattern TYPE_MARKER = java.util.regex.Pattern.compile("^! [A-Z0-9_]+$");

    /**
     * The literal text of a message: every value of {@code Message-Body} (a media caption is added
     * as a second value) except the parsers' type markers. {@code null} when the item is not a
     * message; empty when the message has no text.
     */
    static String messageBody(Metadata md) {
        String[] values = md.getValues(ExtraProperties.MESSAGE_BODY);
        if (values == null || values.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (v == null || TYPE_MARKER.matcher(v.strip()).matches()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(v);
        }
        return sb.toString();
    }

    private static TextItem.TextSource textSource(IItem evidence) {
        boolean hasText = evidence instanceof Item ? ((Item) evidence).getTextCache() != null
                : evidence.getParsedTextCache() != null;
        return hasText ? evidence::getTextReader : null;
    }

    private static String transcript(IItem evidence) {
        Object t = evidence.getExtraAttribute(ExtraProperties.TRANSCRIPT_ATTR);
        return t == null ? null : t.toString();
    }

    @Override
    public void finish() throws Exception {
        synchronized (FINISHED) {
            TextPreparation prep = preparation;
            if (prep == null || FINISHED.getAndSet(true)) {
                return;
            }
            try {
                prep.finish();
            } finally {
                preparation = null;
            }
        }
    }

    /** Test hook: whether a preparation is open in this JVM. */
    static boolean isPreparing() {
        return preparation != null;
    }
}
