package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.redshift.RedshiftAuditLogPublisher;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import org.awaitility.Awaitility;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedshiftDataAuditTest {

    @ParameterizedTest
    @CsvSource({"COMMIT,false", "END,false", "COMMIT,true", "END,true",
            "/* outer /* inner */ comment */ COMMIT WORK,true"})
    void explicitBatchCommitRejectsCancellationDuringAndAfterCommit(String command, boolean sameEntry) throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection connection = fixture.mockSuccessfulBackend();
            PreparedStatement create = mock(PreparedStatement.class);
            PreparedStatement commit = mock(PreparedStatement.class);
            PreparedStatement tail = mock(PreparedStatement.class);
            CountDownLatch committing = new CountDownLatch(1);
            CountDownLatch releaseCommit = new CountDownLatch(1);
            CountDownLatch runningTail = new CountDownLatch(1);
            CountDownLatch releaseTail = new CountDownLatch(1);
            when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0);
                return sql.contains("CREATE USER") ? create : sql.contains("SELECT") ? tail : commit;
            });
            when(commit.execute()).thenAnswer(invocation -> {
                committing.countDown();
                assertTrue(releaseCommit.await(5, TimeUnit.SECONDS));
                return false;
            });
            when(tail.execute()).thenAnswer(invocation -> {
                runningTail.countDown();
                assertTrue(releaseTail.await(5, TimeUnit.SECONDS));
                return false;
            });
            ObjectNode request = fixture.request("unused");
            request.remove("WaitTimeSeconds");
            ArrayNode sqls = request.putArray("Sqls").add("CREATE USER alice");
            if (sameEntry) {
                sqls.add(command + "; SELECT 1");
            } else {
                sqls.add(command).add("SELECT 1");
            }
            String id = fixture.service.batchExecuteStatement(request, "us-east-1").path("Id").asText();
            ObjectNode identifier = fixture.mapper.createObjectNode().put("Id", id);
            try {
                assertTrue(committing.await(5, TimeUnit.SECONDS));
                assertFalse(fixture.service.cancelStatement(identifier).path("Status").asBoolean(),
                        "cancellation must be rejected while an explicit commit is running");
                releaseCommit.countDown();
                assertTrue(runningTail.await(5, TimeUnit.SECONDS));
                assertFalse(fixture.service.cancelStatement(identifier).path("Status").asBoolean(),
                        "cancellation must be rejected after an explicit commit succeeds");
            } finally {
                releaseCommit.countDown();
                releaseTail.countDown();
            }
            Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals("FINISHED", fixture.service.describeStatement(identifier).path("Status").asText()));
            assertEquals(List.of("alice"), fixture.loggedUsers());
        }
    }

    @Test
    void commentedUserDdlProducesUserAndActivityRecords() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.execute("/* migration */ CREATE /* change */ USER alice PASSWORD 'secret'");
            assertTrue(fixture.records("userlog").stream().anyMatch(line -> line.contains(",alice,,Create,")),
                    fixture.events.toString());
            assertTrue(fixture.records("useractivitylog").stream().allMatch(line -> !line.contains("secret")));
        }
    }

    @Test
    void batchPublishesAttemptedSqlAndUserChangesOnlyAfterCommit() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.batch("CREATE USER alice PASSWORD 'secret'", "SELECT 1");
            assertEquals(2, fixture.records("useractivitylog").size());
            assertEquals(1, fixture.records("userlog").size());
            fixture.events.clear();

            fixture.batch("CREATE USER bob PASSWORD 'secret'", "SELECT * FROM missing", "SELECT 3");
            assertEquals(2, fixture.records("useractivitylog").size());
            assertTrue(fixture.records("userlog").isEmpty(), fixture.events.toString());
        }
    }

    @Test
    void batchSavepointRollbackDoesNotLogDiscardedUsers() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.mockSuccessfulBackend();
            fixture.assertFinished(fixture.batch("SAVEPOINT s", "CREATE USER bob PASSWORD 'secret'", "ROLLBACK TO s"));
            assertTrue(fixture.records("userlog").isEmpty());
            assertEquals(3, fixture.records("useractivitylog").size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ROLLBACK TO s", "ROLLBACK WORK TO SAVEPOINT s", "-- comment\r rOlLbAcK To s"
    })
    void batchSavepointRollbackPreservesEarlierChanges(String rollback) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.mockSuccessfulBackend();
            fixture.assertFinished(fixture.batch("CREATE USER kept", "SAVEPOINT s", "CREATE USER discarded", rollback));
            assertEquals(List.of("kept"), fixture.loggedUsers());
        }
    }

    @Test
    void batchTracksNestedRepeatedAndReleasedSavepointsInsideOneEntry() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection connection = fixture.mockSuccessfulBackend();
            String sql = "; CREATE USER kept; SAVEPOINT s; CREATE USER discarded; SAVEPOINT s; CREATE USER nested; "
                    + "ROLLBACK TO s; RELEASE SAVEPOINT s; ROLLBACK TO s; "
                    + "CREATE USER repeated; ROLLBACK TO s; CREATE USER after_rollback";
            ObjectNode response = fixture.batch(sql);
            fixture.assertFinished(response);
            assertEquals(List.of("kept", "after_rollback"), fixture.loggedUsers());
            verify(connection).prepareStatement(" CREATE USER kept");
            assertEquals(sql, fixture.service.describeStatement(fixture.mapper.createObjectNode()
                    .put("Id", response.path("Id").asText())).path("SubStatements").get(0).path("QueryString").asText());
        }
    }

    @Test
    void releasingSavepointRetainsChangesForTheFinalCommit() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.mockSuccessfulBackend();
            fixture.assertFinished(fixture.batch("SAVEPOINT s", "CREATE USER kept", "RELEASE s"));
            assertEquals(List.of("kept"), fixture.loggedUsers());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLLBACK", "ABORT"})
    void batchRollbackDiscardsOnlyChangesSinceTheLastCommit(String rollback) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.mockSuccessfulBackend();
            fixture.assertFinished(fixture.batch("CREATE USER committed", "COMMIT", "CREATE USER discarded",
                    rollback, "CREATE USER final_commit"));
            assertEquals(List.of("committed", "final_commit"), fixture.loggedUsers());
        }
    }

    @Test
    void failedBatchStillLogsChangesCommittedByAnEarlierStatement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection connection = fixture.mockSuccessfulBackend();
            when(connection.prepareStatement("SELECT broken")).thenThrow(new SQLException("broken", "42P01"));
            ObjectNode response = fixture.batch("CREATE USER committed", "END", "CREATE USER discarded", "SELECT broken");
            assertEquals("FAILED", fixture.service.describeStatement(fixture.mapper.createObjectNode()
                    .put("Id", response.path("Id").asText())).path("Status").asText());
            assertEquals(List.of("committed"), fixture.loggedUsers());
        }
    }

    @Test
    void commitBeforeAnErrorInsideTheSameEntryRetainsCommittedAuditRecords() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Connection connection = fixture.mockSuccessfulBackend();
            when(connection.prepareStatement(" SELECT broken")).thenThrow(new SQLException("broken", "42P01"));
            ObjectNode response = fixture.batch("CREATE USER committed", "COMMIT; SELECT broken");
            assertEquals("FAILED", fixture.service.describeStatement(fixture.mapper.createObjectNode()
                    .put("Id", response.path("Id").asText())).path("Status").asText());
            assertEquals(List.of("committed"), fixture.loggedUsers());
        }
    }

    @Test
    void batchSavepointRollbackRetainsPendingChangesFromTheSession() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.mockSuccessfulBackend();
            String session = fixture.service.executeStatement(fixture.request("BEGIN")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            fixture.reuse(session, "CREATE USER kept");
            ObjectNode request = fixture.mapper.createObjectNode().put("SessionId", session).put("WaitTimeSeconds", 5);
            request.putArray("Sqls").add("SAVEPOINT s").add("CREATE USER discarded").add("ROLLBACK TO s");
            fixture.assertFinished(fixture.service.batchExecuteStatement(request, "us-east-1"));
            assertEquals(List.of("kept"), fixture.loggedUsers());
        }
    }

    @Test
    void batchRollbackCanUseSavepointCreatedByAnEarlierSessionStatement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.mockSuccessfulBackend();
            String session = fixture.service.executeStatement(fixture.request("BEGIN")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            fixture.reuse(session, "CREATE USER kept");
            fixture.reuse(session, "SAVEPOINT s");
            fixture.reuse(session, "CREATE USER discarded");
            ObjectNode request = fixture.mapper.createObjectNode().put("SessionId", session).put("WaitTimeSeconds", 5);
            request.putArray("Sqls").add("ROLLBACK TO s");
            fixture.assertFinished(fixture.service.batchExecuteStatement(request, "us-east-1"));
            assertEquals(List.of("kept"), fixture.loggedUsers());
        }
    }

    @Test
    void transactionKeywordsInsideCommentsAndLiteralsDoNotRejectBatch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ObjectNode response = fixture.batch("/* ROLLBACK */ SELECT 'SAVEPOINT s; COMMIT'", "SELECT 2");
            ObjectNode description = fixture.service.describeStatement(fixture.mapper.createObjectNode()
                    .put("Id", response.path("Id").asText()));
            assertEquals("FINISHED", description.path("Status").asText(), description.toString());
            assertEquals(2, fixture.records("useractivitylog").size());
        }
    }

    @Test
    void retainedSessionPublishesUserChangesOnCommitAndDiscardsThemOnRollback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String session = fixture.service.executeStatement(fixture.request("BEGIN")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            fixture.reuse(session, "CREATE USER alice PASSWORD 'secret'");
            assertTrue(fixture.records("userlog").isEmpty());
            fixture.reuse(session, "COMMIT");
            assertEquals(1, fixture.records("userlog").size(), fixture.events.toString());
            fixture.events.clear();
            fixture.reuse(session, "BEGIN");
            fixture.reuse(session, "CREATE USER bob PASSWORD 'secret'");
            fixture.reuse(session, "ROLLBACK");
            assertTrue(fixture.records("userlog").isEmpty());
            fixture.reuse(session, "BEGIN");
            fixture.reuse(session, "CREATE USER carol PASSWORD 'secret'");
            fixture.reuse(session, "SELECT * FROM missing");
            fixture.reuse(session, "COMMIT");
            assertTrue(fixture.records("userlog").isEmpty());
        }
    }

    @Test
    void retainedConnectionLogsAuthenticationOnceAndDisconnectionWhenRetired() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String session = fixture.service.executeStatement(fixture.request("SELECT 1")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            fixture.reuse(session, "SELECT 2");
            assertEquals(1, fixture.records("connectionlog").size(), fixture.events.toString());
            assertTrue(fixture.records("connectionlog").getFirst().startsWith("authenticated,"));
            fixture.service.clear();
            assertEquals(2, fixture.records("connectionlog").size());
            assertTrue(fixture.records("connectionlog").getLast().startsWith("disconnection,"));
        }
    }

    @Test
    void failedBackendAuthenticationProducesAConnectionRecord() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.factory.open(any())).thenThrow(new SQLException("bad password", "28P01"));
            fixture.execute("SELECT 1");
            assertEquals(1, fixture.records("connectionlog").size());
            assertTrue(fixture.records("connectionlog").getFirst().startsWith("authentication failure,"));
        }
    }

    @Test
    void activityAndConnectionRecordsUseRequestedIdentityInsteadOfBackendCredentials() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.execute("SELECT 1");
            assertTrue(fixture.records("useractivitylog").getFirst().contains(",analytics,analyst,"));
            assertTrue(fixture.records("connectionlog").stream().allMatch(line -> line.contains(",analytics,analyst,")));
        }
    }

    private record Event(String group, String message) { }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final List<Event> events = new CopyOnWriteArrayList<>();
        private final RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
        private final RedshiftDataExecutor executor = new RedshiftDataExecutor(2, 8, 1);
        private final RedshiftDataService service;
        private final Connection keeper;

        private Fixture() throws Exception {
            Driver.load();
            String url = "jdbc:h2:mem:" + UUID.randomUUID();
            keeper = DriverManager.getConnection(url);
            when(factory.open(any())).thenAnswer(invocation -> DriverManager.getConnection(url));
            Cluster cluster = new Cluster();
            cluster.setClusterIdentifier("warehouse");
            cluster.setLoggingEnabled(true);
            cluster.setLoggingDestinationType("cloudwatch");
            cluster.setUserActivityLoggingEnabled(true);
            RedshiftDataResourceResolver resolver = mock(RedshiftDataResourceResolver.class);
            when(resolver.resolve(any(), any())).thenReturn(new RedshiftDataResourceResolver.DatabaseTarget(
                    "arn:aws:redshift:us-east-1:000000000000:cluster:warehouse", "localhost", 5439,
                    "analytics", "admin", "password", null, cluster, "analyst"));
            CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
            doAnswer(invocation -> {
                List<Map<String, Object>> records = invocation.getArgument(3);
                for (Map<String, Object> record : records) {
                    events.add(new Event(invocation.getArgument(1), record.get("message").toString()));
                }
                return null;
            }).when(logs).putLogEventsForAccount(anyString(), anyString(), anyString(), anyList(), anyString());
            service = new RedshiftDataService(resolver, factory, new RedshiftDataStatementStore(24, Clock.systemUTC()),
                    mapper, null, executor, null, null, null,
                    new RedshiftDataIdempotencyStore(AccountAwareStorageBackend.inMemory("000000000000"), Clock.systemUTC()),
                    new RedshiftDataSessions(), new RedshiftAuditLogPublisher(logs));
        }

        private List<String> records(String type) {
            return events.stream().filter(event -> event.group().endsWith("/" + type)).map(Event::message).toList();
        }

        private List<String> loggedUsers() {
            return records("userlog").stream().map(line -> line.split(",", -1)[1]).toList();
        }

        private Connection mockSuccessfulBackend() throws SQLException {
            // H2 user DDL implicitly commits; model successful PostgreSQL responses for transaction audit tests.
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(factory.open(any())).thenReturn(connection);
            return connection;
        }

        private void assertFinished(ObjectNode response) {
            ObjectNode described = service.describeStatement(mapper.createObjectNode().put("Id", response.path("Id").asText()));
            assertEquals("FINISHED", described.path("Status").asText(), described.toString());
        }

        private ObjectNode request(String sql) {
            return mapper.createObjectNode().put("Sql", sql).put("WaitTimeSeconds", 5)
                    .put("ClusterIdentifier", "warehouse").put("Database", "analytics").put("DbUser", "analyst");
        }

        private void execute(String sql) {
            service.executeStatement(request(sql), "us-east-1");
        }

        private void reuse(String session, String sql) {
            service.executeStatement(mapper.createObjectNode().put("Sql", sql).put("SessionId", session)
                    .put("WaitTimeSeconds", 5), "us-east-1");
        }

        private ObjectNode batch(String... sqls) {
            ObjectNode request = request("unused");
            request.remove("Sql");
            ArrayNode statements = request.putArray("Sqls");
            for (String sql : sqls) {
                statements.add(sql);
            }
            return service.batchExecuteStatement(request, "us-east-1");
        }

        @Override
        public void close() throws SQLException {
            service.clear();
            executor.close();
            keeper.close();
        }
    }
}
