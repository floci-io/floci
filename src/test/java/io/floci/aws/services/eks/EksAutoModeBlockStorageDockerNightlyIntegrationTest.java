package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.eks.model.BlockStorage;
import io.floci.aws.services.eks.model.Cluster;
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * EKS Auto Mode block storage on a real k3s node: with {@code storageConfig.blockStorage} enabled, a claim of a
 * StorageClass whose provisioner is {@code ebs.csi.eks.amazonaws.com} is bound to a volume that provisioner made;
 * without it, no provisioner answers to that name. Runs on {@link RdsMockProfile} with the other classes that run a
 * real cluster.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class EksAutoModeBlockStorageDockerNightlyIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksAutoModeBlockStorageDockerNightlyIntegrationTest.class);
    private static final String ACCOUNT = "000000000000";
    private static final String PROVISIONER = "floci-auto-mode-ebs-provisioner";

    @Inject
    DockerClient dockerClient;

    @Inject
    EksClusterManager eksClusterManager;

    private Cluster cluster;

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = DockerAvailability.answers(dockerClient);
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksAutoModeBlockStorageDockerNightlyIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for the Auto Mode block storage test");
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
    void aClaimOfTheAutoModeProvisionerIsBoundWhileBlockStorageIsEnabled() {
        cluster = EksTestNode.cluster(ACCOUNT, "ebs-on-" + UUID.randomUUID().toString().substring(0, 8));
        cluster.setStorageConfig(new StorageConfig(new BlockStorage(true)));
        EksTestNode node = EksTestNode.start(dockerClient, eksClusterManager, cluster, Duration.ofSeconds(90));

        node.apply("""
                apiVersion: storage.k8s.io/v1
                kind: StorageClass
                metadata:
                  name: auto-ebs-sc
                provisioner: ebs.csi.eks.amazonaws.com
                volumeBindingMode: WaitForFirstConsumer
                ---
                apiVersion: v1
                kind: PersistentVolumeClaim
                metadata:
                  name: data
                  namespace: default
                spec:
                  storageClassName: auto-ebs-sc
                  accessModes: ["ReadWriteOnce"]
                  resources:
                    requests:
                      storage: 1Gi
                ---
                apiVersion: v1
                kind: Pod
                metadata:
                  name: writer
                  namespace: default
                spec:
                  containers:
                  - name: writer
                    image: rancher/mirrored-library-busybox:1.37.0
                    command: ["sh", "-c", "echo stored > /data/file && sleep 3600"]
                    volumeMounts:
                    - name: data
                      mountPath: /data
                  volumes:
                  - name: data
                    persistentVolumeClaim:
                      claimName: data""");

        await("claim bound").atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(3))
                .until(() -> "Bound".equals(node.kubectlOutput("get", "pvc", "data", "-n", "default",
                        "-o", "jsonpath={.status.phase}")));
        String volume = node.kubectlOutput("get", "pvc", "data", "-n", "default", "-o", "jsonpath={.spec.volumeName}");
        assertEquals("ebs.csi.eks.amazonaws.com", node.kubectlOutput("get", "pv", volume,
                "-o", "jsonpath={.metadata.annotations.pv\\.kubernetes\\.io/provisioned-by}"));
        await("pod wrote to the volume").atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(3))
                .until(() -> node.kubectl("exec", "writer", "-n", "default", "--", "cat", "/data/file")
                        .stdout().trim().equals("stored"));
    }

    @Test
    void noProvisionerAnswersToTheAutoModeNameWithoutBlockStorage() {
        cluster = EksTestNode.cluster(ACCOUNT, "ebs-off-" + UUID.randomUUID().toString().substring(0, 8));
        EksTestNode node = EksTestNode.start(dockerClient, eksClusterManager, cluster, Duration.ofSeconds(90));

        ContainerExec.Result deployment = node.kubectl("get", "deployment", PROVISIONER, "-n", "kube-system");
        assertEquals(1, deployment.exitCode(), deployment.summary());
        assertEquals("Error from server (NotFound): deployments.apps \"" + PROVISIONER + "\" not found",
                deployment.stderr().trim());
    }
}
