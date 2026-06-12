package iped.rcp.core.filters;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.data.IPEDMultiSource;
import iped.localization.LocaleResolver;
import iped.rcp.core.i18n.Messages;
import iped.utils.UTF8Properties;

/**
 * Saved filters in the CURRENT on-disk format (task T031, FR-005/FI-02/03,
 * legacy {@code FilterManager}): user filters in
 * {@code ~/.iped/ipedFilters[-locale].txt} merged with the case defaults
 * ({@code <case>/iped/conf/DefaultFilters.txt}); entries valued
 * {@code OBSOLETE} are dropped and the legacy {@code \\:} escaping fix is
 * applied. Mutations are stored back to the user file, exactly like the
 * legacy dialog.
 */
public class SavedFiltersStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(SavedFiltersStore.class);

    /** Localization catalog of the shipped default filter names. */
    private static final String FILTERS_BUNDLE = "iped-filters";

    private final File userFilters;
    private final UTF8Properties filters = new UTF8Properties();

    public SavedFiltersStore(IPEDMultiSource source) {
        this(source, defaultUserFile());
    }

    /** Test hook: redirect the user filters file. */
    public SavedFiltersStore(IPEDMultiSource source, File userFiltersFile) {
        this.userFilters = userFiltersFile;
        try {
            if (userFilters.isFile()) {
                filters.load(userFilters);
            }
            File defaultFilters = new File(source.getAtomicSources().get(0).getModuleDir(),
                    "conf/DefaultFilters.txt");
            if (defaultFilters.isFile()) {
                filters.load(defaultFilters);
            }
        } catch (IOException e) {
            LOGGER.error("Error loading saved filters", e);
        }

        // remove obsolete default filters (legacy FilterManager.loadFilters)
        Iterator<Map.Entry<Object, Object>> it = filters.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().toString().trim().equalsIgnoreCase("OBSOLETE")) {
                it.remove();
            }
        }
        // fix filter values saved by old versions (#1392)
        for (Map.Entry<Object, Object> entry : filters.entrySet()) {
            if (entry.getValue().toString().contains("\\\\:")) {
                entry.setValue(entry.getValue().toString().replace("\\\\:", "\\:"));
            }
        }
    }

    /** Same per-locale file name resolution as the legacy FilterManager. */
    private static File defaultUserFile() {
        String name = "ipedFilters";
        String locale = LocaleResolver.getLocaleString();
        if (locale != null && !locale.equals("pt-BR")) {
            name += "-" + locale;
        }
        name += ".txt";
        return new File(System.getProperty("user.home") + "/.iped/" + name);
    }

    /** Raw name → expression, sorted by the localized display name. */
    public Map<String, String> getAll() {
        Map<String, String> result = new TreeMap<>((a, b) -> {
            int cmp = localizedName(a).compareToIgnoreCase(localizedName(b));
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        for (Map.Entry<Object, Object> entry : filters.entrySet()) {
            result.put(entry.getKey().toString(), entry.getValue().toString());
        }
        return result;
    }

    public String getExpression(String name) {
        return filters.getProperty(name);
    }

    public void put(String name, String expression) {
        filters.setProperty(name, expression);
        store();
    }

    public void remove(String name) {
        filters.remove(name);
        store();
    }

    /** Localized display name of a shipped filter (legacy MessagesFilter). */
    public static String localizedName(String rawName) {
        String localized = Messages.getString(FILTERS_BUNDLE, rawName);
        // adapter renders missing keys as !key!; user filters have no catalog
        return localized.startsWith("!") && localized.endsWith("!") ? rawName : localized;
    }

    private void store() {
        try {
            userFilters.getParentFile().mkdirs();
            filters.store(userFilters);
        } catch (IOException e) {
            LOGGER.error("Error saving filters to {}", userFilters, e);
        }
    }
}
