package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.waiters.WaiterResponse;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.*;
import software.amazon.awssdk.services.eks.waiters.EksWaiter;
import software.amazon.awssdk.services.iam.IamClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class EksAccessEntryTest {
    @Test
    void sdkRoundTripPaginationAndClusterCleanup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String cluster = "access-" + suffix;
        String firstRole = "access-a-" + suffix;
        String secondRole = "access-b-" + suffix;
        try (EksClient eks = TestFixtures.eksClient(); IamClient iam = TestFixtures.iamClient()) {
            String first = iam.createRole(builder -> builder.roleName(firstRole).path("/nodes/")
                    .assumeRolePolicyDocument("{}")).role().arn();
            String second = iam.createRole(builder -> builder.roleName(secondRole)
                    .assumeRolePolicyDocument("{}")).role().arn();
            try {
                createCluster(eks, cluster, first);
                CreateAccessEntryRequest request = CreateAccessEntryRequest.builder().clusterName(cluster)
                        .principalArn(first).type("EC2_LINUX").tags(Map.of("team", "platform"))
                        .clientRequestToken("sdk-retry").build();
                AccessEntry entry = eks.createAccessEntry(request).accessEntry();
                assertThat(entry.username()).isEqualTo("system:node:{{EC2PrivateDNSName}}");
                assertThat(entry.kubernetesGroups()).containsExactly("system:nodes");
                assertThat(entry.createdAt()).isNotNull();
                assertThat(entry.tags()).containsEntry("team", "platform");
                assertThat(eks.createAccessEntry(request).accessEntry().accessEntryArn()).isEqualTo(entry.accessEntryArn());
                assertThat(eks.describeAccessEntry(builder -> builder.clusterName(cluster).principalArn(first))
                        .accessEntry().principalArn()).isEqualTo(first);
                eks.createAccessEntry(builder -> builder.clusterName(cluster).principalArn(second));
                ListAccessEntriesResponse page = eks.listAccessEntries(builder -> builder.clusterName(cluster).maxResults(1));
                assertThat(page.accessEntries()).hasSize(1);
                assertThat(page.nextToken()).isNotBlank();
                ListAccessEntriesResponse next = eks.listAccessEntries(builder -> builder.clusterName(cluster)
                        .maxResults(1).nextToken(page.nextToken()));
                assertThat(next.accessEntries()).hasSize(1).doesNotContain(page.accessEntries().get(0));
                assertThat(next.nextToken()).isNull();
                eks.deleteAccessEntry(builder -> builder.clusterName(cluster).principalArn(first));
                assertThatThrownBy(() -> eks.describeAccessEntry(builder -> builder.clusterName(cluster).principalArn(first)))
                        .isInstanceOf(ResourceNotFoundException.class);
                eks.deleteCluster(builder -> builder.name(cluster));
                createCluster(eks, cluster, first);
                assertThat(eks.listAccessEntries(builder -> builder.clusterName(cluster)).accessEntries()).isEmpty();
            } finally {
                eks.deleteCluster(builder -> builder.name(cluster));
                iam.deleteRole(builder -> builder.roleName(firstRole));
                iam.deleteRole(builder -> builder.roleName(secondRole));
            }
        }
    }

    private static void createCluster(EksClient eks, String name, String role) {
        eks.createCluster(builder -> builder.name(name).roleArn(role)
                .resourcesVpcConfig(VpcConfigRequest.builder().build())
                .accessConfig(CreateAccessConfigRequest.builder().authenticationMode(AuthenticationMode.API)
                        .bootstrapClusterCreatorAdminPermissions(false).build()));
        try (EksWaiter waiter = eks.waiter()) {
            WaiterResponse<DescribeClusterResponse> result = waiter.waitUntilClusterActive(
                    request -> request.name(name),
                    configuration -> configuration.waitTimeout(Duration.ofMinutes(3)));
            assertThat(result.matched().response())
                    .withFailMessage("Cluster %s did not become ACTIVE: %s", name, result.matched().exception())
                    .isPresent();
        }
    }
}
