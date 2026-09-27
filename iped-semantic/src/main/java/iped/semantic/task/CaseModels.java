package iped.semantic.task;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.semantic.SemanticPaths;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.config.SemanticSearchConfig.Source;

/**
 * Puts the local model of a prepared modality inside the case (research D17). The engine copies
 * {@code lib}, {@code jre}, {@code tools} and {@code conf} into the case so that its own analysis
 * app runs from there; that app resolves the configured model folder against the case, and a case
 * without the model could not answer a single semantic question. The copy goes to the same relative
 * path the configuration names, and is skipped when the same model is already there.
 */
final class CaseModels {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaseModels.class);
    private static final String MANIFEST = "model.json";

    private CaseModels() {
    }

    /**
     * @param moduleDir
     *            the case's {@code iped} folder
     * @return the model folder inside the case, or {@code null} when nothing was placed there (a
     *         remote source, or a model configured by absolute path)
     */
    static Path copyIntoCase(SemanticSearchConfig config, Modality modality, File moduleDir) throws IOException {
        if (config.getModelSource(modality) != Source.LOCAL) {
            return null;
        }
        String configured = config.getLocalModel(modality);
        if (new File(configured).isAbsolute()) {
            LOGGER.warn("Semantic {} model is configured by absolute path ({}): it is not copied into the case, "
                    + "and the case's analysis app will look for it at that same path", modality.key(), configured);
            return null;
        }
        Path from = SemanticPaths.resolve(configured).toAbsolutePath().normalize();
        Path to = moduleDir.toPath().resolve(configured).toAbsolutePath().normalize();
        if (from.equals(to) || sameModel(from, to)) {
            return to;
        }
        Path partial = to.resolveSibling(to.getFileName() + ".copying");
        deleteTree(partial);
        long bytes = copyTree(from, partial);
        deleteTree(to);
        Files.move(partial, to, StandardCopyOption.ATOMIC_MOVE);
        LOGGER.info("Semantic {} model copied into the case ({} MB), so the case's analysis app can search it: {}",
                modality.key(), bytes >> 20, to);
        return to;
    }

    /** Same manifest and the same files with the same sizes. */
    static boolean sameModel(Path a, Path b) throws IOException {
        Path ma = a.resolve(MANIFEST);
        Path mb = b.resolve(MANIFEST);
        if (!Files.isRegularFile(ma) || !Files.isRegularFile(mb)
                || !Arrays.equals(Files.readAllBytes(ma), Files.readAllBytes(mb))) {
            return false;
        }
        return listing(a).equals(listing(b));
    }

    private static String listing(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile).sorted()::iterator) {
                sb.append(dir.relativize(p).toString().replace('\\', '/')).append(' ').append(Files.size(p))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private static long copyTree(Path from, Path to) throws IOException {
        long[] bytes = { 0 };
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                    bytes[0] += Files.size(p);
                }
            }
        }
        return bytes[0];
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            for (Path q : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(q);
            }
        }
    }
}
