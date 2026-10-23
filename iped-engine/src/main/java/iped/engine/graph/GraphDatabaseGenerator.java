package iped.engine.graph;

import java.io.File;
import java.io.IOException;

/**
 * Builds the graph database of a case from the CSV files written by {@link GraphFileWriter}: bulk
 * import followed by the post-import operations (configured post-generation statements and contact
 * grouping). Implementations must run the database engine out of the IPED process.
 */
public interface GraphDatabaseGenerator {

    /**
     * Receives the output lines of the import, e.g. to show progress to the user.
     */
    interface ImportListener {

        void output(String line);

    }

    /**
     * @param listener
     *            receives the import output, may be {@code null}
     * @param dbHome
     *            graph database home folder, e.g. {@code <case>/iped/neo4j}
     * @param csvFolders
     *            folders with the CSV files (and import argument files) to import
     * @return {@code true} if the database was imported, {@code false} otherwise
     */
    boolean generate(ImportListener listener, File dbHome, File... csvFolders) throws IOException;

}
