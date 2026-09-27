package iped.semantic.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import iped.semantic.config.SemanticSearchConfig;
import iped.semantic.config.SemanticSearchConfig.Modality;
import iped.semantic.model.Embedder;
import iped.semantic.model.Embedder.Role;
import iped.semantic.model.EmbeddingException;
import iped.semantic.model.RemoteEmbedders;
import iped.utils.UTF8Properties;

/**
 * The two remote protocols (contracts/remote-model-protocol.md) against a fake HTTP server in the
 * test JVM: identity required, indices checked, retries on 5xx and timeouts, {@code Retry-After}
 * on 429, 401/403 as configuration, plain HTTP only on loopback, no redirect followed.
 */
public class RemoteEmbedderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "s3cret-token-value";

    /** One scripted answer. */
    private static final class Reply {
        final int status;
        final String body;
        final String retryAfter;
        final long delayMs;

        Reply(int status, String body, String retryAfter, long delayMs) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
            this.delayMs = delayMs;
        }
    }

    /** What the server received. */
    private static final class Seen {
        final String method;
        final String path;
        final String auth;
        final JsonNode body;
        final long at = System.currentTimeMillis();

        Seen(String method, String path, String auth, JsonNode body) {
            this.method = method;
            this.path = path;
            this.auth = auth;
            this.body = body;
        }
    }

    private HttpServer server;
    private final ConcurrentLinkedQueue<Reply> script = new ConcurrentLinkedQueue<>();
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    /** Answer when the script is empty: an echo of the inputs with one vector each. */
    private volatile String model = "remote/model@1";
    private volatile String sha = "abc123";
    private Path credentialFile;

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        credentialFile = Files.createTempFile("credential", ".txt");
        Files.write(credentialFile, (SECRET + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @After
    public void stop() throws Exception {
        server.stop(0);
        Files.deleteIfExists(credentialFile);
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] in = ex.getRequestBody().readAllBytes();
        JsonNode body = in.length == 0 ? null : JSON.readTree(in);
        seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                ex.getRequestHeaders().getFirst("Authorization"), body));
        Reply r = script.poll();
        if (r == null) {
            r = new Reply(200, defaultAnswer(ex.getRequestURI().getPath(), body), null, 0);
        }
        if (r.delayMs > 0) {
            try {
                Thread.sleep(r.delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (r.retryAfter != null) {
            ex.getResponseHeaders().add("Retry-After", r.retryAfter);
        }
        if (r.status >= 300 && r.status < 400) {
            ex.getResponseHeaders().add("Location", "https://elsewhere.example/steal");
        }
        byte[] out = r.body == null ? new byte[0] : r.body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(r.status, out.length == 0 ? -1 : out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    /** Vectors [3, 4 + i] per input: unit norm only after the client normalises them. */
    private String defaultAnswer(String path, JsonNode body) {
        if (path.endsWith("/iped-embed/1/model")) {
            return "{\"id\":\"" + model + "\",\"sha256\":\"" + sha + "\",\"dimensions\":2,\"modalities\":[\"text\",\"image\"]}";
        }
        if (path.endsWith("/iped-embed/1/embed")) {
            StringBuilder sb = new StringBuilder("{\"model\":{\"id\":\"" + model + "\",\"sha256\":\"" + sha
                    + "\"},\"embeddings\":[");
            for (int i = 0; i < body.path("inputs").size(); i++) {
                sb.append(i == 0 ? "" : ",").append("[3,").append(4 + i).append("]");
            }
            return sb.append("]}").toString();
        }
        StringBuilder sb = new StringBuilder("{\"model\":\"" + model + "\",\"data\":[");
        for (int i = 0; i < body.path("input").size(); i++) {
            sb.append(i == 0 ? "" : ",").append("{\"index\":").append(i).append(",\"embedding\":[3,").append(4 + i)
                    .append("]}");
        }
        return sb.append("]}").toString();
    }

    private SemanticSearchConfig config(String protocol, String endpoint, int retries, int timeoutSeconds,
            boolean allowPlainHttp) {
        UTF8Properties p = new UTF8Properties();
        p.setProperty("textModelSource", "remote");
        p.setProperty("visualModelSource", "remote");
        p.setProperty("textRemoteProtocol", protocol);
        p.setProperty("visualRemoteProtocol", protocol);
        p.setProperty("textRemoteEndpoint", endpoint);
        p.setProperty("visualRemoteEndpoint", endpoint);
        p.setProperty("textRemoteModel", "asked-name");
        p.setProperty("visualRemoteModel", "asked-name");
        p.setProperty("textRemoteCredentialFile", credentialFile.toString());
        p.setProperty("visualRemoteCredentialFile", credentialFile.toString());
        p.setProperty("textRemoteQueryPrefix", "\"query: \"");
        p.setProperty("textRemotePassagePrefix", "\"passage: \"");
        p.setProperty("remoteMaxRetries", String.valueOf(retries));
        p.setProperty("remoteTimeoutSeconds", String.valueOf(timeoutSeconds));
        p.setProperty("remoteAllowPlainHttp", String.valueOf(allowPlainHttp));
        SemanticSearchConfig c = new SemanticSearchConfig();
        c.processProperties(p);
        return c;
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/base";
    }

    private Embedder open(String protocol, Modality m, int retries) throws EmbeddingException {
        return RemoteEmbedders.open(config(protocol, endpoint(), retries, 5, false), m, 10);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    public void openAiIdentityIsWhatTheServiceReportsAndVectorsAreNormalised() throws Exception {
        Embedder e = open(RemoteEmbedders.OPENAI, Modality.TEXT, 0);
        assertEquals("the reported model, not the asked name", "remote/model@1", e.identity().getId());
        assertEquals(2, e.identity().getDimensions());
        float[][] v = e.embedTexts(Role.QUERY, List.of("quanto?", "amanhã"));
        assertEquals(0.6f, v[0][0], 1e-6f);
        assertEquals(0.8f, v[0][1], 1e-6f);
        assertEquals(1.0, Math.hypot(v[1][0], v[1][1]), 1e-6);
        Seen last = seen.get(seen.size() - 1);
        assertEquals("/base/v1/embeddings", last.path);
        assertEquals("Bearer " + SECRET, last.auth);
        assertEquals("asked-name", last.body.path("model").asText());
        assertEquals("the prefix travels inside the text", "query: quanto?", last.body.path("input").get(0).asText());
        e.embedTexts(Role.PASSAGE, List.of("x"));
        assertEquals("passage: x", seen.get(seen.size() - 1).body.path("input").get(0).asText());
        assertFalse("never in toString", e.toString().contains(SECRET));
    }

    @Test
    public void anAnswerWithoutIdentityIsRefused() {
        script.add(new Reply(200, "{\"data\":[{\"index\":0,\"embedding\":[1,0]}]}", null, 0));
        try {
            open(RemoteEmbedders.OPENAI, Modality.TEXT, 0);
            fail("an answer without model identity must be refused");
        } catch (EmbeddingException ex) {
            assertTrue(ex.isConfiguration());
            assertTrue(ex.getMessage(), ex.getMessage().contains("without a model identity"));
        }
    }

    @Test
    public void indicesThatDoNotCoverTheInputsFailTheBatch() throws Exception {
        Embedder e = open(RemoteEmbedders.OPENAI, Modality.TEXT, 0);
        script.add(new Reply(200, "{\"model\":\"remote/model@1\",\"data\":[{\"index\":0,\"embedding\":[1,0]}]}", null,
                0));
        try {
            e.embedTexts(Role.PASSAGE, List.of("a", "b"));
            fail("a missing index fails the whole request");
        } catch (EmbeddingException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("does not cover input 1"));
        }
    }

    @Test
    public void serverErrorsAreRetriedUpToTheLimit() throws Exception {
        Embedder e = open(RemoteEmbedders.OPENAI, Modality.TEXT, 2);
        int before = seen.size();
        script.add(new Reply(503, "{}", null, 0));
        script.add(new Reply(502, "{}", null, 0));
        assertNotNull(e.embedTexts(Role.PASSAGE, List.of("a")));
        assertEquals("two failures and one success", 3, seen.size() - before);

        before = seen.size();
        for (int i = 0; i < 3; i++) {
            script.add(new Reply(500, "{}", null, 0));
        }
        try {
            e.embedTexts(Role.PASSAGE, List.of("a"));
            fail("retries exhausted");
        } catch (EmbeddingException ex) {
            assertTrue("reported as unavailable, not as configuration", ex.isRemoteUnavailable());
            assertFalse(ex.isConfiguration());
            assertEquals(3, seen.size() - before);
        }
    }

    @Test
    public void aTimeoutIsRetriedAndThenUnavailable() throws Exception {
        Embedder e = RemoteEmbedders.open(config(RemoteEmbedders.OPENAI, endpoint(), 1, 1, false), Modality.TEXT, 10);
        script.add(new Reply(200, "{}", null, 2_500));
        script.add(new Reply(200, "{}", null, 2_500));
        try {
            e.embedTexts(Role.PASSAGE, List.of("a"));
            fail("timeout");
        } catch (EmbeddingException ex) {
            assertTrue(ex.getMessage(), ex.isRemoteUnavailable());
        }
    }

    @Test
    public void rateLimitWaitsForRetryAfter() throws Exception {
        Embedder e = open(RemoteEmbedders.OPENAI, Modality.TEXT, 2);
        int before = seen.size();
        script.add(new Reply(429, "{}", "1", 0));
        assertNotNull(e.embedTexts(Role.PASSAGE, List.of("a")));
        long waited = seen.get(before + 1).at - seen.get(before).at;
        assertTrue("waited " + waited + " ms, Retry-After said 1 s", waited >= 900);
    }

    @Test
    public void aRefusedCredentialIsConfigurationAndNotRetried() throws Exception {
        Embedder e = open(RemoteEmbedders.OPENAI, Modality.TEXT, 3);
        for (int status : new int[] { 401, 403 }) {
            int before = seen.size();
            script.add(new Reply(status, "{\"error\":\"echo of the evidence text\"}", null, 0));
            try {
                e.embedTexts(Role.PASSAGE, List.of("a"));
                fail(status + " must fail");
            } catch (EmbeddingException ex) {
                assertTrue(ex.isConfiguration());
                assertTrue(ex.getMessage().contains("credential refused"));
                assertFalse("the response body never goes into a message", ex.getMessage().contains("evidence"));
                assertEquals("not retried", 1, seen.size() - before);
            }
        }
    }

    @Test
    public void aRedirectIsNotFollowed() throws Exception {
        Embedder e = open(RemoteEmbedders.OPENAI, Modality.TEXT, 0);
        script.add(new Reply(302, null, null, 0));
        try {
            e.embedTexts(Role.PASSAGE, List.of("a"));
            fail("redirect");
        } catch (EmbeddingException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("redirects are not followed"));
        }
    }

    @Test
    public void plainHttpOnlyOnLoopbackOrWhenAllowed() throws Exception {
        try {
            RemoteEmbedders.open(config(RemoteEmbedders.OPENAI, "http://gpu-lab.invalid/iped", 0, 1, false),
                    Modality.TEXT, 10);
            fail("plain HTTP to a non-loopback host");
        } catch (EmbeddingException ex) {
            assertTrue(ex.isConfiguration());
            assertTrue(ex.getMessage(), ex.getMessage().contains("plain HTTP"));
        }
        try {
            RemoteEmbedders.open(config(RemoteEmbedders.OPENAI, "http://gpu-lab.invalid/iped", 0, 1, true),
                    Modality.TEXT, 10);
            fail("the host does not exist");
        } catch (EmbeddingException ex) {
            assertFalse("allowed: it failed on the network, not on the scheme", ex.getMessage().contains("plain HTTP"));
        }
        try {
            RemoteEmbedders.open(config(RemoteEmbedders.OPENAI, "https://user:pw@gpu-lab.invalid/iped", 0, 1, false),
                    Modality.TEXT, 10);
            fail("a credential in the URL");
        } catch (EmbeddingException ex) {
            assertTrue(ex.isConfiguration());
            assertFalse("the credential is not repeated", ex.getMessage().contains("pw"));
        }
        // loopback: the fake server itself
        assertNotNull(open(RemoteEmbedders.OPENAI, Modality.TEXT, 0));
    }

    @Test
    public void ipedEmbedCarriesIdentityHashImagesAndRoles() throws Exception {
        Embedder e = open(RemoteEmbedders.IPED_EMBED, Modality.VISUAL, 0);
        assertEquals("remote/model@1", e.identity().getId());
        assertEquals("abc123", e.identity().getSha256());
        assertTrue(e.supportsImages());
        assertEquals("GET", seen.get(0).method);
        assertEquals("/base/iped-embed/1/model", seen.get(0).path);

        e.embedTexts(Role.QUERY, List.of("arma de fogo"));
        JsonNode input = seen.get(seen.size() - 1).body.path("inputs").get(0);
        assertEquals("text", input.path("type").asText());
        assertEquals("the role goes along, the server applies the prefix", "query", input.path("role").asText());
        assertEquals("arma de fogo", input.path("text").asText());

        float[][] v = e.embedImages(List.of(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)));
        assertEquals(1.0, Math.hypot(v[0][0], v[0][1]), 1e-6);
        JsonNode img = seen.get(seen.size() - 1).body.path("inputs").get(0);
        assertEquals("image", img.path("type").asText());
        assertEquals("image/png", img.path("media_type").asText());
        assertTrue(img.path("data").asText().length() > 10);

        model = "another/model@2";
        try {
            e.embedTexts(Role.PASSAGE, List.of("x"));
            fail("identity changed mid-session");
        } catch (EmbeddingException ex) {
            assertTrue(ex.isConfiguration());
            assertTrue(ex.getMessage(), ex.getMessage().contains("now reports"));
        }
    }

    @Test
    public void ipedEmbedCountsMustMatch() throws Exception {
        Embedder e = open(RemoteEmbedders.IPED_EMBED, Modality.TEXT, 0);
        script.add(new Reply(200, "{\"model\":{\"id\":\"remote/model@1\",\"sha256\":\"abc123\"},\"embeddings\":[[1,0]]}",
                null, 0));
        try {
            e.embedTexts(Role.PASSAGE, List.of("a", "b"));
            fail("fewer embeddings than inputs");
        } catch (EmbeddingException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("1 embeddings for 2 inputs"));
        }
    }

    @Test
    public void openAiCannotServeTheVisualModality() {
        try {
            open(RemoteEmbedders.OPENAI, Modality.VISUAL, 0);
            fail("openai-embeddings has no images");
        } catch (EmbeddingException ex) {
            assertTrue(ex.isConfiguration());
        }
    }
}
