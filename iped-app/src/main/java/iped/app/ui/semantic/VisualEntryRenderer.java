package iped.app.ui.semantic;

import java.awt.Component;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.border.EmptyBorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.app.ui.App;
import iped.data.IItem;
import iped.localization.LocaleResolver;
import iped.semantic.prep.FrameInstant;
import iped.semantic.prep.VideoMosaicSplitter;
import iped.semantic.search.DeclarationText;
import iped.semantic.search.OrderingEntry;
import iped.utils.ImageUtil;

/**
 * Renders an entry of the visual ordering: the image itself (its thumbnail) or, for a video, the
 * frame that brought it close, with the approximate instant marked as such (FR-035, amendment A2).
 * The gallery's global blur is honoured, exactly as the gallery applies it (FR-037). No description
 * is ever generated: what justifies the entry is the picture.
 */
final class VisualEntryRenderer extends DefaultListCellRenderer {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(VisualEntryRenderer.class);
    private static final int SIZE = 160;
    /** Same intensity as {@code GalleryModel}'s blur. */
    private static final double GALLERY_BLUR_INTENSITY = 0.02d;

    private final transient DeclarationText text = DeclarationText.of(LocaleResolver.getLocale());

    VisualEntryRenderer(SemanticSession session) {
        // the session is kept for symmetry with the text renderer; images are resolved off the EDT
    }

    /**
     * The picture of an entry: the item's thumbnail, or the entry's frame cut from the video mosaic.
     * Called off the EDT. {@code null} when there is nothing to show.
     */
    static BufferedImage imageOf(java.io.File moduleDir, IItem item, OrderingEntry e) {
        if (item == null) {
            return null;
        }
        try {
            BufferedImage img = null;
            if (e.getFrame() != null) {
                img = VideoMosaicSplitter.frameOf(moduleDir, item, e.getFrame().getFrameIndex());
            }
            if (img == null && item.getThumb() != null) {
                img = ImageIO.read(new ByteArrayInputStream(item.getThumb()));
            }
            return img == null ? null : scale(img);
        } catch (Exception ex) {
            LOGGER.debug("No picture for semantic entry of item {}", item.getId(), ex);
            return null;
        }
    }

    private static BufferedImage scale(BufferedImage img) {
        double f = Math.min(1.0, (double) SIZE / Math.max(img.getWidth(), img.getHeight()));
        int w = Math.max(1, (int) (img.getWidth() * f));
        int h = Math.max(1, (int) (img.getHeight() * f));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.getGraphics().drawImage(img.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null);
        return out;
    }

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
            boolean cellHasFocus) {
        JLabel label = (JLabel) super.getListCellRendererComponent(list, "", index, isSelected, cellHasFocus);
        EntryView v = (EntryView) value;
        OrderingEntry e = v.entry;
        BufferedImage img = v.image;
        if (img != null && App.get().getGalleryModel().getBlurFilter()) {
            img = ImageUtil.blur(img, SIZE, GALLERY_BLUR_INTENSITY);
        }
        label.setIcon(img == null ? null : new ImageIcon(img));
        StringBuilder sb = new StringBuilder("<html><b>").append(e.getRank()).append(".</b> ")
                .append(EntryView.html(text.kind(e.getKind()))).append(" &middot; ")
                .append(EntryView.html(text.proximityKind(e.getProximityKind()))).append(' ')
                .append(String.format(Locale.ROOT, "%.4f", e.getProximity()));
        if (v.itemName != null) {
            sb.append("<br>").append(EntryView.html(v.itemName));
        }
        if (e.getFrame() != null && e.getFrame().getInstantApproxMs() != null) {
            sb.append("<br>").append(EntryView.html(
                    text.get("SemanticSearch.InstantApprox", FrameInstant.format(e.getFrame().getInstantApproxMs()))));
        }
        sb.append("</html>");
        label.setText(sb.toString());
        label.setIconTextGap(8);
        label.setBorder(new EmptyBorder(4, 4, 4, 4));
        return label;
    }
}
