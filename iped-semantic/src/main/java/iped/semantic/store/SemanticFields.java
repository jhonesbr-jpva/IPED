package iped.semantic.store;

/**
 * Field names of the semantic indexes (contracts/case-storage.md). They are part of the case
 * contract from the first processed case on: renaming one breaks every case prepared before.
 */
public final class SemanticFields {

    /** {@code passage}, {@code transcript}, {@code sequence}, {@code image}, {@code video_frame}, {@code status}. */
    public static final String KIND = "kind";
    /** Id in the main index; for a sequence, the chat's id. */
    public static final String ITEM_ID = "itemId";
    /** The same id as a term, for {@code updateDocuments}. Not stored. */
    public static final String ITEM_ID_KEY = "itemIdKey";
    /**
     * Ids through which the document belongs to a scope: the item itself, or, for a sequence, the
     * messages that compose it (a sequence enters a scope when at least one of its messages is in
     * it, research D7). Multi-valued doc values; not stored.
     */
    public static final String ANCHOR_IDS = "anchorIds";
    public static final String ORDINAL = "ordinal";
    public static final String CHAR_START = "charStart";
    public static final String CHAR_END = "charEnd";
    /** Literal passage text: stored and indexed (hybrid mode). */
    public static final String TEXT = "text";
    /** Text of a sequence's lines, indexed only (hybrid mode). */
    public static final String SEQ_TEXT = "seqText";
    public static final String MESSAGE_IDS = "messageIds";
    /** One stored JSON object per line: {sender, date, text}. */
    public static final String LINES = "lines";
    public static final String FRAME_INDEX = "frameIndex";
    public static final String FRAME_COUNT = "frameCount";
    public static final String INSTANT_APPROX_MS = "instantApproxMs";
    public static final String REASON = "reason";
    public static final String DETAIL = "detail";
    /** Normalised vector, {@code DOT_PRODUCT}. */
    public static final String VECTOR = "v";

    public static final String KIND_PASSAGE = "passage";
    public static final String KIND_TRANSCRIPT = "transcript";
    public static final String KIND_SEQUENCE = "sequence";
    public static final String KIND_IMAGE = "image";
    public static final String KIND_VIDEO_FRAME = "video_frame";
    public static final String KIND_STATUS = "status";

    private SemanticFields() {
    }
}
