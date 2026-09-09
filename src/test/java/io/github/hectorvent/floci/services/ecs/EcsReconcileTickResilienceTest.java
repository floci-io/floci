package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EcsReconcileTickResilienceTest {

    private static final String REGION = "us-east-1";

    @Test
    void reconcileTickSurvivesAStorageReadFailureSoLaterTicksStillRun() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        EcsService service = new EcsService(
                new RegionResolver(REGION, "000000000000"),
                mock(EcsContainerManager.class),
                config,
                mock(EcsLoadBalancerRegistrar.class),
                new FailingServicesStorageFactory(),
                null);
        service.initializeStorage();

        assertDoesNotThrow(service::reconcile);
        assertDoesNotThrow(service::reconcile);
    }

    private static final class FailingServicesStorageFactory extends StorageFactory {

        private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();

        private FailingServicesStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                       String fileName,
                                                       TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, name -> {
                if ("ecs-services.json".equals(name)) {
                    AccountAwareStorageBackend<V> failing = mock(AccountAwareStorageBackend.class);
                    when(failing.keys()).thenThrow(
                            new IllegalStateException("storage unavailable"));
                    return failing;
                }
                return AccountAwareStorageBackend.inMemory("000000000000");
            });
        }
    }
}
