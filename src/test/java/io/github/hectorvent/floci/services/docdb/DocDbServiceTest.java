package io.github.hectorvent.floci.services.docdb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.docdb.container.DocDbContainerManager;
import io.github.hectorvent.floci.services.docdb.model.DocDbCluster;
import io.github.hectorvent.floci.services.docdb.model.DocDbInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocDbServiceTest {

    private DocDbService docDbService;
    private DocDbContainerManager containerManager;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> new InMemoryStorage<>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var docdbConfig = Mockito.mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.docdb()).thenReturn(docdbConfig);
        when(docdbConfig.mock()).thenReturn(true);

        containerManager = Mockito.mock(DocDbContainerManager.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        docDbService = new DocDbService(config, regionResolver, containerManager, storageFactory);
    }

    @Test
    void createClusterInMockModeSkipsContainer() {
        DocDbCluster cluster = docDbService.createDbCluster(
                "mock-cluster", null, "admin", "secret", false);

        assertNotNull(cluster);
        assertEquals("mock-cluster", cluster.getDbClusterIdentifier());
        assertEquals("available", cluster.getStatus());
        assertEquals("localhost", cluster.getEndpoint());
        assertEquals(27017, cluster.getPort());
        assertTrue(cluster.getDbClusterArn().contains("mock-cluster"));

        verify(containerManager, never()).start(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void describeClusterInMockMode() {
        docDbService.createDbCluster("mock-cluster", null, "admin", "secret", false);

        DocDbCluster described = docDbService.getDbCluster("mock-cluster");
        assertEquals("mock-cluster", described.getDbClusterIdentifier());
        assertEquals("available", described.getStatus());
    }

    @Test
    void createInstanceInMockMode() {
        docDbService.createDbCluster("mock-cluster", null, "admin", "secret", false);

        DocDbInstance instance = docDbService.createDbInstance(
                "mock-instance", "mock-cluster", "db.r5.large", null, false);

        assertNotNull(instance);
        assertEquals("mock-instance", instance.getDbInstanceIdentifier());
        assertEquals("mock-cluster", instance.getDbClusterIdentifier());
        assertEquals("available", instance.getStatus());
        assertEquals("localhost", instance.getEndpoint());
        assertEquals(27017, instance.getPort());
    }

    @Test
    void deleteClusterInMockModeSkipsContainerStop() {
        docDbService.createDbCluster("mock-cluster", null, "admin", "secret", false);

        docDbService.deleteDbCluster("mock-cluster");

        assertTrue(docDbService.listDbClusters(null).isEmpty());
        verify(containerManager, never()).stop(any());
    }
}
