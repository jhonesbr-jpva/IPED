package iped.rcp.core.profiles;

import java.nio.file.Path;
import java.util.Set;

/**
 * A selectable/editable processing profile (feature 005, data-model §3).
 *
 * @param name    unique profile name (the {@code profiles/<name>} folder name,
 *                also the {@code -profile} value)
 * @param kind    {@link Kind#BUILT_IN} (read-only template) or {@link Kind#USER}
 * @param dir     the profile folder ({@code profiles/<name>/})
 * @param basedOn the profile this one was cloned from, or {@code null}
 */
public record ProfileDescriptor(String name, Kind kind, Path dir, String basedOn) {

    /** Built-in profiles shipped with IPED — treated as read-only templates (FR-018). */
    public static final Set<String> BUILT_IN_NAMES = Set.of("forensic", "pedo", "triage", "fastmode", "blind");

    /** Profile origin. */
    public enum Kind {
        /** Shipped with the product; read-only template. */
        BUILT_IN,
        /** Created/edited by the investigator. */
        USER
    }

    public ProfileDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ProfileDescriptor.name must not be blank");
        }
    }

    /** @return true when this is a shipped, read-only profile. */
    public boolean isBuiltIn() {
        return kind == Kind.BUILT_IN;
    }

    /** @return the {@link Kind} for a profile folder name (built-in by name match). */
    public static Kind kindOf(String name) {
        return BUILT_IN_NAMES.contains(name) ? Kind.BUILT_IN : Kind.USER;
    }
}
