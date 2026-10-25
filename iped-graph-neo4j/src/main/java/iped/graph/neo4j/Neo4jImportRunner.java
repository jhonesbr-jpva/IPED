package iped.graph.neo4j;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.engine.graph.GraphDatabaseGenerator.ImportListener;
import iped.engine.graph.GraphFileWriter;
import iped.engine.graph.GraphTask;

/**
 * Runs the Neo4j bulk CSV import in a child JVM with the isolated Neo4j classpath of the plugin home
 * (embedded engine + antlr 4.13.x), via {@link Neo4jChildLauncher}. The import does not use the
 * Cypher parser, but it shares the same isolated stack as the Bolt {@code GraphServer}.
 */
public class Neo4jImportRunner {

    private static Logger LOGGER = LoggerFactory.getLogger(Neo4jImportRunner.class);

    // neo4j-admin entry point, referenced by name: it lives only in the isolated Neo4j stack.
    private static final String ADMIN_TOOL_CLASS = "org.neo4j.cli.AdminTool";

    private File[] inputs;
    private ImportListener listener;

    public Neo4jImportRunner(ImportListener listener, File... inputFolders) {
        super();
        this.inputs = inputFolders;
        this.listener = listener;
    }

    private class InputReader implements Runnable {

        private InputStream in;
        private ImportListener listener;

        public InputReader(InputStream in, ImportListener listener) {
            super();
            this.in = in;
            this.listener = listener;
        }

