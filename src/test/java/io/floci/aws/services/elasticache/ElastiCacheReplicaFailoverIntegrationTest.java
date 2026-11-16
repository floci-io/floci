package io.floci.aws.services.elasticache;

import io.floci.aws.services.elasticache.container.ElastiCacheContainerHandle;
import io.floci.aws.services.elasticache.container.ElastiCacheContainerManager;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.ElastiCacheException;
import software.amazon.awssdk.services.elasticache.model.NodeGroupMember;
import software.amazon.awssdk.services.elasticache.model.ReplicationGroup;
import software.amazon.awssdk.services.elasticache.model.ReplicationGroupNotFoundException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** IncreaseReplicaCount, DecreaseReplicaCount and TestFailover through the SDK, on a cluster-mode-disabled group. */
@QuarkusTest
class ElastiCacheReplicaFailoverIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @InjectMock
    ElastiCacheContainerManager containerManager;

    @BeforeEach
    void stubOnlyBackendProvisioning() {
        when(containerManager.tryStart(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> new ElastiCacheContainerHandle("stub-" + invocation.getArgument(0),
                        invocation.getArgument(0), "127.0.0.1", 16379));
    }

    @Test
    void replicasAndTheFailedOverPrimaryAreWhatEveryDescribeReports() {
        String groupId = "replicas-" + UUID.randomUUID().toString().substring(0, 8);
        try (ElastiCacheClient client = AwsTestClients.create(ElastiCacheClient.builder(), "000000000000",
                Region.US_EAST_1, endpoint)) {
            try {
                client.createReplicationGroup(request -> request.replicationGroupId(groupId)
                        .replicationGroupDescription("replicas and failover").engine("valkey")
                        .numCacheClusters(2).automaticFailoverEnabled(true).multiAZEnabled(true));

                ReplicationGroup increased = client.increaseReplicaCount(request -> request
                        .replicationGroupId(groupId).newReplicaCount(3).applyImmediately(true)).replicationGroup();
                assertEquals(List.of(groupId + "-001", groupId + "-002", groupId + "-003", groupId + "-004"),
                        increased.memberClusters());

                ReplicationGroup failedOver = client.testFailover(request -> request
                        .replicationGroupId(groupId).nodeGroupId("0001")).replicationGroup();
                assertEquals(groupId + "-002", primary(failedOver));

                ReplicationGroup decreased = client.decreaseReplicaCount(request -> request
                        .replicationGroupId(groupId).newReplicaCount(1).applyImmediately(true)).replicationGroup();
                assertEquals(List.of(groupId + "-001", groupId + "-002"), decreased.memberClusters());

                ReplicationGroup described = client.describeReplicationGroups(request -> request
                        .replicationGroupId(groupId)).replicationGroups().getFirst();
                assertEquals(groupId + "-002", primary(described));
                assertEquals(1, client.describeCacheClusters(request -> request
                        .cacheClusterId(groupId + "-002")).cacheClusters().size());

                assertEquals("InvalidParameterValue", assertThrows(ElastiCacheException.class,
                        () -> client.decreaseReplicaCount(request -> request.replicationGroupId(groupId)
                                .newReplicaCount(0).applyImmediately(true))).awsErrorDetails().errorCode());
                assertEquals("InvalidParameterValue", assertThrows(ElastiCacheException.class,
                        () -> client.increaseReplicaCount(request -> request.replicationGroupId(groupId)
                                .newReplicaCount(2).applyImmediately(false))).awsErrorDetails().errorCode());
            } finally {
                try {
                    client.deleteReplicationGroup(request -> request.replicationGroupId(groupId));
                } catch (ReplicationGroupNotFoundException expected) {
                    // A failed create may not have stored the group; there is then nothing to clean up.
                }
            }
        }
    }

    private static String primary(ReplicationGroup group) {
        return group.nodeGroups().getFirst().nodeGroupMembers().stream()
                .filter(member -> "primary".equals(member.currentRole()))
                .map(NodeGroupMember::cacheClusterId).findFirst().orElseThrow();
    }
}
