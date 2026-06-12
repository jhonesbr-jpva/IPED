package iped.rcp.core.metadata;

import java.io.IOException;

/**
 * Ord-to-string resolver of a docvalues field (port of
 * {@code iped.app.metadata.LookupOrd} — the legacy class lives in the Swing
 * UI module and cannot be reused).
 */
public abstract class LookupOrd {

    boolean isCategory = false;

    public abstract String lookupOrd(int ord) throws IOException;

    public boolean isCategory() {
        return isCategory;
    }

    public void setCategory(boolean isCategory) {
        this.isCategory = isCategory;
    }
}
