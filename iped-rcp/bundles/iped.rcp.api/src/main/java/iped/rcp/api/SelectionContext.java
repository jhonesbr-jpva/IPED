package iped.rcp.api;

import java.util.List;

/**
 * Immutable snapshot of the current item selection, published through the e4
 * {@code ESelectionService} under the {@link UiEventTopics#SELECTION_KEY} key
 * and shared by all parts (results table, gallery, viewers, third-party
 * views).
 *
 * @param activeItem    the focused item (drives content viewers); may be
 *                      {@code null} when nothing is focused
 * @param selectedItems all selected items, in selection order; never
 *                      {@code null}, possibly empty, possibly large (iterate
 *                      lazily for bulk operations)
 * @param originPartId  e4 element id of the part that produced this
 *                      selection; parts must ignore selections they
 *                      originated to avoid echo loops
 *
 * @apiNote Provisional API — subject to change without deprecation cycle.
 */
public record SelectionContext(ItemId activeItem, List<ItemId> selectedItems, String originPartId) {

    public SelectionContext {
        selectedItems = selectedItems == null ? List.of() : List.copyOf(selectedItems);
    }
}
