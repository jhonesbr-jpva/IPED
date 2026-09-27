package iped.semantic.search;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import iped.semantic.model.Embedder;
import iped.semantic.model.EmbeddingException;
import iped.semantic.model.Fingerprint;
import iped.semantic.model.ModelIdentity;
import iped.semantic.store.BuildRecord.ModalityRecord;

/**
 * Decides whether the model in use is the model a case modality was built with (FR-016, FR-043,
 * FR-044, amendment A4): the reported identity must match and every fingerprint probe must stay
 * within tolerance. The source does not matter; a case prepared remotely is searchable with the
 * identical local artifact and vice versa.
 *
 * <p>
 * The probes are recomputed once per search session and per embedder (research D10), not per
 * question: this object is that session's cache.
 * </p>
 */
public final class Compatibility {

    private final Map<Embedder, Fingerprint> computed = new ConcurrentHashMap<>();

    /**
     * @param record
     *            the case's modality section
     * @param embedder
     *            the source configured in this installation
     * @param withImages
     *            visual modality: the probes include the synthetic images
     * @param tolerance
     *            the installation's {@code fingerprintTolerance}
     * @throws EmbeddingException
     *             when the source cannot compute the probes (it is then unreachable)
     */
    public Fingerprint.Comparison check(ModalityRecord record, Embedder embedder, boolean withImages,
            double tolerance) throws EmbeddingException {
        Fingerprint current = computed.get(embedder);
        if (current == null) {
            current = Fingerprint.compute(embedder, withImages, tolerance);
            computed.put(embedder, current);
        }
        ModelIdentity recorded = record.identity();
        Fingerprint recordedFp = record.fingerprint;
        if (recordedFp == null) {
            // A record without fingerprint cannot prove anything; treat as probe mismatch.
            return new Fingerprint(0, tolerance, java.util.List.of()).compare(recorded, current, embedder.identity(),
                    tolerance);
        }
        return recordedFp.compare(recorded, current, embedder.identity(), tolerance);
    }

    /** Forgets the computed probes (a new session). */
    public void reset() {
        computed.clear();
    }
}
