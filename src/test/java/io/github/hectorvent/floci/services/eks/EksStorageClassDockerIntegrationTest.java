package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker integration test verifying that freshly created clusters start with no StorageClass,
 * no bundled local-path provisioner, and that CoreDNS remains functional.
 */
@QuarkusTest
@TestProfile(EksStorageClassDockerIntegrationTest.Profile.class)
class EksStorageClassDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksStorageClassDockerIntegrationTest.class);
    private static final String ACCOUNT = "000000000000";

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.eks.mock", "false"
            );
        }
    }

    @Inject
    DockerClient dockerClient;

    @Inject
    EksClusterManager eksClusterManager;

    private Cluster cluster;

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = isDockerAvailable();
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksStorageClassDockerIntegrationTest");
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
    void clusterStartsWithoutStorageClassAndCoreDnsRemainsAvailable() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = "sc-dock-" + suffix;

        cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setAccountId(ACCOUNT);
        cluster.setArn("arn:aws:eks:us-east-1:" + ACCOUNT + ":cluster/" + clusterName);
        cluster.setCreatedAt(Instant.now());
        cluster.setRoleArn("arn:aws:iam::" + ACCOUNT + ":role/eks-service-role");

        eksClusterManager.startCluster(cluster);
        assertNotNull(cluster.getContainerId(), "Container ID must not be null");

        // Wait for k3s API server readiness (up to 60s)
        long deadline = System.currentTimeMillis() + 60000;
        boolean ready = false;
        while (System.currentTimeMillis() < deadline) {
            if (eksClusterManager.isReady(cluster)) {
                ready = true;
                break;
            }
            Thread.sleep(2000);
        }
        assertTrue(ready, "k3s API server must become ready within 60 seconds");
        eksClusterManager.finalizeCluster(cluster);

        String containerId = cluster.getContainerId();

        // 1. Verify CoreDNS deployment is present and healthy in kube-system
        long corednsDeadline = System.currentTimeMillis() + 60000;
        boolean corednsRolledOut = false;
        while (System.currentTimeMillis() < corednsDeadline) {
            ContainerExec.Result rolloutResult = execInContainerWithExitCode(containerId,
                    new String[]{"kubectl", "rollout", "status", "deployment/coredns", "-n", "kube-system", "--timeout=10s"});
            if (rolloutResult.exitCode() == 0) {
                corednsRolledOut = true;
                break;
            }
            Thread.sleep(2000);
        }
        assertTrue(corednsRolledOut, "CoreDNS deployment must be successfully rolled out");

        // 2. Verify CoreDNS pods are running and ready
        ContainerExec.Result podsResult = execInContainerWithExitCode(containerId,
                new String[]{"kubectl", "get", "pods", "-n", "kube-system", "-l", "k8s-app=kube-dns",
                        "-o", "jsonpath={.items[*].status.phase}"});
        assertEquals(0, podsResult.exitCode(), "kubectl get pods failed: " + podsResult.stderr());
        assertTrue(podsResult.stdout().contains("Running"),
                "CoreDNS pod must be in Running phase, but got: " + podsResult.stdout());

        // 3. Verify no StorageClass exists in the cluster after manifests settle
        ContainerExec.Result scResult = execInContainerWithExitCode(containerId,
                new String[]{"kubectl", "get", "storageclass", "-o", "jsonpath={.items[*].metadata.name}"});
        assertEquals(0, scResult.exitCode(), "kubectl get storageclass failed: " + scResult.stderr());
        assertTrue(scResult.stdout().trim().isEmpty(),
                "No StorageClass should exist in newly created cluster, but found: " + scResult.stdout());

        // 4. Explicitly verify local-path StorageClass is absent
        ContainerExec.Result localPathResult = execInContainerWithExitCode(containerId,
                new String[]{"kubectl", "get", "storageclass", "local-path"});
        assertNotEquals(0, localPathResult.exitCode(), "local-path StorageClass must not exist");
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private ContainerExec.Result execInContainerWithExitCode(String containerId, String[] cmd) {
        return ContainerExec.run(dockerClient, containerId, cmd, 30).throwIfTimedOut(containerId);
    }
}
