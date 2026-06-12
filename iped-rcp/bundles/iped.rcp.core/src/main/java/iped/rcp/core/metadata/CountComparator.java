package iped.rcp.core.metadata;

import java.util.Comparator;

/**
 * Sorts facet values by descending count (port of
 * {@code iped.app.metadata.CountComparator}).
 */
public class CountComparator implements Comparator<ValueCount> {

    @Override
    public final int compare(ValueCount o1, ValueCount o2) {
        return Long.compare(o2.getCount(), o1.getCount());
    }
}
