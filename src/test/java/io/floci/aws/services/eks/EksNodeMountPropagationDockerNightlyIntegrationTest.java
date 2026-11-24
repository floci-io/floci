package io.floci.aws.services.eks;

import com.github.dockerjava.api.DockerClient;
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

/**
 * Docker integration test verifying that a cluster's node shares its mounts as an EKS node does, so a pod asking
 * for mount propagation starts: a CSI node plugin mounts its volumes under {@code /var/lib/kubelet/pods} with
 * {@code Bidirectional} propagation, and a node exporter reads the node's {@code /} with {@code HostToContainer}.
 * On a node whose mounts are private, containerd refuses both ("is not a shared mount").
 *
 * <p>The cluster is started through {@link EksClusterManager} directly and runs on {@link RdsMockProfile} with the
 * other classes that run a real cluster.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class EksNodeMountPropagationDockerNightlyIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksNodeMountPropagationDockerNightlyIntegrationTest.class);
    private static final String ACCOUNT = "000000000000";
    private static final String POD = "mount-propagation";

    @Inject
    DockerClient dockerClient;

    @Inject
    EksClusterManager eksClusterManager;

    private Cluster cluster;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient),
                "Docker daemon must be available for EKS mount propagation test");
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            try {
                eksClusterManager.stopCluster(cluster);
            } catch (Exception e) {
                LOG.warnv(e, "Failed to stop test cluster {0}", cluster.getName());
            }
        }
    }

    @Test
    void aPodAskingForMountPropagationStartsOnTheNode() {
        cluster = EksTestNode.cluster(ACCOUNT, "mount-prop-" + UUID.randomUUID().toString().substring(0, 8));
        EksTestNode node = EksTestNode.start(dockerClient, eksClusterManager, cluster, Duration.ofSeconds(60));

        node.apply("""
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
                """.formatted(POD));

        node.awaitPodRunning(POD, Duration.ofSeconds(90));
    }
}
