package io.floci.aws.services.elasticache;

import io.floci.aws.services.elasticache.container.ElastiCacheContainerHandle;
import io.floci.aws.services.elasticache.container.ElastiCacheContainerManager;
import io.floci.aws.services.elasticache.container.ValkeyClusterFormation;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.CacheCluster;
import software.amazon.awssdk.services.elasticache.model.InvalidParameterCombinationException;
import software.amazon.awssdk.services.elasticache.model.InvalidParameterValueException;
import software.amazon.awssdk.services.elasticache.model.ReplicationGroup;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class ElastiCacheTransitModeIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @InjectMock
    ElastiCacheContainerManager containers;

    @InjectMock
    ValkeyClusterFormation formation;

    @BeforeEach
    void stubDataPlaneProvisioning() {
        when(containers.tryStart(anyString(), anyString(), anyString())).thenAnswer(call ->
                new ElastiCacheContainerHandle("stub", call.getArgument(0), "127.0.0.1", 16379));
        when(containers.start(anyString(), anyString(), anyList(), anyString())).thenAnswer(call ->
                new ElastiCacheContainerHandle("stub", call.getArgument(0), "127.0.0.1", 16379));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createDescribeAndModifyRoundTripModeOnTheGroupAndMembers(boolean clusterMode) {
        String id = "transit-" + UUID.randomUUID().toString().substring(0, 8);
        try (ElastiCacheClient client = client()) {
            try {
                ReplicationGroup created = client.createReplicationGroup(request -> request.replicationGroupId(id)
                        .replicationGroupDescription("transit metadata").engine("valkey")
                        .clusterMode(clusterMode ? "enabled" : "disabled")
                        .numNodeGroups(clusterMode ? 2 : 1).replicasPerNodeGroup(0)
                        .transitEncryptionEnabled(true).transitEncryptionMode("preferred")
                        .securityGroupIds("sg-0123456789abcdef0")).replicationGroup();
                assertEquals("preferred", created.transitEncryptionModeAsString());
                assertMode(client, id, "preferred");
                assertEquals("required", client.modifyReplicationGroup(request -> request.replicationGroupId(id)
                        .transitEncryptionMode("required").applyImmediately(true))
                        .replicationGroup().transitEncryptionModeAsString());
                assertMode(client, id, "required");
                client.modifyReplicationGroup(request -> request.replicationGroupId(id).replicationGroupDescription("kept"));
                assertMode(client, id, "required");
                assertThrows(InvalidParameterValueException.class, () -> client.modifyReplicationGroup(request ->
                        request.replicationGroupId(id).transitEncryptionMode("invalid")));
                assertMode(client, id, "required");
            } finally {
                client.deleteReplicationGroup(request -> request.replicationGroupId(id));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enablingEncryptionRequiresPreferredBeforeRequired(boolean clusterMode) {
        String id = "transit-" + UUID.randomUUID().toString().substring(0, 8);
        try (ElastiCacheClient client = client()) {
            client.createReplicationGroup(request -> request.replicationGroupId(id)
                    .replicationGroupDescription("enable transit encryption").engine("valkey")
                    .clusterMode(clusterMode ? "enabled" : "disabled")
                    .numNodeGroups(clusterMode ? 2 : 1).replicasPerNodeGroup(0)
                    .transitEncryptionEnabled(false).securityGroupIds("sg-0123456789abcdef0"));
            try {
                assertThrows(InvalidParameterCombinationException.class, () -> client.modifyReplicationGroup(request ->
                        request.replicationGroupId(id).transitEncryptionMode("preferred")));
                assertThrows(InvalidParameterCombinationException.class, () -> client.modifyReplicationGroup(request ->
                        request.replicationGroupId(id).transitEncryptionEnabled(true).transitEncryptionMode("required")));
                assertMode(client, id, null);
                assertFalse(client.describeReplicationGroups(request -> request.replicationGroupId(id))
                        .replicationGroups().getFirst().transitEncryptionEnabled());

                ReplicationGroup modified = client.modifyReplicationGroup(request -> request.replicationGroupId(id)
                        .transitEncryptionEnabled(true).transitEncryptionMode("preferred").applyImmediately(true))
                        .replicationGroup();
                assertTrue(modified.transitEncryptionEnabled());
                assertEquals("preferred", modified.transitEncryptionModeAsString());
                assertMode(client, id, "preferred");
                client.modifyReplicationGroup(request -> request.replicationGroupId(id)
                        .transitEncryptionMode("required").applyImmediately(true));
                assertMode(client, id, "required");
                client.modifyReplicationGroup(request -> request.replicationGroupId(id).replicationGroupDescription("kept"));
                assertMode(client, id, "required");
            } finally {
                client.deleteReplicationGroup(request -> request.replicationGroupId(id));
            }
        }
    }

    private void assertMode(ElastiCacheClient client, String id, String mode) {
        ReplicationGroup group = client.describeReplicationGroups(request -> request.replicationGroupId(id))
                .replicationGroups().getFirst();
        assertEquals(mode, group.transitEncryptionModeAsString());
        assertEquals(mode != null, group.transitEncryptionEnabled());
        for (String member : group.memberClusters()) {
            CacheCluster cluster = client.describeCacheClusters(
                    request -> request.cacheClusterId(member)).cacheClusters().getFirst();
            assertEquals(mode, cluster.transitEncryptionModeAsString());
            assertEquals(mode != null, cluster.transitEncryptionEnabled());
            assertEquals(List.of("sg-0123456789abcdef0"), cluster.securityGroups().stream()
                    .map(securityGroup -> securityGroup.securityGroupId()).toList());
        }
    }

    private ElastiCacheClient client() {
        return ElastiCacheClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }
}
