package iped.rcp.casecreation.profiles;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import iped.rcp.core.profiles.ConfigFileGroup;
import iped.rcp.core.profiles.ConfigOption;

/**
 * Eclipse-Preferences-style page for one {@code key = value} config file of a
 * profile (feature 005, US3, Eclipse-Preferences-style editor). Each option is a typed control bound
 * directly to its {@link ConfigOption}: a checkbox for BOOLEAN, a text field for
 * INT/TEXT/ENUM, with the {@code #} comment block as the control tooltip.
 *
 * <p>
 * The override semantics map onto the page's standard buttons: a control whose
 * value differs from the base marks the option overridden (only those are
 * saved); <em>Restore Defaults</em> resets every control to the canonical base
 * value (drops the overrides). Values are written into the model on
 * {@code performOk}; the dialog persists the model on Save.
 */
public class ConfigFilePreferencePage extends PreferencePage {

    private final ConfigFileGroup group;
    private final List<Runnable> applyActions = new ArrayList<>();
    private final List<Runnable> resetActions = new ArrayList<>();

    public ConfigFilePreferencePage(ConfigFileGroup group, String title) {
        this.group = group;
        setTitle(title);
        setDescription(group.fileName());
    }

    @Override
    protected Control createContents(Composite parent) {
        ScrolledComposite scrolled = new ScrolledComposite(parent, SWT.V_SCROLL | SWT.H_SCROLL);
        scrolled.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite content = new Composite(scrolled, SWT.NONE);
        content.setLayout(new GridLayout(2, false));
        for (ConfigOption option : group.options()) {
            buildRow(content, option);
        }

        scrolled.setContent(content);
        scrolled.setExpandHorizontal(true);
        scrolled.setExpandVertical(true);
        scrolled.setMinSize(content.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        return scrolled;
    }

    private void buildRow(Composite parent, ConfigOption option) {
        String tooltip = option.description() == null || option.description().isBlank() ? null : option.description();
        if (option.type() == ConfigOption.Type.BOOLEAN) {
            Button check = new Button(parent, SWT.CHECK);
            check.setText(option.key());
            check.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
            check.setSelection(Boolean.parseBoolean(option.effectiveValue()));
            if (tooltip != null) {
                check.setToolTipText(tooltip);
            }
            applyActions.add(() -> {
                boolean value = check.getSelection();
                if (value == Boolean.parseBoolean(option.baseValue())) {
                    option.resetToBase();
                } else {
                    option.setValue(String.valueOf(value));
                }
            });
            resetActions.add(() -> check.setSelection(Boolean.parseBoolean(option.baseValue())));
        } else {
            Label label = new Label(parent, SWT.NONE);
            label.setText(option.key());
            if (tooltip != null) {
                label.setToolTipText(tooltip);
            }
            Text text = new Text(parent, SWT.BORDER | SWT.SINGLE);
            text.setText(nullToEmpty(option.effectiveValue()));
            text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            if (tooltip != null) {
                text.setToolTipText(tooltip);
            }
            applyActions.add(() -> {
                String value = text.getText();
                if (value.equals(nullToEmpty(option.baseValue()))) {
                    option.resetToBase();
                } else {
                    option.setValue(value);
                }
            });
            resetActions.add(() -> text.setText(nullToEmpty(option.baseValue())));
        }
    }

    @Override
    public boolean performOk() {
        applyActions.forEach(Runnable::run);
        return true;
    }

    @Override
    protected void performApply() {
        performOk();
    }

    @Override
    protected void performDefaults() {
        resetActions.forEach(Runnable::run);
        super.performDefaults();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
