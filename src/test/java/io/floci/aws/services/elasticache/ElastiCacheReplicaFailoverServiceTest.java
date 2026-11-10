package io.floci.aws.services.elasticache;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.elasticache.container.ElastiCacheContainerHandle;
import io.floci.aws.services.elasticache.container.ElastiCacheContainerManager;
import io.floci.aws.services.elasticache.container.ValkeyClusterFormation;
import io.floci.aws.services.elasticache.model.AuthMode;
import io.floci.aws.services.elasticache.model.ReplicationGroup;
import io.floci.aws.services.elasticache.proxy.ElastiCacheProxyManager;
import io.floci.aws.services.kms.KmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * IncreaseReplicaCount, DecreaseReplicaCount and TestFailover on cluster-mode-disabled groups, whose
 * members share one backend: the member count and the member reported as primary are what change.
 */
class ElastiCacheReplicaFailoverServiceTest {

    private ElastiCacheService service;

    @BeforeEach
    void setUp() {
        ElastiCacheContainerManager containerManager = mock(ElastiCacheContainerManager.class);
        ElastiCacheProxyManager proxyManager = mock(ElastiCacheProxyManager.class);
        StorageFactory storageFactory = mock(StorageFactory.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ElastiCacheServiceConfig ecConfig = mock(EmulatorConfig.ElastiCacheServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.elasticache()).thenReturn(ecConfig);
        when(ecConfig.proxyBasePort()).thenReturn(16379);
        when(ecConfig.proxyMaxPort()).thenReturn(16399);
        when(ecConfig.defaultImage()).thenReturn("valkey/valkey:8");
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(inv -> AccountAwareStorageBackend.inMemory("000000000000"));
        when(containerManager.tryStart(anyString(), anyString(), any())).thenAnswer(inv ->
                new ElastiCacheContainerHandle("container-" + inv.getArgument(0),
                        inv.getArgument(0), "localhost", 6379));
        doNothing().when(proxyManager).startProxy(anyString(), any(), anyInt(), anyString(), anyInt(), any());
        KmsService kmsService = mock(KmsService.class);
        when(kmsService.describeKey(any(), any())).thenThrow(new AwsException("NotFoundException", "Key not found", 404));
        service = new ElastiCacheService(containerManager, proxyManager, mock(ValkeyClusterFormation.class),
                storageFactory, config, mock(Ec2Service.class), new RegionResolver("us-east-1", "000000000000"),
                kmsService, new ElastiCacheProvisioningIds());
    }

    @Test
    void replicasAreAddedAndRemovedAsMemberCacheClusters() {
        group("cache", 2, false);

        service.changeReplicaCount("cache", 4, true);
        assertEquals(List.of("cache-001", "cache-002", "cache-003", "cache-004", "cache-005"), memberIds("cache"));

        service.changeReplicaCount("cache", 0, false);
        assertEquals(List.of("cache-001"), memberIds("cache"));
    }

    @Test
    void aCountThatDoesNotMoveTheRightWayOrLeavesTheRangeIsRefused() {
        group("cache", 2, false);

        assertEquals("InvalidParameterValue", fault(() -> service.changeReplicaCount("cache", 1, true)));
        assertEquals("InvalidParameterValue", fault(() -> service.changeReplicaCount("cache", 2, false)));
        assertEquals("InvalidParameterValue", fault(() -> service.changeReplicaCount("cache", 6, true)));
        assertEquals("InvalidParameterValue", fault(() -> service.changeReplicaCount("cache", null, true)));
        assertEquals("ReplicationGroupNotFoundFault", fault(() -> service.changeReplicaCount("nope", 2, true)));
        assertEquals(2, service.getReplicationGroup("cache").getNumCacheClusters());
    }

    @Test
    void automaticFailoverKeepsAReplica() {
        group("cache", 2, true);

        assertEquals("InvalidParameterCombination", fault(() -> service.changeReplicaCount("cache", 0, false)));
        assertEquals(2, service.getReplicationGroup("cache").getNumCacheClusters());
    }

    @Test
    void failoverMovesThePrimaryToTheNextMemberAndBackRound() {
        group("cache", 3, true);

        service.testFailover("cache", "0001");
        assertEquals("cache-002", primaryId("cache"));
        service.testFailover("cache", "0001");
        assertEquals("cache-003", primaryId("cache"));
        service.testFailover("cache", "0001");
        assertEquals("cache-001", primaryId("cache"));
    }

    @Test
    void aDecreaseThatRemovesThePrimaryHandsTheRoleToTheFirstMember() {
        group("cache", 3, true);
        service.testFailover("cache", "0001");
        service.testFailover("cache", "0001");

        service.changeReplicaCount("cache", 1, false);

        assertEquals("cache-001", primaryId("cache"));
    }

    @Test
    void failoverNeedsAutomaticFailoverAReplicaAndNodeGroup0001() {
        group("manual", 2, false);
        group("lonely", 1, false);
        group("cache", 2, true);

        assertEquals("InvalidReplicationGroupState", fault(() -> service.testFailover("manual", "0001")));
        assertEquals("InvalidReplicationGroupState", fault(() -> service.testFailover("lonely", "0001")));
        assertEquals("NodeGroupNotFoundFault", fault(() -> service.testFailover("cache", "0002")));
        assertEquals("InvalidParameterValue", fault(() -> service.testFailover("cache", null)));
        assertEquals("cache-001", primaryId("cache"));
    }

    private void group(String id, int numCacheClusters, boolean automaticFailover) {
        service.createReplicationGroup(new ElastiCacheService.CreateReplicationGroupRequest(id, "group " + id,
                AuthMode.NO_AUTH, null, "us-east-1", null, null, null, null, null, null, null, null,
                numCacheClusters, automaticFailover, null, null, null, Map.of(), null, null));
    }

    private List<String> memberIds(String groupId) {
        ReplicationGroup group = service.getReplicationGroup(groupId);
        return service.memberCacheClusters(group).stream()
                .map(ElastiCacheService.MemberCacheCluster::cacheClusterId).toList();
    }

    private String primaryId(String groupId) {
        ReplicationGroup group = service.getReplicationGroup(groupId);
        return service.memberCacheClusters(group).stream().filter(ElastiCacheService.MemberCacheCluster::primary)
                .map(ElastiCacheService.MemberCacheCluster::cacheClusterId).findFirst().orElseThrow();
    }

    private static String fault(Runnable call) {
        return assertThrows(AwsException.class, call::run).getErrorCode();
    }
}
