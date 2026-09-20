package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.services.ec2.Ec2MetadataServer;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(EksImdsDockerIntegrationTest.Profile.class)
class EksImdsDockerIntegrationTest {

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.eks.imds", "true",
                    "floci.services.eks.imds-pod-network", "true");
        }
    }

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

    @Inject
    DockerHostResolver dockerHostResolver;

    private String containerId;
    private Cluster cluster;

    @BeforeEach
    void requireDockerAndStartMetadataServer() {
        boolean dockerAvailable = isDockerAvailable();
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksImdsDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for EKS IMDS integration test");
        Assumptions.assumeTrue(dockerHostResolver.isLinuxHost(),
                "Link-local IMDS container test requires a Linux host; on macOS/Windows, container-to-host proxy traffic arrives from 127.0.0.1 which collides across clusters");
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

        // Ensure curl, iptables, and iproute2 dependencies are installed in the test fixture if absent
        execInContainer(containerId, new String[]{"sh", "-c",
                "command -v curl >/dev/null 2>&1 && command -v iptables >/dev/null 2>&1 && command -v ip >/dev/null 2>&1 || apk add --no-cache curl iptables iproute2"});

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

        // IMDSv1 fallback test using wget (hostNetwork / node namespace regression test)
        String imdsv1Cmd = "wget -q -O - http://169.254.169.254/latest/meta-data/instance-id";
        String v1InstanceId = execInContainer(containerId, new String[]{"sh", "-c", imdsv1Cmd});
        assertEquals(instanceId.trim(), v1InstanceId.trim(), "IMDSv1 and IMDSv2 instance IDs should match");

        // Pod network namespace test: ordinary pods in their own network namespace reach link-local IMDS
        // via the rules programmed for the pod CIDR (10.42.0.0/16).
        String podNetworkSetupCmd = """
                set -e
                ip link add cni0 type bridge 2>/dev/null || true
                ip addr add 10.42.0.1/24 dev cni0 2>/dev/null || true
                ip link set cni0 up

                ip netns add pod-test 2>/dev/null || true
                ip link add veth-host type veth peer name eth0 netns pod-test 2>/dev/null || true
                ip link set veth-host master cni0 up
                ip netns exec pod-test ip link set lo up
                ip netns exec pod-test ip addr add 10.42.0.15/24 dev eth0 2>/dev/null || true
                ip netns exec pod-test ip link set eth0 up
                ip netns exec pod-test ip route replace default via 10.42.0.1

                # Stand-in CNI portmap rule: k3s/flannel installs CNI-HOSTPORT-DNAT matching dst-type LOCAL.
                # Simulate a hostPort/ingress binding or redirect on port 80 that intercepts LOCAL traffic.
                iptables -t nat -N CNI-HOSTPORT-DNAT 2>/dev/null || true
                iptables -t nat -F CNI-HOSTPORT-DNAT 2>/dev/null || true
                iptables -t nat -A CNI-HOSTPORT-DNAT -p tcp --dport 80 -j REDIRECT --to-ports 9999
                iptables -t nat -D PREROUTING -m addrtype --dst-type LOCAL -j CNI-HOSTPORT-DNAT 2>/dev/null || true
                iptables -t nat -A PREROUTING -m addrtype --dst-type LOCAL -j CNI-HOSTPORT-DNAT
                """;
        execInContainer(containerId, new String[]{"sh", "-c", podNetworkSetupCmd});

        try {
            // Ordinary pod querying instance-id with FLOCI-LINK-LOCAL preempting CNI-HOSTPORT-DNAT
            String podInstanceIdCmd = "ip netns exec pod-test wget -q -O - -T 3 http://169.254.169.254/latest/meta-data/instance-id";
            String podInstanceId = execInContainer(containerId, new String[]{"sh", "-c", podInstanceIdCmd});
            assertNotNull(podInstanceId, "Pod instance ID response should not be null");
            assertEquals(instanceId.trim(), podInstanceId.trim(),
                    "Pod should receive the same instance ID as the node namespace");

            // Ordinary pod querying IAM info
            String podIamInfoCmd = "ip netns exec pod-test wget -q -O - -T 3 http://169.254.169.254/latest/meta-data/iam/info";
            String podIamInfo = execInContainer(containerId, new String[]{"sh", "-c", podIamInfoCmd});
            assertNotNull(podIamInfo, "Pod IAM info response should not be null");
            assertTrue(podIamInfo.contains(clusterName + "-node-profile"),
                    "Pod IAM info should contain instance profile: " + podIamInfo);

            // Prove the FLOCI-LINK-LOCAL rule is strictly required:
            // Temporarily detach FLOCI-LINK-LOCAL from PREROUTING. The pod request now hits CNI-HOSTPORT-DNAT
            // and fails because port 80 is redirected to 9999.
            execInContainer(containerId, new String[]{"sh", "-c",
                    "iptables -t nat -D PREROUTING -j FLOCI-LINK-LOCAL"});
            ExecResult intercepted = execInContainerWithExitCode(containerId, new String[]{"sh", "-c",
                    "ip netns exec pod-test wget -q -O - -T 2 http://169.254.169.254/latest/meta-data/instance-id"});
            assertNotEquals(0, intercepted.exitCode(),
                    "Without FLOCI-LINK-LOCAL chain, pod request should be intercepted by CNI-HOSTPORT-DNAT and fail");

            // Re-insert FLOCI-LINK-LOCAL at rule 1: pod request succeeds again
            execInContainer(containerId, new String[]{"sh", "-c",
                    "iptables -t nat -I PREROUTING 1 -j FLOCI-LINK-LOCAL"});
            String restoredInstanceId = execInContainer(containerId, new String[]{"sh", "-c", podInstanceIdCmd});
            assertEquals(instanceId.trim(), restoredInstanceId.trim(),
                    "Restoring FLOCI-LINK-LOCAL rule must restore pod reachability");
        } finally {
            execInContainerWithExitCode(containerId, new String[]{"sh", "-c", """
                    ip netns del pod-test 2>/dev/null || true
                    ip link del cni0 2>/dev/null || true
                    iptables -t nat -D PREROUTING -j FLOCI-LINK-LOCAL 2>/dev/null || true
                    iptables -t nat -D PREROUTING -m addrtype --dst-type LOCAL -j CNI-HOSTPORT-DNAT 2>/dev/null || true
                    iptables -t nat -F CNI-HOSTPORT-DNAT 2>/dev/null || true
                    iptables -t nat -X CNI-HOSTPORT-DNAT 2>/dev/null || true
                    """});
        }
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
