package iped.semantic.store;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import iped.semantic.config.SemanticSearchConfig.Modality;

/**
 * Paths of the semantic search data inside a case (contracts/case-storage.md). Everything lives
 * under {@code <case>/iped/semantic/}; nothing is written to the main index or outside the case.
 *
 * <pre>
 * &lt;case&gt;/iped/semantic/
 *   build-record.json   build record (E2), UTF-8
 *   text/               Lucene index of the text modality
 *   visual/             Lucene index of the visual modality
 *   staging.mv.db       chat staging area (E11), only while processing
 * </pre>
 *
 * The absence of {@code semantic/} means the case was processed without the capability (FR-027).
 */
public final class SemanticStore {

    public static final String DIR = "semantic";
    public static final String BUILD_RECORD = "build-record.json";
    public static final String STAGING = "staging.mv.db";

    private final Path root;

    private SemanticStore(Path root) {
        this.root = root;
    }

    /**
     * @param moduleDir
     *            the case's {@code iped} folder (a task's {@code output}, or the folder holding
     *            {@code index/})
     */
    public static SemanticStore of(File moduleDir) {
        return new SemanticStore(moduleDir.toPath().resolve(DIR));
    }

    public static SemanticStore of(Path moduleDir) {
        return new SemanticStore(moduleDir.resolve(DIR));
    }

    public Path root() {
        return root;
    }

    public Path index(Modality modality) {
        return root.resolve(modality.key());
    }

    public Path buildRecord() {
        return root.resolve(BUILD_RECORD);
    }

    /** H2 MVStore file of the chat staging area. */
    public Path staging() {
        return root.resolve(STAGING);
    }

    /** Whether the case was processed with the capability at all. */
    public boolean exists() {
        return Files.isDirectory(root);
    }

    public boolean hasIndex(Modality modality) {
        return Files.isDirectory(index(modality));
    }
}
