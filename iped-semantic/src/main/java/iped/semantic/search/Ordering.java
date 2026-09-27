package iped.semantic.search;

import java.util.Collections;
import java.util.List;

import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.TextMode;

/**
 * The result of a question in one modality (data-model E8): a ranking, declared as such - depth,
 * ceiling, method, mode, coverage and model - never a set, never a total, never "no results"
 * (FR-007, FR-010).
 *
 * <p>
 * The list is computed once, up to the ceiling ({@link #getAll()}); the depth is a prefix of it.
 * Showing more, in the UI, or paging, in the MCP, reveals more of the same list and never runs the
 * search again, so what was shown keeps its order (research D5).
 * </p>
 */
public final class Ordering {

    /** How the semantic part compared: every unit of the scope, or the HNSW graph (amendment A3). */
    public enum Method {
        EXACT, APPROXIMATE
    }

    /** Model that produced the ordering, and where the question went. */
    public static final class ModelInfo {
        private final String id;
        private final String sha256;
        private final String source;
        private final String preparedWith;
        private final String sentTo;

        public ModelInfo(String id, String sha256, String source, String preparedWith, String sentTo) {
            this.id = id;
            this.sha256 = sha256;
            this.source = source;
            this.preparedWith = preparedWith;
            this.sentTo = sentTo;
        }

        public String getId() {
            return id;
        }

        public String getSha256() {
            return sha256;
        }

        /** Source this installation used for the question: {@code local} or {@code remote}. */
        public String getSource() {
            return source;
        }

        /** Source recorded for the case's preparation. */
        public String getPreparedWith() {
            return preparedWith;
        }

        /** Remote destination the question was sent to; {@code null} for a local model. */
        public String getSentTo() {
            return sentTo;
        }
    }

    private final Modality modality;
    private final TextMode mode;
    private final Method method;
    private final int lexicalMatches;
    private final int ceiling;
    private final int depth;
    private final List<OrderingEntry> all;
    private final Coverage coverage;
    private final ModelInfo model;
    private final List<Notice> notices;

    public Ordering(Modality modality, TextMode mode, Method method, int lexicalMatches, int ceiling, int depth,
            List<OrderingEntry> all, Coverage coverage, ModelInfo model, List<Notice> notices) {
        this.modality = modality;
        this.mode = mode;
        this.method = method;
        this.lexicalMatches = lexicalMatches;
        this.ceiling = ceiling;
        this.depth = depth;
        this.all = Collections.unmodifiableList(all);
        this.coverage = coverage;
        this.model = model;
        this.notices = Collections.unmodifiableList(notices);
    }

    /** The same ordering showing {@code newDepth} entries (clamped to the ceiling). */
    public Ordering withDepth(int newDepth) {
        return new Ordering(modality, mode, method, lexicalMatches, ceiling, Math.max(1, Math.min(newDepth, ceiling)),
                all, coverage, model, notices);
    }

    public Modality getModality() {
        return modality;
    }

    /** {@code SEMANTIC} or {@code HYBRID}; always {@code SEMANTIC} for the visual modality. */
    public TextMode getMode() {
        return mode;
    }

    public Method getMethod() {
        return method;
    }

    /** Hybrid only: units the word part brought; {@code 0} declares a semantic ordering. {@code -1} otherwise. */
    public int getLexicalMatches() {
        return lexicalMatches;
    }

    /** The ceiling ({@code maxDepth}): a depth limit, not a relevance threshold. */
    public int getCeiling() {
        return ceiling;
    }

    /** Requested depth. The number of entries shown is {@link #getShown()}. */
    public int getDepth() {
        return depth;
    }

    /** Entries revealed: the depth, or fewer when consolidation left fewer units than that. */
    public int getShown() {
        return Math.min(depth, all.size());
    }

    public List<OrderingEntry> getShownEntries() {
        return all.subList(0, getShown());
    }

    /** The whole list up to the ceiling. */
    public List<OrderingEntry> getAll() {
        return all;
    }

    /** The revealed part reached the ceiling: nothing more can be revealed by depth. */
    public boolean isCeilingReached() {
        return getShown() >= ceiling;
    }

    /** Whether more of the list can be revealed. */
    public boolean canExtend() {
        return getShown() < all.size() && getShown() < ceiling;
    }

    public Coverage getCoverage() {
        return coverage;
    }

    public ModelInfo getModel() {
        return model;
    }

    public List<Notice> getNotices() {
        return notices;
    }

    public boolean hasNotice(Notice.Code code) {
        for (Notice n : notices) {
            if (n.getCode() == code) {
                return true;
            }
        }
        return false;
    }
}
