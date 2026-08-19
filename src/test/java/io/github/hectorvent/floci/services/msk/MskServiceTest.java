package io.github.hectorvent.floci.services.msk;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.msk.model.ClusterState;
import io.github.hectorvent.floci.services.msk.model.ConfigurationState;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import io.github.hectorvent.floci.services.msk.model.MskConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
        // A fresh backend per call - MskService now creates two (clusters, configurations),
        // and a shared instance would let configuration scans see cluster entries and vice versa.
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));

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
    void createConfiguration() {
        MskConfiguration configuration = mskService.createConfiguration(
                "test-config", "a test config", List.of("3.6.0"), "auto.create.topics.enable=true");

        assertNotNull(configuration);
        assertEquals("test-config", configuration.getName());
        assertEquals(ConfigurationState.ACTIVE, configuration.getState());
        assertTrue(configuration.getArn().contains("test-config"));
        assertNotNull(configuration.getLatestRevision());
        assertEquals(1L, configuration.getLatestRevision().getRevision());
        assertEquals("auto.create.topics.enable=true", configuration.getServerProperties());
    }

    @Test
    void createConfigurationRejectsMissingName() {
        assertThrows(AwsException.class, () ->
                mskService.createConfiguration(null, "desc", List.of("3.6.0"), "props"));
    }

    @Test
    void createConfigurationRejectsInvalidNamePattern() {
        assertThrows(AwsException.class, () ->
                mskService.createConfiguration("bad name!", "desc", List.of("3.6.0"), "props"));
    }

    @Test
    void createConfigurationRejectsMissingServerProperties() {
        assertThrows(AwsException.class, () ->
                mskService.createConfiguration("test-config", "desc", List.of("3.6.0"), null));
    }

    @Test
    void createConfigurationRejectsDuplicateName() {
        mskService.createConfiguration("test-config", "desc", List.of("3.6.0"), "props");
        assertThrows(AwsException.class, () ->
                mskService.createConfiguration("test-config", "desc", List.of("3.6.0"), "props"));
    }

    @Test
    void describeConfiguration() {
        MskConfiguration created = mskService.createConfiguration(
                "test-config", "desc", List.of("3.6.0"), "props");
        MskConfiguration described = mskService.describeConfiguration(created.getArn());
        assertEquals(created.getArn(), described.getArn());
    }

    @Test
    void describeConfigurationNotFoundThrows() {
        assertThrows(AwsException.class, () ->
                mskService.describeConfiguration("arn:aws:kafka:us-east-1:000000000000:configuration/missing/id"));
    }

    @Test
    void listConfigurations() {
        mskService.createConfiguration("config-1", "desc", List.of("3.6.0"), "props");
        mskService.createConfiguration("config-2", "desc", List.of("3.6.0"), "props");
        List<MskConfiguration> configurations = mskService.listConfigurations(null, null).items();
        assertEquals(2, configurations.size());
    }

    @Test
    void listConfigurationsPaginatesWithMaxResultsAndNextToken() {
        mskService.createConfiguration("config-1", "desc", List.of("3.6.0"), "props");
        mskService.createConfiguration("config-2", "desc", List.of("3.6.0"), "props");
        mskService.createConfiguration("config-3", "desc", List.of("3.6.0"), "props");

        PaginatedResult<MskConfiguration> firstPage = mskService.listConfigurations(2, null);
        assertEquals(2, firstPage.items().size());
        assertNotNull(firstPage.nextToken());

        PaginatedResult<MskConfiguration> secondPage = mskService.listConfigurations(2, firstPage.nextToken());
        assertEquals(1, secondPage.items().size());
        assertNull(secondPage.nextToken());
    }

    @Test
    void listConfigurationsRejectsMaxResultsAboveLimit() {
        assertThrows(AwsException.class, () -> mskService.listConfigurations(101, null));
    }

    @Test
    void listConfigurationsRejectsZeroMaxResults() {
        // AWS declares MaxResults with a minimum of 1; 0 is a real out-of-range value, not a
        // synonym for "omitted" (that's represented by null instead).
        AwsException ex = assertThrows(AwsException.class, () -> mskService.listConfigurations(0, null));
        assertEquals("BadRequestException", ex.getErrorCode());
    }

    @Test
    void listConfigurationsRejectsInvalidNextToken() {
        assertThrows(AwsException.class, () -> mskService.listConfigurations(null, "not-a-valid-token!!"));
    }

    @Test
    void deleteConfiguration() {
        MskConfiguration configuration = mskService.createConfiguration(
                "test-config", "desc", List.of("3.6.0"), "props");
        mskService.deleteConfiguration(configuration.getArn());
        assertTrue(mskService.listConfigurations(null, null).items().isEmpty());
    }

    // StorageBackend persists this model via plain Jackson serialization (PersistentStorage,
    // HybridStorage, WalStorage all call ObjectMapper#writeValue on it directly), so
    // serverProperties must round-trip through JSON or persistent/hybrid/wal storage modes
    // silently discard the broker configuration on reload.
    @Test
    void configurationServerPropertiesSurvivesJsonRoundTrip() throws Exception {
        MskConfiguration configuration = mskService.createConfiguration(
                "test-config", "desc", List.of("3.6.0"), "auto.create.topics.enable=true");

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(configuration);
        MskConfiguration restored = mapper.readValue(json, MskConfiguration.class);

        assertEquals("auto.create.topics.enable=true", restored.getServerProperties());
    }
}
