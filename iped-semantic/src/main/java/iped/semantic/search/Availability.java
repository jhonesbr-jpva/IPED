package iped.semantic.search;

import java.io.IOException;

import iped.semantic.config.SemanticConfigException;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.Source;
import iped.semantic.model.Embedder;
import iped.semantic.model.EmbedderProvider;
import iped.semantic.model.EmbeddingException;
import iped.semantic.model.Fingerprint;
import iped.semantic.model.ModelIdentity;
import iped.semantic.store.BuildRecord;
import iped.semantic.store.BuildRecord.ModalityRecord;
import iped.semantic.store.BuildRecord.State;
import iped.semantic.store.SemanticStore;

/**
 * Whether a modality of a case can answer a question now, and if not, why and what to do - the
 * table "Disponibilidade na hora da pergunta" of data-model E2, row by row:
 *
 * <table>
 * <tr><th>Situation</th><th>Result</th></tr>
 * <tr><td>{@code semantic/} absent</td><td>{@link Reason#CASE_WITHOUT_CAPABILITY} (FR-027)</td></tr>
 * <tr><td>modality without section</td><td>{@link Reason#MODALITY_NOT_PREPARED} (FR-033)</td></tr>
 * <tr><td>local model missing</td><td>{@link Reason#LOCAL_MODEL_MISSING}, naming model and package</td></tr>
 * <tr><td>configured source unreachable</td><td>{@link Reason#SOURCE_UNREACHABLE}, naming destination
 * or model; no other model or source is tried (FR-044)</td></tr>
 * <tr><td>source other than preparation's</td><td>available if the fingerprint confirms the model (A4)</td></tr>
 * <tr><td>fingerprint out of tolerance</td><td>{@link Reason#MODEL_INCOMPATIBLE}, naming both models (FR-016)</td></tr>
 * <tr><td>{@code PARTIAL}</td><td>available, partial coverage declared</td></tr>
 * <tr><td>{@code COMPLETE}</td><td>available</td></tr>
 * </table>
 *
 * Two situations the table does not list are treated explicitly: a configuration that cannot serve
 * the modality ({@link Reason#CONFIGURATION_ERROR}), and a record whose index folder is gone
 * ({@link Reason#INDEX_MISSING}). A modality left {@code PREPARING} (processing running or killed)
 * or {@code DISABLED_MISMATCH} (an append refused the model) answers like {@code PARTIAL}: what was
 * built is searchable and the coverage says what is missing.
 */
public final class Availability {

    /** Why a modality is unavailable. The name is the stable code the MCP returns. */
    public enum Reason {
        CASE_WITHOUT_CAPABILITY, MODALITY_NOT_PREPARED, INDEX_MISSING, CONFIGURATION_ERROR, LOCAL_MODEL_MISSING,
        SOURCE_UNREACHABLE, MODEL_INCOMPATIBLE;

        /** Key of the localized statement of the reason. */
        public String messageKey() {
            return "SemanticSearch.Unavailable." + name();
        }

        /** Key of the localized remedy. */
        public String remedyKey() {
            return "SemanticSearch.Remedy." + name();
        }
    }

    private final Modality modality;
    private final Reason reason;
    private final String detail;
    private final ModalityRecord record;
    private final Embedder embedder;
    private final Source source;
    private final String destination;
    private final ModelIdentity caseModel;
    private final ModelIdentity currentModel;

    private Availability(Modality modality, Reason reason, String detail, ModalityRecord record, Embedder embedder,
            Source source, String destination, ModelIdentity caseModel, ModelIdentity currentModel) {
        this.modality = modality;
        this.reason = reason;
        this.detail = detail;
        this.record = record;
        this.embedder = embedder;
        this.source = source;
        this.destination = destination;
        this.caseModel = caseModel;
        this.currentModel = currentModel;
    }

