package iped.app.ui.semantic;

import java.io.IOException;

import javax.swing.JMenuItem;
import javax.swing.JTable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.app.ui.App;
import iped.data.IItem;
import iped.data.IItemId;
import iped.localization.LocaleResolver;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.search.DeclarationText;
import iped.semantic.search.SemanticRequest.Reference;
import iped.semantic.store.CoverageReason;
import iped.viewers.ATextViewer;

/**
 * "More like this" (FR-013): from the item highlighted in the results table (context menu), or
 * from a passage selected in the text viewer. The menu entry is disabled, with the reason as its
 * tooltip, when the item is not within reach of semantic text search.
 */
public final class MoreLikeThisAction {

    private static final Logger LOGGER = LoggerFactory.getLogger(MoreLikeThisAction.class);

    private MoreLikeThisAction() {
    }

    private static DeclarationText text() {
        return DeclarationText.of(LocaleResolver.getLocale());
    }

    /** The highlighted row of the results table, or {@code null}. */
    private static IItemId selectedItemId() {
        JTable table = App.get().getResultsTable();
        int row = table.getSelectedRow();
        if (row < 0) {
            return null;
        }
        return App.get().getResults().getItem(table.convertRowIndexToModel(row));
    }

    /** The entry of the table's context menu for the highlighted item. */
    public static JMenuItem menuItem(IItem item) {
        DeclarationText t = text();
        JMenuItem mi = new JMenuItem(t.get("SemanticSearch.MoreLikeThis"));
        IItemId id = selectedItemId();
        SemanticSearchPanel panel = App.get().semanticSearchPanel;
        if (item == null || id == null || panel == null) {
            mi.setEnabled(false);
            return mi;
        }
        CoverageReason reason = reasonOf(panel, id);
        if (reason == null) {
            mi.setEnabled(false);
            mi.setToolTipText(t.get("SemanticSearch.Unavailable.CASE_WITHOUT_CAPABILITY"));
            return mi;
        }
        if (!reason.isCovered() && reason != CoverageReason.COVERED_BY_MESSAGES) {
            mi.setEnabled(false);
            mi.setToolTipText(t.get("SemanticSearch.MoreLikeThis.NotCovered", t.reason(reason)));
            return mi;
        }
        String name = item.getName();
        mi.addActionListener(e -> run(panel, id, Reference.item(id.getId()), name));
        return mi;
    }

    private static CoverageReason reasonOf(SemanticSearchPanel panel, IItemId id) {
        SemanticSession session = panel.getSession();
        int caseIndex = session.caseIndexOf(id.getSourceId());
        if (caseIndex < 0) {
            session.get();
            caseIndex = session.caseIndexOf(id.getSourceId());
        }
        if (caseIndex < 0) {
            return null;
        }
        try {
            return session.get().member(caseIndex).reasonOf(Modality.TEXT, id.getId());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Cannot read the semantic coverage of item {}", id, e);
            return null;
        }
    }

    private static void run(SemanticSearchPanel panel, IItemId id, Reference ref, String description) {
        int caseIndex = panel.getSession().caseIndexOf(id.getSourceId());
        App.get().showSemanticSearch();
        panel.searchReference(ref.inCase(Math.max(0, caseIndex)), description);
    }

    /**
     * Registers "more like this passage" on the text viewer: the selected text of the item being
     * viewed becomes the reference.
     */
    public static void install() {
        ATextViewer.setSelectionAction(text().get("SemanticSearch.MoreLikeThisPassage"), selected -> {
            SemanticSearchPanel panel = App.get().semanticSearchPanel;
            int doc = App.get().getLastSelectedDoc();
            if (panel == null || doc < 0) {
                return;
            }
            IItemId id = App.get().appCase.getItemId(doc);
            String snippet = selected.strip();
            String description = snippet.length() > 60 ? snippet.substring(0, 60) + "..." : snippet;
            run(panel, id, Reference.selectedText(id.getId(), snippet), description);
        });
    }
}
