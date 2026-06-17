package iped.rcp.core.processing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the Bootstrap argument list for a {@link NewCaseRequest} and validates
 * it before launch (feature 005, contracts/new-case-wizard.contract.md).
 *
 * <p>
 * The produced list is the {@code <args>} part appended after
 * {@code java -jar iped.jar} by the {@code ProcessingLaunchService}
 * (contracts/processing-launch.contract.md). The mapping mirrors
 * {@code iped.app.processing.CmdLineArgsImpl} 1:1 so a case created here is
 * forensically equivalent to one created from the CLI with the same options
 * (FR-013). Never emits {@code --nogui} — the interactive flow always wants
 * the progress window (FR-010).
 *
 * <p>
 * {@link #validate(NewCaseRequest)} is a friendly superset of the
 * {@code CmdLineArgsImpl} checks, returning clear messages so the wizard can
 * block invalid submissions before any process is spawned (FR-008/SC-003).
 *
 * <p>
 * Stateless and toolkit-free — unit-tested by task T011.
 */
public final class BootstrapCommandBuilder {

    private BootstrapCommandBuilder() {
    }

    /**
     * Builds the Bootstrap arguments for {@code request}. Does not validate;
     * call {@link #validate(NewCaseRequest)} first.
     *
     * @return an ordered, modifiable argument list (without the
     *         {@code java -jar iped.jar} prefix)
     */
    public static List<String> buildArgs(NewCaseRequest request) {
        List<String> args = new ArrayList<>();

        // Sources: -d <path> [-dname <name>] [-p <pwd>] — keep -dname/-p adjacent
        // to their -d so CmdLineArgsImpl.getDataSourceName/Password resolve them.
        for (DataSourceEntry source : request.dataSources()) {
            args.add("-d");
            args.add(source.path().toString());
            if (notBlank(source.displayName())) {
                args.add("-dname");
                args.add(source.displayName());
            }
            if (notBlank(source.password())) {
                args.add("-p");
                args.add(source.password());
            }
        }

        args.add("-o");
        args.add(request.outputDir().toString());

        args.add("-profile");
        args.add(request.profileName());

        switch (request.mode()) {
            case APPEND -> args.add("--append");
            case CONTINUE -> args.add("--continue");
            case RESTART -> args.add("--restart");
            case NEW -> { /* no flag */ }
        }

        if (notBlank(request.timezone())) {
            args.add("-tz");
            args.add(request.timezone());
        }
        if (request.keywordsFile() != null) {
            args.add("-l");
            args.add(request.keywordsFile().toString());
        }

        // Tier B — Common toggles
        CommonOptions common = request.commonOptions();
        if (common.addOwner()) {
            args.add("--addowner");
        }
        if (common.portable()) {
            args.add("--portable");
        }
        if (common.noPstAttachs()) {
            args.add("--nopstattachs");
        }
        if (common.noLinkedItems()) {
            args.add("--nolinkeditems");
        }
        if (common.downloadInternetData()) {
            args.add("--downloadInternetData");
        }

        // Tier C — Advanced typed fields
        AdvancedOptions adv = request.advancedOptions();
        if (adv.blocksize() != null && adv.blocksize() != 0) {
            args.add("-b");
            args.add(Integer.toString(adv.blocksize()));
        }
        for (String cat : adv.ocrSubset()) {
            args.add("-ocr");
            args.add(cat);
        }
        for (String cat : adv.noContent()) {
            args.add("-nocontent");
            args.add(cat);
        }
        if (adv.logFile() != null) {
            args.add("-log");
            args.add(adv.logFile().toString());
        }
        if (adv.noLogFile()) {
            args.add("--nologfile");
        }
        if (notBlank(adv.splashMessage())) {
            args.add("-splash");
            args.add(adv.splashMessage());
        }

        // Tier C — dynamic/literal passthrough: JCommander -X parses -Xkey=value.
        request.advancedParams().forEach((k, v) -> args.add("-X" + k + "=" + v));

        return args;
    }

    /**
     * Validates {@code request}, returning an ordered list of human-readable
     * error messages (empty when valid). Mirrors and pre-empts the
     * {@code CmdLineArgsImpl} failures (FR-008/SC-003).
     */
    public static List<String> validate(NewCaseRequest request) {
        List<String> errors = new ArrayList<>();

        List<DataSourceEntry> sources = request.dataSources();
        if (sources.isEmpty()) {
            errors.add("At least one evidence source is required.");
        }

        Set<String> names = new LinkedHashSet<>();
        for (DataSourceEntry source : sources) {
            Path path = source.path();
            if (!Files.exists(path)) {
                errors.add("Evidence source not found: " + path);
            } else if (!Files.isReadable(path)) {
                errors.add("Evidence source is not readable: " + path);
            }
            if (!names.add(source.effectiveName())) {
                errors.add("Duplicate evidence names are not allowed: " + source.effectiveName());
            }
        }

        Path outputDir = request.outputDir();
        if (outputDir == null) {
            errors.add("An output folder is required.");
        } else {
            // Output must not equal nor be a sub-folder of any source
            // (mirrors CmdLineArgsImpl: "output folder can not be equal or a
            // subfolder of an input").
            Path absOutput = outputDir.toAbsolutePath().normalize();
            for (DataSourceEntry source : sources) {
                Path absSource = source.path().toAbsolutePath().normalize();
                if (absOutput.startsWith(absSource)) {
                    errors.add("The output folder cannot be equal to or inside an evidence source: " + absSource);
                }
            }
            boolean caseExists = Files.exists(outputDir.resolve("iped"));
            if (request.mode().requiresExistingCase() && !caseExists) {
                errors.add("Mode " + request.mode() + " requires an existing case in: " + outputDir.resolve("iped"));
            }
            if (request.mode() == ProcessingMode.NEW && caseExists) {
                errors.add("Output already contains a case (" + outputDir.resolve("iped")
                        + "); choose Append/Continue/Restart or another folder.");
            }
            Path parent = outputDir.getParent();
            if (parent != null && !Files.isWritable(firstExistingAncestor(outputDir))) {
                errors.add("The output folder is not writable: " + outputDir);
            }
        }

        if (!notBlank(request.profileName())) {
            errors.add("A processing profile must be selected.");
        }

        if (request.keywordsFile() != null && !Files.exists(request.keywordsFile())) {
            errors.add("Keyword list file not found: " + request.keywordsFile());
        }

        return errors;
    }

    /** @return true when the request passes {@link #validate(NewCaseRequest)}. */
    public static boolean isValid(NewCaseRequest request) {
        return validate(request).isEmpty();
    }

    /** Walks up to the first existing ancestor (the dir to be created may not exist yet). */
    private static Path firstExistingAncestor(Path dir) {
        Path p = dir.toAbsolutePath();
        while (p != null && !Files.exists(p)) {
            p = p.getParent();
        }
        return p != null ? p : dir.toAbsolutePath();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
