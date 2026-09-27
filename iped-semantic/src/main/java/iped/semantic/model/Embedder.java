package iped.semantic.model;

import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.util.List;

/**
 * Turns text or images into L2-normalised vectors. Implementations: the local ONNX Runtime model
 * and the two remote protocols. The same model must produce the same vectors wherever it runs; the
 * {@link Fingerprint} is what verifies that (research D10).
 */
public interface Embedder extends Closeable {

    /**
     * Role of a text. Asymmetric retrieval models (e5) embed questions and passages with different
     * prefixes; the model's {@link ModelIdentity} says which. A remote service never learns about
     * prefixes: they travel inside the text for {@code openai-embeddings}, and as this role for
     * {@code iped-embed/1}.
     */
    enum Role {
        QUERY, PASSAGE
    }

    /**
     * Embeds texts. The result has one unit-norm vector per input, in input order.
     *
     * @throws EmbeddingException
     *             when the model cannot answer; the caller decides whether that fails the item
     *             (processing) or the whole question (search)
     */
    float[][] embedTexts(Role role, List<String> texts) throws EmbeddingException;

    /**
     * Embeds images (thumbnails or frames cut from a video mosaic, never original files).
     *
     * @throws UnsupportedOperationException
     *             if the model has no image encoder
     */
    default float[][] embedImages(List<BufferedImage> images) throws EmbeddingException {
        throw new UnsupportedOperationException(identity().getId() + " does not embed images");
    }

    default boolean supportsImages() {
        return false;
    }

    /** The identity the model itself reports (FR-043), never the one the configuration declares. */
    ModelIdentity identity();

    default int dimensions() {
        return identity().getDimensions();
    }

    /** {@code null} for a local model; the URL without credentials for a remote one (FR-024). */
    default String destination() {
        return null;
    }

    @Override
    default void close() {
        // most embedders share a per-JVM session and have nothing to release
    }
}
