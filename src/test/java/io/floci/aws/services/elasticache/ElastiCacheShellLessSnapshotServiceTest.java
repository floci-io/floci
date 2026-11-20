package io.floci.aws.services.elasticache;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.elasticache.container.ElastiCacheContainerManager;
import io.floci.aws.services.elasticache.model.AuthMode;
import io.floci.aws.services.elasticache.proxy.ElastiCacheProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A group created from a snapshot when the backend is the shell-less one: the snapshot goes to the
 * container manager with the start, so it is loaded by the server at startup and never through
 * {@code restore}, and a group that cannot be started with it is rolled back without touching the
 * group or the snapshot it came from.
 */
class ElastiCacheShellLessSnapshotServiceTest {

    private static final String RDB = "UkVESVMwMDEx+gl2YWxrZXktdmVyBTguMC4x/w==";

    private ElastiCacheService service;
    private ElastiCacheContainerManager containerManager;
    private ElastiCacheProxyManager proxyManager;

    @BeforeEach
    void setUp() {
        ElastiCacheServiceFixture fixture = new ElastiCacheServiceFixture();
        service = fixture.service;
        containerManager = fixture.containerManager;
        proxyManager = fixture.proxyManager;
        when(containerManager.isShellLessBackend()).thenReturn(true);
        doAnswer(inv -> ElastiCacheServiceFixture.containerHandle(inv.getArgument(0)))
                .when(containerManager).tryStart(anyString(), anyString(), any(), any());
        when(containerManager.snapshot("container-source")).thenReturn(RDB);
        service.createReplicationGroup("source", "group source", AuthMode.NO_AUTH, null, "us-east-1");
        service.createSnapshot("snap", "source", null);
    }

    @Test
    void theSnapshotIsHandedToTheStartAndNeverRestoredIntoARunningServer() {
        service.createReplicationGroupFromSnapshot(request("restored"), "snap");

        verify(containerManager).tryStart("restored", "valkey/valkey:8", "us-east-1", RDB);
        verify(containerManager, never()).restore(anyString(), anyString());
        verify(proxyManager).startProxy(eq("restored"), any(), anyInt(), anyString(), anyInt(), any());
        assertEquals("restored", service.getReplicationGroup("restored").getReplicationGroupId());
    }

    @Test
    void aGroupWithoutASnapshotStartsWithoutOne() {
        service.createReplicationGroup("plain", "group plain", AuthMode.NO_AUTH, null, "us-east-1");

        verify(containerManager).tryStart("plain", "valkey/valkey:8", "us-east-1");
        verify(containerManager, never()).tryStart(eq("plain"), anyString(), anyString(), any());
    }

    @Test
    void aStartTheSnapshotCannotBeLoadedInRollsTheNewGroupBackAndKeepsTheRest() {
        doAnswer(inv -> {
            throw new ElastiCacheContainerManager.SnapshotRestoreException("the server could not load it");
        }).when(containerManager).tryStart(eq("restored"), anyString(), anyString(), any());

        AwsException failure = assertThrows(AwsException.class,
                () -> service.createReplicationGroupFromSnapshot(request("restored"), "snap"));

        assertEquals("InvalidSnapshotState", failure.getErrorCode());
        // The container of the failed start is removed by id, since no handle came back.
        verify(containerManager).stopByGroupId("restored");
        verify(proxyManager, never()).startProxy(eq("restored"), any(), anyInt(), anyString(), anyInt(), any());
        assertEquals("ReplicationGroupNotFoundFault", fault(() -> service.getReplicationGroup("restored")));
        // Neither the group the snapshot came from nor the snapshot is lost.
        assertEquals("source", service.getReplicationGroup("source").getReplicationGroupId());
        assertEquals("available", service.getSnapshot("snap").getSnapshotStatus());
        // The failed group's name and port are free again.
        service.createReplicationGroupFromSnapshot(request("restored-again"), "snap");
    }

    @Test
    void withoutADockerDaemonASnapshotCannotBeRestoredAndNothingIsLeftBehind() {
        doAnswer(inv -> null).when(containerManager).tryStart(eq("headless"), anyString(), anyString(), any());

        assertEquals("InvalidReplicationGroupState",
                fault(() -> service.createReplicationGroupFromSnapshot(request("headless"), "snap")));
        assertEquals("ReplicationGroupNotFoundFault", fault(() -> service.getReplicationGroup("headless")));
        verify(containerManager).stopByGroupId("headless");
    }

    private static ElastiCacheService.CreateReplicationGroupRequest request(String id) {
        return new ElastiCacheService.CreateReplicationGroupRequest(id, "group " + id, AuthMode.NO_AUTH, null,
                "us-east-1", null, null, null, null, null, null, null, null, null, null, null, null, null,
                Map.of(), null, null);
    }

    private static String fault(Runnable call) {
        return assertThrows(AwsException.class, call::run).getErrorCode();
    }
}
