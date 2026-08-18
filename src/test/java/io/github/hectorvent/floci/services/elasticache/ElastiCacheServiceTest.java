package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class ElastiCacheServiceTest {

    private ElastiCacheService service;
    private ElastiCacheContainerManager containerManager;
    private ElastiCacheProxyManager proxyManager;
    private Ec2Service ec2Service;
    private RegionResolver regionResolver;

    @BeforeEach
    void setUp() {
        containerManager = mock(ElastiCacheContainerManager.class);
        proxyManager = mock(ElastiCacheProxyManager.class);
        ec2Service = mock(Ec2Service.class);
        regionResolver = new RegionResolver("us-east-1", "123456789012");
        StorageFactory storageFactory = mock(StorageFactory.class);
        EmulatorConfig config = mock(EmulatorConfig.class);

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.proxyBasePort()).thenReturn(16379);
        when(ecConfig.proxyMaxPort()).thenReturn(16399);
        when(ecConfig.defaultImage()).thenReturn("valkey/valkey:8");
        when(config.hostname()).thenReturn(java.util.Optional.of("localhost"));

        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        when(containerManager.tryStart(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379));
        doNothing().when(proxyManager).startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());

        when(ec2Service.describeSubnets(eq("us-east-1"), any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<String> subnetIds = invocation.getArgument(1, List.class);
                    java.util.Map<String, io.github.hectorvent.floci.services.ec2.model.Subnet> byId =
                            defaultSubnets().stream().collect(
                                    java.util.stream.Collectors.toMap(
                                            io.github.hectorvent.floci.services.ec2.model.Subnet::getSubnetId,
                                            subnet -> subnet));
                    if (subnetIds == null || subnetIds.isEmpty()) {
                        return defaultSubnets();
                    }
                    return subnetIds.stream()
                            .map(byId::get)
                            .filter(java.util.Objects::nonNull)
                            .toList();
                });

        service = new ElastiCacheService(containerManager, proxyManager, ec2Service, regionResolver, storageFactory, config);
    }

    private static List<io.github.hectorvent.floci.services.ec2.model.Subnet> defaultSubnets() {
        return List.of(
                subnet("subnet-default-a", "vpc-default", "us-east-1a"),
                subnet("subnet-default-b", "vpc-default", "us-east-1b"));
    }

    private static io.github.hectorvent.floci.services.ec2.model.Subnet subnet(
            String subnetId, String vpcId, String availabilityZone) {
        io.github.hectorvent.floci.services.ec2.model.Subnet subnet =
                new io.github.hectorvent.floci.services.ec2.model.Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setVpcId(vpcId);
        subnet.setAvailabilityZone(availabilityZone);
        return subnet;
    }

    @Test
    void singleArgAuthMatchesDefaultUserOnly() {
        service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null);

        service.createUser("default-user-id", "default", AuthMode.PASSWORD,
                List.of("default-pass"), "on ~* +@all");
        service.createUser("other-user-id", "other", AuthMode.PASSWORD,
                List.of("other-pass"), "on ~* +@all");

        service.modifyReplicationGroup("grp",
                List.of("default-user-id", "other-user-id"), null);

        // Single-arg AUTH with default user's password should succeed
        assertTrue(service.validatePassword("grp", null, "default-pass"));

        // Single-arg AUTH with other user's password should fail
        assertFalse(service.validatePassword("grp", null, "other-pass"),
                "AUTH <password> must only match the 'default' user per Redis 6+ ACL spec");
    }

    @Test
    void twoArgAuthMatchesNamedUser() {
        service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null);

        service.createUser("other-user-id", "other", AuthMode.PASSWORD,
                List.of("other-pass"), "on ~* +@all");

        service.modifyReplicationGroup("grp", List.of("other-user-id"), null);

        // Two-arg AUTH with correct username + password should succeed
        assertTrue(service.validatePassword("grp", "other", "other-pass"));

        // Two-arg AUTH with wrong username should fail
        assertFalse(service.validatePassword("grp", "wrong", "other-pass"));
    }

    @Test
    void singleArgAuthFallsBackToGroupAuthToken() {
        service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, "group-token");

        // Single-arg AUTH with group auth token should succeed
        assertTrue(service.validatePassword("grp", null, "group-token"));

        // Single-arg AUTH with wrong password should fail
        assertFalse(service.validatePassword("grp", null, "wrong-token"));
    }

    @Test
    void failedProvisioningRollsBackContainerAndReleasesProxyPort() {
        ElastiCacheContainerHandle handle =
                new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379);
        when(containerManager.tryStart(anyString(), anyString())).thenReturn(handle);

        // Proxy startup blows up after the port is reserved and the container is started.
        doThrow(new RuntimeException("proxy boom"))
                .when(proxyManager).startProxy(eq("grp"), any(), anyInt(), anyString(), anyInt(), any());

        // The original failure must propagate to the caller (we clean up, then rethrow).
        assertThrows(RuntimeException.class,
                () -> service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null));

        // Rollback stops by the exact handle, not a fresh by-id lookup.
        verify(proxyManager).stopProxy("grp");
        verify(containerManager).stop(handle);
        verify(containerManager, never()).stopByGroupId(anyString());

        // The reserved proxy port was released: a subsequent successful create reuses the base port
        // instead of skipping to the next one (which is what a leak would cause).
        doNothing().when(proxyManager)
                .startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());
        ReplicationGroup recovered =
                service.createReplicationGroup("grp2", "test", AuthMode.PASSWORD, null);
        assertEquals(16379, recovered.getProxyPort(),
                "Port from the failed create must be released so the next group reuses it");
    }

    @Test
    void failedContainerStartupCleansUpContainerByIdAndReleasesPort() {
        // Models a readiness timeout: start() throws without ever returning a handle.
        doThrow(new RuntimeException("readiness boom"))
                .when(containerManager).tryStart(eq("grp"), anyString());

        assertThrows(RuntimeException.class,
                () -> service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null));

        verify(proxyManager, never()).stopProxy(anyString());
        verify(containerManager).stopByGroupId("grp");

        // The reserved proxy port was still released: a subsequent successful create reuses the base port.
        when(containerManager.tryStart(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "grp2", "localhost", 6379));
        ReplicationGroup recovered =
                service.createReplicationGroup("grp2", "test", AuthMode.PASSWORD, null);
        assertEquals(16379, recovered.getProxyPort(),
                "Port from the failed create must be released so the next group reuses it");
    }

    @Test
    void concurrentCreateForSameGroupIdIsRejectedWhileFirstIsProvisioning() throws InterruptedException {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        when(containerManager.tryStart(anyString(), anyString())).thenAnswer(inv -> {
            startedLatch.countDown();
            assertTrue(releaseLatch.await(5, TimeUnit.SECONDS), "test timed out waiting for release");
            return new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379);
        });

        Thread firstRequest = new Thread(() ->
                service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null));
        firstRequest.start();
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "first request never reached container start");

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null));
        assertEquals("ReplicationGroupAlreadyExistsFault", ex.jsonType());
        verify(containerManager, never()).stop(any());
        verify(containerManager, never()).stopByGroupId(anyString());

        releaseLatch.countDown();
        firstRequest.join(5000);

        assertEquals("grp", service.getReplicationGroup("grp").getReplicationGroupId());
    }

    @Test
    void createWithoutDockerDaemonStillReachesAvailable() {
        // tryStart() returns null when no Docker daemon is reachable. The replication group
        // record is metadata, so the create still succeeds, the group reaches 'available' on the
        // first describe (what SDK/Terraform waiters poll), and no auth proxy is started.
        when(containerManager.tryStart(anyString(), anyString())).thenReturn(null);

        ReplicationGroup group =
                service.createReplicationGroup("no-docker-grp", "test", AuthMode.PASSWORD, null);

        assertEquals(io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus.AVAILABLE,
                group.getStatus());
        assertEquals("localhost", group.getConfigurationEndpoint().address());
        assertEquals(16379, group.getProxyPort());
        verify(proxyManager, never()).startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());

        assertEquals("no-docker-grp",
                service.getReplicationGroup("no-docker-grp").getReplicationGroupId());

        // Delete must not reach for a container that was never created.
        service.deleteReplicationGroup("no-docker-grp");
        verify(containerManager, never()).stop(any());
    }

    // ── Cache Subnet Groups ─────────────────────────────────────────────────────

    @Test
    void cacheSubnetGroupRoundTrip() {
        io.github.hectorvent.floci.services.elasticache.model.CacheSubnetGroup group =
                service.createCacheSubnetGroup("my-subnet-group", "test group",
                        List.of("subnet-default-a", "subnet-default-b"));

        assertEquals("my-subnet-group", group.getCacheSubnetGroupName());
        assertEquals("test group", group.getDescription());
        assertEquals("vpc-default", group.getVpcId());
        assertEquals(List.of("subnet-default-a", "subnet-default-b"), group.getSubnetIds());
        assertEquals("arn:aws:elasticache:us-east-1:123456789012:subnetgroup:my-subnet-group", group.getArn());

        assertEquals(1, service.listCacheSubnetGroups("my-subnet-group").size());
        assertEquals("my-subnet-group",
                service.getCacheSubnetGroup("my-subnet-group").getCacheSubnetGroupName());

        service.deleteCacheSubnetGroup("my-subnet-group");
        AwsException missing = assertThrows(AwsException.class,
                () -> service.getCacheSubnetGroup("my-subnet-group"));
        assertEquals("CacheSubnetGroupNotFoundFault", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void createCacheSubnetGroupRejectsDuplicateName() {
        service.createCacheSubnetGroup("dup-group", "desc", List.of("subnet-default-a"));

        AwsException exception = assertThrows(AwsException.class, () ->
                service.createCacheSubnetGroup("dup-group", "desc", List.of("subnet-default-a")));

        assertEquals("CacheSubnetGroupAlreadyExists", exception.getErrorCode());
    }

    @Test
    void createCacheSubnetGroupRequiresSubnetIds() {
        AwsException exception = assertThrows(AwsException.class, () ->
                service.createCacheSubnetGroup("empty-group", "desc", List.of()));

        assertEquals("MissingParameter", exception.getErrorCode());
    }

    @Test
    void createCacheSubnetGroupRequiresName() {
        AwsException exception = assertThrows(AwsException.class, () ->
                service.createCacheSubnetGroup(null, "desc", List.of("subnet-default-a")));

        assertEquals("MissingParameter", exception.getErrorCode());
    }

    @Test
    void createCacheSubnetGroupRejectsUnknownSubnet() {
        AwsException exception = assertThrows(AwsException.class, () ->
                service.createCacheSubnetGroup("bad-group", "desc", List.of("subnet-does-not-exist")));

        assertEquals("InvalidSubnet", exception.getErrorCode());
    }

    @Test
    void listCacheSubnetGroupsFaultsForMissingName() {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.listCacheSubnetGroups("does-not-exist"));
        assertEquals("CacheSubnetGroupNotFoundFault", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void deleteCacheSubnetGroupFaultsForMissingName() {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.deleteCacheSubnetGroup("does-not-exist"));
        assertEquals("CacheSubnetGroupNotFoundFault", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void modifyCacheSubnetGroupUpdatesSubnetsAndKeepsDescriptionWhenOmitted() {
        service.createCacheSubnetGroup("mod-group", "original desc",
                List.of("subnet-default-a", "subnet-default-b"));

        io.github.hectorvent.floci.services.elasticache.model.CacheSubnetGroup updated =
                service.modifyCacheSubnetGroup("mod-group", null, List.of("subnet-default-a"));

        assertEquals("original desc", updated.getDescription());
        assertEquals(List.of("subnet-default-a"), updated.getSubnetIds());
    }

    @Test
    void createCacheSubnetGroupUsesSuppliedRegionForSubnetLookup() {
        List<String> subnetIds = List.of("subnet-west-a", "subnet-west-b");
        when(ec2Service.describeSubnets(eq("us-west-2"), eq(subnetIds), eq(java.util.Map.of())))
                .thenReturn(List.of(
                        subnet("subnet-west-a", "vpc-west", "us-west-2a"),
                        subnet("subnet-west-b", "vpc-west", "us-west-2b")));

        io.github.hectorvent.floci.services.elasticache.model.CacheSubnetGroup group =
                service.createCacheSubnetGroup("west-subnets", "desc", subnetIds, "us-west-2");

        assertEquals("vpc-west", group.getVpcId());
        assertEquals("arn:aws:elasticache:us-west-2:123456789012:subnetgroup:west-subnets", group.getArn());
        verify(ec2Service).describeSubnets(eq("us-west-2"), eq(subnetIds), eq(java.util.Map.of()));
    }
}
