package iped.rcp.core.i18n;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.localization.LocaleResolver;

/**
 * i18n adapter of the RCP UI (task T009, research R7, FR-020). Bridges to the
 * existing localization catalogs ({@code iped-app/resources/localization/}
 * {@code *.properties}, distributed as the {@code localization/} folder of
 * the release and of self-contained cases) so the single source of
 * translations is kept — no per-bundle OSGi catalogs.
 *
 * <p>
 * Locale precedence: the {@code iped-locale} system property (current IPED
 * mechanism) wins; otherwise the Equinox {@code -nl} argument
 * ({@code osgi.nl}) is honored; otherwise the JVM default. The resolved
 * locale is pushed back into {@link LocaleResolver} so the legacy
 * {@code Messages} classes of the engine and bridged viewers render in the
 * same language.
 *
 * <p>
 * The legacy resolver locates the folder relative to its own jar, which does
 * not survive the OSGi wrapper bundle; this adapter resolves it from (in
 * order): the {@code iped.rcp.localization.dir} system property (tests and
 * headless harness), {@code osgi.install.area} (product at
 * {@code <root>/ui/} with bundles at {@code <root>/localization/}), and a
 * bounded walk up from the working directory to the source tree (dev mode).
 *
 * <p>
 * Missing keys never throw: after the standard parent-chain fallback to the
 * root (English) bundle, an unresolved key is logged once and rendered as
 * {@code !key!} so UI tests can detect raw keys (SC-006, task T047).
 */
public final class Messages {

    /** Base name of the main desktop UI catalog (legacy analysis UI strings). */
    public static final String DESKTOP_BUNDLE = "iped-desktop-messages";

    /** Override for the localization folder, meant for tests and the parity harness. */
    public static final String LOCALIZATION_DIR_PROP = "iped.rcp.localization.dir";

    private static final String OSGI_NL_PROP = "osgi.nl";
    private static final String OSGI_INSTALL_AREA_PROP = "osgi.install.area";
    private static final String DEV_BUNDLES_PATH = "iped-app/resources/localization";

    private static final Logger LOGGER = LoggerFactory.getLogger(Messages.class);

    private static final ConcurrentHashMap<String, ResourceBundle> BUNDLES = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED_MISSING = ConcurrentHashMap.newKeySet();
    private static volatile Locale locale;
    private static volatile ClassLoader bundlesLoader;

    private Messages() {
    }

    /** Resolves a key from the desktop catalog ({@link #DESKTOP_BUNDLE}). */
    public static String getString(String key) {
        return getString(DESKTOP_BUNDLE, key);
    }

    /** Resolves and formats a key from the desktop catalog ({@link MessageFormat} syntax). */
    public static String getString(String key, Object... args) {
        return MessageFormat.format(getString(key), args);
    }

    /** Resolves a key from an arbitrary catalog (e.g. {@code iped-properties}). */
    public static String getString(String bundleBaseName, String key) {
        try {
            return getBundle(bundleBaseName).getString(key);
        } catch (MissingResourceException e) {
            if (REPORTED_MISSING.add(bundleBaseName + '#' + key)) {
                LOGGER.warn("Missing i18n key '{}' in bundle '{}' for locale {}", key, bundleBaseName, getLocale());
            }
            return '!' + key + '!';
        }
    }

    /**
     * The UTF-8 resource bundle for the given base name, with the standard
     * locale fallback chain ending at the root (English) properties file.
     *
     * @throws MissingResourceException if the catalog itself cannot be found
     */
    public static ResourceBundle getBundle(String bundleBaseName) {
        ResourceBundle bundle = BUNDLES.get(bundleBaseName);
        if (bundle == null) {
            bundle = ResourceBundle.getBundle(bundleBaseName, getLocale(), getBundlesLoader(), new Utf8Control());
            BUNDLES.putIfAbsent(bundleBaseName, bundle);
        }
        return bundle;
    }

    /** The locale of the UI, resolved once (see class Javadoc for precedence). */
    public static Locale getLocale() {
        Locale result = locale;
        if (result == null) {
            synchronized (Messages.class) {
                if (locale == null) {
                    locale = resolveLocale();
                }
                result = locale;
            }
        }
        return result;
    }

