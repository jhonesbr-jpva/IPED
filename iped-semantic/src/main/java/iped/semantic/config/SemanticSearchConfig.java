package iped.semantic.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import iped.engine.config.AbstractTaskConfig;
import iped.utils.UTF8Properties;

/**
 * Configuration of semantic search, read from {@code conf/SemanticSearchConfig.txt}, with the text
 * modality enabled by {@code enableSemanticText} in {@code IPEDConfig.txt}. The image and video keys
 * ({@code enableSemanticImages}, {@code enableSemanticVideos}) are separate
 * {@link iped.engine.config.EnableTaskProperty} instances returned by the visual task.
 *
 * <p>
 * The distributed file is the authority. The defaults below are last-resort fallbacks, identical to
 * the file, used when a key is missing (isolated tests, a broken installation) - the same contract as
 * {@code McpServerConfig}. See {@code specs/009-semantic-search/contracts/config-surface.md}.
 * </p>
 *
 * <p>
 * This class extends {@link AbstractTaskConfig} directly rather than
 * {@code AbstractTaskPropertiesConfig}: the latter's {@code processProperties} is package-private
 * in {@code iped.engine.config} and cannot be implemented from another module. The behaviour is the
 * same: properties are accumulated from every profile file found, then parsed.
 * </p>
 *
 * <p>
 * Parsing is lenient on purpose: a malformed value never breaks IPED start-up while the capability is
 * off. {@link #validate(Modality)} is what turns configuration problems into errors, and the tasks
 * call it only when their key is on (FR-042).
 * </p>
 */
public class SemanticSearchConfig extends AbstractTaskConfig<UTF8Properties> {

    private static final long serialVersionUID = 1L;

    public static final String CONF_FILE = "SemanticSearchConfig.txt";

    public static final String ENABLE_TEXT = "enableSemanticText";
    public static final String ENABLE_IMAGES = "enableSemanticImages";
    public static final String ENABLE_VIDEOS = "enableSemanticVideos";

    /** The two modalities; each has its own model, index and build record section. */
    public enum Modality {
        TEXT("text"), VISUAL("visual");

        private final String key;

        Modality(String key) {
            this.key = key;
        }

        /** Prefix of the per-modality keys and name of the index folder. */
        public String key() {
            return key;
        }
    }

    public enum Source {
        LOCAL, REMOTE
    }

    public enum TextMode {
        SEMANTIC, HYBRID
    }

    // --- defaults: identical to the distributed conf/SemanticSearchConfig.txt ---
    static final String DEFAULT_TEXT_LOCAL_MODEL = "models/semantic/text/multilingual-e5-small-int8";
    static final String DEFAULT_VISUAL_LOCAL_MODEL = "models/semantic/visual/clip-vit-b-32-multilingual-v1";
    static final String DEFAULT_TEXT_CREDENTIAL_ENV = "IPED_SEMANTIC_TEXT_CREDENTIAL";
    static final String DEFAULT_VISUAL_CREDENTIAL_ENV = "IPED_SEMANTIC_VISUAL_CREDENTIAL";
    static final List<String> DEFAULT_MESSAGE_MEDIA_TYPES = List.of("message/x-whatsapp-message",
            "message/x-telegram-message", "message/x-skype-message", "message/x-discord-message",
            "message/x-threema-message", "message/x-jabber-message", "application/x-ufed-instantmessage");
    /**
     * Chat databases IPED decodes into chats and messages. Their own extracted text is a listing of
     * the conversations they hold, which reach semantic search as the messages' sequences; prepared
     * as passages, a database would stand close to every question about a conversation and take a
     * place at the head of the ordering with content already there (measured on the eval case).
     */
    static final List<String> DEFAULT_MESSAGE_CONTAINER_MEDIA_TYPES = List.of("application/x-whatsapp-db",
            "application/x-whatsapp-db-f", "application/x-whatsapp-chatstorage", "application/x-whatsapp-chatstorage-f",
            "application/x-threema-chatstorage", "application/x-threema-chatstorage-f", "application/x-telegram-db",
            "application/x-telegram-db-ios", "application/x-ios-sms-db");

    private UTF8Properties properties = new UTF8Properties();

