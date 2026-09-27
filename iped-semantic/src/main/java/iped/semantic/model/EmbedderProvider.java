package iped.semantic.model;

import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.Source;

/**
 * Gives the embedder of the source this installation configured for a modality. It never falls back
 * to another source or another model (FR-044): when the configured source cannot answer, the caller
 * gets the exception and declares the modality unavailable.
 */
public interface EmbedderProvider {

    /**
     * @throws EmbeddingException
     *             when the configured source is unusable: local model missing, remote destination
     *             unreachable, credential refused
     */
    Embedder open(Modality modality) throws EmbeddingException;

    /** The source configured for the modality in this installation. */
    Source source(Modality modality);

    /** Remote destination (URL without credential) when the source is remote, else {@code null}. */
    String destination(Modality modality);

    /** Local model folder or remote destination, for messages that name what is missing. */
    String describeLocation(Modality modality);
}
