package iped.rcp.casecreation.wizard;

import java.util.List;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import iped.rcp.core.i18n.Messages;
import iped.rcp.core.profiles.ProfileDescriptor;

/**
 * Wizard page 3 — processing profile (FR-006). Lists built-in and user
 * profiles discovered under {@code profiles/}; {@code forensic} is preselected
 * (the current default).
 */
class ProfilePage extends WizardPage {

    private static final String DEFAULT_PROFILE = "forensic";

    private final List<ProfileDescriptor> profiles;
    private Combo combo;

    ProfilePage(List<ProfileDescriptor> profiles) {
        super("profile");
        this.profiles = profiles;
        setTitle(Messages.getString("NewCaseWizard.profile.title"));
        setDescription(Messages.getString("NewCaseWizard.profile.description"));
    }

    @Override
    public void createControl(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(2, false));

        new Label(c, SWT.NONE).setText(Messages.getString("NewCaseWizard.profile.label"));
        combo = new Combo(c, SWT.READ_ONLY);
        combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        int defaultIndex = -1;
        for (int i = 0; i < profiles.size(); i++) {
            combo.add(profiles.get(i).name());
            if (DEFAULT_PROFILE.equals(profiles.get(i).name())) {
                defaultIndex = i;
            }
        }
        if (!profiles.isEmpty()) {
            combo.select(defaultIndex >= 0 ? defaultIndex : 0);
        }
        combo.addListener(SWT.Selection, e -> validate());

        setControl(c);
        validate();
    }

    private void validate() {
        setPageComplete(combo.getSelectionIndex() >= 0);
    }

    String getProfileName() {
        int index = combo.getSelectionIndex();
        return index >= 0 ? combo.getItem(index) : null;
    }
}