    // model sources
    private Source textModelSource = Source.LOCAL;
    private String textLocalModel = DEFAULT_TEXT_LOCAL_MODEL;
    private String textRemoteProtocol = "";
    private String textRemoteEndpoint = "";
    private String textRemoteModel = "";
    private String textRemoteCredentialEnv = DEFAULT_TEXT_CREDENTIAL_ENV;
    private String textRemoteCredentialFile = "";
    /** openai-embeddings only: the prefixes travel inside the text, the service knows nothing of them. */
    private String textRemoteQueryPrefix = "";
    private String textRemotePassagePrefix = "";
    private Source visualModelSource = Source.LOCAL;
    private String visualLocalModel = DEFAULT_VISUAL_LOCAL_MODEL;
    private String visualRemoteProtocol = "";
    private String visualRemoteEndpoint = "";
    private String visualRemoteModel = "";
    private String visualRemoteCredentialEnv = DEFAULT_VISUAL_CREDENTIAL_ENV;
    private String visualRemoteCredentialFile = "";
    private boolean remoteAllowPlainHttp = false;
    private int remoteTimeoutSeconds = 60;
    private int remoteMaxRetries = 5;

    // what enters
    private ArrayList<String> textCategories = new ArrayList<>();
    private ArrayList<String> visualCategories = new ArrayList<>();
    private ArrayList<String> messageMediaTypes = new ArrayList<>(DEFAULT_MESSAGE_MEDIA_TYPES);
    private ArrayList<String> messageContainerMediaTypes = new ArrayList<>(DEFAULT_MESSAGE_CONTAINER_MEDIA_TYPES);
    private int maxTextCharsPerItem = 200_000;
    private int passageChars = 1000;
    private int passageOverlapChars = 200;
    private int focusChars = 400;
    private int focusOverlapChars = 100;
    private int chatWindowMessages = 8;
    private int chatWindowStride = 4;
    private int chatWindowMaxChars = 1500;
    private int chatMaxGapMinutes = 720;
    private int chatShortMessageChars = 20;
    private int minImageSize = 48;

    // search
    private int defaultDepth = 100;
    private int maxDepth = 1000;
    private int oversampling = 4;
    private int exactSearchMaxItems = 50_000;
    private int maxQueryChars = 2000;
    private double fingerprintTolerance = 0.9995;
    private TextMode defaultTextMode = TextMode.HYBRID;
    private int rrfK = 60;
    private double rrfLexicalWeight = 0.3;
    private int lexicalCandidates = 0;
    private int contextMaxChars = 8000;
    private int hnswMaxConn = 16;
    private int hnswBeamWidth = 100;

    // cost
    private int inferenceThreads = 0;
    private int batchSize = 32;
    private int queueCapacity = 1024;
    private int commitIntervalMinutes = 10;

    /** Problems found while parsing; reported by {@link #validate(Modality)}. */
    private ArrayList<String> parseProblems = new ArrayList<>();

    @Override
    public String getTaskEnableProperty() {
        return ENABLE_TEXT;
    }

    @Override
    public String getTaskConfigFileName() {
        return CONF_FILE;
    }

    @Override
    public UTF8Properties getConfiguration() {
        return properties;
    }

    @Override
    public void setConfiguration(UTF8Properties config) {
        this.properties = config;
        processProperties(config);
    }

    @Override
    public void processTaskConfig(Path resource) throws IOException {
        properties.load(resource.toFile());
        processProperties(properties);
    }

