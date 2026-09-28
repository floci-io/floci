package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EksVpcRouteProgrammingDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EksVpcRouteProgrammingDockerIntegrationTest.class);
    private static final String TEST_IMAGE = "alpine:3.21";

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    private String containerId;

    @BeforeEach
    void requireDocker() {
        boolean dockerAvailable = isDockerAvailable();
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksVpcRouteProgrammingDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for VPC route programming integration test");
    }

    @AfterEach
    void tearDown() {
        if (containerId != null) {
            try {
                lifecycleManager.stopAndRemove(containerId, null);
            } catch (Exception e) {
                LOG.warnv("Failed to clean up test container {0}: {1}", containerId, e.getMessage());
            }
        }
    }

    @Test
    void routeProgrammingAppliesAndReconcilesRoutesInsideContainer() throws Exception {
        ContainerSpec spec = containerBuilder.newContainer(TEST_IMAGE)
                .withName("floci-eks-vpc-route-" + UUID.randomUUID().toString().substring(0, 8))
                .withPrivileged(true)
                .withCmd(List.of("sleep", "3600"))
                .build();

        containerId = lifecycleManager.createAndStart(spec).containerId();
        assertNotNull(containerId, "Container ID must not be null");

        // Ensure iproute2 is available in the test fixture
        execInContainer(containerId, new String[]{"sh", "-c",
                "command -v ip >/dev/null 2>&1 || apk add --no-cache iproute2"});

        // 1. Apply a route
        String destinationCidr = "10.123.0.0/16";
        String gwOutput = execInContainer(containerId, new String[]{"sh", "-c",
                "ip route show | awk '/default/ {print $3}' | head -n1"}).trim();
        String gatewayIp = gwOutput.isBlank() ? "172.17.0.1" : gwOutput;
        List<EksVpcRouteProgramming.VpcRouteEntry> entries = List.of(
                new EksVpcRouteProgramming.VpcRouteEntry(destinationCidr, gatewayIp)
        );

        Optional<String[]> cmdOpt = EksVpcRouteProgramming.buildRoutingCommand(entries);
        assertTrue(cmdOpt.isPresent(), "Routing command should be generated");

        String execOutput = execInContainer(containerId, cmdOpt.get());
        LOG.infov("Route programming script output: {0}", execOutput);

        // Verify routing table inside container reflects the new route
        String routesOutput = execInContainer(containerId, new String[]{"sh", "-c", "ip route show"});
        assertTrue(routesOutput.contains(destinationCidr), "Route table must contain " + destinationCidr + ", got: " + routesOutput);
        assertTrue(routesOutput.contains(gatewayIp), "Route table must contain gateway " + gatewayIp + ", got: " + routesOutput);

        // 2. Reconcile / remove the stale route
        Optional<String[]> delCmdOpt = EksVpcRouteProgramming.buildRoutingCommand(List.of(), Set.of(destinationCidr));
        assertTrue(delCmdOpt.isPresent(), "Delete routing command should be generated");

        String delOutput = execInContainer(containerId, delCmdOpt.get());
        LOG.infov("Route deletion script output: {0}", delOutput);

        // Verify route has been removed from the kernel routing table
        String routesAfterDel = execInContainer(containerId, new String[]{"sh", "-c", "ip route show"});
        assertFalse(routesAfterDel.contains(destinationCidr), "Route table should no longer contain " + destinationCidr + ", got: " + routesAfterDel);
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String execInContainer(String containerId, String[] cmd) throws Exception {
        ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        StringBuilder output = new StringBuilder();
        dockerClient.execStartCmd(exec.getId()).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame item) {
                if (item != null && item.getPayload() != null) {
                    output.append(new String(item.getPayload(), StandardCharsets.UTF_8));
                }
            }
        }).awaitCompletion(30, TimeUnit.SECONDS);

        return output.toString();
    }
}
