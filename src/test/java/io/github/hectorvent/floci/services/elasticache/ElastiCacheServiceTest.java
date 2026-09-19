package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupSettings;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheMemcachedContainerManager;
import io.github.hectorvent.floci.services.elasticache.container.ValkeyClusterFormation;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.CacheCluster;
import io.github.hectorvent.floci.services.elasticache.model.CacheClusterStatus;
import io.github.hectorvent.floci.services.elasticache.model.ClusterNode;
import io.github.hectorvent.floci.services.elasticache.model.Endpoint;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.inject.Instance;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElastiCacheServiceTest {

    private ElastiCacheService service;
    private KmsService kmsService;
    private ElastiCacheContainerManager containerManager;
    private ElastiCacheProxyManager proxyManager;
    private EmulatorConfig config;
    private ValkeyClusterFormation clusterFormation;
    private StorageFactory storageFactory;
    private ElastiCacheMemcachedContainerManager memcachedContainerManager;
    private Ec2Service ec2Service;
    private ElastiCacheProvisioningIds provisioningIds;

    @BeforeEach
    void setUp() {
        containerManager = mock(ElastiCacheContainerManager.class);
        proxyManager = mock(ElastiCacheProxyManager.class);
        provisioningIds = new ElastiCacheProvisioningIds();
        storageFactory = mock(StorageFactory.class);
        config = mock(EmulatorConfig.class);

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.proxyBasePort()).thenReturn(16379);
        when(ecConfig.proxyMaxPort()).thenReturn(16399);
        when(ecConfig.defaultImage()).thenReturn("valkey/valkey:8");
        when(ecConfig.defaultMemcachedImage()).thenReturn("memcached:1.6");
        when(config.hostname()).thenReturn(java.util.Optional.of("localhost"));

        // Keyed by file name, as the real StorageFactory is: it hands the second caller of a path
        // the first caller's backend, which is what lets the two services share one id namespace.
        Map<String, AccountAwareStorageBackend<?>> backendsByFile = new ConcurrentHashMap<>();
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv ->
                backendsByFile.computeIfAbsent(inv.getArgument(1),
                        file -> AccountAwareStorageBackend.inMemory("000000000000")));
        memcachedContainerManager = mock(ElastiCacheMemcachedContainerManager.class);
        when(memcachedContainerManager.tryStart(anyString(), anyString())).thenReturn(null);
        when(containerManager.tryStart(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379));
        doNothing().when(proxyManager).startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());
        ec2Service = org.mockito.Mockito.mock(Ec2Service.class);
        kmsService = org.mockito.Mockito.mock(KmsService.class);
        when(kmsService.describeKey(any(), any())).thenThrow(
                new AwsException("NotFoundException", "Key not found", 404));
        clusterFormation = mock(ValkeyClusterFormation.class);
        service = new ElastiCacheService(containerManager, proxyManager, clusterFormation,
                storageFactory, config, ec2Service, new RegionResolver("us-east-1", "000000000000"),
                kmsService, provisioningIds);
    }

    @Test
    void proxyPortExhaustionSurfacesModeledCapacityFault() {
        // A one-port range: the first replication group claims it, so the second must fail with
        // the botocore/smithy-modeled fault for CreateReplicationGroup — wire code
        // InsufficientCacheClusterCapacity at HTTP 400 (Sender) — not the invented
        // InsufficientReplicationGroupCapacity/503 that no SDK can map.
        ElastiCacheContainerManager cm = mock(ElastiCacheContainerManager.class);
        ElastiCacheProxyManager pm = mock(ElastiCacheProxyManager.class);
        StorageFactory sf = mock(StorageFactory.class);
        EmulatorConfig cfg = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig sc = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ec = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(cfg.services()).thenReturn(sc);
        when(sc.elasticache()).thenReturn(ec);
        when(ec.proxyBasePort()).thenReturn(17000);
        when(ec.proxyMaxPort()).thenReturn(17000);
        when(ec.defaultImage()).thenReturn("valkey/valkey:8");
        when(cfg.hostname()).thenReturn(java.util.Optional.of("localhost"));
        when(sf.create(anyString(), anyString(), any())).thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        when(cm.start(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379));
        doNothing().when(pm).startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());
        ElastiCacheService svc = new ElastiCacheService(cm, pm, mock(ValkeyClusterFormation.class),
                sf, cfg, org.mockito.Mockito.mock(Ec2Service.class),
                new RegionResolver("us-east-1", "000000000000"),
                org.mockito.Mockito.mock(KmsService.class), provisioningIds);

        svc.createReplicationGroup("g1", "d", AuthMode.PASSWORD, null, "us-east-1");

        AwsException ex = assertThrows(AwsException.class,
                () -> svc.createReplicationGroup("g2", "d", AuthMode.PASSWORD, null, "us-east-1"));
        assertEquals("InsufficientCacheClusterCapacity", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void singleArgAuthMatchesDefaultUserOnly() {
        service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null, "us-east-1");

        service.createUser("default-user-id", "default", AuthMode.PASSWORD,
                List.of("default-pass"), "on ~* +@all", null);
        service.createUser("other-user-id", "other", AuthMode.PASSWORD,
                List.of("other-pass"), "on ~* +@all", null);

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
        service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null, "us-east-1");

        service.createUser("other-user-id", "other", AuthMode.PASSWORD,
                List.of("other-pass"), "on ~* +@all", null);

        service.modifyReplicationGroup("grp", List.of("other-user-id"), null);

        // Two-arg AUTH with correct username + password should succeed
        assertTrue(service.validatePassword("grp", "other", "other-pass"));

        // Two-arg AUTH with wrong username should fail
        assertFalse(service.validatePassword("grp", "wrong", "other-pass"));
    }

    @Test
    void singleArgAuthFallsBackToGroupAuthToken() {
        service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, "group-token", "us-east-1");

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
                () -> service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null, "us-east-1"));

        // Rollback stops by the exact handle, not a fresh by-id lookup.
        verify(proxyManager).stopProxy("grp");
        verify(containerManager).stop(handle);
        verify(containerManager, never()).stopByGroupId(anyString());

        // The reserved proxy port was released: a subsequent successful create reuses the base port
        // instead of skipping to the next one (which is what a leak would cause).
        doNothing().when(proxyManager)
                .startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());
        ReplicationGroup recovered =
                service.createReplicationGroup("grp2", "test", AuthMode.PASSWORD, null, "us-east-1");
        assertEquals(16379, recovered.getProxyPort(),
                "Port from the failed create must be released so the next group reuses it");
    }

    @Test
    void failedContainerStartupCleansUpContainerByIdAndReleasesPort() {
        // Models a readiness timeout: start() throws without ever returning a handle.
        doThrow(new RuntimeException("readiness boom"))
                .when(containerManager).tryStart(eq("grp"), anyString());

        assertThrows(RuntimeException.class,
                () -> service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null, "us-east-1"));

        verify(proxyManager, never()).stopProxy(anyString());
        verify(containerManager).stopByGroupId("grp");

        // The reserved proxy port was still released: a subsequent successful create reuses the base port.
        when(containerManager.tryStart(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "grp2", "localhost", 6379));
        ReplicationGroup recovered =
                service.createReplicationGroup("grp2", "test", AuthMode.PASSWORD, null, "us-east-1");
        assertEquals(16379, recovered.getProxyPort(),
                "Port from the failed create must be released so the next group reuses it");
    }

    private static ElastiCacheService.CreateReplicationGroupRequest clusterRequest(
            String groupId, Integer numNodeGroups, Integer replicasPerNodeGroup) {
        return new ElastiCacheService.CreateReplicationGroupRequest(groupId, "test",
                AuthMode.NO_AUTH, null, "us-east-1", "valkey", "8.2", "cache.t4g.micro",
                "default.valkey8.cluster.on", null, null, numNodeGroups, replicasPerNodeGroup,
                null, true, null, null, ReplicationGroupSettings.defaults(), Map.of());
    }

    private static ElastiCacheService.CreateReplicationGroupRequest singleNodeRequest(
            String groupId, Integer port) {
        return new ElastiCacheService.CreateReplicationGroupRequest(groupId, "test",
                AuthMode.NO_AUTH, null, "us-east-1", null, null, null, null, null, null,
                null, null, null, null, null, port, ReplicationGroupSettings.defaults(), Map.of());
    }

    // botocore models Port as an optional input on CreateReplicationGroup ("the port number on
    // which each member of the replication group accepts connections"). Ignoring it made every
    // caller that pins a port read back a different one, which Terraform treats as
    // replacement-forcing and reports as permanent drift.
    @Test
    void requestedPortIsHonoredWhenFreeAndInRange() {
        ReplicationGroup group = service.createReplicationGroup(singleNodeRequest("grp", 16390));

        assertEquals(16390, group.getProxyPort(),
                "A free, in-range requested Port must be the port the group reports");
    }

    @Test
    void unpinnedCreateStillAllocatesFromTheBasePort() {
        ReplicationGroup group = service.createReplicationGroup(singleNodeRequest("grp", null));

        assertEquals(16379, group.getProxyPort(),
                "A create with no Port keeps the previous behavior of taking the base port");
    }

    @Test
    void requestedPortAlreadyInUseIsRejected() {
        // floci multiplexes every group's proxy onto one host, so two groups cannot share a port.
        // Substituting a different one would hand back the drift honoring Port exists to remove,
        // and it could only ever hit a caller who did pin a port.
        service.createReplicationGroup(singleNodeRequest("grp1", 16390));

        AwsException thrown = assertThrows(AwsException.class,
                () -> service.createReplicationGroup(singleNodeRequest("grp2", 16390)));

        assertEquals("InvalidParameterValue", thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("16390"));
    }

    @Test
    void requestedPortOutsideTheProxyRangeIsRejected() {
        AwsException thrown = assertThrows(AwsException.class,
                () -> service.createReplicationGroup(singleNodeRequest("grp", 9999)));

        assertEquals("InvalidParameterValue", thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("9999"));
    }

    @Test
    void anUnpinnedCreateStillFallsBackWhenTheBasePortIsTaken() {
        // The fallback survives for callers that named no port: only an explicit one is refused.
        service.createReplicationGroup(singleNodeRequest("grp1", 16379));

        ReplicationGroup second = service.createReplicationGroup(singleNodeRequest("grp2", null));

        assertEquals(16380, second.getProxyPort());
    }

    @Test
    void clusterModeHonorsTheRequestedPortOnTheFirstNode() {
        // The group's Port is reported from the first node's proxy port, so that is the only
        // node whose port a caller can pin; the remaining members take whatever is free.
        stubPerNodeContainers();

        ReplicationGroup group = service.createReplicationGroup(
                new ElastiCacheService.CreateReplicationGroupRequest("grp", "test",
                        AuthMode.NO_AUTH, null, "us-east-1", "valkey", "8.2", "cache.t4g.micro",
                        "default.valkey8.cluster.on", null, null, 2, 1,
                        null, true, null, 16390, ReplicationGroupSettings.defaults(), Map.of()));

        assertEquals(16390, group.getConfigurationEndpoint().port());
        assertEquals(16390, group.getClusterNodes().getFirst().getProxyPort());
        assertEquals(4, group.getClusterNodes().stream()
                        .map(ClusterNode::getProxyPort).distinct().count(),
                "Each node must still own its own proxy port");
    }

    private void stubPerNodeContainers() {
        when(containerManager.start(anyString(), anyString(), any())).thenAnswer(inv ->
                new ElastiCacheContainerHandle("cid-" + inv.getArgument(0, String.class),
                        inv.getArgument(0, String.class), "localhost", 6379));
    }

    @Test
    void clusterModeCreateStartsOneContainerAndProxyPerNode() {
        stubPerNodeContainers();

        ReplicationGroup group = service.createReplicationGroup(clusterRequest("grp", 2, 1));

        assertTrue(group.isClusterEnabled());
        assertEquals(2, group.getNumNodeGroups());
        assertEquals(1, group.getReplicasPerNodeGroup());
        assertEquals(4, group.getClusterNodes().size());

        List<ClusterNode> nodes = group.getClusterNodes();
        assertEquals("grp-0001-001", nodes.get(0).getMemberClusterId());
        assertEquals("grp-0001-002", nodes.get(1).getMemberClusterId());
        assertEquals("grp-0002-001", nodes.get(2).getMemberClusterId());
        assertEquals("grp-0002-002", nodes.get(3).getMemberClusterId());
        assertTrue(nodes.get(0).isPrimary());
        assertFalse(nodes.get(1).isPrimary());
        assertEquals("0-8191", nodes.get(0).getSlots());
        assertEquals("8192-16383", nodes.get(2).getSlots());
        assertEquals(4, nodes.stream().map(ClusterNode::getProxyPort).distinct().count(),
                "Each node must own its own proxy port");
        assertEquals(16379, group.getConfigurationEndpoint().port());

        verify(clusterFormation).form(eq("grp"), any(), eq(2));
        verify(containerManager, times(4)).start(anyString(), anyString(), any());
        verify(proxyManager, times(4)).startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());
    }

    @Test
    void clusterOnParameterGroupEnablesClusterModeForSingleShard() {
        stubPerNodeContainers();

        ReplicationGroup group = service.createReplicationGroup(clusterRequest("grp", 1, 0));

        assertTrue(group.isClusterEnabled());
        assertEquals(1, group.getClusterNodes().size());
        assertEquals("0-16383", group.getClusterNodes().getFirst().getSlots());
    }

    @Test
    void numNodeGroupsBeyondQuotaSurfacesModeledQuotaFault() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createReplicationGroup(clusterRequest("grp", 501, 0)));

        assertEquals("NodeGroupsPerReplicationGroupQuotaExceeded", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        verify(containerManager, never()).start(anyString(), anyString(), any());
    }

    @Test
    void plainCreateStaysClusterModeDisabled() {
        ReplicationGroup group =
                service.createReplicationGroup("grp", "test", AuthMode.NO_AUTH, null, "us-east-1");

        assertFalse(group.isClusterEnabled());
        assertTrue(group.getClusterNodes().isEmpty());
        assertEquals(List.of("grp-001"),
                service.memberCacheClusters(group).stream()
                        .map(ElastiCacheService.MemberCacheCluster::cacheClusterId).toList());
        verify(containerManager, never()).start(anyString(), anyString(), any());
    }

    @Test
    void clusterFormationFailureRollsBackAllNodesAndReleasesPorts() {
        stubPerNodeContainers();
        doThrow(new RuntimeException("formation boom"))
                .when(clusterFormation).form(anyString(), any(), anyInt());

        assertThrows(RuntimeException.class,
                () -> service.createReplicationGroup(clusterRequest("grp", 2, 1)));

        verify(containerManager, times(4)).stop(any());
        verify(proxyManager, never()).startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());

        ReplicationGroup recovered =
                service.createReplicationGroup("grp2", "test", AuthMode.PASSWORD, null, "us-east-1");
        assertEquals(16379, recovered.getProxyPort(),
                "Ports from the failed cluster create must be released for the next group");
    }

    @Test
    void deleteClusterModeGroupStopsEveryNodeAndReleasesPorts() {
        stubPerNodeContainers();
        service.createReplicationGroup(clusterRequest("grp", 2, 0));

        service.deleteReplicationGroup("grp");

        verify(proxyManager).stopProxy("grp-0001-001");
        verify(proxyManager).stopProxy("grp-0002-001");
        verify(containerManager, times(2)).stop(any());

        ReplicationGroup recovered =
                service.createReplicationGroup("grp2", "test", AuthMode.PASSWORD, null, "us-east-1");
        assertEquals(16379, recovered.getProxyPort(),
                "Ports from the deleted cluster group must be released for the next group");
    }

    @Test
    void clusterNodesAnnounceTheConfiguredHostnameAsPreferredEndpoint() {
        stubPerNodeContainers();

        service.createReplicationGroup(clusterRequest("grp", 1, 0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> flags = ArgumentCaptor.forClass(List.class);
        verify(containerManager).start(eq("grp-0001-001"), anyString(), flags.capture());
        List<String> captured = flags.getValue();
        assertEquals("localhost", flagValue(captured, "--cluster-announce-hostname"));
        assertEquals("hostname", flagValue(captured, "--cluster-preferred-endpoint-type"));
        assertEquals("16379", flagValue(captured, "--cluster-announce-port"));
    }

    @Test
    void clusterAnnounceHostnameOverrideIsAnnouncedAndReported() {
        when(config.services().elasticache().clusterAnnounceHostname())
                .thenReturn(java.util.Optional.of("localhost.floci.io"));
        stubPerNodeContainers();

        ReplicationGroup group = service.createReplicationGroup(clusterRequest("grp", 1, 0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> flags = ArgumentCaptor.forClass(List.class);
        verify(containerManager).start(eq("grp-0001-001"), anyString(), flags.capture());
        assertEquals("localhost.floci.io", flagValue(flags.getValue(), "--cluster-announce-hostname"));
        assertEquals("localhost.floci.io", group.getConfigurationEndpoint().address());
    }

    private static String flagValue(List<String> flags, String flag) {
        int index = flags.indexOf(flag);
        assertTrue(index >= 0 && index + 1 < flags.size(), "Missing flag " + flag + " in " + flags);
        return flags.get(index + 1);
    }

    private static StorageFactory sharedStorageFactory() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        Map<String, Object> backends = new ConcurrentHashMap<>();
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv ->
                backends.computeIfAbsent(inv.getArgument(1, String.class),
                        key -> AccountAwareStorageBackend.inMemory("000000000000")));
        return storageFactory;
    }

    private static ElastiCacheService serviceWith(StorageFactory storageFactory,
                                                  ElastiCacheContainerManager containerManager,
                                                  ElastiCacheProxyManager proxyManager,
                                                  ValkeyClusterFormation clusterFormation) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.proxyBasePort()).thenReturn(16379);
        when(ecConfig.proxyMaxPort()).thenReturn(16399);
        when(ecConfig.defaultImage()).thenReturn("valkey/valkey:8");
        when(config.hostname()).thenReturn(java.util.Optional.of("localhost"));
        return new ElastiCacheService(containerManager, proxyManager, clusterFormation,
                storageFactory, config, mock(Ec2Service.class),
                new RegionResolver("us-east-1", "000000000000"), mock(KmsService.class), new ElastiCacheProvisioningIds());
    }

    private static void stubPerNodeContainers(ElastiCacheContainerManager containerManager) {
        when(containerManager.start(anyString(), anyString(), any())).thenAnswer(inv ->
                new ElastiCacheContainerHandle("cid-" + inv.getArgument(0, String.class),
                        inv.getArgument(0, String.class), "localhost", 6379));
        when(containerManager.start(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379));
    }

    @Test
    void restorePersistedRuntimeReprovisionsClusterModeGroups() {
        StorageFactory storageFactory = sharedStorageFactory();
        ElastiCacheContainerManager beforeRestart = mock(ElastiCacheContainerManager.class);
        stubPerNodeContainers(beforeRestart);
        serviceWith(storageFactory, beforeRestart, mock(ElastiCacheProxyManager.class),
                mock(ValkeyClusterFormation.class))
                .createReplicationGroup(clusterRequest("grp", 2, 1));

        ElastiCacheContainerManager restartedContainers = mock(ElastiCacheContainerManager.class);
        stubPerNodeContainers(restartedContainers);
        ElastiCacheProxyManager restartedProxies = mock(ElastiCacheProxyManager.class);
        ValkeyClusterFormation restartedFormation = mock(ValkeyClusterFormation.class);
        ElastiCacheService restarted = serviceWith(storageFactory, restartedContainers,
                restartedProxies, restartedFormation);

        restarted.restorePersistedRuntime().join();

        verify(restartedContainers, times(4)).start(anyString(), anyString(), any());
        verify(restartedFormation).form(eq("grp"), any(), eq(2));
        verify(restartedProxies, times(4)).startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());
        ReplicationGroup restored = restarted.getReplicationGroup("grp");
        assertEquals(ReplicationGroupStatus.AVAILABLE, restored.getStatus());
        assertEquals(16379, restored.getConfigurationEndpoint().port());
        assertEquals("cid-grp-0001-001", restored.getClusterNodes().getFirst().getContainerId());

        ReplicationGroup next =
                restarted.createReplicationGroup("grp2", "test", AuthMode.NO_AUTH, null, "us-east-1");
        assertEquals(16383, next.getProxyPort(),
                "Restored node ports must be reserved again so new groups cannot take them");
    }

    @Test
    void restoreFailureReportsCreateFailedAndReleasesPorts() {
        StorageFactory storageFactory = sharedStorageFactory();
        ElastiCacheContainerManager beforeRestart = mock(ElastiCacheContainerManager.class);
        stubPerNodeContainers(beforeRestart);
        serviceWith(storageFactory, beforeRestart, mock(ElastiCacheProxyManager.class),
                mock(ValkeyClusterFormation.class))
                .createReplicationGroup(clusterRequest("grp", 2, 0));

        ElastiCacheContainerManager restartedContainers = mock(ElastiCacheContainerManager.class);
        when(restartedContainers.start(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("docker down"));
        when(restartedContainers.start(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379));
        ElastiCacheProxyManager restartedProxies = mock(ElastiCacheProxyManager.class);
        ElastiCacheService restarted = serviceWith(storageFactory, restartedContainers,
                restartedProxies, mock(ValkeyClusterFormation.class));

        restarted.restorePersistedRuntime().join();

        ReplicationGroup failed = restarted.getReplicationGroup("grp");
        assertEquals(ReplicationGroupStatus.CREATE_FAILED, failed.getStatus());
        assertNull(failed.getConfigurationEndpoint(),
                "A group whose data plane is gone must not advertise an endpoint");
        verify(restartedProxies, never()).startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());

        ReplicationGroup next =
                restarted.createReplicationGroup("grp2", "test", AuthMode.NO_AUTH, null, "us-east-1");
        assertEquals(16379, next.getProxyPort(),
                "Ports from the failed restore must be released for the next group");
    }

    @Test
    void restoreRunsInBackgroundAndReportsCreatingUntilDone() throws InterruptedException {
        StorageFactory storageFactory = sharedStorageFactory();
        ElastiCacheContainerManager beforeRestart = mock(ElastiCacheContainerManager.class);
        stubPerNodeContainers(beforeRestart);
        serviceWith(storageFactory, beforeRestart, mock(ElastiCacheProxyManager.class),
                mock(ValkeyClusterFormation.class))
                .createReplicationGroup(clusterRequest("grp", 2, 0));

        ElastiCacheContainerManager restartedContainers = mock(ElastiCacheContainerManager.class);
        CountDownLatch restoreStarted = new CountDownLatch(1);
        CountDownLatch releaseRestore = new CountDownLatch(1);
        when(restartedContainers.start(anyString(), anyString(), any())).thenAnswer(inv -> {
            restoreStarted.countDown();
            releaseRestore.await(5, TimeUnit.SECONDS);
            return new ElastiCacheContainerHandle("cid-" + inv.getArgument(0, String.class),
                    inv.getArgument(0, String.class), "localhost", 6379);
        });
        ElastiCacheService restarted = serviceWith(storageFactory, restartedContainers,
                mock(ElastiCacheProxyManager.class), mock(ValkeyClusterFormation.class));

        CompletableFuture<Void> restore = restarted.restorePersistedRuntime();

        assertTrue(restoreStarted.await(5, TimeUnit.SECONDS));
        assertFalse(restore.isDone(), "Restore must not block the caller while the data plane comes up");
        assertEquals(ReplicationGroupStatus.CREATING, restarted.getReplicationGroup("grp").getStatus(),
                "Groups must report creating while their restore is in flight");
        releaseRestore.countDown();
        restore.join();
        assertEquals(ReplicationGroupStatus.AVAILABLE, restarted.getReplicationGroup("grp").getStatus());
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
                service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null, "us-east-1"));
        firstRequest.start();
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "first request never reached container start");

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null, "us-east-1"));
        assertEquals("ReplicationGroupAlreadyExistsFault", ex.jsonType());
        verify(containerManager, never()).stop(any());
        verify(containerManager, never()).stopByGroupId(anyString());

        releaseLatch.countDown();
        firstRequest.join(5000);

        assertEquals("grp", service.getReplicationGroup("grp").getReplicationGroupId());
    }

    private static final String KEY_ARN = "arn:aws:kms:us-east-1:000000000000:key/k1";

    private KmsKey knownKey(String... forms) {
        KmsKey key = new KmsKey();
        key.setKeyId("k1");
        key.setArn(KEY_ARN);
        key.setEnabled(true);
        key.setKeyState("Enabled");
        for (String form : forms) {
            org.mockito.Mockito.doReturn(key).when(kmsService).describeKey(form, "us-east-1");
        }
        return key;
    }

    @Test
    void createReplicationGroupStoresEncryptionSnapshotSettingsAndTags() {
        knownKey("alias/cache");
        ReplicationGroup group = service.createReplicationGroup("g1", "d", AuthMode.NO_AUTH, null, "us-east-1",
                new ReplicationGroupSettings(true, "alias/cache", 7, "06:30-07:30"),
                Map.of("Name", "g1", "env", "tst"));

        ReplicationGroup stored = service.getReplicationGroup("g1");
        assertTrue(stored.isAtRestEncryptionEnabled());
        assertEquals(KEY_ARN, stored.getKmsKeyId());
        assertEquals(7, stored.getSnapshotRetentionLimit());
        assertEquals("06:30-07:30", stored.getSnapshotWindow());
        assertEquals(Map.of("Name", "g1", "env", "tst"), stored.getTags());
        assertEquals("arn:aws:elasticache:us-east-1:000000000000:replicationgroup:g1", group.getArn());
    }

    @Test
    void createReplicationGroupWithoutSettingsKeepsAwsDefaults() {
        service.createReplicationGroup("g1", "d", AuthMode.NO_AUTH, null, "us-east-1");
        ReplicationGroup stored = service.getReplicationGroup("g1");
        assertFalse(stored.isAtRestEncryptionEnabled());
        assertNull(stored.getKmsKeyId());
        assertEquals(0, stored.getSnapshotRetentionLimit());
        assertTrue(stored.getTags().isEmpty());
        assertEquals(ReplicationGroupSettings.DEFAULT_SNAPSHOT_WINDOW, stored.getSnapshotWindow());
    }

    @Test
    void createReplicationGroupRejectsAKeyItCannotUseBeforeStartingAContainer() {
        AwsException missing = assertThrows(AwsException.class, () -> service.createReplicationGroup(
                "g1", "d", AuthMode.NO_AUTH, null, "us-east-1",
                new ReplicationGroupSettings(true, "alias/does-not-exist", null, null), Map.of()));
        assertEquals("InvalidParameterValue", missing.getErrorCode());
        assertEquals("KMS key does not exist with key id: alias/does-not-exist", missing.getMessage());
        assertThrows(AwsException.class, () -> service.getReplicationGroup("g1"));
        org.mockito.Mockito.verify(containerManager, org.mockito.Mockito.never()).start(anyString(), anyString());

        AwsException combination = assertThrows(AwsException.class, () -> service.createReplicationGroup(
                "g1", "d", AuthMode.NO_AUTH, null, "us-east-1",
                new ReplicationGroupSettings(false, KEY_ARN, null, null), Map.of()));
        assertEquals("InvalidParameterCombination", combination.getErrorCode());
        assertEquals("Please enable encryption at rest to use Customer Managed CMK", combination.getMessage());
        // leaving AtRestEncryptionEnabled out is false on a live account, refused the same way
        AwsException omitted = assertThrows(AwsException.class, () -> service.createReplicationGroup(
                "g1", "d", AuthMode.NO_AUTH, null, "us-east-1",
                new ReplicationGroupSettings(null, KEY_ARN, null, null), Map.of()));
        assertEquals("InvalidParameterCombination", omitted.getErrorCode());

        AwsException retention = assertThrows(AwsException.class, () -> service.createReplicationGroup(
                "g1", "d", AuthMode.NO_AUTH, null, "us-east-1",
                new ReplicationGroupSettings(null, null, 36, null), Map.of()));
        assertEquals("Invalid snapshot retention limit: 36. Retention limit must be between 0 and 35.", retention.getMessage());
        AwsException window = assertThrows(AwsException.class, () -> service.createReplicationGroup(
                "g1", "d", AuthMode.NO_AUTH, null, "us-east-1",
                new ReplicationGroupSettings(null, null, null, "25:00-26:00"), Map.of()));
        assertEquals("Invalid backup window format. Should be specified as a range hh24:mi-hh24:mi (24H Clock UTC). Example: 03:15-08:15", window.getMessage());
        AwsException shortWindow = assertThrows(AwsException.class, () -> service.createReplicationGroup(
                "g1", "d", AuthMode.NO_AUTH, null, "us-east-1",
                new ReplicationGroupSettings(null, null, null, "05:00-05:30"), Map.of()));
        assertEquals("Snapshot window must be at least 60 minutes.", shortWindow.getMessage());
        // equal start and end is an empty window, not a full day
        AwsException emptyWindow = assertThrows(AwsException.class, () -> service.createReplicationGroup(
                "g1", "d", AuthMode.NO_AUTH, null, "us-east-1",
                new ReplicationGroupSettings(null, null, null, "05:00-05:00"), Map.of()));
        assertEquals("Snapshot window must be at least 60 minutes.", emptyWindow.getMessage());
        // while a window wrapping midnight is measured across it
        service.createReplicationGroup("wrap", "d", AuthMode.NO_AUTH, null, "us-east-1",
                new ReplicationGroupSettings(null, null, null, "23:30-00:30"), Map.of());
        assertEquals("23:30-00:30", service.getReplicationGroup("wrap").getSnapshotWindow());
    }

    @Test
    void modifyReplicationGroupChangesSnapshotSettingsAndKeepsEncryption() {
        knownKey(KEY_ARN);
        service.createReplicationGroup("g1", "d", AuthMode.NO_AUTH, null, "us-east-1",
                new ReplicationGroupSettings(true, KEY_ARN, 7, "06:30-07:30"), Map.of());

        service.modifyReplicationGroup("g1", null, null, new ReplicationGroupSettings(null, null, 3, "01:00-02:00"));

        ReplicationGroup stored = service.getReplicationGroup("g1");
        assertEquals(3, stored.getSnapshotRetentionLimit());
        assertEquals("01:00-02:00", stored.getSnapshotWindow());
        assertTrue(stored.isAtRestEncryptionEnabled());
        assertEquals(KEY_ARN, stored.getKmsKeyId());

        assertThrows(AwsException.class, () -> service.modifyReplicationGroup("g1", null, null,
                new ReplicationGroupSettings(null, null, null, "25:00-26:00")));
        assertEquals("01:00-02:00", service.getReplicationGroup("g1").getSnapshotWindow());

        // a refusal later in the same request must not leave the earlier part applied: the store
        // hands out its own object, so settings applied before the user check would stay visible
        AwsException unknownUser = assertThrows(AwsException.class, () -> service.modifyReplicationGroup(
                "g1", List.of("no-such-user"), null, new ReplicationGroupSettings(null, null, 9, "03:00-04:00")));
        assertEquals("UserNotFoundFault", unknownUser.getErrorCode());
        assertEquals(3, service.getReplicationGroup("g1").getSnapshotRetentionLimit());
        assertEquals("01:00-02:00", service.getReplicationGroup("g1").getSnapshotWindow());
    }

    @Test
    void modifyReplicationGroupCannotWriteAGroupBackAfterDelete() throws Exception {
        // modify has read the group, delete removes it, modify writes its copy back — the store is
        // held inside modify's put so the delete can be run in exactly that window
        PausingStorageBackend<ReplicationGroup> pausing = new PausingStorageBackend<>(new InMemoryStorage<>());
        StorageFactory factory = org.mockito.Mockito.mock(StorageFactory.class);
        when(factory.create(anyString(), eq("elasticache-groups.json"), any()))
                .thenAnswer(inv -> new AccountAwareStorageBackend<>(pausing, null, "000000000000"));
        when(factory.create(anyString(), org.mockito.ArgumentMatchers.argThat(f -> !"elasticache-groups.json".equals(f)), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        ElastiCacheService svc = new ElastiCacheService(containerManager, proxyManager, clusterFormation,
                factory, config, org.mockito.Mockito.mock(Ec2Service.class),
                new RegionResolver("us-east-1", "000000000000"), kmsService, provisioningIds);
        svc.createReplicationGroup("g1", "d", AuthMode.NO_AUTH, null, "us-east-1");

        pausing.pauseOn(PausingStorageBackend.Call.PUT, "g1");
        java.util.concurrent.atomic.AtomicReference<Throwable> modifyOutcome = new java.util.concurrent.atomic.AtomicReference<>();
        Thread modify = new Thread(() -> {
            try {
                svc.modifyReplicationGroup("g1", null, null, new ReplicationGroupSettings(null, null, 3, null));
            } catch (Throwable t) {
                modifyOutcome.set(t);
            }
        });
        modify.start();
        pausing.awaitReached();

        Thread delete = new Thread(() -> svc.deleteReplicationGroup("g1"));
        delete.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (delete.getState() != Thread.State.BLOCKED && delete.getState() != Thread.State.TERMINATED) {
            assertTrue(System.nanoTime() < deadline, "delete neither ran nor queued");
            Thread.onSpinWait();
        }
        pausing.release();
        modify.join(5000);
        delete.join(5000);

        assertNull(modifyOutcome.get(), "modify completed before the delete");
        assertThrows(AwsException.class, () -> svc.getReplicationGroup("g1"),
                "the deleted group must not come back from the modify");
    }

    private static ElastiCacheService.CreateReplicationGroupRequest requestWithParameterGroup(
            String groupId, String parameterGroupName) {
        return new ElastiCacheService.CreateReplicationGroupRequest(groupId, "test",
                AuthMode.NO_AUTH, null, "us-east-1", null, null, null,
                parameterGroupName, null, null, null, null,
                null, null, null, null, ReplicationGroupSettings.defaults(), Map.of());
    }

    @Test
    void createReferencingAnUnknownParameterGroupIsRefusedBeforeProvisioning() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createReplicationGroup(requestWithParameterGroup("grp", "absent-pg")));

        assertEquals("CacheParameterGroupNotFound", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
        verify(containerManager, never()).start(anyString(), anyString());
        verify(containerManager, never()).start(anyString(), anyString(), any());
        assertThrows(AwsException.class, () -> service.getReplicationGroup("grp"));
    }

    @Test
    void aParameterGroupStillReferencedByAReplicationGroupCannotBeDeleted() {
        service.createCacheParameterGroup("custom-pg", "redis7", "in use", Map.of());
        service.createReplicationGroup(requestWithParameterGroup("grp", "custom-pg"));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.deleteCacheParameterGroup("custom-pg"));
        assertEquals("InvalidCacheParameterGroupState", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(service.findParameterGroup("custom-pg").isPresent(),
                "a refused delete must leave the group in place");

        service.deleteReplicationGroup("grp");
        service.deleteCacheParameterGroup("custom-pg");
        assertTrue(service.findParameterGroup("custom-pg").isEmpty());
    }

    @Test
    void aParameterGroupNamedByAProvisioningCreateCannotBeDeleted() throws InterruptedException {
        service.createCacheParameterGroup("custom-pg", "redis7", "in use", Map.of());
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        when(containerManager.tryStart(anyString(), anyString())).thenAnswer(inv -> {
            startedLatch.countDown();
            assertTrue(releaseLatch.await(5, TimeUnit.SECONDS), "test timed out waiting for release");
            return new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379);
        });

        Thread create = new Thread(() ->
                service.createReplicationGroup(requestWithParameterGroup("grp", "custom-pg")));
        create.start();
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "create never reached container start");

        // The replication group is not stored yet: only the reservation can refuse this.
        AwsException ex = assertThrows(AwsException.class,
                () -> service.deleteCacheParameterGroup("custom-pg"));
        assertEquals("InvalidCacheParameterGroupState", ex.getErrorCode());
        assertTrue(service.findParameterGroup("custom-pg").isPresent());

        releaseLatch.countDown();
        create.join(5000);

        assertEquals("custom-pg", service.getReplicationGroup("grp").getCacheParameterGroupName());
        assertEquals("InvalidCacheParameterGroupState",
                assertThrows(AwsException.class, () -> service.deleteCacheParameterGroup("custom-pg"))
                        .getErrorCode());

        service.deleteReplicationGroup("grp");
        service.deleteCacheParameterGroup("custom-pg");
        assertTrue(service.findParameterGroup("custom-pg").isEmpty());
    }

    @Test
    void aClaimHeldByOneAccountDoesNotBlockAnotherAccountsDeleteOfItsOwnSameNamedGroup()
            throws InterruptedException {
        // Storage prefixes keys with the account of the calling thread; the default account
        // applies to a thread that never registered one.
        ConcurrentHashMap<Thread, String> accountByThread = new ConcurrentHashMap<>();
        RequestContext requestContext = mock(RequestContext.class);
        when(requestContext.getAccountId()).thenAnswer(inv -> accountByThread.get(Thread.currentThread()));
        @SuppressWarnings("unchecked")
        Instance<RequestContext> requestContextInstance = mock(Instance.class);
        when(requestContextInstance.get()).thenReturn(requestContext);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any())).thenAnswer(inv ->
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), requestContextInstance, "000000000000"));
        ElastiCacheService svc = new ElastiCacheService(containerManager, proxyManager, clusterFormation,
                factory, config, mock(Ec2Service.class),
                new RegionResolver("us-east-1", "000000000000"), kmsService, provisioningIds);

        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        when(containerManager.tryStart(anyString(), anyString())).thenAnswer(inv -> {
            startedLatch.countDown();
            assertTrue(releaseLatch.await(5, TimeUnit.SECONDS), "test timed out waiting for release");
            return new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379);
        });

        // Account A: owns shared-pg and is provisioning a replication group that names it.
        Thread accountA = new Thread(() -> {
            accountByThread.put(Thread.currentThread(), "111111111111");
            svc.createCacheParameterGroup("shared-pg", "redis7", "account A", Map.of());
            svc.createReplicationGroup(requestWithParameterGroup("grp", "shared-pg"));
        });
        accountA.start();
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "create never reached container start");

        // Account B: owns an unrelated shared-pg of its own; account A's claim must not hold it.
        accountByThread.put(Thread.currentThread(), "222222222222");
        svc.createCacheParameterGroup("shared-pg", "redis7", "account B", Map.of());
        svc.deleteCacheParameterGroup("shared-pg");
        assertTrue(svc.findParameterGroup("shared-pg").isEmpty());

        // Account A's own group is still held by its in-flight create.
        accountByThread.put(Thread.currentThread(), "111111111111");
        assertEquals("InvalidCacheParameterGroupState",
                assertThrows(AwsException.class, () -> svc.deleteCacheParameterGroup("shared-pg"))
                        .getErrorCode());

        releaseLatch.countDown();
        accountA.join(5000);
        assertEquals("shared-pg", svc.getReplicationGroup("grp").getCacheParameterGroupName());
    }

    @Test
    void aFailedCreateReleasesItsClaimOnTheParameterGroup() {
        service.createCacheParameterGroup("custom-pg", "redis7", "in use", Map.of());
        when(containerManager.tryStart(anyString(), anyString()))
                .thenThrow(new RuntimeException("docker is down"));

        assertThrows(RuntimeException.class,
                () -> service.createReplicationGroup(requestWithParameterGroup("grp", "custom-pg")));

        service.deleteCacheParameterGroup("custom-pg");
        assertTrue(service.findParameterGroup("custom-pg").isEmpty());
    }

    @Test
    void aCreateThatFailsBeforeProvisioningStillReleasesItsClaimOnTheParameterGroup() {
        service.createCacheParameterGroup("custom-pg", "redis7", "in use", Map.of());
        service.createReplicationGroup("grp", "test", AuthMode.NO_AUTH, null, "us-east-1");

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createReplicationGroup(requestWithParameterGroup("grp", "custom-pg")));
        assertEquals("ReplicationGroupAlreadyExistsFault", ex.jsonType());

        service.deleteCacheParameterGroup("custom-pg");
        assertTrue(service.findParameterGroup("custom-pg").isEmpty());
    }

    // ── CreateCacheCluster: single-node redis/valkey ──────────────────────────

    private static ElastiCacheService.CreateCacheClusterRequest cacheClusterRequest(
            String clusterId, String engine, Integer numCacheNodes) {
        return cacheClusterRequest(clusterId, engine, numCacheNodes, AuthMode.NO_AUTH, null, null);
    }

    private static ElastiCacheService.CreateCacheClusterRequest cacheClusterRequest(
            String clusterId, String engine, Integer numCacheNodes, AuthMode authMode,
            String authToken, String parameterGroupName) {
        return new ElastiCacheService.CreateCacheClusterRequest(clusterId, engine, null, null,
                numCacheNodes, null, authMode, authToken, parameterGroupName, null,
                null, null, null, null, null, null, null, null, "us-east-1", Map.of());
    }

    @Test
    void singleNodeRedisClusterIsBackedByAValkeyContainerBehindAProxy() {
        // The point of the path: terraform's aws_elasticache_cluster with engine "redis" sends
        // this call, and the endpoint it reads back has to answer. That means a container and a
        // proxy on the port the describe reports, not a metadata-only record.
        CacheCluster cluster = service.createCacheCluster(cacheClusterRequest("tf-redis", "redis", 1));

        assertEquals("redis", cluster.getEngine());
        assertEquals("7.1", cluster.getEngineVersion());
        assertEquals("cache.t4g.micro", cluster.getCacheNodeType());
        assertEquals(1, cluster.getNumCacheNodes());
        assertEquals(CacheClusterStatus.AVAILABLE, cluster.getCacheClusterStatus());
        assertEquals("localhost", cluster.getConfigurationEndpoint().address());
        assertEquals(16379, cluster.getConfigurationEndpoint().port());
        assertEquals("arn:aws:elasticache:us-east-1:000000000000:cluster:tf-redis", cluster.getArn());

        verify(containerManager).tryStart(eq("tf-redis"), eq("valkey/valkey:8"));
        verify(proxyManager).startProxy(eq("tf-redis"), eq(AuthMode.NO_AUTH), eq(16379),
                eq("localhost"), eq(6379), any());

        // read back through a separate call, not the create's own return value
        assertEquals("tf-redis", service.findCacheClusters("tf-redis").getFirst().getCacheClusterId());
    }

    @Test
    void valkeyCacheClusterTakesTheValkeyEngineVersionDefault() {
        assertEquals("8.1", service.createCacheCluster(cacheClusterRequest("tf-valkey", "valkey", null))
                .getEngineVersion());
    }

    @Test
    void redisCacheClusterWithMoreThanOneNodeIsRefusedAsOnAws() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createCacheCluster(cacheClusterRequest("too-big", "redis", 2)));

        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("NumCacheNodes should be 1 if engine is redis", ex.getMessage());
        verify(containerManager, never()).tryStart(eq("too-big"), anyString());
        assertTrue(service.findCacheClusters("too-big").isEmpty());
    }

    @Test
    void duplicateCacheClusterIdIsRefused() {
        service.createCacheCluster(cacheClusterRequest("dupe", "redis", 1));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createCacheCluster(cacheClusterRequest("dupe", "redis", 1)));
        // botocore elasticache/2015-02-02 codes this CacheClusterAlreadyExists: the Fault suffix
        // is the shape name, not the wire code, and an SDK matches on the code.
        assertEquals("CacheClusterAlreadyExists", ex.getErrorCode());
    }

    @Test
    void deletingACacheClusterStopsItsProxyAndContainerAndFreesThePort() {
        service.createCacheCluster(cacheClusterRequest("cc", "redis", 1));

        service.deleteCacheCluster("cc");

        verify(proxyManager).stopProxy("cc");
        verify(containerManager).stop(any());
        assertTrue(service.findCacheClusters("cc").isEmpty());
        assertEquals("CacheClusterNotFound",
                assertThrows(AwsException.class, () -> service.deleteCacheCluster("cc")).getErrorCode());

        // the freed proxy port goes to the next cluster rather than being leaked
        assertEquals(16379, service.createCacheCluster(cacheClusterRequest("cc2", "redis", 1))
                .getConfigurationEndpoint().port());
    }

    @Test
    void aCacheClusterAndAReplicationGroupNeverShareAProxyPort() {
        service.createReplicationGroup("grp", "d", AuthMode.NO_AUTH, null, "us-east-1");

        assertEquals(16380, service.createCacheCluster(cacheClusterRequest("cc", "redis", 1))
                .getConfigurationEndpoint().port());
    }

    @Test
    void aCacheClusterHoldsItsParameterGroupAgainstDeletion() {
        service.createCacheParameterGroup("cc-pg", "redis7", "in use", Map.of());
        service.createCacheCluster(
                cacheClusterRequest("cc", "redis", 1, AuthMode.NO_AUTH, null, "cc-pg"));

        assertEquals("InvalidCacheParameterGroupState",
                assertThrows(AwsException.class, () -> service.deleteCacheParameterGroup("cc-pg"))
                        .getErrorCode());
    }

    @Test
    void anAuthTokenOnACacheClusterIsValidatedAgainstThatClusterAlone() {
        service.createCacheCluster(
                cacheClusterRequest("auth-cc", "redis", 1, AuthMode.PASSWORD, "s3cret-token", null));

        assertTrue(service.validateCacheClusterPassword("auth-cc", null, "s3cret-token"));
        assertFalse(service.validateCacheClusterPassword("auth-cc", null, "wrong"));
        assertFalse(service.validateCacheClusterPassword("other-cc", null, "s3cret-token"));
    }

    @Test
    void aCacheClusterCannotTakeTheIdOfALiveReplicationGroup() {
        // Not a cosmetic clash. Both name their container valkey-<id> and register their proxy
        // under the id, so letting this through would have ElastiCacheContainerManager.start
        // removeIfExists the group's running container and the proxy registry overwrite its
        // entry, leaving a listener bound that nothing can stop.
        service.createReplicationGroup("shared", "d", AuthMode.NO_AUTH, null, "us-east-1");

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createCacheCluster(cacheClusterRequest("shared", "redis", 1)));

        assertEquals("CacheClusterAlreadyExists", ex.getErrorCode());
        // the live group kept its container and its proxy: nothing was started or removed for the
        // refused request
        verify(containerManager, times(1)).tryStart(eq("shared"), anyString());
        verify(containerManager, never()).stopByGroupId("shared");
        verify(proxyManager, times(1)).startProxy(eq("shared"), any(), anyInt(), anyString(), anyInt(), any());
        assertEquals("shared", service.getReplicationGroup("shared").getReplicationGroupId());
    }

    @Test
    void aReplicationGroupCannotTakeTheIdOfALiveCacheCluster() {
        service.createCacheCluster(cacheClusterRequest("shared", "redis", 1));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createReplicationGroup("shared", "d", AuthMode.NO_AUTH, null, "us-east-1"));

        assertEquals("ReplicationGroupAlreadyExistsFault", ex.getErrorCode());
        verify(containerManager, times(1)).tryStart(eq("shared"), anyString());
        verify(containerManager, never()).stopByGroupId("shared");
        assertEquals("shared", service.findCacheClusters("shared").getFirst().getCacheClusterId());
    }

    @Test
    void twoConcurrentCreatesOfOneIdCannotBothProvisionIt() throws Exception {
        // The stored-record check alone cannot separate them: neither create has stored anything
        // while the other is inside tryStart, so both would pass it, and the loser's rollback
        // would stopByGroupId the winner's container out from under it.
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        when(containerManager.tryStart(eq("raced"), anyString())).thenAnswer(inv -> {
            startedLatch.countDown();
            assertTrue(releaseLatch.await(5, TimeUnit.SECONDS), "test timed out waiting for release");
            return new ElastiCacheContainerHandle("cid", "raced", "localhost", 6379);
        });

        Thread winner = new Thread(() -> service.createCacheCluster(cacheClusterRequest("raced", "redis", 1)));
        winner.start();
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "create never reached container start");

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createCacheCluster(cacheClusterRequest("raced", "redis", 1)));
        assertEquals("CacheClusterAlreadyExists", ex.getErrorCode());
        // the loser must not have reached for the container the winner is still starting
        verify(containerManager, never()).stopByGroupId("raced");

        releaseLatch.countDown();
        winner.join(5000);
        assertEquals("raced", service.findCacheClusters("raced").getFirst().getCacheClusterId());
        verify(containerManager, times(1)).tryStart(eq("raced"), anyString());
    }

    @Test
    void aReplicationGroupCreateIsBlockedByAnInFlightCacheClusterCreateOfThatId() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        when(containerManager.tryStart(eq("raced"), anyString())).thenAnswer(inv -> {
            startedLatch.countDown();
            assertTrue(releaseLatch.await(5, TimeUnit.SECONDS), "test timed out waiting for release");
            return new ElastiCacheContainerHandle("cid", "raced", "localhost", 6379);
        });

        Thread cacheCluster = new Thread(() -> service.createCacheCluster(cacheClusterRequest("raced", "redis", 1)));
        cacheCluster.start();
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "create never reached container start");

        assertEquals("ReplicationGroupAlreadyExistsFault",
                assertThrows(AwsException.class, () -> service.createReplicationGroup(
                        "raced", "d", AuthMode.NO_AUTH, null, "us-east-1")).getErrorCode());
        verify(containerManager, never()).stopByGroupId("raced");

        releaseLatch.countDown();
        cacheCluster.join(5000);
    }

    @Test
    void theOptionalMembersARequestCarriesAreStoredAndTheRestDefaulted() {
        // Every one of these is an optional aws_elasticache_cluster argument. Dropping any of them
        // reads back as unset on the next plan, which is a diff terraform can never settle.
        CacheCluster cluster = service.createCacheCluster(new ElastiCacheService.CreateCacheClusterRequest(
                "settings-cc", "redis", null, null, 1, null, AuthMode.NO_AUTH, null, null, null,
                5, "03:00-05:00", "Tue:04:00-Tue:05:00", "us-east-1b", List.of("sg-123"),
                "ipv4", "ipv4", true, "us-east-1", Map.of()));

        assertEquals(5, cluster.getSnapshotRetentionLimit());
        assertEquals("03:00-05:00", cluster.getSnapshotWindow());
        assertEquals("tue:04:00-tue:05:00", cluster.getPreferredMaintenanceWindow());
        assertEquals("us-east-1b", cluster.getPreferredAvailabilityZone());
        assertEquals(List.of("sg-123"), cluster.getSecurityGroupIds());
        assertTrue(cluster.isAtRestEncryptionEnabled());

        // and a request that carries none of them still reads back concrete values, not zeroes
        CacheCluster bare = service.createCacheCluster(cacheClusterRequest("bare-cc", "redis", 1));
        assertEquals(0, bare.getSnapshotRetentionLimit());
        assertEquals("00:00-01:00", bare.getSnapshotWindow());
        assertEquals("mon:00:00-mon:03:00", bare.getPreferredMaintenanceWindow());
        assertEquals("us-east-1a", bare.getPreferredAvailabilityZone());
        assertEquals("ipv4", bare.getNetworkType());
        assertEquals("ipv4", bare.getIpDiscovery());
        assertFalse(bare.isAtRestEncryptionEnabled());
    }

    @Test
    void theSnapshotMembersTakeTheReplicationGroupsChecks() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createCacheCluster(new ElastiCacheService.CreateCacheClusterRequest(
                        "bad-cc", "redis", null, null, 1, null, AuthMode.NO_AUTH, null, null, null,
                        99, null, null, null, null, null, null, null, "us-east-1", Map.of())));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Retention limit must be between 0 and 35"), ex.getMessage());

        assertEquals("InvalidParameterValue",
                assertThrows(AwsException.class,
                        () -> service.createCacheCluster(new ElastiCacheService.CreateCacheClusterRequest(
                                "bad-cc", "redis", null, null, 1, null, AuthMode.NO_AUTH, null, null, null,
                                null, null, "notaday:04:00-notaday:05:00", null, null, null, null, null,
                                "us-east-1", Map.of()))).getErrorCode());

        // a refused request provisions nothing
        verify(containerManager, never()).tryStart(eq("bad-cc"), anyString());
        assertTrue(service.findCacheClusters("bad-cc").isEmpty());
    }

    @Test
    void aStandaloneClusterIsListedAsAnExplorerResource() {
        service.createCacheCluster(cacheClusterRequest("explorer-cc", "redis", 1));

        assertTrue(service.getResources().stream().anyMatch(r ->
                        "arn:aws:elasticache:us-east-1:000000000000:cluster:explorer-cc".equals(r.arn())),
                "the standalone cluster must appear in the resource explorer alongside groups");
    }

    /** A second service over the same stores: what a restart leaves behind, minus the runtime. */
    private ElastiCacheService serviceAfterRestart() {
        return new ElastiCacheService(containerManager, proxyManager, clusterFormation,
                storageFactory, config, ec2Service, new RegionResolver("us-east-1", "000000000000"),
                kmsService, provisioningIds);
    }

    private ElastiCacheMemcachedService memcachedService() {
        return new ElastiCacheMemcachedService(memcachedContainerManager, storageFactory, config, provisioningIds);
    }

    @Test
    void aPersistedClusterKeepsItsPortAcrossARestart() {
        // Before this, nothing re-added the port to usedPorts on startup: the restored cluster
        // went on advertising 16379 while the next create was handed the same 16379, and the
        // first delete then freed the second cluster's reservation.
        assertEquals(16379, service.createCacheCluster(cacheClusterRequest("persisted", "redis", 1))
                .getConfigurationEndpoint().port());

        ElastiCacheService restarted = serviceAfterRestart();
        restarted.restorePersistedRuntime().join();

        assertEquals(16380, restarted.createCacheCluster(cacheClusterRequest("after", "redis", 1))
                .getConfigurationEndpoint().port());
        assertEquals(16379, restarted.findCacheClusters("persisted").getFirst()
                .getConfigurationEndpoint().port());
    }

    @Test
    void deletingARestoredClusterFreesThePortItActuallyHolds() {
        service.createCacheCluster(cacheClusterRequest("persisted", "redis", 1));
        ElastiCacheService restarted = serviceAfterRestart();
        restarted.restorePersistedRuntime().join();
        restarted.createCacheCluster(cacheClusterRequest("after", "redis", 1));

        restarted.deleteCacheCluster("persisted");

        // 16379 is free again because that record owned it; 16380 is still the other cluster's
        assertEquals(16379, restarted.createCacheCluster(cacheClusterRequest("third", "redis", 1))
                .getConfigurationEndpoint().port());
        assertEquals(16380, restarted.findCacheClusters("after").getFirst()
                .getConfigurationEndpoint().port());
    }

    @Test
    void deletingARecordThatNeverHeldItsPortDoesNotFreeTheHoldersPort() {
        // Two records advertising one port: only one can hold it. Here the holder is the created
        // cluster and the other record never reserved anything, so its delete must leave the
        // reservation alone rather than hand a live cluster's port to the next create.
        service.createCacheCluster(cacheClusterRequest("holder", "redis", 1));
        AccountAwareStorageBackend<CacheCluster> store = storageFactory.create("elasticache",
                "elasticache-redis-clusters.json", new TypeReference<Map<String, CacheCluster>>() {});
        store.put("squatter", new CacheCluster("squatter", CacheClusterStatus.AVAILABLE, "redis",
                "7.1", new Endpoint("localhost", 16379), Instant.now()));

        service.deleteCacheCluster("squatter");

        assertEquals(16380, service.createCacheCluster(cacheClusterRequest("next", "redis", 1))
                        .getConfigurationEndpoint().port(),
                "16379 is still held by the cluster that actually reserved it");
    }

    @Test
    void deletingAGroupThatNeverHeldItsPortDoesNotFreeTheHoldersPort() {
        // The same asymmetry on the replication-group side: a group written straight into the
        // store advertises 16379 without ever reserving it, so its delete must not free the
        // reservation the cluster is holding.
        service.createCacheCluster(cacheClusterRequest("holder", "redis", 1));
        AccountAwareStorageBackend<ReplicationGroup> store = storageFactory.create("elasticache",
                "elasticache-groups.json", new TypeReference<Map<String, ReplicationGroup>>() {});
        store.put("squatter", new ReplicationGroup("squatter", "d", ReplicationGroupStatus.AVAILABLE,
                AuthMode.NO_AUTH, new Endpoint("localhost", 16379), Instant.now(), 16379));

        service.deleteReplicationGroup("squatter");

        assertEquals(16380, service.createCacheCluster(cacheClusterRequest("next", "redis", 1))
                        .getConfigurationEndpoint().port(),
                "16379 is still held by the cluster that actually reserved it");
    }

    @Test
    void deletingAGroupCreatedInProcessFreesItsPort() {
        // The ordinary path, which the squatter and restored-group tests both step around: a
        // group created here holds its port, so its delete has to give it back.
        service.createReplicationGroup("grp", "d", AuthMode.NO_AUTH, null, "us-east-1");

        service.deleteReplicationGroup("grp");

        assertEquals(16379, service.createCacheCluster(cacheClusterRequest("next", "redis", 1))
                .getConfigurationEndpoint().port());
    }

    @Test
    void deletingARestoredGroupFreesThePortItActuallyHolds() {
        service.createReplicationGroup("persisted", "d", AuthMode.NO_AUTH, null, "us-east-1");
        ElastiCacheService restarted = serviceAfterRestart();
        restarted.restorePersistedRuntime().join();
        restarted.createCacheCluster(cacheClusterRequest("after", "redis", 1));

        restarted.deleteReplicationGroup("persisted");

        // 16379 is free again because that group owned it; 16380 is still the cluster's.
        assertEquals(16379, restarted.createCacheCluster(cacheClusterRequest("third", "redis", 1))
                .getConfigurationEndpoint().port());
    }

    @Test
    void aReplicationGroupCannotTakeTheIdOfAMemcachedCluster() {
        // The third store counts too: CreateReplicationGroup would otherwise name a group after
        // a live memcached cluster and take over its container and proxy registration.
        memcachedService().createCacheCluster("shared-id");

        AwsException ex = assertThrows(AwsException.class, () -> service.createReplicationGroup(
                "shared-id", "d", AuthMode.NO_AUTH, null, "us-east-1"));

        assertEquals("ReplicationGroupAlreadyExistsFault", ex.getErrorCode());
        verify(containerManager, never()).tryStart(eq("shared-id"), anyString());
    }

    @Test
    void aPersistedReplicationGroupAlsoKeepsItsPortAcrossARestart() {
        service.createReplicationGroup("grp", "d", AuthMode.NO_AUTH, null, "us-east-1");

        ElastiCacheService restarted = serviceAfterRestart();
        restarted.restorePersistedRuntime().join();

        assertEquals(16380, restarted.createCacheCluster(cacheClusterRequest("after", "redis", 1))
                .getConfigurationEndpoint().port());
    }

    @Test
    void aCacheClusterCannotTakeTheIdOfAMemcachedCluster() {
        // One namespace: DescribeCacheClusters answers from every store, so two records sharing
        // an id would have it reported twice, each with a different engine.
        memcachedService().createCacheCluster("shared-id");

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createCacheCluster(cacheClusterRequest("shared-id", "redis", 1)));

        assertEquals("CacheClusterAlreadyExists", ex.getErrorCode());
        verify(containerManager, never()).tryStart(eq("shared-id"), anyString());
    }

    @Test
    void aMemcachedClusterCannotTakeTheIdOfARedisClusterOrAGroup() {
        ElastiCacheMemcachedService memcached = memcachedService();
        service.createCacheCluster(cacheClusterRequest("redis-id", "redis", 1));
        service.createReplicationGroup("group-id", "d", AuthMode.NO_AUTH, null, "us-east-1");

        assertEquals("CacheClusterAlreadyExists",
                assertThrows(AwsException.class, () -> memcached.createCacheCluster("redis-id"))
                        .getErrorCode());
        assertEquals("CacheClusterAlreadyExists",
                assertThrows(AwsException.class, () -> memcached.createCacheCluster("group-id"))
                        .getErrorCode());
        verify(memcachedContainerManager, never()).tryStart(eq("redis-id"), anyString());
        verify(memcachedContainerManager, never()).tryStart(eq("group-id"), anyString());
    }

    @Test
    void aMemcachedCreateIsRefusedWhileAReplicationGroupCreateHoldsTheSameIdInFlight()
            throws InterruptedException {
        // The stores were the only thing the memcached path consulted, and neither path writes
        // its record until its container has started. Held in that window, a group create and a
        // memcached create for one id both saw three empty stores and both went on to write,
        // leaving the id in two stores with one describe reporting it twice.
        ElastiCacheMemcachedService memcached = memcachedService();
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        when(containerManager.tryStart(anyString(), anyString())).thenAnswer(inv -> {
            startedLatch.countDown();
            assertTrue(releaseLatch.await(5, TimeUnit.SECONDS), "test timed out waiting for release");
            return new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379);
        });

        Thread groupCreate = new Thread(() ->
                service.createReplicationGroup("shared-id", "d", AuthMode.NO_AUTH, null, "us-east-1"));
        groupCreate.start();
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "create never reached container start");

        AwsException ex = assertThrows(AwsException.class,
                () -> memcached.createCacheCluster("shared-id"));
        assertEquals("CacheClusterAlreadyExists", ex.getErrorCode());
        verify(memcachedContainerManager, never()).tryStart(eq("shared-id"), anyString());

        releaseLatch.countDown();
        groupCreate.join(5000);

        // One record for the id, in the store the winning create writes.
        assertEquals("shared-id", service.getReplicationGroup("shared-id").getReplicationGroupId());
        assertTrue(memcached.listCacheClusters(null).isEmpty(),
                "the refused memcached create must not have written a second record for the id");
    }

    @Test
    void anUnknownCacheSubnetGroupIsRefused() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.createCacheCluster(new ElastiCacheService.CreateCacheClusterRequest(
                        "sng-cc", "redis", null, null, 1, null, AuthMode.NO_AUTH, null, null,
                        "no-such-group", null, null, null, null, null, null, null, null,
                        "us-east-1", Map.of())));

        assertEquals("CacheSubnetGroupNotFoundFault", ex.getErrorCode());
        // and nothing was provisioned against the name that does not resolve
        verify(containerManager, never()).tryStart(eq("sng-cc"), anyString());
        assertTrue(service.findCacheClusters("sng-cc").isEmpty());
    }

    @Test
    void aKnownCacheSubnetGroupIsAcceptedAndReported() {
        io.github.hectorvent.floci.services.ec2.model.Subnet subnet =
                new io.github.hectorvent.floci.services.ec2.model.Subnet();
        subnet.setSubnetId("subnet-1");
        subnet.setVpcId("vpc-1");
        subnet.setAvailabilityZone("us-east-1a");
        when(ec2Service.describeSubnets(anyString(), any(), any())).thenReturn(List.of(subnet));
        service.createCacheSubnetGroup("real-group", "d", List.of("subnet-1"), Map.of());

        CacheCluster cluster = service.createCacheCluster(new ElastiCacheService.CreateCacheClusterRequest(
                "sng-cc", "redis", null, null, 1, null, AuthMode.NO_AUTH, null, null,
                "real-group", null, null, null, null, null, null, null, null,
                "us-east-1", Map.of()));

        assertEquals("real-group", cluster.getCacheSubnetGroupName());
    }

    @Test
    void aCacheClusterCreatedWithoutDockerStillReachesAvailable() {
        when(containerManager.tryStart(anyString(), anyString())).thenReturn(null);

        CacheCluster cluster = service.createCacheCluster(cacheClusterRequest("no-docker", "redis", 1));

        assertEquals(CacheClusterStatus.AVAILABLE, cluster.getCacheClusterStatus());
        verify(proxyManager, never()).startProxy(eq("no-docker"), any(), anyInt(), anyString(), anyInt(), any());

        // delete must not reach for a container that was never created
        service.deleteCacheCluster("no-docker");
        verify(containerManager, never()).stop(any());
        verify(containerManager).stopByGroupId("no-docker");
    }
}
