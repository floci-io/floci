package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.container.RdsContainerHandle;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyManager;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdsServiceTest {

    private RdsService rdsService;
    private RdsContainerManager containerManager;
    private RdsProxyManager proxyManager;
    private RegionResolver regionResolver;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        containerManager = mock(RdsContainerManager.class);
        proxyManager = mock(RdsProxyManager.class);
        regionResolver = new RegionResolver("us-east-1", "123456789012");
        config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.RdsServiceConfig rdsConfig = mock(EmulatorConfig.RdsServiceConfig.class);

        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.rds()).thenReturn(rdsConfig);
        when(rdsConfig.proxyBasePort()).thenReturn(7000);
        when(rdsConfig.proxyMaxPort()).thenReturn(7099);
        when(rdsConfig.defaultPostgresImage()).thenReturn("postgres:16-alpine");
        when(rdsConfig.defaultMysqlImage()).thenReturn("mysql:8.0");
        when(rdsConfig.defaultMariadbImage()).thenReturn("mariadb:11");

        rdsService = newService(containerManager, proxyManager,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        when(containerManager.start(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("cont-id", "id", "localhost", 5432));
    }

    @Test
    void createDbInstanceGeneratesMissingFields() {
        DbInstance instance = rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        assertEquals("mydb", instance.getDbInstanceIdentifier());
        assertNotNull(instance.getDbiResourceId());
        assertTrue(instance.getDbiResourceId().startsWith("db-"));
        assertEquals("arn:aws:rds:us-east-1:123456789012:db:mydb", instance.getDbInstanceArn());
    }

    @Test
    void postgresImageUsesRequestedEngineVersionAndDefaultFlavor() {
        assertEquals("postgres:18.1-alpine",
                RdsService.imageForRequestedVersion("postgres:16-alpine", "18.1"));
        assertEquals("example.com/library/postgres:18.1-alpine",
                RdsService.imageForRequestedVersion("example.com/library/postgres:16-alpine", "18.1"));
        assertEquals("postgres:18.1",
                RdsService.imageForRequestedVersion("postgres", "18.1"));
        assertEquals("postgres:18.1-alpine",
                RdsService.imageForRequestedVersion("postgres:16-alpine", "18.1-alpine"));
    }

    @Test
    void createDbInstanceStartsContainerWithRequestedEngineVersionImage() {
        rdsService.createDbInstance("mydb", "postgres", "18.1",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        verify(containerManager).start(eq("mydb"), any(), eq(DatabaseEngine.POSTGRES),
                eq("postgres:18.1-alpine"), eq("admin"), eq("password"), eq("dbname"));
    }

    @Test
    void dbInstanceTagsRoundTripAndMutateByArn() {
        DbInstance instance = rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, false, null,
                java.util.Map.of("example:ClusterId", "cluster-a"));

        assertEquals(java.util.Map.of("example:ClusterId", "cluster-a"),
                rdsService.listTagsForResource(instance.getDbInstanceArn()));

        rdsService.addTagsToResource(instance.getDbInstanceArn(), java.util.Map.of("Name", "mydb"));
        assertEquals(java.util.Map.of("example:ClusterId", "cluster-a", "Name", "mydb"),
                rdsService.listTagsForResource(instance.getDbInstanceArn()));

        rdsService.removeTagsFromResource(instance.getDbInstanceArn(), java.util.List.of("Name"));
        assertEquals(java.util.Map.of("example:ClusterId", "cluster-a"),
                rdsService.listTagsForResource(instance.getDbInstanceArn()));
    }

    @Test
    void dbInstanceEndpointUsesResolvedProxyHost() {
        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.resolve()).thenReturn("floci.local");
        RdsService service = new RdsService(containerManager, proxyManager, regionResolver, config,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), null, dockerHostResolver);

        DbInstance instance = service.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        assertEquals("floci.local", instance.getEndpoint().address());
    }

    @Test
    void createDbInstanceWithManagedMasterPasswordCreatesSecret() {
        SecretsManagerService secretsManager = mock(SecretsManagerService.class);
        Secret secret = new Secret();
        secret.setArn("arn:aws:secretsmanager:us-east-1:123456789012:secret:rds!db-secret");
        when(secretsManager.createSecret(any(), any(), eq(null), any(), eq("kms-key-1"), eq(null), eq("us-east-1")))
                .thenReturn(secret);
        RdsService service = newService(containerManager, proxyManager,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                secretsManager);

        DbInstance instance = service.createDbInstance("mydb", "postgres", "13",
                "admin", null, "dbname", "db.t3.micro",
                20, true, null, null, null, true, "kms-key-1");

        assertEquals("arn:aws:secretsmanager:us-east-1:123456789012:secret:rds!db-secret", instance.getMasterUserSecretArn());
        assertEquals("active", instance.getMasterUserSecretStatus());
        assertEquals("kms-key-1", instance.getMasterUserSecretKmsKeyId());
        assertNotNull(instance.getMasterPassword());
        assertTrue(instance.getMasterPassword().startsWith("floci-"));

        ArgumentCaptor<String> secretName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secretString = ArgumentCaptor.forClass(String.class);
        verify(secretsManager).createSecret(secretName.capture(), secretString.capture(), eq(null), any(), eq("kms-key-1"), eq(null), eq("us-east-1"));
        assertTrue(secretName.getValue().startsWith("rds!db-"));
        assertTrue(secretString.getValue().contains("\"username\":\"admin\""));
        assertTrue(secretString.getValue().contains("\"password\":\"" + instance.getMasterPassword() + "\""));
        assertTrue(secretString.getValue().contains("\"dbInstanceIdentifier\":\"mydb\""));
    }

    @Test
    void listDbInstancesIsCaseInsensitive() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        Collection<DbInstance> result = rdsService.listDbInstances("MYDB");
        assertEquals(1, result.size());
        assertEquals("mydb", result.iterator().next().getDbInstanceIdentifier());

        result = rdsService.listDbInstances("mydb");
        assertEquals(1, result.size());
    }

    @Test
    void listDbInstancesReturnsEmptyWhenNotFound() {
        Collection<DbInstance> result = rdsService.listDbInstances("nonexistent");
        assertTrue(result.isEmpty());
    }

    @Test
    void modifyDbInstanceBlankPasswordDoesNotOverwriteExistingPassword() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "original-password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        DbInstance modified = rdsService.modifyDbInstance("mydb", "   ", null, null);

        assertEquals("original-password", modified.getMasterPassword());
        assertFalse(modified.isIamDatabaseAuthenticationEnabled());
    }

    @Test
    void modifyDbInstanceCanToggleIamWithoutChangingPassword() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "original-password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        DbInstance modified = rdsService.modifyDbInstance("mydb", null, true, null);

        assertEquals("original-password", modified.getMasterPassword());
        assertTrue(modified.isIamDatabaseAuthenticationEnabled());
    }

    @Test
    void modifyDbInstanceRejectsMissingDbSubnetGroup() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "original-password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.modifyDbInstance("mydb", null, null, "missing-subnet-group"));

        assertEquals("DBSubnetGroupNotFoundFault", exception.getErrorCode());
    }

    @Test
    void dbSubnetGroupRoundTrip() {
        DbSubnetGroup group = rdsService.createDbSubnetGroup(
                "sample-db-subnets", "test", java.util.List.of("subnet-aaa", "subnet-bbb"));

        assertEquals("sample-db-subnets", group.getDbSubnetGroupName());
        assertEquals(java.util.List.of("subnet-aaa", "subnet-bbb"), group.getSubnetIds());
        assertEquals(1, rdsService.listDbSubnetGroups("sample-db-subnets").size());

        rdsService.deleteDbSubnetGroup("sample-db-subnets");
        assertTrue(rdsService.listDbSubnetGroups("sample-db-subnets").isEmpty());
    }

    @Test
    void createDbInstanceRejectsMissingDbSubnetGroupBeforeStartingRuntime() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbInstance("mydb", "postgres", "13",
                        "admin", "password", "dbname", "db.t3.micro",
                        20, false, null, "missing-subnet-group", null));

        assertEquals("DBSubnetGroupNotFoundFault", exception.getErrorCode());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any());
        verify(proxyManager, never()).startProxy(any(), any(), anyBoolean(), anyInt(),
                any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void describeOrderableDbInstanceOptionsFiltersByEngineVersionAndClass() {
        var result = rdsService.describeOrderableDbInstanceOptions(
                "postgres", "18.1", "db.t3.micro");

        assertEquals(1, result.size());
        assertEquals("postgres", result.getFirst().get("engine"));
        assertEquals("18.1", result.getFirst().get("engineVersion"));
        assertEquals("db.t3.micro", result.getFirst().get("dbInstanceClass"));
    }

    @Test
    void describeOrderableDbInstanceOptionsIncludesModernGravitonPostgresClasses() {
        var flociPinned = rdsService.describeOrderableDbInstanceOptions(
                "postgres", "18.1", "db.m8g.large");
        var awsEquivalent = rdsService.describeOrderableDbInstanceOptions(
                "postgres", "18.4", "db.m8g.large");

        assertEquals(1, flociPinned.size());
        assertEquals("db.m8g.large", flociPinned.getFirst().get("dbInstanceClass"));
        assertEquals("18.1", flociPinned.getFirst().get("engineVersion"));
        assertEquals(1, awsEquivalent.size());
        assertEquals("db.m8g.large", awsEquivalent.getFirst().get("dbInstanceClass"));
        assertEquals("18.4", awsEquivalent.getFirst().get("engineVersion"));
    }

    @Test
    void describeOrderableDbInstanceOptionsIncludesCurrentSmallGravitonPostgresClass() {
        var result = rdsService.describeOrderableDbInstanceOptions(
                "postgres", "16.14", "db.t4g.small");

        assertEquals(1, result.size());
        assertEquals("db.t4g.small", result.getFirst().get("dbInstanceClass"));
        assertEquals("16.14", result.getFirst().get("engineVersion"));
    }

    @Test
    void deleteDbClusterFailsWhenMembersRemain() {
        DbCluster cluster = rdsService.createDbCluster("cluster1", "postgres", "13",
                "admin", "password", "dbname", false, null);
        cluster.getDbClusterMembers().add("instance-1");

        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.deleteDbCluster("cluster1"));

        assertEquals("InvalidDBClusterStateFault", exception.getErrorCode());
        assertTrue(exception.getMessage().contains("still has DB instances"));
    }

    @Test
    void createDbClusterParameterGroupRoundTrip() {
        DbClusterParameterGroup created = rdsService.createDbClusterParameterGroup(
                "cpg1", "aurora-postgresql16", "test cluster group");

        assertEquals("cpg1", created.getDbClusterParameterGroupName());
        assertEquals("aurora-postgresql16", created.getDbParameterGroupFamily());

        DbClusterParameterGroup fetched = rdsService.getDbClusterParameterGroup("cpg1");
        assertEquals("cpg1", fetched.getDbClusterParameterGroupName());

        Collection<DbClusterParameterGroup> listed = rdsService.listDbClusterParameterGroups(null);
        assertEquals(1, listed.size());
    }

    @Test
    void createDbClusterParameterGroupRejectsDuplicate() {
        rdsService.createDbClusterParameterGroup("cpg1", "aurora-postgresql16", "desc");

        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbClusterParameterGroup("cpg1", "aurora-postgresql16", "desc"));

        assertEquals("DBParameterGroupAlreadyExists", exception.getErrorCode());
    }

    @Test
    void modifyDbClusterParameterGroupAppliesParameters() {
        rdsService.createDbClusterParameterGroup("cpg1", "aurora-postgresql16", "desc");

        DbClusterParameterGroup modified = rdsService.modifyDbClusterParameterGroup(
                "cpg1", java.util.Map.of("log_statement", "all", "shared_preload_libraries", "pg_stat_statements"));

        assertEquals("all", modified.getParameters().get("log_statement"));
        assertEquals("pg_stat_statements", modified.getParameters().get("shared_preload_libraries"));
    }

    @Test
    void deleteDbClusterParameterGroupMissingThrows() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.deleteDbClusterParameterGroup("nonexistent"));

        assertEquals("DBParameterGroupNotFound", exception.getErrorCode());
    }

    @Test
    void getDbClusterParameterGroupMissingThrows() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.getDbClusterParameterGroup("nonexistent"));

        assertEquals("DBParameterGroupNotFound", exception.getErrorCode());
    }

    @Test
    void restorePersistedRuntimeRestartsStandaloneInstanceWithSameVolumeAndProxyPort() {
        StorageBackend<String, DbInstance> instances = new InMemoryStorage<>();
        StorageBackend<String, DbCluster> clusters = new InMemoryStorage<>();
        StorageBackend<String, DbParameterGroup> parameterGroups = new InMemoryStorage<>();
        StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups = new InMemoryStorage<>();

        when(containerManager.start(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("initial-container", "mydb", "localhost", 5432));

        RdsService initialService = newService(containerManager, proxyManager,
                instances, clusters, parameterGroups, clusterParameterGroups);
        DbInstance created = initialService.createDbInstance("mydb", "postgres", "16.3",
                "admin", "secret", "app", "db.t3.micro",
                20, false, null, null, null);

        String persistedVolumeId = created.getVolumeId();
        int persistedProxyPort = created.getProxyPort();

        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        when(restoredContainerManager.start(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("restored-container", "mydb", "127.0.0.1", 15432));

        RdsService restoredService = newService(restoredContainerManager, restoredProxyManager,
                instances, clusters, parameterGroups, clusterParameterGroups);
        restoredService.restorePersistedRuntime();

        DbInstance restored = restoredService.getDbInstance("mydb");
        assertEquals(persistedVolumeId, restored.getVolumeId());
        assertEquals("floci-rds-" + persistedVolumeId, restored.getDockerVolumeName());
        assertEquals(persistedProxyPort, restored.getProxyPort());
        assertEquals(persistedProxyPort, restored.getEndpoint().port());
        assertEquals("restored-container", restored.getContainerId());
        assertEquals("127.0.0.1", restored.getContainerHost());
        assertEquals(15432, restored.getContainerPort());

        verify(restoredContainerManager).start(eq("mydb"), eq(persistedVolumeId),
                eq(DatabaseEngine.POSTGRES), eq("postgres:16.3-alpine"), eq("admin"), eq("secret"), eq("app"));
        verify(restoredProxyManager).startProxy(eq("mydb"), eq(DatabaseEngine.POSTGRES),
                eq(false), eq(persistedProxyPort), eq("127.0.0.1"), eq(15432),
                eq("admin"), eq("secret"), eq("app"), any());
    }

    @Test
    void restorePersistedRuntimeRestoresClusterAndMemberInstance() {
        StorageBackend<String, DbInstance> instances = new InMemoryStorage<>();
        StorageBackend<String, DbCluster> clusters = new InMemoryStorage<>();
        StorageBackend<String, DbParameterGroup> parameterGroups = new InMemoryStorage<>();
        StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups = new InMemoryStorage<>();

        when(containerManager.start(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("initial-cluster-container", "cluster1", "localhost", 5432));

        RdsService initialService = newService(containerManager, proxyManager,
                instances, clusters, parameterGroups, clusterParameterGroups);
        DbCluster cluster = initialService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null);
        DbInstance member = initialService.createDbInstance("member1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", "db.t3.medium",
                20, false, null, null, "cluster1");

        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        when(restoredContainerManager.start(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("restored-cluster-container", "cluster1", "127.0.0.1", 15432));

        RdsService restoredService = newService(restoredContainerManager, restoredProxyManager,
                instances, clusters, parameterGroups, clusterParameterGroups);
        restoredService.restorePersistedRuntime();

        DbCluster restoredCluster = restoredService.getDbCluster("cluster1");
        DbInstance restoredMember = restoredService.getDbInstance("member1");

        assertEquals(cluster.getVolumeId(), restoredCluster.getVolumeId());
        assertEquals(cluster.getProxyPort(), restoredCluster.getProxyPort());
        assertEquals(member.getProxyPort(), restoredMember.getProxyPort());
        assertEquals("restored-cluster-container", restoredCluster.getContainerId());
        assertEquals("restored-cluster-container", restoredMember.getContainerId());
        assertEquals("127.0.0.1", restoredMember.getContainerHost());
        assertEquals(15432, restoredMember.getContainerPort());

        verify(restoredContainerManager).start(eq("cluster1"), eq(cluster.getVolumeId()),
                eq(DatabaseEngine.POSTGRES), eq("postgres:16.3-alpine"), eq("admin"), eq("secret"), eq("app"));
        verify(restoredProxyManager).startProxy(eq("cluster1"), eq(DatabaseEngine.POSTGRES),
                eq(false), eq(cluster.getProxyPort()), eq("127.0.0.1"), eq(15432),
                eq("admin"), eq("secret"), eq("app"), any());
        verify(restoredProxyManager).startProxy(eq("member1"), eq(DatabaseEngine.POSTGRES),
                eq(false), eq(member.getProxyPort()), eq("127.0.0.1"), eq(15432),
                eq("admin"), eq("secret"), eq("app"), any());
    }

    private RdsService newService(RdsContainerManager containerManager,
                                  RdsProxyManager proxyManager,
                                  StorageBackend<String, DbInstance> instances,
                                  StorageBackend<String, DbCluster> clusters,
                                  StorageBackend<String, DbParameterGroup> parameterGroups,
                                  StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups) {
        return newService(containerManager, proxyManager, instances, clusters, parameterGroups, clusterParameterGroups, null);
    }

    private RdsService newService(RdsContainerManager containerManager,
                                  RdsProxyManager proxyManager,
                                  StorageBackend<String, DbInstance> instances,
                                  StorageBackend<String, DbCluster> clusters,
                                  StorageBackend<String, DbParameterGroup> parameterGroups,
                                  StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups,
                                  SecretsManagerService secretsManager) {
        return new RdsService(containerManager, proxyManager, regionResolver, config,
                instances, clusters, parameterGroups, clusterParameterGroups, new InMemoryStorage<>(), secretsManager);
    }
}
