package io.github.hectorvent.floci.services.msk;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.msk.model.ClusterState;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class MskServiceTest {

    private MskService mskService;
    private StorageFactory storageFactory;
    private EmulatorConfig config;
    private RedpandaManager redpandaManager;

    @BeforeEach
    void setUp() {
        storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new InMemoryStorage<>());

        config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var mskConfig = Mockito.mock(EmulatorConfig.MskServiceConfig.class);
        
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.msk()).thenReturn(mskConfig);
        when(mskConfig.mock()).thenReturn(true);
        when(config.defaultRegion()).thenReturn("us-east-1");

        redpandaManager = Mockito.mock(RedpandaManager.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        mskService = new MskService(storageFactory, config, regionResolver, redpandaManager);
    }

    @Test
    void createCluster() {
        MskCluster cluster = mskService.createCluster("test-cluster");
        assertNotNull(cluster);
        assertEquals("test-cluster", cluster.getClusterName());
        assertEquals(ClusterState.ACTIVE, cluster.getState());
        assertTrue(cluster.getClusterArn().contains("test-cluster"));
    }

    @Test
    void createClusterPopulatesCurrentBrokerSoftwareInfoWithDefaultKafkaVersion() {
        MskCluster cluster = mskService.createCluster("test-cluster");
        assertNotNull(cluster.getCurrentBrokerSoftwareInfo());
        assertEquals("3.6.0", cluster.getCurrentBrokerSoftwareInfo().getKafkaVersion());
    }

    @Test
    void createClusterEchoesRequestedKafkaVersion() {
        MskCluster cluster = mskService.createCluster("test-cluster", "3.5.1");
        assertNotNull(cluster.getCurrentBrokerSoftwareInfo());
        assertEquals("3.5.1", cluster.getCurrentBrokerSoftwareInfo().getKafkaVersion());
    }

    @Test
    void describeCluster() {
        MskCluster created = mskService.createCluster("test-cluster");
        MskCluster described = mskService.describeCluster(created.getClusterArn());
        assertEquals(created.getClusterArn(), described.getClusterArn());
    }

    @Test
    void listClusters() {
        mskService.createCluster("cluster-1");
        mskService.createCluster("cluster-2");
        List<MskCluster> clusters = mskService.listClusters();
        assertEquals(2, clusters.size());
    }

    @Test
    void deleteCluster() {
        MskCluster cluster = mskService.createCluster("test-cluster");
        mskService.deleteCluster(cluster.getClusterArn());
        assertTrue(mskService.listClusters().isEmpty());
    }
}
