package iped.app.ui.semantic;

import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;

import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.model.EmbedderFactory;
import iped.semantic.search.DeclarationText;
import iped.semantic.search.SemanticRequest.Target;

/**
 * When a modality asks its questions to a remote model, the panel says so permanently, with the
 * destination, and asks before the first question of the session goes to each destination
 * (FR-024, FR-044). Refusing sends nothing: the question is not asked, and no other model or
 * source is tried in its place.
 *
 * <p>
 * The destination shown is the one this installation is configured with - the URL without
 * credentials - which is where the question goes; where the case was prepared is a separate fact,
 * shown in the declaration of each ordering.
 * </p>
 */
final class RemoteDestinationNotice extends JLabel {

    private static final long serialVersionUID = 1L;

    private final transient DeclarationText text;
    /** Destinations the examiner accepted in this session. */
    private final Set<String> confirmed = new HashSet<>();

    RemoteDestinationNotice(DeclarationText text) {
        this.text = text;
        setOpaque(true);
        setBackground(new Color(255, 243, 205));
        setForeground(new Color(102, 77, 3));
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(255, 223, 128)),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        setVisible(false);
    }

    /** Remote destinations of the modalities of a target, from this installation's configuration. */
    static Map<Modality, String> destinations(Target target, SemanticSearchConfig cfg) {
        Map<Modality, String> out = new LinkedHashMap<>();
        EmbedderFactory sources = new EmbedderFactory(cfg);
        List<Modality> modalities = new ArrayList<>();
        if (target != Target.VISUAL) {
            modalities.add(Modality.TEXT);
        }
        if (target != Target.TEXT) {
            modalities.add(Modality.VISUAL);
        }
        for (Modality m : modalities) {
            String d = sources.destination(m);
            if (d != null) {
                out.put(m, d);
            }
        }
        return out;
    }

    /** Shows the banner for the modalities of the target, or hides it when all are local. */
    void update(Target target) {
        Map<Modality, String> d = destinations(target, SemanticSession.config());
        if (d.isEmpty()) {
            setVisible(false);
            return;
        }
        StringBuilder html = new StringBuilder("<html>");
        for (Map.Entry<Modality, String> e : d.entrySet()) {
            if (html.length() > 6) {
                html.append("<br>");
            }
            html.append("<b>").append(escape(text.modality(e.getKey().key()))).append(":</b> ")
                    .append(escape(text.get("SemanticSearch.Remote.Banner", e.getValue())));
        }
        setText(html.append("</html>").toString());
        setVisible(true);
    }

    /**
     * Asks, once per destination and session, before a question leaves the workstation.
     *
     * @return {@code false} when the examiner refused: then nothing may be sent
     */
    boolean confirm(Component parent, Target target) {
        for (String d : destinations(target, SemanticSession.config()).values()) {
            if (confirmed.contains(d)) {
                continue;
            }
            int answer = JOptionPane.showConfirmDialog(parent, text.get("SemanticSearch.Remote.Confirm", d),
                    text.get("SemanticSearch.Remote.Title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return false;
            }
            confirmed.add(d);
        }
        return true;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
