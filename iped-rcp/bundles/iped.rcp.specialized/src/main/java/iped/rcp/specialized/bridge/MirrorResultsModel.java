package iped.rcp.specialized.bridge;

import javax.swing.table.AbstractTableModel;

import iped.data.IItemId;
import iped.rcp.api.ItemId;
import iped.rcp.core.bookmarks.BookmarkService;
import iped.rcp.core.search.ResultSet;
import iped.rcp.core.search.SearchService;

/**
 * Swing table model mirroring the ACTIVE workbench result set for the
 * bridged legacy views (task T033 plumbing). Reproduces the two columns of
 * the legacy results table the specialized views rely on: column 0 = 1-based
 * row number, column 1 = checked state (lives in the bookmarks model, same
 * discipline as the legacy {@code ResultTableModel} and the SWT results
 * table of US1).
 *
 * <p>
 * EDT-confined like any Swing model; the backing {@link ResultSet} snapshot
 * is immutable and swapped atomically by {@link SearchService}, so reads are
 * bounds-checked against the row count the JTable last saw.
 */
public class MirrorResultsModel extends AbstractTableModel {

    private static final long serialVersionUID = 1L;

    private final transient SearchService searchService;
    private final transient BookmarkService bookmarkService;

    public MirrorResultsModel(SearchService searchService, BookmarkService bookmarkService) {
        this.searchService = searchService;
        this.bookmarkService = bookmarkService;
    }

    @Override
    public int getRowCount() {
        ResultSet current = searchService.getCurrent();
        return current == null ? 0 : current.size();
    }

    @Override
    public int getColumnCount() {
        return 2;
    }

    @Override
    public String getColumnName(int column) {
        return column == 0 ? "" : "checked";
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return column == 0 ? Integer.class : Boolean.class;
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return column == 1;
    }

    @Override
    public Object getValueAt(int row, int column) {
        if (column == 0) {
            return row + 1;
        }
        IItemId item = itemAt(row);
        return item != null && bookmarkService.isChecked(toApiId(item));
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        if (column != 1 || !(value instanceof Boolean checked)) {
            return;
        }
        IItemId item = itemAt(row);
        if (item != null) {
            // single write path of the UI layer: saves asynchronously and
            // publishes bookmarks/CHANGED (FR-005)
            bookmarkService.setChecked(toApiId(item), checked);
            fireTableCellUpdated(row, column);
        }
    }

    /** The engine item at a row of the active result, or null out of range. */
    public IItemId itemAt(int row) {
        ResultSet current = searchService.getCurrent();
        if (current == null || row < 0 || row >= current.size()) {
            return null;
        }
        return current.result().getItem(row);
    }

    private static ItemId toApiId(IItemId itemId) {
        return new ItemId(itemId.getSourceId(), itemId.getId());
    }
}
