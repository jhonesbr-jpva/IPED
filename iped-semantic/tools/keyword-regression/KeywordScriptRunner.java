import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

import iped.engine.config.Configuration;
import iped.engine.data.IPEDSource;
import iped.engine.search.IPEDSearcher;
import iped.search.SearchResult;

/**
 * SC-007: runs a fixed keyword-search script through IPED's own searcher on a case, with the
 * classes of one installation, and prints one line per query - the number of results and a SHA-256
 * over every (id, score) in result order - plus the first ten ids. Run it with the previous
 * installation and with the new one, on a case processed before semantic search existed, and diff
 * the two outputs: they must be identical.
 *
 * <pre>
 * java -cp "&lt;install&gt;\lib\*" KeywordScriptRunner.java &lt;install&gt; &lt;case folder&gt; &lt;script&gt; &gt; out.txt
 * </pre>
 *
 * Single-file source: it needs a JDK 11 to launch (the embedded runtime has no compiler), with the
 * installation's jars on the class path. It only reads the case.
 */
public class KeywordScriptRunner {

    public static void main(String[] args) throws Exception {
        String install = args[0];
        File caseDir = new File(args[1]);
        List<String> script = Files.readAllLines(Paths.get(args[2]), StandardCharsets.UTF_8);
        Configuration.getInstance().loadConfigurables(install, true);
        PrintStream out = new PrintStream(System.out, true, "UTF-8");
        try (IPEDSource source = new IPEDSource(caseDir, null, false)) {
            for (String line : script) {
                String q = line.strip();
                if (q.isEmpty() || q.startsWith("#")) {
                    continue;
                }
                SearchResult r = new IPEDSearcher(source, q).search();
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                StringBuilder first = new StringBuilder();
                for (int i = 0; i < r.getLength(); i++) {
                    String pair = r.getId(i) + ":" + String.format(Locale.ROOT, "%.6f", r.getScore(i)) + "\n";
                    sha.update(pair.getBytes(StandardCharsets.UTF_8));
                    if (i < 10) {
                        first.append(first.length() == 0 ? "" : ",").append(r.getId(i));
                    }
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : sha.digest()) {
                    hex.append(String.format("%02x", b));
                }
                out.println(q + "\t" + r.getLength() + "\t" + hex + "\t" + first);
            }
        }
    }
}
