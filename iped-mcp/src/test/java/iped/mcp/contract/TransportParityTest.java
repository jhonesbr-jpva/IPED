package iped.mcp.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.File;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import iped.mcp.McpServerMain;
import iped.mcp.McpTestSupport;
import iped.mcp.config.McpServerConfig;
import iped.mcp.protocol.JsonRpcCodec;
import iped.mcp.session.CasePool;
import iped.mcp.session.WriteClaims;
import iped.mcp.transport.Transport;

/**
 * FR-015: the tool surface is the same over both transports.
 *
 * <p>
 * This is the same reasoning that gives the skill a single canonical source and byte-identical
 * wrappers. Guidance that differs between harnesses would produce different analyses of the same
 * evidence; a tool surface that differs between transports would do the same, and would do it
 * without anyone noticing, because nobody compares the two by hand.
 */
public class TransportParityTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void bothTransportsExposeTheSameToolsWithTheSameSchemas() throws Exception {
        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        CasePool pool = new CasePool();
        WriteClaims claims = new WriteClaims();

        String overStdio;
        String overSocket;
        try (McpServerMain local = new McpServerMain(config)) {
            overStdio = toolsOf(local);
        }
        try (McpServerMain remote = new McpServerMain(config, pool, claims, Transport.Kind.SOCKET, "127.0.0.1:65000",
                "perito.silva")) {
            overSocket = toolsOf(remote);
        }
        pool.close();

        assertFalse("the surface cannot be empty, or this test proves nothing", overStdio.isEmpty());
        assertEquals("no tool may exist, or differ, on only one transport", overStdio, overSocket);
    }

    /**
     * FR-011: the shape of a result is the same over both transports too.
     *
     * <p>
     * The surface check above compares {@code tools/list}, which never exercises a result. Once a
     * response can carry more than one kind of block, "same tools" stops being the whole of parity:
     * a transport that dropped or reordered the image block would expose the same surface and
     * answer differently, and nobody compares two transports by hand.
     *
     * <p>
     * Nothing in either transport inspects {@code result.content} — they carry JSON-RPC the codec
     * already serialized, and the block is built above both, in the dispatcher. So this asserts a
     * property the design gives rather than one the code arranges, which is the kind worth pinning
     * down before someone optimizes one transport and not the other.
     */
    @Test
    public void bothTransportsAnswerAThumbnailWithTheSameBlocks() throws Exception {
        File casePath = McpTestSupport.requireReferenceCase();

        McpServerConfig config = McpTestSupport.configWithTempAudit(temp.getRoot());
        CasePool pool = new CasePool();
        WriteClaims claims = new WriteClaims();

        String overStdio;
        String overSocket;
        try (McpServerMain local = new McpServerMain(config)) {
            overStdio = thumbnailContentOf(local, casePath);
        }
        try (McpServerMain remote = new McpServerMain(config, pool, claims, Transport.Kind.SOCKET, "127.0.0.1:65001",
                "perito.silva")) {
            overSocket = thumbnailContentOf(remote, casePath);
        }
        pool.close();

        assertFalse("the content cannot be empty, or this test proves nothing", overStdio.isEmpty());
        assertEquals("a thumbnail must answer with the same blocks on either transport", overStdio, overSocket);
    }

    /** The {@code content} array of a thumbnail call, on the first item of the case that has one. */
    private static String thumbnailContentOf(McpServerMain server, File casePath) throws Exception {
        String caseId = callTool(server, "iped_open_case", "case_path", casePath.getAbsolutePath())
                .path("structuredContent").path("case_id").asText();
        JsonNode page = callTool(server, "iped_search", "case_id", caseId, "query", "*:*", "page_size", 60,
                "include_snippets", false).path("structuredContent");
        for (JsonNode item : page.path("items")) {
            int itemId = item.path("item_id").asInt();
            JsonNode result = callTool(server, "iped_item_thumbnail", "case_id", caseId, "item_id", itemId);
            if (result.path("structuredContent").path("available").asBoolean()
                    && result.path("structuredContent").path("media_type").asText().startsWith("image/")) {
                return result.path("content").toString();
            }
        }
        throw new AssertionError("No item in this case has a readable thumbnail, so transport parity of the "
                + "image block cannot be checked. Build the bench with a profile that generates thumbnails; "
                + "fastmode does not — see specs/008-thumbnail-image-block/quickstart.md.");
    }

    /** One {@code tools/call}, returning its {@code result}. */
    private static JsonNode callTool(McpServerMain server, String tool, Object... keyValues) throws Exception {
        ObjectNode arguments = JsonRpcCodec.mapper().createObjectNode();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            String key = String.valueOf(keyValues[i]);
            if (value instanceof Integer) {
                arguments.put(key, (Integer) value);
            } else if (value instanceof Boolean) {
                arguments.put(key, (Boolean) value);
            } else {
                arguments.put(key, String.valueOf(value));
            }
        }
        ObjectNode params = JsonRpcCodec.mapper().createObjectNode();
        params.put("name", tool);
        params.set("arguments", arguments);

        ObjectNode request = JsonRpcCodec.mapper().createObjectNode();
        request.put("jsonrpc", JsonRpcCodec.VERSION);
        request.put("id", 1);
        request.put("method", "tools/call");
        request.set("params", params);

        try (JsonRpcCodec codec = new JsonRpcCodec(new java.io.ByteArrayInputStream(new byte[0]),
                new java.io.ByteArrayOutputStream())) {
            ObjectNode response = server.getDispatcher().dispatch(request, codec);
            if (response.has("error")) {
                throw new AssertionError(tool + " failed: " + response.path("error").path("message").asText());
            }
            return response.path("result");
        }
    }

    /** The tools/list answer, which is the surface exactly as an agent sees it. */
    private static String toolsOf(McpServerMain server) throws Exception {
        ObjectNode request = JsonRpcCodec.mapper().createObjectNode();
        request.put("jsonrpc", JsonRpcCodec.VERSION);
        request.put("id", 1);
        request.put("method", "tools/list");
        try (JsonRpcCodec codec = new JsonRpcCodec(new java.io.ByteArrayInputStream(new byte[0]),
                new java.io.ByteArrayOutputStream())) {
            return server.getDispatcher().dispatch(request, codec).path("result").toString();
        }
    }
}
