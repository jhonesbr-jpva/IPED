package iped.rcp.views;

import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.search.Query;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.swt.widgets.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItemId;
import iped.engine.data.IPEDMultiSource;
import iped.engine.search.IPEDSearcher;
import iped.engine.search.MultiSearchResult;
import iped.rcp.core.bookmarks.BookmarkService;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.search.ResultSet;
import iped.rcp.core.session.ICaseSessionManager;

/**
 * Checked-state keyboard actions of the results table (task T046, FR-021) —
 * ports of the legacy {@code ResultTableListener} shortcuts:
 *
 * <ul>
 * <li>space: toggle the checked state of the highlighted rows (target value =
 * inverse of the FIRST highlighted row, legacy semantics);</li>
 * <li>Ctrl/Alt+R/P/F/D: check/uncheck the highlighted rows AND their
 * subitems / parent / referenced items / referencing items — the related set
 * comes from the exact aux-table queries ({@link RelatedItemsQueries}), like
 * the legacy actions reuse the aux table models.</li>
 * </ul>
 *
 * <p>
 * Bulk writes go through {@link BookmarkService#setCheckedEngine} (single
 * save + single {@code bookmarks/CHANGED}); related-set resolution runs in a
 * Job off the UI thread (Principle V) and the table repaints at the end (the
 * virtual rows re-read the checked state on {@code SWT.SetData}).
 */
public final class CheckActions {

    /** Related-set flavor of the check-with-related shortcuts. */
    public enum Related {
        SUBITEMS, PARENT, REFERENCING, REFERENCED_BY
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CheckActions.class);

    private CheckActions() {
    }

    /** Space: toggle the highlighted rows (legacy first-row semantics). */
    public static void toggleChecked(Table table, ResultSet current, BookmarkService bookmarks,
            UISynchronize uiSync) {
        int[] rows = table.getSelectionIndices();
        if (rows.length == 0 || current == null) {
            return;
        }
        IItemId first = current.result().getItem(rows[0]);
        boolean target = !bookmarks.isChecked(toApiId(first));
        List<IItemId> ids = new ArrayList<>(rows.length);
        for (int row : rows) {
            if (row < current.size()) {
                ids.add(current.result().getItem(row));
            }
        }
        bookmarks.setCheckedEngine(ids, target);
        repaint(table, uiSync);
    }

    /**
     * Ctrl/Alt+R/P/F/D: applies {@code value} to the highlighted rows and to
     * every item related to them per {@code related}.
     */
    public static void checkWithRelated(Table table, ResultSet current, Related related, boolean value,
            ICaseSessionManager sessionManager, BookmarkService bookmarks, UISynchronize uiSync) {
        int[] rows = table.getSelectionIndices();
        if (rows.length == 0 || current == null) {
            return;
        }
        List<IItemId> selected = new ArrayList<>(rows.length);
        for (int row : rows) {
            if (row < current.size()) {
                selected.add(current.result().getItem(row));
            }
        }
        Job job = Job.create(Messages.getString("App.Wait"), (IProgressMonitor monitor) -> {
            try {
                IPEDMultiSource source = sessionManager.getSession().getSource();
                List<IItemId> all = new ArrayList<>(selected);
                for (IItemId itemId : selected) {
                    Document doc = source.getReader().document(source.getLuceneId(itemId));
                    Query query = switch (related) {
                        case SUBITEMS -> RelatedItemsQueries.subitems(source, doc);
                        case PARENT -> RelatedItemsQueries.parent(source, doc);
                        case REFERENCING -> RelatedItemsQueries.referencing(source, doc);
                        case REFERENCED_BY -> RelatedItemsQueries.referencedBy(source, doc);
                    };
                    if (query == null) {
                        continue;
                    }
                    // same path as the legacy itemSelectionAndResultsByQuery:
                    // plain engine search, no query rewrite
                    IPEDSearcher searcher = new IPEDSearcher(source, query);
                    searcher.setRewritequery(false);
                    MultiSearchResult result = searcher.multiSearch();
                    for (int i = 0; i < result.getLength(); i++) {
                        all.add(result.getItem(i));
                    }
                }
                bookmarks.setCheckedEngine(all, value);
                repaint(table, uiSync);
                return Status.OK_STATUS;
            } catch (Exception e) {
                LOGGER.error("Check-with-related ({}) failed", related, e);
                return Status.error(e.getMessage(), e);
            }
        });
        job.setUser(true);
        job.schedule();
    }

    private static void repaint(Table table, UISynchronize uiSync) {
        uiSync.asyncExec(() -> {
            if (!table.isDisposed()) {
                table.clearAll();
            }
        });
    }

    private static iped.rcp.api.ItemId toApiId(IItemId itemId) {
        return new iped.rcp.api.ItemId(itemId.getSourceId(), itemId.getId());
    }
}
