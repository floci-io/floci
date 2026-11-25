package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ElasticLoadBalancing;
import io.floci.aws.services.eks.model.KubernetesNetworkConfig;
import io.floci.aws.services.eks.model.ResourcesVpcConfig;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import io.floci.aws.services.elbv2.model.TargetGroup;
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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * EKS Auto Mode load balancing on a real k3s node: a LoadBalancer Service of the {@code eks.amazonaws.com/nlb}
 * class gets a Network Load Balancer with a target group on its NodePort, its DNS name is written to the
 * Service's status, and the load balancer goes with the Service; on a node started with load balancing on, a
 * Service of no class gets one too, and k3s's ServiceLB doesn't take it. The reconciler is called directly: EKS is mocked
 * in {@link RdsMockProfile}, which the other classes that run a real cluster use, so its interval doesn't run.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class EksAutoModeNlbDockerNightlyIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksAutoModeNlbDockerNightlyIntegrationTest.class);
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @Inject
    DockerClient dockerClient;

    @Inject
    EksClusterManager eksClusterManager;

    @Inject
    EksAutoModeLoadBalancers loadBalancers;

    @Inject
    ElbV2Service elb;

    @Inject
    Ec2Service ec2;

    private Cluster cluster;

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = DockerAvailability.answers(dockerClient);
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksAutoModeNlbDockerNightlyIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for the Auto Mode NLB test");
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            RequestScopes.runAs(ACCOUNT, REGION, () -> loadBalancersOf(cluster.getName())
                    .forEach(lb -> elb.deleteLoadBalancer(REGION, lb.getLoadBalancerArn())));
            try {
                eksClusterManager.stopCluster(cluster);
            } catch (Exception e) {
                LOG.warnv("Failed to stop test cluster: {0}", e.getMessage());
            }
        }
    }

    @Test
    void aServiceOfTheNlbClassGetsALoadBalancerOnItsNodePortWhichGoesWithIt() {
        cluster = clusterInAVpc("nlb-", "10.61", false);
        EksTestNode node = EksTestNode.start(dockerClient, eksClusterManager, cluster, Duration.ofSeconds(90));
        node.apply("""
                apiVersion: v1
                kind: Service
                metadata:
                  name: web
                  namespace: default
                  annotations:
                    service.beta.kubernetes.io/aws-load-balancer-scheme: internet-facing
                spec:
                  type: LoadBalancer
                  loadBalancerClass: eks.amazonaws.com/nlb
                  selector:
                    app: web
                  ports:
                  - port: 18080
                    targetPort: 80
                    protocol: TCP""");
        int nodePort = Integer.parseInt(node.kubectlOutput("get", "service", "web", "-n", "default",
                "-o", "jsonpath={.spec.ports[0].nodePort}"));

        RequestScopes.runAs(ACCOUNT, REGION, () -> loadBalancers.reconcile(cluster, REGION));

        List<LoadBalancer> created = RequestScopes.callAs(ACCOUNT, REGION, () -> loadBalancersOf(cluster.getName()));
        assertEquals(1, created.size());
        LoadBalancer lb = created.getFirst();
        assertEquals("network", lb.getType());
        assertEquals("internet-facing", lb.getScheme());
        List<TargetGroup> groups = RequestScopes.callAs(ACCOUNT, REGION,
                () -> elb.describeTargetGroups(REGION, lb.getLoadBalancerArn(), null, null));
        assertEquals(1, groups.size());
        assertEquals(nodePort, groups.getFirst().getPort());
        assertEquals("instance", groups.getFirst().getTargetType());
        assertEquals(lb.getDnsName(), node.kubectlOutput("get", "service", "web", "-n", "default",
                "-o", "jsonpath={.status.loadBalancer.ingress[0].hostname}"));

        node.kubectl("delete", "service", "web", "-n", "default");
        RequestScopes.runAs(ACCOUNT, REGION, () -> loadBalancers.reconcile(cluster, REGION));

        await("load balancer deleted").atMost(Duration.ofSeconds(10))
                .until(() -> RequestScopes.callAs(ACCOUNT, REGION, () -> loadBalancersOf(cluster.getName())).isEmpty());
        AwsException gone = assertThrows(AwsException.class, () -> RequestScopes.callAs(ACCOUNT, REGION,
                () -> elb.describeTargetGroups(REGION, null, List.of(groups.getFirst().getTargetGroupArn()), null)));
        assertEquals("TargetGroupNotFound", gone.getErrorCode());
    }

    @Test
    void aServiceOfNoClassGetsALoadBalancerOnANodeStartedWithLoadBalancingOn() {
        cluster = clusterInAVpc("nlbd-", "10.62", true);
        EksTestNode node = EksTestNode.start(dockerClient, eksClusterManager, cluster, Duration.ofSeconds(90));
        node.apply("""
                apiVersion: v1
                kind: Service
                metadata:
                  name: plain
                  namespace: default
                spec:
                  type: LoadBalancer
                  selector:
                    app: plain
                  ports:
                  - port: 18081
                    targetPort: 80
                    protocol: TCP""");

        RequestScopes.runAs(ACCOUNT, REGION, () -> loadBalancers.reconcile(cluster, REGION));

        List<LoadBalancer> created = RequestScopes.callAs(ACCOUNT, REGION, () -> loadBalancersOf(cluster.getName()));
        assertEquals(1, created.size());
        assertEquals("internal", created.getFirst().getScheme());
        assertEquals(created.getFirst().getDnsName(), node.kubectlOutput("get", "service", "plain", "-n", "default",
                "-o", "jsonpath={.status.loadBalancer.ingress[0].hostname}"));
        // k3s's ServiceLB, which would take the Service too, doesn't run on the node.
        assertEquals("", node.kubectlOutput("get", "pods", "-A", "-l", "svccontroller.k3s.cattle.io/svcname=plain",
                "-o", "name"));
    }

    /** A cluster with a VPC and subnet of its own, with EKS Auto Mode load balancing on or not. */
    private Cluster clusterInAVpc(String prefix, String cidr, boolean loadBalancing) {
        Cluster created = EksTestNode.cluster(ACCOUNT, prefix + UUID.randomUUID().toString().substring(0, 8));
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            String vpc = ec2.createVpc(REGION, cidr + ".0.0/16", false).getVpcId();
            String subnet = ec2.createSubnet(REGION, vpc, cidr + ".1.0/24", "us-east-1a").getSubnetId();
            ResourcesVpcConfig network = new ResourcesVpcConfig();
            network.setVpcId(vpc);
            network.setSubnetIds(List.of(subnet));
            created.setResourcesVpcConfig(network);
        });
        if (loadBalancing) {
            KubernetesNetworkConfig network = new KubernetesNetworkConfig();
            network.setElasticLoadBalancing(new ElasticLoadBalancing(true));
            created.setKubernetesNetworkConfig(network);
        }
        return created;
    }

    private List<LoadBalancer> loadBalancersOf(String clusterName) {
        List<LoadBalancer> all = elb.describeLoadBalancers(REGION, null, null, null, null);
        if (all.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, String>> tags = elb.describeTags(all.stream().map(LoadBalancer::getLoadBalancerArn).toList());
        return all.stream().filter(lb -> clusterName.equals(
                tags.getOrDefault(lb.getLoadBalancerArn(), Map.of()).get(EksAutoModeLoadBalancers.CLUSTER_TAG))).toList();
    }
}
