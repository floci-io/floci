package io.floci.aws.services.redshift.proxy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtendedQueryPortalBindingTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "portal"})
    void rebindToSameStatementSurvivesPriorTransactionEnd(String portalName) {
        ExtendedQuerySession session = new ExtendedQuerySession();
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(session);
        CopyStatementParser.S3Statement unload = preparedUnload(session);
        session.confirm(session.stageBind(portalName, "unload"));
        coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);

        ExtendedQuerySession.Mutation nextBind = session.stageBind(portalName, "unload");
        coordinator.register(BackendResponseCoordinator.Operation.BIND, nextBind);
        coordinator.onBackendFrame('Z', new byte[]{'I'});

        assertSame(unload, session.portal(portalName).orElse(null));
        coordinator.onBackendFrame('2', new byte[0]);
        session.transactionEnded();
        assertTrue(session.portal(portalName).isEmpty());
        assertSame(unload, session.statement("unload").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "portal"})
    void rejectedRebindDoesNotRestoreExpiredPriorPortal(String portalName) {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement unload = preparedUnload(session);
        session.confirm(session.stageBind(portalName, "unload"));
        ExtendedQuerySession.Mutation nextBind = session.stageBind(portalName, "unload");
        session.transactionEnded();
        assertSame(unload, session.portal(portalName).orElse(null));

        session.rejectFrom(nextBind);

        assertTrue(session.portal(portalName).isEmpty());
        assertSame(unload, session.statement("unload").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "portal"})
    void rejectingEarlierBindPreservesLaterBindToSameStatement(String portalName) {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement unload = preparedUnload(session);
        ExtendedQuerySession.Mutation first = session.stageBind(portalName, "unload");
        ExtendedQuerySession.Mutation second = session.stageBind(portalName, "unload");

        session.rejectFrom(first);

        assertSame(unload, session.portal(portalName).orElse(null));
        session.confirm(second);
        session.transactionEnded();
        assertTrue(session.portal(portalName).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "portal"})
    void rejectedPipelinedBindsDoNotRestoreRejectedPortal(String portalName) {
        ExtendedQuerySession session = new ExtendedQuerySession();
        BackendResponseCoordinator coordinator = new BackendResponseCoordinator(session);
        CopyStatementParser.S3Statement unload = preparedUnload(session);
        coordinator.register(BackendResponseCoordinator.Operation.BIND,
                session.stageBind(portalName, "unload"));
        coordinator.register(BackendResponseCoordinator.Operation.BIND,
                session.stageBind(portalName, "unload"));
        coordinator.register(BackendResponseCoordinator.Operation.SYNC, null);

        coordinator.onBackendFrame('E', new byte[0]);

        assertTrue(session.portal(portalName).isEmpty());
        assertSame(unload, session.statement("unload").orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "portal"})
    void rejectingBindsRestoresConfirmedPortalAcrossUnrelatedMutation(String portalName) {
        ExtendedQuerySession session = new ExtendedQuerySession();
        CopyStatementParser.S3Statement unload = preparedUnload(session);
        session.confirm(session.stageParse("other", null, "SELECT 2"));
        session.confirm(session.stageBind(portalName, "unload"));
        ExtendedQuerySession.Mutation first = session.stageBind(portalName, "other");
        ExtendedQuerySession.Mutation unrelated = session.stageParse("third", null, "SELECT 3");
        ExtendedQuerySession.Mutation second = session.stageBind(portalName, "other");

        session.rejectFrom(first);
        session.rejectFrom(unrelated);
        session.rejectFrom(second);

        assertSame(unload, session.portal(portalName).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "portal"})
    void rejectingBindsDoesNotRestoreExpiredConfirmedPortal(String portalName) {
        ExtendedQuerySession session = new ExtendedQuerySession();
        preparedUnload(session);
        session.confirm(session.stageBind(portalName, "unload"));
        ExtendedQuerySession.Mutation first = session.stageBind(portalName, "unload");
        ExtendedQuerySession.Mutation second = session.stageBind(portalName, "unload");
        session.transactionEnded();

        session.rejectFrom(first);
        session.rejectFrom(second);

        assertTrue(session.portal(portalName).isEmpty());
    }

    private static CopyStatementParser.S3Statement preparedUnload(ExtendedQuerySession session) {
        CopyStatementParser.S3Statement unload = CopyStatementParser.parse(
                "UNLOAD ('SELECT 1') TO 's3://bucket/data/' FORMAT AS PARQUET");
        session.confirm(session.stageParse("unload", unload));
        return unload;
    }
}
