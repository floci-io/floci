package io.floci.aws.core.common.dns;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeleteLoadBalancerRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.AliasTarget;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.DeleteHostedZoneRequest;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Signed management calls followed by real UDP queries through CDI-discovered DNS sources. */
@QuarkusTest
@TestProfile(ElbV2AliasDnsIntegrationTest.Profile.class)
class ElbV2AliasDnsIntegrationTest {
    /** Own the listener address for this class without changing another class's DNS singleton. */
    public static class Profile implements QuarkusTestProfile {
    }

    @Inject
    EmbeddedDnsServer dns;
    @Inject
    Vertx vertx;

    @ParameterizedTest
    @CsvSource({"internal,A,1", "internet-facing,A,1", "internal,AAAA,28", "internet-facing,AAAA,28"})
    void publicAliasValidatesItsTargetAcrossAccounts(String scheme, String type, int queryType) throws Exception {
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "730000000001", Region.EU_WEST_1);
             ElasticLoadBalancingV2Client elb = AwsTestClients.create(
                     ElasticLoadBalancingV2Client.builder(), "730000000001", Region.EU_WEST_1);
             ElasticLoadBalancingV2Client other = AwsTestClients.create(
                     ElasticLoadBalancingV2Client.builder(), "730000000002", Region.EU_WEST_1);
             Route53Client route53 = AwsTestClients.create(
                     Route53Client.builder(), "730000000002", Region.EU_WEST_1)) {
            String name = "api." + UUID.randomUUID() + ".example.test";
            String vpc = ec2.createVpc(r -> r.cidrBlock("10.73.0.0/16")).vpc().vpcId();
            List<String> subnets = new ArrayList<>();
            String zone = null;
            String arn = null;
            ResourceRecordSet installed = null;
            DatagramSocket listener = null;
            try {
                listener = dns.listen(vertx, "127.0.0.1", 0)
                        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                subnets.add(ec2.createSubnet(r -> r.vpcId(vpc).cidrBlock("10.73.1.0/24")
                        .availabilityZone("eu-west-1a")).subnet().subnetId());
                subnets.add(ec2.createSubnet(r -> r.vpcId(vpc).cidrBlock("10.73.2.0/24")
                        .availabilityZone("eu-west-1b")).subnet().subnetId());
                LoadBalancer target = elb.createLoadBalancer(
                        r -> r.name("dns-" + UUID.randomUUID().toString().substring(0, 20))
                                .type("application").scheme(scheme).subnets(subnets)).loadBalancers().getFirst();
                arn = target.loadBalancerArn();
                assertTrue(arn.contains(":730000000001:"));
                assertTrue(other.describeLoadBalancers().loadBalancers().stream()
                        .noneMatch(lb -> lb.loadBalancerArn().equals(target.loadBalancerArn())));
                zone = route53.createHostedZone(r -> r.name(name).callerReference(UUID.randomUUID().toString()))
                        .hostedZone().id();
                installed = alias(name, type, target.dnsName(), target.canonicalHostedZoneId());
                change(route53, zone, ChangeAction.CREATE, installed);
                assertValidTarget(query(listener, name, queryType), queryType);

                installed = alias(name, type, "dualstack." + target.dnsName().toUpperCase(Locale.ROOT),
                        target.canonicalHostedZoneId());
                change(route53, zone, ChangeAction.UPSERT, installed);
                assertValidTarget(query(listener, name.toUpperCase(Locale.ROOT) + ".", queryType), queryType);

                installed = alias(name, type, target.dnsName(), "WRONG");
                change(route53, zone, ChangeAction.UPSERT, installed);
                assertEquals(5, rcode(query(listener, name, queryType)));
                installed = alias(name, type, target.dnsName(), target.canonicalHostedZoneId());
                change(route53, zone, ChangeAction.UPSERT, installed);
                assertValidTarget(query(listener, name, queryType), queryType);

                elb.deleteLoadBalancer(r -> r.loadBalancerArn(target.loadBalancerArn()));
                arn = null;
                assertEquals(5, rcode(query(listener, name, queryType)),
                        "A deleted target must not resolve through the emulator suffix or upstream DNS");
                change(route53, zone, ChangeAction.DELETE, installed);
                installed = null;
                byte[] removed = query(listener, name, queryType);
                assertEquals(0, rcode(removed));
                assertEquals(0, ByteBuffer.wrap(removed).getShort(6));
                assertEquals(1, ByteBuffer.wrap(removed).getShort(8));
            } finally {
                if (listener != null) {
                    listener.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                }
                if (installed != null) {
                    change(route53, zone, ChangeAction.DELETE, installed);
                }
                if (zone != null) {
                    route53.deleteHostedZone(DeleteHostedZoneRequest.builder().id(zone).build());
                }
                if (arn != null) {
                    elb.deleteLoadBalancer(DeleteLoadBalancerRequest.builder().loadBalancerArn(arn).build());
                }
                for (String subnet : subnets) {
                    ec2.deleteSubnet(r -> r.subnetId(subnet));
                }
                ec2.deleteVpc(r -> r.vpcId(vpc));
            }
        }
    }

    private static ResourceRecordSet alias(String name, String type, String target, String zone) {
        return ResourceRecordSet.builder().name(name).type(type).aliasTarget(AliasTarget.builder()
                .dnsName(target + ".").hostedZoneId(zone).evaluateTargetHealth(false).build()).build();
    }

    private static void change(Route53Client client, String zone, ChangeAction action, ResourceRecordSet record) {
        client.changeResourceRecordSets(r -> r.hostedZoneId(zone).changeBatch(b -> b.changes(
                Change.builder().action(action).resourceRecordSet(record).build())));
    }

    private static byte[] query(DatagramSocket listener, String name, int type) throws Exception {
        byte[] encoded = DnsRecord.encodeName(name);
        byte[] request = ByteBuffer.allocate(16 + encoded.length)
                .putShort((short) 1234).putShort((short) 256).putShort((short) 1)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0)
                .put(encoded).putShort((short) type).putShort((short) 1).array();
        // Vert.x and the JDK both name their UDP socket DatagramSocket.
        try (java.net.DatagramSocket client = new java.net.DatagramSocket(
                0, InetAddress.getByName("127.0.0.1"))) {
            client.connect(InetAddress.getByName("127.0.0.1"), listener.localAddress().port());
            client.setSoTimeout(5000);
            client.send(new DatagramPacket(request, request.length));
            DatagramPacket reply = new DatagramPacket(new byte[4096], 4096);
            client.receive(reply);
            return Arrays.copyOf(reply.getData(), reply.getLength());
        }
    }

    private static int rcode(byte[] answer) {
        return ByteBuffer.wrap(answer).getShort(2) & 15;
    }

    private static void assertValidTarget(byte[] answer, int type) {
        ByteBuffer packet = ByteBuffer.wrap(answer);
        assertEquals(0, rcode(answer), "A known stored alias target must resolve authoritatively");
        assertEquals(0x0400, packet.getShort(2) & 0x0400);
        assertEquals(0, packet.getShort(2) & 0x0080);
        assertEquals(type == 1 ? 1 : 0, packet.getShort(6));
        if (type == 28) {
            assertEquals(1, packet.getShort(8));
            return;
        }
        packet.position(12);
        EmbeddedDnsServer.readName(packet, answer);
        packet.getInt();
        EmbeddedDnsServer.readName(packet, answer);
        assertEquals(1, packet.getShort());
        assertEquals(1, packet.getShort());
        assertEquals(60, packet.getInt());
        assertEquals(4, packet.getShort());
        byte[] value = new byte[4];
        packet.get(value);
        assertArrayEquals(new byte[] {127, 0, 0, 1}, value);
    }
}
