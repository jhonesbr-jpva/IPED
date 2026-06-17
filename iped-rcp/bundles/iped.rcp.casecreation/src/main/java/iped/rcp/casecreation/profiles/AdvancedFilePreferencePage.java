package iped.rcp.casecreation.profiles;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Text;

import iped.rcp.core.profiles.AdvancedFile;

/**
 * Eclipse-Preferences-style page for a non-{@code key=value} config file
 * ({@code .xml}/{@code .json}) of a profile (feature 005, US3): a raw-text
 * editor — "complete" coverage without a per-format visual parser
 * (contracts/profile-editor.contract.md). Editing marks the file overridden; the
 * dialog copies overridden files into {@code profiles/<name>/} on Save.
 */
public class AdvancedFilePreferencePage extends PreferencePage {

    private final AdvancedFile file;
    private Text text;

    public AdvancedFilePreferencePage(AdvancedFile file, String title) {
        this.file = file;
        setTitle(title);
        setDescription(file.fileName());
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite content = new Composite(parent, SWT.NONE);
        content.setLayout(new GridLayout(1, false));
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        text = new Text(content, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        text.setText(file.content());
        return content;
    }

    @Override
    public boolean performOk() {
        if (text != null && !text.isDisposed()) {
            file.setContent(text.getText());
        }
        return true;
    }

    @Override
    protected void performApply() {
        performOk();
    }

    @Override
    protected void performDefaults() {
        if (text != null && !text.isDisposed()) {
            text.setText(file.baseContent());
        }
        super.performDefaults();
    }
}
