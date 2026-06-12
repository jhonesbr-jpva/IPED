package iped.rcp.core.metadata;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Money facet value, ordered by amount (port of
 * {@code iped.app.metadata.MoneyCount} — task T030, FR-010/MD-02).
 */
public class MoneyCount extends ValueCount implements Comparable<MoneyCount> {

    private static final Pattern PATTERN = Pattern.compile("[\\$\\s\\.\\,]");

    private long money;

    MoneyCount(LookupOrd lo, int ord, int count) {
        super(lo, ord, count);
        String val = getVal();
        char centChar = val.charAt(val.length() - 3);
        if (centChar == '.' || centChar == ',') {
            val = val.substring(0, val.length() - 3);
        }
        Matcher matcher = PATTERN.matcher(val);
        money = Long.valueOf(matcher.replaceAll(""));
    }

    @Override
    public int compareTo(MoneyCount o) {
        return Long.compare(o.money, this.money);
    }
}
