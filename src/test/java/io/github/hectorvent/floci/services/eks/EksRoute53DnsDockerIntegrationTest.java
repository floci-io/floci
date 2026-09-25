package io.github.hectorvent.floci.services.eks;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(EksRoute53DnsDockerIntegrationTest.Profile.class)
class EksRoute53DnsDockerIntegrationTest {

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.eks.embedded-dns", "true");
        }
    }

    private static final Logger LOG = Logger.getLogger(EksRoute53DnsDockerIntegrationTest.class);
    private static final String TEST_IMAGE = "alpine:3.21";

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    EmbeddedDnsServer embeddedDnsServer;

    @Inject
    Route53Service route53Service;

    private String containerId;
    private String zoneId;

    @BeforeEach
    void requireDockerAndEmbeddedDns() {
        boolean dockerAvailable = isDockerAvailable();
        if (!dockerAvailable) {
            LOG.warn("Docker daemon is not available; skipping EksRoute53DnsDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker daemon must be available for EKS Route 53 DNS integration test");

        boolean dnsAvailable = embeddedDnsServer.getServerIp().isPresent();
        if (!dnsAvailable) {
            LOG.warn("Embedded DNS server is not running or has no bound IP; skipping EksRoute53DnsDockerIntegrationTest");
        }
        Assumptions.assumeTrue(dnsAvailable, "Embedded DNS server must have a bound IP for Route 53 DNS integration test");
    }

    @AfterEach
    void tearDown() {
        if (containerId != null) {
            try {
                lifecycleManager.stopAndRemove(containerId, null);
            } catch (Exception ignored) {
                // Best-effort cleanup of test container
            }
        }
        if (zoneId != null) {
            try {
                List<Map<String, Object>> deletes = route53Service.listResourceRecordSets(zoneId, null, null, 100).stream()
                        .filter(rrs -> !"NS".equals(rrs.getType()) && !"SOA".equals(rrs.getType()))
                        .map(rrs -> Map.<String, Object>of("action", "DELETE", "rrs", rrs))
                        .toList();
                if (!deletes.isEmpty()) {
                    route53Service.changeResourceRecordSets(zoneId, deletes, null);
                }
                route53Service.deleteHostedZone(zoneId);
            } catch (Exception ignored) {
                // Best-effort cleanup of test hosted zone
            }
        }
    }

    @Test
    void clusterContainerResolvesRoute53PrivateHostedZoneRecords() throws Exception {
        String testDomain = "corp-it-" + UUID.randomUUID().toString().substring(0, 8) + ".internal.";
        zoneId = route53Service.createHostedZone(
                testDomain,
                UUID.randomUUID().toString(),
                "EKS IT private zone",
                new VpcAssociation("vpc-12345", "us-east-1")).zone().getId();

        ResourceRecordSet aRrs = new ResourceRecordSet();
        aRrs.setName("db." + testDomain);
        aRrs.setType("A");
        aRrs.setTtl(60L);
        aRrs.setRecords(List.of(new ResourceRecord("10.0.1.200")));
        route53Service.changeResourceRecordSets(zoneId, List.of(Map.of("action", "CREATE", "rrs", aRrs)), null);

        ContainerSpec spec = containerBuilder.newContainer(TEST_IMAGE)
                .withName("floci-eks-dns-test-" + UUID.randomUUID().toString().substring(0, 8))
                .withEmbeddedDns()
                .withCmd(List.of("sh", "-c", "trap 'exit 0' TERM; sleep 300 & wait"))
                .build();

        containerId = lifecycleManager.createAndStart(spec).containerId();
        assertNotNull(containerId, "Container ID must not be null");

        String dnsServerIp = embeddedDnsServer.getServerIp().orElseThrow();

        // Verify that /etc/resolv.conf inside the container has the embedded DNS server IP injected
        String resolvConf = execInContainer(containerId, new String[]{"cat", "/etc/resolv.conf"});
        assertTrue(resolvConf.contains(dnsServerIp), "Container /etc/resolv.conf must include embedded DNS server IP: " + resolvConf);

        // Verify that standard resolver queries inside the container resolve the Route 53 private record
        String lookupCmd = "nslookup db." + testDomain.substring(0, testDomain.length() - 1);
        String output = execInContainer(containerId, new String[]{"sh", "-c", lookupCmd});
        assertNotNull(output, "Lookup output must not be null");
        assertTrue(output.contains("10.0.1.200"), "Lookup output must contain record IP: " + output);
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception expected) {
            // Docker daemon is not running or unreachable in this test environment
            LOG.debugv("Docker ping failed: {0}", expected.getMessage());
            return false;
        }
    }

    private String execInContainer(String containerId, String[] cmd) throws Exception {
        ExecCreateCmdResponse exec = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd).withAttachStdout(true).withAttachStderr(true).exec();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        boolean completed = dockerClient.execStartCmd(exec.getId())
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        if (frame != null && frame.getPayload() != null) {
                            String text = new String(frame.getPayload(), StandardCharsets.UTF_8);
                            (frame.getStreamType() == StreamType.STDERR ? stderr : stdout).append(text);
                        }
                    }
                })
                .awaitCompletion(30, TimeUnit.SECONDS);

        if (!completed) {
            throw new RuntimeException("exec timed out in container " + containerId);
        }
        Long exitCode = dockerClient.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
        if (exitCode == null || exitCode != 0) {
            throw new RuntimeException("exec failed with code " + exitCode + ": " + stderr);
        }
        return stdout.toString();
    }
}