    /**
     * Parses every key. Missing keys keep the fallback; malformed ones keep the fallback and are
     * recorded as problems, reported by {@link #validate(Modality)}.
     */
    public void processProperties(UTF8Properties p) {
        parseProblems = new ArrayList<>();

        textModelSource = source(p, "textModelSource", textModelSource);
        textLocalModel = string(p, "textLocalModel", textLocalModel);
        textRemoteProtocol = string(p, "textRemoteProtocol", textRemoteProtocol);
        textRemoteEndpoint = string(p, "textRemoteEndpoint", textRemoteEndpoint);
        textRemoteModel = string(p, "textRemoteModel", textRemoteModel);
        textRemoteCredentialEnv = string(p, "textRemoteCredentialEnv", textRemoteCredentialEnv);
        textRemoteCredentialFile = string(p, "textRemoteCredentialFile", textRemoteCredentialFile);
        textRemoteQueryPrefix = unquoted(p, "textRemoteQueryPrefix", textRemoteQueryPrefix);
        textRemotePassagePrefix = unquoted(p, "textRemotePassagePrefix", textRemotePassagePrefix);
        visualModelSource = source(p, "visualModelSource", visualModelSource);
        visualLocalModel = string(p, "visualLocalModel", visualLocalModel);
        visualRemoteProtocol = string(p, "visualRemoteProtocol", visualRemoteProtocol);
        visualRemoteEndpoint = string(p, "visualRemoteEndpoint", visualRemoteEndpoint);
        visualRemoteModel = string(p, "visualRemoteModel", visualRemoteModel);
        visualRemoteCredentialEnv = string(p, "visualRemoteCredentialEnv", visualRemoteCredentialEnv);
        visualRemoteCredentialFile = string(p, "visualRemoteCredentialFile", visualRemoteCredentialFile);
        remoteAllowPlainHttp = bool(p, "remoteAllowPlainHttp", remoteAllowPlainHttp);
        remoteTimeoutSeconds = integer(p, "remoteTimeoutSeconds", remoteTimeoutSeconds, 1);
        remoteMaxRetries = integer(p, "remoteMaxRetries", remoteMaxRetries, 0);

        textCategories = list(p, "textCategories", textCategories);
        visualCategories = list(p, "visualCategories", visualCategories);
        messageMediaTypes = list(p, "messageMediaTypes", messageMediaTypes);
        messageContainerMediaTypes = list(p, "messageContainerMediaTypes", messageContainerMediaTypes);
        maxTextCharsPerItem = integer(p, "maxTextCharsPerItem", maxTextCharsPerItem, 1);
        passageChars = integer(p, "passageChars", passageChars, 1);
        passageOverlapChars = integer(p, "passageOverlapChars", passageOverlapChars, 0);
        focusChars = integer(p, "focusChars", focusChars, 0);
        focusOverlapChars = integer(p, "focusOverlapChars", focusOverlapChars, 0);
        chatWindowMessages = integer(p, "chatWindowMessages", chatWindowMessages, 1);
        chatWindowStride = integer(p, "chatWindowStride", chatWindowStride, 1);
        chatWindowMaxChars = integer(p, "chatWindowMaxChars", chatWindowMaxChars, 1);
        chatMaxGapMinutes = integer(p, "chatMaxGapMinutes", chatMaxGapMinutes, 0);
        chatShortMessageChars = integer(p, "chatShortMessageChars", chatShortMessageChars, 0);
        minImageSize = integer(p, "minImageSize", minImageSize, 0);

        defaultDepth = integer(p, "defaultDepth", defaultDepth, Integer.MIN_VALUE);
        maxDepth = integer(p, "maxDepth", maxDepth, 1);
        oversampling = integer(p, "oversampling", oversampling, 1);
        exactSearchMaxItems = integer(p, "exactSearchMaxItems", exactSearchMaxItems, 0);
        maxQueryChars = integer(p, "maxQueryChars", maxQueryChars, 1);
        fingerprintTolerance = decimal(p, "fingerprintTolerance", fingerprintTolerance);
        defaultTextMode = textMode(p, "defaultTextMode", defaultTextMode);
        rrfK = integer(p, "rrfK", rrfK, 1);
        rrfLexicalWeight = decimal(p, "rrfLexicalWeight", rrfLexicalWeight);
        lexicalCandidates = integer(p, "lexicalCandidates", lexicalCandidates, 0);
        contextMaxChars = integer(p, "contextMaxChars", contextMaxChars, 1);
        hnswMaxConn = integer(p, "hnswMaxConn", hnswMaxConn, 1);
        hnswBeamWidth = integer(p, "hnswBeamWidth", hnswBeamWidth, 1);

        inferenceThreads = integer(p, "inferenceThreads", inferenceThreads, 0);
        batchSize = integer(p, "batchSize", batchSize, 1);
        queueCapacity = integer(p, "queueCapacity", queueCapacity, 1);
        commitIntervalMinutes = integer(p, "commitIntervalMinutes", commitIntervalMinutes, 1);

        if (passageOverlapChars >= passageChars) {
            parseProblems.add("passageOverlapChars (" + passageOverlapChars + ") must be smaller than passageChars ("
                    + passageChars + ")");
        }
        if (focusChars > 0 && focusOverlapChars >= focusChars) {
            parseProblems.add("focusOverlapChars (" + focusOverlapChars + ") must be smaller than focusChars ("
                    + focusChars + ")");
        }
    }

