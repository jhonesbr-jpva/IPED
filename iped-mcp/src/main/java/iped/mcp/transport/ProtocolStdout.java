package iped.mcp.transport;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * The process's stdout, reserved for the protocol.
 *
 * <p>
 * Where stdout carries JSON-RPC — the server under the stdio transport, and the relay — a single
 * line that is not a message corrupts the channel. The server's own code never writes one; its
 * dependencies do. The Sleuthkit bindings print two lines with {@code System.out.println} when they
 * load their native library, which happens the first time a case built from a disk image is opened:
 * mid-session, after {@code initialize} has been answered, ahead of the response a strict client is
 * waiting for.
 *
 * <p>
 * Silencing libraries one at a time would chase every dependency, present and future. Instead the
 * entry point takes the stream once, before anything else runs, hands it to the transport alone, and
 * points {@code System.out} at stderr for the rest of the process. A stray print from any library
 * then lands among the diagnostics, and nothing but the transport can reach the channel.
 *
 * <p>
 * Two things it does not cover. Native code writing to file descriptor 1 directly never goes through
 * {@code System.out}; none has been observed. And the logger is initialized when the entry class
 * loads, before {@code main} runs, so a Log4j console appender aimed at stdout has already captured
 * the original stream: {@code -Dlog4j.configurationFile} pointing at
 * {@code conf/Log4j2ConfigurationMcp.xml} is still required.
 */
public final class ProtocolStdout {

    private ProtocolStdout() {
    }

    /**
     * Takes stdout for the protocol and sends every other write to stderr.
     *
     * <p>
     * Call it once, as the first statement of an entry point whose stdout carries the protocol. It
     * changes process-wide state, which is why it is called from {@code main} and never from a class
     * a host process might load to embed the server.
     *
     * @return the stream that was stdout, for the transport and nothing else
     */
    public static OutputStream claim() {
        PrintStream protocol = System.out;
        System.setOut(System.err);
        return protocol;
    }
}
