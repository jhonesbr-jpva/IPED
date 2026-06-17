package iped.rcp.casecreation.profiles;

import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.EditingSupport;
import org.eclipse.jface.viewers.ICheckStateProvider;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;

import iped.rcp.core.i18n.Messages;
import iped.rcp.core.profiles.ConfigFileGroup;
import iped.rcp.core.profiles.ConfigOption;

/**
 * Editable grid for one {@link ConfigFileGroup} of the profile editor (feature
 * 005, US3, T027). Each row is a {@link ConfigOption}; the row checkbox is the
 * profile override flag, the Value column is editable, and the Default column
 * shows the canonical value for reference. The {@code #} comment block of each
 * key is surfaced as the row tooltip.
 *
 * <p>
 * Editing a value marks the option overridden (checks the row); unchecking a row
 * resets it to the base value. Only overridden options are written back by
 * {@link iped.rcp.core.profiles.ProfileService#saveModel}.
 */
public class ConfigOptionGrid extends Composite {

    private final CheckboxTableViewer viewer;

    public ConfigOptionGrid(Composite parent, int style) {
        super(parent, style);
        setLayout(new FillLayout());

        viewer = CheckboxTableViewer.newCheckList(this, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        ColumnViewerToolTipSupport.enableFor(viewer);

        TableViewerColumn keyColumn = new TableViewerColumn(viewer, SWT.NONE);
        keyColumn.getColumn().setText(Messages.getString("ProfileEditor.column.key"));
        keyColumn.getColumn().setWidth(280);
        keyColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((ConfigOption) element).key();
            }

            @Override
            public String getToolTipText(Object element) {
                String description = ((ConfigOption) element).description();
                return description == null || description.isBlank() ? null : description;
            }
        });

        TableViewerColumn valueColumn = new TableViewerColumn(viewer, SWT.NONE);
        valueColumn.getColumn().setText(Messages.getString("ProfileEditor.column.value"));
        valueColumn.getColumn().setWidth(220);
        valueColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return nullToEmpty(((ConfigOption) element).effectiveValue());
            }
        });
        valueColumn.setEditingSupport(new ValueEditingSupport());

        TableViewerColumn defaultColumn = new TableViewerColumn(viewer, SWT.NONE);
        defaultColumn.getColumn().setText(Messages.getString("ProfileEditor.column.default"));
        defaultColumn.getColumn().setWidth(180);
        defaultColumn.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return nullToEmpty(((ConfigOption) element).baseValue());
            }
        });

        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.setCheckStateProvider(new ICheckStateProvider() {
            @Override
            public boolean isChecked(Object element) {
                return ((ConfigOption) element).overridden();
            }

            @Override
            public boolean isGrayed(Object element) {
                return false;
            }
        });
        viewer.addCheckStateListener(event -> {
            ConfigOption option = (ConfigOption) event.getElement();
            if (event.getChecked()) {
                option.setOverridden(true);
            } else {
                option.resetToBase();
            }
            viewer.update(option, null);
        });
    }

    /** Binds the grid to {@code group}'s options. */
    public void setGroup(ConfigFileGroup group) {
        viewer.setInput(group.options());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Inline text editing of the value column; committing marks the option overridden. */
    private final class ValueEditingSupport extends EditingSupport {

        private final TextCellEditor editor;

        ValueEditingSupport() {
            super(ConfigOptionGrid.this.viewer);
            this.editor = new TextCellEditor(ConfigOptionGrid.this.viewer.getTable());
        }

        @Override
        protected CellEditor getCellEditor(Object element) {
            return editor;
        }

        @Override
        protected boolean canEdit(Object element) {
            return true;
        }

        @Override
        protected Object getValue(Object element) {
            return nullToEmpty(((ConfigOption) element).effectiveValue());
        }

        @Override
        protected void setValue(Object element, Object value) {
            ConfigOption option = (ConfigOption) element;
            String text = value == null ? "" : value.toString();
            if (!text.equals(nullToEmpty(option.effectiveValue()))) {
                option.setValue(text);
            }
            viewer.update(option, null);
            viewer.setChecked(option, option.overridden());
        }
    }
}
