package iped.rcp.specialized.parts;

import java.awt.event.HierarchyEvent;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.lucene.search.Query;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bibliothek.gui.dock.common.DefaultSingleCDockable;
import iped.app.timelinegraph.IpedChartsPanel;
import iped.engine.search.LoadIndexFields;

/**
 * Timeline part (task T036, FR-012): hosts the EXISTING
 * {@link IpedChartsPanel} (JFreeChart stacked time histogram with its own
 * toolbar, legend and popup menus) through the part's SWT_AWT bridge,
 * initialized like the legacy App did against the shared mirror table.
 *
 * <p>
 * Zoom/interval selection filtering the listing (FR-012) flows through the
 * {@code IpedChartsPanel.GUIHost} seam: when the chart defines/clears its
 * interval and event filters, this part materializes
 * {@code IpedChartsPanel.getQuery()} into a workbench query-filter slot and
 * refreshes the active search - the RCP equivalent of the legacy
 * {@code AppListener.updateFileListing()}. Highlight/check workers keep
 * working against the mirror table (selection lands on the workbench via the
 * bridge; checked state goes through the bookmarks service).
 */
public class TimelinePart extends AbstractBridgedPart {

    public static final String HOST_WIDGET_ID = "iped.rcp.specialized.timeline.host";

    /** Query-filter slot of the timeline interval/event filters. */
    public static final String TIMELINE_FILTER_SLOT = "iped.rcp.specialized.timeline";

    private static final Logger LOGGER = LoggerFactory.getLogger(TimelinePart.class);

    @Override
    protected String hostWidgetId() {
        return HOST_WIDGET_ID;
    }

    @Override
    protected void createLegacyContent() {
        IpedChartsPanel chart = new IpedChartsPanel();
        chart.setGUIHost(new RcpTimelineHost(chart));
        chart.init(bridge.getMirrorTable(), bridge.getProviders(), bridge.getProviders());
        chart.setDockableContainer(new DefaultSingleCDockable(chart.getID()) {
            @Override
            public boolean isShowing() {
                return chart.isShowing();
            }
        });

        // legacy CDockableLocationListener discipline: first show starts the
        // time cache creation and renders the chart; later shows re-render
        // only when results changed while the tab was hidden
        AtomicBoolean cacheStarted = new AtomicBoolean();
        AtomicBoolean dirty = new AtomicBoolean();
        bridge.getMirrorTable().getModel().addTableModelListener(event -> {
            if (!chart.isShowing()) {
                dirty.set(true);
            }
        });
        chart.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0 || !chart.isShowing()) {
                return;
            }
            if (cacheStarted.compareAndSet(false, true)) {
                chart.refreshChart();
                new Thread(() -> chart.getIpedTimelineDatasetManager().startCacheCreation(), "timeline-cache-init")
                        .start();
                dirty.set(false);
            } else if (dirty.getAndSet(false)) {
                chart.refreshChart();
            }
        });

        bridgeHost.setContent(chart);
        // the part renders lazily: replay the active result so the chart's
        // fresh listeners see it (covers results that predate this part)
        bridge.refreshMirror();
    }

    /**
     * RCP side of the {@code IpedChartsPanel.GUIHost} seam. Methods are
     * called on the EDT by the legacy chart code; listing updates hop to a
     * workbench Job (search off the UI threads, constitution principle V).
     */
    private class RcpTimelineHost implements IpedChartsPanel.GUIHost {

        private final IpedChartsPanel chart;

        RcpTimelineHost(IpedChartsPanel chart) {
            this.chart = chart;
        }

        @Override
        public void updateFileListing() {
            if (filterState == null) {
                LOGGER.warn("No filter service available; timeline filters are not applied to the listing");
                return;
            }
            // composed on the EDT (cheap query building); null = no filter
            Query query = chart.getQuery();
            String label = chart.getTitle();
            Job job = Job.create("timeline-filter", monitor -> {
                try {
                    if (query == null) {
                        filterState.clear(TIMELINE_FILTER_SLOT);
                    } else {
                        filterState.setQueryFilter(TIMELINE_FILTER_SLOT, query, label);
                    }
                    searchService.refresh();
                    return Status.OK_STATUS;
                } catch (RuntimeException e) {
                    LOGGER.error("Error applying the timeline filter", e);
                    return Status.error(e.getMessage(), e);
                }
            });
            job.setSystem(true);
            job.schedule();
        }

        @Override
        public void updateFilterColors() {
            // legacy App tinted the dock chrome; the RCP filters panel shows
            // the active-filters label instead - nothing to do here
        }

        @Override
        public String[] getIndexedFieldNames() {
            return LoadIndexFields.getFields(sessionManager.getSession().getSource().getAtomicSources());
        }
    }
}
