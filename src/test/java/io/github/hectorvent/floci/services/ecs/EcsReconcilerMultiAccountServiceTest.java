package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EcsReconcilerMultiAccountServiceTest {

    private static final String REGION = "us-east-1";
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "000000000001";

    @Test
    void reconcilerPlacesTasksForAServiceOwnedByANonDefaultAccount() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        EcsContainerManager containerManager = mock(EcsContainerManager.class);
        when(containerManager.startTask(any(), any(), any(), anyString()))
                .thenReturn(mock(EcsTaskHandle.class));

        AccountScopedStorageFactory storageFactory = new AccountScopedStorageFactory();
        EcsService service = new EcsService(
                new RegionResolver(REGION, DEFAULT_ACCOUNT),
                containerManager,
                config,
                mock(EcsLoadBalancerRegistrar.class),
                storageFactory,
                null);
        service.initializeStorage();

        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage("nginx:alpine");
        service.registerTaskDefinition("multi-acct-fam", List.of(cd), null, null, null,
                null, null, List.of(), REGION);
        service.createCluster("multi-acct-cluster", REGION);
        service.createService("multi-acct-cluster", "multi-acct-svc", "multi-acct-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);

        AccountAwareStorageBackend<EcsServiceModel> store = storageFactory.servicesStore();
        Map<String, EcsServiceModel> byRawKey = store.scanAllAccountsWithRawKeys();
        String rawKey = byRawKey.keySet().iterator().next();
        EcsServiceModel stored = byRawKey.get(rawKey);
        store.delete(rawKey);
        String logicalKey = rawKey.contains("/") ? rawKey.substring(rawKey.indexOf('/') + 1) : rawKey;
        store.putForAccount(OTHER_ACCOUNT, logicalKey, stored);

        service.reconcile();

        EcsServiceModel reconciled =
                store.getForAccount(OTHER_ACCOUNT, logicalKey).orElseThrow();
        assertEquals(1, reconciled.getRunningCount(),
                "a service owned by a non-default account must be reconciled too");
    }

    private static final class AccountScopedStorageFactory extends StorageFactory {

        private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();

        private AccountScopedStorageFactory() {
            super(null, null);
        }

        @SuppressWarnings("unchecked")
        AccountAwareStorageBackend<EcsServiceModel> servicesStore() {
            return (AccountAwareStorageBackend<EcsServiceModel>) stores.get("ecs-services.json");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                       String fileName,
                                                       TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName,
                    ignored -> AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT));
        }
    }
}