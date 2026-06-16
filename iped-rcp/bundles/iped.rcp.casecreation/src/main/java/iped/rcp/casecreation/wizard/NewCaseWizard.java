package iped.rcp.casecreation.wizard;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.wizard.Wizard;

import iped.rcp.core.i18n.Messages;
import iped.rcp.core.processing.AdvancedOptions;
import iped.rcp.core.processing.BootstrapCommandBuilder;
import iped.rcp.core.processing.LaunchException;
import iped.rcp.core.processing.NewCaseRequest;
import iped.rcp.core.processing.ProcessingLaunchService;
import iped.rcp.core.profiles.ProfileDescriptor;
import iped.rcp.core.profiles.ProfileService;

/**
 * New Case wizard (feature 005, US1, T013/T015): collects the evidence
 * sources, output folder + mode, processing profile and common options, then
 * launches processing out-of-process via {@link ProcessingLaunchService}
 * (the SWT progress window of feature 004 shows the run). Validation reuses
 * {@link BootstrapCommandBuilder} so the UI rejects invalid submissions before
 * any process is spawned (FR-008/SC-003).
 *
 * <p>
 * Near-live open of the created case (FR-011) is offered through the File menu
 * once US2's Open Case path lands (same open mechanism); this wizard launches
 * and informs.
 */
public class NewCaseWizard extends Wizard {

    private final ProcessingLaunchService launchService;
    private final ProfileService profileService;
    private final Path profilesDir;

    private SourcesPage sourcesPage;
    private OutputPage outputPage;
    private ProfilePage profilePage;
    private OptionsPage optionsPage;

    public NewCaseWizard(ProcessingLaunchService launchService, ProfileService profileService, Path profilesDir) {
        this.launchService = launchService;
        this.profileService = profileService;
        this.profilesDir = profilesDir;
        setWindowTitle(Messages.getString("NewCaseWizard.title"));
        setNeedsProgressMonitor(false);
    }

    @Override
    public void addPages() {
        List<ProfileDescriptor> profiles = profileService.listProfiles(profilesDir);
        sourcesPage = new SourcesPage();
        outputPage = new OutputPage();
        profilePage = new ProfilePage(profiles);
        optionsPage = new OptionsPage();
        addPage(sourcesPage);
        addPage(outputPage);
        addPage(profilePage);
        addPage(optionsPage);
        addPage(new SummaryPage(this));
    }

    /** Assembles the frozen request from the page values. */
    NewCaseRequest buildRequest() {
        return new NewCaseRequest(sourcesPage.getSources(), outputPage.getOutputDir(), profilePage.getProfileName(),
                outputPage.getMode(), optionsPage.getTimezone(), optionsPage.getKeywordsFile(),
                optionsPage.getCommonOptions(), AdvancedOptions.none(), Map.of());
    }

    @Override
    public boolean performFinish() {
        NewCaseRequest request = buildRequest();
        List<String> errors = BootstrapCommandBuilder.validate(request);
        if (!errors.isEmpty()) {
            MessageDialog.openError(getShell(), Messages.getString("NewCaseWizard.invalid.title"),
                    String.join("\n", errors));
            return false;
        }
        try {
            launchService.launch(request);
        } catch (LaunchException e) {
            MessageDialog.openError(getShell(), Messages.getString("NewCaseWizard.error.title"), e.getMessage());
            return false;
        }
        MessageDialog.openInformation(getShell(), Messages.getString("NewCaseWizard.started.title"),
                Messages.getString("NewCaseWizard.started.message"));
        return true;
    }
}
