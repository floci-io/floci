package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerHandle;
import io.github.hectorvent.floci.services.elasticache.container.ElastiCacheContainerManager;
import io.github.hectorvent.floci.services.elasticache.container.ValkeyClusterFormation;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.CacheCluster;
import software.amazon.awssdk.services.elasticache.model.CreateReplicationGroupRequest;
import software.amazon.awssdk.services.elasticache.model.ReplicationGroupNotFoundException;
import software.amazon.awssdk.services.elasticache.model.SecurityGroupMembership;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class ElastiCacheMemberSecurityGroupsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20261018/us-east-1/elasticache/aws4_request";
    private static final List<String> SECURITY_GROUP_IDS = List.of("sg-0123456789abcdef0", "sg-0fedcba9876543210");

    @TestHTTPResource
    URI endpoint;

    @InjectMock
    ElastiCacheContainerManager containerManager;

    // Cluster formation talks to the backing Valkey nodes, which are not started by these control-plane tests.
    @InjectMock
    ValkeyClusterFormation clusterFormation;

    @BeforeEach
    void stubOnlyBackendProvisioning() {
        when(containerManager.tryStart(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> containerHandle(invocation.getArgument(0)));
        when(containerManager.start(anyString(), anyString(), anyList(), anyString()))
                .thenAnswer(invocation -> containerHandle(invocation.getArgument(0)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sdkCreatePreservesSecurityGroupsOnEveryMember(boolean clusterEnabled) {
        String groupId = groupId();
        try (ElastiCacheClient client = client()) {
            try {
                client.createReplicationGroup(createRequest(groupId, clusterEnabled)
                        .securityGroupIds(SECURITY_GROUP_IDS).build());

                assertMemberSecurityGroups(client, groupId, clusterEnabled ? 4 : 1, SECURITY_GROUP_IDS);
            } finally {
                deleteGroup(client, groupId);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void omittedSecurityGroupsDoNotFabricateMemberships(boolean clusterEnabled) {
        String groupId = groupId();
        try (ElastiCacheClient client = client()) {
            try {
                client.createReplicationGroup(createRequest(groupId, clusterEnabled).build());

                assertMemberSecurityGroups(client, groupId, clusterEnabled ? 4 : 1, List.of());
            } finally {
                deleteGroup(client, groupId);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SecurityGroupIds.SecurityGroupId", "SecurityGroupIds.member"})
    void queryListSpellingsRoundtripThroughTypedSdkMembers(String prefix) {
        String groupId = groupId();
        try (ElastiCacheClient client = client()) {
            try {
                given()
                        .port(endpoint.getPort())
                        .header("Authorization", AUTH_HEADER)
                        .formParam("Action", "CreateReplicationGroup")
                        .formParam("Version", "2015-02-02")
                        .formParam("ReplicationGroupId", groupId)
                        .formParam("ReplicationGroupDescription", "member security groups")
                        .formParam("Engine", "valkey")
                        .formParam("NumCacheClusters", "2")
                        .formParam(prefix + ".1", SECURITY_GROUP_IDS.getFirst())
                        .formParam(prefix + ".2", SECURITY_GROUP_IDS.getLast())
                .when().post("/")
                .then().statusCode(200);

                assertMemberSecurityGroups(client, groupId, 2, SECURITY_GROUP_IDS);
            } finally {
                deleteGroup(client, groupId);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sdkModifyReplacesSecurityGroupsOnEveryMember(boolean clusterEnabled) {
        String groupId = groupId();
        try (ElastiCacheClient client = client()) {
            try {
                client.createReplicationGroup(createRequest(groupId, clusterEnabled)
                        .securityGroupIds(SECURITY_GROUP_IDS).build());

                client.modifyReplicationGroup(request -> request.replicationGroupId(groupId)
                        .securityGroupIds(List.of("sg-modified")));

                assertMemberSecurityGroups(client, groupId, clusterEnabled ? 4 : 1, List.of("sg-modified"));
            } finally {
                deleteGroup(client, groupId);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modifyThatNamesNoSecurityGroupsKeepsTheMemberships(boolean clusterEnabled) {
        String groupId = groupId();
        try (ElastiCacheClient client = client()) {
            try {
                client.createReplicationGroup(createRequest(groupId, clusterEnabled)
                        .securityGroupIds(SECURITY_GROUP_IDS).build());

                client.modifyReplicationGroup(request -> request.replicationGroupId(groupId));

                assertMemberSecurityGroups(client, groupId, clusterEnabled ? 4 : 1, SECURITY_GROUP_IDS);
            } finally {
                deleteGroup(client, groupId);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SecurityGroupIds.SecurityGroupId", "SecurityGroupIds.member"})
    void queryModifyReplacesTheWholeListOnEveryMember(String prefix) {
        String groupId = groupId();
        try (ElastiCacheClient client = client()) {
            try {
                client.createReplicationGroup(createRequest(groupId, false)
                        .securityGroupIds(SECURITY_GROUP_IDS).build());

                given()
                        .port(endpoint.getPort())
                        .header("Authorization", AUTH_HEADER)
                        .formParam("Action", "ModifyReplicationGroup")
                        .formParam("Version", "2015-02-02")
                        .formParam("ReplicationGroupId", groupId)
                        .formParam(prefix + ".1", "sg-replaced")
                .when().post("/")
                .then().statusCode(200);

                assertMemberSecurityGroups(client, groupId, 1, List.of("sg-replaced"));
            } finally {
                deleteGroup(client, groupId);
            }
        }
    }

    private ElastiCacheClient client() {
        return ElastiCacheClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static CreateReplicationGroupRequest.Builder createRequest(String groupId, boolean clusterEnabled) {
        CreateReplicationGroupRequest.Builder request = CreateReplicationGroupRequest.builder()
                .replicationGroupId(groupId).replicationGroupDescription("member security groups")
                .engine("valkey").clusterMode(clusterEnabled ? "enabled" : "disabled");
        if (clusterEnabled) {
            request.numNodeGroups(2).replicasPerNodeGroup(1).automaticFailoverEnabled(true);
        }
        return request;
    }

    private static void assertMemberSecurityGroups(ElastiCacheClient client, String groupId,
                                                   int expectedMembers, List<String> expectedSecurityGroupIds) {
        List<String> memberIds = client.describeReplicationGroups(request -> request.replicationGroupId(groupId))
                .replicationGroups().getFirst().memberClusters();
        assertEquals(expectedMembers, memberIds.size());
        for (String memberId : memberIds) {
            List<CacheCluster> clusters = client.describeCacheClusters(request -> request
                    .cacheClusterId(memberId).showCacheNodeInfo(true)).cacheClusters();
            assertEquals(1, clusters.size(), memberId);
            assertEquals(groupId, clusters.getFirst().replicationGroupId());
            assertSecurityGroups(clusters.getFirst(), expectedSecurityGroupIds);
        }

        List<CacheCluster> listedMembers = client.describeCacheClusters().cacheClusters().stream()
                .filter(cluster -> groupId.equals(cluster.replicationGroupId())).toList();
        assertEquals(expectedMembers, listedMembers.size());
        for (CacheCluster cluster : listedMembers) {
            assertSecurityGroups(cluster, expectedSecurityGroupIds);
        }
    }

    private static void assertSecurityGroups(CacheCluster cluster, List<String> expectedIds) {
        assertEquals(expectedIds, cluster.securityGroups().stream()
                .map(SecurityGroupMembership::securityGroupId).toList(), cluster.cacheClusterId());
        for (SecurityGroupMembership membership : cluster.securityGroups()) {
            assertEquals("active", membership.status(), cluster.cacheClusterId());
        }
    }

    private static ElastiCacheContainerHandle containerHandle(String memberId) {
        return new ElastiCacheContainerHandle("stub-" + memberId, memberId, "127.0.0.1", 16379);
    }

    private static String groupId() {
        return "sg-members-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void deleteGroup(ElastiCacheClient client, String groupId) {
        try {
            client.deleteReplicationGroup(request -> request.replicationGroupId(groupId));
        } catch (ReplicationGroupNotFoundException expected) {
            // A failed create may not have stored the group; there is then nothing to clean up.
        }
    }
}
