package iped.rcp.core.profiles;

import java.util.Objects;

/**
 * A non-{@code key=value} config file ({@code conf/*.xml}, {@code conf/*.json})
 * exposed by the profile editor as raw text (feature 005, US3, data-model §4 /
 * contracts/profile-editor.contract.md): "complete" coverage without a per-format
 * visual parser. Editing the {@link #content()} marks it overridden; only
 * overridden files are copied into {@code profiles/<name>/} on save.
 */
public final class AdvancedFile {

    private final String fileName;
    private final String baseContent;
    private String content;
    private boolean overridden;

    /**
     * @param fileName    path relative to the config root (e.g. {@code conf/CarverConfig.xml})
     * @param baseContent the canonical file content
     * @param content     the effective content (base, or the profile's override)
     * @param overridden  whether the edited profile redefines this file
     */
    public AdvancedFile(String fileName, String baseContent, String content, boolean overridden) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("AdvancedFile.fileName must not be blank");
        }
        this.fileName = fileName;
        this.baseContent = baseContent == null ? "" : baseContent;
        this.content = content == null ? this.baseContent : content;
        this.overridden = overridden;
    }

    public String fileName() {
        return fileName;
    }

    public String baseContent() {
        return baseContent;
    }

    public String content() {
        return content;
    }

    public boolean overridden() {
        return overridden;
    }

    /** Sets the content; marks the file overridden iff it differs from the base. */
    public void setContent(String content) {
        this.content = content == null ? "" : content;
        this.overridden = !this.content.equals(baseContent);
    }

    /** Drops the override: restores the base content. */
    public void resetToBase() {
        this.content = baseContent;
        this.overridden = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AdvancedFile other)) {
            return false;
        }
        return fileName.equals(other.fileName) && Objects.equals(content, other.content)
                && overridden == other.overridden;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, content, overridden);
    }

    @Override
    public String toString() {
        return fileName + (overridden ? " (overridden)" : "");
    }
}
