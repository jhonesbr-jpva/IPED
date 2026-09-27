package iped.semantic.search;

import java.util.BitSet;

import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.TextMode;

/**
 * A semantic question (data-model E1): a natural-language query or a reference item, a scope, a
 * modality and, for text, a mode. Built by the UI and by the MCP tools alike, so both surfaces
 * describe the same question the same way (SC-011).
 */
public final class SemanticRequest {

    /** Which ordering(s) to produce. {@code BOTH} gives two separate orderings, never mixed (FR-034). */
    public enum Target {
        TEXT, VISUAL, BOTH
    }

    /** "More like this" (FR-013): an item, a character range in it, or a selected text. */
    public static final class Reference {
        private final int caseIndex;
        private final int itemId;
        private final Integer charStart;
        private final Integer charEnd;
        private final String selectedText;

        private Reference(int caseIndex, int itemId, Integer charStart, Integer charEnd, String selectedText) {
            this.caseIndex = caseIndex;
            this.itemId = itemId;
            this.charStart = charStart;
            this.charEnd = charEnd;
            this.selectedText = selectedText;
        }

        public static Reference item(int itemId) {
            return new Reference(0, itemId, null, null, null);
        }

        public static Reference range(int itemId, int charStart, int charEnd) {
            if (charStart < 0 || charEnd <= charStart) {
                throw new IllegalArgumentException("invalid range [" + charStart + ", " + charEnd + ")");
            }
            return new Reference(0, itemId, charStart, charEnd, null);
        }

        public static Reference selectedText(int itemId, String text) {
            return new Reference(0, itemId, null, null, text);
        }

        /** The same reference in another open case (several cases opened together). */
        public Reference inCase(int index) {
            return new Reference(index, itemId, charStart, charEnd, selectedText);
        }

        /** Index of the case the item belongs to, among the cases of the question. */
        public int getCaseIndex() {
            return caseIndex;
        }

        public int getItemId() {
            return itemId;
        }

        public Integer getCharStart() {
            return charStart;
        }

        public Integer getCharEnd() {
            return charEnd;
        }

        public String getSelectedText() {
            return selectedText;
        }

        public boolean isWholeItem() {
            return charStart == null && selectedText == null;
        }
    }

    private final Target target;
    private final TextMode mode;
    private final String query;
    private final boolean queryTruncated;
    private final int originalQueryLength;
    private final Reference reference;
    private final BitSet scope;
    private final int depth;

    private SemanticRequest(Builder b, SemanticSearchConfig config) {
        this.target = b.target;
        this.mode = b.mode != null ? b.mode : config.getDefaultTextMode();
        String q = b.query == null ? null : b.query.strip();
        this.originalQueryLength = q == null ? 0 : q.length();
        if (q != null && q.length() > config.getMaxQueryChars()) {
            this.query = q.substring(0, config.getMaxQueryChars());
            this.queryTruncated = true;
        } else {
            this.query = q;
            this.queryTruncated = false;
        }
        this.reference = b.reference;
        this.scope = b.scope;
        this.depth = b.depth != null ? b.depth : config.getDefaultDepth();
        if ((query == null || query.isEmpty()) && reference == null) {
            throw new IllegalArgumentException("a question needs a query or a reference");
        }
        if (reference != null && target != Target.TEXT) {
            throw new IllegalArgumentException("a reference item applies to the text modality only");
        }
        if (depth < 1 || depth > config.getMaxDepth()) {
            throw new IllegalArgumentException(
                    "depth must satisfy 1 <= depth <= maxDepth (" + config.getMaxDepth() + "), not " + depth);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Target target = Target.TEXT;
        private TextMode mode;
        private String query;
        private Reference reference;
        private BitSet scope;
        private Integer depth;

        public Builder target(Target t) {
            this.target = t;
            return this;
        }

        /** {@code null} means the installation's {@code defaultTextMode}. */
        public Builder mode(TextMode m) {
            this.mode = m;
            return this;
        }

        public Builder query(String q) {
            this.query = q;
            return this;
        }

        public Builder reference(Reference r) {
            this.reference = r;
            return this;
        }

        /**
         * Item ids of the main index the question is restricted to (FR-012). {@code null} means the
         * whole case; an empty set is an empty scope, answered as such, never as "nothing found".
         */
        public Builder scope(BitSet ids) {
            this.scope = ids;
            return this;
        }

        public Builder depth(int d) {
            this.depth = d;
            return this;
        }

        public SemanticRequest build(SemanticSearchConfig config) {
            return new SemanticRequest(this, config);
        }
    }

    public Target getTarget() {
        return target;
    }

    /** Text mode; the visual modality is always semantic. */
    public TextMode getMode() {
        return mode;
    }

    /** The query as used, truncated to {@code maxQueryChars}; {@code null} with a reference. */
    public String getQuery() {
        return query;
    }

    public boolean isQueryTruncated() {
        return queryTruncated;
    }

    public int getOriginalQueryLength() {
        return originalQueryLength;
    }

    public Reference getReference() {
        return reference;
    }

    /** {@code null} for the whole case. */
    public BitSet getScope() {
        return scope;
    }

    public int getDepth() {
        return depth;
    }
}