        @Override
        public void run() {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, Charset.defaultCharset()));
            String line = null;
            try {
                while ((line = reader.readLine()) != null) {
                    if (listener != null)
                        listener.output(line);
                    LOGGER.info(line);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

    }

    public void run(File neo4jHome, String dbName, boolean highIO) throws IOException {

        List<String> args = Neo4jChildLauncher.baseCommand();
        args.add(ADMIN_TOOL_CLASS);
        // Neo4j 5 moved the former top-level "import" command under "database import full".
        args.add("database");
        args.add("import");
        args.add("full");

        writeArgs(args, dbName, highIO);

        File csvDir = new File(neo4jHome, GraphTask.CSVS_DIR);
        padShortRows(csvDir, args);

        ExecutorService executorService = null;

        LOGGER.info("Running " + args.stream().collect(Collectors.joining(" ")));

        ProcessBuilder processBuilder = new ProcessBuilder(args);
        processBuilder.redirectErrorStream(true);
        neo4jHome.mkdirs();

        File emptyConf = new File(System.getProperty("java.io.tmpdir"), "neo4j.conf");
        if (!emptyConf.exists()) {
            emptyConf.createNewFile();
        }
        emptyConf.deleteOnExit();

        processBuilder.environment().put("NEO4J_HOME", neo4jHome.getAbsolutePath());
        processBuilder.environment().put("NEO4J_CONF", emptyConf.getParent());
        processBuilder.directory(csvDir);
        Process process = processBuilder.start();
        try {
            executorService = Executors.newFixedThreadPool(1);
            executorService.submit(new InputReader(process.getInputStream(), listener));
            int result = process.waitFor();
            if (result != 0) {
                throw new RuntimeException("Could not import graph database.");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            process.destroy();
            if (executorService != null) {
                executorService.shutdown();
            }
        }
    }

    public void writeArgs(List<String> args, String dbName, boolean highIO) throws IOException {

        // Neo4j 5 CLI options use the --opt=value form: boolean options no longer accept a
        // space-separated value, --high-io was renamed to --high-parallel-io=on|off|auto, and the
        // legacy --database flag was replaced by a trailing <database> positional argument.
        args.add("--input-encoding=utf-8");
        args.add("--bad-tolerance=0");
        if (highIO) {
            args.add("--high-parallel-io=on");
        }
        args.add("--ignore-empty-strings=true");
        args.add("--skip-duplicate-nodes=true");

        for (File input : inputs) {
            File[] argsFiles = input.listFiles(new ArgsFileFilter());
            for (File argFile : argsFiles) {
                for (String line : Files.readAllLines(argFile.toPath(), StandardCharsets.UTF_8)) {
                    args.add(line);
                }
            }
        }

        // End-of-options marker so the variadic --nodes/--relationships do not swallow the
        // database name as an extra file; <database> is the trailing positional in Neo4j 5.
        args.add("--");
        args.add(dbName);
    }

    /** Empty quoted value appended to short rows, one per missing column. */
    private static final byte[] PADDING = ",\"\"".getBytes(StandardCharsets.US_ASCII);

    /** Size of the read and write buffers used to check and pad the data files. */
    static final int BUFFER_SIZE = 1 << 16;

    /**
     * The Neo4j 5 bulk importer requires every data row to carry exactly the header's column count,
     * while Neo4j 4.4 silently treated missing trailing columns as empty values. {@link GraphFileWriter}
     * grows the header of a CSV as new properties show up, so rows written before a property first
     * appeared are shorter than the final header. This pads such rows, in place, with empty quoted
     * values before the import; files whose rows are already complete are left untouched.
     *
     * @param csvDir
     *            directory the import runs in; relative paths in {@code args} are resolved against it
     * @param args
     *            the import command line, whose {@code --nodes=header,data...} and
     *            {@code --relationships=header,data...} entries are inspected
     * @return number of padded rows over all files
     */
    static int padShortRows(File csvDir, List<String> args) throws IOException {
        int total = 0;
        for (String arg : args) {
            if (!arg.startsWith("--nodes=") && !arg.startsWith("--relationships=")) {
                continue;
            }
            String spec = arg.substring(arg.indexOf('=') + 1);
            // optional "<label>[:<label>...]=" prefix of the Neo4j syntax (not written by IPED)
            int labelSeparator = spec.lastIndexOf('=');
            if (labelSeparator >= 0) {
                spec = spec.substring(labelSeparator + 1);
            }
            String[] files = spec.split(",");
            if (files.length < 2) {
                // single file carrying its own header: rows were written together with it
                continue;
            }
            File header = resolve(csvDir, files[0]);
            for (int i = 1; i < files.length; i++) {
                total += padShortRows(header, resolve(csvDir, files[i]));
            }
        }
        return total;
    }

    private static File resolve(File dir, String path) {
        File file = new File(path);
        return file.isAbsolute() ? file : new File(dir, path);
    }

    /**
     * Pads the rows of {@code dataFile} that have fewer fields than the first line of
     * {@code headerFile} with empty quoted values. The data file is first only read, up to its first
     * short row, so complete files (such as every relationship file, whose writer knows all its columns
     * from the start) are left untouched. Otherwise a padded copy is written to
     * {@code <data file>.padding.tmp}, synced and moved over the data file. Every other byte (values,
     * control characters, the original line terminators) is preserved.
     *
     * @return number of padded rows, 0 when the file was left untouched
     */
    static int padShortRows(File headerFile, File dataFile) throws IOException {
        if (!headerFile.isFile() || !dataFile.isFile() || dataFile.length() == 0) {
            return 0;
        }
        int columns;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(headerFile), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            byte[] headerBytes = headerLine == null ? new byte[0] : headerLine.getBytes(StandardCharsets.UTF_8);
            columns = countFields(headerBytes, headerBytes.length);
        }
        if (columns == 0) {
            return 0;
        }
        try (InputStream in = new FileInputStream(dataFile)) {
            if (!hasShortRow(in, columns, BUFFER_SIZE)) {
                return 0;
            }
        }
        File tmp = new File(dataFile.getParentFile(), dataFile.getName() + ".padding.tmp");
        boolean replaced = false;
        try {
            int padded;
            try (InputStream in = new FileInputStream(dataFile);
                    FileOutputStream fileOut = new FileOutputStream(tmp);
                    OutputStream out = new BufferedOutputStream(fileOut, BUFFER_SIZE)) {
                padded = padRows(in, out, columns, BUFFER_SIZE);
                out.flush();
                // the copy replaces a file of the case: make it durable first
                fileOut.getFD().sync();
            }
            if (padded > 0) {
                replace(tmp.toPath(), dataFile.toPath());
                replaced = true;
                LOGGER.info("Padded {} row(s) of {} to {} columns, as required by the Neo4j 5 importer.", padded,
                        dataFile.getName(), columns);
            }
            return padded;
        } finally {
            if (!replaced) {
                if (dataFile.exists()) {
                    deleteQuietly(tmp);
                } else {
                    // a failed non-atomic replace may delete the target first: the copy is then the only one left
                    LOGGER.warn("Kept {}: {} is missing after a failed replace, the copy holds its data.",
                            tmp.getAbsolutePath(), dataFile.getName());
                }
            }
        }
    }

    /**
     * Read-only check: tells whether {@code in} has a non-empty row with fewer fields than
     * {@code columns}, reading no further than the first such row.
     */
    static boolean hasShortRow(InputStream in, int columns, int bufferSize) throws IOException {
        RowReader rows = new RowReader(in, bufferSize);
        while (rows.next()) {
            int fields = countFields(rows.row, rows.contentLength());
            if (fields > 0 && fields < columns) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copies {@code in} to {@code out}, inserting one {@link #PADDING} per missing column before the line
     * terminator of every non-empty row with fewer fields than {@code columns}. Empty rows, complete rows
     * and rows longer than the header are copied as they are.
     *
     * @return number of padded rows
     */
    static int padRows(InputStream in, OutputStream out, int columns, int bufferSize) throws IOException {
        RowReader rows = new RowReader(in, bufferSize);
        int padded = 0;
        while (rows.next()) {
            int contentLength = rows.contentLength();
            int fields = countFields(rows.row, contentLength);
            out.write(rows.row, 0, contentLength);
            if (fields > 0 && fields < columns) {
                for (int i = fields; i < columns; i++) {
                    out.write(PADDING);
                }
                padded++;
            }
            // the original line terminator, if any
            out.write(rows.row, contentLength, rows.length - contentLength);
        }
        return padded;
    }

    /**
     * Replaces {@code target} with {@code source}, with an atomic rename when the file system supports it.
     */
    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Best-effort removal of a temporary file: a failure is logged, never thrown. */
    private static void deleteQuietly(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not delete temporary file {}: {}", file.getAbsolutePath(), e.toString());
        }
    }

    /**
     * Counts the CSV fields of one row, honoring RFC 4180 quoting: commas inside a quoted field do
     * not separate fields and a doubled quote inside a quoted field is an escaped quote.
     */
    static int countFields(byte[] row, int length) {
        if (length == 0) {
            return 0;
        }
        int fields = 1;
        boolean quoted = false;
        for (int i = 0; i < length; i++) {
            byte c = row[i];
            if (c == '"') {
                if (quoted && i + 1 < length && row[i + 1] == '"') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                fields++;
            }
        }
        return fields;
    }

    /**
     * Reads the rows of a CSV file with bulk reads. A row is everything up to and including a
     * {@code '\n'}, or up to the end of the input for the last row. It is kept in a buffer reused from
     * row to row, which grows to the longest row.
     */
    private static final class RowReader {

        /** Largest array size the JVM reliably allocates. */
        private static final int MAX_ROW_LENGTH = Integer.MAX_VALUE - 8;

        private final InputStream in;
        private final byte[] buffer;
        private int position;
        private int limit;

        /** Bytes of the current row, line terminator included, in {@code row[0..length)}. */
        byte[] row = new byte[1024];
        int length;

        RowReader(InputStream in, int bufferSize) {
            this.in = in;
            this.buffer = new byte[bufferSize];
        }

        /**
         * @return {@code false} at the end of the input, when there is no further row
         */
        boolean next() throws IOException {
            length = 0;
            while (true) {
                if (position == limit) {
                    int read = in.read(buffer);
                    if (read == -1) {
                        return length > 0;
                    }
                    position = 0;
                    limit = read;
                }
                int end = position;
                while (end < limit && buffer[end] != '\n') {
                    end++;
                }
                boolean newline = end < limit;
                if (newline) {
                    end++;
                }
                append(position, end - position);
                position = end;
                if (newline) {
                    return true;
                }
            }
        }

        private void append(int offset, int count) throws IOException {
            if (count > MAX_ROW_LENGTH - length) {
                throw new IOException("CSV row longer than " + MAX_ROW_LENGTH + " bytes");
            }
            if (length + count > row.length) {
                // doubling, without int overflow on huge rows
                int grown = row.length > MAX_ROW_LENGTH / 2 ? MAX_ROW_LENGTH : 2 * row.length;
                row = Arrays.copyOf(row, Math.max(length + count, grown));
            }
            System.arraycopy(buffer, offset, row, length, count);
            length += count;
        }

        /**
         * @return length of the row without its line terminator ({@code "\n"}, {@code "\r\n"}, or a
         *         {@code '\r'} ending the input)
         */
        int contentLength() {
            int end = length;
            if (end > 0 && row[end - 1] == '\n') {
                end--;
            }
            if (end > 0 && row[end - 1] == '\r') {
                end--;
            }
            return end;
        }

    }

    /**
     * Accepts only the import argument files written by {@link GraphFileWriter},
     * {@code import-tool-args-<suffix>.txt} (e.g. {@code import-tool-args-iped.txt}, also in the folders
     * prepared for a multi-case graph). Any other name is ignored, such as {@code import-tool-args.txt},
     * a compressed copy (not UTF-8 text) or a copy made by hand (which would repeat every option).
     */
    private static class ArgsFileFilter implements FileFilter {

        private static final Pattern NAME = Pattern
                .compile(Pattern.quote(GraphFileWriter.ARGS_FILE_NAME) + "-[A-Za-z0-9_]+\\.txt");

        @Override
        public boolean accept(File pathname) {
            return NAME.matcher(pathname.getName()).matches();
        }

    }

}
