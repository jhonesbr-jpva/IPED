package iped.semantic.model;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;

/**
 * Local model: ONNX Runtime running the exported artifact ({@code model.json} plus one ONNX file per
 * encoder), with the tokenizer inside the graph (research D2). No network, no Python.
 *
 * <p>
 * One instance - one set of sessions - per model folder and per JVM, shared by every caller:
 * processing workers, the analysis UI and the MCP server each hold one in their own process.
 * </p>
 *
 * <p>
 * <b>One input per {@code run()}</b>, with one intra-op thread. The shipped text model is
 * dynamically quantized, and dynamic quantization computes the activation scale over the whole
 * input tensor: in a batch, a passage's vector would depend on its batch neighbours, which depend on
 * worker scheduling. Running inputs alone makes every vector a function of its own input only, so a
 * question embedded by the UI and by the MCP server is the same vector (SC-011), and a probe
 * recomputed tomorrow is the probe recorded today. Throughput comes from the
 * {@link EmbeddingService} threads calling the shared session concurrently.
 * </p>
 */
public final class LocalOnnxEmbedder implements Embedder {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalOnnxEmbedder.class);

    public static final String MANIFEST = "model.json";
    public static final String DEFAULT_TEXT_FILE = "model.onnx";

    private static final Map<Path, LocalOnnxEmbedder> SHARED = new ConcurrentHashMap<>();

    private final Path modelDir;
    private final ModelIdentity identity;
    private final OrtEnvironment env;
    private final OrtSession textSession;
    private final String textInput;
    private final String textOutput;
    private final OrtSession imageSession;
    private final ImagePreprocessor imagePreprocessor;
    private final String imageInput;
    private final String imageOutput;

    /**
     * Returns the shared embedder of a model folder, loading it on first use.
     *
     * @param modelDir
     *            folder with {@code model.json} and the ONNX file(s)
     * @param nativeBaseDir
     *            where the ONNX Runtime natives are unpacked ({@code <install>/tools/onnxruntime})
     * @throws EmbeddingException
     *             naming the missing file when the model is not installed (FR-042)
     */
    public static LocalOnnxEmbedder open(Path modelDir, Path nativeBaseDir) throws EmbeddingException {
        Path key = modelDir.toAbsolutePath().normalize();
        LocalOnnxEmbedder e = SHARED.get(key);
        if (e != null) {
            return e;
        }
        synchronized (SHARED) {
            e = SHARED.get(key);
            if (e == null) {
                e = new LocalOnnxEmbedder(key, nativeBaseDir);
                SHARED.put(key, e);
            }
            return e;
        }
    }

    /** Whether a model folder has what {@link #open} needs, without loading anything. */
    public static boolean isInstalled(Path modelDir) {
        return Files.isRegularFile(modelDir.resolve(MANIFEST));
    }

    private LocalOnnxEmbedder(Path modelDir, Path nativeBaseDir) throws EmbeddingException {
        this.modelDir = modelDir;
        Path manifestPath = modelDir.resolve(MANIFEST);
        if (!Files.isRegularFile(manifestPath)) {
            throw new EmbeddingException("local model missing: " + manifestPath + " does not exist", null, true);
        }
        JsonNode manifest;
        try {
            manifest = new ObjectMapper().readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new EmbeddingException("cannot read " + manifestPath + ": " + ex.getMessage(), ex, true);
        }

        Map<String, Path> files = files(manifest);
        for (Path f : files.values()) {
            if (!Files.isRegularFile(f)) {
                throw new EmbeddingException("local model incomplete: " + f + " does not exist", null, true);
            }
        }
        String sha;
        try {
            sha = artifactSha256(files);
        } catch (IOException ex) {
            throw new EmbeddingException("cannot hash the model files in " + modelDir, ex, true);
        }
        String declared = text(manifest, "sha256", null);
        if (declared != null && !declared.equalsIgnoreCase(sha)) {
            // The identity is what the artifact is, not what its manifest claims (FR-043).
            LOGGER.warn("Model {}: files hash to {} but model.json declares {}; using the computed hash", modelDir,
                    sha, declared);
        }
        this.identity = new ModelIdentity(text(manifest, "id", null), sha, manifest.path("dimensions").asInt(),
                text(manifest, "similarity", ModelIdentity.DOT_PRODUCT), text(manifest, "queryPrefix", ""),
                text(manifest, "passagePrefix", ""));
        this.textInput = text(manifest, "textInput", "text");
        this.textOutput = text(manifest, "output", "embedding");
        this.imageInput = text(manifest, "imageInput", "pixel_values");
        this.imageOutput = text(manifest, "imageOutput", "image_embedding");

        try {
            Path extensions = OrtNatives.prepare(nativeBaseDir);
            this.env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "iped-semantic");
            this.textSession = files.containsKey("text") ? createSession(files.get("text"), extensions) : null;
            this.imageSession = files.containsKey("image") ? createSession(files.get("image"), extensions) : null;
        } catch (IOException | OrtException | RuntimeException | UnsatisfiedLinkError ex) {
            throw new EmbeddingException("cannot load the local model " + identity.getId() + " from " + modelDir + ": "
                    + ex.getMessage(), ex, true);
        }
        this.imagePreprocessor = imageSession != null ? ImagePreprocessor.fromManifest(manifest.path("image")) : null;
        LOGGER.info("Loaded local semantic model {} from {}", identity.describe(), modelDir);
    }

    private OrtSession createSession(Path onnx, Path extensionsLibrary) throws OrtException {
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.registerCustomOpLibrary(extensionsLibrary.toString());
        opts.setIntraOpNumThreads(1);
        opts.setInterOpNumThreads(1);
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        opts.setDeterministicCompute(true);
        return env.createSession(onnx.toString(), opts);
    }

    /** Model files by role: {@code files} in the manifest, or {@code model.onnx} for text. */
    private Map<String, Path> files(JsonNode manifest) {
        Map<String, Path> out = new TreeMap<>();
        JsonNode f = manifest.path("files");
        if (f.isObject()) {
            f.fields().forEachRemaining(e -> out.put(e.getKey(), modelDir.resolve(e.getValue().asText())));
        } else {
            out.put("text", modelDir.resolve(DEFAULT_TEXT_FILE));
        }
        return out;
    }

    /**
     * SHA-256 of the artifact: of the single file for a one-file model; for several files, of the
     * UTF-8 lines {@code <role>:<file sha256>} sorted by role. The export tool computes the same.
     */
    static String artifactSha256(Map<String, Path> files) throws IOException {
        if (files.size() == 1) {
            return sha256(files.values().iterator().next());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Path> e : new TreeMap<>(files).entrySet()) {
            sb.append(e.getKey()).append(':').append(sha256(e.getValue())).append('\n');
        }
        return hex(digest().digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
            byte[] buf = new byte[1 << 20];
            while (in.read(buf) != -1) {
                // digest only
            }
        }
        return hex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String text(JsonNode n, String field, String fallback) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? fallback : v.asText();
    }

    @Override
    public float[][] embedTexts(Role role, List<String> texts) throws EmbeddingException {
        if (textSession == null) {
            throw new UnsupportedOperationException(identity.getId() + " has no text encoder");
        }
        float[][] out = new float[texts.size()][];
        for (int i = 0; i < texts.size(); i++) {
            String input = identity.prefixed(role, texts.get(i));
            try (OnnxTensor t = OnnxTensor.createTensor(env, new String[] { input });
                    OrtSession.Result r = textSession.run(Map.of(textInput, t))) {
                out[i] = firstRow(r, textOutput);
            } catch (OrtException e) {
                throw new EmbeddingException("local model " + identity.getId() + " failed on a text: " + e.getMessage(), e);
            }
        }
        return out;
    }

    @Override
    public boolean supportsImages() {
        return imageSession != null;
    }

    @Override
    public float[][] embedImages(List<BufferedImage> images) throws EmbeddingException {
        if (imageSession == null) {
            throw new UnsupportedOperationException(identity.getId() + " has no image encoder");
        }
        float[][] out = new float[images.size()][];
        for (int i = 0; i < images.size(); i++) {
            float[] pixels = imagePreprocessor.toChw(images.get(i));
            long[] shape = { 1, 3, imagePreprocessor.size(), imagePreprocessor.size() };
            try (OnnxTensor t = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(pixels), shape);
                    OrtSession.Result r = imageSession.run(Map.of(imageInput, t))) {
                out[i] = firstRow(r, imageOutput);
            } catch (OrtException e) {
                throw new EmbeddingException("local model " + identity.getId() + " failed on an image: " + e.getMessage(),
                        e);
            }
        }
        return out;
    }

    private float[] firstRow(OrtSession.Result r, String output) throws OrtException, EmbeddingException {
        Optional<OnnxValue> v = r.get(output);
        if (v.isEmpty()) {
            throw new EmbeddingException("model output '" + output + "' not found in " + identity.getId(), null, true);
        }
        float[][] rows = (float[][]) v.get().getValue();
        float[] row = rows[0];
        if (row.length != identity.getDimensions()) {
            throw new EmbeddingException("model " + identity.getId() + " returned " + row.length
                    + " dimensions, model.json declares " + identity.getDimensions(), null, true);
        }
        return VectorMath.normalizeInPlace(row);
    }

    @Override
    public ModelIdentity identity() {
        return identity;
    }

    public Path getModelDir() {
        return modelDir;
    }

    /** Lists the model folders loaded in this JVM (diagnostics). */
    public static List<Path> loaded() {
        return new ArrayList<>(SHARED.keySet());
    }
}
