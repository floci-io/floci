package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.ec2.Ec2MetadataServer;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EksImdsDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksImdsDockerIntegrationTest.class);
    private static final String TEST_IMAGE = "alpine:3.21";

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    EmulatorConfig config;

    @Inject
    Ec2MetadataServer metadataServer;

    @Inject
    EksClusterManager eksClusterManager;

    private String containerId;
    private Cluster cluster;

    @BeforeEach
    void requireDockerAndStartMetadataServer() {
        boolean dockerAvailable = isDockerAvailable();
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksImdsDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for EKS IMDS integration test");
        metadataServer.start().join();
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            eksClusterManager.unregisterMetadataEndpoint(cluster);
        }
        if (containerId != null) {
            try {
                lifecycleManager.stopAndRemove(containerId, null);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void linkLocalImdsEndpointAnswersInsideClusterContainer() throws Exception {
        String clusterName = "imds-it-" + UUID.randomUUID().toString().substring(0, 8);
        cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setRoleArn("arn:aws:iam::000000000000:role/eks-it-role");

        ContainerSpec spec = containerBuilder.newContainer(TEST_IMAGE)
                .withName("floci-eks-imds-test-" + clusterName)
                .withPrivileged(true)
                .withHostDockerInternalOnLinux()
                .withCmd(List.of("sleep", "300"))
                .build();

        containerId = lifecycleManager.createAndStart(spec).containerId();
        assertNotNull(containerId, "Container ID must not be null");

        eksClusterManager.configureLinkLocalMetadataEndpoint(cluster, containerId);

        // IMDSv2 token test
        String tokenCmd = "curl -s -f -X PUT http://169.254.169.254/latest/api/token -H 'x-aws-ec2-metadata-token-ttl-seconds: 21600'";
        String token = execInContainer(containerId, new String[]{"sh", "-c", tokenCmd});
        assertNotNull(token, "IMDSv2 token response should not be null");
        assertTrue(!token.isBlank(), "IMDSv2 token should not be blank");

        // IMDSv2 instance-id metadata test
        String instanceIdCmd = "curl -s -f -H 'x-aws-ec2-metadata-token: " + token.trim() + "' http://169.254.169.254/latest/meta-data/instance-id";
        String instanceId = execInContainer(containerId, new String[]{"sh", "-c", instanceIdCmd});
        assertNotNull(instanceId, "instance-id response should not be null");
        assertTrue(instanceId.trim().startsWith("i-"), "Instance ID should start with 'i-': " + instanceId);

        // IMDSv1 fallback test
        String imdsv1Cmd = "curl -s -f http://169.254.169.254/latest/meta-data/instance-id";
        String v1InstanceId = execInContainer(containerId, new String[]{"sh", "-c", imdsv1Cmd});
        assertEquals(instanceId.trim(), v1InstanceId.trim(), "IMDSv1 and IMDSv2 instance IDs should match");

        // Pod-isolation test: ordinary pods in their own network namespace cannot reach link-local IMDS
        String podIsolationCmd = """
                if command -v unshare >/dev/null 2>&1; then
                  unshare -n curl -s -f --connect-timeout 2 http://169.254.169.254/latest/meta-data/instance-id
                else
                  ip netns add pod-test 2>/dev/null || true
                  ip netns exec pod-test curl -s -f --connect-timeout 2 http://169.254.169.254/latest/meta-data/instance-id
                  ret=$?
                  ip netns del pod-test 2>/dev/null || true
                  exit $ret
                fi
                """;
        ExecResult podResult = execInContainerWithExitCode(containerId, new String[]{"sh", "-c", podIsolationCmd});
        assertNotEquals(0L, podResult.exitCode(), "IMDS should not be reachable from an isolated pod network namespace");
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    record ExecResult(long exitCode, String stdout, String stderr) {}

    private ExecResult execInContainerWithExitCode(String containerId, String[] cmd) throws Exception {
        ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        boolean completed = dockerClient.execStartCmd(exec.getId())
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        if (frame != null && frame.getPayload() != null) {
                            String text = new String(frame.getPayload(), StandardCharsets.UTF_8);
                            if (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDERR) {
                                stderr.append(text);
                            } else {
                                stdout.append(text);
                            }
                        }
                    }
                })
                .awaitCompletion(30, TimeUnit.SECONDS);

        if (!completed) {
            throw new RuntimeException("exec timed out in container " + containerId);
        }
        Long exitCode = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
        return new ExecResult(exitCode != null ? exitCode : -1L, stdout.toString(), stderr.toString());
    }

    private String execInContainer(String containerId, String[] cmd) throws Exception {
        ExecResult result = execInContainerWithExitCode(containerId, cmd);
        if (result.exitCode() != 0) {
            throw new RuntimeException("exec failed with code " + result.exitCode() + ": " + result.stderr());
        }
        return result.stdout();
    }
}
