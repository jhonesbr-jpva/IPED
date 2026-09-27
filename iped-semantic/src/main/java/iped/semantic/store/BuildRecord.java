package iped.semantic.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.model.Fingerprint;
import iped.semantic.model.ModelIdentity;

/**
 * The build record of a case, {@code <case>/iped/semantic/build-record.json} (data-model E2,
 * contracts/case-storage.md): per modality, how its semantic search was built.
 *
 * <p>
 * <b>No credential field exists in this class</b>, nor any item content: the record travels with
 * the case (FR-045). The remote section holds the protocol and the destination URL of the
 * <i>preparation</i> only; the destination of questions depends on the installation that asks and
 * goes in each result instead (amendment A4).
 * </p>
 *
 * <p>
 * Written as UTF-8 to a temporary file then renamed atomically. The text and visual tasks run in
 * the same processing JVM and both update this file, so every update goes through
 * {@link #update(Path, Consumer)}, serialized per file.
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BuildRecord {

    public static final int SCHEMA = 1;

    public enum State {
        PREPARING, COMPLETE, PARTIAL, DISABLED_MISMATCH
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    public int schema = SCHEMA;
    public String ipedVersion;
    public Map<String, ModalityRecord> modalities = new TreeMap<>();

    /** One modality's section. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ModalityRecord {
        public State state;
        /** Visual only: {@code images} and {@code videos}, each on or off. */
        public Map<String, Boolean> keys;
        /** {@code local} or {@code remote}: the source used for the preparation. */
        public String source;
        public ModelRecord model;
        /** Preparation destination when {@link #source} is remote; never a credential. */
        public RemoteRecord remote;
        public Fingerprint fingerprint;
        public Map<String, Object> params;
        /** Start, end and duration; in the visual modality, split into images and videos. */
        public Map<String, Object> timing;
        /** Items per coverage reason code. */
        public Map<String, Long> counts;
        public List<String> conflicts;
        /**
         * Items with a smaller id existed before the capability was first enabled in this case
         * (appended case, research D16); without a status document they count as
         * {@code BEFORE_CAPABILITY}. {@code null} when the case was created with the capability on.
         */
        public Integer capabilityStartId;
        /** Why the modality was disabled ({@link State#DISABLED_MISMATCH}), in English. */
        public String disabledReason;

        public ModelIdentity identity() {
            return model == null ? null : model.toIdentity();
        }

        public boolean preparedRemotely() {
            return "remote".equals(source);
        }
    }

    /** The model as recorded: its reported identity plus who reported it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ModelRecord {
        public String id;
        /** Always {@code model}: the identity is the one the model reported (FR-043). */
        public String reportedBy = "model";
        public String sha256;
        public int dimensions;
        public String similarity;
        public String queryPrefix;
        public String passagePrefix;

        public static ModelRecord of(ModelIdentity identity) {
            ModelRecord m = new ModelRecord();
            m.id = identity.getId();
            m.sha256 = identity.getSha256();
            m.dimensions = identity.getDimensions();
            m.similarity = identity.getSimilarity();
            m.queryPrefix = identity.getQueryPrefix();
            m.passagePrefix = identity.getPassagePrefix();
            return m;
        }

        public ModelIdentity toIdentity() {
            return new ModelIdentity(id, sha256, dimensions, similarity, queryPrefix, passagePrefix);
        }
    }

    /** Where content went during preparation. {@code phase} is always {@code preparation}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class RemoteRecord {
        public String protocol;
        public String destination;
        public String phase = "preparation";

        public RemoteRecord() {
        }

        public RemoteRecord(String protocol, String destination) {
            this.protocol = protocol;
            this.destination = destination;
        }
    }

    public ModalityRecord get(Modality modality) {
        return modalities.get(modality.key());
    }

    public ModalityRecord getOrCreate(Modality modality) {
        return modalities.computeIfAbsent(modality.key(), k -> new ModalityRecord());
    }

    /** Reads a record; {@code null} when the file does not exist. */
    public static BuildRecord read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), BuildRecord.class);
    }

    /** Writes to a temporary file in the same folder and renames it over the target. */
    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), BuildRecordFileName.PREFIX, ".tmp");
        try {
            Files.writeString(tmp, MAPPER.writeValueAsString(this), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Read-modify-write under a per-file lock, so the text and visual tasks never lose each other's
     * section. Creates the record when absent.
     *
     * @return the record as written
     */
    public static BuildRecord update(Path file, Consumer<BuildRecord> change) throws IOException {
        Object lock = LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), k -> new Object());
        synchronized (lock) {
            BuildRecord r = read(file);
            if (r == null) {
                r = new BuildRecord();
            }
            change.accept(r);
            r.write(file);
            return r;
        }
    }

    /** JSON text, for diagnostics and tests. */
    public String toJson() throws IOException {
        return MAPPER.writeValueAsString(this);
    }

    /** Convenience: an ordered timing map. */
    public static Map<String, Object> timing(String startedAt, String finishedAt, long durationMs) {
        Map<String, Object> t = new LinkedHashMap<>();
        if (startedAt != null) {
            t.put("startedAt", startedAt);
        }
        if (finishedAt != null) {
            t.put("finishedAt", finishedAt);
        }
        t.put("durationMs", durationMs);
        return t;
    }

    public static List<String> newConflicts() {
        return new ArrayList<>();
    }

    private static final class BuildRecordFileName {
        static final String PREFIX = "build-record-";
    }
}
