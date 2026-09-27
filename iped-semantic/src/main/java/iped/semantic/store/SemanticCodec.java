package iped.semantic.store;

import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.lucene92.Lucene92Codec;
import org.apache.lucene.codecs.lucene92.Lucene92HnswVectorsFormat;

/**
 * Codec of the semantic indexes only: the default Lucene 9.2 codec with the HNSW graph parameters
 * fixed from {@code params.hnsw} (research D5). The codec name written to the segments stays
 * {@code Lucene92}, and the vectors format is recorded per field by name, so a reader needs no
 * custom SPI registration: the graph parameters matter only when writing.
 *
 * <p>
 * The main index's codec and similarity are never touched.
 * </p>
 */
public final class SemanticCodec extends Lucene92Codec {

    private final KnnVectorsFormat vectors;

    public SemanticCodec(int maxConn, int beamWidth) {
        this.vectors = new Lucene92HnswVectorsFormat(maxConn, beamWidth);
    }

    @Override
    public KnnVectorsFormat getKnnVectorsFormatForField(String field) {
        return vectors;
    }
}
