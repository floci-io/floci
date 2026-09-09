package io.github.hectorvent.floci.services.msk;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.msk.model.ClusterState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.services.msk.model.BrokerNodeGroupInfo;
import io.github.hectorvent.floci.services.msk.model.ClientAuthentication;
import io.github.hectorvent.floci.services.msk.model.ConfigurationInfo;
import io.github.hectorvent.floci.services.msk.model.ConfigurationRevision;
import io.github.hectorvent.floci.services.msk.model.ConfigurationRevisionDetail;
import io.github.hectorvent.floci.services.msk.model.ConfigurationState;
import io.github.hectorvent.floci.services.msk.model.CreateClusterRequest;
import io.github.hectorvent.floci.services.msk.model.CreateClusterV2Request;
import io.github.hectorvent.floci.services.msk.model.EncryptionInTransit;
import io.github.hectorvent.floci.services.msk.model.EbsStorageInfo;
import io.github.hectorvent.floci.services.msk.model.EncryptionInfo;
import io.github.hectorvent.floci.services.msk.model.JmxExporter;
import io.github.hectorvent.floci.services.msk.model.LoggingInfo;
import io.github.hectorvent.floci.services.msk.model.OpenMonitoring;
import io.github.hectorvent.floci.services.msk.model.Prometheus;
import io.github.hectorvent.floci.services.msk.model.Rebalancing;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import io.github.hectorvent.floci.services.msk.model.MskConfiguration;
import io.github.hectorvent.floci.services.msk.model.ProvisionedRequest;
import io.github.hectorvent.floci.services.msk.model.Sasl;
import io.github.hectorvent.floci.services.msk.model.Scram;
import io.github.hectorvent.floci.services.msk.model.Serverless;
import io.github.hectorvent.floci.services.msk.model.StorageInfo;
import io.github.hectorvent.floci.services.msk.model.VpcConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class MskServiceTest {

    private MskService mskService;
    private StorageFactory storageFactory;
    private EmulatorConfig config;
    private RedpandaManager redpandaManager;
    // MskService's constructor creates the cluster backend first and the configuration backend
    // second; captured here so tests can seed raw entries directly into the configuration store
    // (e.g. to simulate a pre-revision-history persisted entry) without exposing it from MskService.
    private StorageBackend<String, MskConfiguration> configurationStorage;

    @BeforeEach
    void setUp() {
        storageFactory = Mockito.mock(StorageFactory.class);
        // A fresh backend per call - MskService now creates two (clusters, configurations),
        // and a shared instance would let configuration scans see cluster entries and vice versa.
        // The configuration backend is also captured by filename so tests can seed raw entries
        // into it directly (e.g. to simulate a pre-revision-history persisted entry).
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> {
                    AccountAwareStorageBackend<?> backend = AccountAwareStorageBackend.inMemory("000000000000");
                    if ("msk-configurations.json".equals(invocation.getArgument(1))) {
                        configurationStorage = (StorageBackend<String, MskConfiguration>) backend;
                    }
                    return backend;
                });

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
    void createClusterStaysCreatingWhileBrokerStarts() {
        when(config.services().msk().mock()).thenReturn(false);
        when(redpandaManager.tryStartContainer(Mockito.any())).thenReturn(true);

        MskCluster cluster = mskService.createCluster("docker-cluster");

        assertEquals(ClusterState.CREATING, cluster.getState());
        assertEquals(ClusterState.CREATING,
                mskService.describeCluster(cluster.getClusterArn()).getState());
    }

    @Test
    void createClusterWithoutDockerDaemonStillReachesActive() {
        // tryStartContainer() returns false when no Docker daemon is reachable. The cluster
        // record is metadata, so the create still succeeds, reaches ACTIVE, and reports a
        // placeholder bootstrap address until a daemon appears.
        when(config.services().msk().mock()).thenReturn(false);
        when(redpandaManager.tryStartContainer(Mockito.any())).thenReturn(false);

        MskCluster cluster = mskService.createCluster("no-docker-cluster");

        assertEquals(ClusterState.ACTIVE, cluster.getState());
        assertEquals("localhost:9092", cluster.getBootstrapBrokers());
        assertEquals("no-docker-cluster",
                mskService.describeCluster(cluster.getClusterArn()).getClusterName());
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
        assertEquals("arn:aws:kafka:us-east-1:123456789012:configuration/conf/1",
                cluster.getCurrentBrokerSoftwareInfo().getConfigurationArn());
        assertEquals(2L, cluster.getCurrentBrokerSoftwareInfo().getConfigurationRevision());
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

    @Test
    void createClusterV2MapsProvisionedConfigurationOntoCurrentBrokerSoftwareInfo() {
        CreateClusterV2Request request = new CreateClusterV2Request();
        request.setClusterName("v2-configuration-cluster");

        ProvisionedRequest provisioned = new ProvisionedRequest();
        provisioned.setKafkaVersion("3.6.0");
        ConfigurationInfo configurationInfo = new ConfigurationInfo();
        configurationInfo.setArn("arn:aws:kafka:us-east-1:123456789012:configuration/conf/9");
        configurationInfo.setRevision(4L);
        provisioned.setConfigurationInfo(configurationInfo);
        request.setProvisioned(provisioned);

        MskCluster cluster = mskService.createCluster(request);

        assertEquals("arn:aws:kafka:us-east-1:123456789012:configuration/conf/9",
                cluster.getCurrentBrokerSoftwareInfo().getConfigurationArn());
        assertEquals(4L, cluster.getCurrentBrokerSoftwareInfo().getConfigurationRevision());
    }

    @Test
    void createClusterV2PersistsOpenMonitoringStorageModeAndRebalancing() {
        CreateClusterV2Request request = new CreateClusterV2Request();
        request.setClusterName("v2-open-monitoring-cluster");

        JmxExporter jmxExporter = new JmxExporter();
        jmxExporter.setEnabledInBroker(true);
        Prometheus prometheus = new Prometheus();
        prometheus.setJmxExporter(jmxExporter);
        OpenMonitoring openMonitoring = new OpenMonitoring();
        openMonitoring.setPrometheus(prometheus);
        Rebalancing rebalancing = new Rebalancing();
        rebalancing.setStatus("PAUSED");

        ProvisionedRequest provisioned = new ProvisionedRequest();
        provisioned.setKafkaVersion("3.6.0");
        provisioned.setOpenMonitoring(openMonitoring);
        provisioned.setStorageMode("TIERED");
        provisioned.setRebalancing(rebalancing);
        request.setProvisioned(provisioned);

        MskCluster cluster = mskService.createCluster(request);

        assertTrue(cluster.getOpenMonitoring().getPrometheus().getJmxExporter().getEnabledInBroker());
        assertEquals("TIERED", cluster.getStorageMode());
        assertEquals("PAUSED", cluster.getRebalancing().getStatus());
    }

    @Test
    void createClusterAppliesServerSideDefaultsWhenMembersAreAbsent() {
        CreateClusterRequest request = new CreateClusterRequest();
        request.setClusterName("defaults-cluster");
        request.setKafkaVersion("3.6.0");

        MskCluster cluster = mskService.createCluster(request);

        assertEquals("DEFAULT", cluster.getEnhancedMonitoring());
        assertEquals("TLS_PLAINTEXT", cluster.getEncryptionInfo().getEncryptionInTransit().getClientBroker());
        assertTrue(cluster.getEncryptionInfo().getEncryptionInTransit().getInCluster());
    }

    @Test
    void createClusterDoesNotOverrideExplicitlyRequestedEncryptionInTransit() {
        CreateClusterRequest request = new CreateClusterRequest();
        request.setClusterName("explicit-encryption-cluster");
        request.setKafkaVersion("3.6.0");
        EncryptionInTransit inTransit = new EncryptionInTransit();
        inTransit.setClientBroker("PLAINTEXT");
        inTransit.setInCluster(false);
        EncryptionInfo encryptionInfo = new EncryptionInfo();
        encryptionInfo.setEncryptionInTransit(inTransit);
        request.setEncryptionInfo(encryptionInfo);

        MskCluster cluster = mskService.createCluster(request);

        assertEquals("PLAINTEXT", cluster.getEncryptionInfo().getEncryptionInTransit().getClientBroker());
        assertFalse(cluster.getEncryptionInfo().getEncryptionInTransit().getInCluster());
    }

    // MskCluster is the shape WalStorage/PersistentStorage/HybridStorage write. All three build
    // their mapper the same way this test does - JavaTimeModule and a date format, no view or
    // mixin - so a @JsonIgnore on the model hides the field from the store, not just from the
    // API: a restart would come back with no bootstrap brokers (breaking GetBootstrapBrokers
    // and the Pipes Kafka source) and no container or volume ID (orphaning the Docker
    // resources). Responses stay clean through MskController's views instead - see
    // MskControllerIntegrationTest#describeClusterDoesNotLeakInternalFields.
    @Test
    void clusterSurvivesTheStorageMapperRoundTripWithItsInternalFields() throws Exception {
        CreateClusterRequest request = new CreateClusterRequest();
        request.setClusterName("persistence-cluster");
        request.setKafkaVersion("3.6.0");
        request.setNumberOfBrokerNodes(3);
        request.setTags(Map.of("Environment", "example"));
        MskCluster cluster = mskService.createCluster(request);
        cluster.setContainerId("container-abc");

        ObjectMapper storageMapper = new ObjectMapper();
        storageMapper.registerModule(new JavaTimeModule());
        storageMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MskCluster reloaded = storageMapper.readValue(storageMapper.writeValueAsString(cluster), MskCluster.class);

        assertEquals(cluster.getBootstrapBrokers(), reloaded.getBootstrapBrokers());
        assertNotNull(reloaded.getBootstrapBrokers());
        assertEquals("container-abc", reloaded.getContainerId());
        assertEquals(cluster.getVolumeId(), reloaded.getVolumeId());
        assertNotNull(reloaded.getVolumeId());
        assertEquals(cluster.getAccountId(), reloaded.getAccountId());
        assertNotNull(reloaded.getAccountId());

        // and the client-facing metadata survives too
        assertEquals(3, reloaded.getNumberOfBrokerNodes());
        assertEquals("example", reloaded.getTags().get("Environment"));
        assertEquals("3.6.0", reloaded.getCurrentBrokerSoftwareInfo().getKafkaVersion());
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
        assertEquals("auto.create.topics.enable=true",
                configuration.getServerPropertiesByRevision().get(1L));
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
    void createConfigurationAcceptsEmptyServerProperties() {
        // A zero-length blob means "no property overrides" - Gruntwork's msk module joins a
        // map that defaults to {} and creates aws_msk_configuration unconditionally.
        MskConfiguration configuration = mskService.createConfiguration(
                "empty-props-config", "desc", List.of("3.6.0"), "");

        assertEquals("", configuration.getServerPropertiesByRevision().get(1L));
        assertEquals(ConfigurationState.ACTIVE, configuration.getState());
    }

    @Test
    void createConfigurationAcceptsNonEmptyServerProperties() {
        MskConfiguration configuration = mskService.createConfiguration(
                "props-config", "desc", List.of("3.6.0"), "num.partitions=3");

        assertEquals("num.partitions=3", configuration.getServerPropertiesByRevision().get(1L));
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
    void describeConfigurationUnknownArnThrowsBadRequest() {
        // Real MSK uses BadRequestException with this message for unknown configuration ARNs;
        // terraform-provider-aws substring-matches it to detect a deleted configuration.
        AwsException ex = assertThrows(AwsException.class, () ->
                mskService.describeConfiguration("arn:aws:kafka:us-east-1:000000000000:configuration/missing/id"));
        assertEquals("BadRequestException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("Configuration ARN does not exist"));
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

    @Test
    void describeConfigurationAfterDeleteThrowsBadRequest() {
        MskConfiguration configuration = mskService.createConfiguration(
                "test-config", "desc", List.of("3.6.0"), "props");
        mskService.deleteConfiguration(configuration.getArn());

        AwsException ex = assertThrows(AwsException.class, () ->
                mskService.describeConfiguration(configuration.getArn()));
        assertEquals("BadRequestException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Configuration ARN does not exist"));
    }

    @Test
    void updateConfiguration() {
        MskConfiguration created = mskService.createConfiguration(
                "test-config", "v1 desc", List.of("3.6.0"), "auto.create.topics.enable=true");

        MskConfiguration updated = mskService.updateConfiguration(
                created.getArn(), "v2 desc", "auto.create.topics.enable=false");

        assertEquals(2L, updated.getLatestRevision().getRevision());
        assertEquals("v2 desc", updated.getLatestRevision().getDescription());
        // Revision 1 is untouched - update appends, it doesn't overwrite.
        assertEquals("auto.create.topics.enable=true", updated.getServerPropertiesByRevision().get(1L));
        assertEquals("auto.create.topics.enable=false", updated.getServerPropertiesByRevision().get(2L));
        assertEquals(2, updated.getRevisions().size());
    }

    @Test
    void updateConfigurationRejectsMissingServerProperties() {
        MskConfiguration created = mskService.createConfiguration(
                "test-config", "desc", List.of("3.6.0"), "props");
        assertThrows(AwsException.class, () ->
                mskService.updateConfiguration(created.getArn(), "desc", null));
    }

    @Test
    void updateConfigurationAcceptsEmptyServerProperties() {
        MskConfiguration created = mskService.createConfiguration(
                "test-config", "desc", List.of("3.6.0"), "num.partitions=3");

        MskConfiguration updated = mskService.updateConfiguration(created.getArn(), "cleared", "");

        assertEquals(2L, updated.getLatestRevision().getRevision());
        assertEquals("", updated.getServerPropertiesByRevision().get(2L));
    }

    @Test
    void updateConfigurationAcceptsNonEmptyServerProperties() {
        MskConfiguration created = mskService.createConfiguration(
                "test-config", "desc", List.of("3.6.0"), "");

        MskConfiguration updated = mskService.updateConfiguration(
                created.getArn(), "desc", "num.partitions=3");

        assertEquals("num.partitions=3", updated.getServerPropertiesByRevision().get(2L));
    }

    @Test
    void updateConfigurationUnknownArnThrowsBadRequest() {
        AwsException ex = assertThrows(AwsException.class, () ->
                mskService.updateConfiguration(
                        "arn:aws:kafka:us-east-1:000000000000:configuration/missing/id", "desc", "props"));
        assertEquals("BadRequestException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("Configuration ARN does not exist"));
    }

    @Test
    void listConfigurationRevisions() {
        MskConfiguration created = mskService.createConfiguration(
                "test-config", "desc", List.of("3.6.0"), "props-v1");
        mskService.updateConfiguration(created.getArn(), "desc v2", "props-v2");
        mskService.updateConfiguration(created.getArn(), "desc v3", "props-v3");

        List<ConfigurationRevision> revisions =
                mskService.listConfigurationRevisions(created.getArn(), null, null).items();
        assertEquals(3, revisions.size());
        assertEquals(1L, revisions.get(0).getRevision());
        assertEquals(3L, revisions.get(2).getRevision());
    }

    @Test
    void listConfigurationRevisionsPaginates() {
        MskConfiguration created = mskService.createConfiguration(
                "test-config", "desc", List.of("3.6.0"), "props-v1");
        mskService.updateConfiguration(created.getArn(), "desc v2", "props-v2");
        mskService.updateConfiguration(created.getArn(), "desc v3", "props-v3");

        PaginatedResult<ConfigurationRevision> firstPage =
                mskService.listConfigurationRevisions(created.getArn(), 2, null);
        assertEquals(2, firstPage.items().size());
        assertNotNull(firstPage.nextToken());

        PaginatedResult<ConfigurationRevision> secondPage =
                mskService.listConfigurationRevisions(created.getArn(), 2, firstPage.nextToken());
        assertEquals(1, secondPage.items().size());
        assertEquals(3L, secondPage.items().getFirst().getRevision());
    }

    @Test
    void describeConfigurationRevisionReturnsServerPropertiesForThatRevision() {
        MskConfiguration created = mskService.createConfiguration(
                "test-config", "desc", List.of("3.6.0"), "props-v1");
        mskService.updateConfiguration(created.getArn(), "desc v2", "props-v2");

        ConfigurationRevisionDetail rev1 = mskService.describeConfigurationRevision(created.getArn(), 1L);
        assertEquals("props-v1", rev1.getServerProperties());
        assertEquals("desc", rev1.getDescription());

        ConfigurationRevisionDetail rev2 = mskService.describeConfigurationRevision(created.getArn(), 2L);
        assertEquals("props-v2", rev2.getServerProperties());
        assertEquals("desc v2", rev2.getDescription());
    }

    @Test
    void describeConfigurationRevisionNotFoundThrows() {
        MskConfiguration created = mskService.createConfiguration(
                "test-config", "desc", List.of("3.6.0"), "props");
        assertThrows(AwsException.class, () ->
                mskService.describeConfigurationRevision(created.getArn(), 99L));
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

        assertEquals("auto.create.topics.enable=true",
                restored.getServerPropertiesByRevision().get(1L));
    }

    // A configuration persisted by the pre-revision-history schema stored "latestRevision" and
    // a flat "serverProperties" instead of a revision list. Both are mapped back onto revision 1
    // as it loads - otherwise the entry would come back with getLatestRevision() == null, and
    // AWS marks Configuration's LatestRevision required, so DescribeConfiguration and
    // ListConfigurations would emit a required member as an explicit null for it.
    @Test
    void preRevisionHistorySchemaMigratesOntoRevisionOne() throws Exception {
        String oldSchemaJson = """
                {
                  "arn": "arn:aws:kafka:us-east-1:000000000000:configuration/legacy/id",
                  "name": "legacy",
                  "description": "desc",
                  "kafkaVersions": ["3.6.0"],
                  "state": "ACTIVE",
                  "creationTime": 1700000000,
                  "latestRevision": {"revision": 1, "creationTime": 1700000000, "description": "desc"},
                  "serverProperties": "auto.create.topics.enable=true"
                }
                """;

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MskConfiguration restored = mapper.readValue(oldSchemaJson, MskConfiguration.class);

        assertEquals("legacy", restored.getName());
        assertNotNull(restored.getLatestRevision());
        assertEquals(1L, restored.getLatestRevision().getRevision());
        assertEquals("desc", restored.getLatestRevision().getDescription());
        assertEquals(1, restored.getRevisions().size());
        assertEquals("auto.create.topics.enable=true",
                restored.getServerPropertiesByRevision().get(1L));
    }

    // Jackson calls the two legacy setters in whatever order the keys sit in the file, so the
    // migration cannot assume "serverProperties" is read before "latestRevision". Same fixture
    // as above with the two keys swapped.
    @Test
    void preRevisionHistorySchemaMigratesRegardlessOfKeyOrder() throws Exception {
        String oldSchemaJson = """
                {
                  "serverProperties": "auto.create.topics.enable=true",
                  "latestRevision": {"revision": 1, "creationTime": 1700000000, "description": "desc"},
                  "arn": "arn:aws:kafka:us-east-1:000000000000:configuration/legacy/id",
                  "name": "legacy",
                  "kafkaVersions": ["3.6.0"],
                  "state": "ACTIVE",
                  "creationTime": 1700000000
                }
                """;

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MskConfiguration restored = mapper.readValue(oldSchemaJson, MskConfiguration.class);

        assertEquals(1, restored.getRevisions().size());
        assertEquals("auto.create.topics.enable=true",
                restored.getServerPropertiesByRevision().get(1L));
    }

    // serverPropertiesByRevision is a ConcurrentHashMap, which rejects null values, and
    // DescribeConfigurationRevision base64-encodes what it finds without a null check - so a
    // legacy entry missing serverProperties has to migrate to an empty string, not a null.
    @Test
    void preRevisionHistorySchemaWithoutServerPropertiesMigratesToEmptyProperties() throws Exception {
        String oldSchemaJson = """
                {
                  "arn": "arn:aws:kafka:us-east-1:000000000000:configuration/legacy/id",
                  "name": "legacy",
                  "kafkaVersions": ["3.6.0"],
                  "state": "ACTIVE",
                  "creationTime": 1700000000,
                  "latestRevision": {"revision": 1, "creationTime": 1700000000}
                }
                """;

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MskConfiguration restored = mapper.readValue(oldSchemaJson, MskConfiguration.class);

        assertEquals(1L, restored.getLatestRevision().getRevision());
        assertEquals("", restored.getServerPropertiesByRevision().get(1L));
    }

    // The migration adds setters for the two old key names; it must not also start writing them
    // back out, or a file saved today would load as a legacy entry tomorrow.
    @Test
    void migrationDoesNotReintroduceLegacyKeysOnSerialization() throws Exception {
        MskConfiguration created = mskService.createConfiguration(
                "round-trip", "v1", List.of("3.6.0"), "props-v1");

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(created);

        assertFalse(json.contains("latestRevision"));
        assertFalse(json.contains("legacyServerProperties"));
        assertFalse(json.contains("legacyRevisionNumber"));
        // The per-revision map keeps its own name; only the flat legacy key must be gone.
        assertTrue(json.contains("serverPropertiesByRevision"));
    }

    // The whole point of the migration: a pre-revision-history entry is updatable again rather
    // than being rejected for the rest of its life.
    @Test
    void updateConfigurationOnMigratedPreRevisionHistoryEntryAppendsRevisionTwo() throws Exception {
        String oldSchemaJson = """
                {
                  "arn": "arn:aws:kafka:us-east-1:000000000000:configuration/legacy/id",
                  "name": "legacy",
                  "kafkaVersions": ["3.6.0"],
                  "state": "ACTIVE",
                  "creationTime": 1700000000,
                  "latestRevision": {"revision": 1, "creationTime": 1700000000},
                  "serverProperties": "props"
                }
                """;
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MskConfiguration legacy = mapper.readValue(oldSchemaJson, MskConfiguration.class);
        // Seed the service's own backing store directly - the point of this test is what
        // happens when MskService reads back an already-persisted legacy entry, not what
        // happens to an object that was merely deserialized in isolation.
        configurationStorage.put(legacy.getArn(), legacy);

        MskConfiguration updated = mskService.updateConfiguration(legacy.getArn(), "new desc", "new-props");

        assertEquals(2L, updated.getLatestRevision().getRevision());
        assertEquals(2, updated.getRevisions().size());
        // Revision 1's migrated properties survive the update rather than being overwritten.
        assertEquals("props", updated.getServerPropertiesByRevision().get(1L));
        assertEquals("new-props", updated.getServerPropertiesByRevision().get(2L));
    }

    // Nothing the emulator has ever written looks like this, but the guard in
    // updateConfiguration still has to hold for a store that carries no revision data at all -
    // hand-edited, or an entry whose latestRevision was explicitly null.
    @Test
    void updateConfigurationWithNoRevisionDataAtAllStillThrowsInsteadOfNpe() throws Exception {
        String noRevisionJson = """
                {
                  "arn": "arn:aws:kafka:us-east-1:000000000000:configuration/legacy/id",
                  "name": "legacy",
                  "kafkaVersions": ["3.6.0"],
                  "state": "ACTIVE",
                  "creationTime": 1700000000,
                  "latestRevision": null
                }
                """;
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MskConfiguration broken = mapper.readValue(noRevisionJson, MskConfiguration.class);
        assertNull(broken.getLatestRevision());
        configurationStorage.put(broken.getArn(), broken);

        AwsException ex = assertThrows(AwsException.class, () -> {
            mskService.updateConfiguration(broken.getArn(), "new desc", "new-props");
        });
        assertEquals("BadRequestException", ex.getErrorCode());
    }

    @Test
    void concurrentUpdatesProduceDistinctRevisionsWithoutOverwritingServerProperties() throws InterruptedException {
        MskConfiguration created = mskService.createConfiguration(
                "concurrent-config", "v0", List.of("3.6.0"), "props-v0");

        int updates = 16;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < updates; i++) {
            int idx = i;
            pool.submit(() -> mskService.updateConfiguration(created.getArn(), "v" + idx, "props-" + idx));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "updates did not finish");

        MskConfiguration finalState = mskService.describeConfiguration(created.getArn());
        List<Long> revisionNumbers = finalState.getRevisions().stream()
                .map(ConfigurationRevision::getRevision).toList();

        assertEquals(1 + updates, revisionNumbers.size(), "revision count");
        assertEquals(revisionNumbers.size(), new HashSet<>(revisionNumbers).size(),
                "duplicate revision numbers were assigned");
        for (long revision : revisionNumbers) {
            assertNotNull(finalState.getServerPropertiesByRevision().get(revision),
                    "revision " + revision + " lost its serverProperties");
        }
    }

    // ── Tags ─────────────────────────────────────────────────────────────────────────────

    @Test
    void tagAndUntagResourceWorkOnClustersAndConfigurations() {
        MskCluster cluster = mskService.createCluster("tagged-cluster");
        mskService.tagResource(cluster.getClusterArn(), Map.of("Environment", "example"));
        assertEquals("example", mskService.listTagsForResource(cluster.getClusterArn()).get("Environment"));

        MskConfiguration configuration = mskService.createConfiguration(
                "tagged-config", "d", List.of("3.6.0"), "auto.create.topics.enable=true");
        mskService.tagResource(configuration.getArn(), Map.of("Owner", "platform"));
        assertEquals("platform", mskService.listTagsForResource(configuration.getArn()).get("Owner"));

        mskService.untagResource(cluster.getClusterArn(), List.of("Environment"));
        assertTrue(mskService.listTagsForResource(cluster.getClusterArn()).isEmpty());
    }

    // createCluster stores whatever map the request carried, which for an immutable Map.of would
    // reject an in-place putAll - the tag write has to copy instead.
    @Test
    void tagResourceCanExtendAnImmutableTagMapSetAtCreateTime() {
        CreateClusterRequest request = new CreateClusterRequest();
        request.setClusterName("immutable-tags-cluster");
        request.setTags(Map.of("Environment", "example"));
        MskCluster cluster = mskService.createCluster(request);

        mskService.tagResource(cluster.getClusterArn(), Map.of("Team", "data"));

        Map<String, String> tags = mskService.listTagsForResource(cluster.getClusterArn());
        assertEquals("example", tags.get("Environment"));
        assertEquals("data", tags.get("Team"));
    }

    @Test
    void tagOperationsOnAnUnknownArnAreNotFound() {
        String missing = "arn:aws:kafka:us-east-1:000000000000:cluster/nope/id";
        assertThrows(AwsException.class, () -> mskService.listTagsForResource(missing));
        assertThrows(AwsException.class, () -> mskService.tagResource(missing, Map.of("a", "b")));
        assertThrows(AwsException.class, () -> mskService.untagResource(missing, List.of("a")));
    }

    @Test
    void configurationKeepsTagsAndAccountIdAcrossTheStorageMapperRoundTrip() throws Exception {
        MskConfiguration configuration = mskService.createConfiguration(
                "persisted-config", "d", List.of("3.6.0"), "auto.create.topics.enable=true");
        mskService.tagResource(configuration.getArn(), Map.of("Owner", "platform"));
        MskConfiguration tagged = mskService.describeConfiguration(configuration.getArn());

        ObjectMapper storageMapper = new ObjectMapper();
        storageMapper.registerModule(new JavaTimeModule());
        storageMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MskConfiguration reloaded = storageMapper.readValue(
                storageMapper.writeValueAsString(tagged), MskConfiguration.class);

        assertEquals("platform", reloaded.getTags().get("Owner"));
        assertEquals(tagged.getAccountId(), reloaded.getAccountId());
        assertNotNull(reloaded.getAccountId());
    }

    // ── CreateCluster validation ─────────────────────────────────────────────────────────

    @Test
    void createClusterRejectsAMissingOrOversizedClusterName() {
        CreateClusterRequest noName = new CreateClusterRequest();
        noName.setKafkaVersion("3.6.0");
        assertThrows(AwsException.class, () -> mskService.createCluster(noName));

        CreateClusterRequest longName = new CreateClusterRequest();
        longName.setClusterName("c".repeat(65));
        assertThrows(AwsException.class, () -> mskService.createCluster(longName));
    }

    @Test
    void createClusterRejectsOnlyNonsensicalBrokerCountsAndOutOfRangeVolumeSize() {
        // No upper bound: the SDK model says 15, but the REST reference documents no maximum and
        // the quota page allows 30 (ZooKeeper) / 60 (KRaft), adjustable upward.
        CreateClusterRequest manyBrokers = new CreateClusterRequest();
        manyBrokers.setClusterName("many-brokers");
        manyBrokers.setNumberOfBrokerNodes(30);
        assertEquals(30, mskService.createCluster(manyBrokers).getNumberOfBrokerNodes());

        CreateClusterRequest tooFew = new CreateClusterRequest();
        tooFew.setClusterName("too-few");
        tooFew.setNumberOfBrokerNodes(0);
        assertThrows(AwsException.class, () -> mskService.createCluster(tooFew));

        CreateClusterRequest badVolume = new CreateClusterRequest();
        badVolume.setClusterName("bad-volume");
        EbsStorageInfo ebs = new EbsStorageInfo();
        ebs.setVolumeSize(16385);
        StorageInfo storageInfo = new StorageInfo();
        storageInfo.setEbsStorageInfo(ebs);
        BrokerNodeGroupInfo nodeGroup = new BrokerNodeGroupInfo();
        nodeGroup.setStorageInfo(storageInfo);
        badVolume.setBrokerNodeGroupInfo(nodeGroup);
        assertThrows(AwsException.class, () -> mskService.createCluster(badVolume));
    }

    @Test
    void createClusterRejectsUnknownEnumValuesButAcceptsTheDocumentedOnes() {
        CreateClusterRequest bad = new CreateClusterRequest();
        bad.setClusterName("bad-monitoring");
        bad.setEnhancedMonitoring("SOMETIMES");
        assertThrows(AwsException.class, () -> mskService.createCluster(bad));

        CreateClusterRequest good = new CreateClusterRequest();
        good.setClusterName("good-monitoring");
        good.setEnhancedMonitoring("PER_TOPIC_PER_PARTITION");
        good.setStorageMode("TIERED");
        assertEquals("PER_TOPIC_PER_PARTITION", mskService.createCluster(good).getEnhancedMonitoring());
    }

    // ── Serverless ───────────────────────────────────────────────────────────────────────

    @Test
    void createClusterV2CreatesAServerlessClusterWithoutProvisionedMetadata() {
        VpcConfig vpcConfig = new VpcConfig();
        vpcConfig.setSubnetIds(List.of("subnet-aaa"));
        Serverless serverless = new Serverless();
        serverless.setVpcConfigs(List.of(vpcConfig));

        CreateClusterV2Request request = new CreateClusterV2Request();
        request.setClusterName("serverless-cluster");
        request.setServerless(serverless);

        MskCluster cluster = mskService.createCluster(request);

        assertTrue(mskService.isServerless(cluster));
        assertEquals("SERVERLESS", cluster.getClusterType());
        assertEquals(List.of("subnet-aaa"), cluster.getServerless().getVpcConfigs().get(0).getSubnetIds());
        assertNull(cluster.getBrokerNodeGroupInfo());
        assertNull(cluster.getCurrentBrokerSoftwareInfo());
        assertEquals(0, cluster.getNumberOfBrokerNodes());
    }

    @Test
    void v1ReadsExcludeServerlessClusters() {
        Serverless serverless = new Serverless();
        serverless.setVpcConfigs(List.of(new VpcConfig()));
        CreateClusterV2Request request = new CreateClusterV2Request();
        request.setClusterName("serverless-cluster");
        request.setServerless(serverless);
        MskCluster serverlessCluster = mskService.createCluster(request);

        mskService.createCluster("provisioned-cluster");

        assertThrows(AwsException.class, () -> mskService.describeClusterV1(serverlessCluster.getClusterArn()));
        assertEquals(2, mskService.listClusters().size());
        assertEquals(1, mskService.listProvisionedClusters().size());
        assertEquals("provisioned-cluster", mskService.listProvisionedClusters().get(0).getClusterName());
    }

    @Test
    void createClusterV2RejectsBothProvisionedAndServerless() {
        CreateClusterV2Request request = new CreateClusterV2Request();
        request.setClusterName("both-shapes");
        request.setProvisioned(new ProvisionedRequest());
        request.setServerless(new Serverless());

        assertThrows(AwsException.class, () -> mskService.createCluster(request));
    }
}
