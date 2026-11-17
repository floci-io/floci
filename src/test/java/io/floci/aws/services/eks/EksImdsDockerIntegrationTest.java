package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.services.ec2.Ec2MetadataServer;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.testing.CloudFormationSettingsProfile;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.EksTestNode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * IMDS answers on a stock {@code rancher/k3s} node, which has {@code iptables} but no {@code socat}, {@code curl} or
 * package manager: the node itself, where a {@code hostNetwork} pod's requests come from, and an ordinary pod both
 * read the node's instance from {@code 169.254.169.254}. The settings come from
 * {@link CloudFormationSettingsProfile}, which turns on IMDS for the node and its pod network; the node is started
 * through {@link EksClusterManager} directly, which runs a k3s container while EKS is mocked too.
 */
@QuarkusTest
@TestProfile(CloudFormationSettingsProfile.class)
class EksImdsDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksImdsDockerIntegrationTest.class);
    private static final String ACCOUNT = "000000000000";
    private static final String POD = "imds-client";
    private static final String IMDS = "http://169.254.169.254/latest/meta-data/";

    @Inject
    DockerClient dockerClient;

    @Inject
    Ec2MetadataServer metadataServer;

    @Inject
    EksClusterManager eksClusterManager;

    @Inject
    DockerHostResolver dockerHostResolver;

    private Cluster cluster;

    @BeforeEach
    void requireDockerAndStartMetadataServer() {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient),
                "Docker daemon must be available for EKS IMDS integration test");
        Assumptions.assumeTrue(dockerHostResolver.isLinuxHost(),
                "Link-local IMDS container test requires a Linux host; on macOS/Windows, container-to-host traffic"
                        + " arrives from 127.0.0.1 which collides across clusters");
        metadataServer.start().join();
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
    void theNodeAndAPodReadTheNodesInstanceFromImds() throws Exception {
        cluster = EksTestNode.cluster(ACCOUNT, "imds-" + UUID.randomUUID().toString().substring(0, 8));
        EksTestNode node = EksTestNode.start(dockerClient, eksClusterManager, cluster, Duration.ofSeconds(60));
        Instance instance = eksClusterManager.getRegisteredClusterNodeInstance(cluster);
        assertNotNull(instance, "the node must be registered with the metadata server");

        // The node's namespace, with BusyBox's wget: the stock image has no curl.
        assertEquals(instance.getInstanceId(), node.execOutput("wget", "-q", "-O", "-", "-T", "3",
                IMDS + "instance-id"));

        node.apply("""
                apiVersion: v1
                kind: Pod
                metadata:
                  name: %s
                  namespace: default
                spec:
                  containers:
                  - name: client
                    image: alpine:3.21
                    command: ["sleep", "3600"]
                """.formatted(POD));
        node.awaitPodRunning(POD, Duration.ofSeconds(90));

        // An ordinary pod, from the pod network: the node's own instance and its instance profile.
        assertEquals(instance.getInstanceId(), node.kubectlOutput("exec", POD, "--", "wget", "-q", "-O", "-",
                "-T", "3", IMDS + "instance-id"));
        String iamInfo = node.kubectlOutput("exec", POD, "--", "wget", "-q", "-O", "-", "-T", "3", IMDS + "iam/info");
        assertEquals(instance.getIamInstanceProfileArn(),
                new ObjectMapper().readTree(iamInfo).path("InstanceProfileArn").asText(), iamInfo);
    }
}
