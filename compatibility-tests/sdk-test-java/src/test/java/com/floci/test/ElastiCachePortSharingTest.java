package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.CreateReplicationGroupRequest;
import software.amazon.awssdk.services.elasticache.model.CreateReplicationGroupResponse;
import software.amazon.awssdk.services.elasticache.model.DeleteReplicationGroupRequest;
import software.amazon.awssdk.services.elasticache.model.DescribeCacheClustersRequest;
import software.amazon.awssdk.services.elasticache.model.DescribeCacheClustersResponse;
import software.amazon.awssdk.services.elasticache.model.DescribeReplicationGroupsRequest;
import software.amazon.awssdk.services.elasticache.model.DescribeReplicationGroupsResponse;
import software.amazon.awssdk.services.elasticache.model.Endpoint;
import software.amazon.awssdk.services.elasticache.model.ReplicationGroup;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two caches both asking for 6379, read back through the real AWS SDK.
 *
 * <p>On AWS, Port belongs to a cache's own endpoint, so 6379 is shared routinely and two
 * replication groups on it coexist. Floci puts every cache's proxy on one host, where the port is
 * the only thing that tells them apart, so the second cache is served on a port of its own and
 * that is the port it reports.
 *
 * <p>What the SDK adds over the handcrafted-HTTP tests in {@code src/test}: those assert on the
 * XML a describe returns, and a response can be well formed and still not populate the model a
 * caller actually reads. This one goes through the generated client, so it fails if the
 * substituted port does not deserialise into {@code PrimaryEndpoint.Port} on the create response,
 * on the describe, and on the member cluster the terraform provider follows.
 *
 * <p>Self-contained on purpose: its own client, its own uniquely named groups, its own cleanup. It
 * shares no fixture with another class and no ordering with one, so nothing it needs can be torn
 * down by a test that happens to run first.
 */
@DisplayName("ElastiCache port sharing")
class ElastiCachePortSharingTest {

    /** The Redis default, and the port a module pins when it does not think about it. */
    private static final int SHARED_PORT = 6379;

    private static ElastiCacheClient elasticache;
    private static String firstGroupId;
    private static String secondGroupId;

    @BeforeAll
    static void setup() {
        elasticache = TestFixtures.elastiCacheClient();
        firstGroupId = TestFixtures.uniqueName("ec-share-a");
        secondGroupId = TestFixtures.uniqueName("ec-share-b");
    }

    @AfterAll
    static void cleanup() {
        if (elasticache == null) {
            return;
        }
        for (String groupId : new String[] {firstGroupId, secondGroupId}) {
            try {
                elasticache.deleteReplicationGroup(DeleteReplicationGroupRequest.builder()
                        .replicationGroupId(groupId)
                        .build());
            } catch (Exception ignored) {
                // Best effort: a group whose create failed has nothing to delete.
            }
        }
    }

    @Test
    void bothCachesArePortedAndEachReportsThePortItIsOn() {
        int firstPort = createOnSharedPort(firstGroupId);

        // The create AWS performs and Floci used to answer InvalidParameterValue.
        int secondPort = createOnSharedPort(secondGroupId);

        assertThat(secondPort)
                .as("the second cache must be served on a port of its own, not the first's")
                .isNotEqualTo(firstPort);

        // The whole point of going through the SDK: the substituted port has to survive
        // deserialisation into the model, not merely appear in the XML.
        assertThat(describedPort(firstGroupId))
                .as("describe must report the first cache on the port its create reported")
                .isEqualTo(firstPort);
        assertThat(describedPort(secondGroupId))
                .as("describe must report the second cache on the port its create reported")
                .isEqualTo(secondPort);

        // DescribeCacheClusters is where terraform-provider-aws reads a member's port back, so
        // the two describes must agree or a plan never settles.
        assertThat(memberPort(secondGroupId))
                .as("the member cluster must agree with its group's endpoint")
                .isEqualTo(secondPort);
    }

    private static int createOnSharedPort(String groupId) {
        CreateReplicationGroupResponse response = elasticache.createReplicationGroup(CreateReplicationGroupRequest.builder()
                .replicationGroupId(groupId)
                .replicationGroupDescription("port sharing compat test")
                .engine("redis")
                .numCacheClusters(1)
                .port(SHARED_PORT)
                .build());

        assertThat(response.replicationGroup().replicationGroupId()).isEqualTo(groupId);
        Endpoint endpoint = primaryEndpoint(response.replicationGroup());
        assertThat(endpoint)
                .as("the create response must carry an endpoint the caller can dial")
                .isNotNull();
        assertThat(endpoint.port())
                .as("the port must deserialise into the model, not merely into the XML")
                .isNotNull()
                .isPositive();
        return endpoint.port();
    }

    private static int describedPort(String groupId) {
        DescribeReplicationGroupsResponse response = elasticache.describeReplicationGroups(DescribeReplicationGroupsRequest.builder()
                .replicationGroupId(groupId)
                .build());
        assertThat(response.replicationGroups()).hasSize(1);
        return primaryEndpoint(response.replicationGroups().get(0)).port();
    }

    private static int memberPort(String groupId) {
        DescribeCacheClustersResponse response = elasticache.describeCacheClusters(DescribeCacheClustersRequest.builder()
                .cacheClusterId(groupId + "-001")
                .showCacheNodeInfo(true)
                .build());
        assertThat(response.cacheClusters()).hasSize(1);
        assertThat(response.cacheClusters().get(0).cacheNodes()).isNotEmpty();
        return response.cacheClusters().get(0).cacheNodes().get(0).endpoint().port();
    }

    private static Endpoint primaryEndpoint(ReplicationGroup group) {
        return group.nodeGroups().get(0).primaryEndpoint();
    }
}