    /**
     * Throws when the configuration cannot serve the given modality: a malformed value, a depth
     * outside {@code 1 <= defaultDepth <= maxDepth}, or a remote source without an endpoint.
     * Called by the tasks when their key is on, and by the searcher. A remote source without
     * endpoint is an error, never permission to pick another source (FR-042, FR-044).
     *
     * @throws SemanticConfigException
     *             naming every problem found
     */
    public void validate(Modality modality) throws SemanticConfigException {
        List<String> problems = new ArrayList<>(parseProblems);
        if (defaultDepth < 1 || defaultDepth > maxDepth) {
            problems.add("defaultDepth (" + defaultDepth + ") must satisfy 1 <= defaultDepth <= maxDepth (" + maxDepth
                    + ")");
        }
        if (getModelSource(modality) == Source.REMOTE) {
            String key = modality.key();
            if (getRemoteEndpoint(modality).isEmpty()) {
                problems.add(key + "ModelSource = remote but " + key
                        + "RemoteEndpoint is empty; there is no default destination");
            }
            String protocol = getRemoteProtocol(modality);
            if (!"openai-embeddings".equals(protocol) && !"iped-embed/1".equals(protocol)) {
                problems.add(key + "RemoteProtocol must be openai-embeddings or iped-embed/1, not '" + protocol + "'");
            } else if (modality == Modality.VISUAL && "openai-embeddings".equals(protocol)) {
                problems.add("visualRemoteProtocol = openai-embeddings cannot embed images; use iped-embed/1");
            }
        }
        if (!problems.isEmpty()) {
            throw new SemanticConfigException(CONF_FILE + ": " + String.join("; ", problems));
        }
    }

    // --- parsing helpers ---

    private String string(UTF8Properties p, String key, String fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : v.trim();
    }

