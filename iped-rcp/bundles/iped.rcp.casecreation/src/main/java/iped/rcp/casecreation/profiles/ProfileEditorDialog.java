package iped.rcp.casecreation.profiles;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceNode;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.jface.preference.PreferenceManager;
import org.eclipse.jface.preference.PreferenceNode;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import iped.rcp.core.i18n.Messages;
import iped.rcp.core.profiles.AdvancedFile;
import iped.rcp.core.profiles.ConfigFileGroup;
import iped.rcp.core.profiles.ConfigOption;
import iped.rcp.core.profiles.ProfileConfigModel;
import iped.rcp.core.profiles.ProfileDescriptor;
import iped.rcp.core.profiles.ProfileService;
import iped.rcp.core.profiles.ProfileValidation;

/**
 * Full, file-driven profile editor in the Eclipse-Preferences idiom (feature
 * 005, US3, contracts/profile-editor.contract.md): a category tree on the
 * left (root files, the {@code conf/} modules, the advanced files) and a typed
 * page per config file on the right ({@link ConfigFilePreferencePage} with
 * checkboxes/fields, {@link AdvancedFilePreferencePage} for {@code .xml}/
 * {@code .json}). Each page's <em>Restore Defaults</em> drops that file's
 * overrides.
 *
 * <p>
 * Built-in profiles are read-only templates (FR-018): the primary action is
 * "Save As…", which writes a new user profile. User profiles save in place.
 * Only overridden options/files are persisted (lean profiles), gated by
 * {@link ProfileValidation}. The headless model/service are unchanged — this is
 * purely the presentation layer.
 */
public class ProfileEditorDialog extends PreferenceDialog {

    private final ProfileService service;
    private final Path profilesDir;
    private final ProfileDescriptor descriptor;
    private final ProfileConfigModel model;
    private String savedProfileName;

    public ProfileEditorDialog(Shell parentShell, ProfileService service, Path profilesDir,
            ProfileDescriptor descriptor) {
        this(parentShell, service, profilesDir, descriptor, service.loadModel(profilesDir, descriptor.name()));
    }

    private ProfileEditorDialog(Shell parentShell, ProfileService service, Path profilesDir,
            ProfileDescriptor descriptor, ProfileConfigModel model) {
        super(parentShell, buildManager(model));
        this.service = service;
        this.profilesDir = profilesDir;
        this.descriptor = descriptor;
        this.model = model;
    }

    /** @return the profile written on a successful save (the same name, or the Save-As name). */
    public String savedProfileName() {
        return savedProfileName;
    }

    /** Builds the preference tree: root files, a {@code conf/} category, and an advanced-files category. */
    private static PreferenceManager buildManager(ProfileConfigModel model) {
        PreferenceManager manager = new PreferenceManager();

        for (ConfigFileGroup group : model.groups()) {
            if (!group.fileName().contains("/")) {
                manager.addToRoot(new PreferenceNode(group.fileName(), new ConfigFilePreferencePage(group,
                        group.fileName())));
            }
        }

        List<ConfigFileGroup> confGroups = model.groups().stream().filter(g -> g.fileName().contains("/")).toList();
        if (!confGroups.isEmpty()) {
            // node ids must NOT contain '.' — PreferenceManager.addTo() treats it as a path separator
            PreferenceNode confCategory = new PreferenceNode("confCategory", new CategoryPreferencePage(
                    Messages.getString("ProfileEditor.category.conf"), Messages.getString("ProfileEditor.category.hint")));
            manager.addToRoot(confCategory);
            for (ConfigFileGroup group : confGroups) {
                manager.addTo(confCategory.getId(),
                        new PreferenceNode(group.fileName(), new ConfigFilePreferencePage(group, lastSegment(group.fileName()))));
            }
        }

        if (!model.advancedFiles().isEmpty()) {
            PreferenceNode advCategory = new PreferenceNode("advancedCategory", new CategoryPreferencePage(
                    Messages.getString("ProfileEditor.category.advanced"),
                    Messages.getString("ProfileEditor.category.hint")));
            manager.addToRoot(advCategory);
            for (AdvancedFile file : model.advancedFiles()) {
                manager.addTo(advCategory.getId(),
                        new PreferenceNode(file.fileName(), new AdvancedFilePreferencePage(file, lastSegment(file.fileName()))));
            }
        }
        return manager;
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        // (Object) forces the varargs formatting overload, not getString(bundle, key)
        shell.setText(Messages.getString(
                descriptor.isBuiltIn() ? "ProfileEditor.shell.title.builtin" : "ProfileEditor.shell.title",
                (Object) descriptor.name()));
    }

    @Override
    protected Point getInitialSize() {
        return new Point(860, 600);
    }

