package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RedshiftDataIdempotencyServiceTest {

    @Test
    void slowReceiptWriteDoesNotBlockOtherTokensOrTokenlessRequests() throws Exception {
        try (Fixture fixture = new Fixture(); ExecutorService callers = Executors.newFixedThreadPool(3)) {
            CountDownLatch writing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                RedshiftDataIdempotencyStore.Scope scope = invocation.getArgument(0);
                if (scope.token().equals("slow")) {
                    writing.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }
                return invocation.callRealMethod();
            }).when(fixture.receipts).put(any(), any());
            Future<ObjectNode> slow = callers.submit(() -> fixture.service.executeStatement(
                    fixture.single("slow", "SELECT 1"), "us-east-1"));
            try {
                assertTrue(writing.await(5, TimeUnit.SECONDS));
                Future<ObjectNode> independent = callers.submit(() -> fixture.service.executeStatement(
                        fixture.single("independent", "SELECT 1"), "us-east-1"));
                Future<ObjectNode> tokenless = callers.submit(() -> {
                    ObjectNode request = fixture.single("unused", "SELECT 1");
                    request.remove("ClientToken");
                    return fixture.service.executeStatement(request, "us-east-1");
                });
                assertFalse(independent.get(2, TimeUnit.SECONDS).path("Id").asText().isEmpty());
                assertFalse(tokenless.get(2, TimeUnit.SECONDS).path("Id").asText().isEmpty());
            } finally {
                release.countDown();
            }
            slow.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectedQueueCannotLeaveAReceiptAfterCleanupFailureAndRestart(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path receiptFile = directory.resolve("idempotency.json");
            RedshiftDataIdempotencyStore receipts = spy(persistentReceipts(receiptFile, fixture.clock));
            RedshiftDataService service = fixture.service(fixture.statements, receipts);
            doThrow(new IllegalStateException("receipt deletion unavailable")).when(receipts).removeIfStatement(any(), any());
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            try {
                for (int i = 0; i < 2; i++) {
                    fixture.executor.submit("000000000000", "us-east-1", "running-" + i, execution -> {
                        started.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
                    });
                }
                assertTrue(started.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 16; i++) {
                    fixture.executor.submit("000000000000", "us-east-1", "queued-" + i, execution -> { });
                }
                assertThrows(AwsException.class, () -> service.executeStatement(
                        fixture.single("rejected-persistent", "INSERT INTO mutations VALUES (1)"), "us-east-1"));
                RedshiftDataIdempotencyStore reopened = persistentReceipts(receiptFile, fixture.clock);
                assertNull(reopened.find(new RedshiftDataIdempotencyStore.Scope("000000000000", "us-east-1",
                        "ExecuteStatement", "rejected-persistent")));
                verifyNoInteractions(fixture.factory);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void executeRetryReturnsOriginalIdentityAndMutatesOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ObjectNode request = fixture.single("insert-token", "INSERT INTO mutations VALUES (1)");
            ObjectNode first = fixture.service.executeStatement(request, "us-east-1");
            ObjectNode retry = fixture.service.executeStatement(request, "us-east-1");
            assertEquals(first.path("Id"), retry.path("Id"));
            assertEquals(first.path("CreatedAt"), retry.path("CreatedAt"));
            assertEquals(1, fixture.count());
            verify(fixture.factory, times(1)).open(any());
        }
    }

    @Test
    void batchRetryMutatesOnceAndPublishesOneParentEvent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ObjectNode request = fixture.mapper.createObjectNode().put("ClientToken", "batch-token")
                    .put("WaitTimeSeconds", 5).put("WithEvent", true);
            request.putArray("Sqls").add("INSERT INTO mutations VALUES (1)")
                    .add("INSERT INTO mutations VALUES (2)");
            ObjectNode first = fixture.service.batchExecuteStatement(request, "us-east-1");
            ObjectNode retry = fixture.service.batchExecuteStatement(request, "us-east-1");
            assertEquals(first.path("Id"), retry.path("Id"));
            assertEquals(2, fixture.count());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    verify(fixture.publisher, times(1)).publish(any()));
            verify(fixture.factory, times(1)).open(any());
        }
    }

    @Test
    void absentDifferentAndDifferentlyCasedTokensAreIndependent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            List<String> ids = new ArrayList<>();
            for (String token : List.of("one", "One", "two", "", "")) {
                ObjectNode request = fixture.single(token, "INSERT INTO mutations VALUES (1)");
                if (token.isEmpty()) {
                    request.remove("ClientToken");
                }
                ids.add(fixture.service.executeStatement(request, "us-east-1").path("Id").asText());
            }
            assertEquals(5, ids.stream().distinct().count());
            assertEquals(5, fixture.count());
        }
    }

    @Test
    void suppliedMalformedTokensAreRejectedBeforeAdmission() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (String token : List.of("", "x".repeat(65))) {
                AwsException failure = assertThrows(AwsException.class, () -> fixture.service.executeStatement(
                        fixture.single(token, "SELECT 1"), "us-east-1"));
                assertEquals("ValidationException", failure.getErrorCode());
            }
            ObjectNode numeric = fixture.single("token", "SELECT 1").put("ClientToken", 123);
            assertThrows(AwsException.class, () -> fixture.service.batchExecuteStatement(numeric, "us-east-1"));
            ObjectNode nullToken = fixture.single("token", "SELECT 1").putNull("ClientToken");
            assertThrows(AwsException.class, () -> fixture.service.executeStatement(nullToken, "us-east-1"));
            verifyNoInteractions(fixture.factory);
        }
    }

    @Test
    void concurrentRetriesOfRunningWorkAdmitOneStatement() throws Exception {
        try (Fixture fixture = new Fixture(); ExecutorService clients = Executors.newFixedThreadPool(8)) {
            CountDownLatch opened = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(fixture.factory.open(any())).thenAnswer(invocation -> {
                opened.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return DriverManager.getConnection(fixture.jdbcUrl);
            });
            ObjectNode request = fixture.single("parallel", "INSERT INTO mutations VALUES (1)").put("WithEvent", true);
            request.remove("WaitTimeSeconds");
            try {
                List<Future<String>> replies = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    replies.add(clients.submit(() -> fixture.service.executeStatement(request, "us-east-1")
                            .path("Id").asText()));
                }
                assertTrue(opened.await(5, TimeUnit.SECONDS));
                List<String> ids = new ArrayList<>();
                for (Future<String> reply : replies) {
                    ids.add(reply.get(5, TimeUnit.SECONDS));
                }
                assertEquals(1, ids.stream().distinct().count());
            } finally {
                release.countDown();
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(1, fixture.count()));
            verify(fixture.factory, times(1)).open(any());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(fixture.publisher, times(1)).publish(any()));
        }
    }

    @Test
    void failedStatementReplaysUntilReset() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ObjectNode request = fixture.single("failed", "SELECT * FROM missing_table");
            String id = fixture.service.executeStatement(request, "us-east-1").path("Id").asText();
            assertEquals(RedshiftDataStatementStore.Status.FAILED, fixture.statements.get(id).status);
            assertEquals(id, fixture.service.executeStatement(request, "us-east-1").path("Id").asText());
            fixture.service.clear();
            assertNotEquals(id, fixture.service.executeStatement(request, "us-east-1").path("Id").asText());
            verify(fixture.factory, times(2)).open(any());
        }
    }

    @Test
    void tokenScopeSeparatesRegionsAndExecuteOperations() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String first = fixture.service.executeStatement(fixture.single("shared", "SELECT 1"), "us-east-1")
                    .path("Id").asText();
            String otherRegion = fixture.service.executeStatement(fixture.single("shared", "SELECT 1"), "eu-west-1")
                    .path("Id").asText();
            ObjectNode batch = fixture.mapper.createObjectNode().put("ClientToken", "shared").put("WaitTimeSeconds", 5);
            batch.putArray("Sqls").add("SELECT 1");
            String otherOperation = fixture.service.batchExecuteStatement(batch, "us-east-1").path("Id").asText();
            assertEquals(3, List.of(first, otherRegion, otherOperation).stream().distinct().count());
        }
    }

    @Test
    void sameTokenReplaysOriginalPayloadAndDoesNotChangeEventPreference() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ObjectNode first = fixture.service.executeStatement(
                    fixture.single("reused", "INSERT INTO mutations VALUES (1)"), "us-east-1");
            ObjectNode retry = fixture.service.executeStatement(
                    fixture.single("reused", "INSERT INTO mutations VALUES (2)").put("WithEvent", true), "us-east-1");
            assertEquals(first.path("Id"), retry.path("Id"));
            assertEquals(1, fixture.count());
            verifyNoInteractions(fixture.publisher);
        }
    }

    @Test
    void replayStillValidatesDatabaseAccess() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ObjectNode request = fixture.single("access", "INSERT INTO mutations VALUES (1)");
            fixture.service.executeStatement(request, "us-east-1");
            when(fixture.resolver.resolve(any(), any())).thenThrow(new AwsException("ValidationException", "Access revoked", 400));
            assertThrows(AwsException.class, () -> fixture.service.executeStatement(request, "us-east-1"));
            verify(fixture.factory, times(1)).open(any());
        }
    }

    @Test
    void expiredTokenAdmitsNewWorkAndShortResultTtlDoesNotLoseTheReceipt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ObjectNode request = fixture.single("retention", "INSERT INTO mutations VALUES (1)");
            ObjectNode first = fixture.service.executeStatement(request, "us-east-1");
            fixture.clock.advance(Duration.ofHours(2));
            fixture.statements.sweep();
            assertNull(fixture.statements.get(first.path("Id").asText()));
            assertEquals(first, fixture.service.executeStatement(request, "us-east-1"));
            assertEquals(1, fixture.count());
            fixture.clock.advance(Duration.ofHours(6));
            assertNotEquals(first.path("Id"), fixture.service.executeStatement(request, "us-east-1").path("Id"));
            assertEquals(2, fixture.count());
        }
    }

    @Test
    void rejectedAdmissionAndStorageFailuresLeaveTokenReusable() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ObjectNode request = fixture.single("retry-error", "INSERT INTO mutations VALUES (1)");
            doThrow(new IllegalStateException("receipt write failed")).doCallRealMethod()
                    .when(fixture.receipts).put(any(), any());
            assertThrows(IllegalStateException.class, () -> fixture.service.executeStatement(request, "us-east-1"));
            assertTrue(fixture.statements.values().isEmpty());
            verifyNoInteractions(fixture.factory);
            fixture.service.executeStatement(request, "us-east-1");
            assertEquals(1, fixture.count());
        }
    }

    @Test
    void statementWriteFailureDoesNotConsumeToken() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ObjectNode request = fixture.single("statement-write", "INSERT INTO mutations VALUES (1)");
            doThrow(new IllegalStateException("statement write failed")).doCallRealMethod()
                    .when(fixture.statements).admitForAccount(any(), any());
            assertThrows(IllegalStateException.class, () -> fixture.service.executeStatement(request, "us-east-1"));
            verifyNoInteractions(fixture.factory);
            fixture.service.executeStatement(request, "us-east-1");
            assertEquals(1, fixture.count());
        }
    }

    @Test
    void queueFullReplaysAcceptedTokenAndRejectedTokenCanBeRetried() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.service.executeStatement(fixture.single("accepted", "INSERT INTO mutations VALUES (1)"), "us-east-1");
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            try {
                for (int i = 0; i < 2; i++) {
                    fixture.executor.submit("000000000000", "us-east-1", "blocker-" + i, execution -> {
                        started.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
                    });
                }
                assertTrue(started.await(5, TimeUnit.SECONDS));
                ObjectNode queued = fixture.single("queued", "INSERT INTO mutations VALUES (2)");
                queued.remove("WaitTimeSeconds");
                for (int i = 0; i < 16; i++) {
                    fixture.service.executeStatement(queued.deepCopy().put("ClientToken", "queued-" + i), "us-east-1");
                }
                ObjectNode rejected = fixture.single("rejected", "INSERT INTO mutations VALUES (3)");
                rejected.remove("WaitTimeSeconds");
                AwsException failure = assertThrows(AwsException.class,
                        () -> fixture.service.executeStatement(rejected, "us-east-1"));
                assertEquals("ActiveStatementsExceededException", failure.getErrorCode());
                fixture.service.executeStatement(fixture.single("accepted", "INSERT INTO mutations VALUES (1)"), "us-east-1");
            } finally {
                release.countDown();
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(17, fixture.count()));
            fixture.service.executeStatement(fixture.single("rejected", "INSERT INTO mutations VALUES (3)"), "us-east-1");
            assertEquals(18, fixture.count());
        }
    }

    @Test
    void restartRetainsReceiptAndFailsInterruptedWorkWithoutReexecutingSql(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path receiptFile = directory.resolve("idempotency.json");
            Path statementFile = directory.resolve("statements.json");
            RedshiftDataIdempotencyStore originalReceipts = persistentReceipts(receiptFile, fixture.clock);
            RedshiftDataStatementStore originalStatements = persistentStatements(statementFile, originalReceipts);
            RedshiftDataService firstService = fixture.service(originalStatements, originalReceipts);
            ObjectNode request = fixture.single("restart", "INSERT INTO mutations VALUES (1)");
            String id = firstService.executeStatement(request, "us-east-1").path("Id").asText();
            RedshiftDataStatementStore.StoredStatement interrupted = originalStatements.get(id);
            interrupted.status = RedshiftDataStatementStore.Status.STARTED;
            originalStatements.put(interrupted);
            originalStatements.flush();

            RedshiftDataIdempotencyStore reopenedReceipts = persistentReceipts(receiptFile, fixture.clock);
            RedshiftDataStatementStore reopenedStatements = persistentStatements(statementFile, reopenedReceipts);
            reopenedStatements.start();
            try {
                assertEquals(RedshiftDataStatementStore.Status.FAILED, reopenedStatements.get(id).status);
                RedshiftDataService reopenedService = fixture.service(reopenedStatements, reopenedReceipts);
                assertEquals(id, reopenedService.executeStatement(request, "us-east-1").path("Id").asText());
                assertEquals(1, fixture.count());
                verify(fixture.factory, times(1)).open(any());
                verifyNoInteractions(fixture.publisher);
            } finally {
                reopenedStatements.stop();
            }
        }
    }

    @Test
    void persistentStatementWriteFailureCannotAdmitSqlOrPublishAReceipt(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path statementFile = directory.resolve("statements.json");
            RedshiftDataStatementStore statements = persistentStatements(statementFile, fixture.receipts);
            Files.createDirectory(statementFile);
            Files.writeString(statementFile.resolve("blocked"), "cannot replace a nonempty directory");
            RedshiftDataService service = fixture.service(statements, fixture.receipts);
            assertThrows(UncheckedIOException.class,
                    () -> service.executeStatement(fixture.single("disk-failure", "INSERT INTO mutations VALUES (1)"), "us-east-1"));
            verifyNoInteractions(fixture.factory);
            verify(fixture.receipts, never()).put(any(), any());
            assertTrue(statements.values().isEmpty());
        }
    }

    @Test
    void resetWaitsForAdmissionAndClearsItsReceipt() throws Exception {
        try (Fixture fixture = new Fixture(); ExecutorService callers = Executors.newFixedThreadPool(2)) {
            CountDownLatch writing = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                writing.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return invocation.callRealMethod();
            }).when(fixture.receipts).put(any(), any());
            ObjectNode request = fixture.single("reset-race", "INSERT INTO mutations VALUES (1)");
            request.remove("WaitTimeSeconds");
            Future<ObjectNode> admitted = callers.submit(() -> fixture.service.executeStatement(request, "us-east-1"));
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Future<?> reset = callers.submit(fixture.service::clear);
            try {
                assertThrows(TimeoutException.class, () -> reset.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            String originalId = admitted.get(5, TimeUnit.SECONDS).path("Id").asText();
            reset.get(5, TimeUnit.SECONDS);
            assertTrue(fixture.statements.values().isEmpty());
            assertNotEquals(originalId, fixture.service.executeStatement(request, "us-east-1").path("Id").asText());
        }
    }

    @Test
    void cancelledQueuedStatementReplaysWithoutOpeningAConnection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            try {
                for (int i = 0; i < 2; i++) {
                    fixture.executor.submit("000000000000", "us-east-1", "blocker-" + i, execution -> {
                        started.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
                    });
                }
                assertTrue(started.await(5, TimeUnit.SECONDS));
                ObjectNode request = fixture.single("cancelled", "INSERT INTO mutations VALUES (1)");
                request.remove("WaitTimeSeconds");
                String id = fixture.service.executeStatement(request, "us-east-1").path("Id").asText();
                assertTrue(fixture.service.cancelStatement(fixture.mapper.createObjectNode().put("Id", id))
                        .path("Status").asBoolean());
                assertEquals(RedshiftDataStatementStore.Status.ABORTED, fixture.statements.get(id).status);
                assertEquals(id, fixture.service.executeStatement(request, "us-east-1").path("Id").asText());
                verifyNoInteractions(fixture.factory);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void expiryDuringExecutionDoesNotLetOldCompletionReplaceTheNewReceipt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            CountDownLatch opened = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger connections = new AtomicInteger();
            when(fixture.factory.open(any())).thenAnswer(invocation -> {
                if (connections.getAndIncrement() == 0) {
                    opened.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }
                return DriverManager.getConnection(fixture.jdbcUrl);
            });
            ObjectNode request = fixture.single("expiry-active", "INSERT INTO mutations VALUES (1)");
            request.remove("WaitTimeSeconds");
            String original = fixture.service.executeStatement(request, "us-east-1").path("Id").asText();
            assertTrue(opened.await(5, TimeUnit.SECONDS));
            String replacement;
            try {
                fixture.clock.advance(Duration.ofHours(8));
                replacement = fixture.service.executeStatement(request.deepCopy().put("WaitTimeSeconds", 5), "us-east-1")
                        .path("Id").asText();
                assertNotEquals(original, replacement);
            } finally {
                release.countDown();
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(2, fixture.count()));
            assertEquals(replacement, fixture.service.executeStatement(request, "us-east-1").path("Id").asText());
            verify(fixture.factory, times(2)).open(any());
        }
    }

    @Test
    void waitingRetryDoesNotBlockAnotherTokensAdmission() throws Exception {
        try (Fixture fixture = new Fixture(); ExecutorService callers = Executors.newFixedThreadPool(2)) {
            CountDownLatch opened = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch retryLookup = new CountDownLatch(1);
            AtomicInteger connections = new AtomicInteger();
            when(fixture.factory.open(any())).thenAnswer(invocation -> {
                if (connections.getAndIncrement() == 0) {
                    opened.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                }
                return DriverManager.getConnection(fixture.jdbcUrl);
            });
            ObjectNode request = fixture.single("waiting", "INSERT INTO mutations VALUES (1)");
            request.remove("WaitTimeSeconds");
            String first = fixture.service.executeStatement(request, "us-east-1").path("Id").asText();
            assertTrue(opened.await(5, TimeUnit.SECONDS));
            doAnswer(invocation -> {
                retryLookup.countDown();
                return invocation.callRealMethod();
            }).when(fixture.receipts).find(any());
            Future<ObjectNode> retry = callers.submit(() -> fixture.service.executeStatement(
                    request.deepCopy().put("WaitTimeSeconds", 5), "us-east-1"));
            try {
                assertTrue(retryLookup.await(5, TimeUnit.SECONDS));
                Future<ObjectNode> independent = callers.submit(() -> fixture.service.executeStatement(
                        fixture.single("independent", "SELECT 1"), "us-east-1"));
                assertNotEquals(first, independent.get(2, TimeUnit.SECONDS).path("Id").asText());
            } finally {
                release.countDown();
            }
            assertEquals(first, retry.get(5, TimeUnit.SECONDS).path("Id").asText());
        }
    }

    private RedshiftDataIdempotencyStore persistentReceipts(Path file, Clock clock) {
        PersistentStorage<String, RedshiftDataIdempotencyStore.Receipt> delegate = new PersistentStorage<>(file,
                new TypeReference<Map<String, RedshiftDataIdempotencyStore.Receipt>>() { });
        delegate.load();
        return new RedshiftDataIdempotencyStore(new AccountAwareStorageBackend<>(delegate, null, "000000000000"), clock);
    }

    private RedshiftDataStatementStore persistentStatements(Path file, RedshiftDataIdempotencyStore receipts) {
        PersistentStorage<String, RedshiftDataStatementStore.StoredStatement> delegate = new PersistentStorage<>(file,
                new TypeReference<Map<String, RedshiftDataStatementStore.StoredStatement>>() { });
        delegate.load();
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.<RedshiftDataStatementStore.StoredStatement>create(eq("redshift-data"), eq("statements.json"), any()))
                .thenReturn(new AccountAwareStorageBackend<>(delegate, null, "000000000000"));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().redshiftData().resultTtlHours()).thenReturn(24);
        return new RedshiftDataStatementStore(factory, config, receipts);
    }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final String jdbcUrl = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        private final RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
        private final RedshiftDataEventPublisher publisher = mock(RedshiftDataEventPublisher.class);
        private final RedshiftDataExecutor executor = new RedshiftDataExecutor(2, 16, 1);
        private final MutableClock clock = new MutableClock();
        private final RedshiftDataStatementStore statements = spy(new RedshiftDataStatementStore(1, clock));
        private final RedshiftDataIdempotencyStore receipts = spy(new RedshiftDataIdempotencyStore(
                AccountAwareStorageBackend.inMemory("000000000000"), clock));
        private final RedshiftDataService service;
        private final RedshiftDataResourceResolver resolver = mock(RedshiftDataResourceResolver.class);

        private Fixture() throws Exception {
            Driver.load();
            try (Connection connection = DriverManager.getConnection(jdbcUrl); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE mutations (id INT)");
            }
            when(resolver.resolve(any(), any())).thenReturn(new RedshiftDataResourceResolver.DatabaseTarget(
                    "arn:aws:redshift:us-east-1:000000000000:cluster:wh", "localhost", 5439, "dev", "admin", "password"));
            when(factory.open(any())).thenAnswer(invocation -> DriverManager.getConnection(jdbcUrl));
            service = service(statements, receipts);
        }

        private RedshiftDataService service(RedshiftDataStatementStore statements, RedshiftDataIdempotencyStore receipts) {
            return new RedshiftDataService(resolver, factory, statements, mapper, null, executor, publisher,
                    null, null, receipts);
        }

        private ObjectNode single(String token, String sql) {
            return mapper.createObjectNode().put("ClientToken", token).put("Sql", sql).put("WaitTimeSeconds", 5);
        }

        private int count() throws Exception {
            try (Connection connection = DriverManager.getConnection(jdbcUrl);
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM mutations")) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }

        @Override
        public void close() {
            executor.close();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.now();

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
