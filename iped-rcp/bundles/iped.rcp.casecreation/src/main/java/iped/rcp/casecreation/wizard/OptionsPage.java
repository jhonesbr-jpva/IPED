package iped.rcp.casecreation.wizard;

import java.nio.file.Path;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import iped.rcp.core.i18n.Messages;
import iped.rcp.core.processing.CommonOptions;

/**
 * Wizard page 4 — common (tier-B) options plus timezone and keyword list
 * (FR-007 curated subset). All optional, so the page is always complete; rare
 * flags stay CLI/config-only (contracts/new-case-wizard.contract.md).
 */
class OptionsPage extends WizardPage {

    private Button addOwner;
    private Button portable;
    private Button noPstAttachs;
    private Button noLinkedItems;
    private Button downloadInternetData;
    private Text timezone;
    private Text keywords;

    OptionsPage() {
        super("options");
        setTitle(Messages.getString("NewCaseWizard.options.title"));
        setDescription(Messages.getString("NewCaseWizard.options.description"));
    }

    @Override
    public void createControl(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(3, false));

        addOwner = check(c, "NewCaseWizard.options.addowner");
        portable = check(c, "NewCaseWizard.options.portable");
        noPstAttachs = check(c, "NewCaseWizard.options.nopstattachs");
        noLinkedItems = check(c, "NewCaseWizard.options.nolinkeditems");
        downloadInternetData = check(c, "NewCaseWizard.options.download");

        new Label(c, SWT.NONE).setText(Messages.getString("NewCaseWizard.options.timezone"));
        timezone = new Text(c, SWT.BORDER | SWT.SINGLE);
        GridData tzData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        tzData.horizontalSpan = 2;
        timezone.setLayoutData(tzData);

        new Label(c, SWT.NONE).setText(Messages.getString("NewCaseWizard.options.keywords"));
        keywords = new Text(c, SWT.BORDER | SWT.SINGLE);
        keywords.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button browse = new Button(c, SWT.PUSH);
        browse.setText(Messages.getString("NewCaseWizard.options.browse"));
        browse.addListener(SWT.Selection, e -> {
            org.eclipse.swt.widgets.FileDialog dialog = new org.eclipse.swt.widgets.FileDialog(getShell(), SWT.OPEN);
            String file = dialog.open();
            if (file != null) {
                keywords.setText(file);
            }
        });

        setControl(c);
        setPageComplete(true);
    }

    private Button check(Composite parent, String key) {
        Button button = new Button(parent, SWT.CHECK);
        button.setText(Messages.getString(key));
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.horizontalSpan = 3;
        button.setLayoutData(data);
        return button;
    }

    CommonOptions getCommonOptions() {
        return new CommonOptions(addOwner.getSelection(), portable.getSelection(), noPstAttachs.getSelection(),
                noLinkedItems.getSelection(), downloadInternetData.getSelection());
    }

    String getTimezone() {
        String text = timezone.getText().trim();
        return text.isEmpty() ? null : text;
    }

    Path getKeywordsFile() {
        String text = keywords.getText().trim();
        return text.isEmpty() ? null : Path.of(text);
    }
}
