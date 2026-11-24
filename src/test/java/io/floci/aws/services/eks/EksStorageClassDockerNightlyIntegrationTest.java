package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.eks.model.Cluster;
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
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Docker integration test verifying that freshly created clusters start with no StorageClass,
 * no bundled local-path provisioner, and that CoreDNS remains functional.
 *
 * <p>The cluster is started through {@link EksClusterManager} directly, which runs a k3s container
 * whether or not EKS is mocked. The class runs on {@link RdsMockProfile} with the other classes
 * that run a real cluster.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class EksStorageClassDockerNightlyIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksStorageClassDockerNightlyIntegrationTest.class);
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
            LOG.warn("Docker daemon is not available; skipping EksStorageClassDockerNightlyIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for EKS storage class integration test");
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
    void clusterStartsWithoutStorageClassAndCoreDnsRemainsAvailable() {
        cluster = EksTestNode.cluster(ACCOUNT, "sc-dock-" + UUID.randomUUID().toString().substring(0, 8));
        EksTestNode node = EksTestNode.start(dockerClient, eksClusterManager, cluster, Duration.ofSeconds(60));

        // 1. Verify CoreDNS deployment is present and healthy in kube-system
        await("CoreDNS rolled out").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2))
                .until(() -> node.kubectl("rollout", "status", "deployment/coredns", "-n", "kube-system",
                        "--timeout=10s").exitCode() == 0);

        // 2. Verify CoreDNS pods are running and ready
        assertEquals("Running", node.kubectlOutput("get", "pods", "-n", "kube-system", "-l", "k8s-app=kube-dns",
                        "-o", "jsonpath={.items[*].status.phase}"),
                "CoreDNS pod must be in Running phase");

        // 3. Verify no StorageClass exists in the cluster after manifests settle
        assertEquals("", node.kubectlOutput("get", "storageclass", "-o", "jsonpath={.items[*].metadata.name}"),
                "No StorageClass should exist in newly created cluster");

        // 4. Explicitly verify local-path StorageClass is absent
        ContainerExec.Result localPath = node.kubectl("get", "storageclass", "local-path");
        assertEquals(1, localPath.exitCode(), localPath.summary());
        assertEquals("Error from server (NotFound): storageclasses.storage.k8s.io \"local-path\" not found",
                localPath.stderr().trim(), "local-path StorageClass must not exist");
    }
}
