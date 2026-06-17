package iped.rcp.casecreation.wizard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;

import iped.rcp.core.i18n.Messages;
import iped.rcp.core.processing.DataSourceEntry;

/**
 * Wizard page 1 — evidence sources (FR-004). Add files/folders via native
 * dialogs; at least one source is required to advance.
 */
class SourcesPage extends WizardPage {

    private final List<Path> sources = new ArrayList<>();
    private org.eclipse.swt.widgets.List listWidget;

    SourcesPage() {
        super("sources");
        setTitle(Messages.getString("NewCaseWizard.sources.title"));
        setDescription(Messages.getString("NewCaseWizard.sources.description"));
    }

    @Override
    public void createControl(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(1, false));

        listWidget = new org.eclipse.swt.widgets.List(c, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.MULTI);
        listWidget.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite buttons = new Composite(c, SWT.NONE);
        buttons.setLayout(new GridLayout(3, true));
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));

        Button addFile = new Button(buttons, SWT.PUSH);
        addFile.setText(Messages.getString("NewCaseWizard.sources.addFile"));
        addFile.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        addFile.addListener(SWT.Selection, e -> {
            FileDialog dialog = new FileDialog(getShell(), SWT.OPEN | SWT.MULTI);
            if (dialog.open() != null) {
                String dir = dialog.getFilterPath();
                for (String name : dialog.getFileNames()) {
                    sources.add(Path.of(dir, name));
                }
                refresh();
            }
        });

        Button addFolder = new Button(buttons, SWT.PUSH);
        addFolder.setText(Messages.getString("NewCaseWizard.sources.addFolder"));
        addFolder.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        addFolder.addListener(SWT.Selection, e -> {
            DirectoryDialog dialog = new DirectoryDialog(getShell(), SWT.OPEN);
            String dir = dialog.open();
            if (dir != null) {
                sources.add(Path.of(dir));
                refresh();
            }
        });

        Button remove = new Button(buttons, SWT.PUSH);
        remove.setText(Messages.getString("NewCaseWizard.sources.remove"));
        remove.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        remove.addListener(SWT.Selection, e -> {
            int[] selected = listWidget.getSelectionIndices();
            Arrays.sort(selected);
            for (int i = selected.length - 1; i >= 0; i--) {
                sources.remove(selected[i]);
            }
            refresh();
        });

        setControl(c);
        setPageComplete(false);
    }

    private void refresh() {
        listWidget.removeAll();
        for (Path p : sources) {
            listWidget.add(p.toString());
        }
        setPageComplete(!sources.isEmpty());
    }

    /** @return the sources as model entries (path only; name/password are future UI). */
    List<DataSourceEntry> getSources() {
        List<DataSourceEntry> result = new ArrayList<>();
        for (Path p : sources) {
            result.add(DataSourceEntry.of(p));
        }
        return result;
    }
}
