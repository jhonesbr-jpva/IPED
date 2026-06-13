package iped.rcp.views.handlers;

import java.util.List;
import java.util.function.Supplier;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.swt.widgets.Display;

import iped.rcp.api.ItemId;
import iped.rcp.api.SelectionContext;
import iped.rcp.api.UiEventTopics;
import iped.rcp.core.bookmarks.BookmarkService;
import iped.rcp.views.bookmarks.BookmarkManagerDialog;

/**
 * Window-level handler of the Manage Bookmarks command — bound to Ctrl+B
 * (task T046, FR-021; legacy global shortcut of {@code App}). Opens the same
 * dialog as the table context menu, reading the live selection from the
 * shared application-context key, so the shortcut works wherever the focus
 * is, like the legacy {@code KeyEventDispatcher}.
 */
public class ManageBookmarksHandler {

    @Execute
    public void execute(IEclipseContext context, BookmarkService bookmarks) {
        Supplier<List<ItemId>> selectionSupplier = () -> {
            Object selection = context.get(UiEventTopics.SELECTION_KEY);
            return selection instanceof SelectionContext sc ? sc.selectedItems() : List.of();
        };
        new BookmarkManagerDialog(Display.getDefault().getActiveShell(), bookmarks, selectionSupplier).open();
    }
}
