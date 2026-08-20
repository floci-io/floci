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
import io.github.hectorvent.floci.services.msk.model.ConfigurationRevision;
import io.github.hectorvent.floci.services.msk.model.ConfigurationRevisionDetail;
import io.github.hectorvent.floci.services.msk.model.ConfigurationState;
import io.github.hectorvent.floci.services.msk.model.MskCluster;
import io.github.hectorvent.floci.services.msk.model.MskConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashSet;
import java.util.List;
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
    void updateConfigurationNotFoundThrows() {
        assertThrows(AwsException.class, () ->
                mskService.updateConfiguration(
                        "arn:aws:kafka:us-east-1:000000000000:configuration/missing/id", "desc", "props"));
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
}