    /** Clears cached locale and bundles (locale switch in tests/preferences). */
    public static synchronized void reset() {
        locale = null;
        bundlesLoader = null;
        BUNDLES.clear();
        REPORTED_MISSING.clear();
        ResourceBundle.clearCache();
    }

    private static Locale resolveLocale() {
        String tag = LocaleResolver.getLocaleString();
        if (tag == null || tag.isBlank()) {
            tag = System.getProperty(OSGI_NL_PROP);
        }
        Locale resolved;
        if (tag != null && !tag.isBlank()) {
            // osgi.nl uses pt_BR form; language tags use pt-BR
            resolved = Locale.forLanguageTag(tag.trim().replace('_', '-'));
        } else {
            resolved = Locale.getDefault();
        }
        // keep legacy engine/viewer Messages on the same locale (FR-020)
        LocaleResolver.setLocale(resolved);
        LOGGER.info("UI locale: {}", resolved.toLanguageTag());
        return resolved;
    }

    private static ClassLoader getBundlesLoader() {
        ClassLoader result = bundlesLoader;
        if (result == null) {
            synchronized (Messages.class) {
                if (bundlesLoader == null) {
                    File dir = resolveBundlesDir();
                    if (dir == null) {
                        throw new MissingResourceException("Localization folder not found; set -D"
                                + LOCALIZATION_DIR_PROP, Messages.class.getName(), "");
                    }
                    LOGGER.info("Loading localization bundles from {}", dir.getAbsolutePath());
                    try {
                        bundlesLoader = new URLClassLoader(new URL[] { dir.toURI().toURL() }, null);
                    } catch (MalformedURLException e) {
                        throw new IllegalStateException(e);
                    }
                }
                result = bundlesLoader;
            }
        }
        return result;
    }

    private static File resolveBundlesDir() {
        String explicit = System.getProperty(LOCALIZATION_DIR_PROP);
        if (explicit != null && !explicit.isBlank()) {
            File dir = new File(explicit);
            if (dir.isDirectory()) {
                return dir;
            }
            LOGGER.warn("Localization dir from -D{} not found: {}", LOCALIZATION_DIR_PROP, dir);
        }
        String installArea = System.getProperty(OSGI_INSTALL_AREA_PROP);
        if (installArea != null) {
            File install = areaUrlToFile(installArea);
            if (install != null) {
                File dir = new File(install, iped.localization.Messages.BUNDLES_FOLDER);
                if (dir.isDirectory()) {
                    return dir;
                }
                // product lives in <root>/ui/, bundles in <root>/localization/
                dir = new File(install.getParentFile(), iped.localization.Messages.BUNDLES_FOLDER);
                if (dir.isDirectory()) {
                    return dir;
                }
            }
        }
        // dev mode: walk up from the working directory to the source tree
        File base = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (base != null) {
            File dir = new File(base, DEV_BUNDLES_PATH);
            if (dir.isDirectory()) {
                return dir;
            }
            base = base.getParentFile();
        }
        return null;
    }

    /**
     * Converts an Equinox area URL ({@code osgi.install.area}) to a file.
     * Equinox does not URL-encode these values, so they are parsed as plain
     * paths after the {@code file:} scheme.
     */
    private static File areaUrlToFile(String areaUrl) {
        String path = areaUrl.trim();
        if (path.startsWith("file:")) {
            path = path.substring("file:".length());
        }
        // file:/C:/... form — strip the leading slash before the drive letter
        if (path.length() > 2 && path.charAt(0) == '/' && path.charAt(2) == ':') {
            path = path.substring(1);
        }
        File file = new File(path);
        return file.isDirectory() ? file : null;
    }

    /** Reads {@code .properties} as UTF-8 (same behavior as the legacy resolver). */
    private static class Utf8Control extends ResourceBundle.Control {

        @Override
        public ResourceBundle newBundle(String baseName, Locale loc, String format, ClassLoader loader, boolean reload)
                throws IOException {
            String resourceName = toResourceName(toBundleName(baseName, loc), "properties");
            try (InputStream stream = loader.getResourceAsStream(resourceName)) {
                if (stream == null) {
                    return null;
                }
                return new PropertyResourceBundle(new InputStreamReader(stream, StandardCharsets.UTF_8));
            }
        }
    }
}
