package io.floci.aws.services.eks;

import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.testing.AlbFixtures;
import io.floci.aws.testing.QueryRequests;
import io.floci.aws.testing.TestAccounts;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EksRequests.eks;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A managed node group's Auto Scaling group, the one {@code DescribeNodegroup} names in
 * {@code resources.autoScalingGroups}, exists in Auto Scaling with the cluster's node in it. Attaching it to a target
 * group, as Terraform's {@code aws_autoscaling_attachment} does, registers the node there, and deleting the node
 * group deletes the group and deregisters the node.
 */
@QuarkusTest
class EksNodegroupAutoScalingGroupIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String SUBNET = "subnet-0123456789abcdef0";
    private static final String GROUP =
            "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member";

    @Inject
    EksService eksService;

    @Inject
    EksClusterManager clusterManager;

    private final String account = TestAccounts.randomId();

    @Test
    void attachingTheNodeGroupsAutoScalingGroupToATargetGroupRegistersTheClusterNode() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = "ng-asg-" + suffix;
        Cluster cluster = null;
        String targetGroupArn = null;
        try {
            eks(account, REGION).body(Map.of("name", clusterName, "roleArn", "arn:aws:iam::" + account + ":role/cluster"))
                    .post("/clusters").then().statusCode(200);
            cluster = eksService.findAuthenticationCluster(account, REGION, clusterName).orElseThrow();
            // EKS runs in mock mode here, so the node is registered as the k3s container's start registers it.
            clusterManager.registerClusterNodeInstance(cluster, "mock-container-id");
            String nodeId = clusterManager.getRegisteredClusterNodeInstance(cluster).getInstanceId();

            String groupName = eks(account, REGION).body(Map.of(
                            "nodegroupName", "workers",
                            "nodeRole", "arn:aws:iam::" + account + ":role/node",
                            "subnets", List.of(SUBNET),
                            "scalingConfig", Map.of("minSize", 1, "maxSize", 3, "desiredSize", 2)))
                    .post("/clusters/" + clusterName + "/node-groups").then().statusCode(200)
                    // Floci's record that it created the group is not part of the node group AWS describes.
                    .body("nodegroup", not(hasKey("autoScalingGroupCreated")))
                    .extract().path("nodegroup.resources.autoScalingGroups[0].name");
            eks(account, REGION).get("/clusters/" + clusterName + "/node-groups/workers").then().statusCode(200)
                    .body("nodegroup", not(hasKey("autoScalingGroupCreated")));

            XmlPath described = describeGroup(groupName);
            assertEquals(List.of(groupName), described.getList(GROUP + ".AutoScalingGroupName", String.class));
            assertEquals("1", described.getString(GROUP + ".MinSize"));
            assertEquals("3", described.getString(GROUP + ".MaxSize"));
            assertEquals("2", described.getString(GROUP + ".DesiredCapacity"));
            assertEquals(SUBNET, described.getString(GROUP + ".VPCZoneIdentifier"));
            assertEquals(List.of(nodeId), described.getList(GROUP + ".Instances.member.InstanceId", String.class));
            assertEquals("InService", described.getString(GROUP + ".Instances.member.LifecycleState"));
            assertEquals("Healthy", described.getString(GROUP + ".Instances.member.HealthStatus"));
            assertEquals(Map.of(
                    "eks:cluster-name", clusterName,
                    "eks:nodegroup-name", "workers",
                    "k8s.io/cluster-autoscaler/enabled", "true",
                    "k8s.io/cluster-autoscaler/" + clusterName, "owned"), tags(described));

            targetGroupArn = AlbFixtures.createTargetGroup(account, clusterName, "instance", 80);
            query("autoscaling", "AttachLoadBalancerTargetGroups",
                    "AutoScalingGroupName", groupName, "TargetGroupARNs.member.1", targetGroupArn)
                    .post("/").then().statusCode(200);

            assertEquals(List.of(nodeId), AlbFixtures.targetIds(account, targetGroupArn));

            eks(account, REGION).delete("/clusters/" + clusterName + "/node-groups/workers").then().statusCode(200);

            assertEquals(List.of(), describeGroup(groupName).getList(GROUP + ".AutoScalingGroupName", String.class));
            assertEquals(List.of(), AlbFixtures.targetIds(account, targetGroupArn));
        } finally {
            eks(account, REGION).delete("/clusters/" + clusterName + "/node-groups/workers");
            eks(account, REGION).delete("/clusters/" + clusterName);
            AlbFixtures.deleteTargetGroup(account, targetGroupArn);
            if (cluster != null) {
                clusterManager.unregisterMetadataEndpoint(cluster);
            }
        }
    }

    private RequestSpecification query(String service, String action, String... params) {
        return QueryRequests.request(account, REGION, service, action, params);
    }

    private XmlPath describeGroup(String groupName) {
        return query("autoscaling", "DescribeAutoScalingGroups", "AutoScalingGroupNames.member.1", groupName)
                .post("/").then().statusCode(200).extract().xmlPath();
    }

    private static Map<String, String> tags(XmlPath described) {
        List<String> keys = described.getList(GROUP + ".Tags.member.Key", String.class);
        List<String> values = described.getList(GROUP + ".Tags.member.Value", String.class);
        assertEquals(List.of("true", "true", "true", "true"),
                described.getList(GROUP + ".Tags.member.PropagateAtLaunch", String.class));
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            tags.put(keys.get(i), values.get(i));
        }
        return tags;
    }
}
