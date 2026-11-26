package io.floci.aws.core.common.dns;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.DockerBridgeAddress;
import io.floci.aws.services.cloudmap.CloudMapService;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ecs.EcsService;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.model.AwsVpcConfiguration;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.LaunchType;
import io.floci.aws.services.ecs.model.NetworkConfiguration;
import io.floci.aws.services.ecs.model.NetworkMode;
import io.floci.aws.services.ecs.model.TaskDefinition;
import io.floci.aws.services.route53.Route53Service;
import io.floci.aws.services.route53.model.ResourceRecord;
import io.floci.aws.services.route53.model.ResourceRecordSet;
import io.floci.aws.services.route53.model.VpcAssociation;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.EcsTaskWaits.awaitLastStatus;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@QuarkusTest
@TestProfile(EcsPrivateDnsGuestDockerNightlyIntegrationTest.GuestProfile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class EcsPrivateDnsGuestDockerNightlyIntegrationTest {

    private static final String REGION = "us-east-1";

    public static final class GuestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecs.mock", "false",
                    "floci.network.security-group-enforcement.enabled", "false",
                    "floci.services.ec2.vpc-networks.enabled", "false",
                    "floci.docker.resource-namespace", "ecs-private-dns-guest-it");
        }
    }

    @Inject
    EcsService ecs;
    @Inject
    Ec2Service ec2;
    @Inject
    Route53Service route53;
    @Inject
    CloudMapService cloudMap;
    @Inject
    DockerClient docker;
    @Inject
    EcsContainerManager containers;
    @Inject
    EmbeddedDnsServer dns;
    @Inject
    DockerBridgeAddress bridge;
    @Inject
    Vertx vertx;

    @Test
    void managedGuestQueriesAreScopedToTheirVpcAndStopRevokesTheirOrigin() throws Exception {
        assumeTrue(DockerAvailability.isAvailable(), "Docker is required");
        String address = bridge.resolve().orElse(null);
        assumeTrue(address != null, "A local Docker bridge is required");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String cluster = "dns-guest-" + suffix;
        String name = "api.guest-" + suffix + ".example";
        List<String> vpcs = new ArrayList<>();
        List<String> subnets = new ArrayList<>();
        List<EcsTask> tasks = new ArrayList<>();
        String definition = null;
        String zone = null;
        String namespace = null;
        String discoveryService = null;
        String discoveryName = "api.discovery-" + suffix + ".example";
        ResourceRecordSet record = new ResourceRecordSet();
        record.setName(name);
        record.setType("A");
        record.setTtl(60L);
        record.setRecords(List.of(new ResourceRecord("10.73.1.7")));
        DatagramSocket socket = dns.bind(vertx, address, 0, false).toCompletionStage()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        ecs.createCluster(cluster, REGION);
        try {
            for (int octet : List.of(73, 74)) {
                String vpc = ec2.createVpc(REGION, "10." + octet + ".0.0/16", false).getVpcId();
                vpcs.add(vpc);
                subnets.add(ec2.createSubnet(REGION, vpc, "10." + octet + ".1.0/24", REGION + "a")
                        .getSubnetId());
            }
            zone = route53.createHostedZone("guest-" + suffix + ".example", suffix, null,
                    new VpcAssociation(vpcs.getFirst(), REGION)).zone().getId();
            route53.changeResourceRecordSets(zone, List.of(Map.of("action", "CREATE", "rrs", record)), null);
            namespace = cloudMap.createPrivateDnsNamespace("discovery-" + suffix + ".example",
                    vpcs.getFirst(), null, null, Map.of(), REGION).getTargets().get("NAMESPACE");
            discoveryService = cloudMap.createService("api", namespace, null, null,
                    "{\"DnsRecords\":[{\"Type\":\"A\",\"TTL\":15}]}", null, null, null, Map.of(), REGION).getId();
            cloudMap.registerInstance(discoveryService, "guest-target", null,
                    Map.of("AWS_INSTANCE_IPV4", "10.73.1.7"), REGION);
            ContainerDefinition app = new ContainerDefinition();
            app.setName("dns-client");
            app.setImage(TestImages.PYTHON_ALPINE);
            app.setCommand(List.of("python", "-c", """
                    import os, signal, sys
                    os.mkfifo('/tmp/dns-release')
                    def stopping(signum, frame):
                        open('/tmp/dns-stopping', 'w').close()
                        with open('/tmp/dns-release') as release:
                            release.read()
                        sys.exit(0)
                    signal.signal(signal.SIGTERM, stopping)
                    open('/tmp/dns-ready', 'w').close()
                    while True:
                        signal.pause()
                    """));
            app.setStopTimeout(30);
            TaskDefinition registered = ecs.registerTaskDefinition("dns-guest-" + suffix, List.of(app),
                    NetworkMode.awsvpc, null, null, null, null, null, REGION);
            definition = registered.getTaskDefinitionArn();
            for (String subnet : subnets) {
                AwsVpcConfiguration attachment = new AwsVpcConfiguration();
                attachment.setSubnets(List.of(subnet));
                NetworkConfiguration network = new NetworkConfiguration();
                network.setAwsvpcConfiguration(attachment);
                EcsTask task = ecs.runTask(cluster, definition, 1, LaunchType.FARGATE,
                        null, null, List.of(), network, REGION).getFirst();
                tasks.add(task);
                EcsTask running = awaitLastStatus(
                        () -> ecs.describeTasks(cluster, List.of(task.getTaskArn()), REGION).getFirst(),
                        EcsTask::getLastStatus, Duration.ofMinutes(2), "RUNNING", "STOPPED");
                assertEquals("RUNNING", running.getLastStatus(), running.getStoppedReason());
                tasks.set(tasks.size() - 1, running);
                awaitFile(running, "/tmp/dns-ready");
            }
            byte[] allowed = query(tasks.getFirst(), name, address, socket.localAddress().port());
            assertEquals(0, ByteBuffer.wrap(allowed).getShort(2) & 15);
            assertEquals(1, ByteBuffer.wrap(allowed).getShort(6));
            assertArrayEquals(new byte[]{10, 73, 1, 7},
                    Arrays.copyOfRange(allowed, allowed.length - 4, allowed.length));
            byte[] denied = query(tasks.getLast(), name, address, socket.localAddress().port());
            assertEquals(0, ByteBuffer.wrap(denied).getShort(6),
                    "A real guest from an unrelated VPC must not receive the private answer");
            byte[] discoveryAllowed = query(tasks.getFirst(), discoveryName, address, socket.localAddress().port());
            assertEquals(0, ByteBuffer.wrap(discoveryAllowed).getShort(2) & 15);
            assertEquals(1, ByteBuffer.wrap(discoveryAllowed).getShort(6));
            assertArrayEquals(new byte[]{10, 73, 1, 7},
                    Arrays.copyOfRange(discoveryAllowed, discoveryAllowed.length - 4, discoveryAllowed.length));
            byte[] discoveryDenied = query(tasks.getLast(), discoveryName, address, socket.localAddress().port());
            assertEquals(0, ByteBuffer.wrap(discoveryDenied).getShort(6),
                    "An unrelated guest must not receive a private CloudMap answer");
            String dockerId = tasks.getFirst().getContainers().getFirst().getDockerId();
            InspectContainerResponse guest = docker.inspectContainerCmd(dockerId).exec();
            String networkMode = guest.getHostConfig().getNetworkMode();
            assertEquals(usesSharedNamespace(), networkMode.startsWith("container:"),
                    "The guest must use the network mode selected by this fixture");
            String transportId = usesSharedNamespace() ? networkMode.substring("container:".length()) : dockerId;
            List<String> transportAddresses = docker.inspectContainerCmd(transportId).exec()
                    .getNetworkSettings().getNetworks().values().stream()
                    .map(network -> network.getIpAddress()).filter(ip -> ip != null && !ip.isBlank()).toList();
            assertTrue(transportAddresses.stream().anyMatch(ip -> containers.vpcForClient(ip).isPresent()));
            stopWithGuestQueryDuringGrace(cluster, tasks.getFirst(), List.of(name, discoveryName),
                    address, socket.localAddress().port());
            assertTrue(transportAddresses.stream().allMatch(ip -> containers.vpcForClient(ip).isEmpty()),
                    "Confirmed guest stop must revoke its actual transport addresses");
        } finally {
            for (EcsTask task : tasks.reversed()) {
                String id = task.getContainers().getFirst().getDockerId();
                if (id != null) {
                    try {
                        docker.killContainerCmd(id).exec();
                    } catch (NotFoundException | ConflictException ignored) {
                        // The tested stop may already have removed or stopped this container.
                    }
                }
                ecs.stopTask(cluster, task.getTaskArn(), "DNS guest cleanup", REGION);
            }
            if (definition != null) {
                ecs.deregisterTaskDefinition(definition, REGION);
            }
            ecs.deleteCluster(cluster, REGION);
            if (zone != null) {
                route53.changeResourceRecordSets(zone, List.of(Map.of("action", "DELETE", "rrs", record)), null);
                route53.deleteHostedZone(zone);
            }
            if (discoveryService != null) {
                cloudMap.deregisterInstance(discoveryService, "guest-target", REGION);
                cloudMap.deleteService(discoveryService);
            }
            if (namespace != null) {
                cloudMap.deleteNamespace(namespace, REGION);
            }
            for (String subnet : subnets.reversed()) {
                ec2.deleteSubnet(REGION, subnet);
            }
            for (String vpc : vpcs.reversed()) {
                ec2.deleteVpc(REGION, vpc);
            }
            socket.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    boolean usesSharedNamespace() {
        return false;
    }

    private void awaitFile(EcsTask task, String file) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ContainerExec.Result result = ContainerExec.run(docker,
                    task.getContainers().getFirst().getDockerId(),
                    new String[] {"python", "-c", "import os,sys; sys.exit(not os.path.exists(sys.argv[1]))", file}, 5);
            assertEquals(0, result.exitCode(), result.summary());
        });
    }

    private void stopWithGuestQueryDuringGrace(String cluster, EcsTask task, List<String> names,
                                              String address, int port) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> stopped = executor.submit(() -> ecs.stopTask(cluster, task.getTaskArn(), "DNS grace test", REGION));
        try {
            awaitFile(task, "/tmp/dns-stopping");
            try {
                for (String name : names) {
                    byte[] duringGrace = query(task, name, address, port);
                    assertEquals(1, ByteBuffer.wrap(duringGrace).getShort(6),
                            "A real guest must retain private DNS during grace: " + name);
                    assertArrayEquals(new byte[]{10, 73, 1, 7},
                            Arrays.copyOfRange(duringGrace, duringGrace.length - 4, duringGrace.length));
                }
            } finally {
                ContainerExec.Result released = ContainerExec.run(docker,
                        task.getContainers().getFirst().getDockerId(),
                        new String[] {"python", "-c", "with open('/tmp/dns-release','w') as release: release.write('done')"},
                        5);
                assertEquals(0, released.exitCode(), released.summary());
            }
            stopped.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(40, TimeUnit.SECONDS), "Owned stop worker must finish");
        }
    }

    private byte[] query(EcsTask task, String name, String address, int port) throws Exception {
        byte[] packet = DnsTestQuery.query(name, 1, 0x1234);
        ContainerExec.Result result = ContainerExec.run(docker, task.getContainers().getFirst().getDockerId(),
                new String[] {"python", "-c",
                        "import socket,sys; s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); s.settimeout(3); "
                                + "s.sendto(bytes.fromhex(sys.argv[1]),(sys.argv[2],int(sys.argv[3]))); "
                                + "print(s.recvfrom(4096)[0].hex()); s.close()",
                        HexFormat.of().formatHex(packet), address, Integer.toString(port)}, 10);
        assertEquals(0, result.exitCode(), result.summary());
        byte[] response = HexFormat.of().parseHex(result.stdout().replaceAll("\\s", ""));
        assertTrue(response.length >= 12, "Guest must receive an actual DNS packet: " + result.summary());
        assertEquals(0x1234, ByteBuffer.wrap(response).getShort(0) & 65535);
        return response;
    }
}
