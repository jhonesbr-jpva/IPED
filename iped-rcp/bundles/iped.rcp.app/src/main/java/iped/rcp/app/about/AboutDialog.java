package iped.rcp.app.about;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import iped.engine.Version;
import iped.rcp.core.i18n.Messages;

/**
 * Help ▸ About IPED dialog. Shows the product name, version and vendor, and an
 * "Installation Details" button that opens the {@link InstallationDetailsDialog}
 * (Features / Plugins / Configuration tabs) — mirroring the Eclipse About
 * dialog, rebuilt in plain SWT/JFace because this is a pure e4 product.
 */
public class AboutDialog extends Dialog {

    /** Button id of the "Installation Details" action in the button bar. */
    private static final int DETAILS_ID = IDialogConstants.CLIENT_ID + 1;

    private static final String IPED_URL = "https://github.com/sepinf-inc/IPED";

    public AboutDialog(Shell parentShell) {
        super(parentShell);
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText(Messages.getString("RcpAbout.dialogTitle"));
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite content = new Composite(area, SWT.NONE);
        content.setLayout(new GridLayout(1, false));
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label title = new Label(content, SWT.NONE);
        title.setText(Version.APP_EXT);
        FontData[] fontData = title.getFont().getFontData();
        for (FontData fd : fontData) {
            fd.setHeight(fd.getHeight() + 8);
            fd.setStyle(SWT.BOLD);
        }
        Font titleFont = new Font(title.getDisplay(), fontData);
        title.setFont(titleFont);
        title.addDisposeListener(e -> titleFont.dispose());

        new Label(content, SWT.NONE).setText(Version.APP_NAME_PREFIX);
        new Label(content, SWT.NONE).setText(Messages.getString("RcpAbout.versionLabel") + " " + Version.APP_VERSION);

        Label spacer = new Label(content, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(content, SWT.NONE).setText(Messages.getString("RcpAbout.vendor"));
        new Label(content, SWT.NONE).setText(IPED_URL);

        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, DETAILS_ID, Messages.getString("RcpAbout.installationDetails"), false);
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == DETAILS_ID) {
            new InstallationDetailsDialog(getShell()).open();
            return;
        }
        super.buttonPressed(buttonId);
    }
}