    /** Adds a filter field above the standard category tree (find an option by key/description/file). */
    @Override
    protected Control createTreeAreaContents(Composite parent) {
        Composite composite = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        composite.setLayout(layout);
        GridData compositeData = new GridData(GridData.FILL_VERTICAL);
        compositeData.widthHint = 220;
        composite.setLayoutData(compositeData);

        Text filter = new Text(composite, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL | SWT.BORDER);
        filter.setMessage(Messages.getString("ProfileEditor.filter.message"));
        filter.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Control tree = super.createTreeAreaContents(composite);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        OptionTreeFilter optionFilter = new OptionTreeFilter();
        getTreeViewer().addFilter(optionFilter);
        filter.addModifyListener(e -> {
            optionFilter.setPattern(filter.getText());
            getTreeViewer().refresh();
            if (!filter.getText().isBlank()) {
                getTreeViewer().expandAll();
            }
        });
        return composite;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        super.createButtonsForButtonBar(parent);
        Button ok = getButton(IDialogConstants.OK_ID);
        if (ok != null) {
            ok.setText(Messages.getString(
                    descriptor.isBuiltIn() ? "ProfileEditor.button.saveAs" : "ProfileEditor.button.save"));
        }
    }

    @Override
    protected void okPressed() {
        flushCreatedPages();

        ProfileValidation validation = ProfileValidation.validate(model);
        if (!validation.isValid()) {
            MessageDialog.openError(getShell(), Messages.getString("ProfileEditor.error.title"),
                    String.join("\n", validation.errors()));
            return;
        }

        String targetName = descriptor.isBuiltIn() ? promptSaveAsName() : descriptor.name();
        if (targetName == null) {
            return; // user canceled the Save-As prompt
        }
        try {
            service.saveModel(profilesDir, targetName, model);
            savedProfileName = targetName;
        } catch (RuntimeException ex) {
            MessageDialog.openError(getShell(), Messages.getString("ProfileEditor.error.title"),
                    ex.getMessage() != null ? ex.getMessage() : ex.toString());
            return;
        }
        setReturnCode(Window.OK);
        close();
    }

    /** Flushes the controls of every page the user actually opened into the model. */
    private void flushCreatedPages() {
        for (Object element : getPreferenceManager().getElements(PreferenceManager.PRE_ORDER)) {
            IPreferenceNode node = (IPreferenceNode) element;
            if (node.getPage() != null) {
                node.getPage().performOk();
            }
        }
    }

    /** Prompts for a new (valid, non-colliding) user-profile name for Save As (FR-018/FR-019). */
    private String promptSaveAsName() {
        IInputValidator validator = input -> {
            if (input == null || input.isBlank() || !service.isValidProfileName(input)) {
                return Messages.getString("ProfileManager.error.invalidName");
            }
            if (service.profileExists(profilesDir, input)) {
                return Messages.getString("ProfileManager.error.exists");
            }
            return null;
        };
        InputDialog dialog = new InputDialog(getShell(), Messages.getString("ProfileEditor.saveAs.title"),
                Messages.getString("ProfileEditor.saveAs.message"), descriptor.name() + "-copy", validator);
        return dialog.open() == Window.OK ? dialog.getValue() : null;
    }

    /**
     * Filters the category tree by the search text: a config-file node matches
     * when its name, or any of its options' key/description, contains the text; a
     * category node is kept when any of its children match.
     */
    private final class OptionTreeFilter extends ViewerFilter {

        private String pattern = "";

        void setPattern(String text) {
            pattern = text == null ? "" : text.trim().toLowerCase();
        }

        @Override
        public boolean select(Viewer viewer, Object parentElement, Object element) {
            return pattern.isEmpty() || matches((IPreferenceNode) element);
        }

        private boolean matches(IPreferenceNode node) {
            ConfigFileGroup group = model.groups().stream().filter(g -> g.fileName().equals(node.getId())).findFirst()
                    .orElse(null);
            if (group != null) {
                if (group.fileName().toLowerCase().contains(pattern)) {
                    return true;
                }
                for (ConfigOption option : group.options()) {
                    if (option.key().toLowerCase().contains(pattern) || (option.description() != null
                            && option.description().toLowerCase().contains(pattern))) {
                        return true;
                    }
                }
                return false;
            }
            AdvancedFile advanced = model.advancedFiles().stream().filter(f -> f.fileName().equals(node.getId()))
                    .findFirst().orElse(null);
            if (advanced != null) {
                return advanced.fileName().toLowerCase().contains(pattern);
            }
            for (IPreferenceNode child : node.getSubNodes()) {
                if (matches(child)) {
                    return true;
                }
            }
            return false;
        }
    }
}
