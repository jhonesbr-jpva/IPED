package iped.graph.neo4j;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the base command line for the out-of-process Neo4j child JVMs (import, post-import and the
 * Bolt {@code GraphServer}).
 *
 * <p>
 * Those children run with the isolated classpath of the plugin home ({@code iped-graph-neo4j/neo4j/}),
 * which carries the embedded Neo4j 5 engine and antlr4-runtime 4.13.x. The IPED process keeps antlr
 * 4.9.2 (required by libfqlite) and never loads the embedded engine, avoiding the incompatible-ATN
 * clash between the two antlr versions. See {@code GraphServer} and {@link Neo4jGraphService}.
 * </p>
 */
public class Neo4jChildLauncher {

    /** Sub-directory of the plugin home holding the isolated Neo4j stack. */
    public static final String NEO4J_LIB_DIR = "neo4j";

    // The embedded Neo4j 5 engine needs reflective access to a few JDK internals on Java 17+.
    private static final String[] ADD_OPENS = {
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED" };

    private Neo4jChildLauncher() {
    }

    /**
     * Locates the isolated Neo4j stack: {@code <plugin home>/neo4j/}, the plugin home being the
     * {@code iped-graph-neo4j/} folder next to the plugin jar.
     */
    public static File getNeo4jLibDir() throws IOException {
        Path home = Neo4jGraphServiceProvider.pluginHome();
        if (home == null) {
            throw new IOException("Cannot locate the " + Neo4jGraphServiceProvider.DISPLAY_NAME
                    + " plugin home: unknown location of the plugin jar.");
        }
        Path libDir = home.resolve(NEO4J_LIB_DIR);
        if (!Files.isDirectory(libDir)) {
            throw new IOException("Neo4j engine folder not found: " + libDir
                    + ". Extract the whole plugin zip into the plugin folder, next to the plugin jar.");
        }
        return libDir.toFile();
    }

    /**
     * Base command: {@code <java> -XX:+IgnoreUnrecognizedVMOptions <add-opens...> -cp <plugin home>/neo4j/*}.
     * Callers append the main class and its arguments.
     */
    public static List<String> baseCommand() throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(getJreExecutable().getAbsolutePath());
        cmd.add("-XX:+IgnoreUnrecognizedVMOptions");
        for (String addOpens : ADD_OPENS) {
            cmd.add(addOpens);
        }
        cmd.add("-cp");
        cmd.add(getNeo4jLibDir().getAbsolutePath() + File.separator + "*");
        return cmd;
    }

    public static File getJreExecutable() throws FileNotFoundException {
        String jreDirectory = System.getProperty("java.home");
        if (jreDirectory == null) {
            throw new IllegalStateException("java.home");
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        File exe = new File(jreDirectory, os.startsWith("windows") ? "bin/java.exe" : "bin/java");
        if (!exe.isFile()) {
            throw new FileNotFoundException(exe.toString());
        }
        return exe;
    }
}
