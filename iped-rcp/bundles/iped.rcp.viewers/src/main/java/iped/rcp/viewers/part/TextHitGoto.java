package iped.rcp.viewers.part;

/**
 * Cross-part "navigate the text viewer to this occurrence" command, published by
 * {@link HitsViewerPart} and consumed by {@link TextViewerPart} through the e4
 * application context (key {@link #KEY}). The {@code offset} is the character
 * position of the match in the extracted text (identical in both parts because
 * they share {@link TextExtraction}/{@link TextHitFinder}). The {@code nonce}
 * makes each click a distinct value so clicking the same row twice still fires
 * the context-change injection.
 */
record TextHitGoto(int offset, long nonce) {

    /** Application-context key carrying the latest {@link TextHitGoto}. */
    static final String KEY = "iped.rcp.viewers.texthitgoto"; //$NON-NLS-1$
}
