package iped.rcp.views.similarity;

import org.apache.lucene.search.Query;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IItem;
import iped.engine.data.IPEDMultiSource;
import iped.engine.search.SimilarFacesSearch;
import iped.rcp.api.ItemId;
import iped.rcp.core.filters.FilterStateService;
import iped.rcp.core.filters.SimilarityFilters;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.search.SearchService;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.ICaseSessionManager;

/**
 * Similarity search actions of the results-table context menu (task T032,
 * FR-013/SI-01..03): wire the engine similarity searches into the
 * {@link FilterStateService} slots, mirroring the legacy
 * {@code SimilarImagesFilterActions}/{@code SimilarFacesFilterActions}/
 * {@code MenuListener.similarDocs} flows for the highlighted item.
 */
public final class SimilarityActions {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimilarityActions.class);

    private static final int DEFAULT_DOC_SIMILARITY_PERCENT = 70; // legacy MenuListener default

    private SimilarityActions() {
    }

    /**
     * Similar images from the highlighted item (legacy "Current item"): no-op
     * when the item has no similarity features, then score-descending sort —
     * both legacy {@code SimilarImagesFilterActions} behaviors.
     */
    public static void searchSimilarImages(Shell shell, ItemId reference, ICaseSessionManager sessionManager,
            FilterStateService filterState, SearchService searchService, UISynchronize uiSync) {
        runWithItem(shell, reference, sessionManager, uiSync, (source, item) -> {
            Query query = SimilarityFilters.similarImagesQuery(item).getQuery();
            if (query == null) {
                LOGGER.info("Item {} has no image similarity features, filter not applied", item.getName());
                return;
            }
            filterState.setQueryFilter(FilterStateService.SIMILAR_IMAGES, query,
                    Messages.getString("FilterValue.SimilarImage") + " " + item.getName());
            filterState.setResultSetFilter(FilterStateService.SIMILAR_IMAGES,
                    SimilarityFilters.similarImagesRescore(source, item),
                    Messages.getString("FilterValue.SimilarImage") + " " + item.getName());
            searchService.refresh();
            searchService.sortBy(iped.rcp.core.search.ResultSorter.FIELD_SCORE, false);
        });
    }

    /** Similar faces from the highlighted item (legacy "Current item"). */
    public static void searchSimilarFaces(Shell shell, ItemId reference, ICaseSessionManager sessionManager,
            FilterStateService filterState, SearchService searchService, UISynchronize uiSync) {
        runWithItem(shell, reference, sessionManager, uiSync, (source, item) -> {
            if (item.getExtraAttribute(SimilarFacesSearch.FACE_FEATURES) == null) {
                LOGGER.info("Item {} has no face features, filter not applied", item.getName());
                return;
            }
            filterState.setResultSetFilter(FilterStateService.SIMILAR_FACES,
                    SimilarityFilters.similarFaces(source, item),
                    Messages.getString("FilterValue.SimilarFace") + " " + item.getName());
            searchService.refresh();
        });
    }

    /**
     * Similar documents from the highlighted item, asking the match percent
     * like the legacy menu (needs stored term vectors).
     */
    public static void searchSimilarDocuments(Shell shell, ItemId reference, ICaseSessionManager sessionManager,
            FilterStateService filterState, SearchService searchService, UISynchronize uiSync) {
        InputDialog dialog = new InputDialog(shell, Messages.getString("MenuClass.FindSimilarDocs"),
                Messages.getString("MenuListener.SimilarityLabel"), String.valueOf(DEFAULT_DOC_SIMILARITY_PERCENT),
                text -> {
                    try {
                        int value = Integer.parseInt(text.trim());
                        return value >= 0 && value <= 100 ? null : "";
                    } catch (NumberFormatException e) {
                        return "";
                    }
                });
        if (dialog.open() != Window.OK) {
            return;
        }
        int percent = Integer.parseInt(dialog.getValue().trim());
        runWithItem(shell, reference, sessionManager, uiSync, (source, item) -> {
            Query query = SimilarityFilters
                    .similarDocument(source, new iped.engine.data.ItemId(reference.sourceId(), reference.id()),
                            percent)
                    .getQuery();
            if (query == null) {
                LOGGER.info("Similar document query unavailable (term vectors not stored?)");
                return;
            }
            filterState.setQueryFilter(FilterStateService.SIMILAR_DOCUMENT, query,
                    Messages.getString("FilterValue.SimilarDocument") + " (" + percent + "%): " + item.getName());
            searchService.refresh();
        });
    }

    private interface ItemAction {
        void run(IPEDMultiSource source, IItem item) throws Exception;
    }

    /** Item resolution + engine search run in a Job (Principle V). */
    private static void runWithItem(Shell shell, ItemId reference, ICaseSessionManager sessionManager,
            UISynchronize uiSync, ItemAction action) {
        if (reference == null) {
            return;
        }
        Job job = Job.create(Messages.getString("SearchBar.Searching"), (IProgressMonitor monitor) -> {
            try {
                CaseSession session = sessionManager.getSession();
                if (session == null) {
                    return Status.OK_STATUS;
                }
                IPEDMultiSource source = session.getSource();
                IItem item = source.getItemByItemId(
                        new iped.engine.data.ItemId(reference.sourceId(), reference.id()));
                if (item == null) {
                    return Status.OK_STATUS;
                }
                action.run(source, item);
                return Status.OK_STATUS;
            } catch (Exception e) {
                LOGGER.error("Similarity search failed", e);
                return Status.error(e.getMessage(), e);
            }
        });
        job.setUser(true);
        job.schedule();
    }

}
