package iped.rcp.core.profiles;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A single {@code key = value} configuration option of a profile, as rendered
 * by the full profile editor (feature 005, US3, data-model §4).
 *
 * <p>
 * The {@code key}, {@code baseValue}, {@code description} and {@code type} are
 * read from the release's canonical config files and are immutable. The
 * {@code effectiveValue} and {@code overridden} flag are mutated by the editor:
 * {@link #setValue(String)} marks the option as overridden by the profile being
 * edited, while {@link #resetToBase()} drops the override. Only overridden
 * options are written back to {@code profiles/<name>/} (lean profiles, FR-018).
 */
public final class ConfigOption {

    /** Inferred editor control type for the option's value. */
    public enum Type {
        BOOLEAN, INT, TEXT, ENUM
    }

    private static final Pattern INT_PATTERN = Pattern.compile("-?\\d+");

    private final String key;
    private final String baseValue;
    private final String description;
    private final Type type;

    private String effectiveValue;
    private boolean overridden;

    /**
     * @param key            the config key
     * @param baseValue      value in the canonical release config, or {@code null}
     *                       when the key exists only in the profile (unknown key)
     * @param effectiveValue base value overridden by the profile being edited
     * @param overridden     whether the edited profile redefines this key
     * @param description    {@code #} comment block preceding the key (may be empty)
     * @param type           inferred value type ({@link #inferType(String)})
     */
    public ConfigOption(String key, String baseValue, String effectiveValue, boolean overridden, String description,
            Type type) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("ConfigOption.key must not be blank");
        }
        this.key = key;
        this.baseValue = baseValue;
        this.effectiveValue = effectiveValue;
        this.overridden = overridden;
        this.description = description == null ? "" : description;
        this.type = type == null ? Type.TEXT : type;
    }

    public String key() {
        return key;
    }

    public String baseValue() {
        return baseValue;
    }

    public String effectiveValue() {
        return effectiveValue;
    }

    public boolean overridden() {
        return overridden;
    }

    public String description() {
        return description;
    }

    public Type type() {
        return type;
    }

    /** Sets the value and marks the option as overridden by the profile (editor edit). */
    public void setValue(String value) {
        this.effectiveValue = value;
        this.overridden = true;
    }

    /** Drops the override: restores the base value and clears the overridden flag. */
    public void resetToBase() {
        this.effectiveValue = baseValue;
        this.overridden = false;
    }

    /** Explicitly toggles the overridden flag (e.g. an editor checkbox) without changing the value. */
    public void setOverridden(boolean overridden) {
        this.overridden = overridden;
    }

    /** @return true when the key exists in the canonical config (has a base value). */
    public boolean isKnown() {
        return baseValue != null;
    }

    /**
     * Heuristic value-type inference (data-model §4): {@code true/false} →
     * {@link Type#BOOLEAN}, integer literal → {@link Type#INT}, otherwise
     * {@link Type#TEXT}. {@link Type#ENUM} is not auto-inferred (no metadata).
     */
    public static Type inferType(String value) {
        if (value == null) {
            return Type.TEXT;
        }
        String v = value.trim();
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
            return Type.BOOLEAN;
        }
        if (!v.isEmpty() && INT_PATTERN.matcher(v).matches()) {
            return Type.INT;
        }
        return Type.TEXT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConfigOption other)) {
            return false;
        }
        return key.equals(other.key) && Objects.equals(effectiveValue, other.effectiveValue)
                && overridden == other.overridden;
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, effectiveValue, overridden);
    }

    @Override
    public String toString() {
        return key + "=" + effectiveValue + (overridden ? " (overridden)" : "");
    }
}
