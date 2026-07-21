package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.container.RdsContainerHandle;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbProxy;
import io.github.hectorvent.floci.services.rds.model.DbProxyAuth;
import io.github.hectorvent.floci.services.rds.model.DbProxyTarget;
import io.github.hectorvent.floci.services.rds.model.DbProxyTargetGroup;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
import io.github.hectorvent.floci.services.rds.proxy.RdsAuthProxy;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyManager;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdsServiceTest {

    private static final String PROXY_ROLE_ARN = "arn:aws:iam::123456789012:role/proxy";
    private static final List<String> PROXY_SUBNET_IDS =
            List.of("subnet-default-a", "subnet-default-b");
    private static final List<DbProxyAuth> PROXY_AUTH = List.of(new DbProxyAuth(
            "SECRETS", "arn:aws:secretsmanager:us-east-1:123456789012:secret:db-AbCdEf",
            "DISABLED", null, null));

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
        when(config.defaultAccountId()).thenReturn("123456789012");
        when(servicesConfig.rds()).thenReturn(rdsConfig);
        when(rdsConfig.proxyBasePort()).thenReturn(7000);
        when(rdsConfig.proxyMaxPort()).thenReturn(7099);
        when(rdsConfig.defaultPostgresImage()).thenReturn("postgres:16-alpine");
        when(rdsConfig.defaultMysqlImage()).thenReturn("mysql:8.0");
        when(rdsConfig.defaultMariadbImage()).thenReturn("mariadb:11");

        rdsService = newService(containerManager, proxyManager,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());

        when(containerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("cont-id", "id", "localhost", 5432));
        when(ec2Service.describeSubnets(any(), anyList(), any()))
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

        verify(containerManager).start(
                eq("arn:aws:rds:us-east-1:123456789012:db:mydb"), eq("mydb"),
                any(), any(), eq(DatabaseEngine.POSTGRES),
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
        AwsException missing = assertThrows(AwsException.class,
                () -> rdsService.listDbSubnetGroups("sample-db-subnets"));
        assertEquals("DBSubnetGroupNotFoundFault", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void listDbSubnetGroupsFaultsForMissingName() {
        AwsException missing = assertThrows(AwsException.class,
                () -> rdsService.listDbSubnetGroups("does-not-exist"));
        assertEquals("DBSubnetGroupNotFoundFault", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void listDbSubnetGroupsStillResolvesSyntheticDefault() {
        Collection<DbSubnetGroup> groups = rdsService.listDbSubnetGroups("default");
        assertEquals(1, groups.size());
        assertEquals("default", groups.iterator().next().getDbSubnetGroupName());
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
    void sameNamedSubnetGroupsAreRegionScopedIncludingTagsAndDeletion() {
        List<String> westSubnetIds = List.of("subnet-west-a", "subnet-west-b");
        when(ec2Service.describeSubnets(eq("us-west-2"), eq(westSubnetIds), eq(Map.of())))
                .thenReturn(List.of(
                        subnet("subnet-west-a", "vpc-west", "us-west-2a"),
                        subnet("subnet-west-b", "vpc-west", "us-west-2b")));

        DbSubnetGroup east = rdsService.createDbSubnetGroup(
                "regional-subnets", "east", PROXY_SUBNET_IDS, "us-east-1");
        DbSubnetGroup west = rdsService.createDbSubnetGroup(
                "regional-subnets", "west", westSubnetIds, "us-west-2");

        rdsService.addTagsToResource(
                east.getDbSubnetGroupArn(), Map.of("region", "east"), "us-east-1");
        rdsService.addTagsToResource(
                west.getDbSubnetGroupArn(), Map.of("region", "west"), "us-west-2");

        assertEquals("vpc-default", rdsService.getDbSubnetGroup(
                "regional-subnets", "us-east-1").getVpcId());
        assertEquals("vpc-west", rdsService.getDbSubnetGroup(
                "regional-subnets", "us-west-2").getVpcId());
        assertEquals(Map.of("region", "east"), rdsService.listTagsForResource(
                east.getDbSubnetGroupArn(), "us-east-1"));
        assertEquals(Map.of("region", "west"), rdsService.listTagsForResource(
                west.getDbSubnetGroupArn(), "us-west-2"));

        rdsService.deleteDbSubnetGroup("regional-subnets", "us-west-2");

        assertEquals(east.getDbSubnetGroupArn(), rdsService.getDbSubnetGroup(
                "regional-subnets", "us-east-1").getDbSubnetGroupArn());
        AwsException missingWest = assertThrows(AwsException.class, () ->
                rdsService.getDbSubnetGroup("regional-subnets", "us-west-2"));
        assertEquals("DBSubnetGroupNotFoundFault", missingWest.getErrorCode());
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
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
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
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
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
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
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
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void mockModeDeleteClusterSkipsDockerCleanup() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "password", "dbname", false, null);

        rdsService.deleteDbCluster("cluster1");

        verify(containerManager, never()).stop(any());
        verify(containerManager, never()).removeVolume(any(), any(), any());
    }

    @Test
    void mockModeDeleteStandaloneInstanceSkipsDockerCleanup() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbInstance("standalone", "postgres", "16",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null);

        rdsService.deleteDbInstance("standalone");

        verify(containerManager, never()).stop(any());
        verify(containerManager, never()).removeVolume(any(), any(), any());
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
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(containerManager, never()).stop(any());
        verify(proxyManager, never()).startProxy(any(), any(), anyBoolean(), anyInt(), any(), anyInt(),
                any(), any(), any(), any());
    }

    @Test
    void rebootStopsBeforeRestartAndPersistsFailedStatusWhenCleanupFails() {
        DbInstance instance = rdsService.createDbInstance(
                "standalone", "postgres", "16", "admin", "password", "dbname",
                "db.t3.micro", 20, false, null, null, null);
        doThrow(new IllegalStateException("Docker cleanup failed"))
                .when(containerManager).stop(any());

        assertThrows(IllegalStateException.class, () ->
                rdsService.rebootDbInstance("standalone"));

        DbInstance failed = rdsService.getDbInstance("standalone");
        assertEquals(DbInstanceStatus.FAILED, failed.getStatus());
        assertEquals(instance.getContainerId(), failed.getContainerId());
        verify(containerManager, times(1)).start(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(proxyManager).stopProxy("rds-resource:" + instance.getDbInstanceArn());
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
    void parameterGroupsAreRegionScopedAndRegionalResourcesCannotClaimForeignGroups() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbParameterGroup("regional-pg", "postgres16", "east", "us-east-1");
        rdsService.createDbParameterGroup("regional-pg", "postgres16", "west", "us-west-2");
        rdsService.modifyDbParameterGroup(
                "regional-pg", Map.of("application_name", "east"), "us-east-1");
        rdsService.modifyDbParameterGroup(
                "regional-pg", Map.of("application_name", "west"), "us-west-2");

        assertEquals("east", rdsService.getDbParameterGroup(
                "regional-pg", "us-east-1").getParameters().get("application_name"));
        assertEquals("west", rdsService.getDbParameterGroup(
                "regional-pg", "us-west-2").getParameters().get("application_name"));

        rdsService.createDbClusterParameterGroup(
                "regional-cpg", "aurora-postgresql16", "east", "us-east-1");
        rdsService.createDbClusterParameterGroup(
                "regional-cpg", "aurora-postgresql16", "west", "us-west-2");
        rdsService.modifyDbClusterParameterGroup(
                "regional-cpg", Map.of("log_statement", "none"), "us-east-1");
        rdsService.modifyDbClusterParameterGroup(
                "regional-cpg", Map.of("log_statement", "all"), "us-west-2");

        assertEquals("none", rdsService.getDbClusterParameterGroup(
                "regional-cpg", "us-east-1").getParameters().get("log_statement"));
        assertEquals("all", rdsService.getDbClusterParameterGroup(
                "regional-cpg", "us-west-2").getParameters().get("log_statement"));

        rdsService.createDbParameterGroup(
                "east-only-pg", "postgres16", "east", "us-east-1");
        AwsException instanceGroupMissing = assertThrows(AwsException.class, () ->
                rdsService.createDbInstance(
                        "west-db", "postgres", "16.3", "admin", "secret", "app",
                        "db.t3.micro", 20, false, "east-only-pg", null, null,
                        null, false, false, null, Map.of(), List.of(), "us-west-2"));
        assertEquals("DBParameterGroupNotFound", instanceGroupMissing.getErrorCode());

        rdsService.createDbClusterParameterGroup(
                "east-only-cpg", "aurora-postgresql16", "east", "us-east-1");
        AwsException clusterGroupMissing = assertThrows(AwsException.class, () ->
                rdsService.createDbCluster(
                        "west-cluster", "aurora-postgresql", "16.3", "admin", "secret",
                        "app", false, "east-only-cpg", null, null, false, "us-west-2"));
        assertEquals("DBClusterParameterGroupNotFound", clusterGroupMissing.getErrorCode());

        rdsService.deleteDbParameterGroup("regional-pg", "us-east-1");
        rdsService.deleteDbClusterParameterGroup("regional-cpg", "us-east-1");
        assertEquals("west", rdsService.getDbParameterGroup(
                "regional-pg", "us-west-2").getDescription());
        assertEquals("west", rdsService.getDbClusterParameterGroup(
                "regional-cpg", "us-west-2").getDescription());
    }

    @Test
    void rawLegacyParameterGroupsCannotBeClaimedByANonDefaultAccount() {
        String defaultAccount = "123456789012";
        String otherAccount = "222222222222";
        RegionResolver otherResolver = new RegionResolver("us-east-1", otherAccount);
        InMemoryStorage<String, DbParameterGroup> rawParameterGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbClusterParameterGroup> rawClusterParameterGroups =
                new InMemoryStorage<>();
        rawParameterGroups.put("legacy-pg", new DbParameterGroup(
                "legacy-pg", "postgres16", "legacy"));
        rawClusterParameterGroups.put("legacy-cpg", new DbClusterParameterGroup(
                "legacy-cpg", "aurora-postgresql16", "legacy"));
        RdsService service = new RdsService(
                containerManager, proxyManager, ec2Service, otherResolver, config,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(rawParameterGroups, null, defaultAccount),
                new AccountAwareStorageBackend<>(rawClusterParameterGroups, null, defaultAccount),
                new InMemoryStorage<>());

        assertEquals("DBParameterGroupNotFound", assertThrows(AwsException.class, () ->
                service.getDbParameterGroup("legacy-pg", "us-east-1")).getErrorCode());
        assertEquals("DBClusterParameterGroupNotFound", assertThrows(AwsException.class, () ->
                service.getDbClusterParameterGroup("legacy-cpg", "us-east-1")).getErrorCode());
        assertTrue(rawParameterGroups.get("legacy-pg").isPresent());
        assertTrue(rawClusterParameterGroups.get("legacy-cpg").isPresent());
        assertTrue(rawParameterGroups.get(otherAccount + "/us-east-1::legacy-pg").isEmpty());
        assertTrue(rawClusterParameterGroups.get(
                otherAccount + "/us-east-1::legacy-cpg").isEmpty());
    }

    @Test
    void unfilteredGroupListsMigrateSafeRawLegacyStateForTheDefaultAccount() {
        String accountId = "123456789012";
        InMemoryStorage<String, DbSubnetGroup> rawSubnetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbParameterGroup> rawParameterGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbClusterParameterGroup> rawClusterParameterGroups =
                new InMemoryStorage<>();
        DbSubnetGroup subnetGroup = new DbSubnetGroup(
                "legacy-subnets", "legacy", "vpc-default", PROXY_SUBNET_IDS,
                Map.of("subnet-default-a", "us-east-1a", "subnet-default-b", "us-east-1b"));
        subnetGroup.setDbSubnetGroupArn(
                "arn:aws:rds:us-east-1:" + accountId + ":subgrp:legacy-subnets");
        rawSubnetGroups.put("legacy-subnets", subnetGroup);
        rawParameterGroups.put("legacy-pg", new DbParameterGroup(
                "legacy-pg", "postgres16", "legacy"));
        rawClusterParameterGroups.put("legacy-cpg", new DbClusterParameterGroup(
                "legacy-cpg", "aurora-postgresql16", "legacy"));
        RdsService service = new RdsService(
                containerManager, proxyManager, ec2Service, regionResolver, config,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(rawParameterGroups, null, accountId),
                new AccountAwareStorageBackend<>(rawClusterParameterGroups, null, accountId),
                new AccountAwareStorageBackend<>(rawSubnetGroups, null, accountId));

        assertTrue(service.listDbSubnetGroups(null, "us-east-1").stream()
                .anyMatch(group -> "legacy-subnets".equals(group.getDbSubnetGroupName())));
        assertTrue(service.listDbParameterGroups(null, "us-east-1").stream()
                .anyMatch(group -> "legacy-pg".equals(group.getDbParameterGroupName())));
        assertTrue(service.listDbClusterParameterGroups(null, "us-east-1").stream()
                .anyMatch(group -> "legacy-cpg".equals(group.getDbClusterParameterGroupName())));
        assertTrue(rawSubnetGroups.get("legacy-subnets").isEmpty());
        assertTrue(rawParameterGroups.get("legacy-pg").isEmpty());
        assertTrue(rawClusterParameterGroups.get("legacy-cpg").isEmpty());
        assertTrue(rawSubnetGroups.get(accountId + "/us-east-1::legacy-subnets").isPresent());
        assertTrue(rawParameterGroups.get(accountId + "/us-east-1::legacy-pg").isPresent());
        assertTrue(rawClusterParameterGroups.get(accountId + "/us-east-1::legacy-cpg").isPresent());
    }

    @Test
    void corruptCanonicalGroupStateFailsClosedAndCreateDoesNotOverwriteIt() {
        String accountId = "123456789012";
        InMemoryStorage<String, DbSubnetGroup> rawSubnetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbParameterGroup> rawParameterGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbClusterParameterGroup> rawClusterParameterGroups =
                new InMemoryStorage<>();
        DbSubnetGroup wrongSubnetName = new DbSubnetGroup(
                "other-subnets", "corrupt", "vpc-default", PROXY_SUBNET_IDS, Map.of());
        wrongSubnetName.setDbSubnetGroupArn(
                "arn:aws:rds:us-east-1:" + accountId + ":subgrp:expected-subnets");
        DbParameterGroup wrongParameterName = new DbParameterGroup(
                "other-pg", "postgres16", "corrupt");
        wrongParameterName.setRegion("us-east-1");
        DbParameterGroup legacyParameter = new DbParameterGroup(
                "expected-pg", "postgres16", "legacy");
        DbClusterParameterGroup wrongClusterParameterName = new DbClusterParameterGroup(
                "other-cpg", "aurora-postgresql16", "corrupt");
        wrongClusterParameterName.setRegion("us-east-1");
        rawSubnetGroups.put(accountId + "/us-east-1::expected-subnets", wrongSubnetName);
        rawParameterGroups.put(accountId + "/us-east-1::expected-pg", wrongParameterName);
        rawParameterGroups.put(accountId + "/expected-pg", legacyParameter);
        rawClusterParameterGroups.put(
                accountId + "/us-east-1::expected-cpg", wrongClusterParameterName);
        RdsService service = new RdsService(
                containerManager, proxyManager, ec2Service, regionResolver, config,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(rawParameterGroups, null, accountId),
                new AccountAwareStorageBackend<>(rawClusterParameterGroups, null, accountId),
                new AccountAwareStorageBackend<>(rawSubnetGroups, null, accountId));

        assertThrows(AwsException.class, () ->
                service.getDbSubnetGroup("expected-subnets", "us-east-1"));
        assertThrows(AwsException.class, () ->
                service.getDbParameterGroup("expected-pg", "us-east-1"));
        assertThrows(AwsException.class, () ->
                service.getDbClusterParameterGroup("expected-cpg", "us-east-1"));
        assertEquals("DBSubnetGroupAlreadyExists", assertThrows(AwsException.class, () ->
                service.createDbSubnetGroup(
                        "expected-subnets", "new", PROXY_SUBNET_IDS, "us-east-1"))
                .getErrorCode());
        assertEquals("DBParameterGroupAlreadyExists", assertThrows(AwsException.class, () ->
                service.createDbParameterGroup(
                        "expected-pg", "postgres16", "new", "us-east-1"))
                .getErrorCode());
        assertEquals("DBParameterGroupAlreadyExists", assertThrows(AwsException.class, () ->
                service.createDbClusterParameterGroup(
                        "expected-cpg", "aurora-postgresql16", "new", "us-east-1"))
                .getErrorCode());
        assertSame(wrongSubnetName, rawSubnetGroups.get(
                accountId + "/us-east-1::expected-subnets").orElseThrow());
        assertSame(wrongParameterName, rawParameterGroups.get(
                accountId + "/us-east-1::expected-pg").orElseThrow());
        assertSame(legacyParameter, rawParameterGroups.get(
                accountId + "/expected-pg").orElseThrow());
        assertSame(wrongClusterParameterName, rawClusterParameterGroups.get(
                accountId + "/us-east-1::expected-cpg").orElseThrow());
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
        DbSubnetGroup singleAzGroup = new DbSubnetGroup(
                "single-az-group",
                "desc",
                "vpc-default",
                List.of("subnet-a", "subnet-b"),
                Map.of("subnet-a", "us-east-1a", "subnet-b", "us-east-1a"));
        singleAzGroup.setDbSubnetGroupArn(
                "arn:aws:rds:us-east-1:123456789012:subgrp:single-az-group");
        subnetGroups.put("single-az-group", singleAzGroup);
        RdsService service = newService(containerManager, proxyManager,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), subnetGroups);

        AwsException exception = assertThrows(AwsException.class, () ->
                service.createDbInstance("mydb", "postgres", "13",
                        "admin", "password", "dbname", "db.t3.micro",
                        20, false, null, "single-az-group", null, null, true));

        assertEquals("DBSubnetGroupDoesNotCoverEnoughAZs", exception.getErrorCode());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createDbClusterRejectsAvailabilityZoneWhenMultiAzEnabled() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbCluster("cluster1", "postgres", "13",
                        "admin", "password", "dbname", false,
                        null, null, "us-east-1a", true));

        assertEquals("InvalidParameterCombination", exception.getErrorCode());
        verify(containerManager, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
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
    void restorePersistedRuntimeReusesLegacyStandaloneStorageAndProxyPort() {
        StorageBackend<String, DbInstance> instances = new InMemoryStorage<>();
        StorageBackend<String, DbCluster> clusters = new InMemoryStorage<>();
        StorageBackend<String, DbParameterGroup> parameterGroups = new InMemoryStorage<>();
        StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups = new InMemoryStorage<>();

        when(containerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("initial-container", "mydb", "localhost", 5432));

        RdsService initialService = newService(containerManager, proxyManager,
                instances, clusters, parameterGroups, clusterParameterGroups, new InMemoryStorage<>());
        DbInstance created = initialService.createDbInstance("mydb", "postgres", "16.3",
                "admin", "secret", "app", "db.t3.micro",
                20, false, null, null, null, null, false);

        String persistedVolumeId = created.getVolumeId();
        int persistedProxyPort = created.getProxyPort();
        created.setContainerStorageResourceId(null);
        created.setDockerVolumeName("floci-rds-" + persistedVolumeId);

        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        when(restoredContainerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("restored-container", "mydb", "127.0.0.1", 15432));

        RdsService restoredService = newService(restoredContainerManager, restoredProxyManager,
                instances, clusters, parameterGroups, clusterParameterGroups, new InMemoryStorage<>());
        restoredService.restorePersistedRuntime();

        DbInstance restored = restoredService.getDbInstance("mydb");
        assertEquals(persistedVolumeId, restored.getVolumeId());
        assertEquals("mydb", restored.getContainerStorageResourceId());
        assertEquals("floci-rds-" + persistedVolumeId, restored.getDockerVolumeName());
        assertEquals(persistedProxyPort, restored.getProxyPort());
        assertEquals(persistedProxyPort, restored.getEndpoint().port());
        assertEquals("restored-container", restored.getContainerId());
        assertEquals("127.0.0.1", restored.getContainerHost());
        assertEquals(15432, restored.getContainerPort());

        verify(restoredContainerManager).start(
                eq(restored.getDbInstanceArn()), eq("mydb"),
                eq(restored.getContainerStorageResourceId()),
                eq(restored.getDockerVolumeName()), eq(DatabaseEngine.POSTGRES),
                eq("postgres:16.3-alpine"), eq("admin"), eq("secret"), eq("app"));
        verify(restoredProxyManager).startProxy(
                eq("rds-resource:" + restored.getDbInstanceArn()), eq(DatabaseEngine.POSTGRES),
                eq(false), eq(persistedProxyPort), eq("127.0.0.1"), eq(15432),
                eq("admin"), eq("secret"), eq("app"), any());

        restoredService.deleteDbInstance("mydb");
        verify(restoredContainerManager).removeVolume(
                restored.getDbInstanceArn(), "mydb", "floci-rds-" + persistedVolumeId);
    }

    @Test
    void restorePersistedRuntimeRestoresClusterAndMemberInstance() {
        StorageBackend<String, DbInstance> instances = new InMemoryStorage<>();
        StorageBackend<String, DbCluster> clusters = new InMemoryStorage<>();
        StorageBackend<String, DbParameterGroup> parameterGroups = new InMemoryStorage<>();
        StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups = new InMemoryStorage<>();

        when(containerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
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
        when(restoredContainerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
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

        verify(restoredContainerManager).start(
                eq(restoredCluster.getDbClusterArn()), eq("cluster1"),
                eq(restoredCluster.getContainerStorageResourceId()),
                eq(restoredCluster.getDockerVolumeName()), eq(DatabaseEngine.POSTGRES),
                eq("postgres:16.3-alpine"), eq("admin"), eq("secret"), eq("app"));
        verify(restoredProxyManager).startProxy(
                eq("rds-resource:" + restoredCluster.getDbClusterArn()), eq(DatabaseEngine.POSTGRES),
                eq(false), eq(cluster.getProxyPort()), eq("127.0.0.1"), eq(15432),
                eq("admin"), eq("secret"), eq("app"), any());
        verify(restoredProxyManager).startProxy(
                eq("rds-resource:" + restoredMember.getDbInstanceArn()), eq(DatabaseEngine.POSTGRES),
                eq(false), eq(member.getProxyPort()), eq("127.0.0.1"), eq(15432),
                eq("admin"), eq("secret"), eq("app"), any());
    }

    @Test
    void failedInstanceRestoreClearsRuntimeStateAndReleasesItsEndpointPort() {
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        instances.put("broken", persistedInstance("broken", "123456789012", "secret", 7000));
        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        String runtimeId = "arn:aws:rds:us-east-1:123456789012:db:broken";
        RdsContainerHandle restoredHandle = new RdsContainerHandle(
                "restored-container", runtimeId, "broken", "127.0.0.1", 15432);
        RdsContainerHandle replacementHandle = new RdsContainerHandle(
                "replacement-container", "arn:aws:rds:us-east-1:123456789012:db:replacement",
                "replacement", "127.0.0.1", 15433);
        when(restoredContainerManager.start(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(restoredHandle, replacementHandle);
        org.mockito.Mockito.doThrow(new IllegalStateException("relay failed"))
                .doNothing()
                .doNothing()
                .when(restoredProxyManager).startProxy(
                        any(), any(), anyBoolean(), anyInt(), any(), anyInt(),
                        any(), any(), any(), any());
        org.mockito.Mockito.doThrow(new IllegalStateException("cleanup failed"))
                .doNothing()
                .doNothing()
                .when(restoredProxyManager).stopProxy(any());
        org.mockito.Mockito.doThrow(new IllegalStateException("restore container cleanup failed"))
                .doThrow(new IllegalStateException("delete container cleanup failed"))
                .doNothing()
                .when(restoredContainerManager).stop(org.mockito.ArgumentMatchers.argThat(
                        handle -> runtimeId.equals(handle.getRuntimeId())));
        RdsService restoredService = newService(
                restoredContainerManager, restoredProxyManager, instances,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        restoredService.restorePersistedRuntime();

        DbInstance failed = restoredService.getDbInstance("broken");
        assertEquals(DbInstanceStatus.FAILED, failed.getStatus());
        assertNull(failed.getEndpoint());
        assertEquals("restored-container", failed.getContainerId());
        assertNull(failed.getContainerHost());
        assertEquals(0, failed.getContainerPort());
        assertEquals(0, failed.getProxyPort());

        assertThrows(IllegalStateException.class, () ->
                restoredService.deleteDbInstance("broken"));
        assertEquals(DbInstanceStatus.DELETING,
                restoredService.getDbInstance("broken").getStatus());
        verify(restoredContainerManager, never()).removeVolume(any(), any(), any());

        assertDoesNotThrow(() -> restoredService.deleteDbInstance("broken"));
        assertThrows(AwsException.class, () -> restoredService.getDbInstance("broken"));
        verify(restoredContainerManager, times(3)).stop(
                org.mockito.ArgumentMatchers.argThat(
                        handle -> runtimeId.equals(handle.getRuntimeId())));
        verify(restoredContainerManager).removeVolume(any(), any(), any());

        DbInstance replacement = restoredService.createDbInstance(
                "replacement", "postgres", "16.3", "admin", "secret", "app",
                "db.t3.micro", 20, false, null, null, null);
        assertEquals(7000, replacement.getEndpoint().port());
    }

    @Test
    void failedInstanceStartPreservesPersistedContainerIdentityForDeletion() {
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        DbInstance persisted = persistedInstance(
                "broken", "123456789012", "secret", 7000);
        persisted.setContainerId("persisted-container");
        instances.put("broken", persisted);
        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        when(restoredContainerManager.start(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Docker start failed"));
        RdsService restoredService = newService(
                restoredContainerManager, mock(RdsProxyManager.class), instances,
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        restoredService.restorePersistedRuntime();

        DbInstance failed = restoredService.getDbInstance("broken");
        assertEquals(DbInstanceStatus.FAILED, failed.getStatus());
        assertNull(failed.getEndpoint());
        assertEquals("persisted-container", failed.getContainerId());
        assertNull(failed.getContainerHost());
        assertEquals(0, failed.getContainerPort());

        assertDoesNotThrow(() -> restoredService.deleteDbInstance("broken"));
        verify(restoredContainerManager).stop(
                org.mockito.ArgumentMatchers.argThat(handle ->
                        "persisted-container".equals(handle.getContainerId())
                                && persisted.getDbInstanceArn().equals(handle.getRuntimeId())));
        assertThrows(AwsException.class, () -> restoredService.getDbInstance("broken"));
    }

    @Test
    void restorePortExhaustionMarksEachUnrestorableInstanceAndContinues() {
        when(config.services().rds().mock()).thenReturn(true);
        when(config.services().rds().proxyBasePort()).thenReturn(7000);
        when(config.services().rds().proxyMaxPort()).thenReturn(7000);
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        instances.put("first", persistedInstance(
                "first", "123456789012", "secret", 7000));
        instances.put("second", persistedInstance(
                "second", "123456789012", "secret", 7000));
        RdsService restoredService = newService(
                containerManager, proxyManager, instances, new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());

        assertDoesNotThrow(restoredService::restorePersistedRuntime);

        Collection<DbInstance> restored = restoredService.listDbInstances(null);
        assertEquals(2, restored.size());
        assertEquals(1, restored.stream()
                .filter(instance -> instance.getStatus() == DbInstanceStatus.AVAILABLE)
                .count());
        DbInstance failed = restored.stream()
                .filter(instance -> instance.getStatus() == DbInstanceStatus.FAILED)
                .findFirst()
                .orElseThrow();
        assertNull(failed.getEndpoint());
        assertEquals(0, failed.getProxyPort());
    }

    @Test
    void failedClusterRestoreClearsRuntimeStateAndReleasesItsEndpointPort() {
        InMemoryStorage<String, DbCluster> clusters = new InMemoryStorage<>();
        clusters.put("cluster1", persistedCluster("123456789012", "secret", 7000));
        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        String runtimeId = "arn:aws:rds:us-east-1:123456789012:cluster:cluster1";
        RdsContainerHandle restoredHandle = new RdsContainerHandle(
                "restored-container", runtimeId, "cluster1", "127.0.0.1", 15432);
        RdsContainerHandle replacementHandle = new RdsContainerHandle(
                "replacement-container", "arn:aws:rds:us-east-1:123456789012:cluster:replacement",
                "replacement", "127.0.0.1", 15433);
        when(restoredContainerManager.start(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(restoredHandle, replacementHandle);
        org.mockito.Mockito.doThrow(new IllegalStateException("relay failed"))
                .doNothing()
                .when(restoredProxyManager).startProxy(
                        any(), any(), anyBoolean(), anyInt(), any(), anyInt(),
                        any(), any(), any(), any());
        org.mockito.Mockito.doThrow(new IllegalStateException("restore container cleanup failed"))
                .doThrow(new IllegalStateException("delete container cleanup failed"))
                .doNothing()
                .when(restoredContainerManager).stop(org.mockito.ArgumentMatchers.argThat(
                        handle -> runtimeId.equals(handle.getRuntimeId())));
        RdsService restoredService = newService(
                restoredContainerManager, restoredProxyManager, new InMemoryStorage<>(),
                clusters, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>());

        restoredService.restorePersistedRuntime();

        DbCluster failed = restoredService.getDbCluster("cluster1");
        assertEquals(DbInstanceStatus.FAILED, failed.getStatus());
        assertNull(failed.getEndpoint());
        assertNull(failed.getReaderEndpoint());
        assertEquals("restored-container", failed.getContainerId());
        assertNull(failed.getContainerHost());
        assertEquals(0, failed.getContainerPort());
        assertEquals(0, failed.getProxyPort());

        assertThrows(IllegalStateException.class, () ->
                restoredService.deleteDbCluster("cluster1"));
        assertEquals(DbInstanceStatus.DELETING,
                restoredService.getDbCluster("cluster1").getStatus());
        verify(restoredContainerManager, never()).removeVolume(any(), any(), any());

        assertDoesNotThrow(() -> restoredService.deleteDbCluster("cluster1"));
        assertThrows(AwsException.class, () -> restoredService.getDbCluster("cluster1"));
        verify(restoredContainerManager, times(3)).stop(
                org.mockito.ArgumentMatchers.argThat(
                        handle -> runtimeId.equals(handle.getRuntimeId())));
        verify(restoredContainerManager).removeVolume(any(), any(), any());

        DbCluster replacement = restoredService.createDbCluster(
                "replacement", "aurora-postgresql", "16.3", "admin", "secret",
                "app", false, null);
        assertEquals(7000, replacement.getEndpoint().port());
    }

    @Test
    void createDbProxyPopulatesEndpointArnAndDefaultPort() {
        DbProxy proxy = rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of("sg-a"), PROXY_AUTH, Map.of());

        assertEquals("app-proxy", proxy.getDbProxyName());
        assertEquals("available", proxy.getStatus());
        assertEquals(5432, proxy.getProxyPort());   // POSTGRESQL default listener port
        assertNotNull(proxy.getDbProxyResourceId());
        assertTrue(proxy.getDbProxyResourceId().startsWith("prx-"));
        assertEquals("arn:aws:rds:us-east-1:123456789012:db-proxy:" + proxy.getDbProxyResourceId(),
                proxy.getDbProxyArn());
        assertEquals("vpc-default", proxy.getVpcId());
        assertEquals(1, rdsService.listDbProxies("app-proxy").size());
        DbProxyTargetGroup targetGroup = rdsService.describeDbProxyTargetGroups("app-proxy").iterator().next();
        assertEquals("default", targetGroup.getTargetGroupName());
        assertTrue(targetGroup.getTargets().isEmpty());
        assertTrue(targetGroup.getTargetGroupArn().contains(":target-group:prx-tg-"));
    }

    @Test
    void createDbProxyRejectsDuplicate() {
        rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());

        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of()));

        assertEquals("DBProxyAlreadyExistsFault", exception.getErrorCode());
    }

    @Test
    void createDbProxyReservesDistinctInternalListenerPorts() {
        // Listener ports must never collide even though externally routing multiple same-engine
        // proxy hostnames remains a separate concern.
        DbProxy first = rdsService.createDbProxy("proxy-a", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        DbProxy second = rdsService.createDbProxy("proxy-b", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());

        assertEquals(5432, first.getProxyPort());   // first proxy keeps the clean engine default
        assertNotEquals(first.getProxyPort(), second.getProxyPort());
    }

    @Test
    void registerDbProxyTargetsCreatesDefaultTargetGroupForCluster() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null);
        rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());

        DbProxyTargetGroup tg = rdsService.registerDbProxyTargets("app-proxy", null,
                List.of("cluster1"), List.of(), 90, 40);

        assertEquals("app-proxy", tg.getDbProxyName());
        assertEquals("default", tg.getTargetGroupName());   // blank TargetGroupName defaults to "default"
        assertEquals(90, tg.getMaxConnectionsPercent());
        assertEquals(40, tg.getMaxIdleConnectionsPercent());
        assertEquals(1, tg.getTargets().size());
        DbProxyTarget target = tg.getTargets().get(0);
        assertEquals("TRACKED_CLUSTER", target.getType());
        assertEquals("cluster1", target.getRdsResourceId());
        // The registered target group and target are read back through the describe APIs.
        assertEquals(1, rdsService.describeDbProxyTargetGroups("app-proxy").size());
        assertEquals(1, rdsService.describeDbProxyTargets("app-proxy", "default").size());
    }

    @Test
    void createDbProxyValidatesRequiredInputsAndTimeout() {
        AwsException missingName = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy(null, "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidParameterValue", missingName.getErrorCode());

        AwsException invalidEngine = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "ORACLE", true, false, PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidParameterValue", invalidEngine.getErrorCode());

        AwsException missingRole = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, null,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidParameterValue", missingRole.getErrorCode());

        AwsException missingSubnets = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        List.of(), List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidParameterValue", missingSubnets.getErrorCode());

        AwsException oneSubnet = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        List.of("subnet-a"), List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidParameterValue", oneSubnet.getErrorCode());
        assertEquals(400, oneSubnet.getHttpStatus());
        assertTrue(oneSubnet.getMessage().contains("at least two distinct subnet IDs"));
        assertTrue(rdsService.listDbProxies(null).isEmpty());

        AwsException duplicateSubnets = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        List.of("subnet-a", "subnet-a"), List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidParameterValue", duplicateSubnets.getErrorCode());

        AwsException invalidTimeout = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, 0, false, Map.of(), "us-east-1"));
        assertEquals("InvalidParameterValue", invalidTimeout.getErrorCode());

        AwsException missingSubnet = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        List.of("subnet-missing-a", "subnet-missing-b"),
                        List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidSubnet", missingSubnet.getErrorCode());

        when(ec2Service.describeSubnets(eq("us-east-1"),
                eq(List.of("subnet-same-az-a", "subnet-same-az-b")), any()))
                .thenReturn(List.of(
                        subnet("subnet-same-az-a", "vpc-a", "us-east-1a"),
                        subnet("subnet-same-az-b", "vpc-a", "us-east-1a")));
        AwsException sameAvailabilityZone = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        List.of("subnet-same-az-a", "subnet-same-az-b"),
                        List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidSubnet", sameAvailabilityZone.getErrorCode());

        when(ec2Service.describeSubnets(eq("us-east-1"),
                eq(List.of("subnet-vpc-a", "subnet-vpc-b")), any()))
                .thenReturn(List.of(
                        subnet("subnet-vpc-a", "vpc-a", "us-east-1a"),
                        subnet("subnet-vpc-b", "vpc-b", "us-east-1b")));
        AwsException mixedVpc = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        List.of("subnet-vpc-a", "subnet-vpc-b"),
                        List.of(), PROXY_AUTH, Map.of()));
        assertEquals("InvalidSubnet", mixedVpc.getErrorCode());
    }

    @Test
    void createDbProxyValidatesAndPersistsDefaultAuthScheme() {
        DbProxy iamProxy = rdsService.createDbProxy(
                "iam-proxy", "POSTGRESQL", true, true, "IAM_AUTH", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), List.of(), 1800, false, Map.of(), "us-east-1");

        assertEquals("IAM_AUTH", iamProxy.getDefaultAuthScheme());
        assertTrue(iamProxy.isIamAuth());
        assertTrue(iamProxy.getAuth().isEmpty());
        assertNotNull(iamProxy.getUpdatedAt());

        AwsException missingAuth = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy(
                        "none-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), List.of(), 1800, false, Map.of(), "us-east-1"));
        assertEquals("InvalidParameterValue", missingAuth.getErrorCode());

        AwsException invalidScheme = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy(
                        "bad-proxy", "POSTGRESQL", true, false, "PASSWORD", PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, 1800, false, Map.of(), "us-east-1"));
        assertEquals("InvalidParameterValue", invalidScheme.getErrorCode());

        AwsException sqlServerIamAuth = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy(
                        "sqlserver-proxy", "SQLSERVER", true, true, "IAM_AUTH", PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), List.of(), 1800, false, Map.of(), "us-east-1"));
        assertEquals("InvalidParameterValue", sqlServerIamAuth.getErrorCode());

        AwsException blankScheme = assertThrows(AwsException.class, () ->
                rdsService.createDbProxy(
                        "blank-proxy", "POSTGRESQL", true, false, "", PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, 1800, false,
                        Map.of(), "us-east-1"));
        assertEquals("InvalidParameterValue", blankScheme.getErrorCode());
    }

    @Test
    void createAndModifyDbProxyValidateUserAuthConfig() {
        List<DbProxyAuth> invalidEntries = List.of(
                new DbProxyAuth("PASSWORD", PROXY_AUTH.getFirst().getSecretArn(),
                        "DISABLED", null, null),
                new DbProxyAuth("SECRETS", PROXY_AUTH.getFirst().getSecretArn(),
                        "OPTIONAL", null, null),
                new DbProxyAuth("SECRETS", PROXY_AUTH.getFirst().getSecretArn(),
                        "ENABLED", null, null),
                new DbProxyAuth("SECRETS", PROXY_AUTH.getFirst().getSecretArn(),
                        "DISABLED", "KERBEROS", null),
                new DbProxyAuth("SECRETS", PROXY_AUTH.getFirst().getSecretArn(),
                        "DISABLED", null, ""),
                new DbProxyAuth("SECRETS", "too-short", "DISABLED", null, null),
                proxyAuthWithUserName(""),
                proxyAuthWithUserName("u".repeat(129)));

        for (DbProxyAuth invalidEntry : invalidEntries) {
            AwsException exception = assertThrows(AwsException.class, () ->
                    rdsService.createDbProxy(
                            "invalid-auth-proxy", "POSTGRESQL", true, false, "NONE",
                            PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), List.of(invalidEntry),
                            1800, false, Map.of(), "us-east-1"));
            assertEquals("InvalidParameterValue", exception.getErrorCode());
        }

        DbProxyAuth longDescription = new DbProxyAuth(
                "SECRETS", PROXY_AUTH.getFirst().getSecretArn(), "DISABLED", null,
                "d".repeat(1_001));
        assertThrows(AwsException.class, () -> rdsService.createDbProxy(
                "long-description-proxy", "POSTGRESQL", true, false, "NONE",
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), List.of(longDescription),
                1800, false, Map.of(), "us-east-1"));

        List<DbProxyAuth> tooManyEntries = java.util.Collections.nCopies(201, PROXY_AUTH.getFirst());
        assertThrows(AwsException.class, () -> rdsService.createDbProxy(
                "too-many-auth-proxy", "POSTGRESQL", true, false, "NONE",
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), tooManyEntries,
                1800, false, Map.of(), "us-east-1"));

        DbProxyAuth sqlServerAuth = new DbProxyAuth(
                "SECRETS", PROXY_AUTH.getFirst().getSecretArn(), "ENABLED",
                "SQL_SERVER_AUTHENTICATION", "SQL Server credentials");
        DbProxy sqlServerProxy = rdsService.createDbProxy(
                "sqlserver-proxy", "SQLSERVER", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), List.of(sqlServerAuth),
                1800, false, Map.of(), "us-east-1");
        assertEquals("ENABLED", sqlServerProxy.getAuth().getFirst().getIamAuth());
        assertTrue(sqlServerProxy.isIamAuth());

        DbProxyAuth sqlServerAuthDisabled = new DbProxyAuth(
                "SECRETS", PROXY_AUTH.getFirst().getSecretArn(), "DISABLED",
                "SQL_SERVER_AUTHENTICATION", "SQL Server credentials");
        DbProxy sqlServerToModify = rdsService.createDbProxy(
                "sqlserver-modify-proxy", "SQLSERVER", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), List.of(sqlServerAuthDisabled),
                1800, false, Map.of(), "us-east-1");
        assertFalse(sqlServerToModify.isIamAuth());
        DbProxy sqlServerModified = rdsService.modifyDbProxy(
                "sqlserver-modify-proxy", null, List.of(sqlServerAuth),
                null, null, null, null, null, null, "us-east-1");
        assertTrue(sqlServerModified.isIamAuth());

        DbProxy created = rdsService.createDbProxy(
                "modify-auth-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH,
                1800, false, Map.of(), "us-east-1");
        AwsException invalidModify = assertThrows(AwsException.class, () ->
                rdsService.modifyDbProxy(
                        created.getDbProxyName(), null, List.of(invalidEntries.getFirst()),
                        null, null, null, null, null, null, "us-east-1"));
        assertEquals("InvalidParameterValue", invalidModify.getErrorCode());
        assertEquals("SECRETS", rdsService.getDbProxy(
                created.getDbProxyName(), "us-east-1").getAuth().getFirst().getAuthScheme());
    }

    @Test
    void modifyDbProxyIsCopyOnWriteAndPreservesIdentity() {
        DbProxy created = rdsService.createDbProxy(
                "app-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of("sg-old"), PROXY_AUTH,
                1800, false, Map.of("owner", "old"), "us-east-1");
        Instant originalUpdatedAt = created.getUpdatedAt();

        DbProxy modified = rdsService.modifyDbProxy(
                "app-proxy", "NONE", PROXY_AUTH, false, 900, true,
                PROXY_ROLE_ARN, List.of("sg-new"), Map.of("owner", "new"), "us-east-1");

        assertEquals(created.getDbProxyArn(), modified.getDbProxyArn());
        assertEquals(created.getDbProxyResourceId(), modified.getDbProxyResourceId());
        assertEquals(created.getEndpoint(), modified.getEndpoint());
        assertEquals(created.getProxyPort(), modified.getProxyPort());
        assertEquals(created.getCreatedAt(), modified.getCreatedAt());
        assertFalse(modified.isRequireTls());
        assertEquals(900, modified.getIdleClientTimeout());
        assertTrue(modified.isDebugLogging());
        assertEquals(List.of("sg-new"), modified.getVpcSecurityGroupIds());
        assertEquals(Map.of("owner", "new"), modified.getTags());
        assertFalse(modified.getUpdatedAt().isBefore(originalUpdatedAt));

        Instant modifiedAt = modified.getUpdatedAt();
        DbProxy unchanged = rdsService.modifyDbProxy(
                "app-proxy", "NONE", PROXY_AUTH, false, 900, true,
                PROXY_ROLE_ARN, List.of("sg-new"), Map.of("owner", "new"), "us-east-1");
        assertSame(modified, unchanged);
        assertEquals(modifiedAt, unchanged.getUpdatedAt());
    }

    @Test
    void createDbProxyPreservesOriginalFailureWhenProxyCleanupFails() {
        InMemoryStorage<String, DbProxy> proxies = spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = spy(new InMemoryStorage<>());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());
        String proxyKey = "us-east-1::first-proxy";
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated target-group persistence failure");
        IllegalStateException cleanupFailure =
                new IllegalStateException("simulated proxy cleanup failure");
        doThrow(persistenceFailure).when(targetGroups).put(eq(proxyKey), any());
        doThrow(cleanupFailure).doCallRealMethod().when(proxies).delete(proxyKey);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.createDbProxy(
                        "first-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of()));

        assertSame(persistenceFailure, thrown);
        assertTrue(List.of(thrown.getSuppressed()).contains(cleanupFailure));
        verify(targetGroups).delete(proxyKey);
        DbProxy first = proxies.get(proxyKey).orElseThrow();
        DbProxy second = service.createDbProxy(
                "second-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertNotEquals(first.getProxyPort(), second.getProxyPort());

        service.deleteDbProxy("first-proxy", "us-east-1");
        DbProxy third = service.createDbProxy(
                "third-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertEquals(first.getProxyPort(), third.getProxyPort());
    }

    @Test
    void createDbProxyContinuesCleanupWhenTargetGroupCleanupFails() {
        InMemoryStorage<String, DbProxy> proxies = spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = spy(new InMemoryStorage<>());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());
        String proxyKey = "us-east-1::first-proxy";
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated post-mutation persistence failure");
        IllegalStateException cleanupFailure =
                new IllegalStateException("simulated post-mutation cleanup failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw persistenceFailure;
        }).doCallRealMethod().when(targetGroups).put(eq(proxyKey), any());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw cleanupFailure;
        }).doCallRealMethod().when(targetGroups).delete(proxyKey);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.createDbProxy(
                        "first-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of()));

        assertSame(persistenceFailure, thrown);
        assertTrue(List.of(thrown.getSuppressed()).contains(cleanupFailure));
        assertTrue(proxies.get(proxyKey).isEmpty());
        assertTrue(targetGroups.get(proxyKey).isEmpty());
        DbProxy replacement = service.createDbProxy(
                "replacement-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertEquals(5432, replacement.getProxyPort());
    }

    @Test
    void createDbProxyRestoresRetryOwnerWhenTargetGroupCleanupFailsBeforeMutation() {
        InMemoryStorage<String, DbProxy> proxies = spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = spy(new InMemoryStorage<>());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());
        String proxyKey = "us-east-1::first-proxy";
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated post-mutation persistence failure");
        IllegalStateException cleanupFailure =
                new IllegalStateException("simulated pre-mutation cleanup failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw persistenceFailure;
        }).doCallRealMethod().when(targetGroups).put(eq(proxyKey), any());
        doThrow(cleanupFailure).doCallRealMethod().when(targetGroups).delete(proxyKey);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.createDbProxy(
                        "first-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of()));

        assertSame(persistenceFailure, thrown);
        assertTrue(List.of(thrown.getSuppressed()).contains(cleanupFailure));
        DbProxy first = proxies.get(proxyKey).orElseThrow();
        assertTrue(targetGroups.get(proxyKey).isPresent());
        DbProxy second = service.createDbProxy(
                "second-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertNotEquals(first.getProxyPort(), second.getProxyPort());

        service.deleteDbProxy("first-proxy", "us-east-1");
        DbProxy third = service.createDbProxy(
                "third-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertEquals(first.getProxyPort(), third.getProxyPort());
    }

    @Test
    void modifyDbProxyPersistenceFailureLeavesStoredStateUntouched() {
        InMemoryStorage<String, DbProxy> proxies = spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "abc", 5432);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "abc");
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", targetGroup);
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated post-mutation persistence failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw persistenceFailure;
        }).doCallRealMethod().when(proxies).put(eq("us-east-1::app-proxy"), any());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.modifyDbProxy(
                "app-proxy", null, null, null, null, true,
                null, null, null, "us-east-1"));

        assertSame(persistenceFailure, thrown);
        assertFalse(proxy.isDebugLogging());
        assertSame(proxy, service.getDbProxy("app-proxy", "us-east-1"));
    }

    @Test
    void modifyDbProxyRestartsOriginalRelayWhenInitialStopPartiallySucceeds() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "relay", 5432);
        proxy.setDefaultAuthScheme("NONE");
        proxy.setIamAuth(false);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "relay");
        targetGroup.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "db1",
                "arn:aws:rds:us-east-1:123456789012:db:db1", "localhost", 15432)));
        DbInstance instance = persistedInstance("db1", "123456789012", "secret", 15432);
        instance.setContainerHost("localhost");
        instance.setContainerPort(15432);
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", targetGroup);
        instances.put("db1", instance);
        AtomicBoolean relayRunning = new AtomicBoolean(true);
        IllegalStateException stopFailure =
                new IllegalStateException("simulated post-stop failure");
        doAnswer(invocation -> {
            relayRunning.set(false);
            throw stopFailure;
        }).when(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
        java.util.ArrayList<Boolean> startedModes = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            startedModes.add(invocation.getArgument(2));
            relayRunning.set(true);
            return null;
        }).when(proxyManager).startProxy(any(), any(), anyBoolean(), anyInt(), any(),
                anyInt(), any(), any(), any(), any());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups, instances, new InMemoryStorage<>());
        DbProxyAuth requiredAuth = new DbProxyAuth(
                "SECRETS", PROXY_AUTH.getFirst().getSecretArn(), "REQUIRED", null, null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.modifyDbProxy("app-proxy", null, List.of(requiredAuth),
                        null, null, null, null, null, null, "us-east-1"));

        assertSame(stopFailure, thrown);
        assertTrue(relayRunning.get());
        assertEquals(List.of(false), startedModes);
        assertSame(proxy, proxies.get("us-east-1::app-proxy").orElseThrow());
        assertFalse(proxy.isIamAuth());
    }

    @Test
    void modifyDbProxyRestoresDurableStateAndRelayAfterPostMutationFailure() {
        InMemoryStorage<String, DbProxy> proxies = spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "relay", 5432);
        proxy.setDefaultAuthScheme("NONE");
        proxy.setIamAuth(false);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "relay");
        targetGroup.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "db1",
                "arn:aws:rds:us-east-1:123456789012:db:db1", "localhost", 15432)));
        DbInstance instance = persistedInstance("db1", "123456789012", "secret", 15432);
        instance.setContainerHost("localhost");
        instance.setContainerPort(15432);
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", targetGroup);
        instances.put("db1", instance);
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated post-mutation persistence failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw persistenceFailure;
        }).doCallRealMethod().when(proxies).put(eq("us-east-1::app-proxy"), any());
        AtomicBoolean relayRunning = new AtomicBoolean(true);
        doAnswer(invocation -> {
            relayRunning.set(false);
            return null;
        }).when(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
        java.util.ArrayList<Boolean> startedModes = new java.util.ArrayList<>();
        doAnswer(invocation -> {
            startedModes.add(invocation.getArgument(2));
            relayRunning.set(true);
            return null;
        }).when(proxyManager).startProxy(any(), any(), anyBoolean(), anyInt(), any(),
                anyInt(), any(), any(), any(), any());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups, instances, new InMemoryStorage<>());
        DbProxyAuth requiredAuth = new DbProxyAuth(
                "SECRETS", PROXY_AUTH.getFirst().getSecretArn(), "REQUIRED", null, null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.modifyDbProxy("app-proxy", null, List.of(requiredAuth),
                        null, null, null, null, null, null, "us-east-1"));

        assertSame(persistenceFailure, thrown);
        assertTrue(relayRunning.get());
        assertEquals(List.of(true, false), startedModes);
        DbProxy restored = proxies.get("us-east-1::app-proxy").orElseThrow();
        assertSame(proxy, restored);
        assertFalse(restored.isIamAuth());
        assertEquals("DISABLED", restored.getAuth().getFirst().getIamAuth());
    }

    @Test
    void dbProxyIdentityAndTagsAreRegionScoped() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        RdsService service = new RdsService(containerManager, proxyManager, ec2Service,
                regionResolver, config, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                null, null, proxies, targetGroups);

        DbProxy east = service.createDbProxy(
                "shared-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, 1800, false,
                Map.of("region", "east"), "us-east-1");
        DbProxy west = service.createDbProxy(
                "shared-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, 1800, false,
                Map.of("region", "west"), "us-west-2");

        assertTrue(proxies.get("us-east-1::shared-proxy").isPresent());
        assertTrue(proxies.get("us-west-2::shared-proxy").isPresent());
        assertTrue(targetGroups.get("us-east-1::shared-proxy").isPresent());
        assertTrue(targetGroups.get("us-west-2::shared-proxy").isPresent());
        assertEquals(east.getDbProxyArn(), service.getDbProxy("shared-proxy", "us-east-1").getDbProxyArn());
        assertEquals(west.getDbProxyArn(), service.getDbProxy("shared-proxy", "us-west-2").getDbProxyArn());
        assertEquals(Map.of("region", "east"), service.listTagsForResource(east.getDbProxyArn(), "us-east-1"));
        assertEquals(Map.of("region", "west"), service.listTagsForResource(west.getDbProxyArn(), "us-west-2"));

        AwsException wrongRegion = assertThrows(AwsException.class, () ->
                service.addTagsToResource(west.getDbProxyArn(), Map.of("bad", "tag"), "us-east-1"));
        assertEquals("InvalidParameterValue", wrongRegion.getErrorCode());
        String wrongAccountArn = east.getDbProxyArn().replace("123456789012", "999999999999");
        AwsException wrongAccount = assertThrows(AwsException.class, () ->
                service.addTagsToResource(wrongAccountArn, Map.of("bad", "tag"), "us-east-1"));
        assertEquals("InvalidParameterValue", wrongAccount.getErrorCode());

        service.deleteDbProxy("shared-proxy", "us-west-2");
        assertEquals(east.getDbProxyArn(), service.getDbProxy("shared-proxy", "us-east-1").getDbProxyArn());
        assertThrows(AwsException.class, () -> service.getDbProxy("shared-proxy", "us-west-2"));
    }

    @Test
    void dbProxyTagsRoundTripByArn() {
        DbProxy proxy = rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false,
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of("owner", "platform"));

        assertEquals(Map.of("owner", "platform"), rdsService.listTagsForResource(proxy.getDbProxyArn()));
        rdsService.addTagsToResource(proxy.getDbProxyArn(), Map.of("env", "test"));
        assertEquals(Map.of("owner", "platform", "env", "test"),
                rdsService.listTagsForResource(proxy.getDbProxyArn()));
        rdsService.removeTagsFromResource(proxy.getDbProxyArn(), List.of("owner"));
        assertEquals(Map.of("env", "test"), rdsService.listTagsForResource(proxy.getDbProxyArn()));
    }

    @Test
    void targetGroupConfigurationValidatesBeforeMutatingStoredState() {
        rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false,
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());

        AwsException invalid = assertThrows(AwsException.class, () ->
                rdsService.configureDbProxyTargetGroup("app-proxy", "default", 80, 101));

        assertEquals("InvalidParameterValue", invalid.getErrorCode());
        DbProxyTargetGroup targetGroup =
                rdsService.describeDbProxyTargetGroups("app-proxy").iterator().next();
        assertEquals(100, targetGroup.getMaxConnectionsPercent());
        assertEquals(50, targetGroup.getMaxIdleConnectionsPercent());

        AwsException missingMaxConnections = assertThrows(AwsException.class, () ->
                rdsService.configureDbProxyTargetGroup("app-proxy", "default", null, 0));
        assertEquals("InvalidParameterValue", missingMaxConnections.getErrorCode());

        DbProxyTargetGroup configured =
                rdsService.configureDbProxyTargetGroup("app-proxy", "default", 80, null);
        assertEquals(80, configured.getMaxConnectionsPercent());
        assertEquals(40, configured.getMaxIdleConnectionsPercent());
    }

    @Test
    void targetGroupConfigurationRestoresStateAfterPostMutationPersistenceFailure() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = spy(new InMemoryStorage<>());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());
        service.createDbProxy(
                "app-proxy", "MYSQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        DbProxyTargetGroup original = targetGroups.get(
                "us-east-1::app-proxy").orElseThrow();
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated post-mutation persistence failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw persistenceFailure;
        }).doCallRealMethod().when(targetGroups).put(eq("us-east-1::app-proxy"), any());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.configureDbProxyTargetGroup(
                        "app-proxy", "default", 80, 20, 45,
                        "SET application_name = 'floci'",
                        List.of("EXCLUDE_VARIABLE_SETS"), "us-east-1"));

        assertSame(persistenceFailure, thrown);
        assertProxyTargetGroupState(original,
                targetGroups.get("us-east-1::app-proxy").orElseThrow());
    }

    @Test
    void targetGroupReconciliationIsCompleteAndIdempotent() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-mysql", "8.0.36",
                "admin", "secret", "app", false, null);
        rdsService.createDbProxy("mysql-proxy", "MYSQL", true, false,
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());

        DbProxyTargetGroup reconciled = rdsService.reconcileDbProxyTargetGroup(
                "mysql-proxy", "default", List.of("cluster1"), List.of(),
                85, 35, 45, "SET sql_mode='ANSI'", List.of("EXCLUDE_VARIABLE_SETS"),
                "us-east-1");
        String targetGroupArn = reconciled.getTargetGroupArn();
        Instant createdAt = reconciled.getCreatedAt();
        Instant updatedAt = reconciled.getUpdatedAt();

        assertEquals(1, reconciled.getTargets().size());
        assertEquals(85, reconciled.getMaxConnectionsPercent());
        assertEquals(35, reconciled.getMaxIdleConnectionsPercent());
        assertEquals(45, reconciled.getConnectionBorrowTimeout());
        assertEquals("SET sql_mode='ANSI'", reconciled.getInitQuery());
        assertEquals(List.of("EXCLUDE_VARIABLE_SETS"), reconciled.getSessionPinningFilters());

        DbProxyTargetGroup retry = rdsService.reconcileDbProxyTargetGroup(
                "mysql-proxy", "default", List.of("cluster1"), List.of(),
                85, 35, 45, "SET sql_mode='ANSI'", List.of("EXCLUDE_VARIABLE_SETS"),
                "us-east-1");

        assertEquals(targetGroupArn, retry.getTargetGroupArn());
        assertEquals(createdAt, retry.getCreatedAt());
        assertEquals(updatedAt, retry.getUpdatedAt());
        assertEquals(1, retry.getTargets().size());
    }

    @Test
    void targetGroupReconciliationValidatesBeforeMutation() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null);
        rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false,
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        DbProxyTargetGroup before = rdsService.describeDbProxyTargetGroups("app-proxy")
                .iterator().next();

        AwsException invalid = assertThrows(AwsException.class, () ->
                rdsService.reconcileDbProxyTargetGroup(
                        "app-proxy", "default", List.of("cluster1"), List.of(),
                        80, 40, 120, null, List.of("EXCLUDE_VARIABLE_SETS"), "us-east-1"));

        assertEquals("InvalidParameterValue", invalid.getErrorCode());
        DbProxyTargetGroup after = rdsService.describeDbProxyTargetGroups("app-proxy")
                .iterator().next();
        assertEquals(before.getTargetGroupArn(), after.getTargetGroupArn());
        assertEquals(before.getUpdatedAt(), after.getUpdatedAt());
        assertTrue(after.getTargets().isEmpty());
    }

    @Test
    void proxyTargetRegistrationRejectsCrossRegionDatabase() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null,
                null, null, false, "us-east-1");
        rdsService.createDbProxy(
                "west-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, 1800, false, Map.of(), "us-west-2");

        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.registerDbProxyTargets(
                        "west-proxy", "default", List.of("cluster1"), List.of(),
                        0, 0, "us-west-2"));

        assertEquals("DBClusterNotFoundFault", exception.getErrorCode());
        assertTrue(rdsService.describeDbProxyTargets(
                "west-proxy", "default", "us-west-2").isEmpty());
    }

    @Test
    void clearingTargetGroupPreservesIdentityResetsDefaultsAndAllowsReregistration() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null);
        rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false,
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        DbProxyTargetGroup registered = rdsService.registerDbProxyTargets("app-proxy", "default",
                List.of("cluster1"), List.of(), 80, 20);

        rdsService.clearDbProxyTargetGroupByArn(registered.getTargetGroupArn());

        DbProxyTargetGroup cleared = rdsService.describeDbProxyTargetGroups("app-proxy")
                .iterator().next();
        assertEquals(registered.getTargetGroupArn(), cleared.getTargetGroupArn());
        assertTrue(cleared.isDefaultTargetGroup());
        assertTrue(cleared.getTargets().isEmpty());
        assertEquals(100, cleared.getMaxConnectionsPercent());
        assertEquals(50, cleared.getMaxIdleConnectionsPercent());

        DbProxyTargetGroup reregistered = rdsService.registerDbProxyTargets("app-proxy", "default",
                List.of("cluster1"), List.of(), 0, 0);
        assertEquals(1, reregistered.getTargets().size());
    }

    @Test
    void clearingTargetGroupRestoresConfigurationAndRelayAfterPersistenceFailure() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "clear", 5432);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "clear");
        targetGroup.setMaxConnectionsPercent(73);
        targetGroup.setMaxIdleConnectionsPercent(29);
        targetGroup.setConnectionBorrowTimeout(45);
        targetGroup.setInitQuery("SET application_name = 'floci'");
        targetGroup.setSessionPinningFilters(List.of("EXCLUDE_VARIABLE_SETS"));
        targetGroup.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "db1",
                "arn:aws:rds:us-east-1:123456789012:db:db1", "localhost", 15432)));
        DbInstance instance = persistedInstance("db1", "123456789012", "secret", 15432);
        instance.setContainerHost("localhost");
        instance.setContainerPort(15432);
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", targetGroup);
        instances.put("db1", instance);
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated post-mutation persistence failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw persistenceFailure;
        }).doCallRealMethod().when(targetGroups).put(eq("us-east-1::app-proxy"), any());
        AtomicBoolean relayRunning = new AtomicBoolean(true);
        doAnswer(invocation -> {
            relayRunning.set(false);
            return null;
        }).when(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
        doAnswer(invocation -> {
            relayRunning.set(true);
            return null;
        }).when(proxyManager).startProxy(any(), any(), anyBoolean(), anyInt(), any(),
                anyInt(), any(), any(), any(), any());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups, instances, new InMemoryStorage<>());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.clearDbProxyTargetGroupByArn(
                        targetGroup.getTargetGroupArn(), "us-east-1"));

        assertSame(persistenceFailure, thrown);
        assertTrue(relayRunning.get());
        assertProxyTargetGroupState(targetGroup,
                targetGroups.get("us-east-1::app-proxy").orElseThrow());

        service.clearDbProxyTargetGroupByArn(
                targetGroup.getTargetGroupArn(), "us-east-1");
        DbProxyTargetGroup cleared = targetGroups.get(
                "us-east-1::app-proxy").orElseThrow();
        assertFalse(relayRunning.get());
        assertTrue(cleared.getTargets().isEmpty());
        assertEquals(100, cleared.getMaxConnectionsPercent());
        assertEquals(50, cleared.getMaxIdleConnectionsPercent());
        assertEquals(120, cleared.getConnectionBorrowTimeout());
        assertNull(cleared.getInitQuery());
        assertTrue(cleared.getSessionPinningFilters().isEmpty());
    }

    @Test
    void sqlServerTargetGroupUsesEngineSpecificPoolDefaults() {
        rdsService.createDbProxy("sqlserver-proxy", "SQLSERVER", true, false,
                PROXY_ROLE_ARN, PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        DbProxyTargetGroup targetGroup = rdsService.describeDbProxyTargetGroups("sqlserver-proxy")
                .iterator().next();

        assertEquals(10, targetGroup.getMaxConnectionsPercent());
        assertEquals(5, targetGroup.getMaxIdleConnectionsPercent());

        rdsService.configureDbProxyTargetGroup("sqlserver-proxy", "default", 80, 20);
        rdsService.clearDbProxyTargetGroupByArn(targetGroup.getTargetGroupArn());

        DbProxyTargetGroup cleared = rdsService.describeDbProxyTargetGroups("sqlserver-proxy")
                .iterator().next();
        assertEquals(10, cleared.getMaxConnectionsPercent());
        assertEquals(5, cleared.getMaxIdleConnectionsPercent());
    }

    @Test
    void registerDbProxyTargetRejectsDuplicateAndSupportsDeregistration() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null);
        rdsService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        rdsService.registerDbProxyTargets("app-proxy", "default",
                List.of("cluster1"), List.of(), 0, 0);

        AwsException duplicate = assertThrows(AwsException.class, () ->
                rdsService.registerDbProxyTargets("app-proxy", "default",
                        List.of("cluster1"), List.of(), 0, 0));
        assertEquals("DBProxyTargetAlreadyRegisteredFault", duplicate.getErrorCode());

        AwsException referenced = assertThrows(AwsException.class, () ->
                rdsService.deleteDbCluster("cluster1"));
        assertEquals("InvalidDBClusterStateFault", referenced.getErrorCode());

        rdsService.deregisterDbProxyTargets("app-proxy", "default", List.of("cluster1"), List.of());
        assertTrue(rdsService.describeDbProxyTargets("app-proxy", "default").isEmpty());
        rdsService.deleteDbCluster("cluster1");

        AwsException missing = assertThrows(AwsException.class, () ->
                rdsService.deregisterDbProxyTargets("app-proxy", "default",
                        List.of("cluster1"), List.of()));
        assertEquals("DBProxyTargetNotFoundFault", missing.getErrorCode());
    }

    @Test
    void deregistrationRestoresTargetAndRelayAfterStopAndPersistenceFailures() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "abc", 5432);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "abc");
        targetGroup.setMaxConnectionsPercent(77);
        targetGroup.setMaxIdleConnectionsPercent(31);
        targetGroup.setConnectionBorrowTimeout(45);
        targetGroup.setInitQuery("SET application_name = 'floci'");
        targetGroup.setSessionPinningFilters(List.of("EXCLUDE_VARIABLE_SETS"));
        targetGroup.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "db1",
                "arn:aws:rds:us-east-1:123456789012:db:db1", "localhost", 15432)));
        DbInstance instance = persistedInstance("db1", "123456789012", "secret", 15432);
        instance.setContainerHost("localhost");
        instance.setContainerPort(15432);
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", targetGroup);
        instances.put("db1", instance);
        IllegalStateException persistenceFailure =
                new IllegalStateException("simulated post-mutation persistence failure");
        doCallRealMethod().doAnswer(invocation -> {
            invocation.callRealMethod();
            throw persistenceFailure;
        }).doCallRealMethod().when(targetGroups).put(eq("us-east-1::app-proxy"), any());
        AtomicBoolean relayRunning = new AtomicBoolean(true);
        IllegalStateException stopFailure =
                new IllegalStateException("simulated post-stop failure");
        doAnswer(invocation -> {
            relayRunning.set(false);
            throw stopFailure;
        }).doAnswer(invocation -> {
            relayRunning.set(false);
            return null;
        }).when(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
        doAnswer(invocation -> {
            relayRunning.set(true);
            return null;
        }).when(proxyManager).startProxy(any(), any(), anyBoolean(), anyInt(), any(),
                anyInt(), any(), any(), any(), any());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups, instances, new InMemoryStorage<>());

        IllegalStateException stopThrown = assertThrows(IllegalStateException.class, () ->
                service.deregisterDbProxyTargets(
                        "app-proxy", "default", List.of(), List.of("db1")));

        assertSame(stopFailure, stopThrown);
        assertTrue(relayRunning.get());
        assertProxyTargetGroupState(targetGroup,
                targetGroups.get("us-east-1::app-proxy").orElseThrow());

        IllegalStateException persistenceThrown = assertThrows(IllegalStateException.class, () ->
                service.deregisterDbProxyTargets(
                        "app-proxy", "default", List.of(), List.of("db1")));

        assertSame(persistenceFailure, persistenceThrown);
        assertTrue(relayRunning.get());
        assertProxyTargetGroupState(targetGroup,
                targetGroups.get("us-east-1::app-proxy").orElseThrow());

        service.deregisterDbProxyTargets(
                "app-proxy", "default", List.of(), List.of("db1"));
        assertFalse(relayRunning.get());
        assertTrue(targetGroups.get("us-east-1::app-proxy").orElseThrow()
                .getTargets().isEmpty());
    }

    @Test
    void registrationRollsBackMutateThenThrowProxyStatusPersistence() {
        InMemoryStorage<String, DbProxy> proxies =
                spy(new InMemoryStorage<String, DbProxy>());
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "mysql-proxy", "us-east-1", "123456789012", "current", 3306);
        proxy.setEngineFamily("MYSQL");
        proxy.setStatus("insufficient-resource-limits");
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "mysql-proxy", "us-east-1", "123456789012", "current");
        targetGroup.setCreatedAt(proxy.getCreatedAt());
        targetGroup.setUpdatedAt(proxy.getCreatedAt());
        DbInstance instance = persistedInstance(
                "mysql-db", "123456789012", "secret", 7000);
        instance.setEngine(DatabaseEngine.MYSQL);
        instance.setContainerHost("localhost");
        instance.setContainerPort(3306);
        proxies.put("us-east-1::mysql-proxy", proxy);
        targetGroups.put("us-east-1::mysql-proxy", targetGroup);
        instances.put("mysql-db", instance);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("simulated post-mutation persistence failure");
        }).doCallRealMethod().when(proxies).put(
                eq("us-east-1::mysql-proxy"), any());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                instances, new InMemoryStorage<>());

        assertThrows(IllegalStateException.class, () -> service.registerDbProxyTargets(
                "mysql-proxy", "default", List.of(), List.of("mysql-db"), 0, 0));

        assertEquals("insufficient-resource-limits", proxies.get(
                "us-east-1::mysql-proxy").orElseThrow().getStatus());
        assertTrue(targetGroups.get("us-east-1::mysql-proxy").orElseThrow()
                .getTargets().isEmpty());
        verify(proxyManager).startProxy(
                eq("db-proxy:" + proxy.getDbProxyArn()), eq(DatabaseEngine.MYSQL),
                anyBoolean(), eq(3306), eq("localhost"), eq(3306),
                eq("admin"), eq("secret"), eq("app"), any());
        verify(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
    }

    @Test
    void registrationStopsRelayWhenStartupPartiallySucceeds() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "mysql-proxy", "us-east-1", "123456789012", "current", 3306);
        proxy.setEngineFamily("MYSQL");
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "mysql-proxy", "us-east-1", "123456789012", "current");
        DbInstance instance = persistedInstance(
                "mysql-db", "123456789012", "secret", 3306);
        instance.setEngine(DatabaseEngine.MYSQL);
        instance.setContainerHost("localhost");
        instance.setContainerPort(3306);
        proxies.put("us-east-1::mysql-proxy", proxy);
        targetGroups.put("us-east-1::mysql-proxy", targetGroup);
        instances.put("mysql-db", instance);
        AtomicBoolean relayRunning = new AtomicBoolean(false);
        IllegalStateException startupFailure =
                new IllegalStateException("simulated post-start failure");
        doAnswer(invocation -> {
            relayRunning.set(true);
            throw startupFailure;
        }).when(proxyManager).startProxy(any(), any(), anyBoolean(), anyInt(), any(),
                anyInt(), any(), any(), any(), any());
        doAnswer(invocation -> {
            relayRunning.set(false);
            return null;
        }).when(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups, instances, new InMemoryStorage<>());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.registerDbProxyTargets(
                        "mysql-proxy", "default", List.of(), List.of("mysql-db"), 0, 0));

        assertSame(startupFailure, thrown);
        assertFalse(relayRunning.get());
        assertSame(proxy, proxies.get("us-east-1::mysql-proxy").orElseThrow());
        assertTrue(targetGroups.get("us-east-1::mysql-proxy").orElseThrow()
                .getTargets().isEmpty());
    }

    @Test
    void deletingLegacyDbProxyAlsoRemovesLegacyTargetGroup() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy("app-proxy", "us-east-1", "123456789012", "old", 5432);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "old");
        proxies.put("app-proxy", proxy);
        targetGroups.put("app-proxy", targetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.deleteDbProxy("app-proxy", "us-east-1");

        assertTrue(proxies.get("app-proxy").isEmpty());
        assertTrue(proxies.get("us-east-1::app-proxy").isEmpty());
        assertTrue(targetGroups.get("app-proxy").isEmpty());
        assertTrue(targetGroups.get("us-east-1::app-proxy").isEmpty());
    }

    @Test
    void staleProxyAndTargetGroupArnsCannotOverwriteRecreatedResources() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy current = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "new", 5432);
        current.setTags(Map.of("generation", "new"));
        DbProxy stale = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "old", 5432);
        DbProxyTargetGroup currentTargetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "new");
        DbProxyTargetGroup staleTargetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "old");
        proxies.put("us-east-1::app-proxy", current);
        proxies.put("app-proxy", stale);
        targetGroups.put("us-east-1::app-proxy", currentTargetGroup);
        targetGroups.put("app-proxy", staleTargetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        AwsException staleProxyArn = assertThrows(AwsException.class, () ->
                service.addTagsToResource(stale.getDbProxyArn(), Map.of("bad", "tag"), "us-east-1"));
        AwsException staleTargetGroupArn = assertThrows(AwsException.class, () ->
                service.clearDbProxyTargetGroupByArn(
                        staleTargetGroup.getTargetGroupArn(), "us-east-1"));

        assertEquals("DBProxyNotFoundFault", staleProxyArn.getErrorCode());
        assertEquals("DBProxyTargetGroupNotFoundFault", staleTargetGroupArn.getErrorCode());
        assertEquals(current.getDbProxyArn(),
                proxies.get("us-east-1::app-proxy").orElseThrow().getDbProxyArn());
        assertEquals(Map.of("generation", "new"),
                proxies.get("us-east-1::app-proxy").orElseThrow().getTags());
        assertEquals(currentTargetGroup.getTargetGroupArn(),
                targetGroups.get("us-east-1::app-proxy").orElseThrow().getTargetGroupArn());
    }

    @Test
    void rawLegacyProxyCannotBeClaimedByAnotherAccount() {
        String foreignAccount = "222222222222";
        String currentAccount = "333333333333";
        RegionResolver otherRegionResolver = new RegionResolver("us-east-1", currentAccount);
        EmulatorConfig otherConfig = mock(EmulatorConfig.class);
        when(otherConfig.defaultAccountId()).thenReturn(currentAccount);
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        rawProxies.put("app-proxy", persistedProxy(
                "app-proxy", "us-east-1", foreignAccount, "foreign", 5432));
        rawProxies.put(currentAccount + "/us-east-1::corrupt-proxy", persistedProxy(
                "corrupt-proxy", "us-east-1", foreignAccount, "corrupt", 5433));
        AccountAwareStorageBackend<DbProxy> proxies =
                new AccountAwareStorageBackend<>(rawProxies, null, currentAccount);
        AccountAwareStorageBackend<DbProxyTargetGroup> targetGroups =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, currentAccount);
        RdsService service = proxyStoreService(
                otherRegionResolver, otherConfig, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        AwsException exception = assertThrows(AwsException.class, () ->
                service.getDbProxy("app-proxy", "us-east-1"));

        assertEquals("DBProxyNotFoundFault", exception.getErrorCode());
        assertTrue(service.listDbProxies(null, "us-east-1").isEmpty());
        assertTrue(rawProxies.get("app-proxy").isPresent());
        assertTrue(rawProxies.get(currentAccount + "/app-proxy").isEmpty());
    }

    @Test
    void malformedPersistedProxyArnIsSkippedWithoutMigration() {
        when(config.services().rds().mock()).thenReturn(true);
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        DbProxy malformed = persistedProxy(
                "malformed-proxy", "us-east-1", "123456789012", "bad", 5432);
        malformed.setDbProxyArn("not-an-arn");
        proxies.put("malformed-proxy", malformed);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        assertTrue(service.listDbProxies(null, "us-east-1").isEmpty());
        AwsException missing = assertThrows(AwsException.class, () ->
                service.getDbProxy("malformed-proxy", "us-east-1"));
        assertEquals("DBProxyNotFoundFault", missing.getErrorCode());
        assertTrue(proxies.get("malformed-proxy").isPresent());
        assertTrue(proxies.get("us-east-1::malformed-proxy").isEmpty());
    }

    @Test
    void corruptCanonicalProxyAndTargetGroupFailClosedWithoutBeingOverwritten() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy wrongName = persistedProxy(
                "other-proxy", "us-east-1", "123456789012", "other", 5432);
        proxies.put("us-east-1::expected-proxy", wrongName);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        AwsException missing = assertThrows(AwsException.class, () ->
                service.getDbProxy("expected-proxy", "us-east-1"));
        assertEquals("DBProxyNotFoundFault", missing.getErrorCode());
        AwsException occupied = assertThrows(AwsException.class, () ->
                service.createDbProxy(
                        "expected-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                        PROXY_SUBNET_IDS, List.of(), PROXY_AUTH,
                        1800, false, Map.of(), "us-east-1"));
        assertEquals("DBProxyAlreadyExistsFault", occupied.getErrorCode());
        assertSame(wrongName, proxies.get("us-east-1::expected-proxy").orElseThrow());

        DbProxy current = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "current", 5433);
        DbProxyTargetGroup stale = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "stale");
        proxies.put("us-east-1::app-proxy", current);
        targetGroups.put("us-east-1::app-proxy", stale);

        AwsException staleGroup = assertThrows(AwsException.class, () ->
                service.describeDbProxyTargets("app-proxy", "default", "us-east-1"));
        assertEquals("DBProxyTargetGroupNotFoundFault", staleGroup.getErrorCode());
        assertSame(stale, targetGroups.get("us-east-1::app-proxy").orElseThrow());
    }

    @Test
    void proxyRestoreDoesNotReplaceCorruptCanonicalTargetGroupWithLegacyState() {
        when(config.services().rds().mock()).thenReturn(true);
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "current", 5432);
        DbProxyTargetGroup staleCanonical = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "stale");
        DbProxyTargetGroup recoverableLegacy = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "current");
        recoverableLegacy.setCreatedAt(proxy.getCreatedAt());
        recoverableLegacy.setUpdatedAt(proxy.getCreatedAt());
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", staleCanonical);
        targetGroups.put("app-proxy", recoverableLegacy);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        assertSame(staleCanonical,
                targetGroups.get("us-east-1::app-proxy").orElseThrow());
        assertSame(recoverableLegacy, targetGroups.get("app-proxy").orElseThrow());
        assertEquals("insufficient-resource-limits",
                service.getDbProxy("app-proxy", "us-east-1").getStatus());
    }

    @Test
    void proxyRestoreRejectsAccountScopedKeyWhoseModelHasAnotherName() {
        when(config.services().rds().mock()).thenReturn(true);
        String accountId = "123456789012";
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> rawTargetGroups = new InMemoryStorage<>();
        DbProxy wrongName = persistedProxy(
                "other-proxy", "us-east-1", accountId, "other", 5432);
        String corruptKey = accountId + "/us-east-1::expected-proxy";
        rawProxies.put(corruptKey, wrongName);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(rawProxies, null, accountId),
                new AccountAwareStorageBackend<>(rawTargetGroups, null, accountId),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        assertSame(wrongName, rawProxies.get(corruptKey).orElseThrow());
        assertTrue(rawProxies.get(accountId + "/us-east-1::other-proxy").isEmpty());
        assertTrue(rawTargetGroups.get(accountId + "/us-east-1::other-proxy").isEmpty());
        assertTrue(service.listDbProxies(null, "us-east-1").isEmpty());
    }

    @Test
    void proxyRestorePrefersRawRegionalCanonicalOverAccountNameLegacy() {
        when(config.services().rds().mock()).thenReturn(true);
        String accountId = "123456789012";
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> rawTargetGroups = new InMemoryStorage<>();
        DbProxy regional = persistedProxy(
                "app-proxy", "us-east-1", accountId, "shared", 5432);
        regional.setTags(Map.of("source", "regional"));
        DbProxy accountName = persistedProxy(
                "app-proxy", "us-east-1", accountId, "shared", 5432);
        accountName.setTags(Map.of("source", "account-name"));
        rawProxies.put("us-east-1::app-proxy", regional);
        rawProxies.put(accountId + "/app-proxy", accountName);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(rawProxies, null, accountId),
                new AccountAwareStorageBackend<>(rawTargetGroups, null, accountId),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        DbProxy canonical = rawProxies.get(
                accountId + "/us-east-1::app-proxy").orElseThrow();
        assertSame(regional, canonical);
        assertEquals(Map.of("source", "regional"), canonical.getTags());
        assertTrue(rawProxies.get("us-east-1::app-proxy").isEmpty());
        assertTrue(rawProxies.get(accountId + "/app-proxy").isEmpty());
    }

    @Test
    void proxyRestoreUsesRegionalLegacyGenerationDeterministically() {
        when(config.services().rds().mock()).thenReturn(true);
        String accountId = "123456789012";
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        DbProxy regional = persistedProxy(
                "app-proxy", "us-east-1", accountId, "regional", 5432);
        DbProxy accountName = persistedProxy(
                "app-proxy", "us-east-1", accountId, "account", 5433);
        rawProxies.put("us-east-1::app-proxy", regional);
        rawProxies.put(accountId + "/app-proxy", accountName);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(rawProxies, null, accountId),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, accountId),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        assertEquals("prx-regional", rawProxies.get(
                accountId + "/us-east-1::app-proxy").orElseThrow()
                .getDbProxyResourceId());
        assertTrue(rawProxies.get("us-east-1::app-proxy").isEmpty());
        assertSame(accountName, rawProxies.get(accountId + "/app-proxy").orElseThrow());
    }

    @Test
    void proxyRestoreRejectsInvalidPersistedIdentity() {
        when(config.services().rds().mock()).thenReturn(true);
        String accountId = "123456789012";
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> rawTargetGroups = new InMemoryStorage<>();
        DbProxy invalidName = persistedProxy(
                "bad/name", "us-east-1", accountId, "bad-name", 5432);
        DbProxy blankRegion = persistedProxy(
                "blank-region", "", accountId, "blank-region", 5433);
        invalidName.setDefaultAuthScheme(null);
        blankRegion.setDefaultAuthScheme(null);
        rawProxies.put(accountId + "/us-east-1::bad/name", invalidName);
        rawProxies.put(accountId + "/us-east-1::blank-region", blankRegion);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(rawProxies, null, accountId),
                new AccountAwareStorageBackend<>(rawTargetGroups, null, accountId),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        assertSame(invalidName, rawProxies.get(
                accountId + "/us-east-1::bad/name").orElseThrow());
        assertSame(blankRegion, rawProxies.get(
                accountId + "/us-east-1::blank-region").orElseThrow());
        assertNull(invalidName.getDefaultAuthScheme());
        assertNull(blankRegion.getDefaultAuthScheme());
        assertTrue(rawTargetGroups.keys().isEmpty());
    }

    @Test
    void proxyRestoreDoesNotClaimWrongKeyTargetGroup() {
        when(config.services().rds().mock()).thenReturn(true);
        String accountId = "123456789012";
        String otherAccount = "999999999999";
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> rawTargetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", accountId, "current", 5432);
        DbProxyTargetGroup wrongKey = persistedTargetGroup(
                "app-proxy", "us-east-1", accountId, "current");
        wrongKey.setCreatedAt(proxy.getCreatedAt());
        wrongKey.setUpdatedAt(proxy.getCreatedAt());
        wrongKey.setMaxConnectionsPercent(37);
        wrongKey.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "marker",
                "arn:aws:rds:us-east-1:" + accountId + ":db:marker",
                "localhost", 5432)));
        rawProxies.put(accountId + "/us-east-1::app-proxy", proxy);
        String wrongRawKey = otherAccount + "/us-east-1::wrong";
        rawTargetGroups.put(wrongRawKey, wrongKey);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(rawProxies, null, accountId),
                new AccountAwareStorageBackend<>(rawTargetGroups, null, accountId),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        DbProxyTargetGroup canonical = rawTargetGroups.get(
                accountId + "/us-east-1::app-proxy").orElseThrow();
        assertSame(wrongKey, rawTargetGroups.get(wrongRawKey).orElseThrow());
        assertNotSame(wrongKey, canonical);
        assertNotEquals(wrongKey.getTargetGroupArn(), canonical.getTargetGroupArn());
        assertTrue(canonical.getTargets().isEmpty());
        assertEquals(100, canonical.getMaxConnectionsPercent());
        assertEquals(37, wrongKey.getMaxConnectionsPercent());
    }

    @Test
    void restoreNormalizesLegacyTargetGroupWithoutLosingTargetsOrPoolConfiguration() {
        when(config.services().rds().mock()).thenReturn(true);
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "legacy", 3306);
        proxy.setEngineFamily("MYSQL");
        proxy.setCreatedAt(null);
        proxy.setUpdatedAt(null);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "legacy");
        targetGroup.setTargetGroupName(null);
        targetGroup.setDefaultTargetGroup(false);
        targetGroup.setCreatedAt(null);
        targetGroup.setUpdatedAt(null);
        targetGroup.setMaxConnectionsPercent(71);
        targetGroup.setMaxIdleConnectionsPercent(29);
        targetGroup.setConnectionBorrowTimeout(45);
        targetGroup.setInitQuery("SET application_name = 'floci'");
        targetGroup.setSessionPinningFilters(List.of("EXCLUDE_VARIABLE_SETS"));
        DbProxyTarget target = new DbProxyTarget(
                "RDS_INSTANCE", "db1",
                "arn:aws:rds:us-east-1:123456789012:db:db1", "localhost", 5432);
        targetGroup.setTargets(List.of(target));
        proxies.put("app-proxy", proxy);
        targetGroups.put("app-proxy", targetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        DbProxy restoredProxy = service.getDbProxy("app-proxy", "us-east-1");
        DbProxyTargetGroup restoredGroup = service.describeDbProxyTargetGroups(
                "app-proxy", "default", "us-east-1").iterator().next();
        assertNotNull(restoredProxy.getCreatedAt());
        assertEquals(restoredProxy.getCreatedAt(), restoredGroup.getCreatedAt());
        assertEquals("default", restoredGroup.getTargetGroupName());
        assertTrue(restoredGroup.isDefaultTargetGroup());
        assertEquals(List.of(target), restoredGroup.getTargets());
        assertEquals(71, restoredGroup.getMaxConnectionsPercent());
        assertEquals(29, restoredGroup.getMaxIdleConnectionsPercent());
        assertEquals(45, restoredGroup.getConnectionBorrowTimeout());
        assertEquals("SET application_name = 'floci'", restoredGroup.getInitQuery());
        assertEquals(List.of("EXCLUDE_VARIABLE_SETS"),
                restoredGroup.getSessionPinningFilters());
        assertTrue(proxies.get("app-proxy").isEmpty());
        assertTrue(targetGroups.get("app-proxy").isEmpty());
    }

    @Test
    void deleteDbProxySucceedsWhenItsCanonicalTargetGroupIsStale() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "current", 5432);
        DbProxyTargetGroup staleTargetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "stale");
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", staleTargetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        assertDoesNotThrow(() -> service.deleteDbProxy("app-proxy", "us-east-1"));

        assertTrue(proxies.get("us-east-1::app-proxy").isEmpty());
        assertTrue(targetGroups.get("us-east-1::app-proxy").isEmpty());
        verify(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
    }

    @Test
    void deleteDbProxySucceedsWhenTargetGroupIsMissing() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "current", 5432);
        proxies.put("us-east-1::app-proxy", proxy);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        assertDoesNotThrow(() -> service.deleteDbProxy("app-proxy", "us-east-1"));

        assertTrue(proxies.get("us-east-1::app-proxy").isEmpty());
        assertTrue(targetGroups.get("us-east-1::app-proxy").isEmpty());
        verify(proxyManager).stopProxy("db-proxy:" + proxy.getDbProxyArn());
    }

    @Test
    void failedDbProxyDeleteKeepsPortReservedUntilRetrySucceeds() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups =
                spy(new InMemoryStorage<>());
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                instances, new InMemoryStorage<>());
        DbProxy first = service.createDbProxy(
                "first-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        DbProxyTargetGroup originalTargetGroup = targetGroups.get(
                "us-east-1::first-proxy").orElseThrow();
        originalTargetGroup.setMaxConnectionsPercent(79);
        originalTargetGroup.setMaxIdleConnectionsPercent(33);
        originalTargetGroup.setConnectionBorrowTimeout(45);
        originalTargetGroup.setInitQuery("SET application_name = 'floci'");
        originalTargetGroup.setSessionPinningFilters(List.of("EXCLUDE_VARIABLE_SETS"));
        originalTargetGroup.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "db1",
                "arn:aws:rds:us-east-1:123456789012:db:db1", "localhost", 15432)));
        DbInstance instance = persistedInstance("db1", "123456789012", "secret", 15432);
        instance.setContainerHost("localhost");
        instance.setContainerPort(15432);
        instances.put("db1", instance);
        AtomicBoolean relayRunning = new AtomicBoolean(true);
        doAnswer(invocation -> {
            relayRunning.set(false);
            return null;
        }).when(proxyManager).stopProxy("db-proxy:" + first.getDbProxyArn());
        doAnswer(invocation -> {
            relayRunning.set(true);
            return null;
        }).when(proxyManager).startProxy(any(), any(), anyBoolean(), anyInt(), any(),
                anyInt(), any(), any(), any(), any());
        IllegalStateException deleteFailure =
                new IllegalStateException("simulated post-mutation target-group delete failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw deleteFailure;
        }).doCallRealMethod().when(targetGroups).delete("us-east-1::first-proxy");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.deleteDbProxy("first-proxy", "us-east-1"));
        assertSame(deleteFailure, thrown);
        assertEquals(first.getDbProxyArn(),
                service.getDbProxy("first-proxy", "us-east-1").getDbProxyArn());
        assertProxyTargetGroupState(originalTargetGroup,
                targetGroups.get("us-east-1::first-proxy").orElseThrow());
        assertTrue(relayRunning.get());

        DbProxy second = service.createDbProxy(
                "second-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertNotEquals(first.getProxyPort(), second.getProxyPort());

        assertDoesNotThrow(() -> service.deleteDbProxy("first-proxy", "us-east-1"));
        assertFalse(relayRunning.get());
        DbProxy third = service.createDbProxy(
                "third-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertEquals(first.getProxyPort(), third.getProxyPort());
    }

    @Test
    void mutateThenThrowDbProxyDeleteRestoresRetryOwnerAndKeepsPortReserved() {
        InMemoryStorage<String, DbProxy> proxies =
                spy(new InMemoryStorage<String, DbProxy>());
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                instances, new InMemoryStorage<>());
        DbProxy first = service.createDbProxy(
                "first-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        DbProxyTargetGroup originalTargetGroup = targetGroups.get(
                "us-east-1::first-proxy").orElseThrow();
        originalTargetGroup.setMaxConnectionsPercent(79);
        originalTargetGroup.setMaxIdleConnectionsPercent(33);
        originalTargetGroup.setConnectionBorrowTimeout(45);
        originalTargetGroup.setInitQuery("SET application_name = 'floci'");
        originalTargetGroup.setSessionPinningFilters(List.of("EXCLUDE_VARIABLE_SETS"));
        originalTargetGroup.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "db1",
                "arn:aws:rds:us-east-1:123456789012:db:db1", "localhost", 15432)));
        DbInstance instance = persistedInstance("db1", "123456789012", "secret", 15432);
        instance.setContainerHost("localhost");
        instance.setContainerPort(15432);
        instances.put("db1", instance);
        AtomicBoolean relayRunning = new AtomicBoolean(true);
        doAnswer(invocation -> {
            relayRunning.set(false);
            return null;
        }).when(proxyManager).stopProxy("db-proxy:" + first.getDbProxyArn());
        doAnswer(invocation -> {
            relayRunning.set(true);
            return null;
        }).when(proxyManager).startProxy(any(), any(), anyBoolean(), anyInt(), any(),
                anyInt(), any(), any(), any(), any());
        IllegalStateException deleteFailure =
                new IllegalStateException("simulated post-mutation proxy delete failure");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw deleteFailure;
        }).doCallRealMethod().when(proxies).delete("us-east-1::first-proxy");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.deleteDbProxy("first-proxy", "us-east-1"));
        assertSame(deleteFailure, thrown);
        assertEquals(first.getDbProxyArn(), proxies.get(
                "us-east-1::first-proxy").orElseThrow().getDbProxyArn());
        assertProxyTargetGroupState(originalTargetGroup,
                targetGroups.get("us-east-1::first-proxy").orElseThrow());
        assertTrue(relayRunning.get());

        DbProxy second = service.createDbProxy(
                "second-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertNotEquals(first.getProxyPort(), second.getProxyPort());

        assertDoesNotThrow(() -> service.deleteDbProxy("first-proxy", "us-east-1"));
        assertFalse(relayRunning.get());
        DbProxy third = service.createDbProxy(
                "third-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        assertEquals(first.getProxyPort(), third.getProxyPort());
    }

    @Test
    void staleGenerationTargetGroupDoesNotBlockDbInstanceDeletion() {
        when(config.services().rds().mock()).thenReturn(true);
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> instances = new InMemoryStorage<>();
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                instances, new InMemoryStorage<>());
        DbInstance instance = service.createDbInstance(
                "db1", "postgres", "16.3", "admin", "secret", "app",
                "db.t3.micro", 20, false, null, null, null);
        DbProxy currentProxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "current", 5432);
        DbProxyTargetGroup staleTargetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "stale");
        staleTargetGroup.setTargets(List.of(new DbProxyTarget(
                "RDS_INSTANCE", "db1", instance.getDbInstanceArn(), "localhost", 5432)));
        proxies.put("us-east-1::app-proxy", currentProxy);
        targetGroups.put("us-east-1::app-proxy", staleTargetGroup);

        assertDoesNotThrow(() -> service.deleteDbInstance("db1", "us-east-1"));

        assertThrows(AwsException.class, () -> service.getDbInstance("db1", "us-east-1"));
        assertSame(staleTargetGroup,
                targetGroups.get("us-east-1::app-proxy").orElseThrow());
    }

    @Test
    void unsupportedPartitionAndMalformedTargetGroupResourceAreRejected() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "valid", 5432);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "valid");
        targetGroup.setCreatedAt(proxy.getCreatedAt());
        targetGroup.setUpdatedAt(proxy.getCreatedAt());
        targetGroup.setTargetGroupArn(
                "arn:bogus:rds:us-east-1:123456789012:target-group:prx-tg-valid:garbage");
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", targetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        AwsException missing = assertThrows(AwsException.class, () ->
                service.describeDbProxyTargets("app-proxy", "default", "us-east-1"));

        assertEquals("DBProxyTargetGroupNotFoundFault", missing.getErrorCode());
        assertSame(targetGroup, targetGroups.get("us-east-1::app-proxy").orElseThrow());
    }

    @Test
    void guardedLegacyRdsLookupsEnforceAccountOwnership() {
        String foreignAccount = "222222222222";
        String currentAccount = "333333333333";
        RegionResolver currentResolver = new RegionResolver("us-east-1", currentAccount);
        EmulatorConfig currentConfig = mock(EmulatorConfig.class);
        when(currentConfig.defaultAccountId()).thenReturn(currentAccount);

        InMemoryStorage<String, DbCluster> rawForeignClusters = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> rawForeignInstances = new InMemoryStorage<>();
        rawForeignClusters.put("cluster1", persistedCluster(foreignAccount, "secret", 7000));
        rawForeignInstances.put("instance1", persistedInstance(
                "instance1", foreignAccount, "secret", 7001));
        rawForeignClusters.put(currentAccount + "/scoped-cluster",
                persistedCluster(foreignAccount, "secret", 7002));
        rawForeignInstances.put(currentAccount + "/scoped-instance",
                persistedInstance("scoped-instance", foreignAccount, "secret", 7003));
        RdsService foreignService = proxyStoreService(
                currentResolver, currentConfig, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(rawForeignInstances, null, currentAccount),
                new AccountAwareStorageBackend<>(rawForeignClusters, null, currentAccount));

        assertThrows(AwsException.class, () -> foreignService.getDbCluster("cluster1"));
        assertThrows(AwsException.class, () -> foreignService.getDbInstance("instance1"));
        assertTrue(foreignService.listDbClusters(null).isEmpty());
        assertTrue(foreignService.listDbInstances(null).isEmpty());
        assertTrue(rawForeignClusters.get("cluster1").isPresent());
        assertTrue(rawForeignInstances.get("instance1").isPresent());
        assertTrue(rawForeignClusters.get(currentAccount + "/cluster1").isEmpty());
        assertTrue(rawForeignInstances.get(currentAccount + "/instance1").isEmpty());

        InMemoryStorage<String, DbCluster> rawOwnedClusters = new InMemoryStorage<>();
        InMemoryStorage<String, DbInstance> rawOwnedInstances = new InMemoryStorage<>();
        rawOwnedClusters.put("cluster1", persistedCluster(currentAccount, "secret", 7000));
        rawOwnedInstances.put("instance1", persistedInstance(
                "instance1", currentAccount, "secret", 7001));
        RdsService ownedService = proxyStoreService(
                currentResolver, currentConfig, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(rawOwnedInstances, null, currentAccount),
                new AccountAwareStorageBackend<>(rawOwnedClusters, null, currentAccount));

        assertEquals("cluster1", ownedService.getDbCluster("cluster1").getDbClusterIdentifier());
        assertEquals("instance1", ownedService.getDbInstance("instance1").getDbInstanceIdentifier());
        assertTrue(rawOwnedClusters.get("cluster1").isEmpty());
        assertTrue(rawOwnedInstances.get("instance1").isEmpty());
        assertTrue(rawOwnedClusters.get(
                currentAccount + "/us-east-1::cluster1").isPresent());
        assertTrue(rawOwnedInstances.get(
                currentAccount + "/us-east-1::instance1").isPresent());
    }

    @Test
    void sameNamedInstancesInDifferentAccountsUseIndependentRelayKeys() {
        String accountA = "111111111111";
        String accountB = "222222222222";
        InMemoryStorage<String, DbInstance> rawInstances = new InMemoryStorage<>();
        InMemoryStorage<String, DbCluster> rawClusters = new InMemoryStorage<>();
        RdsService serviceA = new RdsService(
                containerManager, proxyManager, ec2Service,
                new RegionResolver("us-east-1", accountA), config,
                new AccountAwareStorageBackend<>(rawInstances, null, accountA),
                new AccountAwareStorageBackend<>(rawClusters, null, accountA),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());
        RdsService serviceB = new RdsService(
                containerManager, proxyManager, ec2Service,
                new RegionResolver("us-east-1", accountB), config,
                new AccountAwareStorageBackend<>(rawInstances, null, accountB),
                new AccountAwareStorageBackend<>(rawClusters, null, accountB),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());

        DbInstance instanceA = serviceA.createDbInstance(
                "shared-db", "postgres", "16.3", "admin", "secret", "app",
                "db.t3.micro", 20, false, null, null, null);
        DbInstance instanceB = serviceB.createDbInstance(
                "shared-db", "postgres", "16.3", "admin", "secret", "app",
                "db.t3.micro", 20, false, null, null, null);
        String relayKeyA = "rds-resource:" + instanceA.getDbInstanceArn();
        String relayKeyB = "rds-resource:" + instanceB.getDbInstanceArn();

        verify(proxyManager).startProxy(eq(relayKeyA), any(), anyBoolean(), anyInt(),
                any(), anyInt(), any(), any(), any(), any());
        verify(proxyManager).startProxy(eq(relayKeyB), any(), anyBoolean(), anyInt(),
                any(), anyInt(), any(), any(), any(), any());

        org.mockito.Mockito.clearInvocations(proxyManager);
        serviceA.deleteDbInstance("shared-db");

        verify(proxyManager).stopProxy(relayKeyA);
        verify(proxyManager, never()).stopProxy(relayKeyB);
        assertTrue(rawInstances.get(accountA + "/us-east-1::shared-db").isEmpty());
        assertTrue(rawInstances.get(accountB + "/us-east-1::shared-db").isPresent());
    }

    @Test
    void sameNamedInstancesAndClustersAreIsolatedByRegion() {
        when(config.services().rds().mock()).thenReturn(true);
        String accountId = "123456789012";
        InMemoryStorage<String, DbInstance> rawInstances = new InMemoryStorage<>();
        InMemoryStorage<String, DbCluster> rawClusters = new InMemoryStorage<>();
        RdsService service = new RdsService(
                containerManager, proxyManager, ec2Service, regionResolver, config,
                new AccountAwareStorageBackend<>(rawInstances, null, accountId),
                new AccountAwareStorageBackend<>(rawClusters, null, accountId),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>());

        DbInstance eastInstance = service.createDbInstance(
                "shared", "postgres", "16.3", "admin", "east-secret", "app",
                "db.t3.micro", 20, false, null, null, null, null, false,
                false, null, Map.of(), List.of(), "us-east-1");
        DbInstance westInstance = service.createDbInstance(
                "shared", "postgres", "16.3", "admin", "west-secret", "app",
                "db.t3.micro", 20, false, null, null, null, null, false,
                false, null, Map.of(), List.of(), "us-west-2");
        DbCluster eastCluster = service.createDbCluster(
                "shared", "aurora-postgresql", "16.3", "admin", "east-cluster",
                "app", false, null, null, null, false, "us-east-1");
        DbCluster westCluster = service.createDbCluster(
                "shared", "aurora-postgresql", "16.3", "admin", "west-cluster",
                "app", false, null, null, null, false, "us-west-2");

        assertEquals(eastInstance.getDbInstanceArn(),
                service.getDbInstance("shared", "us-east-1").getDbInstanceArn());
        assertEquals(westInstance.getDbInstanceArn(),
                service.getDbInstance("shared", "us-west-2").getDbInstanceArn());
        assertEquals(eastCluster.getDbClusterArn(),
                service.getDbCluster("shared", "us-east-1").getDbClusterArn());
        assertEquals(westCluster.getDbClusterArn(),
                service.getDbCluster("shared", "us-west-2").getDbClusterArn());
        assertNotEquals(eastInstance.getContainerStorageResourceId(),
                westInstance.getContainerStorageResourceId());
        assertNotEquals(eastInstance.getContainerStorageResourceId(),
                eastCluster.getContainerStorageResourceId());
        assertTrue(rawInstances.get(accountId + "/us-east-1::shared").isPresent());
        assertTrue(rawInstances.get(accountId + "/us-west-2::shared").isPresent());
        assertTrue(rawClusters.get(accountId + "/us-east-1::shared").isPresent());
        assertTrue(rawClusters.get(accountId + "/us-west-2::shared").isPresent());

        service.modifyDbInstance(
                "shared", "east-updated", null, null, List.of(), "us-east-1");
        service.modifyDbCluster(
                "shared", "east-cluster-updated", null, "us-east-1");
        service.addTagsToResource(
                eastInstance.getDbInstanceArn(), Map.of("region", "east"), "us-east-1");
        assertEquals("east-updated",
                service.getDbInstance("shared", "us-east-1").getMasterPassword());
        assertEquals("west-secret",
                service.getDbInstance("shared", "us-west-2").getMasterPassword());
        assertEquals("east-cluster-updated",
                service.getDbCluster("shared", "us-east-1").getMasterPassword());
        assertEquals("west-cluster",
                service.getDbCluster("shared", "us-west-2").getMasterPassword());
        assertEquals(Map.of("region", "east"),
                service.listTagsForResource(eastInstance.getDbInstanceArn(), "us-east-1"));
        assertEquals(Map.of(),
                service.listTagsForResource(westInstance.getDbInstanceArn(), "us-west-2"));

        service.deleteDbInstance("shared", "us-east-1");
        service.deleteDbCluster("shared", "us-east-1");
        assertThrows(AwsException.class,
                () -> service.getDbInstance("shared", "us-east-1"));
        assertThrows(AwsException.class,
                () -> service.getDbCluster("shared", "us-east-1"));
        assertEquals(westInstance.getDbInstanceArn(),
                service.getDbInstance("shared", "us-west-2").getDbInstanceArn());
        assertEquals(westCluster.getDbClusterArn(),
                service.getDbCluster("shared", "us-west-2").getDbClusterArn());
    }

    @Test
    void proxyRegistrationCannotClaimForeignLegacyTarget() {
        when(config.services().rds().mock()).thenReturn(true);
        String currentAccount = "123456789012";
        String foreignAccount = "222222222222";
        InMemoryStorage<String, DbCluster> rawClusters = new InMemoryStorage<>();
        rawClusters.put("cluster1", persistedCluster(foreignAccount, "secret", 7000));
        AccountAwareStorageBackend<DbCluster> clusters =
                new AccountAwareStorageBackend<>(rawClusters, null, currentAccount);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, currentAccount),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, currentAccount),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, currentAccount),
                clusters);
        service.createDbProxy(
                "app-proxy", "POSTGRESQL", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH,
                1800, false, Map.of(), "us-east-1");

        AwsException exception = assertThrows(AwsException.class, () ->
                service.registerDbProxyTargets(
                        "app-proxy", "default", List.of("cluster1"), List.of(),
                        0, 0, "us-east-1"));

        assertEquals("DBClusterNotFoundFault", exception.getErrorCode());
        assertTrue(rawClusters.get("cluster1").isPresent());
        assertTrue(rawClusters.get(currentAccount + "/cluster1").isEmpty());
        verify(proxyManager, never()).startProxy(
                any(), any(), anyBoolean(), anyInt(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void foreignAccountProxyRestoreDoesNotDeriveVpcFromDefaultAccount() {
        when(config.services().rds().mock()).thenReturn(true);
        String defaultAccount = "123456789012";
        String foreignAccount = "999999999999";
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "foreign-proxy", "us-east-1", foreignAccount, "foreign", 5432);
        proxy.setVpcId(null);
        rawProxies.put(foreignAccount + "/foreign-proxy", proxy);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(rawProxies, null, defaultAccount),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, defaultAccount),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        DbProxy restored = rawProxies.get(
                foreignAccount + "/us-east-1::foreign-proxy").orElseThrow();
        assertNull(restored.getVpcId());
    }

    @Test
    void restorePreservesSameNameLegacyAndCanonicalProxiesAcrossRegions() {
        when(config.services().rds().mock()).thenReturn(true);
        String accountId = "123456789012";
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> rawTargetGroups = new InMemoryStorage<>();
        DbProxy east = persistedProxy(
                "shared-proxy", "us-east-1", accountId, "east", 5432);
        DbProxy west = persistedProxy(
                "shared-proxy", "us-west-2", accountId, "west", 7002);
        DbProxyTargetGroup eastTargetGroup = persistedTargetGroup(
                "shared-proxy", "us-east-1", accountId, "east");
        eastTargetGroup.setCreatedAt(east.getCreatedAt());
        eastTargetGroup.setUpdatedAt(east.getCreatedAt());
        eastTargetGroup.setTargets(List.of(new DbProxyTarget(
                "TRACKED_CLUSTER", "east-cluster",
                "arn:aws:rds:us-east-1:" + accountId + ":cluster:east-cluster",
                "localhost", 5432)));
        DbProxyTargetGroup westTargetGroup = persistedTargetGroup(
                "shared-proxy", "us-west-2", accountId, "west");
        westTargetGroup.setCreatedAt(west.getCreatedAt());
        westTargetGroup.setUpdatedAt(west.getCreatedAt());
        westTargetGroup.setTargets(List.of(new DbProxyTarget(
                "TRACKED_CLUSTER", "west-cluster",
                "arn:aws:rds:us-west-2:" + accountId + ":cluster:west-cluster",
                "localhost", 5432)));
        rawProxies.put(accountId + "/shared-proxy", east);
        rawProxies.put(accountId + "/us-west-2::shared-proxy", west);
        rawTargetGroups.put(accountId + "/shared-proxy", eastTargetGroup);
        rawTargetGroups.put(accountId + "/us-west-2::shared-proxy", westTargetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config,
                new AccountAwareStorageBackend<>(rawProxies, null, accountId),
                new AccountAwareStorageBackend<>(rawTargetGroups, null, accountId),
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        assertEquals("prx-east", rawProxies.get(
                accountId + "/us-east-1::shared-proxy").orElseThrow().getDbProxyResourceId());
        assertEquals("prx-west", rawProxies.get(
                accountId + "/us-west-2::shared-proxy").orElseThrow().getDbProxyResourceId());
        assertEquals("east-cluster", rawTargetGroups.get(
                accountId + "/us-east-1::shared-proxy").orElseThrow()
                .getTargets().getFirst().getRdsResourceId());
        assertEquals("west-cluster", rawTargetGroups.get(
                accountId + "/us-west-2::shared-proxy").orElseThrow()
                .getTargets().getFirst().getRdsResourceId());
        assertTrue(rawProxies.get(accountId + "/shared-proxy").isEmpty());
        assertTrue(rawTargetGroups.get(accountId + "/shared-proxy").isEmpty());
    }

    @Test
    void failedCrossEngineRestoreKeepsProxyPortReserved() {
        InMemoryStorage<String, DbCluster> clusters = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbCluster cluster = persistedCluster("123456789012", "secret", 7000);
        clusters.put("cluster1", cluster);
        DbProxy proxy = persistedProxy(
                "mysql-proxy", "us-east-1", "123456789012", "mysql", 3306);
        proxy.setEngineFamily("MYSQL");
        proxies.put("us-east-1::mysql-proxy", proxy);
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "mysql-proxy", "us-east-1", "123456789012", "mysql");
        targetGroup.setCreatedAt(proxy.getCreatedAt());
        targetGroup.setUpdatedAt(proxy.getCreatedAt());
        targetGroup.setTargets(List.of(new DbProxyTarget(
                "TRACKED_CLUSTER", "cluster1", cluster.getDbClusterArn(), "localhost", 5432)));
        targetGroups.put("us-east-1::mysql-proxy", targetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), clusters);

        service.restorePersistedRuntime();

        DbProxy failed = service.getDbProxy("mysql-proxy", "us-east-1");
        assertEquals("insufficient-resource-limits", failed.getStatus());
        verify(proxyManager, never()).startProxy(
                eq("db-proxy:" + proxy.getDbProxyArn()), any(), anyBoolean(), anyInt(),
                any(), anyInt(), any(), any(), any(), any());

        DbProxy another = service.createDbProxy(
                "another-proxy", "MYSQL", true, false, "NONE", PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, 1800, false, Map.of(), "us-east-1");
        assertNotEquals(failed.getProxyPort(), another.getProxyPort());

        service.deregisterDbProxyTargets(
                "mysql-proxy", "default", List.of("cluster1"), List.of());
        service.createDbInstance(
                "mysql-db", "mysql", "8.0", "admin", "secret", "app",
                "db.t3.micro", 20, false, null, null, null);
        service.registerDbProxyTargets(
                "mysql-proxy", "default", List.of(), List.of("mysql-db"), 0, 0);

        assertEquals("available",
                service.getDbProxy("mysql-proxy", "us-east-1").getStatus());
        verify(proxyManager).startProxy(
                eq("db-proxy:" + proxy.getDbProxyArn()), eq(DatabaseEngine.MYSQL),
                anyBoolean(), eq(failed.getProxyPort()), any(), anyInt(),
                any(), any(), any(), any());
    }

    @Test
    void restoreProxyWithoutTargetsRecoversAvailableStatus() {
        InMemoryStorage<String, DbProxy> proxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> targetGroups = new InMemoryStorage<>();
        DbProxy proxy = persistedProxy(
                "app-proxy", "us-east-1", "123456789012", "current", 5432);
        proxy.setStatus("insufficient-resource-limits");
        DbProxyTargetGroup targetGroup = persistedTargetGroup(
                "app-proxy", "us-east-1", "123456789012", "current");
        targetGroup.setCreatedAt(proxy.getCreatedAt());
        targetGroup.setUpdatedAt(proxy.getCreatedAt());
        proxies.put("us-east-1::app-proxy", proxy);
        targetGroups.put("us-east-1::app-proxy", targetGroup);
        RdsService service = proxyStoreService(
                regionResolver, config, proxies, targetGroups,
                new InMemoryStorage<>(), new InMemoryStorage<>());

        service.restorePersistedRuntime();

        assertEquals("available",
                service.getDbProxy("app-proxy", "us-east-1").getStatus());
        verify(proxyManager, never()).startProxy(
                eq("db-proxy:" + proxy.getDbProxyArn()), any(), anyBoolean(), anyInt(),
                any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void proxyTargetGroupRejectsUnsupportedNameCardinalityAndEngine() {
        when(config.services().rds().mock()).thenReturn(true);
        rdsService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null);
        rdsService.createDbProxy("mysql-proxy", "MYSQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());

        AwsException missingGroup = assertThrows(AwsException.class, () ->
                rdsService.describeDbProxyTargets("mysql-proxy", "other"));
        assertEquals("DBProxyTargetGroupNotFoundFault", missingGroup.getErrorCode());

        AwsException multiple = assertThrows(AwsException.class, () ->
                rdsService.registerDbProxyTargets("mysql-proxy", "default",
                        List.of("cluster1", "cluster2"), List.of(), 0, 0));
        assertEquals("InvalidParameterCombination", multiple.getErrorCode());

        AwsException mismatch = assertThrows(AwsException.class, () ->
                rdsService.registerDbProxyTargets("mysql-proxy", "default",
                        List.of("cluster1"), List.of(), 0, 0));
        assertEquals("InvalidParameterValue", mismatch.getErrorCode());
    }

    @Test
    void restorePersistedRuntimeReArmsDbProxyRelayAcrossRestart() {
        StorageBackend<String, DbInstance> instances = new InMemoryStorage<>();
        StorageBackend<String, DbCluster> clusters = new InMemoryStorage<>();
        StorageBackend<String, DbParameterGroup> parameterGroups = new InMemoryStorage<>();
        StorageBackend<String, DbClusterParameterGroup> clusterParameterGroups = new InMemoryStorage<>();
        StorageBackend<String, DbSubnetGroup> subnetGroups = new InMemoryStorage<>();
        // Shared across the two RdsService instances: this is what persists a proxy over a "restart".
        StorageBackend<String, DbProxy> proxies = new InMemoryStorage<>();
        StorageBackend<String, DbProxyTargetGroup> proxyTargetGroups = new InMemoryStorage<>();

        when(containerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("initial-container", "cluster1", "localhost", 5432));

        RdsService initialService = new RdsService(containerManager, proxyManager, ec2Service,
                regionResolver, config, instances, clusters, parameterGroups, clusterParameterGroups,
                subnetGroups, null, null, proxies, proxyTargetGroups);
        initialService.createDbCluster("cluster1", "aurora-postgresql", "16.3",
                "admin", "secret", "app", false, null);
        initialService.createDbProxy("app-proxy", "POSTGRESQL", true, false, PROXY_ROLE_ARN,
                PROXY_SUBNET_IDS, List.of(), PROXY_AUTH, Map.of());
        initialService.registerDbProxyTargets("app-proxy", null, List.of("cluster1"), List.of(), 0, 0);

        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        when(restoredContainerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("restored-container", "cluster1", "127.0.0.1", 15432));

        RdsService restoredService = new RdsService(restoredContainerManager, restoredProxyManager, ec2Service,
                regionResolver, config, instances, clusters, parameterGroups, clusterParameterGroups,
                subnetGroups, null, null, proxies, proxyTargetGroups);
        restoredService.restorePersistedRuntime();

        // The proxy survives the restart and its relay is re-armed against the restored backend.
        DbProxy restored = restoredService.getDbProxy("app-proxy");
        assertEquals("app-proxy", restored.getDbProxyName());
        verify(restoredProxyManager).startProxy(eq("db-proxy:" + restored.getDbProxyArn()),
                eq(DatabaseEngine.POSTGRES),
                eq(false), anyInt(), eq("127.0.0.1"), eq(15432),
                eq("admin"), eq("secret"), eq("app"), any());
    }

    @Test
    void restoreDbProxyAuthCallbackKeepsTheTargetAccount() {
        String defaultAccount = "123456789012";
        String targetAccount = "999999999999";
        InMemoryStorage<String, DbInstance> rawInstances = new InMemoryStorage<>();
        InMemoryStorage<String, DbCluster> rawClusters = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxy> rawProxies = new InMemoryStorage<>();
        InMemoryStorage<String, DbProxyTargetGroup> rawTargetGroups = new InMemoryStorage<>();

        // A same-named default-account cluster makes an account-blind callback observably wrong.
        DbCluster defaultCluster = persistedCluster(
                defaultAccount, "default-secret", 7000);
        defaultCluster.setContainerStorageResourceId("cluster-DEFAULT");
        defaultCluster.setDockerVolumeName("floci-rds-default-cluster");
        DbCluster targetCluster = persistedCluster(
                targetAccount, "target-secret", 7001);
        targetCluster.setContainerStorageResourceId("cluster-TARGET");
        targetCluster.setDockerVolumeName("floci-rds-target-cluster");
        rawClusters.put(defaultAccount + "/cluster1", defaultCluster);
        rawClusters.put(targetAccount + "/cluster1", targetCluster);

        DbProxy proxy = new DbProxy();
        proxy.setDbProxyName("app-proxy");
        proxy.setDbProxyArn("arn:aws:rds:us-east-1:" + targetAccount + ":db-proxy:prx-abc");
        proxy.setDbProxyResourceId("prx-abc");
        proxy.setEngineFamily("POSTGRESQL");
        proxy.setProxyPort(5432);
        proxy.setEndpointHost("stale-host");
        proxy.setStatus("available");
        Instant proxyCreatedAt = Instant.now();
        proxy.setCreatedAt(proxyCreatedAt);
        rawProxies.put(targetAccount + "/app-proxy", proxy);

        DbProxyTargetGroup targetGroup = new DbProxyTargetGroup();
        targetGroup.setDbProxyName("app-proxy");
        targetGroup.setTargetGroupName("default");
        targetGroup.setTargetGroupArn("arn:aws:rds:us-east-1:" + targetAccount
                + ":target-group:prx-tg-abc");
        targetGroup.setCreatedAt(proxyCreatedAt);
        targetGroup.setUpdatedAt(proxyCreatedAt);
        targetGroup.setTargets(List.of(new DbProxyTarget("TRACKED_CLUSTER", "cluster1",
                "arn:aws:rds:us-east-1:" + targetAccount + ":cluster:cluster1",
                "stale-host", 5432)));
        rawTargetGroups.put(targetAccount + "/app-proxy", targetGroup);

        RdsContainerManager restoredContainerManager = mock(RdsContainerManager.class);
        RdsProxyManager restoredProxyManager = mock(RdsProxyManager.class);
        when(restoredContainerManager.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("restored-container", "cluster1",
                        "127.0.0.1", 15432));

        RdsService restoredService = new RdsService(restoredContainerManager, restoredProxyManager,
                ec2Service, regionResolver, config,
                new AccountAwareStorageBackend<>(rawInstances, null, defaultAccount),
                new AccountAwareStorageBackend<>(rawClusters, null, defaultAccount),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, defaultAccount),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, defaultAccount),
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, defaultAccount),
                null, null,
                new AccountAwareStorageBackend<>(rawProxies, null, defaultAccount),
                new AccountAwareStorageBackend<>(rawTargetGroups, null, defaultAccount));

        restoredService.restorePersistedRuntime();

        ArgumentCaptor<RdsAuthProxy.PasswordValidator> validator =
                ArgumentCaptor.forClass(RdsAuthProxy.PasswordValidator.class);
        verify(restoredProxyManager).startProxy(eq("db-proxy:" + proxy.getDbProxyArn()),
                eq(DatabaseEngine.POSTGRES),
                eq(false), eq(5432), eq("127.0.0.1"), eq(15432), eq("admin"),
                eq("target-secret"), eq("app"), validator.capture());
        assertTrue(validator.getValue().validate("admin", "target-secret"));
        assertFalse(validator.getValue().validate("admin", "default-secret"));
        assertTrue(rawProxies.get(targetAccount + "/us-east-1::app-proxy").isPresent());
        assertTrue(rawTargetGroups.get(targetAccount + "/us-east-1::app-proxy").isPresent());
        assertTrue(rawProxies.get(targetAccount + "/app-proxy").isEmpty());
        assertTrue(rawTargetGroups.get(targetAccount + "/app-proxy").isEmpty());
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

    private RdsService proxyStoreService(
            RegionResolver resolver, EmulatorConfig serviceConfig,
            StorageBackend<String, DbProxy> proxies,
            StorageBackend<String, DbProxyTargetGroup> targetGroups,
            StorageBackend<String, DbInstance> instances,
            StorageBackend<String, DbCluster> clusters) {
        return new RdsService(containerManager, proxyManager, ec2Service, resolver, serviceConfig,
                instances, clusters, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), null, null, proxies, targetGroups);
    }

    private static DbProxy persistedProxy(
            String name, String region, String accountId, String suffix, int proxyPort) {
        DbProxy proxy = new DbProxy();
        proxy.setDbProxyName(name);
        proxy.setDbProxyArn("arn:aws:rds:" + region + ":" + accountId
                + ":db-proxy:prx-" + suffix);
        proxy.setDbProxyResourceId("prx-" + suffix);
        proxy.setEngineFamily("POSTGRESQL");
        proxy.setRoleArn("arn:aws:iam::" + accountId + ":role/proxy");
        proxy.setVpcId("vpc-default");
        proxy.setVpcSubnetIds(PROXY_SUBNET_IDS);
        proxy.setAuth(PROXY_AUTH);
        proxy.setProxyPort(proxyPort);
        proxy.setEndpointHost("localhost");
        proxy.setStatus("available");
        proxy.setCreatedAt(persistedGenerationTime(suffix));
        proxy.setUpdatedAt(proxy.getCreatedAt());
        return proxy;
    }

    private static DbProxyTargetGroup persistedTargetGroup(
            String proxyName, String region, String accountId, String suffix) {
        DbProxyTargetGroup targetGroup = new DbProxyTargetGroup();
        targetGroup.setDbProxyName(proxyName);
        targetGroup.setTargetGroupName("default");
        targetGroup.setTargetGroupArn("arn:aws:rds:" + region + ":" + accountId
                + ":target-group:prx-tg-" + suffix);
        targetGroup.setDefaultTargetGroup(true);
        targetGroup.setCreatedAt(persistedGenerationTime(suffix));
        targetGroup.setUpdatedAt(targetGroup.getCreatedAt());
        return targetGroup;
    }

    private static void assertProxyTargetGroupState(
            DbProxyTargetGroup expected, DbProxyTargetGroup actual) {
        assertEquals(expected.getDbProxyName(), actual.getDbProxyName());
        assertEquals(expected.getTargetGroupName(), actual.getTargetGroupName());
        assertEquals(expected.getTargetGroupArn(), actual.getTargetGroupArn());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.isDefaultTargetGroup(), actual.isDefaultTargetGroup());
        assertEquals(expected.getCreatedAt(), actual.getCreatedAt());
        assertEquals(expected.getUpdatedAt(), actual.getUpdatedAt());
        assertEquals(expected.getMaxConnectionsPercent(), actual.getMaxConnectionsPercent());
        assertEquals(expected.getMaxIdleConnectionsPercent(), actual.getMaxIdleConnectionsPercent());
        assertEquals(expected.getConnectionBorrowTimeout(), actual.getConnectionBorrowTimeout());
        assertEquals(expected.getInitQuery(), actual.getInitQuery());
        assertEquals(expected.getSessionPinningFilters(), actual.getSessionPinningFilters());
        assertEquals(expected.getTargets().size(), actual.getTargets().size());
        for (int index = 0; index < expected.getTargets().size(); index++) {
            DbProxyTarget expectedTarget = expected.getTargets().get(index);
            DbProxyTarget actualTarget = actual.getTargets().get(index);
            assertEquals(expectedTarget.getType(), actualTarget.getType());
            assertEquals(expectedTarget.getRdsResourceId(), actualTarget.getRdsResourceId());
            assertEquals(expectedTarget.getTargetArn(), actualTarget.getTargetArn());
            assertEquals(expectedTarget.getEndpoint(), actualTarget.getEndpoint());
            assertEquals(expectedTarget.getPort(), actualTarget.getPort());
            assertEquals(expectedTarget.getTargetHealth(), actualTarget.getTargetHealth());
        }
    }

    private static Instant persistedGenerationTime(String suffix) {
        return Instant.ofEpochSecond(Integer.toUnsignedLong(suffix.hashCode()));
    }

    private static DbProxyAuth proxyAuthWithUserName(String userName) {
        DbProxyAuth auth = new DbProxyAuth(
                "SECRETS", PROXY_AUTH.getFirst().getSecretArn(), "DISABLED", null, null);
        auth.setUserName(userName);
        return auth;
    }

    private static List<Subnet> defaultSubnets() {
        return List.of(
                subnet("subnet-default-a", "vpc-default", "us-east-1a"),
                subnet("subnet-default-b", "vpc-default", "us-east-1b"));
    }

    private static DbInstance persistedInstance(
            String instanceId, String accountId, String password, int proxyPort) {
        DbInstance instance = new DbInstance(
                instanceId, DatabaseEngine.POSTGRES, "16.3", "admin", password,
                "app", "db.t3.micro", 20, DbInstanceStatus.AVAILABLE,
                new DbEndpoint("stale-host", proxyPort), false, null, null,
                Instant.now(), proxyPort);
        instance.setDbInstanceArn(
                "arn:aws:rds:us-east-1:" + accountId + ":db:" + instanceId);
        return instance;
    }

    private static DbCluster persistedCluster(String accountId, String password, int proxyPort) {
        DbCluster cluster = new DbCluster("cluster1", DatabaseEngine.POSTGRES, "16.3",
                "admin", password, "app", DbInstanceStatus.AVAILABLE,
                new DbEndpoint("stale-host", proxyPort), new DbEndpoint("stale-host", proxyPort),
                false, List.of(), null, Instant.now(), proxyPort);
        cluster.setDbClusterArn("arn:aws:rds:us-east-1:" + accountId + ":cluster:cluster1");
        cluster.setVolumeId("volume-" + accountId);
        return cluster;
    }

    private static Subnet subnet(String subnetId, String vpcId, String availabilityZone) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setVpcId(vpcId);
        subnet.setAvailabilityZone(availabilityZone);
        return subnet;
    }
}
