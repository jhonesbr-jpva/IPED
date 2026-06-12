package iped.rcp.core.metadata;

import java.text.NumberFormat;

import iped.utils.LocalizedFormat;

/**
 * Distinct numeric facet value, in "no ranges" mode (port of
 * {@code iped.app.metadata.SingleValueCount} — task T030, FR-010).
 */
public class SingleValueCount extends ValueCount implements Comparable<SingleValueCount> {

    double value;

    SingleValueCount(double value) {
        super(null, 0, 0);
        this.value = value;
    }

    @Override
    public String toString() {
        NumberFormat nf = LocalizedFormat.getNumberInstance();
        StringBuilder sb = new StringBuilder();
        sb.append(nf.format(value));
        sb.append(" (");
        sb.append(nf.format(count));
        sb.append(')');
        return sb.toString();
    }

    @Override
    public String getVal() {
        return LocalizedFormat.getNumberInstance().format(value);
    }

    public double getValue() {
        return value;
    }

    @Override
    public int compareTo(SingleValueCount o) {
        return Double.compare(value, o.value);
    }
}
