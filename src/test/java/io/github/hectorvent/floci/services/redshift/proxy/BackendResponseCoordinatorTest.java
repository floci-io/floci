package io.github.hectorvent.floci.services.redshift.proxy;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendResponseCoordinatorTest {

    private static final byte[] EMPTY_BODY = new byte[0];

    @Test
    void parseBindAndDescribeCompleteBeforeExecuteBecomesReady() throws InterruptedException {
        ExtendedQuerySession session = new ExtendedQuerySession();
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(session);
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");

        coordinator.register(BackendResponseCoordinator.Operation.PARSE, session.stageParse("s", copy));
        coordinator.register(BackendResponseCoordinator.Operation.BIND, session.stageBind("p", "s"));
        coordinator.register(BackendResponseCoordinator.Operation.DESCRIBE_PORTAL, null);
        BackendResponseCoordinator.Ticket execute = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);

        coordinator.onBackendFrame('1', EMPTY_BODY);
        coordinator.onBackendFrame('2', EMPTY_BODY);
        coordinator.onBackendFrame('n', EMPTY_BODY);

        assertEquals(BackendResponseCoordinator.GateResult.READY, coordinator.awaitTurn(execute));
        assertSame(copy, session.portal("p").orElseThrow());
    }

    @Test
    void statementDescribeWaitsPastParameterDescription() throws InterruptedException {
        ExtendedQuerySession session = new ExtendedQuerySession();
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(session);
        coordinator.register(BackendResponseCoordinator.Operation.DESCRIBE_STATEMENT, null);

        coordinator.onBackendFrame('t', EMPTY_BODY);

        assertFalse(coordinator.awaitIdle(1, TimeUnit.MILLISECONDS));
        coordinator.onBackendFrame('T', EMPTY_BODY);
        assertTrue(coordinator.awaitIdle(1, TimeUnit.SECONDS));
    }

    @Test
    void asynchronousFramesDoNotCompleteTheHeadOperation() throws InterruptedException {
        ExtendedQuerySession session = new ExtendedQuerySession();
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(session);
        coordinator.register(BackendResponseCoordinator.Operation.PARSE, null);

        coordinator.onBackendFrame('N', EMPTY_BODY);
        coordinator.onBackendFrame('A', EMPTY_BODY);
        coordinator.onBackendFrame('S', EMPTY_BODY);

        assertFalse(coordinator.awaitIdle(1, TimeUnit.MILLISECONDS));
        coordinator.onBackendFrame('1', EMPTY_BODY);
        assertTrue(coordinator.awaitIdle(1, TimeUnit.SECONDS));
    }

    @Test
    void extendedErrorRejectsPipelinedMutationsAndSkipsUntilSync() throws InterruptedException {
        ExtendedQuerySession session = new ExtendedQuerySession();
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(session);
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");
        coordinator.register(BackendResponseCoordinator.Operation.PARSE, session.stageParse("s", copy));
        coordinator.register(BackendResponseCoordinator.Operation.BIND, session.stageBind("p", "s"));
        BackendResponseCoordinator.Ticket execute = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);

        coordinator.onBackendFrame('E', EMPTY_BODY);

        assertEquals(BackendResponseCoordinator.GateResult.SKIPPED_AFTER_ERROR,
                coordinator.awaitTurn(execute));
        assertTrue(session.statement("s").isEmpty());
        assertTrue(session.portal("p").isEmpty());
        assertTrue(coordinator.isDiscardingUntilSync());

        BackendResponseCoordinator.Ticket skipped = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);
        assertEquals(BackendResponseCoordinator.GateResult.SKIPPED_AFTER_ERROR,
                coordinator.awaitTurn(skipped));

        coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);
        coordinator.onBackendFrame('Z', new byte[]{'I'});

        assertFalse(coordinator.isDiscardingUntilSync());
        assertEquals('I', coordinator.lastReadyStatus());
    }

    @Test
    void errorOnAnOperationWithoutAMutationRejectsLaterPipelinedMutations() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(session);
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");
        session.confirm(session.stageParse("s", copy));
        coordinator.register(BackendResponseCoordinator.Operation.DESCRIBE_STATEMENT, null);
        coordinator.register(BackendResponseCoordinator.Operation.BIND, session.stageBind("p", "s"));

        coordinator.onBackendFrame('E', EMPTY_BODY);

        assertTrue(session.portal("p").isEmpty());
    }

    @Test
    void simpleQueryErrorCompletesOnlyWhenReadyForQueryArrives() throws InterruptedException {
        ExtendedQuerySession session = new ExtendedQuerySession();
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(session);
        coordinator.register(BackendResponseCoordinator.Operation.SIMPLE_QUERY, null);

        coordinator.onBackendFrame('E', EMPTY_BODY);

        assertFalse(coordinator.isDiscardingUntilSync());
        assertFalse(coordinator.awaitIdle(1, TimeUnit.MILLISECONDS));

        coordinator.onBackendFrame('Z', new byte[]{'I'});

        assertTrue(coordinator.awaitIdle(1, TimeUnit.SECONDS));
        assertEquals('I', coordinator.lastReadyStatus());
    }

    @Test
    void readyForQueryInsideATransactionRetainsPortals() {
        ExtendedQuerySession session = new ExtendedQuerySession();
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(session);
        CopyStatementParser.S3Statement copy = CopyStatementParser.parse("COPY t FROM 's3://b/k'");
        session.confirm(session.stageParse("s", copy));
        session.confirm(session.stageBind("p", "s"));
        coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);

        coordinator.onBackendFrame('Z', new byte[]{'T'});

        assertSame(copy, session.portal("p").orElseThrow());
        assertEquals('T', coordinator.lastReadyStatus());
    }

    @Test
    void closeWakesABlockedWaiter() throws Exception {
        ExtendedQuerySession session = new ExtendedQuerySession();
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(session);
        coordinator.register(BackendResponseCoordinator.Operation.PARSE, null);
        BackendResponseCoordinator.Ticket execute = coordinator.register(
                BackendResponseCoordinator.Operation.EXECUTE, null);
        CompletableFuture<BackendResponseCoordinator.GateResult> result = CompletableFuture.supplyAsync(() -> {
            try {
                return coordinator.awaitTurn(execute);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        coordinator.close();

        assertEquals(BackendResponseCoordinator.GateResult.CLOSED, result.get(1, TimeUnit.SECONDS));
    }
}
