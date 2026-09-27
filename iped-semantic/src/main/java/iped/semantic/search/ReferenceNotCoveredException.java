package iped.semantic.search;

import iped.semantic.store.CoverageReason;

/**
 * "More like this" from an item that is not within reach of semantic search (FR-013, scenario 3 of
 * story 4): the refusal carries the item's own reason (E7), for the UI to show and the MCP to return
 * as {@code REFERENCE_NOT_COVERED}.
 */
public class ReferenceNotCoveredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int itemId;
    private final CoverageReason reason;

    public ReferenceNotCoveredException(int itemId, CoverageReason reason) {
        super("item " + itemId + " cannot be a reference: " + reason);
        this.itemId = itemId;
        this.reason = reason;
    }

    public int getItemId() {
        return itemId;
    }

    public CoverageReason getReason() {
        return reason;
    }
}
