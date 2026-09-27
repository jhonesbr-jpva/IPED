package iped.semantic.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Model fingerprint (research D10, data-model E10): the vectors a model produces for a fixed probe
 * set. The case records them at preparation; each search session recomputes them with the source in
 * use - the same one or another, local or remote (amendment A4) - and compares.
 *
 * <p>
 * Rule: compatible if and only if every probe has cosine &gt;= tolerance <b>and</b> the identity the
 * model reports equals the recorded one. The identity catches a different model under a different
 * name; the probes also catch different weights under the same name, a corrupted file and a
 * different export. The tolerance absorbs numeric differences between CPUs.
 * </p>
 */
public final class Fingerprint implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int probesVersion;
    private final double tolerance;
    private final List<float[]> vectors;

    @JsonCreator
    public Fingerprint(@JsonProperty("probesVersion") int probesVersion,
            @JsonProperty("tolerance") double tolerance, @JsonProperty("vectors") List<float[]> vectors) {
        this.probesVersion = probesVersion;
        this.tolerance = tolerance;
        this.vectors = vectors == null ? List.of() : List.copyOf(vectors);
    }

    /**
     * Computes the fingerprint with an embedder: the text probes as passages, then, when
     * {@code withImages}, the synthetic images. Each probe is embedded on its own, so the result
     * depends on nothing but the probe and the model.
     *
     * @param tolerance
     *            the tolerance in force, recorded for the record's reader
     */
    public static Fingerprint compute(Embedder embedder, boolean withImages, double tolerance)
            throws EmbeddingException {
        List<float[]> out = new ArrayList<>();
        for (String probe : FingerprintProbes.texts()) {
            out.add(embedder.embedTexts(Embedder.Role.PASSAGE, List.of(probe))[0]);
        }
        if (withImages) {
            for (java.awt.image.BufferedImage img : FingerprintProbes.images()) {
                out.add(embedder.embedImages(List.of(img))[0]);
            }
        }
        return new Fingerprint(FingerprintProbes.VERSION, tolerance, out);
    }

    @JsonProperty("probesVersion")
    public int getProbesVersion() {
        return probesVersion;
    }

    @JsonProperty("tolerance")
    public double getTolerance() {
        return tolerance;
    }

    @JsonProperty("vectors")
    public List<float[]> getVectors() {
        return vectors;
    }

    /** Result of comparing a recorded fingerprint with one computed now. */
    public static final class Comparison {
        private final boolean identityMatches;
        private final boolean probesMatch;
        private final double minCosine;
        private final String reason;

        Comparison(boolean identityMatches, boolean probesMatch, double minCosine, String reason) {
            this.identityMatches = identityMatches;
            this.probesMatch = probesMatch;
            this.minCosine = minCosine;
            this.reason = reason;
        }

        public boolean isCompatible() {
            return identityMatches && probesMatch;
        }

        public boolean identityMatches() {
            return identityMatches;
        }

        public boolean probesMatch() {
            return probesMatch;
        }

        /** Smallest probe cosine, or {@code NaN} when the probe sets could not be compared. */
        public double minCosine() {
            return minCosine;
        }

        /** English explanation when incompatible; {@code null} when compatible. */
        public String reason() {
            return reason;
        }
    }

    /**
     * Compares this recorded fingerprint with one computed now.
     *
     * @param recordedIdentity
     *            identity recorded in the case
     * @param current
     *            fingerprint computed now with the source in use
     * @param currentIdentity
     *            identity the source in use reports
     * @param tolerance
     *            minimum cosine per probe (the installation's {@code fingerprintTolerance})
     */
    public Comparison compare(ModelIdentity recordedIdentity, Fingerprint current, ModelIdentity currentIdentity,
            double tolerance) {
        boolean identity = recordedIdentity != null && recordedIdentity.sameReportedIdentity(currentIdentity);
        if (current == null || current.probesVersion != probesVersion || current.vectors.size() != vectors.size()) {
            return new Comparison(identity, false, Double.NaN,
                    "probe sets differ (recorded version " + probesVersion + " with " + vectors.size()
                            + " probes, computed " + (current == null ? "none" : current.probesVersion + " with "
                                    + current.vectors.size() + " probes") + ")");
        }
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < vectors.size(); i++) {
            float[] a = vectors.get(i);
            float[] b = current.vectors.get(i);
            double cos = a.length == b.length ? VectorMath.cosine(a, b) : -1;
            min = Math.min(min, cos);
        }
        boolean probes = min >= tolerance;
        String reason = null;
        if (!identity) {
            reason = "the model in use reports " + describe(currentIdentity) + ", the case was prepared with "
                    + describe(recordedIdentity);
        } else if (!probes) {
            reason = "the model in use reports the same identity (" + describe(currentIdentity)
                    + ") but answers the fingerprint probes differently (minimum cosine "
                    + String.format(java.util.Locale.ROOT, "%.6f", min) + " < tolerance " + tolerance + ")";
        }
        return new Comparison(identity, probes, min, reason);
    }

    private static String describe(ModelIdentity id) {
        return id == null ? "no identity" : id.describe();
    }

    @JsonIgnore
    public int size() {
        return vectors.size();
    }
}
