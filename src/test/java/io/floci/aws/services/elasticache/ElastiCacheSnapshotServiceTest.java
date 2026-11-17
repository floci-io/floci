package io.floci.aws.services.elasticache;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.elasticache.container.ElastiCacheContainerManager;
import io.floci.aws.services.elasticache.model.AuthMode;
import io.floci.aws.services.elasticache.model.ElastiCacheSnapshot;
import io.floci.aws.services.elasticache.proxy.ElastiCacheProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Snapshots of a cluster-mode-disabled replication group, with the container layer mocked: what a
 * snapshot reads from its group's backend is what a group created from it loads, and the failure
 * paths leave neither a snapshot nor a group behind.
 */
class ElastiCacheSnapshotServiceTest {

    private static final String RDB = "UkVESVMwMDEx+gl2YWxrZXktdmVyBTguMC4x/w==";

    private ElastiCacheService service;
    private ElastiCacheContainerManager containerManager;
    private ElastiCacheProxyManager proxyManager;
    private Map<String, AccountAwareStorageBackend<?>> backendsByFile;

    @BeforeEach
    void setUp() {
        ElastiCacheServiceFixture fixture = new ElastiCacheServiceFixture();
        service = fixture.service;
        containerManager = fixture.containerManager;
        proxyManager = fixture.proxyManager;
        backendsByFile = fixture.backendsByFile;
        // Every group gets a container named after it, except "headless", which gets none.
        doAnswer(inv -> "headless".equals(inv.getArgument(0)) ? null
                : ElastiCacheServiceFixture.containerHandle(inv.getArgument(0)))
                .when(containerManager).tryStart(anyString(), anyString(), any());
        when(containerManager.snapshot("container-source")).thenReturn(RDB);
    }

    @Test
    void theSnapshotsRdbSeedsAGroupCreatedFromIt() {
        group("source");

        ElastiCacheSnapshot snapshot = service.createSnapshot("snap", "source", null);
        service.createReplicationGroupFromSnapshot(request("restored", null), "snap");

        assertEquals("available", snapshot.getSnapshotStatus());
        assertEquals("manual", snapshot.getSnapshotSource());
        assertEquals("source", snapshot.getReplicationGroupId());
        assertEquals("arn:aws:elasticache:us-east-1:000000000000:snapshot:snap", snapshot.getArn());
        verify(containerManager).restore("container-restored", RDB);
        assertEquals("restored", service.getReplicationGroup("restored").getReplicationGroupId());
    }

    @Test
    void theRdbIsLoadedBeforeTheGroupIsPublishedOrReachable() {
        group("source");
        service.createSnapshot("snap", "source", null);
        AtomicReference<String> duringLoad = new AtomicReference<>();
        doAnswer(inv -> {
            duringLoad.set(fault(() -> service.getReplicationGroup("restored")));
            return null;
        }).when(containerManager).restore("container-restored", RDB);

        service.createReplicationGroupFromSnapshot(request("restored", null), "snap");

        assertEquals("ReplicationGroupNotFoundFault", duringLoad.get());
        InOrder order = inOrder(containerManager, proxyManager);
        order.verify(containerManager).restore("container-restored", RDB);
        order.verify(proxyManager).startProxy(eq("restored"), any(), anyInt(), anyString(), anyInt(), any());
    }

    @Test
    void aCacheClusterIdNamesTheGroupItBelongsTo() {
        group("source");
        String member = service.memberCacheClusters(service.getReplicationGroup("source")).getFirst().cacheClusterId();

        ElastiCacheSnapshot snapshot = service.createSnapshot("snap", null, member);

        assertEquals("source", snapshot.getReplicationGroupId());
        assertEquals(member, snapshot.getCacheClusterId());
        assertEquals("CacheClusterNotFound", fault(() -> service.createSnapshot("other", null, "no-such-cluster")));
    }

