package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EKS Elastic Kubernetes Service")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksTest {

    private static EksClient eks;
    private static String clusterName;
    private static String clusterArn;
    private static String nodegroupName;
    private static String fargateProfileName;

    @BeforeAll
    static void setup() {
        eks = TestFixtures.eksClient();
        clusterName = "sdk-test-cluster-" + (System.currentTimeMillis() % 100000);
    }

    @AfterAll
    static void cleanup() {
        if (eks != null) {
            if (clusterName != null) {
                if (nodegroupName != null) {
                    try {
                        eks.deleteNodegroup(DeleteNodegroupRequest.builder()
                                .clusterName(clusterName)
                                .nodegroupName(nodegroupName)
                                .build());
                    } catch (Exception ignored) {}
                }
                if (fargateProfileName != null) {
                    try {
                        eks.deleteFargateProfile(DeleteFargateProfileRequest.builder()
                                .clusterName(clusterName)
                                .fargateProfileName(fargateProfileName)
                                .build());
                    } catch (Exception ignored) {}
                }
            }
            try {
                eks.deleteCluster(DeleteClusterRequest.builder()
                        .name(clusterName)
                        .build());
            } catch (Exception ignored) {}
            eks.close();
        }
    }

    @Test
    @Order(1)
    void createCluster() {
        CreateClusterResponse response = eks.createCluster(CreateClusterRequest.builder()
                .name(clusterName)
                .roleArn("arn:aws:iam::000000000000:role/eks-role")
                .resourcesVpcConfig(VpcConfigRequest.builder()
                        .subnetIds(List.of())
                        .securityGroupIds(List.of())
                        .build())
                .version("1.29")
                .tags(Map.of("env", "test"))
                .build());

        assertThat(response.cluster()).isNotNull();
        assertThat(response.cluster().name()).isEqualTo(clusterName);
        assertThat(response.cluster().arn()).isNotBlank();
        assertThat(response.cluster().version()).isEqualTo("1.29");
        assertThat(response.cluster().status()).isIn(ClusterStatus.CREATING, ClusterStatus.ACTIVE);

        clusterArn = response.cluster().arn();
    }

    @Test
    @Order(2)
    void listClusters() {
        ListClustersResponse response = eks.listClusters(ListClustersRequest.builder().build());

        assertThat(response.clusters()).isNotNull();
        assertThat(response.clusters()).contains(clusterName);
    }

    @Test
    @Order(3)
    void describeCluster() {
        DescribeClusterResponse response = eks.describeCluster(DescribeClusterRequest.builder()
                .name(clusterName)
                .build());

        assertThat(response.cluster()).isNotNull();
        assertThat(response.cluster().name()).isEqualTo(clusterName);
        assertThat(response.cluster().arn()).isEqualTo(clusterArn);
        assertThat(response.cluster().status()).isIn(ClusterStatus.CREATING, ClusterStatus.ACTIVE);
        assertThat(response.cluster().resourcesVpcConfig()).isNotNull();
        assertThat(response.cluster().kubernetesNetworkConfig()).isNotNull();
        assertThat(response.cluster().certificateAuthority()).isNotNull();
    }

    @Test
    @Order(4)
    void describeClusterNotFound() {
        assertThatThrownBy(() -> eks.describeCluster(DescribeClusterRequest.builder()
                        .name("nonexistent-cluster-xyz")
                        .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(5)
    void tagResource() {
        eks.tagResource(TagResourceRequest.builder()
                .resourceArn(clusterArn)
                .tags(Map.of("team", "platform", "cost-center", "eng"))
                .build());

        // Verify tags are stored
        ListTagsForResourceResponse listResponse = eks.listTagsForResource(
                ListTagsForResourceRequest.builder()
                        .resourceArn(clusterArn)
                        .build());

        assertThat(listResponse.tags()).containsEntry("team", "platform");
        assertThat(listResponse.tags()).containsEntry("cost-center", "eng");
        assertThat(listResponse.tags()).containsEntry("env", "test");
    }

    @Test
    @Order(6)
    void untagResource() {
        eks.untagResource(UntagResourceRequest.builder()
                .resourceArn(clusterArn)
                .tagKeys("env")
                .build());

        ListTagsForResourceResponse listResponse = eks.listTagsForResource(
                ListTagsForResourceRequest.builder()
                        .resourceArn(clusterArn)
                        .build());

        assertThat(listResponse.tags()).doesNotContainKey("env");
        assertThat(listResponse.tags()).containsKey("team");
    }

    @Test
    @Order(7)
    void createDuplicateClusterFails() {
        assertThatThrownBy(() -> eks.createCluster(CreateClusterRequest.builder()
                        .name(clusterName)
                        .roleArn("arn:aws:iam::000000000000:role/eks-role")
                        .resourcesVpcConfig(VpcConfigRequest.builder().build())
                        .build()))
                .isInstanceOf(ResourceInUseException.class);
    }

    // ──────────────────────────── Managed node groups (#1137) ────────────────────────────

    private static final String NODEGROUP = "sdk-ng";

    @Test
    @Order(10)
    void createNodeGroup() {
        CreateNodegroupResponse response = eks.createNodegroup(CreateNodegroupRequest.builder()
                .clusterName(clusterName)
                .nodegroupName(NODEGROUP)
                .subnets("subnet-abc")
                .nodeRole("arn:aws:iam::000000000000:role/eks-node-role")
                .scalingConfig(NodegroupScalingConfig.builder()
                        .minSize(1).maxSize(3).desiredSize(2).build())
                .build());

        assertThat(response.nodegroup().nodegroupName()).isEqualTo(NODEGROUP);
        assertThat(response.nodegroup().clusterName()).isEqualTo(clusterName);
        assertThat(response.nodegroup().nodegroupArn())
                .contains("nodegroup/" + clusterName + "/" + NODEGROUP);
        assertThat(response.nodegroup().status()).isEqualTo(NodegroupStatus.ACTIVE);
        assertThat(response.nodegroup().scalingConfig().desiredSize()).isEqualTo(2);
    }

    @Test
    @Order(11)
    void listNodeGroups() {
        ListNodegroupsResponse response = eks.listNodegroups(ListNodegroupsRequest.builder()
                .clusterName(clusterName).build());
        assertThat(response.nodegroups()).contains(NODEGROUP);
    }

    @Test
    @Order(12)
    void describeNodeGroup() {
        DescribeNodegroupResponse response = eks.describeNodegroup(DescribeNodegroupRequest.builder()
                .clusterName(clusterName).nodegroupName(NODEGROUP).build());
        assertThat(response.nodegroup().nodegroupName()).isEqualTo(NODEGROUP);
        assertThat(response.nodegroup().subnets()).contains("subnet-abc");
        assertThat(response.nodegroup().amiType()).isNotNull();
    }

    @Test
    @Order(13)
    void describeMissingNodegroupFails() {
        assertThatThrownBy(() -> eks.describeNodegroup(DescribeNodegroupRequest.builder()
                        .clusterName(clusterName).nodegroupName("no-such-ng").build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(14)
    void deleteNodeGroup() {
        DeleteNodegroupResponse response = eks.deleteNodegroup(DeleteNodegroupRequest.builder()
                .clusterName(clusterName).nodegroupName(NODEGROUP).build());
        assertThat(response.nodegroup().status()).isEqualTo(NodegroupStatus.DELETING);
    }

    @Test
    @Order(8)
    void nodegroupLifecycle() {
        nodegroupName = "sdk-test-nodegroup-" + (System.currentTimeMillis() % 100000);

        CreateNodegroupResponse createResponse = eks.createNodegroup(CreateNodegroupRequest.builder()
                .clusterName(clusterName)
                .nodegroupName(nodegroupName)
                .nodeRole("arn:aws:iam::000000000000:role/eks-node-role")
                .subnets("subnet-12345678", "subnet-87654321")
                .scalingConfig(NodegroupScalingConfig.builder()
                        .minSize(1)
                        .maxSize(3)
                        .desiredSize(2)
                        .build())
                .instanceTypes("t3.medium")
                .labels(Map.of("workload", "api"))
                .tags(Map.of("env", "test"))
                .build());

        assertThat(createResponse.nodegroup()).isNotNull();
        assertThat(createResponse.nodegroup().clusterName()).isEqualTo(clusterName);
        assertThat(createResponse.nodegroup().nodegroupName()).isEqualTo(nodegroupName);
        assertThat(createResponse.nodegroup().nodegroupArn()).contains("nodegroup/" + clusterName + "/" + nodegroupName);
        assertThat(createResponse.nodegroup().status()).isEqualTo(NodegroupStatus.ACTIVE);
        assertThat(createResponse.nodegroup().scalingConfig().desiredSize()).isEqualTo(2);
        assertThat(createResponse.nodegroup().labels()).containsEntry("workload", "api");
        assertThat(createResponse.nodegroup().tags()).containsEntry("env", "test");

        ListNodegroupsResponse listResponse = eks.listNodegroups(ListNodegroupsRequest.builder()
                .clusterName(clusterName)
                .build());
        assertThat(listResponse.nodegroups()).contains(nodegroupName);

        DescribeNodegroupResponse describeResponse = eks.describeNodegroup(DescribeNodegroupRequest.builder()
                .clusterName(clusterName)
                .nodegroupName(nodegroupName)
                .build());
        assertThat(describeResponse.nodegroup().nodegroupName()).isEqualTo(nodegroupName);
        assertThat(describeResponse.nodegroup().subnets()).containsExactly("subnet-12345678", "subnet-87654321");

        DeleteNodegroupResponse deleteResponse = eks.deleteNodegroup(DeleteNodegroupRequest.builder()
                .clusterName(clusterName)
                .nodegroupName(nodegroupName)
                .build());
        assertThat(deleteResponse.nodegroup().status()).isEqualTo(NodegroupStatus.DELETING);
        assertThatThrownBy(() -> eks.describeNodegroup(DescribeNodegroupRequest.builder()
                        .clusterName(clusterName)
                        .nodegroupName(nodegroupName)
                        .build()))
                .isInstanceOf(ResourceNotFoundException.class);
        nodegroupName = null;
    }

    @Test
    @Order(9)
    void fargateProfileLifecycle() {
        fargateProfileName = "sdk-test-fargate-" + (System.currentTimeMillis() % 100000);

        CreateFargateProfileResponse createResponse = eks.createFargateProfile(CreateFargateProfileRequest.builder()
                .clusterName(clusterName)
                .fargateProfileName(fargateProfileName)
                .podExecutionRoleArn("arn:aws:iam::000000000000:role/eks-fargate-role")
                .subnets("subnet-12345678", "subnet-87654321")
                .selectors(FargateProfileSelector.builder()
                        .namespace("default")
                        .labels(Map.of("app", "api"))
                        .build())
                .tags(Map.of("env", "test"))
                .build());

        assertThat(createResponse.fargateProfile()).isNotNull();
        assertThat(createResponse.fargateProfile().clusterName()).isEqualTo(clusterName);
        assertThat(createResponse.fargateProfile().fargateProfileName()).isEqualTo(fargateProfileName);
        assertThat(createResponse.fargateProfile().fargateProfileArn())
                .matches("arn:aws:eks:[^:]+:[0-9]+:fargateprofile/" + clusterName + "/" + fargateProfileName + "/.+");
        assertThat(createResponse.fargateProfile().status()).isEqualTo(FargateProfileStatus.ACTIVE);
        assertThat(createResponse.fargateProfile().selectors()).hasSize(1);
        assertThat(createResponse.fargateProfile().selectors().get(0).labels()).containsEntry("app", "api");
        assertThat(createResponse.fargateProfile().tags()).containsEntry("env", "test");

        ListFargateProfilesResponse listResponse = eks.listFargateProfiles(ListFargateProfilesRequest.builder()
                .clusterName(clusterName)
                .build());
        assertThat(listResponse.fargateProfileNames()).contains(fargateProfileName);

        DescribeFargateProfileResponse describeResponse = eks.describeFargateProfile(
                DescribeFargateProfileRequest.builder()
                        .clusterName(clusterName)
                        .fargateProfileName(fargateProfileName)
                        .build());
        assertThat(describeResponse.fargateProfile().fargateProfileName()).isEqualTo(fargateProfileName);
        assertThat(describeResponse.fargateProfile().subnets()).containsExactly("subnet-12345678", "subnet-87654321");

        DeleteFargateProfileResponse deleteResponse = eks.deleteFargateProfile(DeleteFargateProfileRequest.builder()
                .clusterName(clusterName)
                .fargateProfileName(fargateProfileName)
                .build());
        assertThat(deleteResponse.fargateProfile().status()).isEqualTo(FargateProfileStatus.DELETING);
        assertThatThrownBy(() -> eks.describeFargateProfile(DescribeFargateProfileRequest.builder()
                        .clusterName(clusterName)
                        .fargateProfileName(fargateProfileName)
                        .build()))
                .isInstanceOf(ResourceNotFoundException.class);
        fargateProfileName = null;
    }

    @Test
    @Order(100)
    void deleteCluster() {
        DeleteClusterResponse response = eks.deleteCluster(DeleteClusterRequest.builder()
                .name(clusterName)
                .build());

        assertThat(response.cluster()).isNotNull();
        assertThat(response.cluster().name()).isEqualTo(clusterName);
        assertThat(response.cluster().status()).isEqualTo(ClusterStatus.DELETING);
    }

    @Test
    @Order(101)
    void describeDeletedClusterFails() {
        assertThatThrownBy(() -> eks.describeCluster(DescribeClusterRequest.builder()
                        .name(clusterName)
                        .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
