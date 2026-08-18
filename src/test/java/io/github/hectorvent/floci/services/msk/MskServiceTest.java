package io.github.hectorvent.floci.services.msk;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.msk.model.ClusterState;
import io.github.hectorvent.floci.services.msk.model.BrokerNodeGroupInfo;
import io.github.hectorvent.floci.services.msk.model.ClientAuthentication;
import io.github.hectorvent.floci.services.msk.model.ConfigurationInfo;
import io.github.hectorvent.floci.services.msk.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.msk.model.CreateClusterV2Request;
import io.github.hectorvent.floci.services.msk.model.EncryptionInfo;
import io.github.hectorvent.floci.services.msk.model.EncryptionInTransit;
import io.github.hectorvent.floci.services.msk.model.LoggingInfo;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import io.github.hectorvent.floci.services.msk.model.ProvisionedRequest;
import io.github.hectorvent.floci.services.msk.model.Sasl;
import io.github.hectorvent.floci.services.msk.model.Scram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

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
                .thenReturn(AccountAwareStorageBackend.inMemory("000000000000"));

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

    @Test
    void createClusterPersistsRequestMetadata() {
        CreateClusterRequest request = new CreateClusterRequest();
        request.setClusterName("meta-cluster");
        request.setKafkaVersion("3.5.1");
        request.setNumberOfBrokerNodes(3);

        BrokerNodeGroupInfo nodeGroup = new BrokerNodeGroupInfo();
        nodeGroup.setInstanceType("kafka.m5.large");
        nodeGroup.setClientSubnets(List.of("subnet-aaa", "subnet-bbb"));
        nodeGroup.setSecurityGroups(List.of("sg-111"));
        request.setBrokerNodeGroupInfo(nodeGroup);

        EncryptionInTransit encryptionInTransit = new EncryptionInTransit();
        encryptionInTransit.setClientBroker("TLS");
        encryptionInTransit.setInCluster(true);
        EncryptionInfo encryptionInfo = new EncryptionInfo();
        encryptionInfo.setEncryptionInTransit(encryptionInTransit);
        request.setEncryptionInfo(encryptionInfo);

        Sasl sasl = new Sasl();
        Scram scram = new Scram();
        scram.setEnabled(true);
        sasl.setScram(scram);
        ClientAuthentication clientAuthentication = new ClientAuthentication();
        clientAuthentication.setSasl(sasl);
        request.setClientAuthentication(clientAuthentication);

        request.setEnhancedMonitoring("PER_BROKER");
        request.setLoggingInfo(new LoggingInfo());
        ConfigurationInfo configurationInfo = new ConfigurationInfo();
        configurationInfo.setArn("arn:aws:kafka:us-east-1:123456789012:configuration/conf/1");
        configurationInfo.setRevision(2L);
        request.setConfigurationInfo(configurationInfo);
        request.setTags(Map.of("Environment", "example"));

        MskCluster cluster = mskService.createCluster(request);

        assertEquals(3, cluster.getNumberOfBrokerNodes());
        assertEquals("kafka.m5.large", cluster.getBrokerNodeGroupInfo().getInstanceType());
        assertEquals(List.of("subnet-aaa", "subnet-bbb"), cluster.getBrokerNodeGroupInfo().getClientSubnets());
        assertEquals(List.of("sg-111"), cluster.getBrokerNodeGroupInfo().getSecurityGroups());
        assertEquals("TLS", cluster.getEncryptionInfo().getEncryptionInTransit().getClientBroker());
        assertTrue(cluster.getEncryptionInfo().getEncryptionInTransit().getInCluster());
        assertTrue(cluster.getClientAuthentication().getSasl().getScram().getEnabled());
        assertEquals("PER_BROKER", cluster.getEnhancedMonitoring());
        assertNotNull(cluster.getLoggingInfo());
        assertEquals("arn:aws:kafka:us-east-1:123456789012:configuration/conf/1", cluster.getConfigurationInfo().getArn());
        assertEquals(2L, cluster.getConfigurationInfo().getRevision());
        assertEquals("example", cluster.getTags().get("Environment"));

        MskCluster described = mskService.describeCluster(cluster.getClusterArn());
        assertEquals(3, described.getNumberOfBrokerNodes());
        assertEquals("kafka.m5.large", described.getBrokerNodeGroupInfo().getInstanceType());
        assertEquals("example", described.getTags().get("Environment"));
    }

    @Test
    void createClusterV2MergesTopLevelTagsAndProvisionedFields() {
        CreateClusterV2Request request = new CreateClusterV2Request();
        request.setClusterName("v2-meta-cluster");
        request.setTags(Map.of("Team", "data"));

        ProvisionedRequest provisioned = new ProvisionedRequest();
        provisioned.setKafkaVersion("3.4.0");
        provisioned.setNumberOfBrokerNodes(5);
        BrokerNodeGroupInfo nodeGroup = new BrokerNodeGroupInfo();
        nodeGroup.setInstanceType("kafka.t3.small");
        nodeGroup.setClientSubnets(List.of("subnet-ccc"));
        provisioned.setBrokerNodeGroupInfo(nodeGroup);
        request.setProvisioned(provisioned);

        MskCluster cluster = mskService.createCluster(request);

        assertEquals("v2-meta-cluster", cluster.getClusterName());
        assertEquals("3.4.0", cluster.getCurrentBrokerSoftwareInfo().getKafkaVersion());
        assertEquals(5, cluster.getNumberOfBrokerNodes());
        assertEquals("kafka.t3.small", cluster.getBrokerNodeGroupInfo().getInstanceType());
        assertEquals(List.of("subnet-ccc"), cluster.getBrokerNodeGroupInfo().getClientSubnets());
        assertEquals("data", cluster.getTags().get("Team"));
    }
}