    /**
     * A value whose spaces matter, such as the prefix {@code "query: "}: written between double
     * quotes it is taken verbatim, since a properties file trims what is not quoted.
     */
    private String unquoted(UTF8Properties p, String key, String fallback) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        String t = v.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private boolean bool(UTF8Properties p, String key, boolean fallback) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        v = v.trim().toLowerCase(Locale.ROOT);
        if (!v.equals("true") && !v.equals("false")) {
            parseProblems.add(key + " must be true or false, not '" + v + "'");
            return fallback;
        }
        return Boolean.parseBoolean(v);
    }

    private int integer(UTF8Properties p, String key, int fallback, int min) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(v.trim());
            if (value < min) {
                parseProblems.add(key + " must be >= " + min + ", not " + value);
                return fallback;
            }
            return value;
        } catch (NumberFormatException e) {
            parseProblems.add(key + " is not an integer: '" + v.trim() + "'");
            return fallback;
        }
    }

    private double decimal(UTF8Properties p, String key, double fallback) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            double value = Double.parseDouble(v.trim());
            if (value <= 0 || value > 1) {
                parseProblems.add(key + " must be in (0, 1], not " + value);
                return fallback;
            }
            return value;
        } catch (NumberFormatException e) {
            parseProblems.add(key + " is not a number: '" + v.trim() + "'");
            return fallback;
        }
    }

    private Source source(UTF8Properties p, String key, Source fallback) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Source.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            parseProblems.add(key + " must be local or remote, not '" + v.trim() + "'");
            return fallback;
        }
    }

    private TextMode textMode(UTF8Properties p, String key, TextMode fallback) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return TextMode.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            parseProblems.add(key + " must be hybrid or semantic, not '" + v.trim() + "'");
            return fallback;
        }
    }

    private ArrayList<String> list(UTF8Properties p, String key, ArrayList<String> fallback) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        ArrayList<String> out = new ArrayList<>();
        for (String s : v.split(";")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    // --- per-modality accessors ---

    public Source getModelSource(Modality m) {
        return m == Modality.TEXT ? textModelSource : visualModelSource;
    }

    public String getLocalModel(Modality m) {
        return m == Modality.TEXT ? textLocalModel : visualLocalModel;
    }

    public String getRemoteProtocol(Modality m) {
        return m == Modality.TEXT ? textRemoteProtocol : visualRemoteProtocol;
    }

    public String getRemoteEndpoint(Modality m) {
        return m == Modality.TEXT ? textRemoteEndpoint : visualRemoteEndpoint;
    }

    public String getRemoteModel(Modality m) {
        return m == Modality.TEXT ? textRemoteModel : visualRemoteModel;
    }

    public String getRemoteCredentialEnv(Modality m) {
        return m == Modality.TEXT ? textRemoteCredentialEnv : visualRemoteCredentialEnv;
    }

    public String getRemoteCredentialFile(Modality m) {
        return m == Modality.TEXT ? textRemoteCredentialFile : visualRemoteCredentialFile;
    }

    /** Prefix put in front of a question for {@code openai-embeddings} (e5 needs "query: "). */
    public String getRemoteQueryPrefix() {
        return textRemoteQueryPrefix;
    }

    /** Prefix put in front of a passage for {@code openai-embeddings} (e5 needs "passage: "). */
    public String getRemotePassagePrefix() {
        return textRemotePassagePrefix;
    }

    public List<String> getCategories(Modality m) {
        return Collections.unmodifiableList(m == Modality.TEXT ? textCategories : visualCategories);
    }

    // --- plain getters ---

    public boolean isRemoteAllowPlainHttp() {
        return remoteAllowPlainHttp;
    }

    public int getRemoteTimeoutSeconds() {
        return remoteTimeoutSeconds;
    }

    public int getRemoteMaxRetries() {
        return remoteMaxRetries;
    }

    public List<String> getTextCategories() {
        return Collections.unmodifiableList(textCategories);
    }

    public List<String> getVisualCategories() {
        return Collections.unmodifiableList(visualCategories);
    }

    public List<String> getMessageMediaTypes() {
        return Collections.unmodifiableList(messageMediaTypes);
    }

    /** Chat databases recorded as {@code COVERED_BY_MESSAGES} instead of prepared as passages. */
    public List<String> getMessageContainerMediaTypes() {
        return Collections.unmodifiableList(messageContainerMediaTypes);
    }

    public int getMaxTextCharsPerItem() {
        return maxTextCharsPerItem;
    }

    public int getPassageChars() {
        return passageChars;
    }

    public int getPassageOverlapChars() {
        return passageOverlapChars;
    }

    /**
     * Size of the focus windows of long items (research D23): the excerpt shown for an entry is the
     * focus window closest to the question. 0 turns them off.
     */
    public int getFocusChars() {
        return focusChars;
    }

    public int getFocusOverlapChars() {
        return focusOverlapChars;
    }

    public int getChatWindowMessages() {
        return chatWindowMessages;
    }

    public int getChatWindowStride() {
        return chatWindowStride;
    }

    public int getChatWindowMaxChars() {
        return chatWindowMaxChars;
    }

    public int getChatMaxGapMinutes() {
        return chatMaxGapMinutes;
    }

    /** Below this many characters a message never forms a sequence alone; it joins a neighbouring window. */
    public int getChatShortMessageChars() {
        return chatShortMessageChars;
    }

    public int getMinImageSize() {
        return minImageSize;
    }

    public int getDefaultDepth() {
        return defaultDepth;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public int getOversampling() {
        return oversampling;
    }

    public int getExactSearchMaxItems() {
        return exactSearchMaxItems;
    }

    public int getMaxQueryChars() {
        return maxQueryChars;
    }

    public double getFingerprintTolerance() {
        return fingerprintTolerance;
    }

    public TextMode getDefaultTextMode() {
        return defaultTextMode;
    }

    /** Weight of the word list in the fusion, in (0, 1]; see {@code RankFusion}. */
    public double getRrfLexicalWeight() {
        return rrfLexicalWeight;
    }

    public int getRrfK() {
        return rrfK;
    }

    /** Size of the lexical list before fusion; {@code 0} in the file means ceiling x oversampling. */
    public int getLexicalCandidates() {
        return lexicalCandidates > 0 ? lexicalCandidates : maxDepth * oversampling;
    }

    public int getContextMaxChars() {
        return contextMaxChars;
    }

    public int getHnswMaxConn() {
        return hnswMaxConn;
    }

    public int getHnswBeamWidth() {
        return hnswBeamWidth;
    }

    /** Inference threads; {@code 0} in the file means half the available processors, at least one. */
    public int getInferenceThreads() {
        return inferenceThreads > 0 ? inferenceThreads
                : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public int getCommitIntervalMinutes() {
        return commitIntervalMinutes;
    }
}
