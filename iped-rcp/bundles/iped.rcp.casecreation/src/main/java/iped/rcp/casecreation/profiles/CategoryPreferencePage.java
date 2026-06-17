package iped.rcp.casecreation.profiles;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;

/**
 * Placeholder page for a non-leaf category node in the profile editor tree
 * (feature 005, US3) — e.g. the {@code conf/} modules or the advanced
 * files group. It carries no settings; it just guides the user to a child file.
 */
public class CategoryPreferencePage extends PreferencePage {

    private final String hint;

    public CategoryPreferencePage(String title, String hint) {
        this.hint = hint;
        setTitle(title);
        noDefaultAndApplyButton();
    }

    @Override
    protected Control createContents(Composite parent) {
        Label label = new Label(parent, SWT.WRAP);
        label.setText(hint);
        label.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return label;
    }
}
