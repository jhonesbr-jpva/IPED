package iped.rcp.views;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import iped.rcp.core.i18n.Messages;

/**
 * Visible columns configuration (task T018, parity {@code ColumnsManager}
 * dialog): check to show, reorder with up/down; the selection and order are
 * persisted per user by {@link ResultColumns} (R5 workspace preferences).
 */
public class ColumnsConfigDialog extends Dialog {

    private final ResultColumns columns;
    private Table fieldsTable;

    public ColumnsConfigDialog(Shell parentShell, ResultColumns columns) {
        super(parentShell);
        this.columns = columns;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(Messages.getString("MenuClass.ManageColumns"));
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite content = new Composite(area, SWT.NONE);
        content.setLayout(new GridLayout(2, false));
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        fieldsTable = new Table(content, SWT.CHECK | SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.heightHint = 400;
        tableData.widthHint = 350;
        fieldsTable.setLayoutData(tableData);

        // visible fields first (current order), then the remaining available
        // fields unchecked
        List<String> visible = columns.getVisibleFields();
        for (String field : visible) {
            addField(field, true);
        }
        for (String field : ResultColumns.availableFields()) {
            if (!visible.contains(field)) {
                addField(field, false);
            }
        }

        Composite buttons = new Composite(content, SWT.NONE);
        buttons.setLayout(new GridLayout(1, true));
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));

        Button up = new Button(buttons, SWT.PUSH);
        up.setText(Messages.getString("ColumnsDialog.MoveUp"));
        up.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        up.addListener(SWT.Selection, event -> move(-1));

        Button down = new Button(buttons, SWT.PUSH);
        down.setText(Messages.getString("ColumnsDialog.MoveDown"));
        down.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        down.addListener(SWT.Selection, event -> move(1));

        return area;
    }

    private void addField(String field, boolean checked) {
        TableItem item = new TableItem(fieldsTable, SWT.NONE);
        item.setText(ResultColumns.labelOf(field));
        item.setData(field);
        item.setChecked(checked);
    }

    private void move(int delta) {
        int index = fieldsTable.getSelectionIndex();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= fieldsTable.getItemCount()) {
            return;
        }
        TableItem item = fieldsTable.getItem(index);
        String field = (String) item.getData();
        boolean checked = item.getChecked();
        item.dispose();
        addFieldAt(field, checked, target);
        fieldsTable.setSelection(target);
    }

    private void addFieldAt(String field, boolean checked, int index) {
        TableItem item = new TableItem(fieldsTable, SWT.NONE, index);
        item.setText(ResultColumns.labelOf(field));
        item.setData(field);
        item.setChecked(checked);
    }

    @Override
    protected void okPressed() {
        List<String> visible = new ArrayList<>();
        for (TableItem item : fieldsTable.getItems()) {
            if (item.getChecked()) {
                visible.add((String) item.getData());
            }
        }
        columns.setVisibleFields(visible);
        super.okPressed();
    }
}
