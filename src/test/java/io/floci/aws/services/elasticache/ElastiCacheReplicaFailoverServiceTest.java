package io.floci.aws.services.elasticache;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.elasticache.model.AuthMode;
import io.floci.aws.services.elasticache.model.ReplicationGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * IncreaseReplicaCount, DecreaseReplicaCount and TestFailover on cluster-mode-disabled groups, whose
 * members share one backend: the numbered members and the member reported as primary are what change.
 */
class ElastiCacheReplicaFailoverServiceTest {

    private ElastiCacheService service;

    @BeforeEach
    void setUp() {
        service = new ElastiCacheServiceFixture().service;
    }

    @Test
    void replicasAreAddedAndRemovedAsMemberCacheClusters() {
        group("cache", 2, false, false);

        service.changeReplicaCount("cache", 4, true, true);
        assertEquals(List.of("cache-001", "cache-002", "cache-003", "cache-004", "cache-005"), memberIds("cache"));

        service.changeReplicaCount("cache", 0, true, false);
        assertEquals(List.of("cache-001"), memberIds("cache"));
        assertEquals(1, service.getReplicationGroup("cache").getNumCacheClusters());
    }

    @Test
    void aCountThatDoesNotMoveTheRightWayOrLeavesTheRangeIsRefused() {
        group("cache", 2, false, false);

        assertEquals("InvalidParameterValue", fault(() -> service.changeReplicaCount("cache", 1, true, true)));
        assertEquals("InvalidParameterValue", fault(() -> service.changeReplicaCount("cache", 2, true, false)));
        assertEquals("InvalidParameterValue", fault(() -> service.changeReplicaCount("cache", 6, true, true)));
        assertEquals("InvalidParameterValue", fault(() -> service.changeReplicaCount("cache", null, true, true)));
        assertEquals("ReplicationGroupNotFoundFault", fault(() -> service.changeReplicaCount("nope", 2, true, true)));
        assertEquals("InvalidParameterValue", fault(() -> service.changeReplicaCount(null, 3, true, true)));
        assertEquals("InvalidParameterValue", fault(() -> service.changeReplicaCount(" ", 1, true, false)));
        assertEquals(2, service.getReplicationGroup("cache").getNumCacheClusters());
    }

    @Test
    void aCountChangeMustBeAppliedImmediately() {
        group("cache", 2, false, false);

        assertEquals("InvalidParameterValue", fault(() -> service.changeReplicaCount("cache", 3, null, true)));
        assertEquals("InvalidParameterValue", fault(() -> service.changeReplicaCount("cache", 3, false, true)));
        assertEquals("InvalidParameterValue", fault(() -> service.changeReplicaCount("cache", 0, false, false)));
        assertEquals(List.of("cache-001", "cache-002"), memberIds("cache"));
    }

    @Test
    void multiAzKeepsAReplicaWhereAutomaticFailoverAloneDoesNot() {
        group("multi-az", 2, true, true);
        group("failover", 2, true, false);

        assertEquals("InvalidParameterValue", fault(() -> service.changeReplicaCount("multi-az", 0, true, false)));
        assertEquals(List.of("multi-az-001", "multi-az-002"), memberIds("multi-az"));

        service.changeReplicaCount("failover", 0, true, false);
        assertEquals(List.of("failover-001"), memberIds("failover"));
    }

    @Test
    void failoverMovesThePrimaryToTheNextMemberAndBackRound() {
        group("cache", 3, true, false);

        service.testFailover("cache", "0001");
        assertEquals("cache-002", primaryId("cache"));
        service.testFailover("cache", "0001");
        assertEquals("cache-003", primaryId("cache"));
        service.testFailover("cache", "0001");
        assertEquals("cache-001", primaryId("cache"));
    }

    @Test
    void aDecreaseRemovesReplicasAndKeepsThePrimary() {
        group("cache", 3, true, false);
        service.testFailover("cache", "0001");
        service.testFailover("cache", "0001");

        service.changeReplicaCount("cache", 1, true, false);

        assertEquals(List.of("cache-001", "cache-003"), memberIds("cache"));
        assertEquals("cache-003", primaryId("cache"));
    }

    @Test
    void newReplicasTakeTheNextNumbersAndFailoverFollowsTheMembers() {
        group("cache", 3, true, false);
        service.testFailover("cache", "0001");
        service.testFailover("cache", "0001");
        service.changeReplicaCount("cache", 1, true, false);

        service.changeReplicaCount("cache", 3, true, true);
        assertEquals(List.of("cache-001", "cache-003", "cache-004", "cache-005"), memberIds("cache"));
        assertEquals("cache-003", primaryId("cache"));

        service.testFailover("cache", "0001");
        assertEquals("cache-004", primaryId("cache"));
        service.testFailover("cache", "0001");
        service.testFailover("cache", "0001");
        assertEquals("cache-001", primaryId("cache"));
    }

    @Test
    void failoverNeedsAutomaticFailoverAReplicaAndNodeGroup0001() {
        group("manual", 2, false, false);
        group("lonely", 1, false, false);
        group("cache", 2, true, false);

        assertEquals("InvalidReplicationGroupState", fault(() -> service.testFailover("manual", "0001")));
        assertEquals("InvalidReplicationGroupState", fault(() -> service.testFailover("lonely", "0001")));
        assertEquals("NodeGroupNotFoundFault", fault(() -> service.testFailover("cache", "0002")));
        assertEquals("InvalidParameterValue", fault(() -> service.testFailover("cache", null)));
        assertEquals("InvalidParameterValue", fault(() -> service.testFailover(null, "0001")));
        assertEquals("cache-001", primaryId("cache"));
    }

    private void group(String id, int numCacheClusters, boolean automaticFailover, boolean multiAz) {
        service.createReplicationGroup(new ElastiCacheService.CreateReplicationGroupRequest(id, "group " + id,
                AuthMode.NO_AUTH, null, "us-east-1", null, null, null, null, null, null, null, null,
                numCacheClusters, automaticFailover, multiAz, null, null, Map.of(), null, null));
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
