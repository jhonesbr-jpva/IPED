package iped.rcp.core.metadata;

import java.text.NumberFormat;

import iped.engine.localization.CategoryLocalization;
import iped.rcp.core.i18n.Messages;
import iped.utils.LocalizedFormat;

/**
 * A facet value with its item count (port of
 * {@code iped.app.metadata.ValueCount}, decoupled from the Swing UI module —
 * task T030, FR-010/MD-01).
 */
public class ValueCount {

    LookupOrd lo;
    protected int ord, count;
    String cachedStringValue = null;

    public ValueCount(LookupOrd lo, int ord, int count) {
        this.lo = lo;
        this.ord = ord;
        this.count = count;
    }

    public String getVal() {
        try {
            String val = lo.lookupOrd(ord);
            if (lo.isCategory) {
                val = CategoryLocalization.getInstance().getLocalizedCategory(val);
            }
            return val;
        } catch (Exception e) {
            // LookupOrd gets invalid if the case is updated while open
            // (IndexReader closed) — same legacy behavior
            return Messages.getString("MetadataPanel.UpdateWarn");
        }
    }

    @Override
    public String toString() {
        if (cachedStringValue == null) {
            NumberFormat nf = LocalizedFormat.getNumberInstance();
            cachedStringValue = getVal() + " (" + nf.format(count) + ")";
        }
        return cachedStringValue;
    }

    public int getCount() {
        return count;
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof ValueCount) && ((ValueCount) obj).ord == this.ord;
    }

    @Override
    public int hashCode() {
        return ord;
    }

    public int getOrd() {
        return ord;
    }

    public void setOrd(int ord) {
        this.ord = ord;
    }

    public void incrementCount() {
        count++;
    }
}
