package iped.rcp.app.startup;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Early bundle activator (task T045, FR-019). Started eagerly at a low start
 * level (see the {@code .product} configurations) so it runs BEFORE the
 * application — and therefore before any SWT class loads: the
 * {@code swt.autoScale} system property is read once, in the static
 * initializer of SWT's DPI utility.
 *
 * <p>
 * Maps the existing user setting {@code ~/.iped/UiScale.txt} (key
 * {@code uiScale}: {@code auto} or a float factor — same file the legacy UI
 * and the processing JVM read) onto the SWT autoscale: {@code auto} keeps the
 * SWT default (native per-monitor scaling, FR-025), a factor becomes a fixed
 * percentage. An explicit {@code -Dswt.autoScale} always wins.
 *
 * <p>
 * MUST NOT reference SWT/JFace/engine classes: it runs before everything and
 * loading SWT here would freeze the autoscale before the property is set.
 */
public class EarlyStartup implements BundleActivator {

    private static final String UI_SCALE_FILE = "/.iped/UiScale.txt";
    private static final String UI_SCALE_KEY = "uiScale";
    private static final String SWT_AUTOSCALE_PROP = "swt.autoScale";
    private static final String AUTO = "auto";

    @Override
    public void start(BundleContext context) {
        try {
            if (System.getProperty(SWT_AUTOSCALE_PROP) != null) {
                return; // explicit -D wins
            }
            File file = new File(System.getProperty("user.home") + UI_SCALE_FILE);
            if (!file.isFile()) {
                return;
            }
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file.toPath())) {
                properties.load(in);
            }
            String value = properties.getProperty(UI_SCALE_KEY);
            if (value == null || value.isBlank() || AUTO.equalsIgnoreCase(value.trim())) {
                return; // native per-monitor scaling
            }
            double factor = Double.parseDouble(value.trim());
            if (factor > 0) {
                System.setProperty(SWT_AUTOSCALE_PROP, String.valueOf((int) Math.round(factor * 100)));
            }
        } catch (Exception e) {
            // never break the boot over a scale preference; SLF4J may not be
            // resolved this early, report on stderr like the legacy UiScale
            e.printStackTrace();
        }
    }

    @Override
    public void stop(BundleContext context) {
        // nothing to undo: system properties die with the JVM
    }
}
