package iped.semantic.task;

import org.apache.tika.metadata.Metadata;

/** Test access to package-private helpers of the task. */
public final class SemanticTextTaskAccess {

    private SemanticTextTaskAccess() {
    }

    public static String messageBody(Metadata md) {
        return SemanticTextTask.messageBody(md);
    }
}
