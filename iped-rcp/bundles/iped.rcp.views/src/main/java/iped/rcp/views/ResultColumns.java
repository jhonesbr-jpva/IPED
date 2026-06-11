package iped.rcp.views;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;
import org.osgi.service.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.task.index.IndexItem;
import iped.localization.LocalizedProperties;
import iped.properties.BasicProps;
import iped.rcp.core.i18n.Messages;
import iped.rcp.core.search.ResultSorter;

/**
 * Column model of the results table (task T018, parity
 * {@code ColumnsManager}): visible fields and their order, persisted in the
 * user's workspace preferences (e4 instance scope under
 * {@code ~/.iped/ui-workspaces/...} - R5; divergence from the legacy
 * {@code visibleCols.dat} location is recorded in the parity inventory).
 */
public final class ResultColumns {

    /** Pseudo-fields of the legacy table (score and bookmark columns). */
    public static final String SCORE = ResultSorter.FIELD_SCORE;
    public static final String BOOKMARK = ResultSorter.FIELD_BOOKMARK;

    /** Same default fields and order as {@code ColumnsManager.defaultFields}. */
    public static final List<String> DEFAULT_FIELDS = List.of(SCORE, BOOKMARK, BasicProps.NAME, BasicProps.EXT,
            BasicProps.TYPE, BasicProps.LENGTH, BasicProps.DELETED, BasicProps.CATEGORY, BasicProps.CREATED,
            BasicProps.MODIFIED, BasicProps.ACCESSED, BasicProps.CHANGED, BasicProps.TIMESTAMP, BasicProps.TIME_EVENT,
            BasicProps.HASH, BasicProps.PATH);

    /** Same default widths as {@code ColumnsManager.defaultWidths}. */
    public static final List<Integer> DEFAULT_WIDTHS = List.of(50, 100, 200, 50, 50, 100, 60, 150, 155, 155, 155, 155,
            155, 155, 250, 2000);

    private static final Logger LOGGER = LoggerFactory.getLogger(ResultColumns.class);

    private static final String PREFS_NODE = "iped.rcp.views";
    private static final String PREF_VISIBLE = "results.visibleColumns";

    // newline-separated: metadata field names may contain spaces
    private static final String SEPARATOR = "\n";

    private final List<String> visibleFields = new ArrayList<>();

    public ResultColumns() {
        load();
    }

    /** Visible fields, in display order (mutable copy). */
    public List<String> getVisibleFields() {
        return new ArrayList<>(visibleFields);
    }

    public void setVisibleFields(List<String> fields) {
        visibleFields.clear();
        visibleFields.addAll(new LinkedHashSet<>(fields));
        save();
    }

    /** Default width of a field (legacy widths for defaults, 155 otherwise). */
    public int widthOf(String field) {
        int index = DEFAULT_FIELDS.indexOf(field);
        return index >= 0 ? DEFAULT_WIDTHS.get(index) : 155;
    }

    /** Localized column header of a field (legacy header parity). */
    public static String labelOf(String field) {
        if (SCORE.equals(field)) {
            return Messages.getString("ResultTableModel.score");
        }
        if (BOOKMARK.equals(field)) {
            return Messages.getString("ResultTableModel.bookmark");
        }
        return LocalizedProperties.getLocalizedField(field);
    }

    /**
     * All fields offered by the columns dialog: pseudo-columns, legacy
     * defaults and every indexed metadata field known to the engine,
     * alphabetically after the defaults (the legacy {@code ColumnsManager}
     * group tree is simplified to one flat list in this increment).
     */
    public static List<String> availableFields() {
        Set<String> fields = new LinkedHashSet<>(DEFAULT_FIELDS);
        fields.addAll(new TreeSet<>(IndexItem.getMetadataTypes().keySet()));
        return new ArrayList<>(fields);
    }

    private void load() {
        visibleFields.clear();
        String stored = prefs().get(PREF_VISIBLE, null);
        if (stored != null && !stored.isEmpty()) {
            for (String field : stored.split(SEPARATOR)) {
                if (!field.isEmpty()) {
                    visibleFields.add(field);
                }
            }
        }
        if (visibleFields.isEmpty()) {
            visibleFields.addAll(DEFAULT_FIELDS);
        }
    }

    private void save() {
        Preferences prefs = prefs();
        prefs.put(PREF_VISIBLE, String.join(SEPARATOR, visibleFields));
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            LOGGER.warn("Could not persist visible columns", e);
        }
    }

    private static Preferences prefs() {
        return InstanceScope.INSTANCE.getNode(PREFS_NODE);
    }
}
