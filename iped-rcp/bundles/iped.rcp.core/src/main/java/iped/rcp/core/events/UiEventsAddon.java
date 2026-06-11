package iped.rcp.core.events;

import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.services.events.IEventBroker;
import org.eclipse.e4.ui.workbench.modeling.ESelectionService;
import org.eclipse.e4.ui.workbench.modeling.ISelectionListener;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.rcp.api.SelectionContext;
import iped.rcp.api.UiEventTopics;

/**
 * e4 model addon wiring the selection contract (task T012, data-model
 * "SelectionContext"). Contributed by {@code Application.e4xmi}, so it lives
 * in the application context.
 *
 * <p>
 * Parts publish their selection through the standard e4 mechanism
 * ({@code ESelectionService.setSelection(new SelectionContext(...))}); this
 * addon mirrors every {@link SelectionContext} selection into the
 * application {@link IEclipseContext} under
 * {@link UiEventTopics#SELECTION_KEY}, so any part — including third-party
 * ones (ui-extension-api contract) — can simply inject
 * {@code @Named(UiEventTopics.SELECTION_KEY) @Optional SelectionContext} and
 * be re-injected on every change, regardless of which part is active.
 *
 * <p>
 * On {@link UiEventTopics#CASE_CLOSED} the mirrored selection is cleared:
 * a selection referencing items of a disposed session must not outlive it.
 */
public class UiEventsAddon {

    private static final Logger LOGGER = LoggerFactory.getLogger(UiEventsAddon.class);

    private IEclipseContext context;
    private ESelectionService selectionService;
    private IEventBroker eventBroker;

    private final ISelectionListener selectionListener = (part, selection) -> {
        if (selection instanceof SelectionContext selectionContext) {
            context.set(UiEventTopics.SELECTION_KEY, selectionContext);
        }
    };

    private final EventHandler caseClosedHandler = event -> context.set(UiEventTopics.SELECTION_KEY, null);

    @jakarta.annotation.PostConstruct
    void init(IEclipseContext context, ESelectionService selectionService, IEventBroker eventBroker) {
        this.context = context;
        this.selectionService = selectionService;
        this.eventBroker = eventBroker;
        selectionService.addSelectionListener(selectionListener);
        eventBroker.subscribe(UiEventTopics.CASE_CLOSED, caseClosedHandler);
        LOGGER.debug("Selection/event wiring installed");
    }

    @jakarta.annotation.PreDestroy
    void dispose() {
        if (selectionService != null) {
            selectionService.removeSelectionListener(selectionListener);
        }
        if (eventBroker != null) {
            eventBroker.unsubscribe(caseClosedHandler);
        }
    }
}
