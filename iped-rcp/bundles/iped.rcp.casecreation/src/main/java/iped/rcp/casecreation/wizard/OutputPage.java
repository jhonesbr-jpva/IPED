package iped.rcp.casecreation.wizard;

import java.nio.file.Path;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import iped.rcp.core.i18n.Messages;
import iped.rcp.core.processing.ProcessingMode;

/**
 * Wizard page 2 — output folder and processing mode (FR-005/FR-009). Deeper
 * checks (writable, not inside a source, mode/case consistency) run at finish
 * via {@code BootstrapCommandBuilder.validate}.
 */
class OutputPage extends WizardPage {

    private Text outputText;
    private Combo modeCombo;

    OutputPage() {
        super("output");
        setTitle(Messages.getString("NewCaseWizard.output.title"));
        setDescription(Messages.getString("NewCaseWizard.output.description"));
    }

    @Override
    public void createControl(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(3, false));

        new Label(c, SWT.NONE).setText(Messages.getString("NewCaseWizard.output.label"));
        outputText = new Text(c, SWT.BORDER | SWT.SINGLE);
        outputText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        outputText.addModifyListener(e -> validate());

        Button browse = new Button(c, SWT.PUSH);
        browse.setText(Messages.getString("NewCaseWizard.output.browse"));
        browse.addListener(SWT.Selection, e -> {
            DirectoryDialog dialog = new DirectoryDialog(getShell(), SWT.OPEN);
            String dir = dialog.open();
            if (dir != null) {
                outputText.setText(dir);
            }
        });

        new Label(c, SWT.NONE).setText(Messages.getString("NewCaseWizard.output.mode"));
        modeCombo = new Combo(c, SWT.READ_ONLY);
        modeCombo.setItems(Messages.getString("NewCaseWizard.output.mode.new"),
                Messages.getString("NewCaseWizard.output.mode.append"),
                Messages.getString("NewCaseWizard.output.mode.continue"),
                Messages.getString("NewCaseWizard.output.mode.restart"));
        modeCombo.select(0);
        GridData modeData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        modeData.horizontalSpan = 2;
        modeCombo.setLayoutData(modeData);

        setControl(c);
        validate();
    }

    private void validate() {
        setPageComplete(!outputText.getText().isBlank());
    }

    Path getOutputDir() {
        String text = outputText.getText().trim();
        return text.isEmpty() ? null : Path.of(text);
    }

    ProcessingMode getMode() {
        return switch (modeCombo.getSelectionIndex()) {
            case 1 -> ProcessingMode.APPEND;
            case 2 -> ProcessingMode.CONTINUE;
            case 3 -> ProcessingMode.RESTART;
            default -> ProcessingMode.NEW;
        };
    }
}
