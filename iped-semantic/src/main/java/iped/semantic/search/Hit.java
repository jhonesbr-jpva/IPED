package iped.semantic.search;

import java.util.Collections;
import java.util.List;

import iped.semantic.store.SequenceLine;

/**
 * One scored unit before consolidation: a passage, transcript passage, sequence, image or video
 * frame, with its stored fields. {@code score} is the cosine in semantic mode and the fused rank
 * score in hybrid mode.
 */
public final class Hit {

    final int caseIndex;
    final int docId;
    double score;
    final String kind;
    final int itemId;
    final int ordinal;
    final Integer charStart;
    final Integer charEnd;
    final String text;
    final List<SequenceLine> lines;
    final Integer frameIndex;
    final Integer frameCount;
    final Long instantApproxMs;
    Integer semanticRank;
    Integer lexicalRank;

    public Hit(int caseIndex, int docId, double score, String kind, int itemId, int ordinal, Integer charStart,
            Integer charEnd, String text, List<SequenceLine> lines, Integer frameIndex, Integer frameCount,
            Long instantApproxMs) {
        this.caseIndex = caseIndex;
        this.docId = docId;
        this.score = score;
        this.kind = kind;
        this.itemId = itemId;
        this.ordinal = ordinal;
        this.charStart = charStart;
        this.charEnd = charEnd;
        this.text = text;
        this.lines = lines == null ? Collections.emptyList() : lines;
        this.frameIndex = frameIndex;
        this.frameCount = frameCount;
        this.instantApproxMs = instantApproxMs;
    }

    /** A passage or transcript hit. */
    public static Hit passage(int caseIndex, int docId, double score, String kind, int itemId, int ordinal,
            int charStart, int charEnd, String text) {
        return new Hit(caseIndex, docId, score, kind, itemId, ordinal, charStart, charEnd, text, null, null, null,
                null);
    }

    /** A sequence hit; {@code itemId} is the chat. */
    public static Hit sequence(int caseIndex, int docId, double score, int chatId, int ordinal,
            List<SequenceLine> lines) {
        return new Hit(caseIndex, docId, score, iped.semantic.store.SemanticFields.KIND_SEQUENCE, chatId, ordinal, null,
                null, null, lines, null, null, null);
    }

    public double getScore() {
        return score;
    }

    public String getKind() {
        return kind;
    }

    public int getItemId() {
        return itemId;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public int getCaseIndex() {
        return caseIndex;
    }

    public int getDocId() {
        return docId;
    }

    public void setRanks(Integer semantic, Integer lexical) {
        this.semanticRank = semantic;
        this.lexicalRank = lexical;
    }

    /** Identity of the unit across the two lists of the hybrid mode. */
    long unitKey() {
        return ((long) caseIndex << 40) ^ ((long) docId & 0xFFFFFFFFFFL);
    }
}
