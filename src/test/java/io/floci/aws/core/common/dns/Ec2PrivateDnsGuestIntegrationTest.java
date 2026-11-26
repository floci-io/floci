package io.floci.aws.core.common.dns;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.common.docker.DockerBridgeAddress;
import io.floci.aws.services.cloudmap.CloudMapService;
import io.floci.aws.services.ec2.AmiImageResolver;
import io.floci.aws.services.ec2.Ec2MetadataServer;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.ResolvedAmiImage;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.route53.Route53Service;
import io.floci.aws.services.route53.model.ResourceRecord;
import io.floci.aws.services.route53.model.ResourceRecordSet;
import io.floci.aws.services.route53.model.VpcAssociation;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.TestImages;
import io.quarkus.test.InjectMock;
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
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(Ec2PrivateDnsGuestIntegrationTest.GuestProfile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
class Ec2PrivateDnsGuestIntegrationTest {

    private static final String REGION = "us-east-1";

    public static final class GuestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "false",
                    "floci.network.security-group-enforcement.enabled", "false",
                    "floci.services.ec2.vpc-networks.enabled", "false",
                    "floci.docker.resource-namespace", "ec2-private-dns-guest-it");
        }
    }

    @Inject
    Ec2Service ec2;
    @Inject
    Ec2MetadataServer metadata;
    @Inject
    Route53Service route53;
    @Inject
    CloudMapService cloudMap;
    @Inject
    DockerClient docker;
    @Inject
    EmbeddedDnsServer dns;
    @Inject
    DockerBridgeAddress bridge;
    @Inject
    Vertx vertx;
    @InjectMock
    AmiImageResolver images;

    @Test
    void guestDnsIsVpcScopedAndFollowsStopRestartAndTermination() throws Exception {
        assumeTrue(DockerAvailability.isAvailable(), "Docker is required");
        String address = bridge.resolve().orElse(null);
        assumeTrue(address != null, "A local Docker bridge is required");
        when(images.resolveImage(anyString())).thenReturn(ResolvedAmiImage.minimal(TestImages.PYTHON_ALPINE));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "api.ec2-guest-" + suffix + ".example";
        String discoveryName = "api.ec2-discovery-" + suffix + ".example";
        List<String> vpcs = new ArrayList<>();
        List<String> subnets = new ArrayList<>();
        List<Instance> instances = new ArrayList<>();
        String zone = null;
        String namespace = null;
        String discoveryService = null;
        ResourceRecordSet record = new ResourceRecordSet();
        record.setName(name);
        record.setType("A");
        record.setTtl(60L);
        record.setRecords(List.of(new ResourceRecord("10.75.1.7")));
        DatagramSocket socket = dns.bind(vertx, address, 0, false).toCompletionStage()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        try {
            for (int octet : List.of(75, 76)) {
                String vpc = ec2.createVpc(REGION, "10." + octet + ".0.0/16", false).getVpcId();
                vpcs.add(vpc);
                subnets.add(ec2.createSubnet(REGION, vpc, "10." + octet + ".1.0/24", REGION + "a").getSubnetId());
            }
            zone = route53.createHostedZone("ec2-guest-" + suffix + ".example", suffix, null,
                    new VpcAssociation(vpcs.getFirst(), REGION)).zone().getId();
            route53.changeResourceRecordSets(zone, List.of(Map.of("action", "CREATE", "rrs", record)), null);
            namespace = cloudMap.createPrivateDnsNamespace("ec2-discovery-" + suffix + ".example",
                    vpcs.getFirst(), null, null, Map.of(), REGION).getTargets().get("NAMESPACE");
            discoveryService = cloudMap.createService("api", namespace, null, null,
                    "{\"DnsRecords\":[{\"Type\":\"A\",\"TTL\":15}]}",
                    null, null, null, Map.of(), REGION).getId();
            cloudMap.registerInstance(discoveryService, "guest-target", null,
                    Map.of("AWS_INSTANCE_IPV4", "10.75.1.7"), REGION);
            for (String subnet : subnets) {
                Instance instance = ec2.runInstances(REGION, "ami-alpine", "t3.micro", 1, 1,
                        null, List.of(), subnet, null, List.of(), null, null).getInstances().getFirst();
                instances.add(instance);
                ec2.awaitContainerLaunch(instance);
                assertEquals("running", instance.getState().getName());
            }
            for (String hostname : List.of(name, discoveryName)) {
                assertAnswer(instances.getFirst(), hostname, address, socket.localAddress().port());
                byte[] denied = query(instances.getLast(), hostname, address, socket.localAddress().port());
                assertEquals(0, ByteBuffer.wrap(denied).getShort(6), "Unrelated VPC must not receive " + hostname);
            }
            Instance instance = instances.getFirst();
            List<String> transportAddresses = transportAddresses(instance);
            assertTrue(transportAddresses.stream().anyMatch(ip -> metadata.vpcForClient(ip).isPresent()));
            ec2.stopInstances(REGION, List.of(instance.getInstanceId()));
            await().atMost(Duration.ofSeconds(40)).until(() -> "stopped".equals(instance.getState().getName()));
            assertFalse(docker.inspectContainerCmd(instance.getDockerContainerId()).exec().getState().getRunning());
            assertTrue(transportAddresses.stream().allMatch(ip -> metadata.vpcForClient(ip).isEmpty()),
                    "Confirmed stop must revoke the guest transport origin");
            ec2.startInstances(REGION, List.of(instance.getInstanceId()));
            ec2.awaitContainerLaunch(instance);
            for (String hostname : List.of(name, discoveryName)) {
                assertAnswer(instance, hostname, address, socket.localAddress().port());
            }
            List<String> restartedAddresses = transportAddresses(instance);
            terminateAndAwait(instance);
            assertTrue(restartedAddresses.stream().allMatch(ip -> metadata.vpcForClient(ip).isEmpty()),
                    "Confirmed termination must revoke the restarted guest origin");
        } finally {
            for (Instance instance : instances.reversed()) {
                terminateAndAwait(instance);
            }
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

    List<String> transportAddresses(Instance instance) {
        return docker.inspectContainerCmd(instance.getDockerContainerId()).exec()
                .getNetworkSettings().getNetworks().values().stream()
                .map(network -> network.getIpAddress()).filter(ip -> ip != null && !ip.isBlank()).toList();
    }

    private void terminateAndAwait(Instance instance) {
        ec2.terminateInstances(REGION, List.of(instance.getInstanceId()));
        await().atMost(Duration.ofSeconds(30)).until(() -> "terminated".equals(instance.getState().getName()));
    }

    private void assertAnswer(Instance instance, String name, String address, int port) throws Exception {
        byte[] response = query(instance, name, address, port);
        assertEquals(0, ByteBuffer.wrap(response).getShort(2) & 15);
        assertEquals(1, ByteBuffer.wrap(response).getShort(6));
        assertArrayEquals(new byte[]{10, 75, 1, 7}, Arrays.copyOfRange(response, response.length - 4, response.length));
    }

    private byte[] query(Instance instance, String name, String address, int port) throws Exception {
        byte[] packet = DnsTestQuery.query(name, 1, 0x1234);
        ContainerExec.Result result = ContainerExec.run(docker, instance.getDockerContainerId(),
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
