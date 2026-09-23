package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheMemcachedContainerManager;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElastiCacheMemcachedServiceTest {

    private ElastiCacheMemcachedService service;
    private ElastiCacheMemcachedContainerManager containerManager;
    private ElastiCacheProvisioningIds provisioningIds;

    @BeforeEach
    void setUp() {
        containerManager = mock(ElastiCacheMemcachedContainerManager.class);
        provisioningIds = new ElastiCacheProvisioningIds();
        StorageFactory storageFactory = mock(StorageFactory.class);
        EmulatorConfig config = mock(EmulatorConfig.class);

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.defaultMemcachedImage()).thenReturn("memcached:1.6");
        when(config.hostname()).thenReturn(Optional.of("localhost"));

        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        when(containerManager.tryStart(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "cluster", "localhost", 11211));

        service = new ElastiCacheMemcachedService(containerManager, storageFactory, config, provisioningIds);
    }

    @Test
    void createClusterReturnsAvailableCluster() {
        CacheCluster cluster = service.createCacheCluster("my-cluster");

        assertEquals("my-cluster", cluster.getCacheClusterId());
        assertEquals(CacheClusterStatus.AVAILABLE, cluster.getCacheClusterStatus());
        assertEquals("memcached", cluster.getEngine());
        assertEquals("localhost", cluster.getConfigurationEndpoint().address());
    }

    @Test
    void createDuplicateClusterThrows() {
        service.createCacheCluster("my-cluster");

        AwsException ex = assertThrows(AwsException.class, () -> service.createCacheCluster("my-cluster"));
        assertEquals("CacheClusterAlreadyExists", ex.getErrorCode());
    }

    @Test
    void createIsRefusedWhileARedisCreateHoldsTheSameIdInFlight() {
        // What a concurrent CreateReplicationGroup or redis CreateCacheCluster leaves in the
        // shared set between claiming the id and persisting its record. The three stores are
        // still empty in that window, so the store checks alone would let this create through
        // and both would write the same id.
        assertTrue(provisioningIds.claim("racing-cluster"));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createCacheCluster("racing-cluster"));
        assertEquals("CacheClusterAlreadyExists", ex.getErrorCode());
        verify(containerManager, never()).tryStart(eq("racing-cluster"), anyString());

        // The refusal must not have released the claim the other create still holds.
        assertFalse(provisioningIds.claim("racing-cluster"));

        // Once that create finishes and releases, the id is free again.
        provisioningIds.release("racing-cluster");
        assertEquals("racing-cluster",
                service.createCacheCluster("racing-cluster").getCacheClusterId());
    }

    @Test
    void getUnknownClusterThrows() {
        AwsException ex = assertThrows(AwsException.class, () -> service.getCacheCluster("no-such-cluster"));
        assertEquals("CacheClusterNotFound", ex.getErrorCode());
    }

    @Test
    void listClustersReturnsAll() {
        service.createCacheCluster("cluster-a");
        service.createCacheCluster("cluster-b");

        Collection<CacheCluster> list = service.listCacheClusters(null);
        assertEquals(2, list.size());
    }

    @Test
    void listClustersFiltersById() {
        service.createCacheCluster("cluster-a");
        service.createCacheCluster("cluster-b");

        Collection<CacheCluster> list = service.listCacheClusters("cluster-a");
        assertEquals(1, list.size());
        assertEquals("cluster-a", list.iterator().next().getCacheClusterId());
    }

    @Test
    void deleteClusterRemovesIt() {
        service.createCacheCluster("my-cluster");
        service.deleteCacheCluster("my-cluster");

        AwsException ex = assertThrows(AwsException.class, () -> service.getCacheCluster("my-cluster"));
        assertEquals("CacheClusterNotFound", ex.getErrorCode());
    }

    @Test
    void createClusterUsesContainerHostWhenHostnameNotConfigured() {
        ElastiCacheMemcachedContainerManager containerManager = mock(ElastiCacheMemcachedContainerManager.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        EmulatorConfig config = mock(EmulatorConfig.class);

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.defaultMemcachedImage()).thenReturn("memcached:1.6");
        when(config.hostname()).thenReturn(Optional.empty());

        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        when(containerManager.tryStart(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "cluster", "172.20.0.10", 11211));

        ElastiCacheMemcachedService containerModeService =
                new ElastiCacheMemcachedService(containerManager, storageFactory, config,
                        new ElastiCacheProvisioningIds());

        CacheCluster cluster = containerModeService.createCacheCluster("container-cluster");

        assertEquals("172.20.0.10", cluster.getConfigurationEndpoint().address());
    }

    @Test
    void createClusterWithoutDockerDaemonStillReachesAvailable() {
        // tryStart() returns null when no Docker daemon is reachable. The cache cluster record is
        // metadata, so the create still succeeds and the cluster reaches 'available' on the first
        // describe (what SDK/Terraform waiters poll), on Memcached's well-known port.
        when(containerManager.tryStart(anyString(), anyString())).thenReturn(null);

        CacheCluster cluster = service.createCacheCluster("no-docker-cluster");

        assertEquals(CacheClusterStatus.AVAILABLE, cluster.getCacheClusterStatus());
        assertEquals("localhost", cluster.getConfigurationEndpoint().address());
        assertEquals(11211, cluster.getConfigurationEndpoint().port());
        assertEquals("no-docker-cluster",
                service.getCacheCluster("no-docker-cluster").getCacheClusterId());

        // Delete must not reach for a container that was never created.
        service.deleteCacheCluster("no-docker-cluster");
        verify(containerManager, never()).stop(any());
    }

    @Test
    void restorePersistedRuntimeRestartsTheContainerAndRepointsTheEndpoint() {
        StorageFactory storageFactory = sharedStorageFactory();
        ElastiCacheMemcachedContainerManager beforeRestart = mock(ElastiCacheMemcachedContainerManager.class);
        when(beforeRestart.tryStart(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "my-cluster", "localhost", 32770));
        serviceWith(storageFactory, beforeRestart).createCacheCluster("my-cluster");

        ElastiCacheMemcachedContainerManager restarted = mock(ElastiCacheMemcachedContainerManager.class);
        when(restarted.tryStart(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid2", "my-cluster", "localhost", 32771));
        ElastiCacheMemcachedService restartedService = serviceWith(storageFactory, restarted);

        restartedService.restorePersistedRuntime().join();

        verify(restarted).tryStart(eq("my-cluster"), anyString());
        CacheCluster cluster = restartedService.getCacheCluster("my-cluster");
        assertEquals(CacheClusterStatus.AVAILABLE, cluster.getCacheClusterStatus());
        assertEquals(32771, cluster.getConfigurationEndpoint().port(),
                "Docker publishes a fresh host port per run, so the endpoint must follow it");
    }

    @Test
    void memcachedRestoreFailureReportsRestoreFailed() {
        StorageFactory storageFactory = sharedStorageFactory();
        ElastiCacheMemcachedContainerManager beforeRestart = mock(ElastiCacheMemcachedContainerManager.class);
        when(beforeRestart.tryStart(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "my-cluster", "localhost", 11211));
        serviceWith(storageFactory, beforeRestart).createCacheCluster("my-cluster");

        ElastiCacheMemcachedContainerManager restarted = mock(ElastiCacheMemcachedContainerManager.class);
        when(restarted.tryStart(anyString(), anyString()))
                .thenThrow(new RuntimeException("container failed"));
        ElastiCacheMemcachedService restartedService = serviceWith(storageFactory, restarted);

        restartedService.restorePersistedRuntime().join();

        CacheCluster cluster = restartedService.getCacheCluster("my-cluster");
        assertEquals(CacheClusterStatus.RESTORE_FAILED, cluster.getCacheClusterStatus());
        assertNull(cluster.getConfigurationEndpoint(),
                "A cluster whose container is gone must not advertise an endpoint");
    }

    @Test
    void restoreDoesNotResurrectAClusterDeletedWhileItWasRestoring() {
        StorageFactory storageFactory = sharedStorageFactory();
        ElastiCacheMemcachedContainerManager beforeRestart = mock(ElastiCacheMemcachedContainerManager.class);
        when(beforeRestart.tryStart(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "my-cluster", "localhost", 32770));
        serviceWith(storageFactory, beforeRestart).createCacheCluster("my-cluster");

        ElastiCacheMemcachedContainerManager restarted = mock(ElastiCacheMemcachedContainerManager.class);
        ElastiCacheMemcachedService restartedService = serviceWith(storageFactory, restarted);
        ElastiCacheContainerHandle restoredHandle =
                new ElastiCacheContainerHandle("cid2", "my-cluster", "localhost", 32771);
        // The delete lands in the window the cluster's monitor closes: the container is up, the
        // record has not been written back yet.
        when(restarted.tryStart(anyString(), anyString())).thenAnswer(inv -> {
            restartedService.deleteCacheCluster("my-cluster");
            return restoredHandle;
        });

        restartedService.restorePersistedRuntime().join();

        assertThrows(AwsException.class, () -> restartedService.getCacheCluster("my-cluster"),
                "A cluster deleted while it was restoring must stay deleted");
        verify(restarted).stop(restoredHandle);
    }

    private static StorageFactory sharedStorageFactory() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        Map<String, Object> backends = new ConcurrentHashMap<>();
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv ->
                backends.computeIfAbsent(inv.getArgument(1, String.class),
                        key -> AccountAwareStorageBackend.inMemory("000000000000")));
        return storageFactory;
    }

    private static ElastiCacheMemcachedService serviceWith(StorageFactory storageFactory,
                                                           ElastiCacheMemcachedContainerManager containerManager) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.defaultMemcachedImage()).thenReturn("memcached:1.6");
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        return new ElastiCacheMemcachedService(containerManager, storageFactory, config,
                new ElastiCacheProvisioningIds());
    }
}