    /**
     * Evaluates one modality of one case.
     *
     * @param store
     *            the case's semantic folder
     * @param config
     *            this installation's configuration
     * @param provider
     *            the embedder of the source this installation configured
     * @param compatibility
     *            the session's fingerprint cache
     */
    public static Availability evaluate(SemanticStore store, Modality modality, SemanticSearchConfig config,
            EmbedderProvider provider, Compatibility compatibility) {
        Source source = provider.source(modality);
        String destination = provider.destination(modality);
        if (!store.exists()) {
            return unavailable(modality, Reason.CASE_WITHOUT_CAPABILITY, "the case has no " + store.root() + " folder",
                    null, source, destination, null);
        }
        BuildRecord buildRecord;
        try {
            buildRecord = BuildRecord.read(store.buildRecord());
        } catch (IOException e) {
            return unavailable(modality, Reason.INDEX_MISSING, "the build record cannot be read: " + e.getMessage(),
                    null, source, destination, null);
        }
        if (buildRecord == null) {
            return unavailable(modality, Reason.CASE_WITHOUT_CAPABILITY, "the case has no build record", null, source,
                    destination, null);
        }
        ModalityRecord record = buildRecord.get(modality);
        if (record == null) {
            return unavailable(modality, Reason.MODALITY_NOT_PREPARED,
                    "the " + modality.key() + " modality was off when the case was processed", null, source,
                    destination, null);
        }
        ModelIdentity caseModel = record.identity();
        if (!store.hasIndex(modality)) {
            return unavailable(modality, Reason.INDEX_MISSING, "the index folder " + store.index(modality)
                    + " is missing", record, source, destination, caseModel);
        }
        try {
            config.validate(modality);
        } catch (SemanticConfigException e) {
            return unavailable(modality, Reason.CONFIGURATION_ERROR, e.getMessage(), record, source, destination,
                    caseModel);
        }
        Embedder embedder;
        try {
            embedder = provider.open(modality);
        } catch (EmbeddingException e) {
            Reason r = source == Source.LOCAL ? Reason.LOCAL_MODEL_MISSING : Reason.SOURCE_UNREACHABLE;
            return unavailable(modality, r, provider.describeLocation(modality) + ": " + e.getMessage(), record,
                    source, destination, caseModel);
        }
        Fingerprint.Comparison cmp;
        try {
            cmp = compatibility.check(record, embedder, modality == Modality.VISUAL,
                    config.getFingerprintTolerance());
        } catch (EmbeddingException | RuntimeException e) {
            return unavailable(modality, Reason.SOURCE_UNREACHABLE,
                    provider.describeLocation(modality) + " could not compute the model fingerprint: " + e.getMessage(),
                    record, source, destination, caseModel);
        }
        if (!cmp.isCompatible()) {
            return new Availability(modality, Reason.MODEL_INCOMPATIBLE, cmp.reason(), record, null, source,
                    destination, caseModel, embedder.identity());
        }
        return new Availability(modality, null, null, record, embedder, source, destination, caseModel,
                embedder.identity());
    }

    private static Availability unavailable(Modality m, Reason r, String detail, ModalityRecord record, Source source,
            String destination, ModelIdentity caseModel) {
        return new Availability(m, r, detail, record, null, source, destination, caseModel, null);
    }

    public boolean isAvailable() {
        return reason == null;
    }

    public Modality getModality() {
        return modality;
    }

    /** {@code null} when available. */
    public Reason getReason() {
        return reason;
    }

    /** Factual English detail of the unavailability (logs, MCP); {@code null} when available. */
    public String getDetail() {
        return detail;
    }

    /** The case's modality section; {@code null} when the case or modality has none. */
    public ModalityRecord getRecord() {
        return record;
    }

    /** The embedder to ask questions with; {@code null} when unavailable. */
    public Embedder getEmbedder() {
        return embedder;
    }

    /** Whether the coverage must be declared partial: anything but {@code COMPLETE}. */
    public boolean isPartial() {
        return record != null && record.state != State.COMPLETE;
    }

    /** Source of this installation for the questions. */
    public Source getSource() {
        return source;
    }

    /** Where questions are sent when the source is remote ({@code sent_to}); else {@code null}. */
    public String getDestination() {
        return destination;
    }

    /** Source recorded for the preparation, {@code local} or {@code remote}; {@code null} if none. */
    public String getPreparedWith() {
        return record == null ? null : record.source;
    }

    public ModelIdentity getCaseModel() {
        return caseModel;
    }

    public ModelIdentity getCurrentModel() {
        return currentModel;
    }
}
