package iped.semantic.integration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Assume;

/**
 * One reference evidence processed twice by an IPED release, once with semantic search off and once
 * with the text modality on - the pair the processing suites compare. Processed once per JVM and
 * shared, since a run costs a minute or more.
 *
 * <p>
 * Parameters, all required, or the suites that use this are skipped:
 * </p>
 * <ul>
 * <li>{@code -Diped.semantic.test.ipedRoot=<release>}: an unpacked release with the text model, run
 * as a child process exactly as an examiner runs it;</li>
 * <li>{@code -Diped.semantic.test.referenceEvidence=<folder>}: a folder evidence of known,
 * non-sensitive content, with at least one {@code .txt} file (and, to exercise conversations, a
 * chat database).</li>
 * </ul>
 * <p>
 * The "on" run uses the processing profile {@code semantic-it} ({@code enableSemanticText = true}
 * and nothing else). When the release does not have it, it is created for the run and removed
 * afterwards. In that run, the preparation of one {@code .txt} item is made to fail through
 * {@code IPED_SEMANTIC_TEST_FAIL_ITEM}, so the suites can check that the item is declared
 * {@code PREP_FAILURE} and that the processing completes (FR-022).
 * </p>
 */
public final class ProcessedCases {

    public static final String IPED_ROOT_PROPERTY = "iped.semantic.test.ipedRoot";
    public static final String EVIDENCE_PROPERTY = "iped.semantic.test.referenceEvidence";
    /** Reuses the pair left in {@code target/it-cases} by a previous run, when it is there. */
    public static final String REUSE_PROPERTY = "iped.semantic.test.reuseProcessedCases";
    public static final String PROFILE = "semantic-it";
    private static final String FAIL_ITEM_ENV = "IPED_SEMANTIC_TEST_FAIL_ITEM";
    private static final long TIMEOUT_MINUTES = 30;

    private static ProcessedCases instance;

    /** The case processed with semantic search off. */
    public final File off;
    /** The case processed with the text modality on. */
    public final File on;
    /** The evidence folder. */
    public final File evidence;
    /** Name of the item whose preparation was made to fail in {@link #on}. */
    public final String failedItem;

    private ProcessedCases(File off, File on, File evidence, String failedItem) {
        this.off = off;
        this.on = on;
        this.evidence = evidence;
        this.failedItem = failedItem;
    }

    /** The pair, processing it on first use; skips the calling test without its parameters. */
    public static synchronized ProcessedCases get() throws Exception {
        String root = System.getProperty(IPED_ROOT_PROPERTY);
        String ev = System.getProperty(EVIDENCE_PROPERTY);
        Assume.assumeTrue("processing suites need -D" + IPED_ROOT_PROPERTY + "=<release> and -D" + EVIDENCE_PROPERTY
                + "=<evidence folder>", root != null && ev != null);
        if (instance != null) {
            return instance;
        }
        File ipedRoot = new File(root);
        File evidence = new File(ev);
        if (!new File(ipedRoot, "iped.jar").isFile()) {
            throw new AssertionError(ipedRoot + " is not an IPED release (no iped.jar)");
        }
        if (!evidence.isDirectory()) {
            throw new AssertionError(evidence + " is not an evidence folder");
        }
        String failedItem = pickFailItem(evidence.toPath());

        Path base = Paths.get(System.getProperty("user.dir"), "target", "it-cases").toAbsolutePath();
        File off = base.resolve("off").toFile();
        File on = base.resolve("on").toFile();
        if (Boolean.getBoolean(REUSE_PROPERTY) && new File(off, "iped/index").isDirectory()
                && new File(on, "iped/semantic/build-record.json").isFile()) {
            // iterating on the assertions: the pair of the previous run, same evidence and release
            instance = new ProcessedCases(off, on, evidence, failedItem);
            return instance;
        }
        deleteTree(base);
        Files.createDirectories(base);

        process(ipedRoot, evidence, off, null, null, base.resolve("off.log"));
        File profile = new File(ipedRoot, "profiles/" + PROFILE);
        boolean created = false;
        if (!profile.isDirectory()) {
            Files.createDirectories(profile.toPath());
            Files.write(profile.toPath().resolve("IPEDConfig.txt"),
                    "enableSemanticText = true\r\n".getBytes(StandardCharsets.UTF_8));
            created = true;
        }
        try {
            process(ipedRoot, evidence, on, PROFILE, failedItem, base.resolve("on.log"));
        } finally {
            if (created) {
                deleteTree(profile.toPath());
            }
        }
        instance = new ProcessedCases(off, on, evidence, failedItem);
        return instance;
    }

    /** The last {@code .txt} file of the evidence in path order: stable across runs. */
    private static String pickFailItem(Path evidence) throws IOException {
        try (Stream<Path> files = Files.walk(evidence)) {
            List<Path> txt = files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".txt"))
                    .sorted().collect(Collectors.toList());
            if (txt.size() < 2) {
                throw new AssertionError("the reference evidence needs at least two .txt files: one to fail, one "
                        + "to be covered");
            }
            return txt.get(txt.size() - 1).getFileName().toString();
        }
    }

    private static void process(File ipedRoot, File evidence, File out, String profile, String failItem, Path log)
            throws Exception {
        int exit = run(ipedRoot, evidence, out, profile, failItem, log);
        if (exit != 0) {
            throw new AssertionError("processing failed with exit " + exit + "; last lines of " + log + ":\n"
                    + tail(log, 30));
        }
    }

    /**
     * Runs the release on an evidence as an examiner would, with its output in {@code log}.
     *
     * @return the exit code
     */
    static int run(File ipedRoot, File evidence, File out, String profile, String failItem, Path log)
            throws Exception {
        File java = new File(ipedRoot, "jre/bin/java" + (File.pathSeparatorChar == ';' ? ".exe" : ""));
        List<String> cmd = new ArrayList<>();
        cmd.add(java.isFile() ? java.getAbsolutePath() : "java");
        // no -Xms/-Xmx: the Bootstrap refuses them from the JVM that launches it
        cmd.add("-Diped-locale=en");
        cmd.add("-jar");
        cmd.add(new File(ipedRoot, "iped.jar").getAbsolutePath());
        cmd.add("-d");
        cmd.add(evidence.getAbsolutePath());
        cmd.add("-o");
        cmd.add(out.getAbsolutePath());
        if (profile != null) {
            cmd.add("-profile");
            cmd.add(profile);
        }
        cmd.add("--nogui");
        cmd.add("--nologfile");
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(ipedRoot).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        if (failItem != null) {
            pb.environment().put(FAIL_ITEM_ENV, failItem);
        } else {
            pb.environment().remove(FAIL_ITEM_ENV);
        }
        Process p = pb.start();
        if (!p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new AssertionError("processing did not finish in " + TIMEOUT_MINUTES + " min; see " + log);
        }
        return p.exitValue();
    }

    static String tail(Path log, int lines) throws IOException {
        List<String> all = Files.readAllLines(log, StandardCharsets.ISO_8859_1);
        return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path f : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(f);
            }
        }
    }
}
