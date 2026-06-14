package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheMemcachedContainerManager;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElastiCacheMemcachedServiceTest {

    private ElastiCacheMemcachedService service;

    @BeforeEach
    void setUp() {
        ElastiCacheMemcachedContainerManager containerManager = mock(ElastiCacheMemcachedContainerManager.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        EmulatorConfig config = mock(EmulatorConfig.class);

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.defaultMemcachedImage()).thenReturn("memcached:1.6");
        when(config.hostname()).thenReturn(Optional.of("localhost"));

        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> new InMemoryStorage<>());
        when(containerManager.start(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "cluster", "localhost", 11211));

        service = new ElastiCacheMemcachedService(containerManager, storageFactory, config);
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
        assertEquals("CacheClusterAlreadyExistsFault", ex.getErrorCode());
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

        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> new InMemoryStorage<>());
        when(containerManager.start(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "cluster", "172.20.0.10", 11211));

        ElastiCacheMemcachedService containerModeService =
                new ElastiCacheMemcachedService(containerManager, storageFactory, config);

        CacheCluster cluster = containerModeService.createCacheCluster("container-cluster");

        assertEquals("172.20.0.10", cluster.getConfigurationEndpoint().address());
    }
}