    @Test
    void aCacheClusterNamedWithAGroupMustBeOneOfItsMembers() {
        group("source");
        group("another");
        String member = service.memberCacheClusters(service.getReplicationGroup("source")).getFirst().cacheClusterId();
        String stranger = service.memberCacheClusters(service.getReplicationGroup("another")).getFirst().cacheClusterId();

        assertEquals("InvalidParameterCombination", fault(() -> service.createSnapshot("snap", "source", stranger)));
        assertEquals(member, service.createSnapshot("snap", "source", member).getCacheClusterId());
    }

    @Test
    void theGroupCannotBeDeletedWhileItsRdbIsRead() throws Exception {
        group("source");
        AtomicReference<Thread> deleter = new AtomicReference<>();
        AtomicReference<Throwable> deleteFailure = new AtomicReference<>();
        when(containerManager.snapshot("container-source")).thenAnswer(inv -> {
            Thread thread = new Thread(() -> {
                try {
                    service.deleteReplicationGroup("source");
                } catch (Throwable t) {
                    deleteFailure.set(t);
                }
            });
            deleter.set(thread);
            thread.start();
            awaitBlockedOrDone(thread);
            assertTrue(thread.isAlive(), "the delete ran while the RDB was being read");
            return RDB;
        });

        assertEquals("available", service.createSnapshot("snap", "source", null).getSnapshotStatus());
        deleter.get().join(10_000);

        assertNull(deleteFailure.get());
        assertEquals("ReplicationGroupNotFoundFault", fault(() -> service.getReplicationGroup("source")));
    }

    @Test
    void aTakenNameIsRefused() {
        group("source");
        service.createSnapshot("snap", "source", null);

        assertEquals("SnapshotAlreadyExistsFault", fault(() -> service.createSnapshot("snap", "source", null)));
    }

    @Test
    void aGroupWithoutARunningBackendCannotBeSnapshotted() {
        group("headless");

        assertEquals("InvalidReplicationGroupState", fault(() -> service.createSnapshot("snap", "headless", null)));
        assertTrue(service.describeSnapshots(null, null, null, null).isEmpty());
    }

    @Test
    void aBackendThatCannotBeReadLeavesNoSnapshot() {
        group("source");
        when(containerManager.snapshot("container-source")).thenThrow(new IllegalStateException("SAVE exited 1"));

        assertEquals("InvalidReplicationGroupState", fault(() -> service.createSnapshot("snap", "source", null)));
        assertEquals("SnapshotNotFoundFault", fault(() -> service.getSnapshot("snap")));
    }

    @Test
    void aSnapshotWhoseArchiveIsLostIsRefusedBeforeAnyGroupExists() {
        group("source");
        service.createSnapshot("snap", "source", null);
        backendsByFile.get("elasticache-snapshot-data.json").clear();

        assertEquals("InvalidSnapshotState",
                fault(() -> service.createReplicationGroupFromSnapshot(request("restored", null), "snap")));
        assertEquals("ReplicationGroupNotFoundFault", fault(() -> service.getReplicationGroup("restored")));
        verify(containerManager, never()).tryStart(eq("restored"), anyString(), any());
    }

    @Test
    void aGroupTheRdbCannotBeLoadedIntoIsRemoved() {
        group("source");
        service.createSnapshot("snap", "source", null);
        doThrow(new IllegalStateException("DEBUG RELOAD failed"))
                .when(containerManager).restore("container-restored", RDB);

        assertEquals("InvalidSnapshotState",
                fault(() -> service.createReplicationGroupFromSnapshot(request("restored", null), "snap")));
        assertEquals("ReplicationGroupNotFoundFault", fault(() -> service.getReplicationGroup("restored")));
        verify(proxyManager, never()).startProxy(eq("restored"), any(), anyInt(), anyString(), anyInt(), any());
        verify(containerManager).stop(any());
    }

