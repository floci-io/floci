package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class SecurityGroupFirewallDockerIntegrationTest {

    @Inject SecurityGroupFirewallManager firewall;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject ContainerBuilder builder;
    @Inject DockerClient docker;

    @Test
    void deniedLogicalCidrBlocksManagedPeerThenRuleUpdatePermitsIt() throws Exception {
        Assumptions.assumeTrue(firewall.enabled());
        try {
            Assumptions.assumeTrue("linux".equalsIgnoreCase(docker.infoCmd().exec().getOsType()));
        } catch (Exception e) {
            Assumptions.abort("Docker daemon is unavailable");
        }

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String targetEni = "eni-target-" + suffix;
        String sourceEni = "eni-source-" + suffix;
        List<SecurityGroupFirewallManager.Namespace> namespaces = new ArrayList<>();
        List<String> workers = new ArrayList<>();
        try {
            SecurityGroupFirewallManager.Namespace target = firewall.createNamespace("ec2", "sg-target-" + suffix, "000000000000",
                    "us-east-1", Optional.empty(), Map.of());
            namespaces.add(target);
            SecurityGroupFirewallManager.Namespace source = firewall.createNamespace("ec2", "sg-source-" + suffix, "000000000000",
                    "us-east-1", Optional.empty(), Map.of());
            namespaces.add(source);

            SecurityGroup targetGroup = group("sg-target", false, "10.0.0.0/24");
            SecurityGroup sourceGroup = group("sg-source", true, "0.0.0.0/0");
            firewall.register(endpoint(targetEni, "10.0.0.2", target, targetGroup),
                    target.helperId(), Map.of());
            firewall.register(endpoint(sourceEni, "10.1.0.2", source, sourceGroup),
                    source.helperId(), Map.of());

            workers.add(lifecycle.createAndStart(builder.newContainer("alpine:3.21")
                    .withName("floci-sg-test-target-" + suffix)
                    .withNetworkMode("container:" + target.helperId())
                    .withEntrypoint(List.of("sh", "-c"))
                    .withCmd(List.of("exec nc -lk -p 8080 -e /bin/cat"))
                    .build()).containerId());
            workers.add(lifecycle.createAndStart(builder.newContainer("alpine:3.21")
                    .withName("floci-sg-test-source-" + suffix)
                    .withNetworkMode("container:" + source.helperId())
                    .withEntrypoint(List.of("sleep"))
                    .withCmd(List.of("600"))
                    .build()).containerId());

            for (int attempt = 0; attempt < 20 && connect(workers.getFirst(), "127.0.0.1") != 0; attempt++) {
                Thread.sleep(100);
            }
            assertEquals(0, connect(workers.getFirst(), "127.0.0.1"));
            assertNotEquals(0, connect(workers.get(1), target.transportAddress()));

            IpRange corrected = new IpRange();
            corrected.setCidrIp("10.1.0.0/24");
            targetGroup.getIpPermissions().getFirst().getIpRanges().add(corrected);
            firewall.refreshPolicies("us-east-1", Map.of("sg-target", targetGroup,
                    "sg-source", sourceGroup), Map.of());
            assertEquals(0, connect(workers.get(1), target.transportAddress()));

            firewall.quarantineSurvivingNamespaces();
            assertNotEquals(0, connect(workers.get(1), target.transportAddress()));
            firewall.refreshPolicies("us-east-1", Map.of("sg-target", targetGroup,
                    "sg-source", sourceGroup), Map.of());
            assertEquals(0, connect(workers.get(1), target.transportAddress()));

            SecurityGroup replacement = group("sg-replacement", false, "192.0.2.0/24");
            Map<String, SecurityGroup> groups = Map.of("sg-target", targetGroup, "sg-source", sourceGroup,
                    "sg-replacement", replacement);
            firewall.updateGroups(targetEni, Set.of("sg-replacement"), groups, Map.of());
            assertNotEquals(0, connect(workers.get(1), target.transportAddress()));
            firewall.updateGroups(targetEni, Set.of("sg-target"), groups, Map.of());
            assertEquals(0, connect(workers.get(1), target.transportAddress()));

            docker.stopContainerCmd(target.helperId()).withTimeout(0).exec();
            firewall.refreshPolicies("us-east-1", groups, Map.of());
            assertFalse(docker.inspectContainerCmd(workers.getFirst()).exec().getState().getRunning());
        } finally {
            for (String worker : workers) {
                lifecycle.removeIfExists(worker);
            }
            firewall.unregister(sourceEni);
            firewall.unregister(targetEni);
            for (SecurityGroupFirewallManager.Namespace namespace : namespaces) {
                lifecycle.removeIfExists(namespace.helperId());
            }
        }
    }

    private int connect(String worker, String address) throws Exception {
        String id = docker.execCreateCmd(worker).withAttachStdout(true).withAttachStderr(true)
                .withCmd("nc", "-z", "-w", "2", address, "8080")
                .exec().getId();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>()) {
            docker.execStartCmd(id).exec(callback).awaitCompletion(5, TimeUnit.SECONDS);
        }
        return docker.inspectExecCmd(id).exec().getExitCodeLong().intValue();
    }

    private static SecurityGroup group(String id, boolean egress, String cidr) {
        SecurityGroup group = new SecurityGroup();
        group.setGroupId(id);
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(8080);
        permission.setToPort(8080);
        IpRange range = new IpRange();
        range.setCidrIp(cidr);
        permission.getIpRanges().add(range);
        if (egress) {
            group.getIpPermissionsEgress().add(permission);
        } else {
            group.getIpPermissions().add(permission);
        }
        return group;
    }

    private static SecurityGroupNftCompiler.Endpoint endpoint(String eni, String logical,
            SecurityGroupFirewallManager.Namespace namespace, SecurityGroup group) {
        return new SecurityGroupNftCompiler.Endpoint("000000000000", "us-east-1", "vpc-1",
                eni, logical, namespace.transportAddress(), Set.of(group.getGroupId()), List.of(group));
    }
}
