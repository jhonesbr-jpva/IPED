package iped.rcp.casecreation.profiles;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

import iped.rcp.core.i18n.Messages;
import iped.rcp.core.profiles.ProfileDescriptor;
import iped.rcp.core.profiles.ProfileService;

/**
 * Profile manager dialog (feature 005, US3, T026): lists the discovered
 * profiles and lets the investigator create, clone and delete user profiles.
 * Built-in profiles are read-only templates (FR-018) — only cloning is offered
 * for them; delete is disabled. New/cloned profiles appear immediately in the
 * New Case wizard (FR-017) because the wizard re-scans {@code profiles/} on open.
 *
 * <p>
 * Editing a profile's options is the {@code ProfileEditorDialog} (T027); this
 * dialog is the entry point that lists and manages the profile set.
 */
public class ProfileManagerDialog extends TitleAreaDialog {

    private final ProfileService service;
    private final Path profilesDir;

    private TableViewer viewer;
    private Button editButton;
    private Button cloneButton;
    private Button deleteButton;

    public ProfileManagerDialog(Shell parentShell, ProfileService service, Path profilesDir) {
        super(parentShell);
        this.service = service;
        this.profilesDir = profilesDir;
        setHelpAvailable(false);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(Messages.getString("ProfileManager.shell.title"));
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Point getInitialSize() {
        return new Point(560, 420);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle(Messages.getString("ProfileManager.title"));
        setMessage(Messages.getString("ProfileManager.message"));

        Composite area = (Composite) super.createDialogArea(parent);
        Composite content = new Composite(area, SWT.NONE);
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        content.setLayout(new GridLayout(2, false));

        createTable(content);
        createButtons(content);

        refresh();
        return area;
    }

    private void createTable(Composite parent) {
        viewer = new TableViewer(parent, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        TableViewerColumn nameColumn = new TableViewerColumn(viewer, SWT.NONE);
        nameColumn.getColumn().setText(Messages.getString("ProfileManager.column.name"));
        nameColumn.getColumn().setWidth(320);
        nameColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((ProfileDescriptor) element).name();
            }
        });

