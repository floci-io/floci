package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ClusterStatus;
import io.floci.aws.services.eks.model.ComputeConfig;
import io.floci.aws.services.eks.model.ResourcesVpcConfig;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.InstanceProfile;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.EksTestNode;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EKS Auto Mode compute on a real k3s node: a pod that asks for the {@code general-purpose} pool's nodes, which the
 * cluster's own node isn't, is unschedulable, so a pass launches an instance for it, tagged as Auto Mode tags its
 * instances, with the node role's instance profile. Once it has joined, as an instance launched with EKS bootstrap
 * user data does, its node gets the pool's labels and the pod is scheduled on it. Turning compute off terminates
 * the instance and takes its node out, and deleting the cluster deletes the profile. Each pass is called directly,
 * one at a time with the reconciler's own interval, which makes the same choices; the join EKS makes on the
 * instance's launch, from its user data, is made here, as the instance isn't launched through a request.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class EksAutoModeNodesDockerNightlyIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksAutoModeNodesDockerNightlyIntegrationTest.class);
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @Inject
    DockerClient dockerClient;

    @Inject
    EksClusterManager eksClusterManager;

    @Inject
    EksAutoModeCompute compute;

    @Inject
    Ec2Service ec2;

    @Inject
    IamService iam;

    @Inject
    EksService eksService;

    private Cluster cluster;

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = DockerAvailability.answers(dockerClient);
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksAutoModeNodesDockerNightlyIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for the Auto Mode nodes test");
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            try {
                eksClusterManager.stopCluster(cluster);
            } catch (Exception e) {
                LOG.warnv("Failed to stop the test cluster: {0}", e.getMessage());
            }
            try {
                eksService.deleteClusterForAccount(ACCOUNT, cluster.getName());
            } catch (Exception e) {
                LOG.warnv("Failed to delete the test cluster: {0}", e.getMessage());
            }
        }
    }

    @Test
    void anUnschedulablePodGetsANodeOfItsPoolWhichGoesWithCompute() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String role = "auto-node-" + suffix;
        cluster = EksTestNode.cluster(ACCOUNT, "nodes-" + suffix);
        cluster.setVersion(EksService.DEFAULT_K8S_VERSION);
        cluster.setComputeConfig(new ComputeConfig(true, List.of("general-purpose"),
                "arn:aws:iam::" + ACCOUNT + ":role/" + role));
        String subnet = RequestScopes.callAs(ACCOUNT, REGION, () -> {
            iam.createRole(role, "/", "{\"Version\":\"2012-10-17\",\"Statement\":[]}", null, 3600, Map.of());
            String vpc = ec2.createVpc(REGION, "10.71.0.0/16", false).getVpcId();
            return ec2.createSubnet(REGION, vpc, "10.71.1.0/24", "us-east-1a").getSubnetId();
        });
        ResourcesVpcConfig network = new ResourcesVpcConfig();
        network.setSubnetIds(List.of(subnet));
        cluster.setResourcesVpcConfig(network);
        EksTestNode node = EksTestNode.start(dockerClient, eksClusterManager, cluster, Duration.ofSeconds(90));
        cluster.setStatus(ClusterStatus.ACTIVE);
        eksService.putClusterForAccount(ACCOUNT, cluster);
        await("the default NodeClass").atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(3))
                .ignoreExceptions().until(() -> subnet.equals(node.kubectlOutput("get", "nodeclass", "default",
                        "-o", "jsonpath={.spec.subnetSelectorTerms[0].id}")));

        node.kubectl("run", "web", "--image=rancher/mirrored-pause:3.6", "--restart=Never", "--overrides="
                + "{\"spec\":{\"nodeSelector\":{\"karpenter.sh/nodepool\":\"general-purpose\"},\"containers\":[{"
                + "\"name\":\"web\",\"image\":\"rancher/mirrored-pause:3.6\",\"resources\":{\"requests\":"
                + "{\"cpu\":\"100m\",\"memory\":\"64Mi\"}}}]}}");
        await("the pod is unschedulable").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions().until(() -> "Unschedulable".equals(node.kubectlOutput("get", "pod", "web",
                        "-o", "jsonpath={.status.conditions[?(@.type==\"PodScheduled\")].reason}")));

        RequestScopes.runAs(ACCOUNT, REGION, () -> compute.reconcile(cluster, REGION));

        List<Instance> launched = RequestScopes.callAs(ACCOUNT, REGION, () -> compute.instancesOf(cluster, REGION, "pending", "running"));
        assertEquals(1, launched.size());
        Instance instance = launched.getFirst();
        assertEquals(subnet, instance.getSubnetId());
        Map<String, String> tags = instance.getTags().stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue));
        assertEquals(Map.of("eks:eks-cluster-name", cluster.getName(), "eks:kubernetes-node-pool-name", "general-purpose",
                "eks:kubernetes-node-class-name", "default", "kubernetes.io/cluster/" + cluster.getName(), "owned",
                "aws:ec2:managed-launch", "eks-auto"), tags);
        InstanceProfile profile = RequestScopes.callAs(ACCOUNT, REGION, () -> iam.listInstanceProfiles(
                "/eks/" + REGION + "/" + cluster.getName() + "/")).getFirst();
        assertEquals(List.of(role), profile.getRoleNames());
        assertEquals(profile.getArn(), instance.getIamInstanceProfileArn());
        // A second pass while the node hasn't joined launches no other.
        RequestScopes.runAs(ACCOUNT, REGION, () -> compute.reconcile(cluster, REGION));
        assertEquals(1, RequestScopes.callAs(ACCOUNT, REGION, () -> compute.instancesOf(cluster, REGION, "pending", "running")).size());

        RequestScopes.runAs(ACCOUNT, REGION, () -> eksClusterManager.joinInstance(cluster, instance));
        RequestScopes.runAs(ACCOUNT, REGION, () -> compute.reconcile(cluster, REGION));
        // The pod is still unschedulable until the scheduler tries it again, but the joined node has room for it.
        assertEquals(1, RequestScopes.callAs(ACCOUNT, REGION, () -> compute.instancesOf(cluster, REGION, "pending", "running")).size());

        String nodeName = eksClusterManager.deriveInstanceNodeName(instance, REGION);
        assertEquals("general-purpose auto " + instance.getInstanceType(), node.kubectlOutput("get", "node", nodeName,
                "-o", "jsonpath={.metadata.labels.karpenter\\.sh/nodepool} "
                        + "{.metadata.labels.eks\\.amazonaws\\.com/compute-type} "
                        + "{.metadata.labels.node\\.kubernetes\\.io/instance-type}"));
        await("the pod is scheduled on the new node").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions().until(() -> nodeName.equals(node.kubectlOutput("get", "pod", "web",
                        "-o", "jsonpath={.spec.nodeName}")));

        cluster.setComputeConfig(new ComputeConfig(false, List.of(), null));
        RequestScopes.runAs(ACCOUNT, REGION, () -> compute.removeNodes(cluster, REGION));

        assertEquals(List.of(), RequestScopes.callAs(ACCOUNT, REGION, () -> compute.instancesOf(cluster, REGION, "pending", "running")));
        await("the node is gone").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions().until(() -> node.kubectl("get", "node", nodeName).exitCode() != 0);

        // DeleteCluster runs the Auto Mode cleanups, compute's among them.
        Cluster deleted = cluster;
        cluster = null;
        RequestScopes.runAs(ACCOUNT, REGION, () -> eksService.deleteCluster(deleted.getName()));
        assertEquals(List.of(), RequestScopes.callAs(ACCOUNT, REGION, () -> iam.listInstanceProfiles(
                "/eks/" + REGION + "/" + deleted.getName() + "/")));
    }
}