    @Test
    void aClusterModeGroupCannotBeSeededAndIsNeverStarted() {
        group("source");
        service.createSnapshot("snap", "source", null);

        assertEquals("InvalidParameterCombination",
                fault(() -> service.createReplicationGroupFromSnapshot(request("sharded", "enabled"), "snap")));
        verify(containerManager, never()).tryStart(eq("sharded"), anyString(), any());
    }

    @Test
    void describeMatchesEveryFilterAndDeleteRemovesTheSnapshot() {
        group("source");
        service.createSnapshot("snap", "source", null);

        assertEquals(1, service.describeSnapshots("snap", "source", null, "manual").size());
        assertTrue(service.describeSnapshots("snap", "another-group", null, null).isEmpty());
        assertTrue(service.describeSnapshots(null, null, null, "automated").isEmpty());

        assertEquals("deleting", service.deleteSnapshot("snap").getSnapshotStatus());
        assertEquals("SnapshotNotFoundFault", fault(() -> service.describeSnapshots("snap", null, null, null)));
        assertEquals("SnapshotNotFoundFault", fault(() -> service.deleteSnapshot("snap")));
        assertEquals("InvalidParameterValue", fault(() -> service.deleteSnapshot(null)));
        assertEquals("SnapshotNotFoundFault",
                fault(() -> service.createReplicationGroupFromSnapshot(request("restored", null), "snap")));
    }

    @Test
    void tagsGivenAtCreateAreKeptAndCanBeChanged() {
        group("source");
        String arn = service.createSnapshot("snap", "source", null, Map.of("team", "cache")).getArn();

        assertEquals(Map.of("team", "cache"), service.getSnapshot("snap").getTags());
        assertEquals(Map.of("team", "cache", "env", "test"),
                service.addTagsToResource(arn, Map.of("env", "test"), "us-east-1"));
        assertEquals(Map.of("env", "test"), service.removeTagsFromResource(arn, List.of("team"), "us-east-1"));
        assertEquals(Map.of("env", "test"), service.getSnapshot("snap").getTags());
    }

    @Test
    void describeSnapshotsPagesByNameWithMaxRecordsAndMarker() {
        group("source");
        for (int i = 0; i < 25; i++) {
            service.createSnapshot(String.format("snap-%02d", i), "source", null);
        }

        PaginatedResult<ElastiCacheSnapshot> first = service.describeSnapshots(null, null, null, null, 20, null);
        PaginatedResult<ElastiCacheSnapshot> second =
                service.describeSnapshots(null, null, null, null, 20, first.nextToken());

        assertEquals(20, first.items().size());
        assertEquals("snap-00", first.items().getFirst().getSnapshotName());
        assertEquals(List.of("snap-20", "snap-21", "snap-22", "snap-23", "snap-24"),
                second.items().stream().map(ElastiCacheSnapshot::getSnapshotName).toList());
        assertNull(second.nextToken());
        assertEquals(25, service.describeSnapshots(null, null, null, null, null, null).items().size());
        assertEquals("InvalidParameterValue", fault(() -> service.describeSnapshots(null, null, null, null, 19, null)));
        assertEquals("InvalidParameterValue", fault(() -> service.describeSnapshots(null, null, null, null, 51, null)));
        assertEquals("InvalidParameterValue",
                fault(() -> service.describeSnapshots(null, null, null, null, null, "not-a-marker")));
    }

    private void group(String id) {
        service.createReplicationGroup(id, "group " + id, AuthMode.NO_AUTH, null, "us-east-1");
    }

    private static ElastiCacheService.CreateReplicationGroupRequest request(String id, String clusterMode) {
        return new ElastiCacheService.CreateReplicationGroupRequest(id, "group " + id, AuthMode.NO_AUTH, null,
                "us-east-1", null, null, null, null, null, clusterMode,
                "enabled".equals(clusterMode) ? 2 : null, null, null, null, null, null, null, Map.of(), null, null);
    }

    private static String fault(Runnable call) {
        return assertThrows(AwsException.class, call::run).getErrorCode();
    }
}
