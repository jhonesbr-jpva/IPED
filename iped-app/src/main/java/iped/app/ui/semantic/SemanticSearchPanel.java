package iped.app.ui.semantic;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;

import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.app.ui.App;
import iped.app.ui.BookmarksController;
import iped.app.ui.FileProcessor;
import iped.data.IItem;
import iped.data.IItemId;
import iped.data.IMultiBookmarks;
import iped.engine.data.IPEDSource;
import iped.engine.search.QueryBuilder;
import iped.localization.LocaleResolver;
import iped.search.IMultiSearchResult;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.search.Availability;
import iped.semantic.search.DeclarationText;
import iped.semantic.search.MultiCaseSearch;
import iped.semantic.search.Notice;
import iped.semantic.search.Ordering;
import iped.semantic.search.OrderingEntry;
import iped.semantic.search.SemanticRequest;
import iped.semantic.search.SemanticRequest.Target;
import iped.semantic.search.SemanticUnavailableException;
import iped.semantic.store.BuildRecord;
import iped.semantic.store.SemanticStore;

/**
 * The "Semantic search" dockable panel (contracts/ui-surface.md). It computes nothing: it builds a
 * request - question, scope (the current result of the table), modality, mode - and hands it to the
 * same searcher the MCP uses. It shows what the searcher declares: the ordering with literal
 * passages or conversations, its depth, ceiling, method, coverage, model and mode, and never a
 * total or "no results" (FR-007, FR-010).
 *
 * <p>
 * Questions run in a {@link SwingWorker}; components are only touched on the EDT. The model loads
 * on the first question of the session, not when the case opens.
 * </p>
 */
