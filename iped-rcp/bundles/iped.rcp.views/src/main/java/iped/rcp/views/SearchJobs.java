package iped.rcp.views;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.rcp.core.i18n.Messages;
import iped.rcp.core.search.SearchService;

/**
 * Shared refresh discipline of the filter parts (US2): every filter mutation
 * re-runs the active query through the engine in a background Job (legacy
 * {@code updateFileListing()}, Principle V — never on the UI thread).
 */
public final class SearchJobs {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchJobs.class);

    private SearchJobs() {
    }

    public static void refresh(SearchService searchService) {
        Job job = Job.create(Messages.getString("SearchBar.Searching"), (IProgressMonitor monitor) -> {
            try {
                searchService.refresh();
                return Status.OK_STATUS;
            } catch (IllegalStateException e) {
                // no case session open: nothing to refresh
                return Status.OK_STATUS;
            } catch (RuntimeException e) {
                LOGGER.error("Filtered search failed", e);
                return Status.error(e.getMessage(), e);
            }
        });
        job.setUser(false);
        job.schedule();
    }
}
