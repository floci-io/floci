package io.floci.aws.services.cloudwatch.logs;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.lifecycle.EmulatorInfoController;
import io.floci.aws.services.cloudwatch.logs.model.QueryDefinition;
import io.floci.aws.services.cloudwatch.logs.model.QueryDefinitionToken;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudWatchLogsQueryDefinitionResetTest {

    private static final String REGION = "us-east-1";
    private final InMemoryStorage<String, QueryDefinition> store = spy(new InMemoryStorage<>());
    private final InMemoryStorage<String, QueryDefinitionToken> tokens = new InMemoryStorage<>();
    private final StorageFactory factory = mock(StorageFactory.class);
    private final CloudWatchLogsQueryDefinitionService service = new CloudWatchLogsQueryDefinitionService(
            store, tokens, new RegionResolver(REGION, "111111111111"), Clock.systemUTC());

    @Test
    void resetRejectsConcurrentWritesAfterClearAndResumesTheSameService() throws Exception {
        QueryDefinition request = new QueryDefinition(null, "saved", "fields @message", List.of(),
                null, List.of(), 0, UUID.randomUUID().toString());
        String id = service.putQueryDefinition(request, REGION);
        CountDownLatch cleared = new CountDownLatch(1);
        CountDownLatch finishReset = new CountDownLatch(1);
        Resettable barrier = new Resettable() {
            @Override
            public void clear() {
                cleared.countDown();
                await(finishReset);
            }
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> reset = pool.submit(controller(List.of(service, barrier))::reset);
            assertTrue(cleared.await(5, TimeUnit.SECONDS));
            assertUnavailable(() -> service.putQueryDefinition(request, REGION));
            assertUnavailable(() -> service.deleteQueryDefinition(id, REGION));
            finishReset.countDown();
            reset.get(5, TimeUnit.SECONDS);
            assertTrue(service.describeQueryDefinitions(null, null, null, null, REGION).items().isEmpty());
            String freshId = service.putQueryDefinition(request, REGION);
            assertNotEquals(id, freshId);
            assertTrue(service.deleteQueryDefinition(freshId, REGION));
        } finally {
            finishReset.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resetDrainsAnActiveWriteBeforeWipingStorage(boolean delete) throws Exception {
        QueryDefinition request = new QueryDefinition(null, "saved", "fields @message", List.of(),
                null, List.of(), 0, UUID.randomUUID().toString());
        String id = service.putQueryDefinition(request, REGION);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch finishWrite = new CountDownLatch(1);
        if (delete) {
            doAnswer(call -> {
                writing.countDown();
                await(finishWrite);
                return call.callRealMethod();
            }).when(store).delete(anyString());
        } else {
            doAnswer(call -> {
                writing.countDown();
                await(finishWrite);
                return call.callRealMethod();
            }).when(store).put(anyString(), any(QueryDefinition.class));
        }
        CountDownLatch resetting = new CountDownLatch(1);
        Resettable resettable = new Resettable() {
            @Override
            public void beforeReset() {
                resetting.countDown();
                service.beforeReset();
            }

            @Override
            public void clear() {
                service.clear();
            }

            @Override
            public void afterReset() {
                service.afterReset();
            }
        };
        doAnswer(call -> {
            store.clear();
            tokens.clear();
            return null;
        }).when(factory).clearAll();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> write = pool.submit(() -> {
                if (delete) {
                    service.deleteQueryDefinition(id, REGION);
                } else {
                    service.putQueryDefinition(new QueryDefinition(id, "updated", "stats count()", List.of(),
                            null, List.of(), 0, UUID.randomUUID().toString()), REGION);
                }
            });
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            Future<?> reset = pool.submit(controller(List.of(resettable))::reset);
            assertTrue(resetting.await(5, TimeUnit.SECONDS));
            verify(factory, never()).clearAll();
            finishWrite.countDown();
            write.get(5, TimeUnit.SECONDS);
            reset.get(5, TimeUnit.SECONDS);
            assertTrue(store.isEmpty());
            assertTrue(tokens.isEmpty());
        } finally {
            finishWrite.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedStorageResetStillResumesWrites() {
        String id = service.putQueryDefinition(new QueryDefinition(null, "saved", "fields @message", List.of(),
                null, List.of(), 0, null), REGION);
        doThrow(new IllegalStateException("wipe failed")).when(factory).clearAll();

        assertThrows(IllegalStateException.class, () -> controller(List.of(service)).reset());
        assertTrue(service.deleteQueryDefinition(id, REGION));
    }

    @SuppressWarnings("unchecked")
    private EmulatorInfoController controller(List<Resettable> services) {
        Instance<Resettable> resettables = mock(Instance.class);
        when(resettables.iterator()).thenAnswer(call -> services.iterator());
        Instance<ContainerTeardown> teardowns = mock(Instance.class);
        when(teardowns.iterator()).thenAnswer(call -> List.<ContainerTeardown>of().iterator());
        return new EmulatorInfoController(null, null, factory, resettables, teardowns, null, null, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void assertUnavailable(Runnable operation) {
        AwsException error = assertThrows(AwsException.class, operation::run);
        assertEquals("ServiceUnavailableException", error.getErrorCode());
        assertEquals(503, error.getHttpStatus());
    }
}
