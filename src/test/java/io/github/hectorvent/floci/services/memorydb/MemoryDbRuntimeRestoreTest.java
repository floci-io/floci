package io.github.hectorvent.floci.services.memorydb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.elasticache.proxy.SigV4Validator;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerHandle;
import io.github.hectorvent.floci.services.memorydb.container.MemoryDbContainerManager;
import io.github.hectorvent.floci.services.memorydb.model.Acl;
import io.github.hectorvent.floci.services.memorydb.model.AuthMode;
import io.github.hectorvent.floci.services.memorydb.model.Cluster;
import io.github.hectorvent.floci.services.memorydb.model.ClusterStatus;
import io.github.hectorvent.floci.services.memorydb.model.User;
import io.github.hectorvent.floci.services.memorydb.proxy.MemoryDbAuthProxy;
import io.github.hectorvent.floci.services.memorydb.proxy.MemoryDbProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryDbRuntimeRestoreTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private final InMemoryStorage<String, Cluster> rawClusters = new InMemoryStorage<>();
    private final AccountAwareStorageBackend<Cluster> clusters =
            spy(new AccountAwareStorageBackend<>(rawClusters, null, DEFAULT_ACCOUNT));
    private final AccountAwareStorageBackend<User> users = AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT);
    private final AccountAwareStorageBackend<Acl> acls = AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT);
    private MemoryDbContainerManager containers;
    private MemoryDbProxyManager proxies;
    private EmulatorConfig config;
    private MemoryDbService service;

    @BeforeEach
    void setup() {
        containers = mock(MemoryDbContainerManager.class);
        when(containers.isDockerReachable()).thenReturn(true);
        proxies = mock(MemoryDbProxyManager.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().memorydb().proxyBasePort()).thenReturn(17400);
        when(config.services().memorydb().proxyMaxPort()).thenReturn(17403);
        when(config.services().memorydb().defaultImage()).thenReturn("valkey/valkey:8");
        when(config.storage().mode()).thenReturn("persistent");
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        StorageFactory storage = mock(StorageFactory.class);
        when(storage.create(eq("memorydb"), eq("memorydb-clusters.json"), any())).thenAnswer(inv -> clusters);
        when(storage.create(eq("memorydb"), eq("memorydb-users.json"), any())).thenAnswer(inv -> users);
        when(storage.create(eq("memorydb"), eq("memorydb-acls.json"), any())).thenAnswer(inv -> acls);
        service = new MemoryDbService(containers, proxies, mock(SigV4Validator.class), storage, config,
                new RegionResolver("us-east-1", DEFAULT_ACCOUNT));
    }

    @Test
    void creationPersistsItsOwnerVolumeAndPortBeforeStartingDocker() {
        when(containers.tryStart(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    Cluster pending = clusters.getForAccount(DEFAULT_ACCOUNT, "us-east-1:new").orElseThrow();
                    assertEquals(ClusterStatus.CREATING, pending.getStatus());
                    assertEquals(DEFAULT_ACCOUNT, pending.getAccountId());
                    assertEquals("us-east-1", pending.getRegion());
                    assertEquals(inv.getArgument(2), pending.getVolumeId());
                    assertEquals(17400, pending.getProxyPort());
                    assertNull(pending.getClusterEndpoint());
                    return new MemoryDbContainerHandle("new-cid", inv.getArgument(0), "localhost", 6379);
                });

        assertEquals(ClusterStatus.AVAILABLE, service.createCluster(spec("new"), "us-east-1").getStatus());
    }

    @Test
    void failedOwnershipPersistenceDoesNotAllocateRuntimeAndReleasesItsPort() {
        doThrow(new IllegalStateException("snapshot rejected")).when(clusters)
                .putAllAtomicallyForAccount(eq(DEFAULT_ACCOUNT), anyMap());

        assertThrows(IllegalStateException.class, () -> service.createCluster(spec("new"), "us-east-1"));

        verifyNoInteractions(containers, proxies);
        assertTrue(clusters.getForAccount(DEFAULT_ACCOUNT, "us-east-1:new").isEmpty());
        doCallRealMethod().when(clusters).putAllAtomicallyForAccount(eq(DEFAULT_ACCOUNT), anyMap());
        when(containers.tryStart(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new MemoryDbContainerHandle("retry-cid", "retry", "localhost", 6379));
        assertEquals(17400, service.createCluster(spec("retry"), "us-east-1").getProxyPort());
    }

    @Test
    void failedCleanupRetainsOwnershipAndPortUntilDeletionRetries() {
        MemoryDbContainerHandle handle = new MemoryDbContainerHandle("failed-cid",
                "000000000000-us-east-1-failed", "localhost", 6379);
        when(containers.tryStart(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(handle);
        doThrow(new IllegalStateException("proxy rejected")).when(proxies)
                .startProxy(eq(handle.getClusterName()), anyBoolean(), anyInt(), anyString(), anyInt(), any());
        doThrow(new IllegalStateException("Docker cleanup rejected")).when(containers).stop(any(MemoryDbContainerHandle.class));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.createCluster(spec("failed"), "us-east-1"));

        assertEquals(1, failure.getSuppressed().length);
        Cluster retained = service.getCluster("failed", "us-east-1");
        assertEquals(ClusterStatus.CREATING, retained.getStatus());
        assertNull(retained.getClusterEndpoint());
        assertEquals("failed-cid", retained.getContainerId());
        assertNotNull(retained.getVolumeId());
        assertEquals(17401, service.createCluster(spec("other"), "us-east-1").getProxyPort());
        doNothing().when(containers).stop(any(MemoryDbContainerHandle.class));
        service.deleteCluster("failed", "us-east-1");
        assertThrows(AwsException.class, () -> service.getCluster("failed", "us-east-1"));
        assertEquals(17400, service.createCluster(spec("replacement"), "us-east-1").getProxyPort());
    }

    @Test
    void failedRollbackPersistenceKeepsTheClusterUnavailableAndItsPortReserved() {
        AtomicInteger writes = new AtomicInteger();
        doAnswer(inv -> {
            if (writes.incrementAndGet() == 2) {
                throw new IllegalStateException("final record write rejected");
            }
            return inv.callRealMethod();
        }).when(clusters).putAllAtomicallyForAccount(anyString(), anyMap());
        doThrow(new IllegalStateException("rollback record deletion rejected"))
                .when(clusters).deleteAllAtomicallyForAccounts(anyMap());
        when(containers.tryStart(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new MemoryDbContainerHandle("owned-cid", inv.getArgument(0),
                        "localhost", 6379));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.createCluster(spec("failed"), "us-east-1"));

        assertEquals(1, failure.getSuppressed().length);
        Cluster retained = service.getCluster("failed", "us-east-1");
        assertEquals(ClusterStatus.CREATING, retained.getStatus());
        assertNull(retained.getClusterEndpoint());
        assertNull(retained.getContainerId());
        assertEquals(17401, service.createCluster(spec("other"), "us-east-1").getProxyPort());
        doCallRealMethod().when(clusters).deleteAllAtomicallyForAccounts(anyMap());
        service.deleteCluster("failed", "us-east-1");
        assertEquals(17400, service.createCluster(spec("replacement"), "us-east-1").getProxyPort());
    }

    @Test
    void restoresEveryOwnerAndRegionWithTheirOwnAuthenticationAndReservedPorts() throws Exception {
        Cluster first = persisted(DEFAULT_ACCOUNT, "us-east-1", "same", 17400);
        Cluster second = persisted("111111111111", "eu-west-1", "same", 17401);
        passwordAcl(first, "first-secret");
        passwordAcl(second, "second-secret");
        Map<Integer, MemoryDbAuthProxy.AuthValidator> validators = new ConcurrentHashMap<>();
        doAnswer(inv -> {
            validators.put(inv.getArgument(2), inv.getArgument(5));
            return null;
        }).when(proxies).startProxy(anyString(), eq(true), anyInt(), anyString(), anyInt(), any());
        when(containers.tryStart(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> new MemoryDbContainerHandle(inv.getArgument(2), inv.getArgument(0),
                        "127.0.0.1", 6379));

        service.restorePersistedRuntime().get(5, TimeUnit.SECONDS);

        assertEquals(ClusterStatus.AVAILABLE, first.getStatus());
        assertEquals(ClusterStatus.AVAILABLE, second.getStatus());
        assertEquals(17400, first.getClusterEndpoint().port());
        assertEquals(17401, second.getClusterEndpoint().port());
        assertTrue(validators.get(17400).authenticate("user", "first-secret"));
        assertFalse(validators.get(17400).authenticate("user", "second-secret"));
        assertTrue(validators.get(17401).authenticate("user", "second-secret"));
        assertFalse(validators.get(17401).authenticate("user", "first-secret"));
        verify(containers).tryStart("000000000000-us-east-1-same", "valkey/valkey:8", first.getVolumeId(),
                DEFAULT_ACCOUNT, "us-east-1");
        verify(containers).tryStart("111111111111-eu-west-1-same", "valkey/valkey:8", second.getVolumeId(),
                "111111111111", "eu-west-1");
    }

    @Test
    void defaultAccountNameCannotCollideWithAnotherAccountsBackendIdentity() throws Exception {
        persisted(DEFAULT_ACCOUNT, "us-east-1", "111111111111-eu-west-1-same", 17400);
        persisted("111111111111", "eu-west-1", "same", 17401);
        Map<String, String> backendOwners = new ConcurrentHashMap<>();
        when(containers.tryStart(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String identity = inv.getArgument(0);
                    String account = inv.getArgument(3);
                    assertNull(backendOwners.putIfAbsent(identity, account),
                            "Backend identities must remain unique across account and region boundaries");
                    return new MemoryDbContainerHandle(inv.getArgument(2), identity, "localhost", 6379);
                });

        service.restorePersistedRuntime().get(5, TimeUnit.SECONDS);

        assertEquals(2, backendOwners.size());
        assertEquals(2, backendOwners.values().stream().distinct().count());
    }

    @Test
    void deletionWhileBackendStartsDoesNotRestoreTheRecordOrReleaseAnotherClustersPort() throws Exception {
        Cluster old = persisted(DEFAULT_ACCOUNT, "us-east-1", "old", 17400);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        MemoryDbContainerHandle oldHandle = new MemoryDbContainerHandle("old-cid", "old", "localhost", 6379);
        when(containers.tryStart(eq("000000000000-us-east-1-old"), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    started.countDown();
                    assertTrue(finish.await(5, TimeUnit.SECONDS));
                    return oldHandle;
                });
        when(containers.tryStart(eq("000000000000-us-east-1-replacement"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new MemoryDbContainerHandle("new-cid", "replacement", "localhost", 6379));
        CompletableFuture<Void> restored = service.restorePersistedRuntime();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(ClusterStatus.CREATING, old.getStatus());
            service.deleteCluster("old", "us-east-1");
            Cluster spec = new Cluster();
            spec.setName("replacement");
            spec.setAclName("open-access");
            Cluster replacement = service.createCluster(spec, "us-east-1");
            assertEquals(17400, replacement.getProxyPort());
            finish.countDown();
            restored.get(5, TimeUnit.SECONDS);
            assertTrue(clusters.getForAccount(DEFAULT_ACCOUNT, "us-east-1:old").isEmpty());
            verify(containers).stop(oldHandle);
            verify(proxies, never()).startProxy(eq("000000000000-us-east-1-old"), anyBoolean(), anyInt(), anyString(), anyInt(), any());
            Cluster nextSpec = new Cluster();
            nextSpec.setName("next");
            nextSpec.setAclName("open-access");
            assertEquals(17401, service.createCluster(nextSpec, "us-east-1").getProxyPort());
        } finally {
            finish.countDown();
            restored.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void deletingPendingCreateDoesNotResurrectItOrReleaseAReusedPort() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        MemoryDbContainerHandle oldHandle = new MemoryDbContainerHandle("old-cid",
                "000000000000-us-east-1-old", "localhost", 6379);
        when(containers.tryStart(eq(oldHandle.getClusterName()), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    started.countDown();
                    assertTrue(finish.await(5, TimeUnit.SECONDS));
                    return oldHandle;
                });
        when(containers.tryStart(eq("000000000000-us-east-1-other"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new MemoryDbContainerHandle("other-cid", "other", "localhost", 6379));
        CompletableFuture<Cluster> creation = CompletableFuture.supplyAsync(
                () -> service.createCluster(spec("old"), "us-east-1"));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(ClusterStatus.CREATING, service.getCluster("old", "us-east-1").getStatus());
            service.deleteCluster("old", "us-east-1");
            assertEquals(17400, service.createCluster(spec("other"), "us-east-1").getProxyPort());
            finish.countDown();
            assertThrows(ExecutionException.class, () -> creation.get(5, TimeUnit.SECONDS));
            assertThrows(AwsException.class, () -> service.getCluster("old", "us-east-1"));
            verify(containers).stop(oldHandle);
            verify(proxies, never()).startProxy(eq(oldHandle.getClusterName()),
                    anyBoolean(), anyInt(), anyString(), anyInt(), any());
            assertEquals(17401, service.createCluster(spec("next"), "us-east-1").getProxyPort());
        } finally {
            finish.countDown();
            assertThrows(ExecutionException.class, () -> creation.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedBackendIsNotAdvertisedAsAvailableAndOtherClustersStillRestore() throws Exception {
        Cluster failed = persisted(DEFAULT_ACCOUNT, "us-east-1", "failed", 17400);
        Cluster healthy = persisted(DEFAULT_ACCOUNT, "us-east-1", "healthy", 17401);
        when(containers.tryStart(eq("000000000000-us-east-1-failed"), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("backend rejected"));
        when(containers.tryStart(eq("000000000000-us-east-1-healthy"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new MemoryDbContainerHandle("healthy-cid", "healthy", "localhost", 6379));

        assertThrows(ExecutionException.class, () -> service.restorePersistedRuntime().get(5, TimeUnit.SECONDS));

        assertEquals(ClusterStatus.CREATING, failed.getStatus());
        assertNull(failed.getClusterEndpoint());
        assertNull(failed.getContainerId());
        assertEquals(ClusterStatus.AVAILABLE, healthy.getStatus());
    }

    @Test
    void unprefixedLegacyArnRestoresItsOwnAccountAndRegion() throws Exception {
        Cluster legacy = spec("legacy");
        legacy.setArn("arn:aws:memorydb:eu-west-1:111111111111:cluster/legacy");
        rawClusters.put("legacy", legacy);
        when(containers.tryStart(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new MemoryDbContainerHandle("legacy-cid", "legacy", "localhost", 6379));

        service.restorePersistedRuntime().get(5, TimeUnit.SECONDS);

        assertSame(legacy, clusters.getForAccount("111111111111", "eu-west-1:legacy").orElseThrow());
        assertEquals("111111111111", legacy.getAccountId());
        assertEquals("eu-west-1", legacy.getRegion());
        assertTrue(rawClusters.get("legacy").isEmpty());
        assertTrue(service.describeClusters(null, "eu-west-1").isEmpty());
        verify(containers).tryStart("111111111111-eu-west-1-legacy", "valkey/valkey:8",
                legacy.getVolumeId(), "111111111111", "eu-west-1");
    }

    @Test
    void oldAccountPrefixedNameMigratesToItsRegionalKey() throws Exception {
        Cluster legacy = spec("legacy");
        legacy.setAccountId("111111111111");
        legacy.setRegion("eu-west-1");
        rawClusters.put("111111111111/legacy", legacy);

        service.restorePersistedRuntime().get(5, TimeUnit.SECONDS);

        assertSame(legacy, clusters.getForAccount("111111111111", "eu-west-1:legacy").orElseThrow());
        assertTrue(rawClusters.get("111111111111/legacy").isEmpty());
        assertTrue(service.describeClusters(null, "eu-west-1").isEmpty());
    }

    @Test
    void existingScopedRecordWinsWithoutDiscardingConflictingLegacyData() throws Exception {
        Cluster current = persisted(DEFAULT_ACCOUNT, "us-east-1", "same", 17400);
        Cluster legacy = spec("same");
        legacy.setVolumeId("retained-legacy-data");
        rawClusters.put("same", legacy);

        service.restorePersistedRuntime().get(5, TimeUnit.SECONDS);

        assertEquals(List.of(current), List.copyOf(service.describeClusters(null, "us-east-1")));
        assertEquals(List.of(current), List.copyOf(service.describeClusters("same", "us-east-1")));
        assertSame(current, service.getCluster("same", "us-east-1"));
        assertEquals(List.of(legacy), rawClusters.scan(
                key -> key.startsWith(DEFAULT_ACCOUNT + "/legacy-clusters/")));
        assertTrue(rawClusters.get("same").isEmpty());
        verify(containers, times(1)).tryStart(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void contradictoryStoredOwnerIsReportedWithoutAllocatingOrReassigningIt() {
        Cluster inconsistent = spec("conflict");
        inconsistent.setAccountId(DEFAULT_ACCOUNT);
        inconsistent.setRegion("eu-west-1");
        rawClusters.put("111111111111/eu-west-1:conflict", inconsistent);

        assertThrows(ExecutionException.class, () -> service.restorePersistedRuntime().get(5, TimeUnit.SECONDS));

        assertEquals(DEFAULT_ACCOUNT, inconsistent.getAccountId());
        assertSame(inconsistent, rawClusters.get("111111111111/eu-west-1:conflict").orElseThrow());
        verifyNoInteractions(containers, proxies);
    }

    @Test
    void restorationUsesThePersistedArnRegionWhenTheLegacyRegionFieldIsMissing() throws Exception {
        Cluster cluster = persisted(DEFAULT_ACCOUNT, "eu-west-1", "legacy", 17400);
        cluster.setRegion(null);
        cluster.setArn("arn:aws:memorydb:eu-west-1:000000000000:cluster/legacy");
        when(containers.tryStart(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new MemoryDbContainerHandle("restored-cid", "legacy", "localhost", 6379));

        service.restorePersistedRuntime().get(5, TimeUnit.SECONDS);

        assertEquals("eu-west-1", cluster.getRegion());
        verify(containers).tryStart("000000000000-eu-west-1-legacy", "valkey/valkey:8",
                cluster.getVolumeId(), DEFAULT_ACCOUNT, "eu-west-1");
    }

    @Test
    void deletingLegacyRecordKeepsItsStoredOwnerAndCanBeDeletedAfterRestart() throws Exception {
        Cluster cluster = persisted(DEFAULT_ACCOUNT, "us-east-1", "deleting", 17400);
        cluster.setAccountId(null);
        cluster.setRegion(null);
        cluster.setStatus(ClusterStatus.DELETING);

        service.restorePersistedRuntime().get(5, TimeUnit.SECONDS);

        verifyNoInteractions(containers, proxies);
        assertEquals(DEFAULT_ACCOUNT, cluster.getAccountId());
        assertEquals("us-east-1", cluster.getRegion());
        service.deleteCluster("deleting", "us-east-1");
        assertThrows(AwsException.class, () -> service.getCluster("deleting", "us-east-1"));
    }

    @Test
    void restorationRetiresPersistedBackendBeforeReplacingIt() throws Exception {
        Cluster cluster = persisted(DEFAULT_ACCOUNT, "us-east-1", "legacy", 17400);
        cluster.setContainerId("legacy-cid");
        cluster.setContainerHost("localhost");
        cluster.setContainerPort(6379);
        when(containers.isDockerReachable()).thenReturn(true);
        when(containers.tryStart(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new MemoryDbContainerHandle("replacement-cid", "legacy", "localhost", 6379));

        service.restorePersistedRuntime().get(5, TimeUnit.SECONDS);

        InOrder ordering = inOrder(containers);
        ordering.verify(containers).isDockerReachable();
        ordering.verify(containers).stop(argThat(handle -> "legacy-cid".equals(handle.getContainerId())));
        ordering.verify(containers).tryStart("000000000000-us-east-1-legacy", "valkey/valkey:8",
                cluster.getVolumeId(), DEFAULT_ACCOUNT, "us-east-1");
        assertEquals("replacement-cid", cluster.getContainerId());
        assertEquals(ClusterStatus.AVAILABLE, cluster.getStatus());
    }

    @Test
    void unavailableDaemonDoesNotDiscardPersistedBackendOwnership() throws Exception {
        Cluster cluster = persisted(DEFAULT_ACCOUNT, "us-east-1", "legacy", 17400);
        cluster.setContainerId("legacy-cid");
        cluster.setContainerHost("localhost");
        cluster.setContainerPort(6379);
        String volumeId = cluster.getVolumeId();
        when(containers.isDockerReachable()).thenReturn(false);

        service.restorePersistedRuntime().get(5, TimeUnit.SECONDS);

        assertEquals("legacy-cid", cluster.getContainerId());
        assertEquals(volumeId, cluster.getVolumeId());
        assertEquals(ClusterStatus.AVAILABLE, cluster.getStatus());
        verify(containers, never()).stop(any(MemoryDbContainerHandle.class));
        verify(containers, never()).tryStart(anyString(), anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(proxies);
    }

    @Test
    void failedPersistedBackendCleanupKeepsTheExactOldBackendForRetry() throws Exception {
        Cluster cluster = persisted(DEFAULT_ACCOUNT, "us-east-1", "legacy", 17400);
        cluster.setContainerId("legacy-cid");
        cluster.setContainerHost("localhost");
        cluster.setContainerPort(6379);
        when(containers.isDockerReachable()).thenReturn(true);
        doThrow(new IllegalStateException("legacy cleanup rejected")).when(containers)
                .stop(any(MemoryDbContainerHandle.class));

        assertThrows(ExecutionException.class, () -> service.restorePersistedRuntime().get(5, TimeUnit.SECONDS));

        assertEquals("legacy-cid", cluster.getContainerId());
        assertEquals(ClusterStatus.CREATING, cluster.getStatus());
        assertNull(cluster.getClusterEndpoint());
        verify(containers, never()).tryStart(anyString(), anyString(), anyString(), anyString(), anyString());
        assertEquals(17401, service.createCluster(spec("other"), "us-east-1").getProxyPort());
    }

    @Test
    void failedRestoreCleanupRetainsItsBackendPortAndDataForDeletionRetry() throws Exception {
        Cluster failed = persisted(DEFAULT_ACCOUNT, "us-east-1", "failed", 17400);
        String volumeId = failed.getVolumeId();
        MemoryDbContainerHandle handle = new MemoryDbContainerHandle("restore-cid",
                "000000000000-us-east-1-failed", "localhost", 6379);
        when(containers.tryStart(eq(handle.getClusterName()), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(handle);
        when(containers.tryStart(eq("000000000000-us-east-1-other"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new MemoryDbContainerHandle("other-cid", "other", "localhost", 6379));
        doThrow(new IllegalStateException("proxy rejected")).when(proxies)
                .startProxy(eq(handle.getClusterName()), anyBoolean(), anyInt(), anyString(), anyInt(), any());
        doThrow(new IllegalStateException("Docker cleanup rejected")).when(containers).stop(any(MemoryDbContainerHandle.class));

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> service.restorePersistedRuntime().get(5, TimeUnit.SECONDS));

        assertEquals(1, failure.getCause().getSuppressed().length);
        assertEquals(ClusterStatus.CREATING, failed.getStatus());
        assertNull(failed.getClusterEndpoint());
        assertEquals("restore-cid", failed.getContainerId());
        assertEquals(volumeId, failed.getVolumeId());
        assertEquals(17401, service.createCluster(spec("other"), "us-east-1").getProxyPort());
        verify(containers, never()).removeStorage(anyString(), anyString());
        doNothing().when(containers).stop(any(MemoryDbContainerHandle.class));
        service.deleteCluster("failed", "us-east-1");
        verify(containers).removeStorage(handle.getClusterName(), volumeId);
        assertThrows(AwsException.class, () -> service.getCluster("failed", "us-east-1"));
    }

    @Test
    void retryingRestorationReusesThePortRetainedAfterCleanupFailure() throws Exception {
        Cluster failed = persisted(DEFAULT_ACCOUNT, "us-east-1", "failed", 17400);
        MemoryDbContainerHandle handle = new MemoryDbContainerHandle("restore-cid",
                "000000000000-us-east-1-failed", "localhost", 6379);
        when(containers.tryStart(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(handle);
        doThrow(new IllegalStateException("proxy rejected")).when(proxies)
                .startProxy(eq(handle.getClusterName()), anyBoolean(), anyInt(), anyString(), anyInt(), any());
        doThrow(new IllegalStateException("Docker cleanup rejected")).when(containers).stop(any(MemoryDbContainerHandle.class));
        assertThrows(ExecutionException.class, () -> service.restorePersistedRuntime().get(5, TimeUnit.SECONDS));
        doNothing().when(proxies)
                .startProxy(eq(handle.getClusterName()), anyBoolean(), anyInt(), anyString(), anyInt(), any());
        doNothing().when(containers).stop(any(MemoryDbContainerHandle.class));

        service.restorePersistedRuntime().get(5, TimeUnit.SECONDS);

        assertEquals(ClusterStatus.AVAILABLE, failed.getStatus());
        assertEquals(17400, failed.getClusterEndpoint().port());
        assertEquals(17401, service.createCluster(spec("other"), "us-east-1").getProxyPort());
    }

    @Test
    void mockRestorationDoesNotAllocateContainersOrProxies() throws Exception {
        persisted(DEFAULT_ACCOUNT, "us-east-1", "mocked", 17400);
        when(config.services().memorydb().mock()).thenReturn(true);

        service.restorePersistedRuntime().get(5, TimeUnit.SECONDS);

        verifyNoInteractions(containers, proxies);
    }

    private Cluster persisted(String account, String region, String name, int port) {
        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setAccountId(account);
        cluster.setRegion(region);
        cluster.setStatus(ClusterStatus.AVAILABLE);
        cluster.setAclName("open-access");
        cluster.setProxyPort(port);
        cluster.setVolumeId(account + "-" + region + "-" + name);
        clusters.putForAccount(account, region + ":" + name, cluster);
        return cluster;
    }

    private Cluster spec(String name) {
        Cluster cluster = new Cluster();
        cluster.setName(name);
        cluster.setAclName("open-access");
        return cluster;
    }

    private void passwordAcl(Cluster cluster, String password) {
        cluster.setAclName("private");
        Acl acl = new Acl();
        acl.setName("private");
        acl.setUserNames(List.of("user"));
        acls.putForAccount(cluster.getAccountId(), cluster.getRegion() + ":private", acl);
        User user = new User();
        user.setName("user");
        user.setAuthMode(AuthMode.PASSWORD);
        user.setPasswords(List.of(password));
        users.putForAccount(cluster.getAccountId(), cluster.getRegion() + ":user", user);
    }
}
