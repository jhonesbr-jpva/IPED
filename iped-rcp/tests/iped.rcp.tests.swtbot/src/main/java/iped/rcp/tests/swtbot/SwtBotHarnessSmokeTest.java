package iped.rcp.tests.swtbot;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/**
 * Harness wiring smoke (T013): proves the test bundle resolves inside the
 * tycho-surefire UI harness with SWTBot on the classpath. Real UI flows
 * start with T014 ({@code TriageFlowTest}).
 *
 * <p>
 * The whole module is skipped unless {@code -DskipUiTests=false} (see pom):
 * the harness launches the full e4 product, which needs a display and a
 * reference case ({@code -Dcase.dir}).
 */
public class SwtBotHarnessSmokeTest {

    @Test
    public void swtBotIsOnTheTestClasspath() throws Exception {
        assertNotNull(Class.forName("org.eclipse.swtbot.swt.finder.SWTBot"));
    }
}
