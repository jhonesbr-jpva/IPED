package iped.mcp.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import iped.mcp.McpServerMain;
import iped.mcp.config.McpServerConfig;
import iped.mcp.session.CasePool;
import iped.mcp.session.WriteClaims;

/**
 * The transport the server has always had: one session over the process's own streams.
 *
 * <p>
 * Extracted rather than redesigned. It opens no port, which is how FR-057 of feature 001 is
 * satisfied by construction in the default configuration rather than by anyone remembering to turn
 * something off.
 *
 * <p>
 * The output stream is handed in rather than read from {@code System.out}: it is the one
 * {@link ProtocolStdout#claim()} took for the protocol, and {@code System.out} is by then stderr.
 */
public class StdioTransport implements Transport {

    private final McpServerConfig config;
    private final CasePool casePool;
    private final WriteClaims writeClaims;
    private final InputStream in;
    private final OutputStream out;

    public StdioTransport(McpServerConfig config, CasePool casePool, WriteClaims writeClaims, InputStream in,
            OutputStream out) {
        this.config = config;
        this.casePool = casePool;
        this.writeClaims = writeClaims;
        this.in = in;
        this.out = out;
    }

    @Override
    public Kind kind() {
        return Kind.STDIO;
    }

    @Override
    public void serve() throws IOException {
        try (McpServerMain server = new McpServerMain(config, casePool, writeClaims, Kind.STDIO, null, null)) {
            // Returns when the peer closes stdin, which is how every harness signals shutdown.
            server.start(in, out);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public void close() {
        // Nothing of its own: the streams belong to the process.
    }
}
