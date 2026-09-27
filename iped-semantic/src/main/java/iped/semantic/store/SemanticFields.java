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
    /**
     * Literal passage text, stored and indexed; for a sequence, the text of its lines, indexed only
     * (what is shown comes from {@link #LINES}). One field, so BM25 ranks both units on one scale.
     */
    public static final String TEXT = "text";
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
    /**
     * Focus window (data-model E3b): its normalised vector as little-endian floats in binary doc
     * values. Not a KNN field: focus windows are never searched as units, only compared inside the
     * item of an entry, so they need no graph.
     */
    public static final String FOCUS_VECTOR = "vf";
    /** Focus window: its literal text, stored only (never in the word part of the hybrid mode). */
    public static final String FOCUS_TEXT = "focusText";
    /** Focus window: ordinal of the passage its first character belongs to. */
    public static final String PASSAGE_ORDINAL = "passageOrdinal";

    public static final String KIND_PASSAGE = "passage";
    public static final String KIND_TRANSCRIPT = "transcript";
    public static final String KIND_SEQUENCE = "sequence";
    public static final String KIND_IMAGE = "image";
    public static final String KIND_VIDEO_FRAME = "video_frame";
    public static final String KIND_STATUS = "status";
    /** A focus window of an item's text, and of its transcript (data-model E3b, research D23). */
    public static final String KIND_FOCUS = "focus";
    public static final String KIND_TRANSCRIPT_FOCUS = "transcript_focus";

    /** Whether documents of this kind are focus windows, which are never units of an ordering. */
    public static boolean isFocus(String kind) {
        return KIND_FOCUS.equals(kind) || KIND_TRANSCRIPT_FOCUS.equals(kind);
    }

    /** The focus kind for passages of {@code kind}: {@code passage} or {@code transcript}. */
    public static String focusOf(String kind) {
        return KIND_TRANSCRIPT.equals(kind) ? KIND_TRANSCRIPT_FOCUS : KIND_FOCUS;
    }

    private SemanticFields() {
    }
}
