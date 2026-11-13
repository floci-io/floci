package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.IpRange;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.testing.TestImages;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SecurityGroupFirewallDockerIntegrationTest {

    private static final String METADATA_AND_WEB_SERVERS = """
            from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
            from sys import argv

            class Handler(BaseHTTPRequestHandler):
                def respond(self, body):
                    payload = body.encode()
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)

                def do_PUT(self):
                    if self.path == "/latest/api/token" and self.headers.get(
                            "X-aws-ec2-metadata-token-ttl-seconds"):
                        self.respond("fixture-token")
                    else:
                        self.send_error(400)

                def do_GET(self):
                    if self.server.server_port == 80:
                        self.respond("web-ok")
                    elif self.path == "/latest/meta-data/instance-id" and self.headers.get(
                            "X-aws-ec2-metadata-token") == "fixture-token":
                        self.respond("i-fixture")
                    else:
                        self.send_error(401)

                def log_message(self, format, *args):
                    pass

            if argv[1] == "metadata":
                ThreadingHTTPServer(("127.0.0.1", 9169), Handler).serve_forever()
            else:
                ThreadingHTTPServer(("0.0.0.0", 80), Handler).serve_forever()
            """;
    private static final String IMDS_CLIENT = """
            from urllib.request import ProxyHandler, Request, build_opener
            opener = build_opener(ProxyHandler({}))
            endpoint = "http://169.254.169.254"
            token = opener.open(Request(endpoint + "/latest/api/token", method="PUT",
                headers={"X-aws-ec2-metadata-token-ttl-seconds": "60"}), timeout=2).read().decode()
            instance = opener.open(Request(endpoint + "/latest/meta-data/instance-id",
                headers={"X-aws-ec2-metadata-token": token}), timeout=2).read().decode()
            print(token)
            print(instance)
            """;

    // A denied connection is dropped without a reply, so it only ends when nc's timeout expires;
    // a permitted one connects in tens of milliseconds, so one second is ample to call it denied.
    private static final int PERMITTED_TIMEOUT_SECONDS = 2;
    private static final int DENIED_TIMEOUT_SECONDS = 1;

    @Inject SecurityGroupFirewallManager firewall;
    @Inject ContainerLifecycleManager lifecycle;
    @Inject ContainerBuilder builder;
    @Inject DockerClient docker;

    @Test
    void legacyImdsRelayMigratesOfflineAndLeavesWildcardHttpPortAvailable() throws Exception {
        Assumptions.assumeTrue(firewall.enabled());
        try {
            Assumptions.assumeTrue("linux".equalsIgnoreCase(docker.infoCmd().exec().getOsType()));
        } catch (Exception e) {
            Assumptions.abort("Docker daemon is unavailable");
        }

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String eniId = "eni-imds-" + suffix;
        SecurityGroupFirewallManager.Namespace namespace = null;
        String fixtureContainer = null;
        String worker = null;
        try {
            namespace = firewall.createNamespace("ec2", "imds-" + suffix, "000000000000",
                    "us-east-1", Optional.empty(), Map.of());
            SecurityGroup group = group("sg-imds-" + suffix, false, "0.0.0.0/0");
            firewall.register(endpoint(eniId, "10.0.0.2", namespace, group), namespace.helperId(), Map.of());
            fixtureContainer = lifecycle.createAndStart(builder.newContainer(TestImages.PYTHON_ALPINE)
                    .withName("floci-imds-fixture-" + suffix)
                    .withNetworkMode("container:" + namespace.helperId())
                    .withEntrypoint(List.of("python", "-c"))
                    .withCmd(List.of(METADATA_AND_WEB_SERVERS, "metadata"))
                    .build()).containerId();

            ContainerExec.Result fixture = pollCurl(docker, namespace.helperId(), "--noproxy", "*",
                    "-fsS", "--max-time", "1", "-X", "PUT", "-H",
                    "X-aws-ec2-metadata-token-ttl-seconds: 60",
                    "http://127.0.0.1:9169/latest/api/token");
            assertEquals(0, fixture.exitCode(), "IMDS fixture did not start: " + fixture.summary());
            assertEquals("fixture-token", fixture.stdout().trim());

            ContainerExec.Result legacy = ContainerExec.runMerged(docker, namespace.helperId(),
                    Ec2MetadataProxy.startCommand("127.0.0.1", 9169), 30);
            assertEquals(0, legacy.exitCode(), "Old IMDS proxy did not start: " + legacy.summary());

            ContainerExec.Result removed = ContainerExec.runMerged(docker, namespace.helperId(),
                    new String[]{"apk", "del", "iptables"}, 10);
            assertEquals(0, removed.exitCode(), "Could not simulate the old helper image: " + removed.summary());
            ContainerExec.Result missing = ContainerExec.runMerged(docker, namespace.helperId(),
                    new String[]{"sh", "-c", "! command -v iptables && command -v nft"}, 5);
            assertEquals(0, missing.exitCode(), "Old helper tool state is wrong: " + missing.summary());

            ContainerExec.Result install = ContainerExec.runMerged(docker, namespace.helperId(),
                    Ec2ContainerManager.metadataProxyInstallCommand(), 20);
            assertEquals(0, install.exitCode(), "Offline IMDS dependencies are unavailable: " + install.summary());
            String[] start = Ec2ContainerManager.metadataProxyStartCommand("127.0.0.1", 9169);
            ContainerExec.Result proxy = ContainerExec.runMerged(docker, namespace.helperId(), start, 30);
            assertEquals(0, proxy.exitCode(), "IMDS proxy did not start: " + proxy.summary());

            worker = lifecycle.createAndStart(builder.newContainer(TestImages.PYTHON_ALPINE)
                    .withName("floci-imds-web-test-" + suffix)
                    .withNetworkMode("container:" + namespace.helperId())
                    .withEntrypoint(List.of("python", "-c"))
                    .withCmd(List.of(METADATA_AND_WEB_SERVERS, "web"))
                    .build()).containerId();
            ContainerExec.Result web = pollCurl(docker, namespace.helperId(), "--noproxy", "*",
                    "-fsS", "--max-time", "1", "http://127.0.0.1:80/");
            assertEquals(0, web.exitCode(), "Wildcard port 80 did not serve HTTP: " + web.summary());
            assertEquals("web-ok", web.stdout().trim());

            ContainerExec.Result imds = ContainerExec.runMerged(docker, worker,
                    new String[]{"python", "-c", IMDS_CLIENT}, 10);
            assertEquals(0, imds.exitCode(), "Workload could not reach IMDSv2: " + imds.summary());
            assertEquals("fixture-token\ni-fixture", imds.stdout().trim().replace("\r", ""));

            ContainerExec.Result reapplied = ContainerExec.runMerged(docker, namespace.helperId(), start, 30);
            assertEquals(0, reapplied.exitCode(), reapplied.summary());
            ContainerExec.Result stale = ContainerExec.runMerged(docker, namespace.helperId(),
                    new String[]{"nft", "flush", "chain", "ip", "floci_imds", "output"}, 5);
            assertEquals(0, stale.exitCode(), stale.summary());
            ContainerExec.Result otherRule = ContainerExec.runMerged(docker, namespace.helperId(),
                    new String[]{"sh", "-c", "printf '%s\\n'"
                            + " 'add rule ip floci_imds output ip daddr 192.0.2.1 counter"
                            + " comment \"guest-preserved\"'"
                            + " 'add rule ip floci_imds output ip daddr 169.254.169.254"
                            + " tcp dport 80 dnat to 169.254.169.254:42170"
                            + " comment \"floci-imds-relay-42169\"' | nft -f -"}, 5);
            assertEquals(0, otherRule.exitCode(), otherRule.summary());
            ContainerExec.Result repaired = ContainerExec.runMerged(docker, namespace.helperId(), start, 30);
            assertEquals(0, repaired.exitCode(), "A stale nft table was not repaired: " + repaired.summary());
            ContainerExec.Result rules = ContainerExec.runMerged(docker, namespace.helperId(),
                    new String[]{"nft", "list", "chain", "ip", "floci_imds", "output"}, 5);
            assertEquals(0, rules.exitCode(), rules.summary());
            assertEquals(1L, rules.stdout().lines()
                    .filter(line -> line.contains("floci-imds-relay-42169")
                            && line.contains("169.254.169.254:42169")).count());
            assertFalse(rules.stdout().contains("169.254.169.254:42170"), rules.summary());
            assertTrue(rules.stdout().contains("guest-preserved"), rules.summary());
            assertTrue(Boolean.TRUE.equals(docker.inspectContainerCmd(worker).exec().getState().getRunning()));
        } finally {
            if (worker != null) {
                lifecycle.removeIfExists(worker);
            }
            if (fixtureContainer != null) {
                lifecycle.removeIfExists(fixtureContainer);
            }
            firewall.unregister(eniId);
            if (namespace != null) {
                lifecycle.removeIfExists(namespace.helperId());
            }
        }
    }

    private static ContainerExec.Result pollCurl(DockerClient docker, String helperId, String... curlArgs)
            throws Exception {
        String[] command = new String[curlArgs.length + 1];
        command[0] = "curl";
        System.arraycopy(curlArgs, 0, command, 1, curlArgs.length);
        ContainerExec.Result result = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            result = ContainerExec.runMerged(docker, helperId, command, 3);
            if (result.exitCode() == 0) {
                break;
            }
            Thread.sleep(100);
        }
        return result;
    }

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
            assertNotEquals(0, connect(workers.get(1), target.transportAddress(), DENIED_TIMEOUT_SECONDS));

            IpRange corrected = new IpRange();
            corrected.setCidrIp("10.1.0.0/24");
            targetGroup.getIpPermissions().getFirst().getIpRanges().add(corrected);
            firewall.refreshPolicies("us-east-1", Map.of("sg-target", targetGroup,
                    "sg-source", sourceGroup), Map.of());
            assertEquals(0, connect(workers.get(1), target.transportAddress()));

            firewall.quarantineSurvivingNamespaces();
            assertNotEquals(0, connect(workers.get(1), target.transportAddress(), DENIED_TIMEOUT_SECONDS));
            firewall.refreshPolicies("us-east-1", Map.of("sg-target", targetGroup,
                    "sg-source", sourceGroup), Map.of());
            assertEquals(0, connect(workers.get(1), target.transportAddress()));

            SecurityGroup replacement = group("sg-replacement", false, "192.0.2.0/24");
            Map<String, SecurityGroup> groups = Map.of("sg-target", targetGroup, "sg-source", sourceGroup,
                    "sg-replacement", replacement);
            firewall.updateGroups(targetEni, Set.of("sg-replacement"), groups, Map.of());
            assertNotEquals(0, connect(workers.get(1), target.transportAddress(), DENIED_TIMEOUT_SECONDS));
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
        return connect(worker, address, PERMITTED_TIMEOUT_SECONDS);
    }

    private int connect(String worker, String address, int timeoutSeconds) throws Exception {
        String id = docker.execCreateCmd(worker).withAttachStdout(true).withAttachStderr(true)
                .withCmd("nc", "-z", "-w", String.valueOf(timeoutSeconds), address, "8080")
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
