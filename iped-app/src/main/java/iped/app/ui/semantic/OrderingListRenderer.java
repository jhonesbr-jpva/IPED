package iped.app.ui.semantic;

import java.awt.Component;
import java.util.Locale;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.border.EmptyBorder;

import iped.localization.LocaleResolver;
import iped.semantic.search.DeclarationText;
import iped.semantic.search.OrderingEntry;

/**
 * Renders an entry of a text ordering: position, kind, relative proximity (labelled as such, never
 * a percentage, FR-010), in hybrid mode where it came from, the item's name and the literal passage
 * with its beginning highlighted - or the conversation, through {@link SequenceRenderer}.
 */
final class OrderingListRenderer extends DefaultListCellRenderer {

    private static final long serialVersionUID = 1L;
    private static final int MAX_EXCERPT_CHARS = 600;
    private static final int HIGHLIGHT_CHARS = 90;

    private final DeclarationText text = DeclarationText.of(LocaleResolver.getLocale());

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
            boolean cellHasFocus) {
        JLabel label = (JLabel) super.getListCellRendererComponent(list, "", index, isSelected, cellHasFocus);
        EntryView v = (EntryView) value;
        OrderingEntry e = v.entry;
        int width = Math.max(200, list.getWidth() - 30);
        StringBuilder sb = new StringBuilder("<html><div style='width:").append(width).append("px'>");
        sb.append("<b>").append(e.getRank()).append(".</b> ").append(EntryView.html(text.kind(e.getKind())))
                .append(" &middot; ").append(EntryView.html(text.proximityKind(e.getProximityKind()))).append(' ')
                .append(String.format(Locale.ROOT, "%.4f", e.getProximity()));
        if (e.getProximityKind() == OrderingEntry.ProximityKind.COMBINED_RELATIVE) {
            sb.append(" &middot; <i>").append(EntryView.html(text.ranks(e.getSemanticRank(), e.getLexicalRank())))
                    .append("</i>");
        }
        if (v.itemName != null) {
            sb.append("<br><span style='color:#4a6ea9'>").append(EntryView.html(v.itemName)).append("</span>");
        }
        sb.append("<br>");
        if (e.getSequence() != null) {
            sb.append(SequenceRenderer.html(e.getSequence(), text));
        } else if (!e.getExcerpts().isEmpty()) {
            String t = e.getExcerpts().get(0).getText();
            if (t.length() > MAX_EXCERPT_CHARS) {
                t = t.substring(0, MAX_EXCERPT_CHARS) + " [...]";
            }
            int h = Math.min(HIGHLIGHT_CHARS, t.length());
            sb.append("<span style='background-color:#fff3b0'>").append(EntryView.html(t.substring(0, h)))
                    .append("</span>").append(EntryView.html(t.substring(h)));
        }
        sb.append("</div></html>");
        label.setText(sb.toString());
        label.setVerticalAlignment(JLabel.TOP);
        label.setBorder(new EmptyBorder(4, 4, 6, 4));
        return label;
    }
}
