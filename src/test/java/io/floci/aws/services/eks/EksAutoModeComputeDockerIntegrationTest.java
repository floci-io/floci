package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.services.eks.model.BlockStorage;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ComputeConfig;
import io.floci.aws.services.eks.model.ElasticLoadBalancing;
import io.floci.aws.services.eks.model.KubernetesNetworkConfig;
import io.floci.aws.services.eks.model.StorageConfig;
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

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EKS Auto Mode compute on a real k3s node: with compute on, the cluster has the NodePool and NodeClass kinds, the
 * {@code default} NodeClass and the built-in node pools it names, as Auto Mode installs them, and its node carries
 * Auto Mode's {@code eks.amazonaws.com/compute-type: auto} label. Runs on {@link RdsMockProfile} with the other
 * classes that run a real cluster.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class EksAutoModeComputeDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksAutoModeComputeDockerIntegrationTest.class);
    private static final String ACCOUNT = "000000000000";

    @Inject
    DockerClient dockerClient;

    @Inject
    EksClusterManager eksClusterManager;

    private Cluster cluster;

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = DockerAvailability.answers(dockerClient);
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksAutoModeComputeDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for the Auto Mode compute test");
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            try {
                eksClusterManager.stopCluster(cluster);
            } catch (Exception e) {
                LOG.warnv("Failed to stop test cluster: {0}", e.getMessage());
            }
        }
    }

    @Test
    void aClusterWithComputeHasItsNodePoolsTheDefaultNodeClassAndAnAutoModeNode() {
        cluster = EksTestNode.cluster(ACCOUNT, "compute-" + UUID.randomUUID().toString().substring(0, 8));
        cluster.setComputeConfig(new ComputeConfig(true, List.of("general-purpose", "system"),
                "arn:aws:iam::" + ACCOUNT + ":role/eks-auto-node"));
        cluster.setStorageConfig(new StorageConfig(new BlockStorage(true)));
        KubernetesNetworkConfig network = new KubernetesNetworkConfig();
        network.setElasticLoadBalancing(new ElasticLoadBalancing(true));
        cluster.setKubernetesNetworkConfig(network);
        EksTestNode node = EksTestNode.start(dockerClient, eksClusterManager, cluster, Duration.ofSeconds(90));

        await("node pools").atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(3)).ignoreExceptions()
                .until(() -> "general-purpose system".equals(node.kubectlOutput("get", "nodepools",
                        "-o", "jsonpath={.items[*].metadata.name}")));
        assertEquals("eks-auto-node", node.kubectlOutput("get", "nodeclass", "default", "-o", "jsonpath={.spec.role}"));
        assertEquals("CriticalAddonsOnly", node.kubectlOutput("get", "nodepool", "system",
                "-o", "jsonpath={.spec.template.spec.taints[0].key}"));
        assertEquals("auto", node.kubectlOutput("get", "nodes",
                "-o", "jsonpath={.items[0].metadata.labels.eks\\.amazonaws\\.com/compute-type}"));
    }
}
