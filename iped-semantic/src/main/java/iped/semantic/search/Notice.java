package iped.semantic.search;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A declaration attached to an ordering: a stable code (what the MCP returns) and arguments; the
 * localized text comes from {@code DeclarationText}.
 */
public final class Notice {

    public enum Code {
        /** The question exceeded {@code maxQueryChars} and was truncated. Args: limit, original length. */
        QUERY_TRUNCATED,
        /** The question looks like an identifier: keyword search is the exact tool for it. */
        IDENTIFIER_LIKE,
        /** The scope is empty: there is nowhere to ask. */
        RECORTE_EMPTY,
        /** Hybrid mode where the word part matched nothing: the ordering is the semantic one. */
        LEXICAL_NONE,
        /** Cases left out of a multi-case question. Args: case name, reason code. */
        CASES_EXCLUDED,
        /** The modality's preparation is not complete; coverage is partial. */
        PARTIAL_PREPARATION
    }

    private final Code code;
    private final List<Object> args;

    public Notice(Code code, Object... args) {
        this.code = code;
        this.args = Collections.unmodifiableList(Arrays.asList(args));
    }

    public Code getCode() {
        return code;
    }

    public List<Object> getArgs() {
        return args;
    }

    @Override
    public String toString() {
        return code + (args.isEmpty() ? "" : args.toString());
    }
}
