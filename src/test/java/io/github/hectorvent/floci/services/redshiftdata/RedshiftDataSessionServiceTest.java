package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.iam.IamService;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RedshiftDataSessionServiceTest {

    @Test
    void sessionOnlyRequestsShareTemporaryTablesAndReportSessionMetadata() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ObjectNode created = fixture.service.executeStatement(fixture.request(
                    "CREATE LOCAL TEMPORARY TABLE staging(id integer)").put("SessionKeepAliveSeconds", 60), "us-east-1");
            assertTrue(created.path("SessionId").isTextual(), "A retained session must have an id");
            String session = created.path("SessionId").asText();
            fixture.service.executeStatement(fixture.reuse(session, "INSERT INTO staging VALUES (42)"), "us-east-1");
            ObjectNode query = fixture.service.executeStatement(fixture.reuse(session, "SELECT id FROM staging"), "us-east-1");
            ObjectNode described = fixture.service.describeStatement(fixture.mapper.createObjectNode().put("Id", query.path("Id").asText()));

            assertEquals("FINISHED", described.path("Status").asText(), described.toString());
            assertEquals(session, described.path("SessionId").asText());
            ObjectNode result = fixture.service.getStatementResult(fixture.mapper.createObjectNode().put("Id", query.path("Id").asText()));
            assertEquals(42, result.path("Records").get(0).get(0).path("longValue").asInt());
            assertEquals(session, fixture.service.listStatements(fixture.mapper.createObjectNode())
                    .path("Statements").get(0).path("SessionId").asText());
            verify(fixture.factory).open(any());
        }
    }

    @Test
    void batchAndSingleStatementCanShareOneSession() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ObjectNode request = fixture.request("unused").put("SessionKeepAliveSeconds", 60);
            request.remove("Sql");
            request.putArray("Sqls").add("CREATE LOCAL TEMPORARY TABLE staging(id integer)")
                    .add("INSERT INTO staging VALUES (7)");
            ObjectNode batch = fixture.service.batchExecuteStatement(request, "us-east-1");
            String session = batch.path("SessionId").asText();
            ObjectNode query = fixture.service.executeStatement(fixture.reuse(session, "SELECT id FROM staging"), "us-east-1");
            ObjectNode result = fixture.service.getStatementResult(fixture.mapper.createObjectNode().put("Id", query.path("Id").asText()));

            assertEquals(7, result.path("Records").get(0).get(0).path("longValue").asInt());
            verify(fixture.factory).open(any());
        }
    }

    @Test
    void clientTokenRetryKeepsTheOriginalSessionAndExecutesOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ObjectNode request = fixture.request("CREATE LOCAL TEMPORARY TABLE staging(id integer)")
                    .put("SessionKeepAliveSeconds", 60).put("ClientToken", "retained-session");
            ObjectNode first = fixture.service.executeStatement(request, "us-east-1");
            ObjectNode retry = fixture.service.executeStatement(request, "us-east-1");

            assertTrue(first.path("SessionId").isTextual());
            assertEquals(first.path("SessionId"), retry.path("SessionId"));
            assertEquals(first.path("Id"), retry.path("Id"));
            verify(fixture.factory).open(any());
        }
    }

    @Test
    void explicitTransactionCanBeRolledBackAcrossCalls() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String session = fixture.service.executeStatement(fixture.request("CREATE TABLE changes(id integer)")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            fixture.service.executeStatement(fixture.reuse(session, "BEGIN"), "us-east-1");
            fixture.service.executeStatement(fixture.reuse(session, "INSERT INTO changes VALUES (1)"), "us-east-1");
            assertEquals(0, fixture.count("changes"));
            fixture.service.executeStatement(fixture.reuse(session, "ROLLBACK"), "us-east-1");
            assertEquals(0, fixture.count("changes"));
            fixture.service.executeStatement(fixture.reuse(session, "BEGIN TRANSACTION"), "us-east-1");
            fixture.service.executeStatement(fixture.reuse(session, "INSERT INTO changes VALUES (2)"), "us-east-1");
            fixture.service.executeStatement(fixture.reuse(session, "COMMIT"), "us-east-1");
            assertEquals(1, fixture.count("changes"));
            fixture.service.executeStatement(fixture.reuse(session, "BEGIN"), "us-east-1");
            fixture.service.executeStatement(fixture.reuse(session, "INSERT INTO changes VALUES (3)"), "us-east-1");
            ObjectNode batch = fixture.reuse(session, "unused");
            batch.remove("Sql");
            batch.putArray("Sqls").add("INSERT INTO changes VALUES (4)");
            fixture.service.batchExecuteStatement(batch, "us-east-1");
            assertEquals(3, fixture.count("changes"));
        }
    }

    @Test
    void failedExplicitTransactionCannotAccidentallyCommitLaterStatements() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String session = fixture.service.executeStatement(fixture.request("CREATE TABLE changes(id integer)")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            fixture.service.executeStatement(fixture.reuse(session, "BEGIN -- keep this transaction open"), "us-east-1");
            fixture.service.executeStatement(fixture.reuse(session, "INSERT INTO changes VALUES (1)"), "us-east-1");
            assertEquals(0, fixture.count("changes"));
            fixture.service.executeStatement(fixture.reuse(session, "INSERT INTO missing_table VALUES (2)"), "us-east-1");
            ObjectNode later = fixture.service.executeStatement(fixture.reuse(session, "INSERT INTO changes VALUES (3)"), "us-east-1");
            assertEquals("FAILED", fixture.service.describeStatement(fixture.mapper.createObjectNode()
                    .put("Id", later.path("Id").asText())).path("Status").asText());
            ObjectNode batch = fixture.reuse(session, "unused");
            batch.remove("Sql");
            batch.putArray("Sqls").add("INSERT INTO changes VALUES (9)");
            String batchId = fixture.service.batchExecuteStatement(batch, "us-east-1").path("Id").asText();
            assertEquals("FAILED", fixture.service.describeStatement(fixture.mapper.createObjectNode()
                    .put("Id", batchId)).path("Status").asText());
            fixture.service.executeStatement(fixture.reuse(session, "ROLLBACK"), "us-east-1");
            assertEquals(0, fixture.count("changes"));
            fixture.service.executeStatement(fixture.reuse(session, "INSERT INTO changes VALUES (4)"), "us-east-1");
            assertEquals(1, fixture.count("changes"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void connectionFailureRetiresTheSessionWithoutReportingCancellation(boolean batch) throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection connection = spy(DriverManager.getConnection(fixture.url));
            doThrow(new SQLException("broken connection", "08006")).when(connection).prepareStatement("SELECT broken");
            when(fixture.factory.open(any())).thenReturn(connection);
            String session = fixture.service.executeStatement(fixture.request("SELECT 1")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            ObjectNode request = fixture.reuse(session, "SELECT broken");
            if (batch) {
                request.remove("Sql");
                request.putArray("Sqls").add("SELECT broken");
            }
            String failed = (batch ? fixture.service.batchExecuteStatement(request, "us-east-1")
                    : fixture.service.executeStatement(request, "us-east-1")).path("Id").asText();
            ObjectNode described = fixture.service.describeStatement(fixture.mapper.createObjectNode().put("Id", failed));
            assertEquals("FAILED", described.path("Status").asText());
            assertTrue(connection.isClosed());
            assertThrows(AwsException.class, () -> fixture.service.executeStatement(fixture.reuse(session, "SELECT 2"), "us-east-1"));
        }
    }

    @Test
    void unsupportedTransactionModesFailInsteadOfSilentlyCommitting() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String session = fixture.service.executeStatement(fixture.request("SELECT 1")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            for (String sql : new String[]{"BEGIN ISOLATION LEVEL SERIALIZABLE", "START TRANSACTION READ ONLY", "COMMIT AND CHAIN"}) {
                ObjectNode response = fixture.service.executeStatement(fixture.reuse(session, sql), "us-east-1");
                ObjectNode described = fixture.service.describeStatement(fixture.mapper.createObjectNode().put("Id", response.path("Id").asText()));
                assertEquals("FAILED", described.path("Status").asText());
                assertTrue(described.path("Error").asText().contains("not supported"));
            }
        }
    }

    @Test
    void failedBatchRollsBackWithoutClosingTheReusableConnection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String session = fixture.service.executeStatement(fixture.request("CREATE LOCAL TEMPORARY TABLE staging(id integer)")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            ObjectNode batch = fixture.reuse(session, "unused");
            batch.remove("Sql");
            batch.putArray("Sqls").add("INSERT INTO staging VALUES (42)").add("INSERT INTO missing_table VALUES (1)");
            String id = fixture.service.batchExecuteStatement(batch, "us-east-1").path("Id").asText();
            assertEquals("FAILED", fixture.service.describeStatement(fixture.mapper.createObjectNode().put("Id", id))
                    .path("Status").asText());
            ObjectNode query = fixture.service.executeStatement(fixture.reuse(session, "SELECT COUNT(*) FROM staging"), "us-east-1");
            ObjectNode result = fixture.service.getStatementResult(fixture.mapper.createObjectNode().put("Id", query.path("Id").asText()));
            assertEquals(0, result.path("Records").get(0).get(0).path("longValue").asInt());
            verify(fixture.factory).open(any());
        }
    }

    @Test
    void explicitSessionTerminationStillAllowsIdempotentReplay() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String session = fixture.service.executeStatement(fixture.request("SELECT 1")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            ObjectNode request = fixture.reuse(session, "SELECT 2").put("SessionKeepAliveSeconds", 0).put("ClientToken", "last");
            ObjectNode first = fixture.service.executeStatement(request, "us-east-1");
            ObjectNode retry = fixture.service.executeStatement(request, "us-east-1");
            assertEquals(first.path("Id"), retry.path("Id"));
            AwsException gone = assertThrows(AwsException.class, () -> fixture.service.executeStatement(
                    fixture.reuse(session, "SELECT 3"), "us-east-1"));
            assertEquals("ResourceNotFoundException", gone.getErrorCode());
        }
    }

    @Test
    void busySessionRejectsNewQueriesButAllowsClientTokenRetriesAndQueuedCancellation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String session = fixture.service.executeStatement(fixture.request("SELECT 1")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            for (int n = 0; n < 2; n++) {
                fixture.executor.submit("000000000000", "us-east-1", "block-" + n, execution -> {
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException expected) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                ObjectNode request = fixture.reuse(session, "SELECT 2").put("ClientToken", "queued");
                request.remove("WaitTimeSeconds");
                ObjectNode queued = fixture.service.executeStatement(request, "us-east-1");
                assertEquals(queued.path("Id"), fixture.service.executeStatement(request, "us-east-1").path("Id"));
                AwsException busy = assertThrows(AwsException.class, () -> fixture.service.executeStatement(
                        fixture.reuse(session, "SELECT 3"), "us-east-1"));
                assertEquals("ValidationException", busy.getErrorCode());
                assertTrue(fixture.service.cancelStatement(fixture.mapper.createObjectNode()
                        .put("Id", queued.path("Id").asText())).path("Status").asBoolean());
            } finally {
                release.countDown();
            }
            ObjectNode query = fixture.service.executeStatement(fixture.reuse(session, "SELECT 4"), "us-east-1");
            assertEquals("FINISHED", fixture.service.describeStatement(fixture.mapper.createObjectNode()
                    .put("Id", query.path("Id").asText())).path("Status").asText());
        }
    }

    @Test
    void mismatchedTargetsAndResetSessionsAreRejected() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String session = fixture.service.executeStatement(fixture.request("SELECT 1")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            for (String field : new String[]{"Database", "DbUser", "ClusterIdentifier", "WorkgroupName", "SecretArn"}) {
                assertThrows(AwsException.class, () -> fixture.service.executeStatement(
                        fixture.reuse(session, "SELECT 2").put(field, "different"), "us-east-1"));
            }
            fixture.service.clear();
            assertThrows(AwsException.class, () -> fixture.service.executeStatement(fixture.reuse(session, "SELECT 2"), "us-east-1"));
        }
    }

    @Test
    void fullExecutorQueueDoesNotLeaveTheSessionBusy() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String session = fixture.service.executeStatement(fixture.request("SELECT 1")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            List<RedshiftDataExecution> executions = new ArrayList<>();
            for (int n = 0; n < 2; n++) {
                executions.add(fixture.executor.submit("000000000000", "us-east-1", "block-" + n, execution -> {
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException expected) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                for (int n = 0; n < 8; n++) {
                    executions.add(fixture.executor.submit("000000000000", "us-east-1", "queued-" + n, execution -> { }));
                }
                AwsException full = assertThrows(AwsException.class, () -> fixture.service.executeStatement(
                        fixture.reuse(session, "SELECT 2"), "us-east-1"));
                assertEquals("ActiveStatementsExceededException", full.getErrorCode());
            } finally {
                release.countDown();
            }
            for (RedshiftDataExecution execution : executions) {
                execution.await(Duration.ofSeconds(5));
                assertTrue(execution.isDone());
            }
            String query = fixture.service.executeStatement(fixture.reuse(session, "SELECT 3"), "us-east-1").path("Id").asText();
            assertEquals("FINISHED", fixture.service.describeStatement(fixture.mapper.createObjectNode().put("Id", query)).path("Status").asText());
            verify(fixture.factory).open(any());
        }
    }

    @Test
    void rejectedAdmissionReleasesAnExistingSessionWithoutOpeningAnotherConnection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String session = fixture.service.executeStatement(fixture.request("SELECT 1")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            doThrow(new IllegalStateException("statement storage unavailable")).when(fixture.statements)
                    .putForAccount(any(), any());
            assertThrows(IllegalStateException.class, () -> fixture.service.executeStatement(
                    fixture.reuse(session, "SELECT 2"), "us-east-1"));
            doCallRealMethod().when(fixture.statements).putForAccount(any(), any());
            doThrow(new IllegalStateException("receipt storage unavailable")).when(fixture.receipts).put(any(), any(), any());
            assertThrows(IllegalStateException.class, () -> fixture.service.executeStatement(
                    fixture.reuse(session, "SELECT 2").put("ClientToken", "rejected"), "us-east-1"));
            doCallRealMethod().when(fixture.receipts).put(any(), any(), any());
            ObjectNode retried = fixture.service.executeStatement(fixture.reuse(session, "SELECT 2")
                    .put("ClientToken", "rejected"), "us-east-1");
            assertEquals("FINISHED", fixture.service.describeStatement(fixture.mapper.createObjectNode()
                    .put("Id", retried.path("Id").asText())).path("Status").asText());
            verify(fixture.factory).open(any());
        }
    }

    @Test
    void receiptReplaysAfterLosingLiveSessionsAndEvictingStatementResults() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String session = fixture.service.executeStatement(fixture.request("SELECT 1")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            ObjectNode request = fixture.reuse(session, "SELECT 2").put("ClientToken", "restart-replay");
            ObjectNode first = fixture.service.executeStatement(request, "us-east-1");
            fixture.statements.deleteForAccount("000000000000", first.path("Id").asText());
            RedshiftDataService restarted = new RedshiftDataService(fixture.resolver, fixture.factory, fixture.statements,
                    fixture.mapper, null, fixture.executor, null, null, null, fixture.receipts);

            ObjectNode replay = restarted.executeStatement(request, "us-east-1");
            assertEquals(first.path("Id"), replay.path("Id"));
            assertEquals(first.path("SessionId"), replay.path("SessionId"));
            verify(fixture.factory).open(any());
        }
    }

    @Test
    void uncheckedFailureAfterSqlExecutionAbortsTheExplicitTransaction() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection connection = spy(DriverManager.getConnection(fixture.url));
            when(fixture.factory.open(any())).thenReturn(connection);
            String session = fixture.service.executeStatement(fixture.request("CREATE TABLE changes(id integer)")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            PreparedStatement insert = spy(connection.prepareStatement("INSERT INTO changes VALUES (1)"));
            doAnswer(invocation -> {
                invocation.callRealMethod();
                throw new IllegalStateException("result conversion failed");
            }).when(insert).execute();
            doReturn(insert).when(connection).prepareStatement("INSERT INTO changes VALUES (1)");
            fixture.service.executeStatement(fixture.reuse(session, "BEGIN"), "us-east-1");
            String failed = fixture.service.executeStatement(fixture.reuse(session, "INSERT INTO changes VALUES (1)"), "us-east-1")
                    .path("Id").asText();
            ObjectNode described = fixture.service.describeStatement(fixture.mapper.createObjectNode().put("Id", failed));
            assertEquals("FAILED", described.path("Status").asText());
            assertTrue(described.path("Error").asText().contains("result conversion failed"), described.toString());
            String committed = fixture.service.executeStatement(fixture.reuse(session, "COMMIT"), "us-east-1").path("Id").asText();
            assertEquals("FINISHED", fixture.service.describeStatement(fixture.mapper.createObjectNode().put("Id", committed))
                    .path("Status").asText());
            assertEquals(0, fixture.count("changes"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedSessionIdentifiersFailBeforeTargetLookup(boolean batch) throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (String raw : new String[]{"\"malformed\"", "7", "true", "null", "[]", "{}"}) {
                ObjectNode request = fixture.request("SELECT 1");
                JsonNode invalid = fixture.mapper.readTree(raw);
                request.set("SessionId", invalid);
                if (batch) {
                    request.remove("Sql");
                    request.putArray("Sqls").add("SELECT 1");
                }
                AwsException error = assertThrows(AwsException.class, () -> {
                    if (batch) {
                        fixture.service.batchExecuteStatement(request, "us-east-1");
                    } else {
                        fixture.service.executeStatement(request, "us-east-1");
                    }
                });
                assertEquals("ValidationException", error.getErrorCode());
            }
            verifyNoInteractions(fixture.factory, fixture.resolver);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryRequestsDoNotResolveSessionCaller(boolean batch) throws Exception {
        RequestContext context = new RequestContext();
        context.setAccessKeyId("caller-key");
        IamService iam = mock(IamService.class);
        when(iam.resolveCallerArn("caller-key")).thenReturn(Optional.of("caller-arn"));
        try (Fixture fixture = new Fixture(context, iam)) {
            for (boolean token : new boolean[]{false, true}) {
                ObjectNode request = fixture.request("SELECT 1").put("SessionKeepAliveSeconds", 0);
                if (token) {
                    request.put("ClientToken", "ordinary");
                }
                if (batch) {
                    request.remove("Sql");
                    request.putArray("Sqls").add("SELECT 1");
                    fixture.service.batchExecuteStatement(request, "us-east-1");
                } else {
                    fixture.service.executeStatement(request, "us-east-1");
                }
            }
            verifyNoInteractions(iam);
        }
    }

    @Test
    void cleanupFailureIsReportedWithoutSkippingResetOfStatementAndReceiptState() throws Exception {
        try (Fixture fixture = new Fixture(); Connection connection = spy(DriverManager.getConnection(fixture.url))) {
            when(fixture.factory.open(any())).thenReturn(connection);
            ObjectNode request = fixture.request("SELECT 1").put("ClientToken", "cleanup-reset").put("SessionKeepAliveSeconds", 60);
            String id = fixture.service.executeStatement(request, "us-east-1").path("Id").asText();
            doThrow(new IllegalStateException("unexpected close failure")).doCallRealMethod().when(connection).close();

            assertThrows(IllegalStateException.class, fixture.service::clear);

            assertNull(fixture.statements.getForAccount("000000000000", id));
            assertNull(fixture.receipts.find(new RedshiftDataIdempotencyStore.Scope("000000000000", "us-east-1",
                    "ExecuteStatement", "cleanup-reset")));
        }
    }

    @Test
    void terminatingSessionReportsUnexpectedConnectionCleanupFailure() throws Exception {
        try (Fixture fixture = new Fixture(); Connection connection = spy(DriverManager.getConnection(fixture.url))) {
            when(fixture.factory.open(any())).thenReturn(connection);
            String session = fixture.service.executeStatement(fixture.request("SELECT 1")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            doThrow(new IllegalStateException("unexpected close failure")).doCallRealMethod().when(connection).close();
            String id = fixture.service.executeStatement(fixture.reuse(session, "SELECT 2")
                    .put("SessionKeepAliveSeconds", 0), "us-east-1").path("Id").asText();
            ObjectNode described = fixture.service.describeStatement(fixture.mapper.createObjectNode().put("Id", id));
            assertEquals("FAILED", described.path("Status").asText());
            assertTrue(described.path("Error").asText().contains("unexpected close failure"), described.toString());
        }
    }

    @Test
    void replayWithoutSessionFieldsStillChecksRetainedReceiptOwnership() throws Exception {
        RequestContext context = new RequestContext();
        context.setAccessKeyId("owner");
        IamService iam = mock(IamService.class);
        when(iam.resolveCallerArn("owner")).thenReturn(Optional.of("owner-principal"));
        when(iam.resolveCallerArn("other")).thenReturn(Optional.of("other-principal"));
        try (Fixture fixture = new Fixture(context, iam)) {
            ObjectNode request = fixture.request("SELECT 1").put("ClientToken", "retained-replay")
                    .put("SessionKeepAliveSeconds", 60);
            String id = fixture.service.executeStatement(request, "us-east-1").path("Id").asText();
            request.remove("SessionKeepAliveSeconds");
            context.setAccessKeyId("other");
            AwsException denied = assertThrows(AwsException.class, () -> fixture.service.executeStatement(request, "us-east-1"));
            assertEquals("ResourceNotFoundException", denied.getErrorCode());
            context.setAccessKeyId("owner");
            assertEquals(id, fixture.service.executeStatement(request, "us-east-1").path("Id").asText());
            verify(fixture.factory).open(any());
        }
    }

    @Test
    void malformedKeepAliveValuesAreRejectedBeforeOpeningAConnection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (int seconds : new int[]{-1, 86401}) {
                AwsException error = assertThrows(AwsException.class, () -> fixture.service.executeStatement(
                        fixture.request("SELECT 1").put("SessionKeepAliveSeconds", seconds), "us-east-1"));
                assertEquals("ValidationException", error.getErrorCode());
            }
            assertThrows(AwsException.class, () -> fixture.service.executeStatement(
                    fixture.request("SELECT 1").put("SessionKeepAliveSeconds", "60"), "us-east-1"));
            assertThrows(AwsException.class, () -> fixture.service.executeStatement(
                    fixture.request("SELECT 1").put("SessionKeepAliveSeconds", 0.5), "us-east-1"));
            verifyNoInteractions(fixture.factory);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final RedshiftDataResourceResolver resolver = mock(RedshiftDataResourceResolver.class);
        private final RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
        private final RedshiftDataExecutor executor = new RedshiftDataExecutor(2, 8, 1);
        private final RedshiftDataStatementStore statements = spy(new RedshiftDataStatementStore(24, Clock.systemUTC()));
        private final RedshiftDataIdempotencyStore receipts = spy(new RedshiftDataIdempotencyStore(
                AccountAwareStorageBackend.inMemory("000000000000"), Clock.systemUTC()));
        private final RedshiftDataService service;
        private final String url = "jdbc:h2:mem:" + UUID.randomUUID();

        private Fixture() throws Exception {
            this(null, null);
        }

        private Fixture(RequestContext context, IamService iam) throws Exception {
            Driver.load();
            when(resolver.resolve(any(), any())).thenAnswer(invocation -> {
                ObjectNode request = invocation.getArgument(0);
                if (!request.has("Database")) {
                    throw new AwsException("ValidationException", "Database is required", 400);
                }
                return new RedshiftDataResourceResolver.DatabaseTarget(
                        "arn:aws:redshift:us-east-1:000000000000:cluster:warehouse", "localhost", 5439,
                        "dev", "admin", "password");
            });
            when(factory.open(any())).thenAnswer(invocation -> DriverManager.getConnection(url));
            service = new RedshiftDataService(resolver, factory, statements, mapper, null, executor, null,
                    context, iam, receipts);
        }

        private ObjectNode request(String sql) {
            return mapper.createObjectNode().put("Sql", sql).put("WaitTimeSeconds", 5)
                    .put("ClusterIdentifier", "warehouse").put("Database", "dev").put("DbUser", "admin");
        }

        private ObjectNode reuse(String session, String sql) {
            return mapper.createObjectNode().put("Sql", sql).put("SessionId", session).put("WaitTimeSeconds", 5);
        }

        private int count(String table) throws Exception {
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }

        @Override
        public void close() {
            service.clear();
            executor.close();
        }
    }
}
