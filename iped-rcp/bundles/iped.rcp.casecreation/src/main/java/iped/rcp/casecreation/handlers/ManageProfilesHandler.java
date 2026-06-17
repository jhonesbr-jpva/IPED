package iped.rcp.casecreation.handlers;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.swt.widgets.Display;

import iped.rcp.casecreation.profiles.ProfileManagerDialog;
import iped.rcp.core.processing.ProcessingLaunchService;
import iped.rcp.core.profiles.ProfileService;

/**
 * Handler of the File ▸ Manage Profiles command (feature 005, US3, T026,
 * contracts/profile-editor.contract.md). Opens the {@link ProfileManagerDialog};
 * the injected {@link ProcessingLaunchService} (OSGi DS) provides the
 * {@code profiles/} location (same source the New Case wizard uses).
 */
public class ManageProfilesHandler {

    @Execute
    public void execute(ProcessingLaunchService launchService) {
        ProfileManagerDialog dialog = new ProfileManagerDialog(Display.getDefault().getActiveShell(),
                new ProfileService(), launchService.profilesDir());
        dialog.open();
    }
}
