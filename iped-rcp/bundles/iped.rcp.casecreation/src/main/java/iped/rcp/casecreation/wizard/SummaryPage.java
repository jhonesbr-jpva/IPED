package iped.rcp.casecreation.wizard;

import java.util.List;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;

import iped.rcp.core.i18n.Messages;
import iped.rcp.core.processing.BootstrapCommandBuilder;
import iped.rcp.core.processing.NewCaseRequest;

/**
 * Wizard page 5 — read-only review of the resulting command (FR-007 summary
 * step). Shows the validation errors instead, if any remain when the page is
 * displayed, so the investigator sees why Finish would fail.
 */
class SummaryPage extends WizardPage {

    private final NewCaseWizard wizard;
    private Text preview;

    SummaryPage(NewCaseWizard wizard) {
        super("summary");
        this.wizard = wizard;
        setTitle(Messages.getString("NewCaseWizard.summary.title"));
        setDescription(Messages.getString("NewCaseWizard.summary.description"));
    }

    @Override
    public void createControl(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(1, false));
        preview = new Text(c, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
        preview.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        setControl(c);
        setPageComplete(true);
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible) {
            NewCaseRequest request = wizard.buildRequest();
            List<String> errors = BootstrapCommandBuilder.validate(request);
            if (errors.isEmpty()) {
                preview.setText(String.join(" ", BootstrapCommandBuilder.buildArgs(request)));
            } else {
                preview.setText(String.join(System.lineSeparator(), errors));
            }
        }
    }
}