public class SemanticSearchPanel extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(SemanticSearchPanel.class);
    private static final int HIGHLIGHT_WORDS = 8;

    private final transient SemanticSession session = new SemanticSession();
    private final transient DeclarationText text = DeclarationText.of(LocaleResolver.getLocale());

    private final JTextField question = new JTextField();
    private final JButton ask = new JButton();
    private final JRadioButton textModality = new JRadioButton();
    private final JRadioButton visualModality = new JRadioButton();
    private final JRadioButton bothModalities = new JRadioButton();
    private final JComboBox<TextMode> mode = new JComboBox<>(TextMode.values());
    private final JLabel scopeLabel = new JLabel();
    private final JLabel status = new JLabel(" ");
    private final JLabel reference = new JLabel();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea unavailable = new JTextArea();
    private final JButton buildDetails = new JButton();

    private transient SemanticRequest.Reference pendingReference;
    private transient Query highlightBase;
    private transient Query highlightSet;
    private transient SemanticResultFilterer filterer;

    public SemanticSearchPanel() {
        super(new BorderLayout());
        build();
    }

    /** The table filter "Apply to table" installs; registered in the App's filter manager. */
    public void setResultFilterer(SemanticResultFilterer filterer) {
        this.filterer = filterer;
    }

    private void build() {
        ask.setText(text.get("SemanticSearch.Ask"));
        question.setToolTipText(text.get("SemanticSearch.QuestionHint"));
        textModality.setText(text.modality("text"));
        visualModality.setText(text.modality("visual"));
        bothModalities.setText(text.modality("both"));
        ButtonGroup group = new ButtonGroup();
        group.add(textModality);
        group.add(visualModality);
        group.add(bothModalities);
        textModality.setSelected(true);
        mode.setSelectedItem(SemanticSession.config().getDefaultTextMode());
        mode.setRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                String label = value == null ? "" : text.get("SemanticSearch.Mode." + ((TextMode) value).name());
                return super.getListCellRendererComponent(list, label, index, isSelected, cellHasFocus);
            }
        });
        buildDetails.setText(text.get("SemanticSearch.BuildDetails"));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel line1 = new JPanel(new BorderLayout(4, 0));
        line1.add(question, BorderLayout.CENTER);
        line1.add(ask, BorderLayout.EAST);
        JPanel line2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        line2.add(textModality);
        line2.add(visualModality);
        line2.add(bothModalities);
        line2.add(Box.createHorizontalStrut(8));
        line2.add(mode);
        line2.add(buildDetails);
        JPanel line3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        line3.add(scopeLabel);
        line3.add(reference);
        top.add(line1);
        top.add(line2);
        top.add(line3);
        top.add(status);
        add(top, BorderLayout.NORTH);

        unavailable.setEditable(false);
        unavailable.setLineWrap(true);
        unavailable.setWrapStyleWord(true);
        unavailable.setBackground(getBackground());
        add(tabs, BorderLayout.CENTER);

        JTextArea notice = new JTextArea(text.get("SemanticSearch.OrderingNotice.UI"));
        notice.setEditable(false);
        notice.setLineWrap(true);
        notice.setWrapStyleWord(true);
        notice.setFont(notice.getFont().deriveFont(Font.ITALIC));
        notice.setBackground(getBackground());
        notice.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        add(notice, BorderLayout.SOUTH);

        ask.addActionListener(e -> askQuestion());
        question.addActionListener(e -> askQuestion());
        textModality.addActionListener(e -> mode.setEnabled(true));
        visualModality.addActionListener(e -> mode.setEnabled(false));
        bothModalities.addActionListener(e -> mode.setEnabled(true));
        buildDetails.addActionListener(e -> BuildDetailsDialog.show(this, session, text));
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                refreshState();
            }
        });
    }

    /** Scope size and which modalities the open case(s) prepared; cheap, reads no model. */
    public void refreshState() {
        if (App.get().appCase == null) {
            return;
        }
        new SwingWorker<boolean[], Void>() {
            int scopeSize;

            @Override
            protected boolean[] doInBackground() {
                scopeSize = App.get().getResults().getLength();
                boolean text = false;
                boolean visual = false;
                for (IPEDSource s : session.sources()) {
                    SemanticStore store = SemanticStore.of(s.getModuleDir());
                    try {
                        BuildRecord r = BuildRecord.read(store.buildRecord());
                        text |= r != null && r.get(Modality.TEXT) != null;
                        visual |= r != null && r.get(Modality.VISUAL) != null;
                    } catch (IOException e) {
                        LOGGER.warn("Cannot read the semantic build record of {}", s.getCaseDir(), e);
                    }
                }
                return new boolean[] { text, visual };
            }

            @Override
            protected void done() {
                try {
                    boolean[] prepared = get();
                    scopeLabel.setText(text.get("SemanticSearch.Scope", String.valueOf(scopeSize)));
                    textModality.setEnabled(prepared[0]);
                    visualModality.setEnabled(prepared[1]);
                    bothModalities.setEnabled(prepared[0] && prepared[1]);
                    String off = text.get("SemanticSearch.Unavailable.MODALITY_NOT_PREPARED", "");
                    textModality.setToolTipText(prepared[0] ? null : off);
                    visualModality.setToolTipText(prepared[1] ? null : off);
                    if (!prepared[0] && prepared[1]) {
                        visualModality.setSelected(true);
                        mode.setEnabled(false);
                    }
                } catch (InterruptedException | ExecutionException e) {
                    LOGGER.warn("Semantic panel state refresh failed", e);
                }
            }
        }.execute();
    }

    private Target target() {
        if (visualModality.isSelected()) {
            return Target.VISUAL;
        }
        return bothModalities.isSelected() ? Target.BOTH : Target.TEXT;
    }

    /** Starts "more like this" from an item or a passage of it (FR-013); called by the menus. */
    public void searchReference(SemanticRequest.Reference ref, String description) {
        this.pendingReference = ref;
        reference.setText(text.get("SemanticSearch.Reference", description));
        textModality.setSelected(true);
        mode.setEnabled(true);
        run(null, ref);
    }

    private void askQuestion() {
        String q = question.getText();
        if (q == null || q.isBlank()) {
            return;
        }
        pendingReference = null;
        reference.setText("");
        run(q, null);
    }

    private void run(String q, SemanticRequest.Reference ref) {
        Target target = ref != null ? Target.TEXT : target();
        TextMode m = (TextMode) mode.getSelectedItem();
        SemanticSearchConfig cfg = SemanticSession.config();
        ask.setEnabled(false);
        tabs.removeAll();
        new SwingWorker<List<Ordering>, String>() {
            SemanticUnavailableException unavailableError;
            int scopeSize;
            Map<OrderingEntry, EntryView.Info> names = new LinkedHashMap<>();

            @Override
            protected List<Ordering> doInBackground() throws Exception {
                MultiCaseSearch search = session.get();
                publish(session.consumeFresh() ? text.get("SemanticSearch.LoadingModel")
                        : text.get("SemanticSearch.Computing"));
                List<BitSet> scopes = session.scopesOfCurrentResult();
                for (BitSet s : scopes) {
                    scopeSize += s.cardinality();
                }
                try {
                    List<Ordering> out = search.search(target, i -> {
                        SemanticRequest.Builder b = SemanticRequest.builder().target(target).mode(m)
                                .scope(scopes.get(i)).depth(cfg.getDefaultDepth());
                        if (ref != null) {
                            b.reference(ref);
                        } else {
                            b.query(q);
                        }
                        return b.build(cfg);
                    });
                    for (Ordering o : out) {
                        resolveNames(o, 0, o.getShown(), names);
                    }
                    return out;
                } catch (SemanticUnavailableException e) {
                    unavailableError = e;
                    return null;
                } catch (iped.semantic.search.ReferenceNotCoveredException e) {
                    notCovered = e;
                    return null;
                }
            }

            iped.semantic.search.ReferenceNotCoveredException notCovered;

            @Override
            protected void process(List<String> chunks) {
                status.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                ask.setEnabled(true);
                status.setText(" ");
                scopeLabel.setText(text.get("SemanticSearch.Scope", String.valueOf(scopeSize)));
                try {
                    List<Ordering> orderings = get();
                    if (orderings == null && notCovered != null) {
                        status.setText(text.get("SemanticSearch.MoreLikeThis.NotCovered",
                                text.reason(notCovered.getReason())));
                        return;
                    }
                    if (orderings == null) {
                        showUnavailable(unavailableError.getAvailability());
                        return;
                    }
                    for (Ordering o : orderings) {
                        tabs.addTab(text.modality(o.getModality().key()), new OrderingView(o, q, names));
                    }
                } catch (InterruptedException | ExecutionException e) {
                    LOGGER.error("Semantic search failed", e);
                    status.setText(String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                }
            }
        }.execute();
    }

    private void showUnavailable(Availability a) {
        unavailable.setText(String.join("\n", text.unavailable(a)));
        unavailable.setForeground(new Color(0xa0, 0x30, 0x30));
        tabs.addTab(text.modality(a.getModality().key()), new JScrollPane(unavailable));
    }

    /**
     * Resolves what entries {@code [from, to)} show besides their stored fields - the item name and,
     * in the visual modality, the image or the frame (IO: call off the EDT).
     */
    private void resolveNames(Ordering o, int from, int to, Map<OrderingEntry, EntryView.Info> names) {
        List<OrderingEntry> all = o.getAll();
        for (int i = from; i < Math.min(to, all.size()); i++) {
            OrderingEntry e = all.get(i);
            try {
                IItem item = App.get().appCase.getItemByItemId(session.itemId(e.getCaseIndex(), e.getItemId()));
                java.awt.image.BufferedImage image = o.getModality() == Modality.VISUAL
                        ? VisualEntryRenderer.imageOf(session.sources().get(e.getCaseIndex()).getModuleDir(), item, e)
                        : null;
                names.put(e, new EntryView.Info(item == null ? null : item.getName(), image));
            } catch (RuntimeException ex) {
                names.put(e, null);
            }
        }
    }

    /** One ordering: its declaration, the list of entries and the actions on them. */
    private final class OrderingView extends JPanel {
        private static final long serialVersionUID = 1L;

        private Ordering ordering;
        private final String questionText;
        private final Map<OrderingEntry, EntryView.Info> names;
        private final DefaultListModel<EntryView> model = new DefaultListModel<>();
        private final JList<EntryView> list = new JList<>(model);
        private final JTextArea declaration = new JTextArea();
        private final JButton more = new JButton(text.get("SemanticSearch.MoreResults"));

        OrderingView(Ordering ordering, String questionText, Map<OrderingEntry, EntryView.Info> names) {
            super(new BorderLayout());
            this.ordering = ordering;
            this.questionText = questionText;
            this.names = names;
            declaration.setEditable(false);
            declaration.setLineWrap(true);
            declaration.setWrapStyleWord(true);
            declaration.setBackground(getBackground());
            declaration.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
            list.setCellRenderer(ordering.getModality() == Modality.VISUAL ? new VisualEntryRenderer(session)
                    : new OrderingListRenderer());
            list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            list.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    EntryView v = list.getSelectedValue();
                    if (v != null && e.getClickCount() >= 1) {
                        open(v);
                    }
                }
            });
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(declaration),
                    new JScrollPane(list));
            split.setResizeWeight(0.2);
            add(split, BorderLayout.CENTER);

            JButton apply = new JButton(text.get("SemanticSearch.ApplyToTable"));
            apply.setToolTipText(text.get("SemanticSearch.ApplyToTable.Tip"));
            JButton bookmark = new JButton(text.get("SemanticSearch.Bookmark"));
            bookmark.setToolTipText(text.get("SemanticSearch.Bookmark.Tip"));
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            actions.add(more);
            actions.add(apply);
            actions.add(bookmark);
            add(actions, BorderLayout.SOUTH);
            more.addActionListener(e -> extend());
            apply.addActionListener(e -> applyToTable());
            bookmark.addActionListener(e -> bookmarkSelected());
            refresh();
        }

        private void refresh() {
            List<String> lines = new ArrayList<>(text.declaration(ordering));
            if (session.get().size() > 1) {
                Set<String> excluded = new LinkedHashSet<>();
                for (Notice n : ordering.getNotices()) {
                    if (n.getCode() == Notice.Code.CASES_EXCLUDED) {
                        excluded.add(String.valueOf(n.getArgs().get(0)));
                    }
                }
                List<String> in = new ArrayList<>(session.get().names());
                in.removeAll(excluded);
                lines.add(text.get("SemanticSearch.CasesParticipating", String.join(", ", in)));
            }
            declaration.setText(String.join("\n", lines));
            model.clear();
            for (OrderingEntry e : ordering.getShownEntries()) {
                model.addElement(new EntryView(e, ordering, names.get(e)));
            }
            more.setEnabled(ordering.canExtend());
            if (ordering.isCeilingReached()) {
                more.setToolTipText(text.ceilingHint(ordering.getCeiling()));
            } else if (!ordering.canExtend()) {
                more.setToolTipText(text.get("SemanticSearch.MoreResults.EndTip"));
            } else {
                more.setToolTipText(null);
            }
        }

        /** Reveals more of the same list, keeping the order already shown (FR-007). */
        private void extend() {
            Ordering next = ordering.withDepth(ordering.getDepth() + SemanticSession.config().getDefaultDepth());
            int from = ordering.getShown();
            more.setEnabled(false);
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() {
                    resolveNames(next, from, next.getShown(), names);
                    return null;
                }

                @Override
                protected void done() {
                    ordering = next;
                    refresh();
                }
            }.execute();
        }

        private void applyToTable() {
            if (filterer == null) {
                return;
            }
            Map<IItemId, Float> ids = new LinkedHashMap<>();
            for (OrderingEntry e : ordering.getShownEntries()) {
                for (Integer id : e.targetItemIds()) {
                    ids.putIfAbsent(session.itemId(e.getCaseIndex(), id), (float) e.getProximity());
                }
            }
            filterer.apply(ids, questionText == null ? reference.getText() : questionText);
            App.get().getAppListener().updateFileListing();
        }

        private void bookmarkSelected() {
            List<EntryView> selected = list.getSelectedValuesList();
            if (selected.isEmpty()) {
                return;
            }
            IMultiBookmarks bookmarks = App.get().appCase.getMultiBookmarks();
            Object[] existing = bookmarks.getBookmarkSet().toArray();
            Object choice = JOptionPane.showInputDialog(SemanticSearchPanel.this,
                    text.get("SemanticSearch.BookmarkName"), text.get("SemanticSearch.Bookmark"),
                    JOptionPane.PLAIN_MESSAGE, null, null, questionText);
            if (choice == null || choice.toString().isBlank()) {
                return;
            }
            String name = choice.toString().strip();
            List<IItemId> ids = new ArrayList<>();
            for (EntryView v : selected) {
                for (Integer id : v.entry.targetItemIds()) {
                    ids.add(session.itemId(v.entry.getCaseIndex(), id));
                }
            }
            if (!bookmarks.getBookmarkSet().contains(name)) {
                bookmarks.newBookmark(name);
            }
            bookmarks.addBookmark(ids, name);
            String previous = bookmarks.getBookmarkComment(name);
            String comment = text.get("SemanticSearch.BookmarkComment",
                    questionText == null ? reference.getText() : questionText,
                    String.valueOf(ordering.getCoverage().getRecorteSize()), String.valueOf(ordering.getShown()),
                    ordering.getModel().getId(), text.get("SemanticSearch.Mode." + ordering.getMode().name()),
                    DateFormat.getDateTimeInstance().format(new Date()));
            bookmarks.setBookmarkComment(name, previous == null || previous.isBlank() ? comment
                    : previous + "\n" + comment);
            bookmarks.saveState();
            BookmarksController.get().updateUI();
            if (existing.length == 0) {
                LOGGER.info("Created bookmark {} from a semantic ordering", name);
            }
        }
    }

    /** Opens an entry: the item (or the first in-scope message) in the viewer, with the passage highlighted. */
    private void open(EntryView v) {
        OrderingEntry e = v.entry;
        List<Integer> targets = e.targetItemIds();
        int target = targets.isEmpty() ? e.getItemId() : targets.get(0);
        IItemId id = session.itemId(e.getCaseIndex(), target);
        if (!e.getExcerpts().isEmpty()) {
            highlight(e.getExcerpts().get(0).getText());
        }
        if (!selectInTable(id)) {
            int luceneId = App.get().appCase.getLuceneId(id);
            if (luceneId >= 0) {
                new FileProcessor(luceneId, true).execute();
            }
        }
    }

    private boolean selectInTable(IItemId id) {
        IMultiSearchResult result = App.get().getResults();
        for (int i = 0; i < result.getLength(); i++) {
            if (result.getItem(i).equals(id)) {
                JTable table = App.get().getResultsTable();
                int row = table.convertRowIndexToView(i);
                if (row < 0) {
                    return false;
                }
                table.setRowSelectionInterval(row, row);
                table.scrollRectToVisible(table.getCellRect(row, 0, true));
                return true;
            }
        }
        return false;
    }

    /**
     * Highlights the passage in the viewers (research D13, T033): its first words as a phrase, added
     * to the highlight query the text viewer scores with, and to the highlight terms the other
     * viewers use. The next keyword search replaces both, as it always does.
     */
    private void highlight(String passage) {
        String[] words = passage.replace('"', ' ').replace('\\', ' ').trim().split("\\s+");
        if (words.length < 2) {
            return;
        }
        String phrase = String.join(" ", java.util.Arrays.copyOf(words, Math.min(HIGHLIGHT_WORDS, words.length)));
        try {
            Query q = new QueryBuilder(App.get().appCase).getQuery("\"" + phrase + "\"");
            Query current = App.get().getQuery();
            if (current != highlightSet) {
                highlightBase = current;
            }
            Query combined = q;
            if (highlightBase != null) {
                combined = new BooleanQuery.Builder().add(highlightBase, Occur.SHOULD).add(q, Occur.SHOULD).build();
            }
            highlightSet = combined;
            App.get().setQuery(combined);
            Set<String> terms = new LinkedHashSet<>();
            if (App.get().getHighlightTerms() != null) {
                terms.addAll(App.get().getHighlightTerms());
            }
            terms.add(phrase);
            App.get().setHighlightTerms(terms);
        } catch (Exception ex) {
            LOGGER.debug("Could not build the highlight phrase of a semantic passage", ex);
        }
    }

    /** Called when the case is closed or replaced. */
    public void caseClosed() {
        session.close();
        SwingUtilities.invokeLater(() -> {
            tabs.removeAll();
            reference.setText("");
        });
    }

    SemanticSession getSession() {
        return session;
    }
}
