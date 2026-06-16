package iped.rcp.core.processing;

import java.nio.file.Path;

/**
 * A single evidence source of a {@link NewCaseRequest} (feature 005, FR-004).
 * Maps to the Bootstrap triplet {@code -d <path> [-dname <displayName>]
 * [-p <password>]}.
 *
 * @param path        the evidence path (image file, folder, case, report, …);
 *                    never {@code null}
 * @param displayName optional display name ({@code -dname}); {@code null}/blank
 *                    when not set (Bootstrap defaults to the file name)
 * @param password    optional password for encrypted images/volumes
 *                    ({@code -p}); {@code null}/blank when not set
 */
public record DataSourceEntry(Path path, String displayName, String password) {

    public DataSourceEntry {
        if (path == null) {
            throw new IllegalArgumentException("DataSourceEntry.path must not be null");
        }
    }

    /** Convenience for a source without display name or password. */
    public static DataSourceEntry of(Path path) {
        return new DataSourceEntry(path, null, null);
    }

    /** @return the effective evidence name (display name if set, else file name). */
    public String effectiveName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : path.toString();
    }
}
