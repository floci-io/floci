package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.h2.Driver;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RedshiftDataAsyncServiceTest {

    @Test
    void cancellationDuringBindingDoesNotStartJdbcQuery() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RedshiftDataResourceResolver resolver = mock(RedshiftDataResourceResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(new RedshiftDataResourceResolver.DatabaseTarget(
                "arn:aws:redshift:us-east-1:000000000000:cluster:wh", "localhost", 5439, "dev", "admin", "password"));
        RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(factory.open(any())).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        CountDownLatch binding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            binding.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(statement).setString(eq(1), eq("20"));
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 1, 1)) {
            RedshiftDataService service = new RedshiftDataService(resolver, factory,
                    new RedshiftDataStatementStore(24, Clock.systemUTC()), mapper, null, executor, null);
            ObjectNode request = mapper.createObjectNode().put("Sql", "SELECT pg_sleep(:seconds)");
            request.putArray("Parameters").addObject().put("name", "seconds").put("value", "20");
            String id = service.executeStatement(request, "us-east-1").path("Id").asText();
            assertTrue(binding.await(5, TimeUnit.SECONDS));
            try {
                assertTrue(service.cancelStatement(mapper.createObjectNode().put("Id", id)).path("Status").asBoolean());
            } finally {
                release.countDown();
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals("ABORTED",
                    service.describeStatement(mapper.createObjectNode().put("Id", id)).path("Status").asText()));
            verify(statement, never()).execute();
            verify(connection, never()).commit();
            verify(connection).rollback();
        }
    }

    @Test
    void batchEmitsOnlyParentEventAndFalseOrOmittedWithEventEmitsNone() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RedshiftDataResourceResolver resolver = mock(RedshiftDataResourceResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(new RedshiftDataResourceResolver.DatabaseTarget(
                "arn:aws:redshift:us-east-1:000000000000:cluster:wh", "localhost", 5439, "dev", "admin", "password"));
        RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
        Driver.load();
        String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        when(factory.open(any())).thenAnswer(invocation -> DriverManager.getConnection(jdbcUrl));
        RedshiftDataEventPublisher publisher = mock(RedshiftDataEventPublisher.class);
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 4, 1)) {
            RedshiftDataStatementStore store = new RedshiftDataStatementStore(24, Clock.systemUTC());
            RedshiftDataService service = new RedshiftDataService(resolver, factory, store, mapper, null, executor, publisher);
            ObjectNode request = mapper.createObjectNode().put("WithEvent", true).put("WaitTimeSeconds", 5);
            request.putArray("Sqls").add("SELECT 1").add("SELECT 2");
            String id = service.batchExecuteStatement(request, "us-east-1").path("Id").asText();
            assertEquals(RedshiftDataStatementStore.Status.FINISHED, store.getForAccount("000000000000", id).status,
                    () -> store.getForAccount("000000000000", id).error);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(publisher).publish(argThat(statement ->
                    id.equals(statement.id) && statement.status == RedshiftDataStatementStore.Status.FINISHED)));
            assertEquals(RedshiftDataStatementStore.Status.FINISHED, store.getForAccount("000000000000", id).status);
            service.executeStatement(mapper.createObjectNode().put("Sql", "SELECT 3").put("WaitTimeSeconds", 5), "us-east-1");
            service.executeStatement(mapper.createObjectNode().put("Sql", "SELECT 4").put("WaitTimeSeconds", 5)
                    .put("WithEvent", false), "us-east-1");
            executor.close();
            verify(publisher, times(1)).publish(any());
        }
    }

    @Test
    void queuedCancellationPublishesOneAbortedEventAfterTerminalStorage() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RedshiftDataResourceResolver resolver = mock(RedshiftDataResourceResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(new RedshiftDataResourceResolver.DatabaseTarget(
                "arn:aws-cn:redshift:cn-north-1:111111111111:cluster:wh", "localhost", 5439, "dev", "admin", "password"));
        RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
        RedshiftDataEventPublisher publisher = mock(RedshiftDataEventPublisher.class);
        RedshiftDataStatementStore store = new RedshiftDataStatementStore(24, Clock.systemUTC());
        AtomicReference<RedshiftDataStatementStore.StoredStatement> delivered = new AtomicReference<>();
        doAnswer(invocation -> {
            RedshiftDataStatementStore.StoredStatement statement = invocation.getArgument(0);
            assertEquals(RedshiftDataStatementStore.Status.ABORTED,
                    store.getForAccount(statement.accountId, statement.id).status);
            delivered.set(statement);
            return null;
        }).when(publisher).publish(any());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 2, 1)) {
            executor.submit("111111111111", "cn-north-1", "blocking", execution -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            RedshiftDataService service = new RedshiftDataService(resolver, factory, store, mapper, null, executor, publisher);
            String id = service.executeStatement(mapper.createObjectNode().put("Sql", "SELECT 1")
                    .put("WithEvent", true).put("StatementName", "etl"), "cn-north-1", "role-principal").path("Id").asText();
            try {
                assertTrue(executor.cancel("111111111111", id));
                await().atMost(Duration.ofSeconds(5)).until(() -> delivered.get() != null);
                assertEquals("111111111111", delivered.get().accountId);
                assertEquals("cn-north-1", delivered.get().region);
                assertEquals("role-principal", delivered.get().principal);
                assertEquals("etl", delivered.get().statementName);
                assertEquals(RedshiftDataStatementStore.Status.ABORTED, delivered.get().status);
                verifyNoInteractions(factory);
                assertFalse(executor.cancel("111111111111", id));
                verify(publisher, times(1)).publish(any());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void returnsIdBeforeDatabaseConnectionCompletes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RedshiftDataResourceResolver resolver = mock(RedshiftDataResourceResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(new RedshiftDataResourceResolver.DatabaseTarget(
                "arn:aws:redshift:us-east-1:000000000000:cluster:wh", "localhost", 5439, "dev", "admin", "password"));
        RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(factory.open(any())).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            throw new SQLException("connection refused");
        });
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 1, 1)) {
            RedshiftDataService service = new RedshiftDataService(resolver, factory,
                    new RedshiftDataStatementStore(24, Clock.systemUTC()), mapper, null, executor, null);
            ObjectNode request = mapper.createObjectNode().put("Sql", "select 1").put("ClusterIdentifier", "wh");
            ObjectNode response = service.executeStatement(request, "us-east-1");
            assertTrue(started.await(5, TimeUnit.SECONDS));
            ObjectNode id = mapper.createObjectNode().put("Id", response.path("Id").asText());
            assertEquals("STARTED", service.describeStatement(id).path("Status").asText());
            assertThrows(AwsException.class, () -> service.getStatementResult(id));
            release.countDown();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals("FAILED", service.describeStatement(id).path("Status").asText()));
            assertFalse(service.cancelStatement(id).path("Status").asBoolean());
        }
    }

    @Test
    void validatesWaitTimeBeforeAcceptingWork() {
        ObjectMapper mapper = new ObjectMapper();
        RedshiftDataResourceResolver resolver = mock(RedshiftDataResourceResolver.class);
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 1, 1)) {
            RedshiftDataService service = new RedshiftDataService(resolver, mock(RedshiftDataConnectionFactory.class),
                    new RedshiftDataStatementStore(24, Clock.systemUTC()), mapper, null, executor, null);
            ObjectNode request = mapper.createObjectNode().put("Sql", "select 1").put("WaitTimeSeconds", 31);
            assertThrows(AwsException.class, () -> service.executeStatement(request, "us-east-1"));
            verifyNoInteractions(resolver);
        }
    }
}
