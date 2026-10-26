package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class EcsReconciliationConcurrencyServiceTest {

    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void concurrentPassesWaitAndLaunchOnlyTheDesiredCount(boolean firstFullTick, boolean secondFullTick)
            throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        TestStorageFactory storage = new TestStorageFactory();
        EcsService service = new EcsService(new RegionResolver(REGION, "000000000000"),
                mock(EcsContainerManager.class), config, mock(EcsLoadBalancerRegistrar.class), storage, null);
        service.initializeStorage();
        ContainerDefinition container = new ContainerDefinition();
        container.setName("app");
        container.setImage("app:1");
        service.registerTaskDefinition("family", List.of(container), null, null, null,
                null, null, List.of(), REGION);
        service.createCluster("cluster", REGION);
        service.createService("cluster", "service", "family", 1, LaunchType.FARGATE, List.of(), null, REGION);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstRead.compareAndSet(true, false)) {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS), "test must release the first pass");
            }
            return invocation.callRealMethod();
        }).when(storage.services()).keys();

        FutureTask<Void> first = new FutureTask<>(() -> runPass(service, firstFullTick), null);
        FutureTask<Void> second = new FutureTask<>(() -> runPass(service, secondFullTick), null);
        Thread firstThread = new Thread(first, "first-ecs-pass");
        Thread secondThread = new Thread(second, "second-ecs-pass");
        firstThread.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "first pass must reach service storage");
            secondThread.start();
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    secondThread.getState() == Thread.State.BLOCKED || second.isDone());
            assertEquals(Thread.State.BLOCKED, secondThread.getState(),
                    "a direct pass must wait for the running pass instead of reconciling the same deficit");
        } finally {
            release.countDown();
            firstThread.join(10_000);
            secondThread.join(10_000);
        }
        first.get(1, TimeUnit.SECONDS);
        second.get(1, TimeUnit.SECONDS);
        assertEquals(1, service.listTasks("cluster", null, null, null, REGION).size());
        assertEquals(1, service.describeServices("cluster", List.of("service"), REGION)
                .getFirst().getRunningCount());
    }

    private static void runPass(EcsService service, boolean fullTick) {
        if (fullTick) {
            service.reconcile();
        } else {
            service.reconcileServices();
        }
    }

    private static final class TestStorageFactory extends StorageFactory {
        private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();

        private TestStorageFactory() {
            super(null, null);
        }

        @SuppressWarnings("unchecked")
        private AccountAwareStorageBackend<EcsServiceModel> services() {
            return (AccountAwareStorageBackend<EcsServiceModel>) stores.get("ecs-services.json");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                       TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName,
                    ignored -> spy(AccountAwareStorageBackend.inMemory("000000000000")));
        }
    }
}
