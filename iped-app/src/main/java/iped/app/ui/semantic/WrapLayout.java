package iped.app.ui.semantic;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

import javax.swing.SwingUtilities;

/**
 * A {@link FlowLayout} whose preferred size accounts for the rows it wraps into, so a row of
 * controls in a narrow dock grows in height instead of being clipped.
 */
final class WrapLayout extends FlowLayout {

    private static final long serialVersionUID = 1L;

    private Dimension lastPreferred;

    WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return size(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension d = size(target, false);
        d.width -= getHgap() + 1;
        return d;
    }

    @Override
    public void layoutContainer(Container target) {
        Dimension now = preferredLayoutSize(target);
        if (lastPreferred != null && !lastPreferred.equals(now)) {
            // the width changed the number of rows: the parent must give the new height
            SwingUtilities.invokeLater(target::revalidate);
        }
        lastPreferred = now;
        super.layoutContainer(target);
    }

    private Dimension size(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            Container sized = target;
            while (sized.getWidth() == 0 && sized.getParent() != null) {
                sized = sized.getParent();
            }
            int width = sized.getWidth() == 0 ? Integer.MAX_VALUE : sized.getWidth();
            Insets insets = target.getInsets();
            int edges = insets.left + insets.right + getHgap() * 2;
            int max = width - edges;
            Dimension total = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;
            for (Component c : target.getComponents()) {
                if (!c.isVisible()) {
                    continue;
                }
                Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                if (rowWidth > 0 && rowWidth + getHgap() + d.width > max) {
                    addRow(total, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                rowWidth += (rowWidth > 0 ? getHgap() : 0) + d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            addRow(total, rowWidth, rowHeight);
            total.width += edges;
            total.height += insets.top + insets.bottom + getVgap() * 2;
            return total;
        }
    }

    private void addRow(Dimension total, int rowWidth, int rowHeight) {
        if (rowWidth == 0) {
            return;
        }
        total.width = Math.max(total.width, rowWidth);
        if (total.height > 0) {
            total.height += getVgap();
        }
        total.height += rowHeight;
    }
}
