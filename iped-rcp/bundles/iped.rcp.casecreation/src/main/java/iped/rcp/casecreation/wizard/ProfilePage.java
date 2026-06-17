package iped.rcp.casecreation.wizard;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import iped.rcp.casecreation.profiles.ProfileManagerDialog;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.profiles.ProfileDescriptor;
import iped.rcp.core.profiles.ProfileService;

/**
 * Wizard page 3 — processing profile (FR-006). Lists built-in and user
 * profiles discovered under {@code profiles/}; {@code forensic} is preselected
 * (the current default). A "Manage Profiles…" shortcut opens the
 * {@link ProfileManagerDialog} and re-scans the list on close, so a profile
 * just created/edited is immediately selectable (FR-017, T028).
 */
class ProfilePage extends WizardPage {

    private static final String DEFAULT_PROFILE = "forensic";

    private final ProfileService service;
    private final Path profilesDir;
    private Combo combo;

    ProfilePage(ProfileService service, Path profilesDir) {
        super("profile");
        this.service = service;
        this.profilesDir = profilesDir;
        setTitle(Messages.getString("NewCaseWizard.profile.title"));
        setDescription(Messages.getString("NewCaseWizard.profile.description"));
    }

    @Override
    public void createControl(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(3, false));

        new Label(c, SWT.NONE).setText(Messages.getString("NewCaseWizard.profile.label"));
        combo = new Combo(c, SWT.READ_ONLY);
        combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        combo.addListener(SWT.Selection, e -> validate());

        Button manage = new Button(c, SWT.PUSH);
        manage.setText(Messages.getString("NewCaseWizard.profile.manage"));
        manage.addListener(SWT.Selection, e -> openManager());

        reload(DEFAULT_PROFILE);

        setControl(c);
        validate();
    }

    /** Opens the profile manager, then refreshes the list keeping the current selection (FR-017). */
    private void openManager() {
        String previous = getProfileName();
        new ProfileManagerDialog(combo.getShell(), service, profilesDir).open();
        reload(previous != null ? previous : DEFAULT_PROFILE);
        validate();
    }

    /** (Re)loads the profile list into the combo, selecting {@code preferred} if present. */
    private void reload(String preferred) {
        List<ProfileDescriptor> profiles = service.listProfiles(profilesDir);
        combo.removeAll();
        int preferredIndex = -1;
        for (int i = 0; i < profiles.size(); i++) {
            combo.add(profiles.get(i).name());
            if (profiles.get(i).name().equals(preferred)) {
                preferredIndex = i;
            }
        }
        if (!profiles.isEmpty()) {
            combo.select(preferredIndex >= 0 ? preferredIndex : 0);
        }
    }

    private void validate() {
        setPageComplete(combo.getSelectionIndex() >= 0);
    }

    String getProfileName() {
        int index = combo.getSelectionIndex();
        return index >= 0 ? combo.getItem(index) : null;
    }
}
