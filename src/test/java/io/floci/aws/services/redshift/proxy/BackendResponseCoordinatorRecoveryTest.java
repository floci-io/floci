package io.floci.aws.services.redshift.proxy;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendResponseCoordinatorRecoveryTest {

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    @Timeout(5)
    void syncPreservesTheNextCycleWhileItsReadyForQueryIsPending(boolean owned, boolean syncBeforeError)
            throws Exception {
        ExtendedQuerySession session = new ExtendedQuerySession();
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(session);
        BackendResponseCoordinator.Ticket failed = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);
        if (syncBeforeError) {
            coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);
        }
        if (owned) {
            coordinator.completeOwnedExecute(failed, true);
        } else {
            coordinator.onBackendFrame('E', new byte[0]);
        }
        if (!syncBeforeError) {
            BackendResponseCoordinator.Ticket skipped = coordinator.register(
                    BackendResponseCoordinator.Operation.EXECUTE, null);
            assertEquals(BackendResponseCoordinator.GateResult.SKIPPED_AFTER_ERROR,
                    coordinator.awaitTurn(skipped));
            coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);
        }

        CopyStatementParser.S3Statement nextCopy = CopyStatementParser.parse("COPY t FROM 's3://b/next'");
        coordinator.register(BackendResponseCoordinator.Operation.PARSE, session.stageParse("s", nextCopy));
        coordinator.register(BackendResponseCoordinator.Operation.BIND, session.stageBind("p", "s"));
        BackendResponseCoordinator.Ticket next = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);

        assertSame(nextCopy, session.portal("p").orElseThrow(),
                "A Sync already sent to the backend starts a new frontend query cycle");
        assertFalse(coordinator.isDiscardingUntilSync());
        assertTrue(coordinator.extendedCycleFailed(), "Frontend Sync must retain the backend failure");
        assertFalse(coordinator.awaitIdle(1, TimeUnit.MILLISECONDS));
        FutureTask<BackendResponseCoordinator.GateResult> turn =
                new FutureTask<>(() -> coordinator.awaitExtendedExecuteTurn(next));
        Thread waiter = Thread.ofVirtual().start(turn);
        try {
            assertThrows(TimeoutException.class, () -> turn.get(25, TimeUnit.MILLISECONDS),
                    "The new Execute must still wait for the previous Sync response");
            coordinator.onBackendFrame('Z', new byte[]{'I'});
            assertFalse(coordinator.extendedCycleFailed(), "ReadyForQuery ends the failed backend cycle");
            assertSame(nextCopy, session.portal("p").orElseThrow());
            coordinator.onBackendFrame('1', new byte[0]);
            coordinator.onBackendFrame('2', new byte[0]);
            assertEquals(BackendResponseCoordinator.GateResult.READY, turn.get(1, TimeUnit.SECONDS));
            coordinator.completeOwnedExecute(next, false);
            coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);
            coordinator.onBackendFrame('Z', new byte[]{'I'});
            assertTrue(coordinator.awaitIdle(1, TimeUnit.SECONDS));
        } finally {
            coordinator.close();
            waiter.interrupt();
            waiter.join(1000);
        }
    }
}
