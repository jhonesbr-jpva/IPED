package iped.rcp.core.metadata;

import java.text.NumberFormat;

import iped.utils.LocalizedFormat;

/**
 * A numeric facet range with its item count (port of
 * {@code iped.app.metadata.RangeCount} — task T030, FR-010/MD-02).
 */
public class RangeCount extends ValueCount {

    double start, end;

    RangeCount(double start, double end, int ord, int count) {
        super(null, ord, count);
        this.start = start;
        this.end = end;
    }

    @Override
    public String toString() {
        NumberFormat nf = LocalizedFormat.getNumberInstance();
        StringBuilder sb = new StringBuilder();
        sb.append(getVal());
        sb.append(" (");
        sb.append(nf.format(count));
        sb.append(')');
        return sb.toString();
    }

    @Override
    public String getVal() {
        StringBuilder sb = new StringBuilder();
        NumberFormat nf = LocalizedFormat.getNumberInstance();
        sb.append(nf.format(start));
        if (start != end && (!Double.isNaN(start) || !Double.isNaN(end))) {
            sb.append(' ');
            sb.append(MetadataAggregator.rangeSeparator());
            sb.append(' ');
            sb.append(nf.format(end));
        }
        return sb.toString();
    }

    public double getStart() {
        return start;
    }

    public double getEnd() {
        return end;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof RangeCount) {
            RangeCount rc = (RangeCount) obj;
            return this.start == rc.start && this.end == rc.end;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(start);
    }
}
