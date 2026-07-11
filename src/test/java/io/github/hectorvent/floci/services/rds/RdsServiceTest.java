package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.container.RdsContainerHandle;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyManager;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdsServiceTest {

    private RdsService rdsService;
    private RdsContainerManager containerManager;
    private RdsProxyManager proxyManager;
    private Ec2Service ec2Service;
    private RegionResolver regionResolver;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        containerManager = mock(RdsContainerManager.class);
        proxyManager = mock(RdsProxyManager.class);
        ec2Service = mock(Ec2Service.class);
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
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());

        when(containerManager.start(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("cont-id", "id", "localhost", 5432));
        when(ec2Service.describeSubnets(eq("us-east-1"), anyList(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<String> subnetIds = invocation.getArgument(1, List.class);
                    if (subnetIds == null || subnetIds.isEmpty()) {
                        return defaultSubnets();
                    }
                    Map<String, Subnet> byId = defaultSubnets().stream()
                            .collect(Collectors.toMap(Subnet::getSubnetId, subnet -> subnet));
                    return subnetIds.stream()
                            .map(byId::get)
                            .filter(java.util.Objects::nonNull)
                            .toList();
                });
    }

    @Test
    void createDbInstanceGeneratesMissingFields() {
        DbInstance instance = rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);

        assertEquals("mydb", instance.getDbInstanceIdentifier());
        assertNotNull(instance.getDbiResourceId());
        assertTrue(instance.getDbiResourceId().startsWith("db-"));
        assertEquals("arn:aws:rds:us-east-1:123456789012:db:mydb", instance.getDbInstanceArn());
    }

    @Test
    void createAndModifyDbInstancePersistVpcSecurityGroups() {
        DbInstance instance = rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false, false, null,
                Map.of(), List.of("sg-created"));

        assertEquals(List.of("sg-created"), instance.getVpcSecurityGroupIds());

        DbInstance modified = rdsService.modifyDbInstance("mydb", null, null, null,
                List.of("sg-updated-a", "sg-updated-b"));

        assertEquals(List.of("sg-updated-a", "sg-updated-b"), modified.getVpcSecurityGroupIds());
        assertEquals(List.of("sg-updated-a", "sg-updated-b"), rdsService.getDbInstance("mydb").getVpcSecurityGroupIds());
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
        RdsService service = new RdsService(containerManager, proxyManager, ec2Service, regionResolver, config,
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
    void createDbInstanceRejectsUnknownParameterGroup() {
        AwsException exception = assertThrows(AwsException.class, () -> rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, "does-not-exist", null, null));

        assertEquals("DBParameterGroupNotFound", exception.getErrorCode());
        assertEquals("DBParameterGroupName doesn't refer to an existing DB parameter group.", exception.getMessage());
    }

    @Test
    void createDbInstanceRejectsIncompatibleParameterGroupFamily() {
        rdsService.createDbParameterGroup("pg1", "mysql8.0", "test group");

        AwsException exception = assertThrows(AwsException.class, () -> rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, "pg1", null, null));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
        assertEquals("Parameters that must not be used together were used together. Remove one of the conflicting parameters and try again.",
                exception.getMessage());
    }

    @Test
    void listDbInstancesIsCaseInsensitive() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);

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
                20, false, null, null, null, null, false);

        DbInstance modified = rdsService.modifyDbInstance("mydb", "   ", null, null);

        assertEquals("original-password", modified.getMasterPassword());
        assertFalse(modified.isIamDatabaseAuthenticationEnabled());
    }

    @Test
    void modifyDbInstanceCanToggleIamWithoutChangingPassword() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "original-password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);

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
                "sample-db-subnets", "test", java.util.List.of("subnet-default-a", "subnet-default-b"));

        assertEquals("sample-db-subnets", group.getDbSubnetGroupName());
        assertEquals(java.util.List.of("subnet-default-a", "subnet-default-b"), group.getSubnetIds());
        assertEquals(1, rdsService.listDbSubnetGroups("sample-db-subnets").size());

        rdsService.deleteDbSubnetGroup("sample-db-subnets");
        assertTrue(rdsService.listDbSubnetGroups("sample-db-subnets").isEmpty());
    }

    @Test
    void dbSubnetGroupTagsRoundTripAndMutateByArn() {
        rdsService.createDbSubnetGroup(
                "sample-db-subnets", "test", java.util.List.of("subnet-default-a", "subnet-default-b"));
        String arn = "arn:aws:rds:us-east-1:123456789012:subgrp:sample-db-subnets";

        // A subnet group with no tags must list cleanly — previously this threw DBInstanceNotFound (404)
        // because every ResourceName was resolved as a DB instance.
        assertEquals(java.util.Map.of(), rdsService.listTagsForResource(arn));

        rdsService.addTagsToResource(arn, java.util.Map.of("Name", "sample-db-subnets"));
        assertEquals(java.util.Map.of("Name", "sample-db-subnets"),
                rdsService.listTagsForResource(arn));

        rdsService.removeTagsFromResource(arn, java.util.List.of("Name"));
        assertEquals(java.util.Map.of(), rdsService.listTagsForResource(arn));
    }

    @Test
    void dbSubnetGroupTagsSurviveModify() {
        rdsService.createDbSubnetGroup(
                "sample-db-subnets", "test", java.util.List.of("subnet-default-a", "subnet-default-b"));
        String arn = "arn:aws:rds:us-east-1:123456789012:subgrp:sample-db-subnets";
        rdsService.addTagsToResource(arn, java.util.Map.of("Name", "sample-db-subnets"));

        rdsService.modifyDbSubnetGroup("sample-db-subnets", java.util.List.of("subnet-default-a"));

        assertEquals(java.util.Map.of("Name", "sample-db-subnets"),
                rdsService.listTagsForResource(arn));
    }

    @Test
    void listTagsForMissingSubnetGroupReturnsSubnetGroupNotFound() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.listTagsForResource("arn:aws:rds:us-east-1:123456789012:subgrp:missing"));

        assertEquals("DBSubnetGroupNotFoundFault", exception.getErrorCode());
    }

    @Test
    void dbClusterTagsRoundTripByArn() {
        DbCluster cluster = rdsService.createDbCluster("cluster1", "postgres", "13",
                "admin", "password", "dbname", false, null);

        assertEquals(java.util.Map.of(), rdsService.listTagsForResource(cluster.getDbClusterArn()));

        rdsService.addTagsToResource(cluster.getDbClusterArn(), java.util.Map.of("env", "test"));
        assertEquals(java.util.Map.of("env", "test"),
                rdsService.listTagsForResource(cluster.getDbClusterArn()));
    }

    @Test
    void tagOperationsRejectUnsupportedResourceArn() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.listTagsForResource("arn:aws:rds:us-east-1:123456789012:og:some-option-group"));

        assertEquals("InvalidParameterValue", exception.getErrorCode());
        // The type is valid on real AWS; the message must present this as a Floci limitation.
        assertTrue(exception.getMessage().contains("not yet implemented by Floci"));
    }

    @Test
    void tagOperationsRejectTypelessRdsArn() {
        // Real AWS rejects an RDS ARN whose resource part is not <type>:<id> with InvalidParameterValue;
        // previously this fell back to a DB-instance lookup and returned DBInstanceNotFound.
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.listTagsForResource("arn:aws:rds:us-east-1:123456789012:mydb"));

        assertEquals("InvalidParameterValue", exception.getErrorCode());
    }

    @Test
    void tagOperationsRejectNonRdsArn() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.listTagsForResource("arn:aws:s3:::some-bucket"));

        assertEquals("InvalidParameterValue", exception.getErrorCode());
    }

    @Test
    void tagOperationsRejectMalformedArn() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.listTagsForResource("arn:aws:rds:incomplete"));

        assertEquals("InvalidParameterValue", exception.getErrorCode());
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
                "admin", "password", "dbname", false, null, null, null, false);
        cluster.getDbClusterMembers().add("instance-1");

        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.deleteDbCluster("cluster1"));

        assertEquals("InvalidDBClusterStateFault", exception.getErrorCode());
        assertTrue(exception.getMessage().contains("still has DB instances"));
    }

    @Test
    void mockModeCreatesClusterAvailableWithoutContainerOrProxy() {
        when(config.services().rds().mock()).thenReturn(true);

        DbCluster cluster = rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null);

        assertEquals(DbInstanceStatus.AVAILABLE, cluster.getStatus());
        assertEquals("localhost", cluster.getEndpoint().address());
        assertTrue(cluster.getEndpoint().port() > 0);
        assertNull(cluster.getContainerId());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any());
        verify(proxyManager, never()).startProxy(any(), any(), anyBoolean(), anyInt(), any(), anyInt(),
                any(), any(), any(), any());
    }

    @Test
    void mockModeCreatesClusterInstanceAvailableWithoutContainer() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null);

        DbInstance instance = rdsService.createDbInstance("inst1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", "db.serverless",
                0, false, null, null, "cluster1");

        assertEquals(DbInstanceStatus.AVAILABLE, instance.getStatus());
        assertEquals("localhost", instance.getEndpoint().address());
        // No Docker volume name may be persisted: the mock cluster has a null volume id, so the
        // fallback would fabricate a name that a later non-mock restore could try to reference.
        assertNull(instance.getDockerVolumeName());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any());
        verify(proxyManager, never()).startProxy(any(), any(), anyBoolean(), anyInt(), any(), anyInt(),
                any(), any(), any(), any());
    }

    @Test
    void mockModeCreatesStandaloneInstanceAvailableWithoutContainer() {
        when(config.services().rds().mock()).thenReturn(true);

        DbInstance instance = rdsService.createDbInstance("standalone", "postgres", "16",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        assertEquals(DbInstanceStatus.AVAILABLE, instance.getStatus());
        assertNull(instance.getContainerId());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void mockModeDeleteClusterSkipsDockerCleanup() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null);

        rdsService.deleteDbCluster("cluster1");

        verify(containerManager, never()).stop(any());
        verify(containerManager, never()).removeVolume(any(), any());
    }

    @Test
    void mockModeDeleteStandaloneInstanceSkipsDockerCleanup() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbInstance("standalone", "postgres", "16",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        rdsService.deleteDbInstance("standalone");

        verify(containerManager, never()).stop(any());
        verify(containerManager, never()).removeVolume(any(), any());
    }

    @Test
    void mockModeAssignsDistinctEndpointPorts() {
        when(config.services().rds().mock()).thenReturn(true);

        DbCluster a = rdsService.createDbCluster("cluster-a", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null);
        DbCluster b = rdsService.createDbCluster("cluster-b", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null);

        assertNotEquals(a.getEndpoint().port(), b.getEndpoint().port());
    }

    @Test
    void mockModeRebootSkipsContainerAndProxy() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbInstance("standalone", "postgres", "16",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        DbInstance rebooted = rdsService.rebootDbInstance("standalone");

        assertEquals(DbInstanceStatus.AVAILABLE, rebooted.getStatus());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any());
        verify(containerManager, never()).stop(any());
        verify(proxyManager, never()).startProxy(any(), any(), anyBoolean(), anyInt(), any(), anyInt(),
                any(), any(), any(), any());
    }

    @Test
    void createDbClusterRejectsUnknownClusterParameterGroup() {
        AwsException exception = assertThrows(AwsException.class, () -> rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, "does-not-exist"));

        assertEquals("DBClusterParameterGroupNotFound", exception.getErrorCode());
        assertEquals("DBClusterParameterGroupName doesn't refer to an existing DB cluster parameter group.", exception.getMessage());
    }

    @Test
    void createDbClusterRejectsIncompatibleClusterParameterGroupFamily() {
        rdsService.createDbClusterParameterGroup("cpg1", "aurora-mysql8.0", "test group");

        AwsException exception = assertThrows(AwsException.class, () -> rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, "cpg1"));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
        assertEquals("Parameters that must not be used together were used together. Remove one of the conflicting parameters and try again.",
                exception.getMessage());
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
    void createDbSubnetGroupRejectsDuplicateWithModelCode() {
        rdsService.createDbSubnetGroup("my-subnet-group", "desc", List.of("subnet-default-a", "subnet-default-b"));

        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbSubnetGroup("my-subnet-group", "desc", List.of("subnet-default-a", "subnet-default-b")));

        assertEquals("DBSubnetGroupAlreadyExists", exception.getErrorCode());
    }

    @Test
    void createDbSubnetGroupPopulatesArn() {
        DbSubnetGroup group = rdsService.createDbSubnetGroup("my-subnet-group", "desc",
                List.of("subnet-default-a", "subnet-default-b"));

        assertEquals("arn:aws:rds:us-east-1:123456789012:subgrp:my-subnet-group", group.getDbSubnetGroupArn());
    }

    @Test
    void createDbSubnetGroupUsesSuppliedRegionForSubnetLookup() {
        List<String> subnetIds = List.of("subnet-west-a", "subnet-west-b");
        when(ec2Service.describeSubnets(eq("us-west-2"), eq(subnetIds), eq(Map.of())))
                .thenReturn(List.of(
                        subnet("subnet-west-a", "vpc-west", "us-west-2a"),
                        subnet("subnet-west-b", "vpc-west", "us-west-2b")));

        DbSubnetGroup group = rdsService.createDbSubnetGroup("west-subnets", "desc", subnetIds, "us-west-2");

        assertEquals("vpc-west", group.getVpcId());
        assertEquals("arn:aws:rds:us-west-2:123456789012:subgrp:west-subnets", group.getDbSubnetGroupArn());
        verify(ec2Service).describeSubnets(eq("us-west-2"), eq(subnetIds), eq(Map.of()));
    }

    @Test
    void createDbSubnetGroupRequiresSubnetIdsWithMissingParameter() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbSubnetGroup("my-subnet-group", "desc", List.of()));

        assertEquals("MissingParameter", exception.getErrorCode());
    }

    @Test
    void createDbInstanceMultiAzRequiresSubnetGroupCoverageAcrossAvailabilityZones() {
        StorageBackend<String, DbSubnetGroup> subnetGroups = new InMemoryStorage<>();
        subnetGroups.put("single-az-group", new DbSubnetGroup(
                "single-az-group",
                "desc",
                "vpc-default",
                List.of("subnet-a", "subnet-b"),
                Map.of("subnet-a", "us-east-1a", "subnet-b", "us-east-1a")));
        RdsService service = newService(containerManager, proxyManager,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), subnetGroups);

        AwsException exception = assertThrows(AwsException.class, () ->
                service.createDbInstance("mydb", "postgres", "13",
                        "admin", "password", "dbname", "db.t3.micro",
                        20, false, null, "single-az-group", null, null, true));

        assertEquals("DBSubnetGroupDoesNotCoverEnoughAZs", exception.getErrorCode());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createDbClusterRejectsAvailabilityZoneWhenMultiAzEnabled() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbCluster("cluster1", "postgres", "13",
                        "admin", "password", "dbname", false,
                        null, null, "us-east-1a", true));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void resolveDbSubnetGroupViewReturnsStoredCustomGroup() {
        rdsService.createDbSubnetGroup("my-subnet-group", "desc", List.of("subnet-default-a", "subnet-default-b"));

        DbSubnetGroup group = rdsService.resolveDbSubnetGroupView("my-subnet-group");

        assertEquals("my-subnet-group", group.getDbSubnetGroupName());
        assertEquals("arn:aws:rds:us-east-1:123456789012:subgrp:my-subnet-group", group.getDbSubnetGroupArn());
    }

    @Test
    void resolveDbSubnetGroupViewReturnsDefaultGroupForBlankName() {
        DbSubnetGroup group = rdsService.resolveDbSubnetGroupView(null);

        assertEquals("default", group.getDbSubnetGroupName());
        assertEquals("arn:aws:rds:us-east-1:123456789012:subgrp:default", group.getDbSubnetGroupArn());
    }

    @Test
    void resolveDbSubnetGroupViewUsesSuppliedRegionForDefaultGroup() {
        when(ec2Service.describeSubnets(eq("us-west-2"), anyList(), any()))
                .thenReturn(List.of(
                        subnet("subnet-west-a", "vpc-west", "us-west-2a"),
                        subnet("subnet-west-b", "vpc-west", "us-west-2b")));

        DbSubnetGroup group = rdsService.resolveDbSubnetGroupView(null, "us-west-2");

        assertEquals("default", group.getDbSubnetGroupName());
        assertEquals("vpc-west", group.getVpcId());
        assertEquals("arn:aws:rds:us-west-2:123456789012:subgrp:default", group.getDbSubnetGroupArn());
        assertEquals(Map.of("subnet-west-a", "us-west-2a", "subnet-west-b", "us-west-2b"),
                group.getSubnetAvailabilityZones());
    }

    @Test
    void getDbSubnetGroupUsesSuppliedRegionForDefaultGroup() {
        when(ec2Service.describeSubnets(eq("us-west-2"), anyList(), any()))
                .thenReturn(List.of(
                        subnet("subnet-west-a", "vpc-west", "us-west-2a"),
                        subnet("subnet-west-b", "vpc-west", "us-west-2b")));

        DbSubnetGroup group = rdsService.getDbSubnetGroup("default", "us-west-2");

        assertEquals("default", group.getDbSubnetGroupName());
        assertEquals("vpc-west", group.getVpcId());
        assertEquals("arn:aws:rds:us-west-2:123456789012:subgrp:default", group.getDbSubnetGroupArn());
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

        assertEquals("DBClusterParameterGroupNotFound", exception.getErrorCode());
        assertEquals("DBClusterParameterGroupName doesn't refer to an existing DB cluster parameter group.", exception.getMessage());
    }

    @Test
    void getDbClusterParameterGroupMissingThrows() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.getDbClusterParameterGroup("nonexistent"));

        assertEquals("DBClusterParameterGroupNotFound", exception.getErrorCode());
        assertEquals("DBClusterParameterGroupName doesn't refer to an existing DB cluster parameter group.", exception.getMessage());
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
                instances, clusters, parameterGroups, clusterParameterGroups, new InMemoryStorage<>());
        DbInstance created = initialService.createDbInstance("mydb", "postgres", "16.3",
                "admin", "secret", "app", "db.t3.micro",
                20, false, null, null, null, null, false);

        String persistedVolumeId = created.getVolumeId();
        int persistedProxyPort = created.getProxyPort();

        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        when(restoredContainerManager.start(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("restored-container", "mydb", "127.0.0.1", 15432));

        RdsService restoredService = newService(restoredContainerManager, restoredProxyManager,
                instances, clusters, parameterGroups, clusterParameterGroups, new InMemoryStorage<>());
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
                instances, clusters, parameterGroups, clusterParameterGroups, new InMemoryStorage<>());
        DbCluster cluster = initialService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null, null, null, false);
        DbInstance member = initialService.createDbInstance("member1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", "db.t3.medium",
                20, false, null, null, "cluster1", null, false);

        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        when(restoredContainerManager.start(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("restored-cluster-container", "cluster1", "127.0.0.1", 15432));

        RdsService restoredService = newService(restoredContainerManager, restoredProxyManager,
                instances, clusters, parameterGroups, clusterParameterGroups, new InMemoryStorage<>());
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
                                  StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups,
                                  StorageBackend<String, DbSubnetGroup> subnetGroups) {
        return new RdsService(containerManager, proxyManager, ec2Service, regionResolver, config,
                instances, clusters, parameterGroups, clusterParameterGroups, subnetGroups);
    }

    private RdsService newService(RdsContainerManager containerManager,
                                  RdsProxyManager proxyManager,
                                  StorageBackend<String, DbInstance> instances,
                                  StorageBackend<String, DbCluster> clusters,
                                  StorageBackend<String, DbParameterGroup> parameterGroups,
                                  StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups,
                                  SecretsManagerService secretsManager) {
        return new RdsService(containerManager, proxyManager, ec2Service, regionResolver, config,
                instances, clusters, parameterGroups, clusterParameterGroups, new InMemoryStorage<>(),
                secretsManager, null);
    }

    private static List<Subnet> defaultSubnets() {
        return List.of(
                subnet("subnet-default-a", "vpc-default", "us-east-1a"),
                subnet("subnet-default-b", "vpc-default", "us-east-1b"));
    }

    private static Subnet subnet(String subnetId, String vpcId, String availabilityZone) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setVpcId(vpcId);
        subnet.setAvailabilityZone(availabilityZone);
        return subnet;
    }
}
