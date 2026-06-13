package iped.rcp.sample.view;

import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import iped.rcp.api.ICaseSessionService;
import iped.rcp.api.IItemAccessService;
import iped.rcp.api.ItemId;
import iped.rcp.api.SelectionContext;
import iped.rcp.api.UiEventTopics;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Sample third-party view (T051, US6, SC-007): displays the current item
 * selection. It exists to prove the provisional extension contract
 * ({@code specs/004-rcp-gui-migration/contracts/ui-extension-api.contract.md}):
 *
 * <ul>
 * <li>it compiles against {@code iped.rcp.api} ONLY (no engine, no first-party
 * RCP bundle);</li>
 * <li>it is contributed through a model fragment ({@code fragment.e4xmi}) to a
 * stable anchor ({@link iped.rcp.api.ModelAnchors#PART_STACK_RIGHT});</li>
 * <li>it receives the API services and the shared selection by DI.</li>
 * </ul>
 *
 * <p>
 * The API services are injected as {@link Optional} so the part is robust if a
 * service is momentarily unavailable. Heavy I/O through {@link IItemAccessService}
 * should be offloaded to a {@code Job} by real extensions; this sample reads
 * only the (cheap) item name on the UI thread to stay minimal.
 */
public class SelectionView {

    /** Stable element id of this part (matches {@code fragment.e4xmi}). */
    public static final String PART_ID = "iped.rcp.sample.view.part";

    /** SWTBot id of the label, looked up by the drop-in test (T048). */
    public static final String LABEL_WIDGET_ID = "iped.rcp.sample.view.label";

    /** SWT data key SWTBot uses to find widgets (same convention as the parts). */
    public static final String SWTBOT_KEY = "org.eclipse.swtbot.widget.key";

    @Inject
    @Optional
    private ICaseSessionService caseService;

    @Inject
    @Optional
    private IItemAccessService itemAccess;

    private Label label;

    @PostConstruct
    public void create(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        label = new Label(parent, SWT.WRAP);
        label.setData(SWTBOT_KEY, LABEL_WIDGET_ID);
        label.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        render(null);
    }

    /** Shared selection (ESelectionService key); reacts as any first-party part does. */
    @Inject
    @Optional
    public void onSelectionChanged(@Named(UiEventTopics.SELECTION_KEY) @Optional SelectionContext selection) {
        render(selection);
    }

    private void render(SelectionContext selection) {
        if (label == null || label.isDisposed()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append(caseService != null && caseService.isOpen() ? "Case open" : "No case open").append('\n');

        if (selection == null || selection.selectedItems().isEmpty()) {
            text.append("No selection");
        } else {
            text.append(selection.selectedItems().size()).append(" item(s) selected");
            ItemId active = selection.activeItem();
            if (active != null) {
                String name = itemAccess != null ? itemAccess.getName(active).orElse("?") : "?";
                text.append('\n').append("Active: ").append(name)
                        .append(" [src=").append(active.sourceId())
                        .append(", id=").append(active.id()).append(']');
            }
        }
        label.setText(text.toString());
        label.requestLayout();
    }
}
