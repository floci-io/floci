package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.ManualClock;
import org.h2.Driver;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RedshiftDataListSessionsServiceTest {
    @Test
    void sessionTimestampsUseMillisecondPrecision() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.clock.advance(Duration.ofNanos(123_456_789));
            fixture.create("warehouse");
            JsonNode session = fixture.list().path("Sessions").get(0);

            assertEquals(1_793_232_000.123, session.path("CreatedAt").asDouble());
            assertEquals(1_793_232_000.123, session.path("UpdatedAt").asDouble());
            assertEquals(1_793_232_060.123, session.path("SessionTtl").asDouble());
        }
    }

    @Test
    void anotherCallersExpiredConnectionCleanupFailureDoesNotFailListing() throws Exception {
        try (Fixture fixture = new Fixture(); Connection broken = spy(DriverManager.getConnection(fixture.url))) {
            when(fixture.factory.open(any())).thenReturn(broken)
                    .thenAnswer(invocation -> DriverManager.getConnection(fixture.url));
            fixture.context.setAccessKeyId("other");
            fixture.create("broken");
            doThrow(new SQLException("close failed", "08006")).doCallRealMethod().when(broken).close();
            fixture.clock.advance(Duration.ofSeconds(30));
            fixture.context.setAccessKeyId("owner");
            String healthy = fixture.create("warehouse");
            fixture.clock.advance(Duration.ofSeconds(30));

            ObjectNode listed = fixture.list();

            assertEquals(1, listed.path("Sessions").size());
            assertEquals(healthy, listed.path("Sessions").get(0).path("SessionId").asText());
            verify(broken).close();
        }
    }

    @Test
    void listsAvailableAndClosedSessionsWithLifecycleMetadata() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String id = fixture.create("warehouse");
            JsonNode session = fixture.list().path("Sessions").get(0);
            assertEquals(id, session.path("SessionId").asText());
            assertEquals("AVAILABLE", session.path("Status").asText());
            assertEquals("warehouse", session.path("ClusterIdentifier").asText());
            assertEquals("dev", session.path("Database").asText());
            assertEquals("admin", session.path("DbUser").asText());
            assertEquals(60, session.path("SessionAliveSeconds").asInt());
            assertEquals(fixture.clock.instant().getEpochSecond(), session.path("CreatedAt").asLong());
            assertEquals(fixture.clock.instant().plusSeconds(60).getEpochSecond(), session.path("SessionTtl").asLong());
            assertFalse(session.has("CurrentStatementId"));
            assertFalse(session.has("WorkgroupName"));
            assertFalse(session.has("SecretArn"));
            assertFalse(session.toString().contains("password"));

            fixture.clock.advance(Duration.ofSeconds(10));
            fixture.service.executeStatement(fixture.json().put("SessionId", id).put("Sql", "SELECT 2")
                    .put("SessionKeepAliveSeconds", 0).put("WaitTimeSeconds", 5), "us-east-1");
            assertTrue(fixture.list().path("Sessions").isEmpty());
            JsonNode closed = fixture.list(fixture.json().put("Status", "CLOSED")).path("Sessions").get(0);
            assertEquals(id, closed.path("SessionId").asText());
            assertEquals("CLOSED", closed.path("Status").asText());
            assertEquals(fixture.clock.instant().getEpochSecond(), closed.path("UpdatedAt").asLong());
            assertEquals(id, fixture.list(fixture.json().put("SessionId", id))
                    .path("Sessions").get(0).path("SessionId").asText());
            fixture.service.clear();
            assertTrue(fixture.list(fixture.json().put("Status", "CLOSED")).path("Sessions").isEmpty());
        }
    }

    @Test
    void listsBusySessionsWithTheirCurrentStatement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(fixture.factory.open(any())).thenAnswer(invocation -> {
                started.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return DriverManager.getConnection(fixture.url);
            });
            ObjectNode request = fixture.request("warehouse");
            request.remove("WaitTimeSeconds");
            ObjectNode response = fixture.service.executeStatement(request, "us-east-1");
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                JsonNode busy = fixture.list(fixture.json().put("Status", "BUSY")).path("Sessions").get(0);
                assertEquals(response.path("Id").asText(), busy.path("CurrentStatementId").asText());
                assertEquals(response.path("SessionId").asText(), busy.path("SessionId").asText());
            } finally {
                release.countDown();
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals("AVAILABLE", fixture.list().path("Sessions").get(0).path("Status").asText()));
        }
    }

    @Test
    void expiresOnReadAndRetainsClosedHistoryOnlyWithinTheCreationWindow() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String id = fixture.create("warehouse");
            fixture.clock.advance(Duration.ofSeconds(60));
            assertTrue(fixture.list().path("Sessions").isEmpty());
            assertEquals("CLOSED", fixture.list(fixture.json().put("SessionId", id))
                    .path("Sessions").get(0).path("Status").asText());
            fixture.clock.advance(Duration.ofHours(24));
            assertTrue(fixture.list(fixture.json().put("Status", "CLOSED")).path("Sessions").isEmpty());
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> fixture.list(fixture.json().put("SessionId", id))).getErrorCode());
        }
    }

    @Test
    void filtersByComputeTargetAndDatabaseBeforePaginating() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String first = fixture.create("warehouse");
            fixture.clock.advance(Duration.ofSeconds(1));
            String second = fixture.create("warehouse");
            fixture.create("other");
            ObjectNode workgroup = fixture.request("unused");
            workgroup.remove("ClusterIdentifier");
            workgroup.put("WorkgroupName", "analytics");
            fixture.service.executeStatement(workgroup, "us-east-1");
            ObjectNode filter = fixture.json().put("ClusterIdentifier", "warehouse").put("Database", "dev")
                    .put("MaxResults", 1);
            ObjectNode page = fixture.list(filter);
            Set<String> ids = new HashSet<>();
            ids.add(page.path("Sessions").get(0).path("SessionId").asText());
            assertTrue(page.has("NextToken"));
            ObjectNode last = fixture.list(filter.deepCopy().put("NextToken", page.path("NextToken").asText()));
            ids.add(last.path("Sessions").get(0).path("SessionId").asText());
            assertEquals(Set.of(first, second), ids);
            assertFalse(last.has("NextToken"));
            assertTrue(fixture.list(fixture.json().put("Database", "missing")).path("Sessions").isEmpty());
            JsonNode serverless = fixture.list(fixture.json().put("WorkgroupName", "analytics")).path("Sessions");
            assertEquals(1, serverless.size());
            assertFalse(serverless.get(0).has("ClusterIdentifier"));
            String arn = AwsArnUtils.Arn.of("redshift-serverless", "us-east-1", "000000000000",
                    "workgroup/12345678-1234-1234-1234-123456789012").toString();
            assertEquals(serverless, fixture.list(fixture.json().put("WorkgroupName", arn)).path("Sessions"));
        }
    }

    @Test
    void enforcesCallerRegionAndRoleLevelAndBindsTokensToTheQuery() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.iam.resolveCallerArn("owner")).thenReturn(Optional.of("session-one"));
            when(fixture.iam.resolvePrincipalArn("owner")).thenReturn(Optional.of("shared-role"));
            String id = fixture.create("warehouse");
            fixture.create("warehouse");
            String token = fixture.list(fixture.json().put("MaxResults", 1)).path("NextToken").asText();
            when(fixture.iam.resolveCallerArn("other")).thenReturn(Optional.of("session-two"));
            when(fixture.iam.resolvePrincipalArn("other")).thenReturn(Optional.of("shared-role"));
            fixture.context.setAccessKeyId("other");
            assertEquals(2, fixture.list().path("Sessions").size());
            assertTrue(fixture.list(fixture.json().put("RoleLevel", false)).path("Sessions").isEmpty());
            when(fixture.iam.resolvePrincipalArn("other")).thenReturn(Optional.of("different-role"));
            assertTrue(fixture.list().path("Sessions").isEmpty());
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> fixture.list(fixture.json().put("SessionId", id))).getErrorCode());
            assertThrows(AwsException.class, () -> fixture.list(fixture.json().put("NextToken", token)));
            fixture.context.setAccessKeyId("owner");
            assertThrows(AwsException.class, () -> fixture.list(fixture.json().put("NextToken", token).put("Database", "dev")));
            assertTrue(fixture.list(fixture.json(), "eu-west-1").path("Sessions").isEmpty());
            assertThrows(AwsException.class, () -> fixture.list(fixture.json().put("NextToken", token), "eu-west-1"));
        }
    }

    @Test
    void workgroupArnSessionsCanBeListedByNameAndForeignAccountsStayHidden() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String arn = AwsArnUtils.Arn.of("redshift-serverless", "us-east-1", "000000000000",
                    "workgroup/12345678-1234-1234-1234-123456789012").toString();
            ObjectNode request = fixture.request("unused");
            request.remove("ClusterIdentifier");
            request.put("WorkgroupName", arn);
            String id = fixture.service.executeStatement(request, "us-east-1").path("SessionId").asText();
            assertEquals(id, fixture.list(fixture.json().put("WorkgroupName", "analytics"))
                    .path("Sessions").get(0).path("SessionId").asText());
            RedshiftDataSessions.Reservation foreign = fixture.sessions.reserve(new RedshiftDataSessions.Scope(
                    "111111111111", "us-east-1", "access-key:owner"), fixture.request("warehouse"),
                    new RedshiftDataResourceResolver.DatabaseTarget(AwsArnUtils.Arn.of("redshift", "us-east-1",
                            "111111111111", "cluster:warehouse").toString(), "localhost", 5439, "dev", "admin", "password"));
            foreign.admitted();
            assertEquals(1, fixture.list().path("Sessions").size());
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> fixture.list(fixture.json().put("SessionId", foreign.id()))).getErrorCode());
        }
    }

    @Test
    void rejectsMalformedRequestsWithoutOpeningDatabaseConnections() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (ObjectNode request : new ObjectNode[]{fixture.json().put("Status", "FAILED"),
                    fixture.json().put("RoleLevel", "false"), fixture.json().put("MaxResults", -1),
                    fixture.json().put("MaxResults", 101), fixture.json().put("MaxResults", 0.5),
                    fixture.json().put("SessionId", "invalid"), fixture.json().put("ClusterIdentifier", "Uppercase"),
                    fixture.json().put("WorkgroupName", "x"), fixture.json().put("Database", 5),
                    fixture.json().put("NextToken", "garbage"),
                    fixture.json().put("ClusterIdentifier", "warehouse").put("WorkgroupName", "analytics"),
                    fixture.json().put("SessionId", UUID.randomUUID().toString()).put("Status", "CLOSED")}) {
                assertEquals("ValidationException", assertThrows(AwsException.class,
                        () -> fixture.list(request), request.toString()).getErrorCode());
            }
            assertTrue(fixture.list(fixture.json().put("MaxResults", 0)).path("Sessions").isEmpty());
            verifyNoInteractions(fixture.factory);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final ManualClock clock = new ManualClock(Instant.parse("2026-10-29T00:00:00Z"));
        private final RequestContext context = new RequestContext();
        private final IamService iam = mock(IamService.class);
        private final RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
        private final RedshiftDataExecutor executor = new RedshiftDataExecutor(2, 8, 1);
        private final RedshiftDataSessions sessions = new RedshiftDataSessions(clock);
        private final String url = "jdbc:h2:mem:" + UUID.randomUUID();
        private final RedshiftDataService service;

        private Fixture() throws Exception {
            Driver.load();
            context.setAccessKeyId("owner");
            RedshiftDataResourceResolver resolver = mock(RedshiftDataResourceResolver.class);
            when(resolver.resolve(any(), any())).thenAnswer(invocation -> {
                JsonNode request = invocation.getArgument(0);
                String region = invocation.getArgument(1);
                boolean serverless = request.has("WorkgroupName");
                String resource = serverless ? "workgroup/12345678-1234-1234-1234-123456789012"
                        : "cluster:" + request.path("ClusterIdentifier").asText();
                return new RedshiftDataResourceResolver.DatabaseTarget(AwsArnUtils.Arn.of(
                        serverless ? "redshift-serverless" : "redshift", region, "000000000000", resource).toString(),
                        "localhost", 5439, "dev", "admin", "password", null, null, "admin", serverless ? "analytics" : null);
            });
            when(factory.open(any())).thenAnswer(invocation -> DriverManager.getConnection(url));
            service = new RedshiftDataService(resolver, factory, new RedshiftDataStatementStore(24, clock), mapper,
                    null, executor, null, context, iam, new RedshiftDataIdempotencyStore(
                    AccountAwareStorageBackend.inMemory("000000000000"), clock), sessions);
        }

        private ObjectNode json() {
            return mapper.createObjectNode();
        }

        private ObjectNode request(String cluster) {
            return json().put("ClusterIdentifier", cluster).put("Database", "dev").put("DbUser", "admin")
                    .put("Sql", "SELECT 1").put("SessionKeepAliveSeconds", 60).put("WaitTimeSeconds", 5);
        }

        private String create(String cluster) {
            return service.executeStatement(request(cluster), "us-east-1").path("SessionId").asText();
        }

        private ObjectNode list() {
            return list(json());
        }

        private ObjectNode list(ObjectNode request) {
            return list(request, "us-east-1");
        }

        private ObjectNode list(ObjectNode request, String region) {
            return (ObjectNode) new RedshiftDataJsonHandler(service).handle("ListSessions", request, region).getEntity();
        }

        @Override
        public void close() {
            service.clear();
            executor.close();
            sessions.close();
        }
    }

}
