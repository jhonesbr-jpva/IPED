package iped.semantic.task;

import iped.semantic.config.SemanticConfigException;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.model.Embedder;
import iped.semantic.model.EmbedderProvider;
import iped.semantic.model.EmbeddingException;
import iped.semantic.model.Fingerprint;

/**
 * Start-up validation of FR-042: when a semantic key is on, its modality must have a usable model
 * source before any evidence is read, or processing refuses to begin, naming what is missing.
 *
 * <p>
 * It runs in the task's {@code init()}, which the {@code Manager} executes for every task before it
 * creates the item producers that open the evidence (research D15, verified). "Usable" means the
 * source can actually compute the fingerprint probes: a model folder that exists but does not load,
 * or a remote destination that does not answer with an identity, fails here and not on the first
 * item.
 * </p>
 */
public final class SourceValidator {

    /** A validated source: the embedder and the fingerprint it produced. */
    public static final class Validated {
        private final Embedder embedder;
        private final Fingerprint fingerprint;

        Validated(Embedder embedder, Fingerprint fingerprint) {
            this.embedder = embedder;
            this.fingerprint = fingerprint;
        }

        public Embedder embedder() {
            return embedder;
        }

        public Fingerprint fingerprint() {
            return fingerprint;
        }
    }

    private SourceValidator() {
    }

    /**
     * @param enableKey
     *            the key that turned the modality on, named in the error
     * @throws SemanticConfigException
     *             naming the key, the source and what is missing
     */
    public static Validated validate(SemanticSearchConfig config, Modality modality, String enableKey,
            EmbedderProvider provider) throws SemanticConfigException {
        config.validate(modality);
        Embedder embedder;
        try {
            embedder = provider.open(modality);
        } catch (EmbeddingException e) {
            throw refusal(modality, enableKey, provider, e);
        }
        try {
            Fingerprint fp = Fingerprint.compute(embedder, modality == Modality.VISUAL,
                    config.getFingerprintTolerance());
            return new Validated(embedder, fp);
        } catch (EmbeddingException | RuntimeException e) {
            throw refusal(modality, enableKey, provider, e);
        }
    }

    private static SemanticConfigException refusal(Modality modality, String enableKey, EmbedderProvider provider,
            Exception cause) {
        String msg = "Semantic " + modality.key() + " search is on (" + enableKey
                + " = true) but its model source is unusable, so processing does not start: "
                + provider.describeLocation(modality) + ": " + cause.getMessage()
                + ". Install the model, fix the source in conf/" + SemanticSearchConfig.CONF_FILE + ", or turn "
                + enableKey + " off.";
        return new SemanticConfigException(msg, cause);
    }
}