        TableViewerColumn kindColumn = new TableViewerColumn(viewer, SWT.NONE);
        kindColumn.getColumn().setText(Messages.getString("ProfileManager.column.kind"));
        kindColumn.getColumn().setWidth(120);
        kindColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((ProfileDescriptor) element).isBuiltIn() ? Messages.getString("ProfileManager.kind.builtin")
                        : Messages.getString("ProfileManager.kind.user");
            }
        });

        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.addSelectionChangedListener(e -> updateButtonsState());
        viewer.addDoubleClickListener(e -> onEdit());
    }

    private void createButtons(Composite parent) {
        Composite buttons = new Composite(parent, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, true));
        buttons.setLayout(new GridLayout(1, false));

        Button newButton = new Button(buttons, SWT.PUSH);
        newButton.setText(Messages.getString("ProfileManager.button.new"));
        newButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        newButton.addListener(SWT.Selection, e -> onNew());

        editButton = new Button(buttons, SWT.PUSH);
        editButton.setText(Messages.getString("ProfileManager.button.edit"));
        editButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        editButton.addListener(SWT.Selection, e -> onEdit());

        cloneButton = new Button(buttons, SWT.PUSH);
        cloneButton.setText(Messages.getString("ProfileManager.button.clone"));
        cloneButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cloneButton.addListener(SWT.Selection, e -> onClone());

        deleteButton = new Button(buttons, SWT.PUSH);
        deleteButton.setText(Messages.getString("ProfileManager.button.delete"));
        deleteButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        deleteButton.addListener(SWT.Selection, e -> onDelete());
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        // a manager dialog only needs a single Close button
        createButton(parent, IDialogConstants.CLOSE_ID, IDialogConstants.CLOSE_LABEL, true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == IDialogConstants.CLOSE_ID) {
            setReturnCode(OK);
            close();
        } else {
            super.buttonPressed(buttonId);
        }
    }

    private void onNew() {
        String name = promptName(Messages.getString("ProfileManager.new.title"),
                Messages.getString("ProfileManager.new.message"), "");
        if (name != null) {
            createAndSelect(name, null);
        }
    }

    private void onEdit() {
        ProfileDescriptor selected = selectedProfile();
        if (selected == null) {
            return;
        }
        ProfileEditorDialog editor = new ProfileEditorDialog(getShell(), service, profilesDir, selected);
        editor.open();
        String saved = editor.savedProfileName();
        if (saved != null) {
            refresh();
            selectByName(saved);
        }
    }

    private void onClone() {
        ProfileDescriptor selected = selectedProfile();
        if (selected == null) {
            return;
        }
        String name = promptName(Messages.getString("ProfileManager.clone.title"),
                Messages.getString("ProfileManager.clone.message", (Object) selected.name()), selected.name() + "-copy");
        if (name != null) {
            createAndSelect(name, selected.name());
        }
    }

    private void onDelete() {
        ProfileDescriptor selected = selectedProfile();
        if (selected == null || selected.isBuiltIn()) {
            return;
        }
        boolean confirmed = MessageDialog.openConfirm(getShell(),
                Messages.getString("ProfileManager.delete.title"),
                Messages.getString("ProfileManager.delete.confirm", (Object) selected.name()));
        if (confirmed) {
            try {
                service.deleteProfile(profilesDir, selected.name());
                refresh();
            } catch (RuntimeException ex) {
                showError(ex);
            }
        }
    }

    private void createAndSelect(String name, String basedOn) {
        try {
            service.createProfile(profilesDir, name, basedOn);
            refresh();
            selectByName(name);
        } catch (RuntimeException ex) {
            showError(ex);
        }
    }

    /** Opens an {@link InputDialog} that validates the name against syntax and collisions (FR-019). */
    private String promptName(String title, String message, String initial) {
        IInputValidator validator = input -> {
            if (input == null || input.isBlank() || !service.isValidProfileName(input)) {
                return Messages.getString("ProfileManager.error.invalidName");
            }
            if (service.profileExists(profilesDir, input)) {
                return Messages.getString("ProfileManager.error.exists");
            }
            return null;
        };
        InputDialog dialog = new InputDialog(getShell(), title, message, initial, validator);
        return dialog.open() == Window.OK ? dialog.getValue() : null;
    }

    private ProfileDescriptor selectedProfile() {
        IStructuredSelection selection = viewer.getStructuredSelection();
        return selection.isEmpty() ? null : (ProfileDescriptor) selection.getFirstElement();
    }

    private void refresh() {
        List<ProfileDescriptor> profiles = service.listProfiles(profilesDir);
        viewer.setInput(profiles);
        updateButtonsState();
    }

    private void selectByName(String name) {
        for (Object element : (List<?>) viewer.getInput()) {
            ProfileDescriptor descriptor = (ProfileDescriptor) element;
            if (descriptor.name().equals(name)) {
                viewer.setSelection(new StructuredSelection(descriptor), true);
                return;
            }
        }
    }

    private void updateButtonsState() {
        ProfileDescriptor selected = selectedProfile();
        if (editButton != null && !editButton.isDisposed()) {
            editButton.setEnabled(selected != null);
        }
        if (cloneButton != null && !cloneButton.isDisposed()) {
            cloneButton.setEnabled(selected != null);
        }
        if (deleteButton != null && !deleteButton.isDisposed()) {
            deleteButton.setEnabled(selected != null && !selected.isBuiltIn());
        }
    }

    private void showError(RuntimeException ex) {
        MessageDialog.openError(getShell(), Messages.getString("ProfileManager.error.title"),
                ex.getMessage() != null ? ex.getMessage() : ex.toString());
    }
}
