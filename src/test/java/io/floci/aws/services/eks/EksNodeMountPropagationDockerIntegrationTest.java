package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.testing.RealEksProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker integration test verifying that a cluster's node shares its mounts as an EKS node does, so a pod asking
 * for mount propagation starts: a CSI node plugin mounts its volumes under {@code /var/lib/kubelet/pods} with
 * {@code Bidirectional} propagation, and a node exporter reads the node's {@code /} with {@code HostToContainer}.
 * On a node whose mounts are private, containerd refuses both ("is not a shared mount").
 *
 * <p>The cluster is started through {@link EksClusterManager} directly and runs on {@link RealEksProfile} with the
 * other classes that run a real cluster.
 */
@QuarkusTest
@TestProfile(RealEksProfile.class)
class EksNodeMountPropagationDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksNodeMountPropagationDockerIntegrationTest.class);
    private static final String ACCOUNT = "000000000000";
    private static final String POD = "mount-propagation";

    @Inject
    DockerClient dockerClient;

    @Inject
    EksClusterManager eksClusterManager;

    private Cluster cluster;

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = isDockerAvailable();
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksNodeMountPropagationDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for EKS mount propagation test");
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
    void aPodAskingForMountPropagationStartsOnTheNode() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String clusterName = "mount-prop-" + suffix;

        cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setAccountId(ACCOUNT);
        cluster.setArn("arn:aws:eks:us-east-1:" + ACCOUNT + ":cluster/" + clusterName);
        cluster.setCreatedAt(Instant.now());
        cluster.setRoleArn("arn:aws:iam::" + ACCOUNT + ":role/eks-service-role");

        eksClusterManager.startCluster(cluster);
        assertNotNull(cluster.getContainerId(), "Container ID must not be null");
        String containerId = cluster.getContainerId();

        assertTrue(waitFor(60, () -> eksClusterManager.isReady(cluster)),
                "k3s API server must become ready within 60 seconds");
        eksClusterManager.finalizeCluster(cluster);
        assertTrue(waitFor(30, () -> kubectl(containerId, "get", "serviceaccount", "default").exitCode() == 0),
                "default serviceaccount must be created within 30 seconds");

        String podYaml = """
                apiVersion: v1
                kind: Pod
                metadata:
                  name: %s
                  namespace: default
                spec:
                  containers:
                  - name: plugin
                    image: alpine:3.21
                    command: ["sleep", "3600"]
                    securityContext:
                      privileged: true
                    volumeMounts:
                    - name: kubelet-pods
                      mountPath: /var/lib/kubelet/pods
                      mountPropagation: Bidirectional
                    - name: node-root
                      mountPath: /host/root
                      mountPropagation: HostToContainer
                      readOnly: true
                  volumes:
                  - name: kubelet-pods
                    hostPath:
                      path: /var/lib/kubelet/pods
                      type: Directory
                  - name: node-root
                    hostPath:
                      path: /
                """.formatted(POD);
        ContainerExec.Result applied = exec(containerId, "sh", "-c",
                "cat << 'EOF' | kubectl apply -f -\n" + podYaml + "\nEOF");
        assertEquals(0, applied.exitCode(), "kubectl apply failed: " + applied.stderr());

        boolean running = waitFor(90, () -> "Running".equals(
                kubectl(containerId, "get", "pod", POD, "-o", "jsonpath={.status.phase}").stdout().trim()));
        assertTrue(running, () -> "the pod must start, but its container reports: " + kubectl(containerId,
                "get", "pod", POD, "-o", "jsonpath={.status.containerStatuses[0].state}").stdout());
    }

    private boolean waitFor(int seconds, Check check) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (check.passes()) {
                return true;
            }
            Thread.sleep(2000);
        }
        return false;
    }

    @FunctionalInterface
    private interface Check {
        boolean passes() throws Exception;
    }

    private ContainerExec.Result kubectl(String containerId, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "kubectl";
        System.arraycopy(args, 0, cmd, 1, args.length);
        return exec(containerId, cmd);
    }

    private ContainerExec.Result exec(String containerId, String... cmd) {
        return ContainerExec.run(dockerClient, containerId, cmd, 30).throwIfTimedOut(containerId);
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
