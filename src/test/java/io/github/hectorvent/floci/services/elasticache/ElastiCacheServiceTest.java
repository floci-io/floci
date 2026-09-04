package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
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
import io.github.hectorvent.floci.services.elasticache.container.ValkeyClusterFormation;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.ClusterNode;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroup;
import io.github.hectorvent.floci.services.elasticache.model.ReplicationGroupStatus;
import io.github.hectorvent.floci.services.elasticache.proxy.ElastiCacheProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    @BeforeEach
    void setUp() {
        containerManager = mock(ElastiCacheContainerManager.class);
        proxyManager = mock(ElastiCacheProxyManager.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        config = mock(EmulatorConfig.class);

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.proxyBasePort()).thenReturn(16379);
        when(ecConfig.proxyMaxPort()).thenReturn(16399);
        when(ecConfig.defaultImage()).thenReturn("valkey/valkey:8");
        when(config.hostname()).thenReturn(java.util.Optional.of("localhost"));

        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        when(containerManager.start(anyString(), anyString()))
                .thenReturn(new ElastiCacheContainerHandle("cid", "grp", "localhost", 6379));
        doNothing().when(proxyManager).startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());
        Ec2Service ec2Service = org.mockito.Mockito.mock(Ec2Service.class);
        kmsService = org.mockito.Mockito.mock(KmsService.class);
        when(kmsService.describeKey(any(), any())).thenThrow(
                new AwsException("NotFoundException", "Key not found", 404));
        clusterFormation = mock(ValkeyClusterFormation.class);
        service = new ElastiCacheService(containerManager, proxyManager, clusterFormation,
                storageFactory, config, ec2Service, new RegionResolver("us-east-1", "000000000000"),
                kmsService);
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
                org.mockito.Mockito.mock(KmsService.class));

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
        when(containerManager.start(anyString(), anyString())).thenReturn(handle);

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
                .when(containerManager).start(eq("grp"), anyString());

        assertThrows(RuntimeException.class,
                () -> service.createReplicationGroup("grp", "test", AuthMode.PASSWORD, null, "us-east-1"));

        verify(proxyManager, never()).stopProxy(anyString());
        verify(containerManager).stopByGroupId("grp");

        // The reserved proxy port was still released: a subsequent successful create reuses the base port.
        when(containerManager.start(anyString(), anyString()))
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
                null, true, null, ReplicationGroupSettings.defaults(), Map.of());
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
                new RegionResolver("us-east-1", "000000000000"), mock(KmsService.class));
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
        when(containerManager.start(anyString(), anyString())).thenAnswer(inv -> {
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
                new RegionResolver("us-east-1", "000000000000"), kmsService);
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
                null, null, null, ReplicationGroupSettings.defaults(), Map.of());
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
        when(containerManager.start(anyString(), anyString())).thenAnswer(inv -> {
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
                new RegionResolver("us-east-1", "000000000000"), kmsService);

        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        when(containerManager.start(anyString(), anyString())).thenAnswer(inv -> {
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
        when(containerManager.start(anyString(), anyString()))
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
}
