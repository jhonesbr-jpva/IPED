package iped.rcp.tests.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import iped.rcp.core.session.CaseOpenException;
import iped.rcp.core.session.CaseSession;
import iped.rcp.core.session.CaseSessionService;
import iped.rcp.core.session.ICaseSessionManager;
import iped.rcp.core.session.SessionState;

/**
 * Headless smoke of the case session service (T013 harness infrastructure):
 * proves the RCP core services run on a plain JVM over the engine API, which
 * is the foundation every parity test (T015, T025, T064...) builds on.
 *
 * <p>
 * The real-case test requires {@code -Dcase.dir=<reference-case>} and is
 * skipped otherwise (keeps CI green without case data).
 */
class CaseSessionHeadlessTest {

    @Test
    void openInvalidPathFailsCleanly() {
        ICaseSessionManager manager = new CaseSessionService();
        CaseOpenException error = assertThrows(CaseOpenException.class,
                () -> manager.open(List.of(Path.of("definitely-missing-case-dir"))));
        assertNotNull(error.getMessage());
        assertEquals(SessionState.CLOSED, manager.getState(), "failed open must roll back to CLOSED");
        assertFalse(manager.isOpen());
        assertTrue(manager.getCasePaths().isEmpty());
    }

    @Test
    void openReferenceCaseHeadless() throws Exception {
        String caseDir = System.getProperty("case.dir");
        assumeTrue(caseDir != null && !caseDir.isBlank(), "-Dcase.dir not set, skipping reference case test");

        ICaseSessionManager manager = new CaseSessionService();
        List<Boolean> listenerEvents = new CopyOnWriteArrayList<>();
        manager.addSessionListener(listenerEvents::add);

        CaseSession session = manager.open(List.of(Path.of(caseDir)));
        try {
            assertEquals(SessionState.READY, manager.getState());
            assertTrue(manager.isOpen());
            assertEquals(List.of(Boolean.TRUE), listenerEvents, "open must notify listeners once");
            assertFalse(session.getCasePaths().isEmpty());
            assertEquals(manager.getCasePaths(), session.getCasePaths());
            assertTrue(session.getSource().getTotalItems() > 0, "reference case must have items");
            assertFalse(session.isReadOnlyMedia(), "reference case on writable media");
        } finally {
            manager.close();
        }
        assertEquals(SessionState.CLOSED, manager.getState());
        assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), listenerEvents, "close must notify listeners");
        assertTrue(manager.getCasePaths().isEmpty());
    }
}
