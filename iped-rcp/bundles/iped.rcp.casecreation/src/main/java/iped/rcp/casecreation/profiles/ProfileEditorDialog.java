package iped.rcp.casecreation.profiles;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import iped.rcp.core.i18n.Messages;
import iped.rcp.core.profiles.AdvancedFile;
import iped.rcp.core.profiles.ConfigFileGroup;
import iped.rcp.core.profiles.ProfileConfigModel;
import iped.rcp.core.profiles.ProfileDescriptor;
import iped.rcp.core.profiles.ProfileService;
import iped.rcp.core.profiles.ProfileValidation;

/**
 * Full, file-driven profile editor (feature 005, US3, T027,
 * contracts/profile-editor.contract.md). The left list holds every config file
 * of the profile (key=value groups then advanced {@code .xml}/{@code .json}
 * files); selecting one shows its editor on the right — a {@link ConfigOptionGrid}
 * for groups, a raw-text editor for advanced files.
 *
 * <p>
 * Built-in profiles are read-only templates (FR-018): they can be edited
 * in-memory but the primary action is "Save As…", which writes a new user
 * profile. User profiles save in place. Only overridden options/files are
 * persisted (lean profiles), via {@link ProfileService#saveModel}.
 */
public class ProfileEditorDialog extends TitleAreaDialog {

    private final ProfileService service;
    private final Path profilesDir;
    private final ProfileDescriptor descriptor;
    private final ProfileConfigModel model;

    private final Map<String, Control> editors = new LinkedHashMap<>();
    private StackLayout stackLayout;
    private Composite editorStack;
    private String savedProfileName;

    public ProfileEditorDialog(Shell parentShell, ProfileService service, Path profilesDir,
            ProfileDescriptor descriptor) {
        super(parentShell);
        this.service = service;
        this.profilesDir = profilesDir;
        this.descriptor = descriptor;
        this.model = service.loadModel(profilesDir, descriptor.name());
        setHelpAvailable(false);
    }

    /** @return the profile written on a successful save (the same name, or the Save-As name). */
    public String savedProfileName() {
        return savedProfileName;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(Messages.getString("ProfileEditor.shell.title", descriptor.name()));
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Point getInitialSize() {
        return new Point(820, 560);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle(Messages.getString("ProfileEditor.title", descriptor.name()));
        setMessage(descriptor.isBuiltIn() ? Messages.getString("ProfileEditor.message.builtin")
                : Messages.getString("ProfileEditor.message.user"));

        Composite area = (Composite) super.createDialogArea(parent);
        SashForm sash = new SashForm(area, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        List fileList = new List(sash, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);

        editorStack = new Composite(sash, SWT.NONE);
        stackLayout = new StackLayout();
        editorStack.setLayout(stackLayout);

        buildEditors(fileList);
        sash.setWeights(new int[] { 1, 3 });

        fileList.addListener(SWT.Selection, e -> showEditor(fileList.getSelection()));
        if (fileList.getItemCount() > 0) {
            fileList.select(0);
            showEditor(fileList.getSelection());
        }
        return area;
    }

    /** Creates one editor control per config file and lists their names. */
    private void buildEditors(List fileList) {
        for (ConfigFileGroup group : model.groups()) {
            ConfigOptionGrid grid = new ConfigOptionGrid(editorStack, SWT.NONE);
            grid.setGroup(group);
            editors.put(group.fileName(), grid);
            fileList.add(group.fileName());
        }
        for (AdvancedFile file : model.advancedFiles()) {
            Text text = new Text(editorStack,
                    SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
            text.setText(file.content());
            text.addModifyListener(e -> file.setContent(text.getText()));
            editors.put(file.fileName(), text);
            fileList.add(file.fileName());
        }
    }

    private void showEditor(String[] selection) {
        if (selection.length == 0) {
            return;
        }
        Control control = editors.get(selection[0]);
        if (control != null) {
            stackLayout.topControl = control;
            editorStack.layout();
        }
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        String okLabel = descriptor.isBuiltIn() ? Messages.getString("ProfileEditor.button.saveAs")
                : Messages.getString("ProfileEditor.button.save");
        createButton(parent, IDialogConstants.OK_ID, okLabel, true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed() {
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
        super.okPressed();
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
}
