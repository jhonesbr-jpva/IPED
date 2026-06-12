package iped.rcp.tests.parity;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import iped.engine.data.Category;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.CaseSessionService;
import iped.rcp.core.session.ICaseSessionManager;

/**
 * Diagnostic probe (T024/T028 debugging): dumps the engine category tree as
 * the RCP part consumes it, to compare the headless structure against the
 * collapsed tree observed inside the OSGi product.
 */
class CategoryTreeProbeTest {

    private static ICaseSessionManager manager;
    private static CaseSession session;

    @BeforeAll
    static void openCase() throws Exception {
        String caseDir = System.getProperty("case.dir");
        assumeTrue(caseDir != null && !caseDir.isBlank(), "-Dcase.dir not set");
        manager = new CaseSessionService();
        session = manager.open(List.of(Path.of(caseDir)));
    }

    @AfterAll
    static void closeCase() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void dumpCategoryTree() {
        Category root = session.getSource().getCategoryTree();
        StringBuilder out = new StringBuilder("\nCATEGORY TREE (name | numItems | childCount):\n");
        dump(root, 0, out);
        System.out.println(out);
    }

    private static void dump(Category node, int depth, StringBuilder out) {
        if (depth > 3) {
            return;
        }
        out.append("  ".repeat(depth)).append("- ").append(node.getName()).append(" | ").append(node.getNumItems())
                .append(" | ").append(node.getChildren().size()).append('\n');
        int i = 0;
        for (Category child : node.getChildren()) {
            if (i++ >= 25) {
                out.append("  ".repeat(depth + 1)).append("...\n");
                break;
            }
            dump(child, depth + 1, out);
        }
    }
}
