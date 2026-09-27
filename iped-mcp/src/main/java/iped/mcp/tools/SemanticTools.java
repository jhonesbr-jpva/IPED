package iped.mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import iped.data.IItem;
import iped.engine.config.ConfigurationManager;
import iped.engine.data.IPEDSource;
import iped.mcp.config.McpServerConfig;
import iped.mcp.config.McpServerConfig.ContentClass;
import iped.mcp.egress.EgressPolicy;
import iped.mcp.item.ContentAccess;
import iped.mcp.protocol.McpError;
import iped.mcp.protocol.ToolDescriptor;
import iped.mcp.query.PagedSearcher;
import iped.mcp.session.OpenCase;
import iped.mcp.session.Session;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.TextMode;
import iped.semantic.model.EmbedderFactory;
import iped.semantic.model.EmbedderProvider;
import iped.semantic.prep.FrameInstant;
import iped.semantic.search.Availability;
import iped.semantic.search.CaseItems;
import iped.semantic.search.ContextReader;
import iped.semantic.search.Coverage;
import iped.semantic.search.DeclarationText;
import iped.semantic.search.Notice;
import iped.semantic.search.Ordering;
import iped.semantic.search.OrderingEntry;
import iped.semantic.search.ReferenceNotCoveredException;
import iped.semantic.search.SemanticRequest;
import iped.semantic.search.SemanticRequest.Reference;
import iped.semantic.search.SemanticRequest.Target;
import iped.semantic.search.SemanticSearcher;
import iped.semantic.search.SemanticUnavailableException;
import iped.semantic.store.BuildRecord;
import iped.semantic.store.BuildRecord.ModalityRecord;
import iped.semantic.store.CoverageReason;
import iped.semantic.store.SequenceLine;

/**
 * Semantic search tools (009): {@code iped_semantic_status}, {@code iped_semantic_search},
 * {@code iped_semantic_similar} and {@code iped_semantic_context}.
 *
 * <p>
 * Together they are the retrieval layer of a RAG built in the harness (research D21): they find,
 * they cite - item and literal range in every entry - and they widen the context. None of them calls
 * a language model, and none computes anything of its own: requests go to the same
 * {@link SemanticSearcher} the analysis UI uses, which is what makes the two orderings identical
 * (SC-011).
 *
 * <p>
 * An ordering is a ranking, not a set. Nothing here reports a total, a match count or an empty
 * result meaning "nothing found" (FR-007), and every response carries the notice that says so
 * (FR-040). No vector and no internal score leaves through these tools (FR-025): proximity always
 * comes labelled with its kind.
 *
 * <p>
 * The ranking tools declare {@code metadata}, not {@code text}. With text blocked, the dispatcher's
 * boundary gate would refuse them whole, and the examiner must still receive the ordering without
 * the excerpts (story 5, scenario 2). Each excerpt and each message line is checked item by item
 * instead, and withheld in place with the rule that withheld it. The context tool is text through
 * and through, so it declares {@code text} and the boundary decides first.
 *
 * <p>
 * The four tools are always listed, also in an installation or a case without the capability: the
 * agent has to learn that semantic search exists and why a case cannot answer, not infer it from a
 * missing tool.
 */
public class SemanticTools {

    private static final Logger LOGGER = LoggerFactory.getLogger(SemanticTools.class);

    /** Orderings kept for paging: the list of the ceiling is computed once per question. */
    private static final int COMPUTED_ORDERINGS_KEPT = 8;

    private static final String CURSOR_PREFIX = "sem1";

    private static final String END_NOTE = "This is the end of the ordering: every covered unit in the scope was "
            + "ranked. Items that are not covered were never compared - they are counted, with the reason, in "
            + "coverage.not_covered - so their absence here says nothing about their content.";

    private static final String ITEM_HINT = "Pass the item_id of an entry returned by iped_semantic_search or "
            + "iped_search on this same case.";

