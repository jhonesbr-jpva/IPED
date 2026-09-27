package iped.semantic.model;

import java.io.Serializable;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Identity of a model as the model reports it (FR-043): {@code <repository>@<revision>+<variant>}
 * and, when available, the SHA-256 of the artifact. For a local model the SHA-256 is recomputed from
 * the file at load time; for {@code iped-embed/1} it is what the service answers; for
 * {@code openai-embeddings} there is only the model name, which is why the fingerprint exists.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ModelIdentity implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String DOT_PRODUCT = "DOT_PRODUCT";

    private final String id;
    private final String sha256;
    private final int dimensions;
    private final String similarity;
    private final String queryPrefix;
    private final String passagePrefix;

    @JsonCreator
    public ModelIdentity(@JsonProperty("id") String id, @JsonProperty("sha256") String sha256,
            @JsonProperty("dimensions") int dimensions, @JsonProperty("similarity") String similarity,
            @JsonProperty("queryPrefix") String queryPrefix, @JsonProperty("passagePrefix") String passagePrefix) {
        this.id = Objects.requireNonNull(id, "model id");
        this.sha256 = sha256 == null || sha256.isBlank() ? null : sha256;
        this.dimensions = dimensions;
        this.similarity = similarity == null ? DOT_PRODUCT : similarity;
        this.queryPrefix = queryPrefix == null ? "" : queryPrefix;
        this.passagePrefix = passagePrefix == null ? "" : passagePrefix;
    }

    @JsonProperty("id")
    public String getId() {
        return id;
    }

    @JsonProperty("sha256")
    public String getSha256() {
        return sha256;
    }

    @JsonProperty("dimensions")
    public int getDimensions() {
        return dimensions;
    }

    @JsonProperty("similarity")
    public String getSimilarity() {
        return similarity;
    }

    @JsonProperty("queryPrefix")
    public String getQueryPrefix() {
        return queryPrefix;
    }

    @JsonProperty("passagePrefix")
    public String getPassagePrefix() {
        return passagePrefix;
    }

    /** The text actually embedded for a role: the model's prefix followed by the text. */
    public String prefixed(Embedder.Role role, String text) {
        return (role == Embedder.Role.QUERY ? queryPrefix : passagePrefix) + text;
    }

    /**
     * Same reported identity: equal ids and, when both sides know the artifact hash, equal hashes.
     * This is half of compatibility; the other half is the fingerprint (E10), which also catches
     * weights swapped under the same name.
     */
    public boolean sameReportedIdentity(ModelIdentity other) {
        if (other == null || !id.equals(other.id) || dimensions != other.dimensions) {
            return false;
        }
        return sha256 == null || other.sha256 == null || sha256.equalsIgnoreCase(other.sha256);
    }

    /** Short human form: the id, plus the first 12 hex digits of the hash when known. */
    public String describe() {
        return sha256 == null ? id : id + " (sha256 " + sha256.substring(0, Math.min(12, sha256.length())) + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ModelIdentity)) {
            return false;
        }
        ModelIdentity m = (ModelIdentity) o;
        return id.equals(m.id) && Objects.equals(sha256, m.sha256) && dimensions == m.dimensions
                && similarity.equals(m.similarity) && queryPrefix.equals(m.queryPrefix)
                && passagePrefix.equals(m.passagePrefix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, sha256, dimensions);
    }

    @Override
    public String toString() {
        return describe();
    }
}
