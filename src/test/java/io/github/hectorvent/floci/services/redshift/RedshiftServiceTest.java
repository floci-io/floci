package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.rds.proxy.PasswordValidator;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.model.StreamDescription;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.ClusterSubnetGroup;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import io.github.hectorvent.floci.services.redshift.model.Parameter;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import io.github.hectorvent.floci.services.redshift.model.SnapshotCopyGrant;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RedshiftServiceTest {

    private StorageFactory sf;
    private AccountAwareStorageBackend<Cluster> clusterBackend;
    private AccountAwareStorageBackend<Snapshot> snapshotBackend;
    private AccountAwareStorageBackend<String> snapshotDumpBackend;
    private AccountAwareStorageBackend<ClusterParameterGroup> parameterGroupBackend;
    private AccountAwareStorageBackend<ClusterSubnetGroup> subnetGroupBackend;
    private AccountAwareStorageBackend<SnapshotCopyGrant> snapshotCopyGrantBackend;
    private AccountAwareStorageBackend<Integration> integrationBackend;
    private RedshiftContainerManager cm;
    private RegionResolver regionResolver;
    private RedshiftProxyManager proxyManager;
    private DockerHostResolver dockerHostResolver;
    private RedshiftCredentialBroker credentialBroker;
    private SecretsManagerService secretsManagerService;
    private DynamoDbStreamService streamService;
    private RedshiftService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sf = mock(StorageFactory.class);
        clusterBackend = mock(AccountAwareStorageBackend.class);
        snapshotBackend = mock(AccountAwareStorageBackend.class);
        snapshotDumpBackend = mock(AccountAwareStorageBackend.class);
        parameterGroupBackend = mock(AccountAwareStorageBackend.class);
        subnetGroupBackend = mock(AccountAwareStorageBackend.class);
        snapshotCopyGrantBackend = mock(AccountAwareStorageBackend.class);
        integrationBackend = mock(AccountAwareStorageBackend.class);
        cm = mock(RedshiftContainerManager.class);
        proxyManager = mock(RedshiftProxyManager.class);
        dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.resolve()).thenReturn("localhost");

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.StorageConfig storageConfig = mock(EmulatorConfig.StorageConfig.class);
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.persistentPath()).thenReturn("target/test-data");

        EmulatorConfig.ServicesConfig servicesConfig =
                mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.RedshiftServiceConfig redshiftConfig =
                mock(EmulatorConfig.RedshiftServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.redshift()).thenReturn(redshiftConfig);
        when(redshiftConfig.proxyBasePort()).thenReturn(7100);
        when(redshiftConfig.proxyMaxPort()).thenReturn(7199);
        when(redshiftConfig.endpointHost()).thenReturn(Optional.empty());

        when(sf.<Cluster>create(eq("redshift"), eq("redshift-clusters.json"), any())).thenReturn(clusterBackend);
        when(sf.<Snapshot>create(eq("redshift"), eq("redshift-snapshots.json"), any())).thenReturn(snapshotBackend);
        when(sf.<ClusterParameterGroup>create(eq("redshift"), eq("redshift-parameter-groups.json"), any())).thenReturn(parameterGroupBackend);
        when(sf.<ClusterSubnetGroup>create(eq("redshift"), eq("redshift-subnet-groups.json"), any())).thenReturn(subnetGroupBackend);
        when(sf.<SnapshotCopyGrant>create(eq("redshift"), eq("redshift-snapshot-copy-grants.json"), any())).thenReturn(snapshotCopyGrantBackend);
        when(sf.<Integration>create(eq("redshift"), eq("redshift-integrations.json"), any())).thenReturn(integrationBackend);
        when(clusterBackend.accountId()).thenReturn("111111111111");

        regionResolver = new RegionResolver("us-east-1", "111111111111");

        credentialBroker = new RedshiftCredentialBroker();
        secretsManagerService = mock(SecretsManagerService.class);
        streamService = mock(DynamoDbStreamService.class);

        service = new RedshiftService(sf, cm, config, regionResolver, proxyManager, dockerHostResolver,
                credentialBroker, secretsManagerService, new com.fasterxml.jackson.databind.ObjectMapper(),
                streamService);
    }

    @Test
    void createDynamoDbZeroEtlIntegrationRequiresExistingProvisionedResources() {
        String streamArn = "arn:aws:dynamodb:us-east-1:111111111111:table/orders/stream/2026-09-18T00:00:00.000";
        String targetArn = "arn:aws:redshift:us-east-1:111111111111:cluster:warehouse";
        StreamDescription stream = new StreamDescription();
        stream.setStreamArn(streamArn);
        stream.setTableName("orders");
        stream.setStreamStatus("ENABLED");
        when(streamService.describeStream(streamArn)).thenReturn(stream);
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("warehouse");
        when(clusterBackend.get("warehouse")).thenReturn(Optional.of(cluster));
        when(integrationBackend.scan(any())).thenReturn(List.of());

        Integration integration = service.createIntegration("orders-to-warehouse", streamArn, targetArn,
                null, null, Map.of(), Map.of(), "us-east-1");

        assertEquals(streamArn, integration.getSourceStreamArn());
        assertEquals("warehouse", integration.getTargetClusterIdentifier());
        assertNotNull(integration.getLandingTableName());
        assertEquals("syncing", integration.getStatus());
        assertFalse(integration.isBackfillCompleted());
    }

    @Test
    void updateIntegrationBackfillProgressPersistsCheckpointAndFlipsStatusOnCompletion() {
        String streamArn = "arn:aws:dynamodb:us-east-1:111111111111:table/orders/stream/2026-09-18T00:00:00.000";
        String targetArn = "arn:aws:redshift:us-east-1:111111111111:cluster:warehouse";
        StreamDescription stream = new StreamDescription();
        stream.setStreamArn(streamArn);
        stream.setTableName("orders");
        stream.setStreamStatus("ENABLED");
        when(streamService.describeStream(streamArn)).thenReturn(stream);
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("warehouse");
        when(clusterBackend.get("warehouse")).thenReturn(Optional.of(cluster));
        when(integrationBackend.scan(any())).thenReturn(List.of());

        Integration integration = service.createIntegration("orders-to-warehouse", streamArn, targetArn,
                null, null, Map.of(), Map.of(), "us-east-1");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(integrationBackend).put(keyCaptor.capture(), eq(integration));
        String storageKey = keyCaptor.getValue();
        when(integrationBackend.keysForAccount("111111111111")).thenReturn(Set.of(storageKey));
        when(integrationBackend.getForAccount("111111111111", storageKey)).thenReturn(Optional.of(integration));

        service.updateIntegrationBackfillProgress("111111111111", integration.getIntegrationArn(),
                "{\"id\":{\"S\":\"1\"}}", false);
        assertEquals("syncing", integration.getStatus());
        assertEquals("{\"id\":{\"S\":\"1\"}}", integration.getBackfillLastEvaluatedKey());
        assertFalse(integration.isBackfillCompleted());

        service.updateIntegrationBackfillProgress("111111111111", integration.getIntegrationArn(), null, true);
        assertEquals("active", integration.getStatus());
        assertNull(integration.getBackfillLastEvaluatedKey());
        assertTrue(integration.isBackfillCompleted());
    }

    @Test
    void updateIntegrationBackfillProgressOnUnknownIntegrationThrows() {
        when(integrationBackend.keysForAccount("111111111111")).thenReturn(Set.of());

        assertThrows(AwsException.class, () -> service.updateIntegrationBackfillProgress(
                "111111111111", "arn:aws:redshift:us-east-1:111111111111:integration:missing", null, true));
    }

    @Test
    void createDynamoDbZeroEtlIntegrationRejectsServerlessTarget() {
        assertThrows(AwsException.class, () -> service.createIntegration(
                "orders-to-serverless",
                "arn:aws:dynamodb:us-east-1:111111111111:table/orders/stream/2026-09-18T00:00:00.000",
                "arn:aws:redshift-serverless:us-east-1:111111111111:workgroup/analytics",
                null, null, Map.of(), Map.of(), "us-east-1"));
    }

    /** Absolute dump path as {@code createSnapshot} now stores it: under {@code <persistentPath>/redshift-dumps/<accountId>}. */
    private static String dumpPath(String snapshotId) {
        return Paths.get("target/test-data", "redshift-dumps", "111111111111", snapshotId + ".sql")
                .toAbsolutePath().normalize().toString();
    }

    @Test
    void managedMasterPasswordCreatesOwnedSecretAndExposesItsMetadata() {
        when(cm.start(eq("111111111111"), eq("managed-cluster"), eq("admin"), anyString()))
                .thenReturn(new RedshiftContainerHandle("container", "managed-cluster", "localhost", 5432));

        Secret secret = new Secret();
        secret.setArn("arn:aws:secretsmanager:us-east-1:111111111111:secret:redshift-managed");
        secret.setCurrentVersionId("version-1");
        when(secretsManagerService.createSecret(eq("redshift/managed-cluster"), anyString(), isNull(),
                anyString(), eq("arn:aws:kms:us-east-1:111111111111:key/key-1"), anyList(),
                eq("redshift"), eq("us-east-1"))).thenReturn(secret);

        Cluster cluster = service.createClusterWithManagedMasterPassword("managed-cluster", "dc2.large",
                "admin", null, List.of(), List.of(),
                "arn:aws:kms:us-east-1:111111111111:key/key-1", "us-east-1");

        assertEquals(secret.getArn(), cluster.getMasterPasswordSecretArn());
        assertEquals("arn:aws:kms:us-east-1:111111111111:key/key-1", cluster.getMasterPasswordSecretKmsKeyId());
        verify(secretsManagerService).createSecret(eq("redshift/managed-cluster"),
                contains("\"username\":\"admin\""), isNull(), anyString(),
                eq("arn:aws:kms:us-east-1:111111111111:key/key-1"), anyList(), eq("redshift"), eq("us-east-1"));
    }

    @Test
    void managedMasterPasswordRollsBackClusterWhenSecretCreationFails() {
        when(cm.start(eq("111111111111"), eq("failed-managed-cluster"), eq("admin"), anyString()))
                .thenReturn(new RedshiftContainerHandle("container", "failed-managed-cluster", "localhost", 5432));
        when(secretsManagerService.createSecret(eq("redshift/failed-managed-cluster"), anyString(), isNull(),
                anyString(), isNull(), anyList(), eq("redshift"), eq("us-east-1")))
                .thenThrow(new AwsException("InternalFailure", "secret store unavailable", 500));

        assertThrows(AwsException.class, () -> service.createClusterWithManagedMasterPassword(
                "failed-managed-cluster", "dc2.large", "admin", null, List.of(), List.of(), null, "us-east-1"));

        verify(cm).stop("111111111111", "failed-managed-cluster");
        verify(clusterBackend).delete("failed-managed-cluster");
        assertThrows(AwsException.class, () -> service.describeClusters("failed-managed-cluster"));
    }

    @Test
    void onStartRecreatesContainersAcrossAccounts() {
        Cluster clusterA = new Cluster();
        clusterA.setClusterIdentifier("cluster-a");
        clusterA.setMasterUsername("admin");
        clusterA.setMasterPassword("pw-a");
        clusterA.setClusterStatus("available");

        Cluster clusterB = new Cluster();
        clusterB.setClusterIdentifier("cluster-b");
        clusterB.setMasterUsername("admin");
        clusterB.setMasterPassword("pw-b");
        clusterB.setClusterStatus("available");

        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "cluster-a", clusterA),
                new AccountAwareStorageBackend.AccountEntry<>("222222222222", "cluster-b", clusterB)));
        when(cm.getContainer(anyString(), anyString())).thenReturn(Optional.empty());
        when(cm.adoptOrStart(eq("111111111111"), eq("cluster-a"), eq("admin"), eq("pw-a")))
                .thenReturn(new RedshiftContainerHandle("c-a", "cluster-a", "localhost", 5432));
        when(cm.adoptOrStart(eq("222222222222"), eq("cluster-b"), eq("admin"), eq("pw-b")))
                .thenReturn(new RedshiftContainerHandle("c-b", "cluster-b", "localhost", 5433));

        service.onStart(null);

        // Cluster owned by a second, non-default account must also be recovered
        verify(cm).adoptOrStart("111111111111", "cluster-a", "admin", "pw-a");
        verify(cm).adoptOrStart("222222222222", "cluster-b", "admin", "pw-b");
        verify(clusterBackend).putForAccount(eq("111111111111"), eq("cluster-a"), any(Cluster.class));
        verify(clusterBackend).putForAccount(eq("222222222222"), eq("cluster-b"), any(Cluster.class));
        verify(clusterBackend).flush();
    }

    @Test
    void onStartSkipsClusterWithRunningContainer() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("cluster-a");
        cluster.setClusterStatus("available");
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "cluster-a", cluster)));
        when(cm.getContainer("111111111111", "cluster-a"))
                .thenReturn(Optional.of(new RedshiftContainerHandle("c-a", "cluster-a", "localhost", 5432)));

        service.onStart(null);

        verify(cm, never()).adoptOrStart(any(), any(), any(), any());
        verify(clusterBackend, never()).putForAccount(any(), any(), any());
    }

    @Test
    void onStartMarksClusterUnavailableOnStartFailure() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("cluster-a");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("pw-a");
        cluster.setClusterStatus("available");
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "cluster-a", cluster)));
        when(cm.getContainer("111111111111", "cluster-a")).thenReturn(Optional.empty());
        when(cm.adoptOrStart(eq("111111111111"), eq("cluster-a"), eq("admin"), eq("pw-a"))).thenThrow(new RuntimeException("docker down"));

        service.onStart(null);

        ArgumentCaptor<Cluster> captor = ArgumentCaptor.forClass(Cluster.class);
        verify(clusterBackend).putForAccount(eq("111111111111"), eq("cluster-a"), captor.capture());
        assertEquals("unavailable", captor.getValue().getClusterStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onStartRestartsTheProxyForAnAdoptedCluster() {
        Cluster persisted = new Cluster();
        persisted.setClusterIdentifier("c1");
        persisted.setMasterUsername("admin");
        persisted.setMasterPassword("Secret123");
        persisted.setClusterStatus("available");
        persisted.setProxyPort(7108);

        AccountAwareStorageBackend.AccountEntry<Cluster> entry = mock(AccountAwareStorageBackend.AccountEntry.class);
        when(entry.value()).thenReturn(persisted);
        when(entry.accountId()).thenReturn("111111111111");
        when(entry.key()).thenReturn("c1");
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(entry));
        when(cm.getContainer("111111111111", "c1")).thenReturn(Optional.empty());
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.12");
        when(handle.getPort()).thenReturn(32830);
        when(cm.adoptOrStart("111111111111", "c1", "admin", "Secret123")).thenReturn(handle);

        service.onStart(null);

        verify(proxyManager).startProxy(eq("111111111111:c1"), eq(7108),
                eq("172.17.0.12"), eq(32830), eq("localhost"),
                eq("admin"), eq("Secret123"), eq("dev"), any(), any());
    }

    @Test
    void createCluster() {
        when(clusterBackend.get(anyString())).thenReturn(Optional.empty());
        when(cm.start(any(), any(), any(), any())).thenReturn(new RedshiftContainerHandle("c1", "my-cluster", "localhost", 5432));

        Cluster cluster = service.createCluster("my-cluster", "dc2.large", "admin", "password123");
        assertNotNull(cluster);
        assertEquals("my-cluster", cluster.getClusterIdentifier());
        assertEquals("available", cluster.getClusterStatus());
        assertEquals("localhost", cluster.getEndpoint().getAddress());
        verify(clusterBackend, times(2)).put(eq("my-cluster"), any(Cluster.class));
    }

    @Test
    void createClusterWithVpcMetadata() {
        when(clusterBackend.get(anyString())).thenReturn(Optional.empty());
        when(cm.start(any(), any(), any(), any())).thenReturn(new RedshiftContainerHandle("c1", "my-cluster", "localhost", 5432));

        Cluster cluster = service.createCluster("my-cluster", "dc2.large", "admin", "password123",
                "my-subnet-group", List.of("sg-1", "sg-2"));

        assertEquals("my-subnet-group", cluster.getClusterSubnetGroupName());
        assertEquals(List.of("sg-1", "sg-2"), cluster.getVpcSecurityGroupIds());
    }

    @Test
    void createClusterStartsAProxyAndAdvertisesTheProxyEndpoint() {
        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        when(clusterBackend.accountId()).thenReturn("111111111111");
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.9");
        when(handle.getPort()).thenReturn(32800);
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123"))).thenReturn(handle);

        Cluster cluster = service.createCluster("c1", "dc2.large", "admin", "Secret123");

        // Endpoint is the proxy, not the container.
        assertEquals("localhost", cluster.getEndpoint().getAddress());
        assertTrue(cluster.getEndpoint().getPort() >= 7100 && cluster.getEndpoint().getPort() <= 7199);
        assertEquals("172.17.0.9", cluster.getContainerHost());
        assertEquals(32800, cluster.getContainerPort());
        assertEquals(cluster.getEndpoint().getPort(), cluster.getProxyPort());

        verify(proxyManager).startProxy(eq("111111111111:c1"), eq(cluster.getProxyPort()),
                eq("172.17.0.9"), eq(32800), eq("localhost"),
                eq("admin"), eq("Secret123"), eq("dev"), any(), any());
    }

    @Test
    void createClusterAlreadyExists() {
        when(clusterBackend.get("existing-cluster")).thenReturn(Optional.of(new Cluster()));

        assertThrows(AwsException.class, () ->
                service.createCluster("existing-cluster", "dc2.large", "admin", "password123"));
    }

    @Test
    void createClusterRemovesMetadataOnFailure() {
        when(clusterBackend.accountId()).thenReturn("111111111111");
        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        // Simulate a concurrent GetClusterCredentials landing while the row exists, then fail
        // container startup so the rollback path runs.
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("password123")))
                .thenAnswer(inv -> {
                    credentialBroker.issue("111111111111", "c1", "analyst", List.of(), 900);
                    throw new RuntimeException("startup failed");
                });

        assertThrows(AwsException.class, () ->
                service.createCluster("c1", "dc2.large", "admin", "password123"));

        verify(clusterBackend).delete("c1");
        verify(clusterBackend, atLeastOnce()).flush();
        // The rollback that removes the cluster row must also drop that credential.
        assertTrue(credentialBroker.resolve("111111111111", "c1", "analyst").isEmpty());
    }

    @Test
    void createClusterKeepsMetadataWhenProxyStopFails() {
        when(clusterBackend.accountId()).thenReturn("111111111111");
        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("password123")))
                .thenThrow(new RuntimeException("startup failed"));
        doThrow(new RuntimeException("proxy stop failed"))
                .when(proxyManager).stopProxy("111111111111:c1");

        assertThrows(AwsException.class, () ->
                service.createCluster("c1", "dc2.large", "admin", "password123"));

        verify(clusterBackend, never()).delete("c1");
        verify(clusterBackend, atLeast(1)).put(eq("c1"), argThat(c -> "failed".equals(c.getClusterStatus())));
        verify(clusterBackend, atLeastOnce()).flush();
    }

    @Test
    void restoreFromClusterSnapshotRemovesMetadataOnFailure() {
        when(clusterBackend.accountId()).thenReturn("111111111111");
        Snapshot snapshot = new Snapshot();
        snapshot.setSnapshotIdentifier("snap-1");
        snapshot.setClusterIdentifier("source-c1");
        snapshot.setMasterUsername("admin");
        snapshot.setMasterPassword("password123");
        snapshot.setSqlDump(null);

        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("password123")))
                .thenThrow(new RuntimeException("startup failed"));

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("c1", "snap-1"));

        verify(clusterBackend).delete("c1");
        verify(clusterBackend, atLeastOnce()).flush();
    }

    @Test
    void restoreFromClusterSnapshotKeepsMetadataWhenProxyStopFails() {
        when(clusterBackend.accountId()).thenReturn("111111111111");
        Snapshot snapshot = new Snapshot();
        snapshot.setSnapshotIdentifier("snap-1");
        snapshot.setClusterIdentifier("source-c1");
        snapshot.setMasterUsername("admin");
        snapshot.setMasterPassword("password123");
        snapshot.setSqlDump(null);

        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("password123")))
                .thenThrow(new RuntimeException("startup failed"));
        doThrow(new RuntimeException("proxy stop failed"))
                .when(proxyManager).stopProxy("111111111111:c1");

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("c1", "snap-1"));

        verify(clusterBackend, never()).delete("c1");
        verify(clusterBackend, atLeast(1)).put(eq("c1"), argThat(c -> "failed".equals(c.getClusterStatus())));
        verify(clusterBackend, atLeastOnce()).flush();
    }

    @Test
    void describeClusters() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("test-c");
        when(clusterBackend.get("test-c")).thenReturn(Optional.of(c));

        List<Cluster> list = service.describeClusters("test-c");
        assertEquals(1, list.size());
        assertEquals("test-c", list.get(0).getClusterIdentifier());
    }

    @Test
    void describeClustersForAccountUsesTheRequestedAccount() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-c");
        when(clusterBackend.getForAccount("222222222222", "test-c")).thenReturn(Optional.of(cluster));

        List<Cluster> list = service.describeClustersForAccount("222222222222", "test-c");

        assertEquals(List.of(cluster), list);
        verify(clusterBackend).getForAccount("222222222222", "test-c");
        verify(clusterBackend, never()).get("test-c");
    }

    @Test
    void deleteCluster() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("test-c");
        when(clusterBackend.get("test-c")).thenReturn(Optional.of(c));

        Cluster deleted = service.deleteCluster("test-c");
        assertEquals("deleting", deleted.getClusterStatus());
        verify(cm).stop("111111111111", "test-c");
        verify(clusterBackend).delete("test-c");
    }

    @Test
    void deleteClusterRevokesItsGetClusterCredentialsCredentials() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("test-c");
        when(clusterBackend.get("test-c")).thenReturn(Optional.of(c));
        credentialBroker.issue("111111111111", "test-c", "analyst", List.of(), 900);

        service.deleteCluster("test-c");

        assertTrue(credentialBroker.resolve("111111111111", "test-c", "analyst").isEmpty());
    }

    @Test
    void deleteClusterAbortsAndKeepsMetadataWhenTheProxyWontStop() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("test-c");
        c.setProxyPort(7107);
        when(clusterBackend.get("test-c")).thenReturn(Optional.of(c));
        doThrow(new RuntimeException("listener close failed"))
                .when(proxyManager).stopProxy("111111111111:test-c");

        AwsException ex = assertThrows(AwsException.class, () -> service.deleteCluster("test-c"));
        assertEquals("InternalFailure", ex.getErrorCode());

        // Container and metadata are left intact so the deletion can be retried.
        verify(cm, never()).stop(anyString(), anyString());
        verify(clusterBackend, never()).delete(anyString());
    }

    @Test
    void deleteClusterRetrySucceedsOnceTheProxyStops() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("test-c");
        c.setProxyPort(7107);
        when(clusterBackend.get("test-c")).thenReturn(Optional.of(c));
        doThrow(new RuntimeException("listener close failed"))
                .doNothing()
                .when(proxyManager).stopProxy("111111111111:test-c");

        assertThrows(AwsException.class, () -> service.deleteCluster("test-c"));
        Cluster deleted = service.deleteCluster("test-c");

        assertEquals("deleting", deleted.getClusterStatus());
        verify(cm).stop("111111111111", "test-c");
        verify(clusterBackend).delete("test-c");
    }

    @Test
    void rebootClusterDumpsAndRestoresData() throws Exception {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("pw");
        cluster.setClusterStatus("available");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));
        when(cm.start(eq("111111111111"), eq("my-cluster"), eq("admin"), eq("pw")))
                .thenReturn(new RedshiftContainerHandle("c-rebooted", "my-cluster", "localhost", 5555));
        doAnswer(invocation -> {
            Path dumpFile = invocation.getArgument(3);
            Files.writeString(dumpFile, "-- dump");
            return null;
        }).when(cm).takeSnapshot(eq("111111111111"), eq("my-cluster"), eq("admin"), any(Path.class));

        Cluster rebooted = service.rebootCluster("my-cluster");

        assertEquals("available", rebooted.getClusterStatus());
        // Endpoint now advertises the auth proxy; the restarted container is tracked separately.
        assertEquals(5555, rebooted.getContainerPort());
        assertTrue(rebooted.getEndpoint().getPort() >= 7100 && rebooted.getEndpoint().getPort() <= 7199);
        verify(cm).stop("111111111111", "my-cluster");
        verify(cm).start("111111111111", "my-cluster", "admin", "pw");
        verify(cm).restoreSnapshot(eq("111111111111"), eq("my-cluster"), eq("admin"), any(Path.class));
    }

    @Test
    void rebootClusterRestartsTheProxyOnTheSamePort() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        cluster.setEndpoint(new Endpoint("localhost", 7107));
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(clusterBackend.accountId()).thenReturn("111111111111");
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.11");
        when(handle.getPort()).thenReturn(32820);
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123"))).thenReturn(handle);

        Cluster rebooted = service.rebootCluster("c1");

        assertEquals(7107, rebooted.getEndpoint().getPort());
        assertEquals("localhost", rebooted.getEndpoint().getAddress());
        assertEquals("172.17.0.11", rebooted.getContainerHost());
        verify(proxyManager).stopProxy("111111111111:c1");
        verify(proxyManager).startProxy(eq("111111111111:c1"), eq(7107),
                eq("172.17.0.11"), eq(32820), eq("localhost"),
                eq("admin"), eq("Secret123"), eq("dev"), any(), any());
    }

    @Test
    void rebootClusterNotFound() {
        when(clusterBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.rebootCluster("missing"));
    }

    @Test
    void rebootClusterTearsDownTheProxyWhenRestoreFails() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.11");
        when(handle.getPort()).thenReturn(32820);
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123"))).thenReturn(handle);
        doThrow(new RuntimeException("restore boom"))
                .when(cm).restoreSnapshot(eq("111111111111"), eq("c1"), eq("admin"), any(Path.class));

        assertThrows(AwsException.class, () -> service.rebootCluster("c1"));

        // The proxy started during the reboot must be stopped again on failure, and the
        // replacement container must be stopped too, once before the restart, once in
        // rollback, so it is not left running behind a "failed" cluster.
        verify(proxyManager).startProxy(eq("111111111111:c1"), eq(7107), any(), anyInt(),
                any(), any(), any(), any(), any(), any());
        verify(proxyManager, times(2)).stopProxy("111111111111:c1");
        verify(cm, times(2)).stop("111111111111", "c1");
    }

    @Test
    void rebootClusterLeavesTheOriginalContainerAloneWhenTheDumpFails() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        doThrow(new RuntimeException("dump boom"))
                .when(cm).takeSnapshot(eq("111111111111"), eq("c1"), eq("admin"), any(Path.class));

        assertThrows(AwsException.class, () -> service.rebootCluster("c1"));

        // The dump failed before the original was torn down: rollback must not stop the
        // still-running original container or touch its proxy.
        verify(cm, never()).stop(anyString(), anyString());
        verify(proxyManager, never()).stopProxy(anyString());
    }

    @Test
    void rebootClusterRemovesTheReplacementWhenItsStartupThrows() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123")))
                .thenThrow(new RuntimeException("readiness timed out"));

        assertThrows(AwsException.class, () -> service.rebootCluster("c1"));

        // start() can create the container before throwing (readiness check); the
        // original is already gone, so rollback removes anything under the name:
        // once for the original teardown, once for the possible orphan.
        verify(cm, times(2)).stop("111111111111", "c1");

        // The reserved port belongs to the cluster and is not released by reboot rollback;
        // it is released only when the cluster is successfully deleted.
        assertEquals(7107, cluster.getProxyPort());
    }

    @Test
    void rebootClusterKeepsProxyPortWhenRollbackStopFails() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        when(clusterBackend.accountId()).thenReturn("111111111111");
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123")))
                .thenThrow(new RuntimeException("readiness timed out"));
        
        // The first call stops the proxy during takeSnapshot; the second call happens in rollback.
        doNothing().doThrow(new RuntimeException("stop failed")).when(proxyManager).stopProxy(anyString());

        assertThrows(AwsException.class, () -> service.rebootCluster("c1"));

        assertEquals(7107, cluster.getProxyPort());
    }

    @Test
    void createSnapshot() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("secret-pw");
        cluster.setEndpoint(new Endpoint("localhost", 5439));

        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.empty());
        doNothing().when(cm).takeSnapshot(eq("111111111111"), eq("my-cluster"), eq("admin"), any(Path.class));

        Snapshot snapshot = service.createSnapshot("my-snapshot", "my-cluster");
        assertNotNull(snapshot);
        assertEquals("my-snapshot", snapshot.getSnapshotIdentifier());
        assertEquals("my-cluster", snapshot.getClusterIdentifier());
        assertEquals("available", snapshot.getStatus());
        assertEquals("admin", snapshot.getMasterUsername());
        // Password captured at snapshot time so restore can recover it after the source cluster is gone
        assertEquals("secret-pw", snapshot.getMasterPassword());
        assertEquals(5439, snapshot.getPort());
        assertEquals("arn:aws:redshift:us-east-1:111111111111:snapshot:my-cluster/my-snapshot",
                snapshot.getSnapshotArn());
        assertNotNull(snapshot.getSnapshotCreateTime());
        // sqlDump is now an absolute path scoped by account to avoid collisions across accounts
        assertTrue(snapshot.getSqlDump().contains("111111111111"));
        assertTrue(snapshot.getSqlDump().endsWith("my-snapshot.sql"));
        verify(snapshotBackend).put(eq("my-snapshot"), any(Snapshot.class));
        verify(snapshotBackend).flush();
        verify(cm).takeSnapshot(eq("111111111111"), eq("my-cluster"), eq("admin"), any(Path.class));
    }

    @Test
    void createSnapshotClusterNotFound() {
        when(clusterBackend.get("missing-cluster")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.createSnapshot("snap-1", "missing-cluster"));
    }

    @Test
    void createSnapshotAlreadyExists() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(new Snapshot()));

        assertThrows(AwsException.class, () ->
                service.createSnapshot("snap-1", "my-cluster"));
    }

    @Test
    void createSnapshotRejectsTraversalOrMalformedIdentifier() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setMasterUsername("admin");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));
        when(snapshotBackend.get(anyString())).thenReturn(Optional.empty());

        String[] bad = {
                "../evil", "../../etc/cron.d/x", "/etc/passwd", "a/b", "a\\b",
                "foo/../bar", "-leading", "trailing-", "double--hyphen", "1startsdigit", "", "has space",
        };
        for (String id : bad) {
            AwsException ex = assertThrows(AwsException.class,
                    () -> service.createSnapshot(id, "my-cluster"), "expected rejection for: " + id);
            assertEquals("InvalidParameterValue", ex.getErrorCode(), id);
            assertEquals(400, ex.getHttpStatus(), id);
        }
        verify(cm, never()).takeSnapshot(any(), any(), any(), any(Path.class));
        verify(snapshotBackend, never()).put(anyString(), any(Snapshot.class));
    }

    @Test
    void modifyClusterUpdatesMetadataAndPassword() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setNodeType("dc2.large");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("old-pw");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster updated = service.modifyCluster("my-cluster", "ra3.xlplus", null, "new-pw", null, null);

        assertEquals("ra3.xlplus", updated.getNodeType());
        assertEquals("new-pw", updated.getMasterPassword());
        verify(cm).alterUserPassword("111111111111", "my-cluster", "admin", "new-pw");
        verify(clusterBackend).put(eq("my-cluster"), any(Cluster.class));
        verify(clusterBackend).flush();
    }

    @Test
    void modifyClusterUpdatesVpcSecurityGroups() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setMasterUsername("admin");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster updated = service.modifyCluster("my-cluster", null, null, null, "custom-pg", List.of("sg-9"));

        assertEquals("custom-pg", updated.getClusterParameterGroupName());
        assertEquals(List.of("sg-9"), updated.getVpcSecurityGroupIds());
        verify(cm, never()).alterUserPassword(any(), any(), any(), any());
    }

    @Test
    void modifyClusterNotFound() {
        when(clusterBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.modifyCluster("missing", "ra3.xlplus", null, null, null, null));
    }

    @Test
    void modifyClusterIamRolesAddsAndRemovesRolesAndUpdatesTheProxy() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setIamRoleArns(List.of("arn:aws:iam::111111111111:role/old", "arn:aws:iam::111111111111:role/keep"));
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(clusterBackend.accountId()).thenReturn("111111111111");

        Cluster updated = service.modifyClusterIamRoles("c1",
                List.of("arn:aws:iam::111111111111:role/new", "arn:aws:iam::111111111111:role/keep"),
                List.of("arn:aws:iam::111111111111:role/old"));

        assertEquals(List.of("arn:aws:iam::111111111111:role/keep", "arn:aws:iam::111111111111:role/new"),
                updated.getIamRoleArns());
        verify(proxyManager).updateIamRoles("111111111111:c1", updated.getIamRoleArns());
        verify(clusterBackend).put(eq("c1"), any(Cluster.class));
        verify(clusterBackend).flush();
    }

    @Test
    void describeLoggingStatusDefaultsToDisabled() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster result = service.describeLoggingStatus("my-cluster");

        assertFalse(result.isLoggingEnabled());
        assertNull(result.getLoggingBucketName());
    }

    @Test
    void describeLoggingStatusNotFound() {
        when(clusterBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.describeLoggingStatus("missing"));
    }

    @Test
    void enableLogging() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster result = service.enableLogging("my-cluster", "my-bucket", "logs/", null, null);

        assertTrue(result.isLoggingEnabled());
        assertEquals("my-bucket", result.getLoggingBucketName());
        assertEquals("logs/", result.getLoggingS3KeyPrefix());
        verify(clusterBackend).put(eq("my-cluster"), any(Cluster.class));
        verify(clusterBackend).flush();
    }

    @Test
    void modifyClusterIamRolesRejectsMalformedRoleArnWithoutChangingTheCluster() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setIamRoleArns(List.of("arn:aws:iam::111111111111:role/keep"));
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.modifyClusterIamRoles("c1", List.of("not-an-arn"), List.of()));

        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(List.of("arn:aws:iam::111111111111:role/keep"), cluster.getIamRoleArns());
        verify(proxyManager, never()).updateIamRoles(any(), any());
    }

    @Test
    void modifyClusterIamRolesRejectsNonRoleArn() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.modifyClusterIamRoles("c1", List.of("arn:aws:iam::111111111111:user/bob"), List.of()));

        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void modifyClusterIamRolesNotFound() {
        when(clusterBackend.get("missing")).thenReturn(Optional.empty());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.modifyClusterIamRoles("missing", List.of(), List.of()));

        assertEquals("ClusterNotFound", ex.getErrorCode());
    }

    @Test
    void enableLoggingRequiresBucketName() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        assertThrows(AwsException.class, () -> service.enableLogging("my-cluster", null, null, null, null));
    }

    @Test
    void enableLoggingCloudWatchDoesNotRequireBucketName() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster result = service.enableLogging("my-cluster", null, null, "cloudwatch", List.of("connectionlog"));
        assertTrue(result.isLoggingEnabled());
        assertEquals("cloudwatch", result.getLoggingDestinationType());
        assertEquals(List.of("connectionlog"), result.getLoggingExports());
    }

    @Test
    void enableLoggingS3TableDoesNotRequireBucketName() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster result = service.enableLogging("my-cluster", null, null, "s3table", null, "my-kms-key", "account");
        assertTrue(result.isLoggingEnabled());
        assertEquals("s3table", result.getLoggingDestinationType());
        assertNull(result.getLoggingBucketName());
        assertEquals("my-kms-key", result.getLoggingS3TableKmsKeyId());
        assertEquals("account", result.getLoggingS3TableGranularity());
    }

    @Test
    void enableLoggingRejectsInvalidS3TableGranularity() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.enableLogging("my-cluster", null, null, "s3table", null, null, "daily"));

        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertFalse(cluster.isLoggingEnabled());
    }

    @Test
    void enableLoggingRejectsS3TableSettingsForOtherDestinations() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.enableLogging("my-cluster", null, null, "s3", null, null, "cluster"));

        assertEquals("InvalidParameterCombination", ex.getErrorCode());
        assertFalse(cluster.isLoggingEnabled());
    }

    @Test
    void disableLoggingClearsS3TableFields() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingDestinationType("s3table");
        cluster.setLoggingS3TableKmsKeyId("my-kms-key");
        cluster.setLoggingS3TableGranularity("account");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster result = service.disableLogging("my-cluster");
        assertFalse(result.isLoggingEnabled());
        assertNull(result.getLoggingDestinationType());
        assertNull(result.getLoggingS3TableKmsKeyId());
        assertNull(result.getLoggingS3TableGranularity());
    }

    @Test
    void createClusterAssignsDefaultParameterGroup() {
        when(clusterBackend.get(anyString())).thenReturn(Optional.empty());
        when(cm.start(any(), any(), any(), any())).thenReturn(new RedshiftContainerHandle("c1", "my-cluster", "localhost", 5432));

        Cluster cluster = service.createCluster("my-cluster", "dc2.large", "admin", "password123");
        assertEquals("default.redshift-1.0", cluster.getClusterParameterGroupName());
    }

    @Test
    void modifyClusterStoresMultiAZ() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        assertTrue(service.modifyCluster("my-cluster", null, null, null, null, null, true).isMultiAZ());
    }

    @Test
    void describeDefaultParameterGroupIsSynthesized() {
        when(parameterGroupBackend.get("default.redshift-1.0")).thenReturn(Optional.empty());

        List<ClusterParameterGroup> groups = service.describeClusterParameterGroups("default.redshift-1.0");
        assertEquals(1, groups.size());
        assertEquals("default.redshift-1.0", groups.get(0).getParameterGroupName());
    }

    @Test
    void enableLoggingNotFound() {
        when(clusterBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.enableLogging("missing", "my-bucket", null, null, null));
    }

    @Test
    void disableLoggingClearsConfig() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingBucketName("my-bucket");
        cluster.setLoggingS3KeyPrefix("logs/");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster result = service.disableLogging("my-cluster");

        assertFalse(result.isLoggingEnabled());
        assertNull(result.getLoggingBucketName());
        assertNull(result.getLoggingS3KeyPrefix());
        verify(clusterBackend).put(eq("my-cluster"), any(Cluster.class));
        verify(clusterBackend).flush();
    }

    @Test
    void disableLoggingNotFound() {
        when(clusterBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.disableLogging("missing"));
    }

    @Test
    void describeSnapshots() {
        Snapshot s = new Snapshot("snap-1", "my-cluster", "available", 5439, "admin");
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(s));

        List<Snapshot> list = service.describeSnapshots("snap-1");
        assertEquals(1, list.size());
        assertEquals("snap-1", list.get(0).getSnapshotIdentifier());
    }

    @Test
    void describeSnapshotsNotFound() {
        when(snapshotBackend.get("missing-snap")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.describeSnapshots("missing-snap"));
    }

    @Test
    void deleteSnapshot() {
        Snapshot s = new Snapshot("snap-1", "my-cluster", "available", 5439, "admin");
        s.setSqlDump(dumpPath("snap-1"));
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(s));

        Snapshot deleted = service.deleteSnapshot("snap-1");
        assertNotNull(deleted);
        assertEquals("deleted", deleted.getStatus());
        verify(snapshotBackend).delete("snap-1");
        verify(snapshotBackend).flush();
    }

    @Test
    void deleteSnapshotNotFound() {
        when(snapshotBackend.get("missing-snap")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.deleteSnapshot("missing-snap"));
    }

    @Test
    void restoreFromClusterSnapshot() {
        Cluster sourceCluster = new Cluster();
        sourceCluster.setClusterIdentifier("source-cluster");
        sourceCluster.setMasterPassword("password123");

        Snapshot snapshot = new Snapshot("my-snapshot", "source-cluster", "available", 5439, "admin");
        snapshot.setSqlDump(dumpPath("my-snapshot"));
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        when(clusterBackend.get("source-cluster")).thenReturn(Optional.of(sourceCluster));
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("restored-cluster"), eq("admin"), eq("password123")))
                .thenReturn(new RedshiftContainerHandle("c-new", "restored-cluster", "localhost", 5432));
        doNothing().when(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(Path.class));

        Cluster cluster = service.restoreFromClusterSnapshot("restored-cluster", "my-snapshot", "dc2.large");
        assertNotNull(cluster);
        assertEquals("restored-cluster", cluster.getClusterIdentifier());
        assertEquals("available", cluster.getClusterStatus());
        assertEquals("admin", cluster.getMasterUsername());
        assertEquals("password123", cluster.getMasterPassword());
        assertEquals("dc2.large", cluster.getNodeType());
        assertEquals("localhost", cluster.getEndpoint().getAddress());

        // Restore must use the source cluster's actual password, not a hardcoded one
        verify(cm).start("111111111111", "restored-cluster", "admin", "password123");
        verify(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(Path.class));
        verify(clusterBackend, times(2)).put(eq("restored-cluster"), any(Cluster.class));
        verify(clusterBackend, times(2)).flush();
    }

    @Test
    void restoreFromClusterSnapshotUsesStoredPasswordAfterSourceClusterDeleted() {
        Snapshot snapshot = new Snapshot("my-snapshot", "deleted-source", "available", 5439, "admin");
        snapshot.setMasterPassword("original-secret");
        snapshot.setSqlDump(dumpPath("my-snapshot"));
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        // Source cluster no longer exists, but the snapshot itself still carries the original password
        when(clusterBackend.get("deleted-source")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("restored-cluster"), eq("admin"), eq("original-secret")))
                .thenReturn(new RedshiftContainerHandle("c-new", "restored-cluster", "localhost", 5432));
        doNothing().when(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(Path.class));

        Cluster cluster = service.restoreFromClusterSnapshot("restored-cluster", "my-snapshot", "dc2.large");
        assertEquals("original-secret", cluster.getMasterPassword());
        verify(cm).start("111111111111", "restored-cluster", "admin", "original-secret");
    }

    @Test
    void restoreFromClusterSnapshotFallsBackToAdminWhenSourceClusterGone() {
        Snapshot snapshot = new Snapshot("my-snapshot", "deleted-source", "available", 5439, "admin");
        snapshot.setSqlDump(dumpPath("my-snapshot"));
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        when(clusterBackend.get("deleted-source")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("restored-cluster"), eq("admin"), eq("admin")))
                .thenReturn(new RedshiftContainerHandle("c-new", "restored-cluster", "localhost", 5432));
        doNothing().when(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(Path.class));

        Cluster cluster = service.restoreFromClusterSnapshot("restored-cluster", "my-snapshot", "dc2.large");
        assertEquals("admin", cluster.getMasterPassword());
        verify(cm).start("111111111111", "restored-cluster", "admin", "admin");
    }

    @Test
    void restoreFromClusterSnapshotAlreadyExists() {
        when(clusterBackend.get("existing-cluster")).thenReturn(Optional.of(new Cluster()));

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("existing-cluster", "my-snapshot"));
    }

    @Test
    void restoreFromClusterSnapshotNotFound() {
        when(clusterBackend.get("new-cluster")).thenReturn(Optional.empty());
        when(snapshotBackend.get("missing-snapshot")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("new-cluster", "missing-snapshot"));
    }

    @Test
    void restoreFromClusterSnapshotFailure() {
        Snapshot snapshot = new Snapshot("my-snapshot", "source-cluster", "available", 5439, "admin", dumpPath("my-snapshot"));
        when(clusterBackend.get("failed-cluster")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(any(), any(), any(), any())).thenThrow(new RuntimeException("Docker error"));

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("failed-cluster", "my-snapshot"));
    }

    @Test
    void restoreFromClusterSnapshotRejectsUntrustedDumpPathBeforeProvisioning() {
        // A dump path persisted outside the account dir (e.g. by pre-validation code) must be
        // rejected up front: no cluster record, no container.
        Snapshot snapshot = new Snapshot("my-snapshot", "source-cluster", "available", 5439, "admin", "/etc/shadow");
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("restored-cluster", "my-snapshot", "dc2.large"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        verify(cm, never()).start(any(), any(), any(), any());
        verify(cm, never()).restoreSnapshot(any(), any(), any(), any());
        verify(clusterBackend, never()).put(anyString(), any(Cluster.class));
    }

    @Test
    void deleteClusterStopsTheProxyAndReleasesThePort() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setProxyPort(7105);
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(clusterBackend.accountId()).thenReturn("111111111111");

        service.deleteCluster("c1");

        verify(proxyManager).stopProxy("111111111111:c1");
    }

    @Test
    void modifyClusterPasswordUpdatesTheProxySnapshot() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("old");
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(clusterBackend.accountId()).thenReturn("111111111111");

        service.modifyCluster("c1", null, null, "NewSecret1", null, null);

        verify(proxyManager).updateMasterPassword("111111111111:c1", "NewSecret1");
    }

    @Test
    void restoreFromSnapshotStartsAProxyForTheNewCluster() {
        Snapshot snap = new Snapshot();
        snap.setSnapshotIdentifier("s1");
        snap.setClusterIdentifier("src");
        snap.setMasterUsername("admin");
        snap.setMasterPassword("Secret123");
        snap.setSqlDump(null);
        when(snapshotBackend.get("s1")).thenReturn(Optional.of(snap));
        when(clusterBackend.get("restored")).thenReturn(Optional.empty());
        when(clusterBackend.accountId()).thenReturn("111111111111");
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.10");
        when(handle.getPort()).thenReturn(32810);
        when(cm.start(eq("111111111111"), eq("restored"), eq("admin"), eq("Secret123"))).thenReturn(handle);

        Cluster restored = service.restoreFromClusterSnapshot("restored", "s1");

        assertEquals("localhost", restored.getEndpoint().getAddress());
        assertEquals(restored.getEndpoint().getPort(), restored.getProxyPort());
        verify(proxyManager).startProxy(eq("111111111111:restored"), anyInt(),
                eq("172.17.0.10"), eq(32810), eq("localhost"),
                eq("admin"), eq("Secret123"), eq("dev"), any(), any());
    }

    @Test
    void createClusterParameterGroup() {
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.empty());

        ClusterParameterGroup pg = service.createClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        assertNotNull(pg);
        assertEquals("my-pg", pg.getParameterGroupName());
        assertEquals("redshift-1.0", pg.getParameterGroupFamily());
        assertEquals("custom pg", pg.getDescription());
        verify(parameterGroupBackend).put(eq("my-pg"), any(ClusterParameterGroup.class));
        verify(parameterGroupBackend).flush();
    }

    @Test
    void createClusterParameterGroupAlreadyExists() {
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(new ClusterParameterGroup()));

        assertThrows(AwsException.class, () ->
                service.createClusterParameterGroup("my-pg", "redshift-1.0", "custom pg"));
    }

    @Test
    void describeClusterParameterGroups() {
        ClusterParameterGroup pg = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(pg));

        List<ClusterParameterGroup> list = service.describeClusterParameterGroups("my-pg");
        assertEquals(1, list.size());
        assertEquals("my-pg", list.get(0).getParameterGroupName());
    }

    @Test
    void describeClusterParameterGroupsNotFound() {
        when(parameterGroupBackend.get("missing-pg")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.describeClusterParameterGroups("missing-pg"));
    }

    @Test
    void deleteClusterParameterGroup() {
        ClusterParameterGroup pg = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(pg));

        ClusterParameterGroup deleted = service.deleteClusterParameterGroup("my-pg");
        assertNotNull(deleted);
        assertEquals("my-pg", deleted.getParameterGroupName());
        verify(parameterGroupBackend).delete("my-pg");
        verify(parameterGroupBackend).flush();
    }

    @Test
    void deleteClusterParameterGroupNotFound() {
        when(parameterGroupBackend.get("missing-pg")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.deleteClusterParameterGroup("missing-pg"));
    }

    @Test
    void modifyClusterParameterGroup() {
        ClusterParameterGroup group = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(group));

        ClusterParameterGroup updated = service.modifyClusterParameterGroup("my-pg",
                List.of(new Parameter("max_cursor_result_set_size", "1000")));

        assertEquals("1000", updated.getParameters().stream()
                .filter(p -> "max_cursor_result_set_size".equals(p.getParameterName()))
                .findFirst().orElseThrow().getParameterValue());
        verify(parameterGroupBackend).put(eq("my-pg"), any(ClusterParameterGroup.class));
        verify(parameterGroupBackend).flush();
    }

    @Test
    void modifyClusterParameterGroupAppendsUnknownParameter() {
        ClusterParameterGroup group = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(group));

        ClusterParameterGroup updated = service.modifyClusterParameterGroup("my-pg",
                List.of(new Parameter("statement_timeout", "5000")));

        assertTrue(updated.getParameters().stream()
                .anyMatch(p -> "statement_timeout".equals(p.getParameterName()) && "5000".equals(p.getParameterValue())));
    }

    @Test
    void modifyClusterParameterGroupNotFound() {
        when(parameterGroupBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.modifyClusterParameterGroup("missing", List.of(new Parameter("x", "y"))));
    }

    @Test
    void describeClusterParametersReturnsStoredValues() {
        ClusterParameterGroup group = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        group.setParameters(new ArrayList<>(List.of(new Parameter("statement_timeout", "5000"))));
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(group));

        List<Parameter> params = service.describeClusterParameters("my-pg");

        assertEquals(1, params.size());
        assertEquals("statement_timeout", params.get(0).getParameterName());
        assertEquals("5000", params.get(0).getParameterValue());
    }

    @Test
    void modifyClusterParameterGroupPreservesMetadata() {
        ClusterParameterGroup group = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(group));

        // Modify using 2-arg constructor (only name and value) - should preserve description and dataType
        ClusterParameterGroup updated = service.modifyClusterParameterGroup("my-pg",
                List.of(new Parameter("max_cursor_result_set_size", "1000")));

        Parameter modified = updated.getParameters().stream()
                .filter(p -> "max_cursor_result_set_size".equals(p.getParameterName()))
                .findFirst().orElseThrow();
        assertEquals("1000", modified.getParameterValue());
        assertEquals("Maximum cursor result set size", modified.getDescription());
        assertEquals("integer", modified.getDataType());
    }

    @Test
    void createAndListTagsForCluster() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        service.createTags("arn:aws:redshift:us-east-1:111111111111:cluster:my-cluster",
                Map.of("env", "test"));

        assertEquals("test", cluster.getTags().get("env"));
        verify(clusterBackend).put(eq("my-cluster"), any(Cluster.class));
    }

    @Test
    void deleteTagsForCluster() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setTags(new LinkedHashMap<>(Map.of("env", "test", "team", "data")));
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        service.deleteTags("arn:aws:redshift:us-east-1:111111111111:cluster:my-cluster", List.of("env"));

        assertEquals(Map.of("team", "data"), cluster.getTags());
    }

    @Test
    void createTagsRejectsNonArnResourceName() {
        assertThrows(AwsException.class, () ->
                service.createTags("my-cluster", Map.of("env", "test")));
    }

    @Test
    void createTagsRejectsUnknownResourceType() {
        assertThrows(AwsException.class, () ->
                service.createTags("arn:aws:redshift:us-east-1:111111111111:reservednode:foo", Map.of("env", "test")));
    }

    @Test
    void describeTagsForSpecificResource() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setTags(new LinkedHashMap<>(Map.of("env", "test")));
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        List<RedshiftService.TaggedResource> tagged =
                service.describeTags("arn:aws:redshift:us-east-1:111111111111:cluster:my-cluster", null, null);

        assertEquals(1, tagged.size());
        assertEquals("cluster", tagged.get(0).resourceType());
        assertEquals("env", tagged.get(0).tagKey());
        assertEquals("test", tagged.get(0).tagValue());
    }

    @Test
    void describeTagsScansAllResourcesOfType() {
        Cluster a = new Cluster();
        a.setClusterIdentifier("cluster-a");
        a.setTags(new LinkedHashMap<>(Map.of("env", "prod")));
        Cluster b = new Cluster();
        b.setClusterIdentifier("cluster-b");
        b.setTags(new LinkedHashMap<>());
        when(clusterBackend.scan(any())).thenReturn(List.of(a, b));
        when(snapshotBackend.scan(any())).thenReturn(List.of());
        when(parameterGroupBackend.scan(any())).thenReturn(List.of());
        when(subnetGroupBackend.scan(any())).thenReturn(List.of());

        List<RedshiftService.TaggedResource> tagged = service.describeTags(null, "cluster", null);

        assertEquals(1, tagged.size());
        assertEquals("cluster-a", extractResourceId(tagged.get(0).resourceName()));
    }

    @Test
    void createClusterSubnetGroup() {
        when(subnetGroupBackend.get("my-subnet-group")).thenReturn(Optional.empty());

        ClusterSubnetGroup group = service.createClusterSubnetGroup(
                "my-subnet-group", "test group", "vpc-123", List.of("subnet-1", "subnet-2"));

        assertEquals("my-subnet-group", group.getClusterSubnetGroupName());
        assertEquals(List.of("subnet-1", "subnet-2"), group.getSubnetIds());
        verify(subnetGroupBackend).put(eq("my-subnet-group"), any(ClusterSubnetGroup.class));
        verify(subnetGroupBackend).flush();
    }

    @Test
    void createClusterSubnetGroupAlreadyExists() {
        when(subnetGroupBackend.get("existing")).thenReturn(Optional.of(new ClusterSubnetGroup()));

        assertThrows(AwsException.class, () ->
                service.createClusterSubnetGroup("existing", "d", "vpc-1", List.of("subnet-1")));
    }

    @Test
    void describeClusterSubnetGroups() {
        ClusterSubnetGroup group = new ClusterSubnetGroup("my-group", "d", "vpc-1", List.of("subnet-1"));
        when(subnetGroupBackend.get("my-group")).thenReturn(Optional.of(group));

        List<ClusterSubnetGroup> list = service.describeClusterSubnetGroups("my-group");

        assertEquals(1, list.size());
        assertEquals("my-group", list.get(0).getClusterSubnetGroupName());
    }

    @Test
    void modifyClusterSubnetGroup() {
        ClusterSubnetGroup group = new ClusterSubnetGroup("my-group", "old", "vpc-1", List.of("subnet-1"));
        when(subnetGroupBackend.get("my-group")).thenReturn(Optional.of(group));

        ClusterSubnetGroup updated = service.modifyClusterSubnetGroup("my-group", "new", List.of("subnet-2", "subnet-3"));

        assertEquals("new", updated.getDescription());
        assertEquals(List.of("subnet-2", "subnet-3"), updated.getSubnetIds());
    }

    @Test
    void deleteClusterSubnetGroup() {
        ClusterSubnetGroup group = new ClusterSubnetGroup("my-group", "d", "vpc-1", List.of("subnet-1"));
        when(subnetGroupBackend.get("my-group")).thenReturn(Optional.of(group));

        ClusterSubnetGroup deleted = service.deleteClusterSubnetGroup("my-group");

        assertEquals("my-group", deleted.getClusterSubnetGroupName());
        verify(subnetGroupBackend).delete("my-group");
        verify(subnetGroupBackend).flush();
    }

    @Test
    void deleteClusterSubnetGroupNotFound() {
        when(subnetGroupBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.deleteClusterSubnetGroup("missing"));
    }

    @Test
    void createsSnapshotCopyGrant() {
        when(snapshotCopyGrantBackend.get("my-grant")).thenReturn(Optional.empty());

        SnapshotCopyGrant grant = service.createSnapshotCopyGrant("my-grant", "key-abc", Map.of());

        assertEquals("my-grant", grant.getSnapshotCopyGrantName());
        assertEquals("key-abc", grant.getKmsKeyId());
        verify(snapshotCopyGrantBackend).put(eq("my-grant"), any(SnapshotCopyGrant.class));
        verify(snapshotCopyGrantBackend).flush();
    }

    @Test
    void createSnapshotCopyGrantDefaultsKmsKeyId() {
        when(snapshotCopyGrantBackend.get("my-grant")).thenReturn(Optional.empty());

        SnapshotCopyGrant grant = service.createSnapshotCopyGrant("my-grant", null, Map.of());

        assertEquals("arn:aws:kms:us-east-1:111111111111:alias/aws/redshift", grant.getKmsKeyId());
    }

    @Test
    void createSnapshotCopyGrantStoresTags() {
        when(snapshotCopyGrantBackend.get("my-grant")).thenReturn(Optional.empty());

        SnapshotCopyGrant grant = service.createSnapshotCopyGrant("my-grant", "key-abc", Map.of("env", "prod"));

        assertEquals(Map.of("env", "prod"), grant.getTags());
    }

    @Test
    void createSnapshotCopyGrantRejectsDuplicateName() {
        when(snapshotCopyGrantBackend.get("existing")).thenReturn(Optional.of(new SnapshotCopyGrant()));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.createSnapshotCopyGrant("existing", "key-abc", Map.of()));
        assertEquals("SnapshotCopyGrantAlreadyExistsFault", ex.getErrorCode());
        verify(snapshotCopyGrantBackend, never()).put(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1grant",                                                          // must start with a letter
            "Grant",                                                           // no uppercase
            "grant--copy",                                                     // no consecutive hyphens
            "grant-",                                                          // no trailing hyphen
            "g123456789012345678901234567890123456789012345678901234567890123" // 64 characters
    })
    void createSnapshotCopyGrantRejectsNamesRedshiftRejects(String name) {
        when(snapshotCopyGrantBackend.get(name)).thenReturn(Optional.empty());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.createSnapshotCopyGrant(name, "key-abc", Map.of()));

        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        verify(snapshotCopyGrantBackend, never()).put(any(), any());
        verify(snapshotCopyGrantBackend, never()).flush();
    }

    @Test
    void createSnapshotCopyGrantAcceptsMaximumLengthName() {
        String name = "g12345678901234567890123456789012345678901234567890123456789012";
        assertEquals(63, name.length());
        when(snapshotCopyGrantBackend.get(name)).thenReturn(Optional.empty());

        SnapshotCopyGrant grant = service.createSnapshotCopyGrant(name, "key-abc", Map.of());

        assertEquals(name, grant.getSnapshotCopyGrantName());
        verify(snapshotCopyGrantBackend).put(eq(name), any(SnapshotCopyGrant.class));
        verify(snapshotCopyGrantBackend).flush();
    }

    @Test
    void describeSnapshotCopyGrantsByName() {
        SnapshotCopyGrant grant = new SnapshotCopyGrant("my-grant", "key-abc");
        when(snapshotCopyGrantBackend.get("my-grant")).thenReturn(Optional.of(grant));

        List<SnapshotCopyGrant> list = service.describeSnapshotCopyGrants("my-grant", null, null).items();

        assertEquals(1, list.size());
        assertEquals("my-grant", list.get(0).getSnapshotCopyGrantName());
        verify(snapshotCopyGrantBackend, never()).scan(any());
    }

    @Test
    void describeSnapshotCopyGrantsReturnsAllWhenNameOmitted() {
        when(snapshotCopyGrantBackend.scan(any())).thenReturn(List.of(
                new SnapshotCopyGrant("grant-a", "key-a"),
                new SnapshotCopyGrant("grant-b", "key-b")));

        PaginatedResult<SnapshotCopyGrant> page = service.describeSnapshotCopyGrants(null, null, null);

        assertEquals(2, page.items().size());
        assertNull(page.nextToken());
    }

    @Test
    void describeSnapshotCopyGrantsRejectsMissingGrant() {
        when(snapshotCopyGrantBackend.get("missing")).thenReturn(Optional.empty());

        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeSnapshotCopyGrants("missing", null, null));
        assertEquals("SnapshotCopyGrantNotFoundFault", ex.getErrorCode());
        // DescribeSnapshotCopyGrants documents this fault as 400, not 404.
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void describeSnapshotCopyGrantsPagesInNameOrder() {
        // 25 grants created out of order: paging must follow name order, not insertion order.
        List<SnapshotCopyGrant> stored = new ArrayList<>();
        for (int i = 25; i >= 1; i--) {
            stored.add(new SnapshotCopyGrant(String.format("grant-%02d", i), "key-" + i));
        }
        when(snapshotCopyGrantBackend.scan(any())).thenReturn(stored);

        PaginatedResult<SnapshotCopyGrant> first = service.describeSnapshotCopyGrants(null, 20, null);

        assertEquals(20, first.items().size());
        assertEquals("grant-01", first.items().get(0).getSnapshotCopyGrantName());
        assertEquals("grant-20", first.items().get(19).getSnapshotCopyGrantName());
        assertNotNull(first.nextToken());

        PaginatedResult<SnapshotCopyGrant> second =
                service.describeSnapshotCopyGrants(null, 20, first.nextToken());

        assertEquals(5, second.items().size());
        assertEquals("grant-21", second.items().get(0).getSnapshotCopyGrantName());
        assertEquals("grant-25", second.items().get(4).getSnapshotCopyGrantName());
        assertNull(second.nextToken(), "last page must not carry a marker");
    }

    @Test
    void describeSnapshotCopyGrantsOmitsMarkerWhenPageExactlyFits() {
        List<SnapshotCopyGrant> stored = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            stored.add(new SnapshotCopyGrant(String.format("grant-%02d", i), "key-" + i));
        }
        when(snapshotCopyGrantBackend.scan(any())).thenReturn(stored);

        PaginatedResult<SnapshotCopyGrant> page = service.describeSnapshotCopyGrants(null, 20, null);

        assertEquals(20, page.items().size());
        assertNull(page.nextToken());
    }

    @Test
    void describeSnapshotCopyGrantsRejectsMaxRecordsBelowMinimum() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeSnapshotCopyGrants(null, 19, null));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        verify(snapshotCopyGrantBackend, never()).scan(any());
    }

    @Test
    void describeSnapshotCopyGrantsRejectsMaxRecordsAboveMaximum() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeSnapshotCopyGrants(null, 101, null));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void describeSnapshotCopyGrantsAcceptsMarkerAlongsideName() {
        // AWS documents the two as mutually exclusive but models no error, so Floci filters
        // by name and then paginates rather than rejecting the pair.
        SnapshotCopyGrant grant = new SnapshotCopyGrant("my-grant", "key-abc");
        when(snapshotCopyGrantBackend.get("my-grant")).thenReturn(Optional.of(grant));

        PaginatedResult<SnapshotCopyGrant> page =
                service.describeSnapshotCopyGrants("my-grant", null, null);

        assertEquals(1, page.items().size());
        assertNull(page.nextToken());
    }

    @Test
    void deletesSnapshotCopyGrant() {
        SnapshotCopyGrant grant = new SnapshotCopyGrant("my-grant", "key-abc");
        when(snapshotCopyGrantBackend.get("my-grant")).thenReturn(Optional.of(grant));

        SnapshotCopyGrant deleted = service.deleteSnapshotCopyGrant("my-grant");

        assertEquals("my-grant", deleted.getSnapshotCopyGrantName());
        verify(snapshotCopyGrantBackend).delete("my-grant");
        verify(snapshotCopyGrantBackend).flush();
    }

    @Test
    void deleteSnapshotCopyGrantRejectsMissingGrant() {
        when(snapshotCopyGrantBackend.get("missing")).thenReturn(Optional.empty());

        AwsException ex = assertThrows(AwsException.class, () -> service.deleteSnapshotCopyGrant("missing"));
        assertEquals("SnapshotCopyGrantNotFoundFault", ex.getErrorCode());
        // DeleteSnapshotCopyGrant documents this fault as 400, not 404.
        assertEquals(400, ex.getHttpStatus());
        verify(snapshotCopyGrantBackend, never()).delete(any());
    }

    @Test
    void createTagsOnSnapshotCopyGrant() {
        SnapshotCopyGrant grant = new SnapshotCopyGrant("my-grant", "key-abc");
        when(snapshotCopyGrantBackend.get("my-grant")).thenReturn(Optional.of(grant));

        service.createTags("arn:aws:redshift:us-east-1:111111111111:snapshotcopygrant:my-grant",
                Map.of("env", "prod"));

        assertEquals(Map.of("env", "prod"),
                service.listTagsForResource("arn:aws:redshift:us-east-1:111111111111:snapshotcopygrant:my-grant"));
    }

    @Test
    void createTagsOnMissingSnapshotCopyGrantIsNotFound() {
        when(snapshotCopyGrantBackend.get("missing")).thenReturn(Optional.empty());

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createTags("arn:aws:redshift:us-east-1:111111111111:snapshotcopygrant:missing",
                        Map.of("env", "prod")));
        // ResourceNotFoundFault, not the grant-specific fault: CreateTags/DeleteTags/
        // DescribeTags list ResourceNotFoundFault (404) for a missing resource and never
        // list SnapshotCopyGrantNotFoundFault, which the model pins at 400.
        assertEquals("ResourceNotFoundFault", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    private static String extractResourceId(String arn) {
        String resource = arn.substring(arn.lastIndexOf(':') + 1);
        return resource.contains("/") ? resource.substring(resource.lastIndexOf('/') + 1) : resource;
    }

    // ── passwordValidatorFor / GetClusterCredentials broker wiring ─────────────

    private void seedCluster(String accountId, String clusterId) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(clusterId);
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("SecretPass1");
        when(clusterBackend.getForAccount(accountId, clusterId)).thenReturn(Optional.of(cluster));
    }

    @Test
    void passwordValidatorAcceptsMasterPair() {
        seedCluster("acc", "c1");

        PasswordValidator validator =
                service.passwordValidatorForTesting("acc", "c1");

        assertEquals(PasswordValidator.AuthResult.MASTER_EQUIVALENT,
                validator.validate("admin", "SecretPass1"));
    }

    @Test
    void passwordValidatorRejectsEverythingWhenClusterRowIsAbsent() {
        when(clusterBackend.getForAccount("acc", "gone")).thenReturn(Optional.empty());

        PasswordValidator validator = service.passwordValidatorForTesting("acc", "gone");

        assertEquals(PasswordValidator.AuthResult.REJECT, validator.validate("admin", "SecretPass1"));
        assertEquals(PasswordValidator.AuthResult.REJECT, validator.validate("analyst", "anything"));
    }

    @Test
    void passwordValidatorRejectsMasterUserWithWrongPassword() {
        seedCluster("acc", "c1");

        PasswordValidator validator = service.passwordValidatorForTesting("acc", "c1");

        assertEquals(PasswordValidator.AuthResult.REJECT,
                validator.validate("admin", "not-the-master-password"));
    }

    @Test
    void passwordValidatorAcceptsLiveBrokerCredentialAsMasterEquivalent() {
        seedCluster("acc", "c1");
        TempCredential cred = credentialBroker.issue("acc", "c1", "analyst", List.of(), 900);
        PasswordValidator validator =
                service.passwordValidatorForTesting("acc", "c1");

        assertEquals(PasswordValidator.AuthResult.MASTER_EQUIVALENT,
                validator.validate("analyst", cred.password()));
    }

    @Test
    void passwordValidatorRejectsKnownBrokerUserWithWrongPassword() {
        seedCluster("acc", "c1");
        credentialBroker.issue("acc", "c1", "analyst", List.of(), 900);
        PasswordValidator validator =
                service.passwordValidatorForTesting("acc", "c1");

        assertEquals(PasswordValidator.AuthResult.REJECT,
                validator.validate("analyst", "nope"));
    }

    @Test
    void passwordValidatorPassesThroughUnknownUser() {
        seedCluster("acc", "c1");
        PasswordValidator validator =
                service.passwordValidatorForTesting("acc", "c1");

        assertEquals(PasswordValidator.AuthResult.PASSTHROUGH,
                validator.validate("someone-else", "whatever"));
    }
}
