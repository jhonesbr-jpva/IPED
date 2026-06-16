package iped.rcp.casecreation.handlers;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.widgets.Display;

import iped.rcp.casecreation.wizard.NewCaseWizard;
import iped.rcp.core.processing.ProcessingLaunchService;
import iped.rcp.core.profiles.ProfileService;

/**
 * Handler of the File ▸ New Case command (feature 005, T014,
 * contracts/case-menu-commands.contract.md). Opens the {@link NewCaseWizard};
 * the injected {@link ProcessingLaunchService} (OSGi DS) provides both the
 * out-of-process launch and the {@code profiles/} location.
 */
public class NewCaseHandler {

    @Execute
    public void execute(ProcessingLaunchService launchService) {
        NewCaseWizard wizard = new NewCaseWizard(launchService, new ProfileService(), launchService.profilesDir());
        WizardDialog dialog = new WizardDialog(Display.getDefault().getActiveShell(), wizard);
        dialog.open();
    }
}
