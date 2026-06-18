package iped.rcp.app.about;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;

import iped.engine.Version;
import iped.rcp.core.i18n.Messages;

/**
 * Installation Details dialog reached from the {@link AboutDialog}. Rebuilds, in
 * plain SWT/JFace, the three tabs of the Eclipse Installation Details dialog:
 *
 * <ul>
 * <li><b>Features</b> — the installed feature folders under {@code <install>/features}.</li>
 * <li><b>Plugins</b> — the installed OSGi bundles (symbolic name, version, state, vendor).</li>
 * <li><b>Configuration</b> — the running JVM system properties, with a Copy button.</li>
 * </ul>
 */
public class InstallationDetailsDialog extends Dialog {

    private static final Pattern LABEL_ATTR = Pattern.compile("\\blabel\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern PROVIDER_ATTR = Pattern.compile("\\bprovider-name\\s*=\\s*\"([^\"]*)\"");

    public InstallationDetailsDialog(Shell parentShell) {
        super(parentShell);
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText(Messages.getString("RcpAbout.detailsTitle"));
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Point getInitialSize() {
        return new Point(780, 580);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        TabFolder tabs = new TabFolder(area, SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        createFeaturesTab(tabs);
        createPluginsTab(tabs);
        createConfigurationTab(tabs);
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.CLOSE_LABEL, true);
    }

    // ---- Features tab ----

    private void createFeaturesTab(TabFolder tabs) {
        TabItem item = new TabItem(tabs, SWT.NONE);
        item.setText(Messages.getString("RcpAbout.tab.features"));
        Table table = newTable(tabs,
                new String[] { Messages.getString("RcpAbout.col.name"), Messages.getString("RcpAbout.col.version"),
                        Messages.getString("RcpAbout.col.provider"), Messages.getString("RcpAbout.col.id") },
                new int[] { 300, 140, 190, 320 });
        item.setControl(table);

        File featuresDir = installSubdir("features");
        if (featuresDir == null) {
            return;
        }
        File[] dirs = featuresDir.listFiles(File::isDirectory);
        if (dirs == null) {
            return;
        }
        Arrays.sort(dirs, Comparator.comparing(f -> f.getName().toLowerCase()));
        for (File dir : dirs) {
            String folder = dir.getName();
            int sep = folder.lastIndexOf('_');
            String id = sep > 0 ? folder.substring(0, sep) : folder;
            String version = sep > 0 ? folder.substring(sep + 1) : "";
            String[] labelProvider = readFeatureMetadata(new File(dir, "feature.xml"));
            String name = labelProvider[0] != null ? labelProvider[0] : id;
            String provider = labelProvider[1] != null ? labelProvider[1] : "";
            TableItem row = new TableItem(table, SWT.NONE);
            row.setText(new String[] { name, version, provider, id });
        }
    }

    /** {label, provider} read literally from feature.xml; %keys and missing values are null. */
    private String[] readFeatureMetadata(File featureXml) {
        if (!featureXml.isFile()) {
            return new String[] { null, null };
        }
        try {
            String content = Files.readString(featureXml.toPath(), StandardCharsets.UTF_8);
            return new String[] { literal(firstGroup(LABEL_ATTR, content)), literal(firstGroup(PROVIDER_ATTR, content)) };
        } catch (Exception e) {
            return new String[] { null, null };
        }
    }

    private static String firstGroup(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String literal(String value) {
        return (value == null || value.isBlank() || value.startsWith("%")) ? null : value;
    }

    // ---- Plugins tab ----

    private void createPluginsTab(TabFolder tabs) {
        TabItem item = new TabItem(tabs, SWT.NONE);
        item.setText(Messages.getString("RcpAbout.tab.plugins"));
        Table table = newTable(tabs,
                new String[] { Messages.getString("RcpAbout.col.name"), Messages.getString("RcpAbout.col.version"),
                        Messages.getString("RcpAbout.col.state"), Messages.getString("RcpAbout.col.provider"),
                        Messages.getString("RcpAbout.col.id") },
                new int[] { 260, 130, 90, 180, 320 });
        item.setControl(table);

        Bundle self = FrameworkUtil.getBundle(getClass());
        BundleContext ctx = self != null ? self.getBundleContext() : null;
        if (ctx == null) {
            return;
        }
        Bundle[] bundles = ctx.getBundles();
        Arrays.sort(bundles, Comparator.comparing(b -> nullToEmpty(b.getSymbolicName()).toLowerCase()));
        for (Bundle bundle : bundles) {
            TableItem row = new TableItem(table, SWT.NONE);
            row.setText(new String[] { nullToEmpty(header(bundle, Constants.BUNDLE_NAME)), bundle.getVersion().toString(),
                    stateName(bundle.getState()), nullToEmpty(header(bundle, Constants.BUNDLE_VENDOR)),
                    nullToEmpty(bundle.getSymbolicName()) });
        }
    }

    private static String header(Bundle bundle, String key) {
        return bundle.getHeaders().get(key);
    }

    private static String stateName(int state) {
        switch (state) {
            case Bundle.ACTIVE:
                return "ACTIVE";
            case Bundle.RESOLVED:
                return "RESOLVED";
            case Bundle.INSTALLED:
                return "INSTALLED";
            case Bundle.STARTING:
                return "STARTING";
            case Bundle.STOPPING:
                return "STOPPING";
            case Bundle.UNINSTALLED:
                return "UNINSTALLED";
            default:
                return String.valueOf(state);
        }
    }

    // ---- Configuration tab ----

    private void createConfigurationTab(TabFolder tabs) {
        TabItem item = new TabItem(tabs, SWT.NONE);
        item.setText(Messages.getString("RcpAbout.tab.configuration"));
        Composite panel = new Composite(tabs, SWT.NONE);
        panel.setLayout(new GridLayout(1, false));
        item.setControl(panel);

        Text text = new Text(panel, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String report = buildConfigurationReport();
        text.setText(report);

        Button copy = new Button(panel, SWT.PUSH);
        copy.setText(Messages.getString("RcpAbout.copy"));
        copy.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        copy.addListener(SWT.Selection, e -> copyToClipboard(panel.getDisplay(), report));
    }

    private String buildConfigurationReport() {
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append(Version.APP_EXT).append(' ').append(Version.APP_VERSION).append(nl);
        sb.append(Version.APP_NAME_PREFIX).append(nl).append(nl);
        sb.append("-- System Properties --").append(nl);
        Properties props = System.getProperties();
        TreeMap<String, String> sorted = new TreeMap<>();
        for (String key : props.stringPropertyNames()) {
            sorted.put(key, props.getProperty(key));
        }
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append(nl);
        }
        return sb.toString();
    }

    private void copyToClipboard(Display display, String content) {
        Clipboard clipboard = new Clipboard(display);
        try {
            clipboard.setContents(new Object[] { content }, new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    // ---- shared helpers ----

    private static Table newTable(Composite parent, String[] columns, int[] widths) {
        Table table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        for (int i = 0; i < columns.length; i++) {
            TableColumn col = new TableColumn(table, SWT.LEFT);
            col.setText(columns[i]);
            col.setWidth(widths[i]);
        }
        return table;
    }

    private File installSubdir(String name) {
        String area = System.getProperty("osgi.install.area");
        if (area == null || area.isBlank()) {
            return null;
        }
        try {
            File installDir = area.startsWith("file:") ? new File(URI.create(area.replace(" ", "%20"))) : new File(area);
            File sub = new File(installDir, name);
            return sub.isDirectory() ? sub : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
