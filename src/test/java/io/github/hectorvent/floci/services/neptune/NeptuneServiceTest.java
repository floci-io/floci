package io.github.hectorvent.floci.services.neptune;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.neptune.container.NeptuneContainerHandle;
import io.github.hectorvent.floci.services.neptune.container.NeptuneContainerManager;
import io.github.hectorvent.floci.services.neptune.model.NeptuneCluster;
import io.github.hectorvent.floci.services.neptune.model.NeptuneClusterSettings;
import io.github.hectorvent.floci.services.neptune.model.NeptuneDbType;
import io.github.hectorvent.floci.services.neptune.model.NeptuneInstance;
import io.github.hectorvent.floci.services.neptune.model.NeptuneInstanceSettings;
import io.github.hectorvent.floci.services.neptune.proxy.NeptuneProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NeptuneServiceTest {

    private NeptuneService service;
    private NeptuneContainerManager containerManager;
    private NeptuneProxyManager proxyManager;
    private EmulatorConfig.NeptuneServiceConfig neptuneConfig;

    @BeforeEach
    void setUp() {
        containerManager = mock(NeptuneContainerManager.class);
        proxyManager = mock(NeptuneProxyManager.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        neptuneConfig = mock(EmulatorConfig.NeptuneServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.neptune()).thenReturn(neptuneConfig);
        when(neptuneConfig.proxyBasePort()).thenReturn(18182);
        when(neptuneConfig.proxyMaxPort()).thenReturn(18199);
        when(neptuneConfig.dbType()).thenReturn("gremlin");
        when(neptuneConfig.defaultImage()).thenReturn("tinkerpop/gremlin-server:3.7");
        when(neptuneConfig.defaultNeo4jImage()).thenReturn("neo4j:5");
        when(config.hostname()).thenReturn(Optional.of("localhost"));

        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        when(containerManager.tryStart(anyString(), anyString(), any(NeptuneDbType.class)))
                .thenReturn(new NeptuneContainerHandle("cid", "c", "localhost", 8182));
        doNothing().when(proxyManager).startProxy(anyString(), anyInt(), anyString(), anyInt());

        service = new NeptuneService(config, regionResolver, containerManager, proxyManager, storageFactory);
    }

    @Test
    void failedProvisioningRollsBackContainerAndReleasesProxyPort() {
        NeptuneContainerHandle handle = new NeptuneContainerHandle("cid", "c", "localhost", 8182);
        when(containerManager.tryStart(anyString(), anyString(), any(NeptuneDbType.class)))
                .thenReturn(handle);

        // Proxy startup blows up after the port is reserved and the container is started.
        doThrow(new RuntimeException("proxy boom"))
                .when(proxyManager).startProxy(eq("c"), anyInt(), anyString(), anyInt());

        // The original failure must propagate to the caller (we clean up, then rethrow).
        assertThrows(RuntimeException.class,
                () -> service.createDbCluster("c", "1.3.2.1", false));

        // Rollback stopped the proxy and the already-started container (by id).
        verify(proxyManager).stopProxy("c");
        verify(containerManager).stopByClusterId("c");

        // The reserved proxy port was released: a subsequent successful create reuses the base port
        // instead of skipping to the next one (which is what a leak would cause).
        doNothing().when(proxyManager).startProxy(anyString(), anyInt(), anyString(), anyInt());
        NeptuneCluster recovered = service.createDbCluster("c2", "1.3.2.1", false);
        assertEquals(18182, recovered.getProxyPort(),
                "Port from the failed create must be released so the next cluster reuses it");
    }

    @Test
    void jvmErrorDuringProvisioningStillRollsBack() {
        NeptuneContainerHandle handle = new NeptuneContainerHandle("cid", "c", "localhost", 8182);
        when(containerManager.tryStart(anyString(), anyString(), any(NeptuneDbType.class)))
                .thenReturn(handle);

        // A JVM Error (not a RuntimeException) escapes provisioning — a catch (RuntimeException)
        // would miss it, so rollback must run from a finally instead.
        doThrow(new StackOverflowError("boom"))
                .when(proxyManager).startProxy(eq("c"), anyInt(), anyString(), anyInt());

        assertThrows(StackOverflowError.class,
                () -> service.createDbCluster("c", "1.3.2.1", false));

        // Rollback still fired despite the Error: proxy and container stopped, port released.
        verify(proxyManager).stopProxy("c");
        verify(containerManager).stopByClusterId("c");

        doNothing().when(proxyManager).startProxy(anyString(), anyInt(), anyString(), anyInt());
        NeptuneCluster recovered = service.createDbCluster("c2", "1.3.2.1", false);
        assertEquals(18182, recovered.getProxyPort(),
                "Port from the failed create must be released even when the failure is an Error");
    }

    @Test
    void failedContainerStartupCleansUpContainerByIdAndReleasesPort() {
        // containerManager.tryStart(...) throws — this models both a container that never started
        // and (crucially) a readiness timeout, where start() created + registered the container
        // before throwing, so no handle ever reaches the service.
        doThrow(new RuntimeException("readiness boom"))
                .when(containerManager).tryStart(eq("c"), anyString(), any(NeptuneDbType.class));

        // The original failure must propagate to the caller (we clean up, then rethrow).
        assertThrows(RuntimeException.class,
                () -> service.createDbCluster("c", "1.3.2.1", false));

        // No handle returned, so the proxy never started and must not be stopped...
        verify(proxyManager, never()).stopProxy(anyString());
        // ...but the container must still be cleaned up by id, since start() may have created and
        // registered it before failing. Cleaning up by handle here would orphan it.
        verify(containerManager).stopByClusterId("c");

        // The reserved proxy port was still released: a subsequent successful create reuses the base port.
        when(containerManager.tryStart(anyString(), anyString(), any(NeptuneDbType.class)))
                .thenReturn(new NeptuneContainerHandle("cid", "c2", "localhost", 8182));
        NeptuneCluster recovered = service.createDbCluster("c2", "1.3.2.1", false);
        assertEquals(18182, recovered.getProxyPort(),
                "Port from the failed create must be released so the next cluster reuses it");
    }

    @Test
    void listDbClustersMatchesByArnButNotForeignArn() {
        NeptuneCluster cluster = service.createDbCluster("my-cluster", "1.3.2.1", false);

        String arn = cluster.getDbClusterArn();
        assertEquals(1, service.listDbClusters(arn).size());
        assertTrue(service.listDbClusters(arn.replace("000000000000", "999999999999")).isEmpty(),
                "cross-account ARN must not match");
        assertTrue(service.listDbClusters(arn.replace("us-east-1", "eu-west-1")).isEmpty(),
                "cross-region ARN must not match");
    }

    @Test
    void listDbInstancesMatchesByArnButNotForeignArn() {
        service.createDbCluster("my-cluster", "1.3.2.1", false);
        NeptuneInstance instance = service.createDbInstance(
                "my-instance", "my-cluster", "db.r5.large", null, false);

        String arn = instance.getDbInstanceArn();
        assertEquals(1, service.listDbInstances(arn).size());
        assertTrue(service.listDbInstances(arn.replace("000000000000", "999999999999")).isEmpty(),
                "cross-account ARN must not match");
        assertTrue(service.listDbInstances(arn.replace("us-east-1", "eu-west-1")).isEmpty(),
                "cross-region ARN must not match");
    }

    @Test
    void exhaustedProxyPortRangeThrowsMappableCapacityFault() {
        // Shrink the range to one port so the second create exhausts it.
        when(neptuneConfig.proxyMaxPort()).thenReturn(18182);
        service.createDbCluster("c1", "1.3.2.1", false);

        AwsException e = assertThrows(AwsException.class,
                () -> service.createDbCluster("c2", "1.3.2.1", false));
        // Real Neptune wire code (maps to InsufficientStorageClusterCapacityFault), not the
        // fabricated "InsufficientNeptuneCapacity" the SDK couldn't map to a typed exception.
        assertEquals("InsufficientStorageClusterCapacity", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void createWithoutDockerDaemonStillReachesAvailable() {
        // tryStart() returns null when no Docker daemon is reachable. The cluster record is
        // metadata, so the create still succeeds, the cluster reaches 'available' on the first
        // describe (what SDK/Terraform waiters poll), and no proxy is started.
        when(containerManager.tryStart(anyString(), anyString(), any(NeptuneDbType.class))).thenReturn(null);

        NeptuneCluster created = service.createDbCluster("no-docker-cluster", "1.3.2.1", false);

        assertEquals("available", created.getStatus());
        assertEquals("localhost", created.getEndpoint());
        assertEquals(18182, created.getProxyPort());
        verify(proxyManager, never()).startProxy(anyString(), anyInt(), anyString(), anyInt());

        assertEquals("no-docker-cluster", service.getDbCluster("no-docker-cluster").getDbClusterIdentifier());

        // Delete must not reach for a container that was never created.
        service.deleteDbCluster("no-docker-cluster");
        verify(containerManager, never()).stop(any());
    }

    private static NeptuneClusterSettings settings(Integer port, Integer backupRetentionPeriod,
                                                   Boolean deletionProtection, List<String> vpcSecurityGroupIds,
                                                   List<String> enableLogTypes, List<String> disableLogTypes) {
        return new NeptuneClusterSettings(null, port, backupRetentionPeriod, null, null, null, null,
                vpcSecurityGroupIds, null, null, null, deletionProtection, null, enableLogTypes, disableLogTypes,
                null, null, null);
    }

    @Test
    void createDbClusterStoresEverySettingAndTheTags() {
        NeptuneClusterSettings requested = new NeptuneClusterSettings(
                List.of("us-east-1a", "us-east-1b"), null, 7, "03:00-04:00", "Sun:05:00-Sun:06:00",
                "my-subnets", "my-params", List.of("sg-1"), false, null, "iopt1", true, true,
                List.of("audit"), null, null, 2.5, 8.0);

        NeptuneCluster c = service.createDbCluster("settings", "1.3.2.1", false, requested, Map.of("Name", "settings"));

        assertEquals(List.of("us-east-1a", "us-east-1b"), c.getAvailabilityZones());
        assertEquals(7, c.getBackupRetentionPeriod());
        assertEquals("03:00-04:00", c.getPreferredBackupWindow());
        assertEquals("sun:05:00-sun:06:00", c.getPreferredMaintenanceWindow());
        assertEquals("my-subnets", c.getDbSubnetGroupName());
        assertEquals("my-params", c.getDbClusterParameterGroupName());
        assertEquals(List.of("sg-1"), c.getVpcSecurityGroupIds());
        assertFalse(c.isStorageEncrypted());
        assertEquals("iopt1", c.getStorageType());
        assertTrue(c.isDeletionProtection());
        assertTrue(c.isCopyTagsToSnapshot());
        assertEquals(List.of("audit"), c.getEnabledCloudwatchLogsExports());
        assertEquals(Double.valueOf(2.5), c.getServerlessV2MinCapacity());
        assertEquals(Double.valueOf(8.0), c.getServerlessV2MaxCapacity());
        assertEquals(Map.of("Name", "settings"), c.getTags());
    }

    @Test
    void storageEncryptedDefaultsToFalseLikeAws() {
        NeptuneCluster c = service.createDbCluster("plain", "1.3.2.1", false);
        assertFalse(c.isStorageEncrypted());
        assertEquals(1, c.getBackupRetentionPeriod());
        assertTrue(c.getAvailabilityZones().isEmpty());
        assertTrue(c.getTags().isEmpty());
    }

    @Test
    void requestedPortIsHonouredWhenFreeInTheProxyRangeAndFallsBackOtherwise() {
        assertEquals(18190, service.createDbCluster("p1", "1.3.2.1", false,
                settings(18190, null, null, null, null, null), Map.of()).getPort());
        assertEquals(18182, service.createDbCluster("p2", "1.3.2.1", false,
                settings(18190, null, null, null, null, null), Map.of()).getPort());
        assertEquals(18183, service.createDbCluster("p3", "1.3.2.1", false,
                settings(9999, null, null, null, null, null), Map.of()).getPort());

        service.deleteDbCluster("p1");
        assertEquals(18190, service.createDbCluster("p4", "1.3.2.1", false,
                settings(18190, null, null, null, null, null), Map.of()).getPort());
    }

    @Test
    void invalidSettingsAreRejectedBeforeAPortIsReserved() {
        AwsException e = assertThrows(AwsException.class, () -> service.createDbCluster("bad", "1.3.2.1", false,
                settings(null, 99, null, null, null, null), Map.of()));
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals(18182, service.createDbCluster("good", "1.3.2.1", false).getPort());
    }

    @Test
    void deleteIsRefusedWhileDeletionProtectionIsOn() {
        service.createDbCluster("protected", "1.3.2.1", false,
                settings(null, null, true, null, null, null), Map.of());

        AwsException e = assertThrows(AwsException.class, () -> service.deleteDbCluster("protected"));
        assertEquals("InvalidParameterCombination", e.getErrorCode());
        assertTrue(service.hasCluster("protected"));

        service.modifyDbCluster("protected", null, null, settings(null, null, false, null, null, null));
        service.deleteDbCluster("protected");
        assertFalse(service.hasCluster("protected"));
    }

    @Test
    void modifyMergesLogExportsAndClearsSecurityGroupsWithAnEmptyList() {
        service.createDbCluster("logs", "1.3.2.1", false,
                settings(null, null, null, List.of("sg-1"), List.of("audit"), null), Map.of());

        NeptuneCluster c = service.modifyDbCluster("logs", null, null,
                settings(null, 3, null, List.of(), List.of("slowquery"), List.of("audit")));

        assertEquals(List.of("slowquery"), c.getEnabledCloudwatchLogsExports());
        assertTrue(c.getVpcSecurityGroupIds().isEmpty());
        assertEquals(3, c.getBackupRetentionPeriod());

        NeptuneCluster unchanged = service.modifyDbCluster("logs", null, null, NeptuneClusterSettings.unchanged());
        assertEquals(List.of("slowquery"), unchanged.getEnabledCloudwatchLogsExports());
        assertEquals(3, unchanged.getBackupRetentionPeriod());
    }

    @Test
    void rolesAreAssociatedOnceAndRemovedByArn() {
        service.createDbCluster("roles", "1.3.2.1", false);
        String roleArn = "arn:aws:iam::000000000000:role/neptune-load";

        assertEquals(List.of(roleArn), service.addRoleToDbCluster("roles", roleArn).getAssociatedRoleArns());
        assertEquals("DBClusterRoleAlreadyExists",
                assertThrows(AwsException.class, () -> service.addRoleToDbCluster("roles", roleArn)).getErrorCode());

        assertTrue(service.removeRoleFromDbCluster("roles", roleArn).getAssociatedRoleArns().isEmpty());
        assertEquals("DBClusterRoleNotFound",
                assertThrows(AwsException.class, () -> service.removeRoleFromDbCluster("roles", roleArn)).getErrorCode());
    }

    @Test
    void tagsAreResolvedByTheExactArnOnly() {
        NeptuneCluster c = service.createDbCluster("tagged", "1.3.2.1", false,
                NeptuneClusterSettings.defaults(), Map.of("env", "test"));
        String arn = c.getDbClusterArn();

        assertTrue(service.hasResourceWithArn(arn));
        assertEquals(Map.of("env", "test"), service.listTagsForResource(arn));

        service.addTagsToResource(arn, Map.of("team", "graph"));
        service.removeTagsFromResource(arn, List.of("env", "absent"));
        assertEquals(Map.of("team", "graph"), service.listTagsForResource(arn));

        String foreign = arn.replace("us-east-1", "eu-west-1");
        assertFalse(service.hasResourceWithArn(foreign));
        assertEquals("DBClusterNotFoundFault",
                assertThrows(AwsException.class, () -> service.listTagsForResource(foreign)).getErrorCode());
        assertEquals("InvalidParameterValue",
                assertThrows(AwsException.class, () -> service.listTagsForResource("tagged")).getErrorCode());

        NeptuneInstance instance = service.createDbInstance("tagged-db", "tagged", null, null, false,
                Map.of("role", "writer"));
        assertTrue(service.hasResourceWithArn(instance.getDbInstanceArn()));
        assertEquals(Map.of("role", "writer"), service.listTagsForResource(instance.getDbInstanceArn()));
    }

    @Test
    void parameterGroupFamilyFollowsTheEngineMajorAndMinor() {
        assertEquals("neptune1", NeptuneService.parameterGroupFamily("1.0.5.1"));
        assertEquals("neptune1", NeptuneService.parameterGroupFamily("1.1.1.0"));
        assertEquals("neptune1.2", NeptuneService.parameterGroupFamily("1.2.1.0"));
        assertEquals("neptune1.3", NeptuneService.parameterGroupFamily("1.3.2.1"));
        assertEquals("neptune1.4", NeptuneService.parameterGroupFamily("1.4.5.1"));
        assertEquals("neptune1.3", NeptuneService.parameterGroupFamily(null));
        assertEquals("neptune1.3", NeptuneService.parameterGroupFamily("latest"));
    }

    // --- Instance settings the Terraform aws_neptune_cluster_instance resource reads back ---

    @Test
    void createDbInstanceStoresTheRequestedSettingsAndAwsDefaultsOtherwise() {
        service.createDbCluster("instance-settings", "1.3.2.1", false);

        NeptuneInstance requested = service.createDbInstance("with-settings", "instance-settings",
                "db.r5.large", null, false,
                new NeptuneInstanceSettings("us-east-1b", false, 0, true, "custom-params", "custom-subnets",
                        "07:00-08:00", "Sun:05:00-Sun:06:00"),
                Map.of("Name", "with-settings"));
        assertEquals("us-east-1b", requested.getAvailabilityZone());
        assertFalse(requested.isAutoMinorVersionUpgrade());
        assertEquals(0, requested.getPromotionTier());
        assertTrue(requested.isPubliclyAccessible());
        assertEquals("custom-params", requested.getDbParameterGroupName());
        assertEquals("custom-subnets", requested.getDbSubnetGroupName());
        assertEquals("07:00-08:00", requested.getPreferredBackupWindow());
        assertEquals("sun:05:00-sun:06:00", requested.getPreferredMaintenanceWindow());
        assertEquals("with-settings", requested.getTags().get("Name"));

        NeptuneInstance defaults = service.createDbInstance("bare", "instance-settings", "db.r5.large", null, false);
        assertTrue(defaults.isAutoMinorVersionUpgrade());
        assertEquals(1, defaults.getPromotionTier());
        assertFalse(defaults.isPubliclyAccessible());
        assertNull(defaults.getAvailabilityZone());
        assertNull(defaults.getDbParameterGroupName());
        assertNull(defaults.getPreferredMaintenanceWindow());
    }

    @Test
    void modifyDbInstanceAppliesOnlyTheSettingsTheRequestCarries() {
        service.createDbCluster("instance-modify", "1.3.2.1", false);
        service.createDbInstance("to-modify", "instance-modify", "db.r5.large", null, false,
                new NeptuneInstanceSettings(null, true, 2, null, null, null, null, null), Map.of());

        NeptuneInstance modified = service.modifyDbInstance("to-modify", null, null,
                new NeptuneInstanceSettings(null, false, null, null, "tuned", null, null, "Mon:02:00-Mon:03:00"));

        assertFalse(modified.isAutoMinorVersionUpgrade());
        assertEquals(2, modified.getPromotionTier(), "An omitted PromotionTier must keep its value");
        assertEquals("tuned", modified.getDbParameterGroupName());
        assertEquals("mon:02:00-mon:03:00", modified.getPreferredMaintenanceWindow());
        assertEquals("db.r5.large", modified.getDbInstanceClass(), "An omitted DBInstanceClass must keep its value");
    }

    @Test
    void instanceSettingsRejectAPromotionTierOutsideTheAwsRange() {
        service.createDbCluster("instance-invalid", "1.3.2.1", false);

        AwsException ex = assertThrows(AwsException.class, () -> service.createDbInstance("bad-tier",
                "instance-invalid", "db.r5.large", null, false,
                new NeptuneInstanceSettings(null, null, 16, null, null, null, null, null), Map.of()));

        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertFalse(service.hasInstance("bad-tier"), "A rejected create must not leave an instance behind");
    }
}
