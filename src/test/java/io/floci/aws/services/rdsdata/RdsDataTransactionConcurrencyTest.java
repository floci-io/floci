package io.floci.aws.services.rdsdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.services.rds.container.RdsBackendGate;
import io.floci.aws.services.rds.model.DatabaseEngine;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.secretsmanager.model.SecretVersion;
import io.floci.aws.testing.ManualClock;
import org.h2.Driver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.invocation.InvocationOnMock;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class RdsDataTransactionConcurrencyTest {
    private static final String REGION = "us-east-1";
    private static final String RESOURCE_ARN = "arn:aws:rds:us-east-1:000000000000:cluster:test";
    private static final String BLOCKED_QUERY = "select 1";
    private static final String BLOCKED_BATCH = "insert into items(id) values (1)";

    private final ObjectMapper mapper = new ObjectMapper();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ManualClock clock = new ManualClock(Instant.EPOCH);
    private String jdbcUrl;
    private RdsDataService service;

    @BeforeEach
    void setUp() throws Exception {
        Driver.load();
        jdbcUrl = "jdbc:h2:mem:busy_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("create table items(id integer)");
        }
        service = createService(Duration.ofSeconds(60));
    }

    private RdsDataService createService(Duration ttl) throws SQLException {
        RdsDataResourceResolver resolver = mock(RdsDataResourceResolver.class);
        when(resolver.resolve(RESOURCE_ARN, REGION)).thenReturn(new RdsDataResourceResolver.DatabaseTarget(
                RESOURCE_ARN, RESOURCE_ARN, DatabaseEngine.POSTGRES, "sa", "", "app"));
        SecretsManagerService secrets = mock(SecretsManagerService.class);
        SecretVersion version = new SecretVersion();
        version.setSecretString("{\"username\":\"sa\",\"password\":\"\"}");
        when(secrets.getSecretValue(any(), any(), any(), any())).thenReturn(version);
        RdsDataConnectionFactory factory = mock(RdsDataConnectionFactory.class);
        when(factory.open(any(), any(), any(), any(), any())).thenAnswer(invocation -> connection());
        return new RdsDataService(resolver, secrets, mapper, factory, ttl, RdsBackendGate.OPEN,
                (runtimeId, engine) -> new EndpointInfo("127.0.0.1", 5432), false, clock);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        if (service != null) {
            service.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void rejectsConcurrentRequestsWithoutWaiting(Operation operation) throws Exception {
        String tx = begin();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ObjectNode> first = executor.submit(() -> service.executeStatement(request(tx, BLOCKED_QUERY), REGION));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                Future<AwsException> second = executor.submit(() -> assertThrows(AwsException.class,
                        () -> perform(operation, tx)));
                AwsException error = second.get(5, TimeUnit.SECONDS);
                assertEquals("DatabaseErrorException", error.getErrorCode());
                assertEquals("Transaction is still running a query", error.getMessage());
                assertEquals(400, error.getHttpStatus());

                String independent = begin();
                assertEquals(2, query(independent));
                service.rollbackTransaction(request(independent, null), REGION);
            } finally {
                release.countDown();
            }
            assertEquals(1, first.get(5, TimeUnit.SECONDS).path("records").path(0).path(0).path("longValue").asInt());
        }
        assertEquals(2, query(tx));
        service.commitTransaction(request(tx, null), REGION);
    }

    @Test
    void anInFlightBatchAlsoRejectsConcurrentStatements() throws Exception {
        String tx = begin();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ObjectNode> first = executor.submit(() -> service.batchExecuteStatement(batch(tx, BLOCKED_BATCH), REGION));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                Future<AwsException> second = executor.submit(() -> assertThrows(AwsException.class, () -> query(tx)));
                assertEquals("Transaction is still running a query", second.get(5, TimeUnit.SECONDS).getMessage());
            } finally {
                release.countDown();
            }
            assertEquals(1, first.get(5, TimeUnit.SECONDS).path("updateResults").size());
        }
        assertEquals(2, query(tx));
        service.rollbackTransaction(request(tx, null), REGION);
    }

    @Test
    void aFailedStatementReleasesTheTransaction() {
        String tx = begin();
        AwsException error = assertThrows(AwsException.class,
                () -> service.executeStatement(request(tx, "select * from missing_table"), REGION));
        assertEquals("DatabaseErrorException", error.getErrorCode());
        assertEquals(2, query(tx));
        service.rollbackTransaction(request(tx, null), REGION);
    }

    @Test
    void cleanupSkipsActiveQueriesAndTheIdleTimeoutStartsWhenTheyFinish() throws Exception {
        service.shutdown();
        service = createService(Duration.ofSeconds(1));
        String tx = begin();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ObjectNode> first = executor.submit(() -> service.executeStatement(request(tx, BLOCKED_QUERY), REGION));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                clock.advance(Duration.ofSeconds(2));
                Future<String> independent = executor.submit(this::begin);
                String other = independent.get(5, TimeUnit.SECONDS);
                service.rollbackTransaction(request(other, null), REGION);
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
        assertEquals(2, query(tx));
        clock.advance(Duration.ofSeconds(2));
        assertEquals("TransactionNotFoundException",
                assertThrows(AwsException.class, () -> query(tx)).getErrorCode());
    }

    @Test
    void busyTransactionsStillRejectRequestsForAnotherRegion() throws Exception {
        String tx = begin();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<ObjectNode> first = executor.submit(() -> service.executeStatement(request(tx, BLOCKED_QUERY), REGION));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                Future<AwsException> invalid = executor.submit(() -> assertThrows(AwsException.class,
                        () -> service.executeStatement(request(tx, "select 2"), "us-west-2")));
                assertEquals("TransactionNotFoundException", invalid.get(5, TimeUnit.SECONDS).getErrorCode());
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
        }
    }

    private Connection connection() throws SQLException {
        Connection connection = spy(DriverManager.getConnection(jdbcUrl, "sa", ""));
        doAnswer(invocation -> {
            PreparedStatement statement = spy((PreparedStatement) invocation.callRealMethod());
            String sql = invocation.getArgument(0);
            if (BLOCKED_QUERY.equals(sql)) {
                doAnswer(this::awaitQuery).when(statement).execute();
            } else if (BLOCKED_BATCH.equals(sql)) {
                doAnswer(this::awaitQuery).when(statement).executeBatch();
            }
            return statement;
        }).when(connection).prepareStatement(anyString());
        return connection;
    }

    private Object awaitQuery(InvocationOnMock invocation) throws Throwable {
        started.countDown();
        if (!release.await(15, TimeUnit.SECONDS)) {
            throw new SQLException("Timed out waiting for the test to release the query");
        }
        return invocation.callRealMethod();
    }

    private String begin() {
        return service.beginTransaction(request(null, null), REGION).path("transactionId").asText();
    }

    private int query(String tx) {
        return service.executeStatement(request(tx, "select 2"), REGION)
                .path("records").path(0).path(0).path("longValue").asInt();
    }

    private ObjectNode request(String tx, String sql) {
        ObjectNode request = mapper.createObjectNode().put("resourceArn", RESOURCE_ARN)
                .put("secretArn", "arn:aws:secretsmanager:us-east-1:000000000000:secret:test")
                .put("database", "app");
        if (tx != null) {
            request.put("transactionId", tx);
        }
        if (sql != null) {
            request.put("sql", sql);
        }
        return request;
    }

    private ObjectNode batch(String tx, String sql) {
        return request(tx, sql).set("parameterSets", mapper.createArrayNode().add(mapper.createArrayNode()));
    }

    private ObjectNode perform(Operation operation, String tx) {
        return switch (operation) {
            case EXECUTE -> service.executeStatement(request(tx, "select 2"), REGION);
            case BATCH -> service.batchExecuteStatement(batch(tx, "delete from items"), REGION);
            case COMMIT -> service.commitTransaction(request(tx, null), REGION);
            case ROLLBACK -> service.rollbackTransaction(request(tx, null), REGION);
        };
    }

    private enum Operation {
        EXECUTE, BATCH, COMMIT, ROLLBACK
    }
}
