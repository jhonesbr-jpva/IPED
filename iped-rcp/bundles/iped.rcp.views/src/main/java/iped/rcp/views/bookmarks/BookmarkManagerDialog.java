package iped.rcp.views.bookmarks;

import java.util.List;
import java.util.function.Supplier;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.ColorDialog;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import iped.rcp.api.ItemId;
import iped.rcp.core.bookmarks.BookmarkService;
import iped.rcp.core.i18n.Messages;
import iped.utils.LocalizedFormat;

/**
 * Bookmark management dialog (task T021, FR-005/FR-014, parity
 * {@code BookmarksManager}): create, rename, delete, color, comment, and
 * add/remove the current selection to/from bookmarks, all through
 * {@link BookmarkService} (current on-disk format; writes publish
 * {@code bookmarks/CHANGED}). Keyboard-shortcut binding of the legacy dialog
 * arrives with US5 (T046).
 */
public class BookmarkManagerDialog extends Dialog {

    private final BookmarkService bookmarks;
    private final Supplier<List<ItemId>> selectionSupplier;

    private Table bookmarksTable;

    public BookmarkManagerDialog(Shell parentShell, BookmarkService bookmarks,
            Supplier<List<ItemId>> selectionSupplier) {
        super(parentShell);
        this.bookmarks = bookmarks;
        this.selectionSupplier = selectionSupplier;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(Messages.getString("BookmarksManager.Title"));
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite content = new Composite(area, SWT.NONE);
        content.setLayout(new GridLayout(2, false));
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        bookmarksTable = new Table(content, SWT.BORDER | SWT.MULTI | SWT.FULL_SELECTION);
        bookmarksTable.setHeaderVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.heightHint = 350;
        tableData.widthHint = 420;
        bookmarksTable.setLayoutData(tableData);

        TableColumn nameColumn = new TableColumn(bookmarksTable, SWT.LEFT);
        nameColumn.setText(Messages.getString("BookmarksManager.Edit.Name"));
        nameColumn.setWidth(200);
        TableColumn countColumn = new TableColumn(bookmarksTable, SWT.RIGHT);
        countColumn.setText("#");
        countColumn.setWidth(80);
        TableColumn commentColumn = new TableColumn(bookmarksTable, SWT.LEFT);
        commentColumn.setText(Messages.getString("BookmarksManager.CommentsTooltip"));
        commentColumn.setWidth(220);

        Composite buttons = new Composite(content, SWT.NONE);
        buttons.setLayout(new GridLayout(1, true));
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));

        addButton(buttons, "BookmarksManager.New", event -> newBookmark());
        addButton(buttons, "BookmarksManager.Edit", event -> renameBookmark());
        addButton(buttons, "BookmarksManager.Edit.Color", event -> changeColor());
        addButton(buttons, "BookmarksManager.Update", event -> editComment());
        addButton(buttons, "BookmarksManager.Delete", event -> deleteBookmarks());
        addButton(buttons, "BookmarksManager.Add", event -> addSelection());
        addButton(buttons, "BookmarksManager.Remove", event -> removeSelection());

        refresh();
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.CLOSE_LABEL, true);
    }

    private void addButton(Composite parent, String key, org.eclipse.swt.widgets.Listener listener) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(Messages.getString(key));
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button.addListener(SWT.Selection, listener);
    }

    private void refresh() {
        bookmarksTable.removeAll();
        for (String name : bookmarks.getBookmarkNames()) {
            TableItem item = new TableItem(bookmarksTable, SWT.NONE);
            item.setText(0, name);
            item.setText(1, LocalizedFormat.format(bookmarks.getBookmarkCount(name)));
            item.setText(2, bookmarks.getComment(name).orElse(""));
        }
    }

    private void newBookmark() {
        InputDialog input = new InputDialog(getShell(), Messages.getString("BookmarksManager.New"),
                Messages.getString("BookmarksManager.NewBookmark.Tip"), "", name -> validateNewName(name));
        if (input.open() == OK) {
            bookmarks.createBookmark(input.getValue().trim());
            refresh();
        }
    }

    private String validateNewName(String name) {
        if (name == null || name.isBlank()) {
            return Messages.getString("BookmarksManager.NewBookmark.Tip");
        }
        if (bookmarks.getBookmarkNames().contains(name.trim())) {
            return Messages.getString("BookmarksManager.AlreadyExists");
        }
        return null;
    }

    private void renameBookmark() {
        String selected = singleSelection();
        if (selected == null) {
            return;
        }
        InputDialog input = new InputDialog(getShell(), Messages.getString("BookmarksManager.Edit.Title"),
                Messages.getString("BookmarksManager.Edit.Name"), selected, name -> validateNewName(name));
        if (input.open() == OK) {
            bookmarks.renameBookmark(selected, input.getValue().trim());
            refresh();
        }
    }

    private void changeColor() {
        String selected = singleSelection();
        if (selected == null) {
            return;
        }
        ColorDialog colorDialog = new ColorDialog(getShell());
        colorDialog.setText(Messages.getString("BookmarksManager.Edit.Color"));
        bookmarks.getColor(selected)
                .ifPresent(rgb -> colorDialog.setRGB(new RGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)));
        RGB chosen = colorDialog.open();
        if (chosen != null) {
            bookmarks.setColor(selected, (chosen.red << 16) | (chosen.green << 8) | chosen.blue);
        }
    }

    private void editComment() {
        String selected = singleSelection();
        if (selected == null) {
            return;
        }
        InputDialog input = new InputDialog(getShell(), Messages.getString("BookmarksManager.CommentsTooltip"),
                selected, bookmarks.getComment(selected).orElse(""), null);
        if (input.open() == OK) {
            bookmarks.setComment(selected, input.getValue());
            refresh();
        }
    }

    private void deleteBookmarks() {
        TableItem[] selection = bookmarksTable.getSelection();
        if (selection.length == 0) {
            MessageDialog.openInformation(getShell(), Messages.getString("BookmarksManager.Title"),
                    Messages.getString("BookmarksManager.AlertNoSelectedBookmarks"));
            return;
        }
        if (!MessageDialog.openConfirm(getShell(), Messages.getString("BookmarksManager.ConfirmDelTitle"),
                Messages.getString("BookmarksManager.ConfirmDelete"))) {
            return;
        }
        for (TableItem item : selection) {
            bookmarks.deleteBookmark(item.getText(0));
        }
        refresh();
    }

    private void addSelection() {
        applyToSelectedBookmarks(true);
    }

    private void removeSelection() {
        applyToSelectedBookmarks(false);
    }

    private void applyToSelectedBookmarks(boolean add) {
        TableItem[] selection = bookmarksTable.getSelection();
        if (selection.length == 0) {
            MessageDialog.openInformation(getShell(), Messages.getString("BookmarksManager.Title"),
                    Messages.getString("BookmarksManager.AlertNoSelectedBookmarks"));
            return;
        }
        List<ItemId> items = selectionSupplier.get();
        if (items.isEmpty()) {
            MessageDialog.openInformation(getShell(), Messages.getString("BookmarksManager.Title"),
                    Messages.getString("BookmarksManager.AlertNoHighlightedItems"));
            return;
        }
        for (TableItem bookmarkItem : selection) {
            String name = bookmarkItem.getText(0);
            if (add) {
                bookmarks.addToBookmark(name, items);
            } else {
                bookmarks.removeFromBookmark(name, items);
            }
        }
        refresh();
    }

    private String singleSelection() {
        TableItem[] selection = bookmarksTable.getSelection();
        if (selection.length != 1) {
            MessageDialog.openInformation(getShell(), Messages.getString("BookmarksManager.Title"),
                    Messages.getString("BookmarksManager.AlertMultipleSelectedBookmarks"));
            return null;
        }
        return selection[0].getText(0);
    }
}
