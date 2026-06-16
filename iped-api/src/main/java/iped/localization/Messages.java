package iped.localization;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.ResourceBundle.Control;

import iped.io.URLUtil;

public class Messages {

    public static final String BUNDLES_FOLDER = "localization";
    public static final String BUNDLES_FOLDER_PREFIX = "iped-app/resources/";

    /**
     * System property pointing directly at the {@value #BUNDLES_FOLDER} folder.
     * When set to an existing directory it takes precedence over the
     * jar-relative and working-directory heuristics below. The RCP product
     * sets it at boot so this resolver works from inside the OSGi wrapper
     * bundle, where the class location is not a usable {@code file:} jar path
     * and the working directory may be outside the source tree (task T065).
     */
    public static final String LOCALIZATION_DIR_PROP = "iped.localization.dir";

    private Messages() {
    }

    public static ResourceBundle getExternalBundle(String bundleName, Locale locale) {
        File file = resolveBundlesFolder();
        try {
            URL[] urls = { file.toURI().toURL() };
            ClassLoader loader = new URLClassLoader(urls);
            return ResourceBundle.getBundle(bundleName, locale, loader, new UTF8Control());
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Locates the {@value #BUNDLES_FOLDER} folder, in order: the
     * {@link #LOCALIZATION_DIR_PROP} system property, the folder next to this
     * class' jar (release/dev layout), then a bounded walk up from the working
     * directory to the source tree. Throws a clear {@link MissingResourceException}
     * instead of overflowing at the filesystem root when nothing is found, and
     * tolerates a non-{@code file:} class location (OSGi wrapper) instead of
     * failing with a {@code NullPointerException} (task T065).
     */
    private static File resolveBundlesFolder() {
        // 1) explicit override (e.g. set by the RCP product at boot — T065)
        String explicit = System.getProperty(LOCALIZATION_DIR_PROP);
        if (explicit != null && !explicit.isBlank()) {
            File dir = new File(explicit);
            if (dir.isDirectory()) {
                return dir;
            }
        }
        // 2) folder next to this class' jar (standard release / dev layout)
        File file = null;
        try {
            URL url = URLUtil.getURL(Messages.class);
            if (url != null && "file".equalsIgnoreCase(url.getProtocol())) {
                file = new File(new File(url.toURI()).getParentFile().getParentFile(), BUNDLES_FOLDER);
            }
        } catch (URISyntaxException | IllegalArgumentException e) {
            // class location is not a usable file: jar path (e.g. inside the
            // OSGi wrapper bundle) — fall through to the working-directory walk-up
        }
        if (file != null && file.exists()) {
            return file;
        }
        // 3) bounded walk up from the working directory to the source tree
        File baseFile = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (baseFile != null) {
            File candidate = new File(baseFile, BUNDLES_FOLDER_PREFIX + BUNDLES_FOLDER);
            if (candidate.exists()) {
                return candidate;
            }
            baseFile = baseFile.getParentFile();
        }
        throw new MissingResourceException(
                "Localization folder '" + BUNDLES_FOLDER + "' not found; set -D" + LOCALIZATION_DIR_PROP,
                Messages.class.getName(), BUNDLES_FOLDER);
    }

    private static class UTF8Control extends Control {

        @Override
        public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader,
                boolean reload) throws IllegalAccessException, InstantiationException, IOException {
            // The below is a copy of the default implementation.
            String bundleName = toBundleName(baseName, locale);
            String resourceName = toResourceName(bundleName, "properties");
            ResourceBundle bundle = null;
            InputStream stream = null;
            if (reload) {
                URL url = loader.getResource(resourceName);
                if (url != null) {
                    URLConnection connection = url.openConnection();
                    if (connection != null) {
                        connection.setUseCaches(false);
                        stream = connection.getInputStream();
                    }
                }
            } else {
                stream = loader.getResourceAsStream(resourceName);
            }
            if (stream != null) {
                try {
                    // Only this line is changed to make it to read properties files as UTF-8.
                    bundle = new PropertyResourceBundle(new InputStreamReader(stream, StandardCharsets.UTF_8));
                } finally {
                    stream.close();
                }
            }
            return bundle;
        }
    }
}
