package iped.app.ui.semantic;

import java.awt.Component;
import java.awt.Dimension;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import iped.engine.data.IPEDSource;
import iped.semantic.search.DeclarationText;
import iped.semantic.store.BuildRecord;
import iped.semantic.store.BuildRecord.ModalityRecord;
import iped.semantic.store.SemanticStore;

/**
 * "Build details" (FR-014): for each open case and modality, the model and version, the source and
 * destination of the preparation, the date, the parameters, the state and the counts per reason -
 * read from the case's build record, as recorded.
 */
final class BuildDetailsDialog {

    private BuildDetailsDialog() {
    }

    static void show(Component parent, SemanticSession session, DeclarationText text) {
        List<String> lines = new ArrayList<>();
        for (IPEDSource s : session.sources()) {
            lines.add("== " + s.getCaseDir().getName());
            BuildRecord r;
            try {
                r = BuildRecord.read(SemanticStore.of(s.getModuleDir()).buildRecord());
            } catch (IOException e) {
                lines.add(e.toString());
                continue;
            }
            if (r == null || r.modalities.isEmpty()) {
                lines.add(text.get("SemanticSearch.Build.None"));
                continue;
            }
            for (Map.Entry<String, ModalityRecord> m : r.modalities.entrySet()) {
                ModalityRecord mr = m.getValue();
                lines.add(text.get("SemanticSearch.Build.Modality", text.modality(m.getKey())));
                lines.add("  " + text.get("SemanticSearch.Build.State", String.valueOf(mr.state)));
                if (mr.model != null) {
                    lines.add("  " + text.get("SemanticSearch.Build.Model", mr.identity().describe()));
                }
                lines.add("  " + text.get("SemanticSearch.Build.Source", String.valueOf(mr.source)));
                if (mr.remote != null) {
                    lines.add("  " + text.get("SemanticSearch.Build.Destination", mr.remote.protocol + " "
                            + mr.remote.destination));
                }
                if (mr.keys != null) {
                    lines.add("  " + text.get("SemanticSearch.Build.Keys", String.valueOf(mr.keys)));
                }
                if (mr.timing != null) {
                    Object finished = mr.timing.get("finishedAt");
                    lines.add("  " + text.get("SemanticSearch.Build.Date", String.valueOf(
                            finished != null ? finished : mr.timing.get("startedAt"))));
                    lines.add("  " + text.get("SemanticSearch.Build.Timing", String.valueOf(mr.timing)));
                }
                if (mr.params != null) {
                    lines.add("  " + text.get("SemanticSearch.Build.Params", String.valueOf(mr.params)));
                }
                if (mr.counts != null) {
                    lines.add("  " + text.get("SemanticSearch.Build.Counts", String.valueOf(mr.counts)));
                }
                if (mr.conflicts != null && !mr.conflicts.isEmpty()) {
                    lines.add("  " + text.get("SemanticSearch.Build.Conflicts", String.valueOf(mr.conflicts)));
                }
                if (mr.disabledReason != null) {
                    lines.add("  " + mr.disabledReason);
                }
            }
            lines.add("  ipedVersion: " + r.ipedVersion);
        }
        JTextArea area = new JTextArea(String.join("\n", lines));
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(640, 420));
        JOptionPane.showMessageDialog(parent, scroll, text.get("SemanticSearch.Build.Title"),
                JOptionPane.INFORMATION_MESSAGE);
    }
}
