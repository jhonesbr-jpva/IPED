package iped.semantic.store;

/**
 * Why an item of a modality is, or is not, within reach of semantic search (data-model E7). Every
 * code names something true about the item, never a guess from a list: an item without text is
 * {@link #NO_TEXT} unless an analysis that would have produced text was off in that processing, and
 * then it is {@link #ANALYSIS_NOT_RUN}.
 *
 * <p>
 * The {@code code} is what the semantic index stores in the {@code status} documents, and is
 * stable: renaming one would make old cases report a reason this version does not know. The
 * {@code ordinal()} is not persisted anywhere and may change.
 * </p>
 */
public enum CoverageReason {

    COVERED,
    /** Covered up to {@code maxTextCharsPerItem}; the excess character count goes in {@code detail}. */
    COVERED_TRUNCATED,
    NO_TEXT,
    /** The item would have text if OCR or transcription had run in this processing. */
    ANALYSIS_NOT_RUN,
    IMAGE_TOO_SMALL,
    IMAGE_UNDECODABLE,
    NO_THUMBNAIL,
    VIDEO_NO_FRAMES,
    /** A chat whose messages were prepared as conversation sequences. */
    COVERED_BY_MESSAGES,
    /** A video frame extracted as a subitem while videos are prepared from the source video. */
    COVERED_BY_SOURCE_VIDEO,
    CATEGORY_NOT_SELECTED,
    REMOTE_UNAVAILABLE,
    /** Appended evidence whose model fingerprint does not match the case's (research D16). */
    MODEL_MISMATCH,
    PREP_FAILURE,
    /**
     * The modality or key was off when the item was processed. Derived from the build record when
     * the whole modality was off; stored by the visual preparation for the items of a key that was
     * off while the other was on (images on and videos off, or the reverse).
     */
    MODALITY_OFF,
    /** Derived, never stored: the item predates the capability in this case (appended case). */
    BEFORE_CAPABILITY;

    private static final String MESSAGE_PREFIX = "SemanticSearch.Reason.";

    /** Stable code stored in the index and returned by the MCP tools. */
    public String code() {
        return name();
    }

    /** Key of the localized label in {@code iped-desktop-messages*.properties}. */
    public String messageKey() {
        return MESSAGE_PREFIX + name();
    }

    /** Covered items count as covered in {@code Coverage}; every other code is a not-covered reason. */
    public boolean isCovered() {
        return this == COVERED || this == COVERED_TRUNCATED;
    }

    /** Reasons the build record can give an item that has no status document (research D12). */
    public boolean isDerived() {
        return this == MODALITY_OFF || this == BEFORE_CAPABILITY;
    }

    /**
     * Parses a stored code.
     *
     * @return the reason, or {@code null} for a code this version does not know
     */
    public static CoverageReason fromCode(String code) {
        if (code == null) {
            return null;
        }
        try {
            return valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
