package iped.semantic.search;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

import iped.localization.Messages;
import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.store.CoverageReason;

/**
 * The texts that declare what an ordering is (FR-007, FR-008, FR-010, FR-014, FR-040): mode,
 * depth and ceiling, method, coverage with reasons, model, notices and unavailability. Built here,
 * in one place, so the UI shows them and the tests check them in PT-BR and EN; the MCP uses the
 * English ones. None of them reads as a set: no total, no "no results", no confidence percentage.
 *
 * <p>
 * Texts come from {@code iped-desktop-messages*.properties} ({@code SemanticSearch.*} keys).
 * </p>
 */
public final class DeclarationText {

    public static final String BUNDLE = "iped-desktop-messages";

    private static final Map<Locale, DeclarationText> CACHE = new ConcurrentHashMap<>();

    private final ResourceBundle bundle;
    private final Locale locale;

    private DeclarationText(Locale locale) {
        this.locale = locale;
        // English is the base bundle. Asking for Locale.ENGLISH would fall back to the machine's
        // default locale (Portuguese on a pt-BR workstation) before reaching the base file.
        this.bundle = Messages.getExternalBundle(BUNDLE,
                "en".equals(locale.getLanguage()) ? Locale.ROOT : locale);
    }

    public static DeclarationText of(Locale locale) {
        return CACHE.computeIfAbsent(locale, DeclarationText::new);
    }

    /** English texts, for the MCP. */
    public static DeclarationText english() {
        return of(Locale.ENGLISH);
    }

    public Locale getLocale() {
        return locale;
    }

    /** A localized message; {@code args} formatted with {@link MessageFormat}. */
    public String get(String key, Object... args) {
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (MissingResourceException e) {
            pattern = key;
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        MessageFormat mf = new MessageFormat(pattern, locale);
        return mf.format(args);
    }

    /** FR-040: the ranking-is-not-a-set notice every response and panel carries. */
    public String orderingNotice() {
        return get("SemanticSearch.OrderingNotice");
    }

    public String reason(CoverageReason r) {
        return get(r.messageKey());
    }

    public String proximityKind(OrderingEntry.ProximityKind kind) {
        return get("SemanticSearch.ProximityKind." + kind.name());
    }

    /** Hybrid hint: where the entry was in each list; an absent position is shown as a dash. */
    public String ranks(Integer semantic, Integer lexical) {
        String none = get("SemanticSearch.Ranks.Absent");
        return get("SemanticSearch.Ranks", semantic == null ? none : String.valueOf(semantic),
                lexical == null ? none : String.valueOf(lexical));
    }

    public String kind(String kind) {
        return get("SemanticSearch.Kind." + kind);
    }

    public String notice(Notice n) {
        return get("SemanticSearch.Notice." + n.getCode().name(), n.getArgs().toArray());
    }

    /** Hint of the "more results" control when the ceiling is reached (FR-007). */
    public String ceilingHint(int maxDepth) {
        return get("SemanticSearch.MoreResults.CeilingTip", String.valueOf(maxDepth));
    }

    /** Lines declaring an ordering, in display order. */
    public List<String> declaration(Ordering o) {
        List<String> lines = new ArrayList<>();
        lines.add(get("SemanticSearch.Declaration.Ranking"));
        if (o.getMode() == TextMode.HYBRID) {
            lines.add(o.getLexicalMatches() > 0
                    ? get("SemanticSearch.Declaration.Mode.Hybrid", String.valueOf(o.getLexicalMatches()))
                    : get("SemanticSearch.Declaration.Mode.HybridNone"));
        } else {
            lines.add(get("SemanticSearch.Declaration.Mode.Semantic"));
        }
        lines.add(get("SemanticSearch.Declaration.Depth", String.valueOf(o.getShown()), String.valueOf(o.getCeiling())));
        lines.add(get("SemanticSearch.Declaration.Method." + o.getMethod().name()));
        Coverage c = o.getCoverage();
        lines.add(get("SemanticSearch.Declaration.Coverage", String.valueOf(c.getCovered()),
                String.valueOf(c.getRecorteSize())));
        if (!c.getNotCovered().isEmpty()) {
            List<String> parts = new ArrayList<>();
            c.getNotCovered().forEach((r, n) -> parts.add(reason(r) + ": " + n));
            lines.add(get("SemanticSearch.Declaration.NotCovered", String.join("; ", parts)));
        }
        if (c.isPartial()) {
            lines.add(get("SemanticSearch.Declaration.Partial"));
        }
        Ordering.ModelInfo m = o.getModel();
        lines.add(get("SemanticSearch.Declaration.Model", m.getId(), get("SemanticSearch.Declaration.Source." + m.getSource())));
        if (m.getSentTo() != null) {
            lines.add(get("SemanticSearch.Declaration.SentTo", m.getSentTo()));
        }
        for (Notice n : o.getNotices()) {
            lines.add(notice(n));
        }
        return lines;
    }

    /** Statement and remedy of an unavailable modality (FR-027, FR-033, FR-016). */
    public List<String> unavailable(Availability a) {
        List<String> lines = new ArrayList<>();
        Availability.Reason r = a.getReason();
        String caseModel = a.getCaseModel() == null ? "?" : a.getCaseModel().describe();
        String current = a.getCurrentModel() == null ? "?" : a.getCurrentModel().describe();
        lines.add(get(r.messageKey(), a.getModality() == null ? "" : modality(a.getModality().key()), caseModel,
                current));
        lines.add(get(r.remedyKey()));
        if (a.getDetail() != null) {
            lines.add(get("SemanticSearch.Unavailable.Detail", a.getDetail()));
        }
        return lines;
    }

    public String modality(String key) {
        return get("SemanticSearch.Modality." + key);
    }
}
