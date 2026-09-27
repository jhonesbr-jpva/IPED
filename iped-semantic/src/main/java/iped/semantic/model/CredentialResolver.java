package iped.semantic.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import iped.semantic.SemanticPaths;
import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;

/**
 * Finds the credential of a remote model source (FR-045): the configuration names an environment
 * variable ({@code …RemoteCredentialEnv}) or a file holding only the credential
 * ({@code …RemoteCredentialFile}), never the value. The file wins when both are set, because the
 * variable always has a default name.
 *
 * <p>
 * The value lives in a {@link Credential}, whose {@code toString()} never shows it: it goes into the
 * {@code Authorization} header and nowhere else - not the log, not the build record, not an error
 * message.
 * </p>
 */
public final class CredentialResolver {

    /** A credential, or its absence. Never printed. */
    public static final class Credential {
        public static final Credential NONE = new Credential(null, "none");

        private final String value;
        private final String origin;

        private Credential(String value, String origin) {
            this.value = value;
            this.origin = origin;
        }

        /** The secret itself, for the {@code Authorization} header only. */
        String value() {
            return value;
        }

        public boolean isPresent() {
            return value != null;
        }

        /** Where it came from, safe to log: "environment variable X", "file Y" or "none". */
        public String origin() {
            return origin;
        }

        @Override
        public String toString() {
            return value == null ? "Credential[none]" : "Credential[redacted, from " + origin + "]";
        }
    }

    private CredentialResolver() {
    }

    public static Credential resolve(SemanticSearchConfig config, Modality modality) throws EmbeddingException {
        return resolve(config.getRemoteCredentialFile(modality), config.getRemoteCredentialEnv(modality),
                System::getenv);
    }

    /**
     * @param env
     *            environment lookup (tests pass their own)
     * @throws EmbeddingException
     *             a configuration error when a named file cannot be read or is empty
     */
    public static Credential resolve(String file, String envName, UnaryOperator<String> env)
            throws EmbeddingException {
        if (file != null && !file.isBlank()) {
            Path p = SemanticPaths.resolve(file.trim());
            String value;
            try {
                value = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new EmbeddingException("the credential file " + p + " cannot be read (" + e.getClass().getSimpleName()
                        + ")", null, true);
            }
            if (value.isEmpty()) {
                throw new EmbeddingException("the credential file " + p + " is empty", null, true);
            }
            return new Credential(value, "file " + p);
        }
        if (envName != null && !envName.isBlank()) {
            String value = env.apply(envName.trim());
            if (value != null && !value.trim().isEmpty()) {
                return new Credential(value.trim(), "environment variable " + envName.trim());
            }
        }
        return Credential.NONE;
    }
}