    private final Session session;
    private final PagedSearcher pagedSearcher;
    private final ContentAccess contentAccess;
    /**
     * Guarded by its own lock, not by this object's: closing a case calls {@link #release} while the
     * case registry is locked, and a handler holding this object's lock may be waiting on the
     * registry.
     */
    private final Map<OpenCase, SemanticSearcher> searchers = new IdentityHashMap<>();
    private final Object searchersLock = new Object();
    private final Map<String, Ordering> computed = Collections
            .synchronizedMap(new LinkedHashMap<String, Ordering>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Ordering> eldest) {
                    return size() > COMPUTED_ORDERINGS_KEPT;
                }
            });
    private SemanticSearchConfig config;
    private EmbedderProvider provider;

    public SemanticTools(Session session, PagedSearcher pagedSearcher, ContentAccess contentAccess) {
        this.session = session;
        this.pagedSearcher = pagedSearcher;
        this.contentAccess = contentAccess;
        session.getCaseRegistry().addCloseListener(this::release);
    }

    public List<ToolDescriptor> descriptors() {
        List<ToolDescriptor> tools = new ArrayList<>();

        tools.add(new ToolDescriptor("iped_semantic_status",
                "Reports what semantic search a case offers, without running a search. For each modality - text "
                        + "(passages, transcripts, conversations) and visual (images and video frames) - whether it "
                        + "can answer now and, if not, why and what to do; the model and where it runs; how the "
                        + "case was prepared; and how many items are covered, with the reason for each one that "
                        + "is not. Call it before the first semantic question on a case.",
                arguments -> status(arguments))
                        .required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .returnsContent(ContentClass.metadata.name()));

        tools.add(new ToolDescriptor("iped_semantic_search",
                "Ranks the passages, conversations, images and video frames of a case by closeness in meaning to "
                        + "a question in natural language, and returns one page of the ranking. It is a ranking, "
                        + "not a set: there is no total, the end of the list is a depth ceiling, and an item "
                        + "further down or absent is not evidence that the subject does not occur. Use iped_search "
                        + "for identifiers (names, numbers, e-mail addresses), exact words and any claim of "
                        + "absence. Every text entry is citable: item_id with the ordinal and char_start/char_end "
                        + "of each excerpt, or the item_id of each message; widen one with iped_semantic_context. "
                        + "A visual entry carries no picture: call iped_item_thumbnail to look at it. Restrict "
                        + "with query and/or bookmark exactly as in iped_search. Page with next_cursor, passing "
                        + "the same arguments again: the ranking is computed once, so paging neither re-runs nor "
                        + "reorders it.",
                arguments -> search(arguments))
                        .required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .required("question", "string",
                                "What to look for, in natural language, in any language. Describe the subject "
                                        + "rather than listing keywords. Past the server's length limit it is "
                                        + "truncated, and a notice says so.")
                        .optional("modality", "string",
                                "text (default), visual, or both. With both, two orderings come back, one per "
                                        + "modality, never merged: their proximities are not comparable.")
                        .optional("mode", "string",
                                "Text only. semantic ranks by meaning alone; hybrid also ranks the words of the "
                                        + "question and fuses the two rankings by position - use it when the "
                                        + "question mixes a concept with an exact term (a nickname, a code). "
                                        + "Defaults to the server's defaultTextMode.")
                        .optional("query", "string",
                                "IPED query expression restricting where to rank, with the same syntax and "
                                        + "diagnostics as iped_search. Omit it to rank over the whole case.")
                        .optional("bookmark", "string",
                                "Restrict the ranking to the items of this bookmark, by its exact name. Combines "
                                        + "with query.")
                        .optional("page_size", "integer",
                                "Entries per page. Defaults to the server default and is capped by its ceiling.")
                        .optional("cursor", "string",
                                "next_cursor from the previous page, with the same other arguments. Omit for the "
                                        + "first page. With modality both, each ordering has its own cursor and a "
                                        + "cursor continues that ordering only.")
                        .returnsContent(ContentClass.metadata.name()));

        tools.add(new ToolDescriptor("iped_semantic_similar",
                "More like this: ranks the passages and conversations of a case by closeness in meaning to a "
                        + "reference item, or to a range of its text (char_start/char_end of an excerpt). Text "
                        + "modality only. The reference item does not appear in its own ranking. Same result, "
                        + "paging and restrictions as iped_semantic_search, and the same rule: a ranking, not a "
                        + "set.",
                arguments -> similar(arguments))
                        .required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .required("item_id", "integer",
                                "The reference item. For a conversation, pass the item_id of one of its "
                                        + "messages.")
                        .optional("char_start", "integer",
                                "Start of a range of the item's text to use as the reference instead of the "
                                        + "whole item. Pass char_end with it.")
                        .optional("char_end", "integer", "End of the range, exclusive. Pass char_start with it.")
                        .optional("query", "string",
                                "IPED query expression restricting where to rank, as in iped_search.")
                        .optional("bookmark", "string", "Restrict the ranking to the items of this bookmark.")
                        .optional("page_size", "integer",
                                "Entries per page. Defaults to the server default and is capped by its ceiling.")
                        .optional("cursor", "string",
                                "next_cursor from the previous page, with the same other arguments.")
                        .returnsContent(ContentClass.metadata.name()));

        tools.add(new ToolDescriptor("iped_semantic_context",
                "Returns the literal text around one entry of a semantic ranking, read from the semantic index "
                        + "without extracting the whole item: the neighbouring passages stitched by their "
                        + "offsets - exactly the range char_start..char_end of the item's text, overlap not "
                        + "repeated - or the neighbouring messages of a conversation. Use it to read more before "
                        + "answering, and cite the range it returns.",
                arguments -> context(arguments))
                        .required("case_id", "string", "Case identifier returned by iped_open_case.")
                        .required("item_id", "integer",
                                "item_id of the entry: the item of a passage, or the chat of a sequence.")
                        .required("ordinal", "integer",
                                "ordinal of the excerpt or sequence, as the ranking returned it.")
                        .optional("before", "integer", "Passages (or conversation windows) before it. Default 1.")
                        .optional("after", "integer", "Passages (or conversation windows) after it. Default 1.")
                        .optional("max_chars", "integer",
                                "Largest amount of text to return; capped by the server's contextMaxChars. "
                                        + "When the cap cuts the context, truncated comes back true.")
                        .returnsContent(ContentClass.text.name()));

        return tools;
    }

    // ---------------------------------------------------------------------------------------------
    // Handlers
    // ---------------------------------------------------------------------------------------------

    private synchronized Object status(JsonNode arguments) throws IOException {
        Args.rejectUnknown(arguments, "iped_semantic_status", "case_id");
        OpenCase openCase = requireCase(arguments);
        SemanticSearcher searcher = searcherFor(openCase);
        SemanticSearchConfig cfg = config();

        Map<String, Object> modalities = new LinkedHashMap<>();
        for (Modality m : Modality.values()) {
            modalities.put(m.key(), describe(searcher, m));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("modalities", modalities);
        result.put("default_depth", cfg.getDefaultDepth());
        result.put("max_depth", cfg.getMaxDepth());
        result.put("default_text_mode", cfg.getDefaultTextMode().name().toLowerCase(Locale.ROOT));
        result.put("ordering_notice", text().orderingNotice());
        return result;
    }

    private synchronized Object search(JsonNode arguments) {
        Args.rejectUnknown(arguments, "iped_semantic_search", "case_id", "question", "modality", "mode", "query",
                "bookmark", "page_size", "cursor");
        OpenCase openCase = requireCase(arguments);
        String question = Args.requiredString(arguments, "question",
                "Pass what to look for in natural language, for example: conversations arranging a cash "
                        + "payment.");
        Target target = target(Args.optionalString(arguments, "modality", null));
        TextMode mode = mode(Args.optionalString(arguments, "mode", null));
        Scope scope = scope(openCase, arguments);
        SemanticRequest.Builder request = SemanticRequest.builder().target(target).query(question).mode(mode)
                .scope(scope.ids);
        return run(openCase, "iped_semantic_search", request, target, scope, arguments, null);
    }

    private synchronized Object similar(JsonNode arguments) {
        Args.rejectUnknown(arguments, "iped_semantic_similar", "case_id", "item_id", "char_start", "char_end",
                "query", "bookmark", "page_size", "cursor");
        OpenCase openCase = requireCase(arguments);
        int itemId = Args.requiredInt(arguments, "item_id", ITEM_HINT);
        contentAccess.requireItem(openCase, itemId);
        Integer start = Args.optionalInt(arguments, "char_start");
        Integer end = Args.optionalInt(arguments, "char_end");
        if ((start == null) != (end == null)) {
            throw new McpError(McpError.INVALID_ARGUMENT, "char_start and char_end go together; only one was given.",
                    "Pass both, as an excerpt of iped_semantic_search returned them, or neither to use the "
                            + "whole item.").with("parameter", start == null ? "char_start" : "char_end");
        }
        if (start != null && (start < 0 || end <= start)) {
            throw new McpError(McpError.INVALID_ARGUMENT,
                    "The range [" + start + ", " + end + ") is empty or negative.",
                    "Pass char_start < char_end, both from an excerpt of this item.").with("parameter", "char_end");
        }
        Reference reference = start == null ? Reference.item(itemId) : Reference.range(itemId, start, end);
        Scope scope = scope(openCase, arguments);
        SemanticRequest.Builder request = SemanticRequest.builder().target(Target.TEXT).reference(reference)
                .scope(scope.ids);
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("item_id", itemId);
        if (start != null) {
            ref.put("char_start", start);
            ref.put("char_end", end);
        }
        return run(openCase, "iped_semantic_similar", request, Target.TEXT, scope, arguments, ref);
    }

    private synchronized Object context(JsonNode arguments) {
        Args.rejectUnknown(arguments, "iped_semantic_context", "case_id", "item_id", "ordinal", "before", "after",
                "max_chars");
        OpenCase openCase = requireCase(arguments);
        int itemId = Args.requiredInt(arguments, "item_id", ITEM_HINT);
        int ordinal = Args.requiredInt(arguments, "ordinal",
                "Pass the ordinal of the excerpt or sequence as iped_semantic_search returned it.");
        IItem item = contentAccess.requireItem(openCase, itemId);
        session.getEgressPolicy().enforce(ContentClass.text, item);
        int before = nonNegative(arguments, "before", 1);
        int after = nonNegative(arguments, "after", 1);
        int ceiling = config().getContextMaxChars();
        Integer asked = Args.optionalInt(arguments, "max_chars");
        int maxChars = asked == null || asked <= 0 ? ceiling : Math.min(asked, ceiling);

        ContextReader.Context context;
        try {
            context = searcherFor(openCase).context(itemId, ordinal, before, after, maxChars);
        } catch (SemanticUnavailableException e) {
            throw unavailable(e);
        } catch (IOException e) {
            throw internal(e);
        }
        if (context == null) {
            throw new McpError(McpError.ENTRY_NOT_FOUND,
                    "The semantic index of case " + openCase.getCaseId() + " has no entry for item " + itemId
                            + " with ordinal " + ordinal + ".",
                    "Pass the item_id and ordinal of an entry exactly as iped_semantic_search or "
                            + "iped_semantic_similar returned them: for a conversation, the item_id of the entry "
                            + "is the chat, not a message.").with("item_id", itemId).with("ordinal", ordinal);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        result.put("item_id", context.getItemId());
        result.put("kind", context.getKind());
        result.put("ordinal", ordinal);
        if (context.getMessages() != null) {
            Withholding withholding = new Withholding(openCase);
            List<Map<String, Object>> messages = new ArrayList<>();
            for (SequenceLine line : context.getMessages()) {
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("item_id", line.getItemId());
                message.put("sender", line.getSender());
                message.put("date", line.getDate());
                Map<String, Object> withheld = withholding.check(line.getItemId());
                if (withheld == null) {
                    message.put("text", line.getText());
                } else {
                    message.put("text_withheld", withheld);
                }
                messages.add(message);
            }
            result.put("messages", messages);
        } else {
            result.put("char_start", context.getCharStart());
            result.put("char_end", context.getCharEnd());
            result.put("text", context.getText());
        }
        result.put("truncated", context.isTruncated());
        return result;
    }

    // ---------------------------------------------------------------------------------------------
    // Running a question and rendering its orderings
    // ---------------------------------------------------------------------------------------------

    /** The scope of a question: ids, the plan that selected them, and the bookmark. */
    private static final class Scope {
        final BitSet ids;
        final PagedSearcher.QueryPlan plan;
        final String bookmark;

        Scope(BitSet ids, PagedSearcher.QueryPlan plan, String bookmark) {
            this.ids = ids;
            this.plan = plan;
            this.bookmark = bookmark;
        }
    }

    private Scope scope(OpenCase openCase, JsonNode arguments) {
        String query = Args.optionalString(arguments, "query", null);
        String bookmark = Args.optionalString(arguments, "bookmark", null);
        boolean noQuery = query == null || query.trim().isEmpty();
        boolean noBookmark = bookmark == null || bookmark.trim().isEmpty();
        if (noQuery && noBookmark) {
            return new Scope(null, null, null);
        }
        PagedSearcher.QueryPlan plan = pagedSearcher.plan(openCase, noQuery ? PagedSearcher.MATCH_ALL : query);
        String b = noBookmark ? null : bookmark;
        return new Scope(pagedSearcher.collectIdSet(openCase, plan, b), noQuery ? null : plan, b);
    }

    private Object run(OpenCase openCase, String tool, SemanticRequest.Builder builder, Target target, Scope scope,
            JsonNode arguments, Map<String, Object> reference) {
        SemanticRequest request;
        try {
            request = builder.build(config());
        } catch (IllegalArgumentException e) {
            throw new McpError(McpError.INVALID_ARGUMENT, "The request is not valid: " + e.getMessage() + ".",
                    "Pass a question in words, or a reference item with iped_semantic_similar.");
        }
        String key = requestKey(openCase, tool, request);
        List<Modality> asked = modalitiesOf(target);
        CursorPosition cursor = CursorPosition.decode(Args.optionalString(arguments, "cursor", null), key, asked);
        List<Modality> modalities = cursor != null ? List.of(cursor.modality) : asked;
        int pageSize = pageSize(Args.optionalInt(arguments, "page_size"));

        SemanticSearcher searcher = searcherFor(openCase);
        Map<String, Object> orderings = new LinkedHashMap<>();
        List<Map<String, Object>> notices = new ArrayList<>();
        McpError firstRefusal = null;
        boolean anyOrdering = false;
        for (Modality m : modalities) {
            Ordering ordering;
            try {
                ordering = ordering(searcher, request, m, key);
            } catch (SemanticUnavailableException e) {
                McpError refusal = unavailable(e);
                if (firstRefusal == null) {
                    firstRefusal = refusal;
                }
                Map<String, Object> declared = new LinkedHashMap<>();
                declared.put("available", false);
                declared.put("code", refusal.getCode());
                declared.put("message", refusal.getMessage());
                declared.put("remedy", refusal.getRemedy());
                declared.putAll(refusal.getDetails());
                orderings.put(m.key(), declared);
                continue;
            } catch (ReferenceNotCoveredException e) {
                throw referenceNotCovered(e);
            } catch (IOException e) {
                throw internal(e);
            }
            anyOrdering = true;
            orderings.put(m.key(), render(openCase, ordering, cursor == null ? 0 : cursor.offset, pageSize, key));
            for (Notice n : ordering.getNotices()) {
                Map<String, Object> notice = new LinkedHashMap<>();
                notice.put("code", n.getCode().name());
                notice.put("modality", m.key());
                notice.put("message", noticeText(n));
                notices.add(notice);
            }
        }
        if (!anyOrdering) {
            // A modality that cannot answer is a refusal, never an empty ordering (FR-007).
            throw firstRefusal;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", openCase.getCaseId());
        if (reference != null) {
            result.put("reference", reference);
        }
        if (scope.plan != null) {
            result.put("query", scope.plan.getRequestedExpression());
            PagedSearcher.declareNormalization(result, scope.plan);
        }
        if (scope.bookmark != null) {
            result.put("bookmark", scope.bookmark);
        }
        result.put("orderings", orderings);
        result.put("notices", notices);
        result.put("ordering_notice", text().orderingNotice());
        return result;
    }

    /** The whole list of the ceiling, computed once per question and modality. */
    private Ordering ordering(SemanticSearcher searcher, SemanticRequest request, Modality m, String key)
            throws SemanticUnavailableException, IOException {
        String cacheKey = key + "|" + m.key();
        Ordering ordering = computed.get(cacheKey);
        if (ordering == null) {
            ordering = searcher.search(request, m);
            computed.put(cacheKey, ordering);
        }
        return ordering;
    }

    private Map<String, Object> render(OpenCase openCase, Ordering ordering, int offset, int pageSize, String key) {
        List<OrderingEntry> all = ordering.getAll();
        int from = Math.min(offset, all.size());
        int to = Math.min(all.size(), from + pageSize);

        Map<String, Object> out = new LinkedHashMap<>();
        if (ordering.getModality() == Modality.TEXT) {
            out.put("mode", ordering.getMode().name().toLowerCase(Locale.ROOT));
        }
        out.put("method", ordering.getMethod().name().toLowerCase(Locale.ROOT));
        if (ordering.getModality() == Modality.TEXT && ordering.getMode() == TextMode.HYBRID) {
            out.put("lexical_matches", Math.max(0, ordering.getLexicalMatches()));
        }
        out.put("ceiling", ordering.getCeiling());
        out.put("returned", to - from);
        out.put("entries", entries(openCase, all.subList(from, to)));
        out.put("coverage", coverage(ordering.getCoverage()));
        out.put("model", model(ordering.getModel()));
        if (to < all.size()) {
            out.put("next_cursor", CursorPosition.encode(ordering.getModality(), to, key));
        } else {
            boolean ceilingReached = all.size() >= ordering.getCeiling();
            out.put("ceiling_reached", ceilingReached);
            if (ceilingReached) {
                out.put("ceiling_note", text().ceilingHint(ordering.getCeiling()));
            } else {
                out.put("end_note", END_NOTE);
            }
        }
        return out;
    }

    private List<Map<String, Object>> entries(OpenCase openCase, List<OrderingEntry> page) {
        Withholding withholding = new Withholding(openCase);
        List<Map<String, Object>> out = new ArrayList<>(page.size());
        for (OrderingEntry e : page) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rank", e.getRank());
            entry.put("proximity", Math.round(e.getProximity() * 10000d) / 10000d);
            entry.put("proximity_kind", e.getProximityKind().code());
            if (e.getProximityKind() == OrderingEntry.ProximityKind.COMBINED_RELATIVE) {
                // Which list the entry came from; an entry without "lexical" rose by meaning alone.
                Map<String, Object> ranks = new LinkedHashMap<>();
                if (e.getSemanticRank() != null) {
                    ranks.put("semantic", e.getSemanticRank());
                }
                if (e.getLexicalRank() != null) {
                    ranks.put("lexical", e.getLexicalRank());
                }
                entry.put("ranks", ranks);
            }
            entry.put("kind", e.getKind());
            entry.put("item_id", e.getItemId());
            Map<String, Object> withheld = null;
            if (e.getSequence() != null) {
                entry.put("ordinal", e.getOrdinal());
                List<Map<String, Object>> messages = new ArrayList<>();
                for (OrderingEntry.MessageLine line : e.getSequence().getMessages()) {
                    Map<String, Object> message = new LinkedHashMap<>();
                    message.put("item_id", line.getItemId());
                    message.put("sender", line.getSender());
                    message.put("date", line.getDate());
                    Map<String, Object> blocked = withholding.check(line.getItemId());
                    if (blocked == null) {
                        message.put("text", line.getText());
                    } else {
                        message.put("text_withheld", blocked);
                        if (withheld == null) {
                            withheld = blocked;
                        }
                    }
                    message.put("in_recorte", line.isInScope());
                    messages.add(message);
                }
                Map<String, Object> sequence = new LinkedHashMap<>();
                sequence.put("messages", messages);
                sequence.put("absorbed_windows", e.getSequence().getAbsorbedWindows());
                entry.put("sequence", sequence);
            } else if (e.getFrame() != null) {
                OrderingEntry.Frame f = e.getFrame();
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("frame_index", f.getFrameIndex());
                if (f.getFrameCount() != null) {
                    frame.put("frame_count", f.getFrameCount());
                }
                if (f.getInstantApproxMs() != null) {
                    frame.put("instant_approx", FrameInstant.approxLabel(f.getInstantApproxMs()));
                    frame.put("instant_is_approximate", true);
                }
                entry.put("frame", frame);
            } else if (!e.getExcerpts().isEmpty()) {
                entry.put("ordinal", e.getOrdinal());
                withheld = withholding.check(e.getItemId());
                List<Map<String, Object>> excerpts = new ArrayList<>();
                for (OrderingEntry.Excerpt x : e.getExcerpts()) {
                    Map<String, Object> excerpt = new LinkedHashMap<>();
                    excerpt.put("ordinal", x.getOrdinal());
                    excerpt.put("char_start", x.getCharStart());
                    excerpt.put("char_end", x.getCharEnd());
                    if (withheld == null) {
                        excerpt.put("text", x.getText());
                    }
                    excerpts.add(excerpt);
                }
                entry.put("excerpts", excerpts);
            }
            if (withheld != null) {
                entry.put("excerpt_withheld", withheld);
            }
            out.add(entry);
        }
        return out;
    }

    /**
     * The egress check of each excerpt, item by item and once per item on a page. With the policy
     * inactive nothing is looked up.
     */
    private final class Withholding {
        private final OpenCase openCase;
        private final EgressPolicy policy = session.getEgressPolicy();
        private final Map<Integer, Map<String, Object>> decided = new HashMap<>();

        Withholding(OpenCase openCase) {
            this.openCase = openCase;
        }

        /** {@code null} when the text of the item may be returned; else the rule that withholds it. */
        Map<String, Object> check(int itemId) {
            if (!policy.isActive()) {
                return null;
            }
            if (decided.containsKey(itemId)) {
                return decided.get(itemId);
            }
            Map<String, Object> withheld = null;
            IItem item = openCase.getSource().getItemByID(itemId);
            if (item == null) {
                // The policy cannot be verified without the item, so the text does not go out.
                withheld = withheld("unverifiable", "item " + itemId + " could not be read to check the policy");
            } else {
                EgressPolicy.Decision decision = policy.check(ContentClass.text, item);
                if (!decision.allowed) {
                    withheld = withheld(decision.rule, decision.detail);
                }
            }
            decided.put(itemId, withheld);
            return withheld;
        }

        private Map<String, Object> withheld(String rule, String reason) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("policy_rule", rule);
            w.put("reason", reason);
            return w;
        }
    }

    private static Map<String, Object> coverage(Coverage c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recorte_size", c.getRecorteSize());
        out.put("covered", c.getCovered());
        out.put("not_covered", reasons(c.getNotCovered()));
        out.put("partial", c.isPartial());
        return out;
    }

    private static Map<String, Long> reasons(Map<CoverageReason, Long> notCovered) {
        Map<String, Long> out = new LinkedHashMap<>();
        notCovered.forEach((reason, count) -> out.put(reason.code(), count));
        return out;
    }

    private static Map<String, Object> model(Ordering.ModelInfo m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("source", m.getSource());
        if (m.getPreparedWith() != null) {
            out.put("prepared_with", m.getPreparedWith());
        }
        if (m.getSentTo() != null) {
            out.put("sent_to", m.getSentTo());
        }
        return out;
    }

    private Map<String, Object> describe(SemanticSearcher searcher, Modality m) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        Availability a = searcher.availability(m);
        out.put("available", a.isAvailable());
        if (!a.isAvailable()) {
            List<String> lines = text().unavailable(a);
            out.put("reason", a.getReason().name());
            out.put("message", lines.get(0));
            out.put("remedy", lines.get(1));
            if (a.getDetail() != null) {
                out.put("detail", a.getDetail());
            }
            if (a.getReason() == Availability.Reason.MODEL_INCOMPATIBLE) {
                out.put("case_model", a.getCaseModel() == null ? null : a.getCaseModel().describe());
                out.put("current_model", a.getCurrentModel() == null ? null : a.getCurrentModel().describe());
            }
        }
        ModalityRecord record = a.getRecord();
        if (record == null) {
            return out;
        }
        // Build details (FR-014). The fingerprint is left out on purpose: it is made of vectors.
        out.put("state", record.state == null ? null : record.state.name());
        if (record.model != null) {
            out.put("model", record.model.id);
            out.put("model_sha256", record.model.sha256);
        }
        out.put("source", a.getSource() == null ? null : a.getSource().name().toLowerCase(Locale.ROOT));
        out.put("prepared_with", record.source);
        if (a.getDestination() != null && a.getSource() == SemanticSearchConfig.Source.REMOTE) {
            out.put("sent_to", a.getDestination());
        }
        if (record.keys != null) {
            out.put("keys", record.keys);
        }
        if (record.timing != null) {
            Object finishedAt = record.timing.get("finishedAt");
            if (finishedAt != null) {
                out.put("built_at", finishedAt);
            }
            out.put("timing", record.timing);
        }
        BuildRecord buildRecord = BuildRecord.read(searcher.getStore().buildRecord());
        if (buildRecord != null) {
            out.put("ipedVersion", buildRecord.ipedVersion);
        }
        if (record.params != null) {
            out.put("params", record.params);
        }
        if (record.conflicts != null && !record.conflicts.isEmpty()) {
            out.put("conflicts", record.conflicts);
        }
        if (record.disabledReason != null) {
            out.put("disabled_reason", record.disabledReason);
        }
        if (searcher.getStore().hasIndex(m)) {
            Coverage c = searcher.coverage(m, null);
            out.put("covered", c.getCovered());
            out.put("not_covered", reasons(c.getNotCovered()));
            out.put("partial", c.isPartial());
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Refusals
    // ---------------------------------------------------------------------------------------------

    /** An unavailable modality: the reason, the modality and the remedy (FR-027, FR-033, FR-016). */
    private McpError unavailable(SemanticUnavailableException e) {
        Availability a = e.getAvailability();
        Availability.Reason reason = a.getReason();
        String code = reason == Availability.Reason.MODALITY_NOT_PREPARED ? McpError.MODALITY_NOT_PREPARED
                : reason == Availability.Reason.MODEL_INCOMPATIBLE ? McpError.MODEL_INCOMPATIBLE
                        : McpError.SEMANTIC_UNAVAILABLE;
        List<String> lines = text().unavailable(a);
        McpError error = new McpError(code, lines.get(0),
                lines.get(1) + " Keyword search (iped_search) is not affected and remains available.")
                        .with("modality", a.getModality().key()).with("reason", reason.name());
        if (a.getDetail() != null) {
            error.with("detail", a.getDetail());
        }
        if (reason == Availability.Reason.MODEL_INCOMPATIBLE) {
            error.with("case_model", a.getCaseModel() == null ? null : a.getCaseModel().describe());
            error.with("current_model", a.getCurrentModel() == null ? null : a.getCurrentModel().describe());
        }
        return error;
    }

    private McpError referenceNotCovered(ReferenceNotCoveredException e) {
        return new McpError(McpError.REFERENCE_NOT_COVERED,
                "Item " + e.getItemId() + " has no semantic representation to compare from: "
                        + text().reason(e.getReason()) + ".",
                "Pick a reference among the entries iped_semantic_search returns, or describe in words what the "
                        + "item is about and ask iped_semantic_search.").with("item_id", e.getItemId())
                                .with("reason", e.getReason().code());
    }

    private static McpError internal(IOException e) {
        LOGGER.error("Semantic search failed", e);
        return new McpError(McpError.INTERNAL_ERROR, "The semantic index could not be read: " + e.getMessage(),
                "Report this with the server log attached. Keyword search (iped_search) is not affected.", e);
    }

    // ---------------------------------------------------------------------------------------------
    // Paging
    // ---------------------------------------------------------------------------------------------

    /**
     * Where the next page starts in the list of the ceiling, bound to the question it belongs to.
     *
     * <p>
     * The key is a digest of the case, the tool, the question or reference, the mode and the ids of
     * the scope. A cursor presented with other arguments - or after the scope changed, a bookmark
     * that gained or lost items - is refused rather than continued: continuing it would splice two
     * different rankings into one list with nothing to say so.
     */
    private static final class CursorPosition {
        final Modality modality;
        final int offset;

        private CursorPosition(Modality modality, int offset) {
            this.modality = modality;
            this.offset = offset;
        }

        static String encode(Modality modality, int offset, String key) {
            String raw = CURSOR_PREFIX + ":" + modality.key() + ":" + offset + ":" + key;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static CursorPosition decode(String cursor, String key, List<Modality> asked) {
            if (cursor == null || cursor.isEmpty()) {
                return null;
            }
            String[] parts;
            Modality modality = null;
            int offset;
            try {
                parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":");
                if (parts.length != 4 || !CURSOR_PREFIX.equals(parts[0])) {
                    throw new IllegalArgumentException();
                }
                for (Modality m : Modality.values()) {
                    if (m.key().equals(parts[1])) {
                        modality = m;
                    }
                }
                offset = Integer.parseInt(parts[2]);
                if (modality == null || offset < 0) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException e) {
                throw refuse(cursor, "The cursor is not one this tool produced.");
            }
            if (!parts[3].equals(key) || !asked.contains(modality)) {
                throw refuse(cursor, "The cursor belongs to another question: the case, the question, the mode, "
                        + "the modality or the scope differ from the page it came from - or the scope changed "
                        + "since then, as when a bookmark gains or loses items.");
            }
            return new CursorPosition(modality, offset);
        }

        private static McpError refuse(String cursor, String message) {
            return new McpError(McpError.INVALID_CURSOR, message,
                    "Pass the next_cursor with exactly the arguments of the page it came from, or omit it to "
                            + "start from the first page.").with("cursor", cursor);
        }
    }

    private static String requestKey(OpenCase openCase, String tool, SemanticRequest request) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            StringBuilder b = new StringBuilder();
            b.append(openCase.getCaseBinding()).append('\n').append(tool).append('\n').append(request.getMode())
                    .append('\n').append(request.getQuery()).append('\n');
            Reference ref = request.getReference();
            if (ref != null) {
                b.append(ref.getItemId()).append(':').append(ref.getCharStart()).append(':').append(ref.getCharEnd());
            }
            b.append('\n');
            sha.update(b.toString().getBytes(StandardCharsets.UTF_8));
            BitSet scope = request.getScope();
            sha.update(scope == null ? new byte[] { 1 } : scope.toByteArray());
            byte[] digest = sha.digest();
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private int pageSize(Integer requested) {
        McpServerConfig mcp = session.getConfig();
        if (requested == null || requested <= 0) {
            return mcp.getDefaultPageSize();
        }
        return Math.min(requested, mcp.getMaxPageSize());
    }

    // ---------------------------------------------------------------------------------------------
    // Arguments
    // ---------------------------------------------------------------------------------------------

    private OpenCase requireCase(JsonNode arguments) {
        return session.getCaseRegistry()
                .require(Args.requiredString(arguments, "case_id", "Pass the case_id returned by iped_open_case."));
    }

    private static Target target(String modality) {
        if (modality == null || modality.isEmpty() || "text".equals(modality)) {
            return Target.TEXT;
        }
        if ("visual".equals(modality)) {
            return Target.VISUAL;
        }
        if ("both".equals(modality)) {
            return Target.BOTH;
        }
        throw new McpError(McpError.INVALID_ARGUMENT, "Unknown modality '" + modality + "'.",
                "Pass text, visual or both.").with("parameter", "modality").with("received", modality);
    }

    private static TextMode mode(String mode) {
        if (mode == null || mode.isEmpty()) {
            return null;
        }
        if ("semantic".equals(mode)) {
            return TextMode.SEMANTIC;
        }
        if ("hybrid".equals(mode)) {
            return TextMode.HYBRID;
        }
        throw new McpError(McpError.INVALID_ARGUMENT, "Unknown mode '" + mode + "'.",
                "Pass semantic or hybrid, or omit it for the server default.").with("parameter", "mode")
                        .with("received", mode);
    }

    private static List<Modality> modalitiesOf(Target target) {
        List<Modality> out = new ArrayList<>();
        if (target != Target.VISUAL) {
            out.add(Modality.TEXT);
        }
        if (target != Target.TEXT) {
            out.add(Modality.VISUAL);
        }
        return out;
    }

    private static int nonNegative(JsonNode arguments, String name, int fallback) {
        Integer value = Args.optionalInt(arguments, name);
        if (value == null) {
            return fallback;
        }
        if (value < 0) {
            throw new McpError(McpError.INVALID_ARGUMENT, "The parameter '" + name + "' cannot be negative.",
                    "Pass 0 or more, or omit it for the default of " + fallback + ".").with("parameter", name);
        }
        return value;
    }

    // ---------------------------------------------------------------------------------------------
    // Session state
    // ---------------------------------------------------------------------------------------------

    /** One searcher per open case: availability, fingerprint and readers are evaluated once. */
    private SemanticSearcher searcherFor(OpenCase openCase) {
        SemanticSearchConfig cfg = config();
        EmbedderProvider embedders = provider();
        synchronized (searchersLock) {
            SemanticSearcher searcher = searchers.get(openCase);
            if (searcher == null) {
                IPEDSource source = openCase.getSource();
                searcher = new SemanticSearcher(source.getModuleDir(), CaseItems.of(source), cfg, embedders);
                searchers.put(openCase, searcher);
            }
            return searcher;
        }
    }

    private void release(OpenCase openCase) {
        SemanticSearcher searcher;
        synchronized (searchersLock) {
            searcher = searchers.remove(openCase);
        }
        computed.clear();
        if (searcher != null) {
            try {
                searcher.close();
            } catch (IOException e) {
                LOGGER.warn("Closing the semantic index of case {} failed", openCase.getCaseId(), e);
            }
        }
    }

    /**
     * The installation's semantic configuration, as the engine loaded it; when the task that owns it
     * is not in the task list, it is registered and loaded here, as {@link McpServerConfig#get()}
     * does for the server's own.
     */
    private synchronized SemanticSearchConfig config() {
        if (config == null) {
            ConfigurationManager manager = ConfigurationManager.get();
            SemanticSearchConfig found = manager == null ? null : manager.findObject(SemanticSearchConfig.class);
            if (found == null) {
                found = new SemanticSearchConfig();
                if (manager != null) {
                    manager.addObject(found);
                    try {
                        manager.loadConfig(found);
                    } catch (IOException e) {
                        LOGGER.warn("conf/SemanticSearchConfig.txt could not be read; semantic search uses its "
                                + "defaults", e);
                    }
                }
            }
            config = found;
        }
        return config;
    }

    private synchronized EmbedderProvider provider() {
        if (provider == null) {
            provider = new EmbedderFactory(config());
        }
        return provider;
    }

    private static DeclarationText text() {
        return DeclarationText.english();
    }

    /** The notice text, pointing at this server's keyword tool where the UI says "keyword search". */
    private static String noticeText(Notice n) {
        String message = text().notice(n);
        return n.getCode() == Notice.Code.IDENTIFIER_LIKE ? message + " Use iped_search." : message;
    }
}
