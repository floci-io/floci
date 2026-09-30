package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.services.cloudmap.CloudMapDnsRecordSource;
import io.github.hectorvent.floci.services.cloudmap.CloudMapService;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudMapDnsPacketIntegrationTest {

    private static final String REGION = "us-east-1";

    @Inject
    CloudMapService cloudMapService;

    @Inject
    CloudMapDnsRecordSource cloudMapDnsRecordSource;

    @Inject
    Vertx vertx;

    @Test
    void configuredTtlReachesTheDnsPacket() throws Exception {
        String namespace = uniqueNamespace();
        String namespaceId = privateDnsNamespace(namespace);
        Service service = cloudMapService.createService("api", namespaceId, null, null,
                "{\"DnsRecords\":[{\"Type\":\"A\",\"TTL\":15}]}", null, null, null, Map.of(), REGION);
        cloudMapService.registerInstance(service.getId(), "task-1", null,
                Map.of("AWS_INSTANCE_IPV4", "172.31.0.6"), REGION);

        byte[] request = buildQuery("api." + namespace, (short) 1);
        byte[] response = query(new EmbeddedDnsServer(List.of(), List.of(cloudMapDnsRecordSource)), request);
        ByteBuffer packet = ByteBuffer.wrap(response);

        assertEquals(0, packet.getShort(2) & 0x000F);
        assertEquals(1, packet.getShort(6));
        assertEquals(15, packet.getInt(request.length + 6));
        assertArrayEquals(new byte[]{(byte) 172, 31, 0, 6},
                Arrays.copyOfRange(response, request.length + 12, request.length + 16));
    }

    @Test
    void srvOnlyNameReturnsNoDataForAQuery() throws Exception {
        String namespace = uniqueNamespace();
        String namespaceId = privateDnsNamespace(namespace);
        Service service = cloudMapService.createService("srvonly", namespaceId, null, null,
                "{\"DnsRecords\":[{\"Type\":\"SRV\",\"TTL\":300}]}", null, null, null, Map.of(), REGION);
        cloudMapService.registerInstance(service.getId(), "task-1", null,
                Map.of("AWS_INSTANCE_IPV4", "172.31.0.6", "AWS_INSTANCE_PORT", "8080"), REGION);

        byte[] response = query(new EmbeddedDnsServer(List.of(), List.of(cloudMapDnsRecordSource)),
                buildQuery("srvonly." + namespace, (short) 1));
        ByteBuffer packet = ByteBuffer.wrap(response);

        assertEquals(0, packet.getShort(2) & 0x000F);
        assertTrue((packet.getShort(2) & 0x0400) != 0);
        assertEquals(0, packet.getShort(6));
    }

    @Test
    void srvInstanceHostnameAnswersAQueryWithConfiguredTtl() throws Exception {
        String namespace = uniqueNamespace();
        String namespaceId = privateDnsNamespace(namespace);
        Service service = cloudMapService.createService("backend", namespaceId, null, null,
                "{\"DnsRecords\":[{\"Type\":\"SRV\",\"TTL\":300}]}", null, null, null, Map.of(), REGION);
        cloudMapService.registerInstance(service.getId(), "task-1", null,
                Map.of("AWS_INSTANCE_IPV4", "172.31.0.6", "AWS_INSTANCE_PORT", "8080"), REGION);

        byte[] request = buildQuery("task-1.backend." + namespace, (short) 1);
        byte[] response = query(new EmbeddedDnsServer(List.of(), List.of(cloudMapDnsRecordSource)), request);
        ByteBuffer packet = ByteBuffer.wrap(response);

        assertEquals(0, packet.getShort(2) & 0x000F);
        assertEquals(1, packet.getShort(6));
        assertEquals(300, packet.getInt(request.length + 6));
        assertArrayEquals(new byte[]{(byte) 172, 31, 0, 6},
                Arrays.copyOfRange(response, request.length + 12, request.length + 16));
    }

    @Test
    void absentServiceNameStillReturnsNxDomain() throws Exception {
        String namespace = uniqueNamespace();
        privateDnsNamespace(namespace);

        byte[] response = query(new EmbeddedDnsServer(List.of(), List.of(cloudMapDnsRecordSource)),
                buildQuery("missing." + namespace, (short) 1));

        assertEquals(3, ByteBuffer.wrap(response).getShort(2) & 0x000F);
    }

    private String privateDnsNamespace(String name) {
        return cloudMapService.createPrivateDnsNamespace(name, "vpc-dns", null, null,
                Map.of(), REGION).getTargets().get("NAMESPACE");
    }

    @Test
    void ipv6InstanceAnswersAaaaWithConfiguredTtl() throws Exception {
        String namespace = uniqueNamespace();
        Service service = typedService(namespace, "AAAA", "MULTIVALUE");
        cloudMapService.registerInstance(service.getId(), "task-1", null,
                Map.of("AWS_INSTANCE_IPV6", "2001:db8::1"), REGION);
        byte[] request = buildQuery("typed." + namespace, (short) 28);
        byte[] response = query(new EmbeddedDnsServer(List.of(), List.of(cloudMapDnsRecordSource)), request);
        ByteBuffer packet = ByteBuffer.wrap(response);
        assertEquals(1, packet.getShort(6));
        assertEquals(28, packet.getShort(request.length + 2));
        assertEquals(45, packet.getInt(request.length + 6));
        assertEquals(16, packet.getShort(request.length + 10));
        assertArrayEquals(new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0, 1},
                Arrays.copyOfRange(response, request.length + 12, response.length));
    }

    @Test
    void cnameRecordIsReturnedForBothCnameAndAddressQueries() throws Exception {
        String namespace = uniqueNamespace();
        Service service = typedService(namespace, "CNAME", "WEIGHTED");
        cloudMapService.registerInstance(service.getId(), "task-1", null,
                Map.of("AWS_INSTANCE_CNAME", "backend.example.com"), REGION);
        for (short type : new short[]{1, 5, 28}) {
            byte[] request = buildQuery("typed." + namespace, type);
            byte[] response = query(new EmbeddedDnsServer(List.of(), List.of(cloudMapDnsRecordSource)), request);
            ByteBuffer packet = ByteBuffer.wrap(response);
            assertEquals(1, packet.getShort(6));
            assertEquals(5, packet.getShort(request.length + 2));
            assertEquals(45, packet.getInt(request.length + 6));
            packet.position(request.length + 12);
            assertEquals("backend.example.com", EmbeddedDnsServer.readName(packet, response));
        }
    }

    @Test
    void srvRecordCarriesThePortAndInstanceHostname() throws Exception {
        String namespace = uniqueNamespace();
        Service service = typedService(namespace, "SRV", "MULTIVALUE");
        cloudMapService.registerInstance(service.getId(), "task-1", null,
                Map.of("AWS_INSTANCE_IPV6", "2001:db8::2", "AWS_INSTANCE_PORT", "8080"), REGION);
        byte[] request = buildQuery("typed." + namespace, (short) 33);
        byte[] response = query(new EmbeddedDnsServer(List.of(), List.of(cloudMapDnsRecordSource)), request);
        ByteBuffer packet = ByteBuffer.wrap(response);
        assertEquals(1, packet.getShort(6));
        assertEquals(33, packet.getShort(request.length + 2));
        packet.position(request.length + 12);
        assertEquals(1, packet.getShort());
        assertEquals(1, packet.getShort());
        assertEquals(8080, packet.getShort());
        assertEquals("task-1.typed." + namespace, EmbeddedDnsServer.readName(packet, response));
        byte[] address = query(new EmbeddedDnsServer(List.of(), List.of(cloudMapDnsRecordSource)),
                buildQuery("task-1.typed." + namespace, (short) 28));
        assertEquals(1, ByteBuffer.wrap(address).getShort(6));
    }

    @Test
    void weightedRoutingReturnsOneInstanceInsteadOfEight() throws Exception {
        String namespace = uniqueNamespace();
        Service service = typedService(namespace, "A", "WEIGHTED");
        for (int i = 1; i <= 10; i++) {
            cloudMapService.registerInstance(service.getId(), "task-" + i, null,
                    Map.of("AWS_INSTANCE_IPV4", "172.31.0." + i), REGION);
        }
        byte[] response = query(new EmbeddedDnsServer(List.of(), List.of(cloudMapDnsRecordSource)),
                buildQuery("typed." + namespace, (short) 1));
        assertEquals(1, ByteBuffer.wrap(response).getShort(6));
    }

    private Service typedService(String namespace, String type, String routingPolicy) {
        return cloudMapService.createService("typed", privateDnsNamespace(namespace), null, null,
                "{\"RoutingPolicy\":\"" + routingPolicy + "\",\"DnsRecords\":[{\"Type\":\""
                        + type + "\",\"TTL\":45}]}", null, null, null, Map.of(), REGION);
    }

    @Test
    void largeSrvResponsesRespectLegacyAndEdnsUdpSizes() throws Exception {
        String namespace = uniqueNamespace();
        Service service = typedService(namespace, "SRV", "MULTIVALUE");
        for (int i = 0; i < 8; i++) {
            cloudMapService.registerInstance(service.getId(), "instance-with-long-name-" + i, null,
                    Map.of("AWS_INSTANCE_IPV4", "192.0.2.1", "AWS_INSTANCE_PORT", "8080"), REGION);
        }
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of(), List.of(cloudMapDnsRecordSource));
        byte[] legacyQuery = buildQuery("typed." + namespace, (short) 33);
        byte[] legacy = query(dns, legacyQuery);
        ByteBuffer legacyPacket = ByteBuffer.wrap(legacy);
        assertTrue(legacy.length <= 512);
        assertTrue((legacyPacket.getShort(2) & 0x0200) != 0);
        assertTrue(legacyPacket.getShort(6) < 8);
        assertCompleteRecords(legacy, legacyQuery.length, legacyPacket.getShort(6));

        byte[] ednsQuery = withEdns(legacyQuery, 1232);
        byte[] edns = query(dns, ednsQuery);
        ByteBuffer ednsPacket = ByteBuffer.wrap(edns);
        assertTrue(edns.length <= 1232);
        assertEquals(0, ednsPacket.getShort(2) & 0x0200);
        assertEquals(8, ednsPacket.getShort(6));
        assertEquals(1, ednsPacket.getShort(10));
        assertEquals(41, ednsPacket.getShort(edns.length - 10));

        byte[] smallEdns = query(dns, withEdns(legacyQuery, 512));
        assertTrue(smallEdns.length <= 512);
        assertTrue((ByteBuffer.wrap(smallEdns).getShort(2) & 0x0200) != 0);
    }

    private static byte[] withEdns(byte[] query, int payloadSize) {
        ByteBuffer extended = ByteBuffer.allocate(query.length + 11).put(query);
        extended.putShort(10, (short) 1);
        extended.put((byte) 0).putShort((short) 41).putShort((short) payloadSize).putInt(0).putShort((short) 0);
        return extended.array();
    }

    private static void assertCompleteRecords(byte[] response, int questionEnd, int count) {
        ByteBuffer packet = ByteBuffer.wrap(response);
        packet.position(questionEnd);
        for (int i = 0; i < count; i++) {
            EmbeddedDnsServer.readName(packet, response);
            packet.getShort();
            packet.getShort();
            packet.getInt();
            int length = Short.toUnsignedInt(packet.getShort());
            assertTrue(packet.remaining() >= length);
            packet.position(packet.position() + length);
        }
        assertEquals(response.length, packet.position());
    }

    private static String uniqueNamespace() {
        return "dnspacket" + UUID.randomUUID().toString().substring(0, 8) + ".internal";
    }

    private byte[] query(EmbeddedDnsServer dns, byte[] request) throws Exception {
        DatagramSocket server = vertx.createDatagramSocket(new DatagramSocketOptions().setIpV6(false));
        DatagramSocket client = vertx.createDatagramSocket(new DatagramSocketOptions().setIpV6(false));
        try {
            server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            server.handler(packet -> dns.handleQuery(vertx, server, packet.data().getBytes(),
                    packet.sender().host(), packet.sender().port(), "127.0.0.1"));
            client.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            CompletableFuture<byte[]> received = new CompletableFuture<>();
            client.handler(packet -> received.complete(packet.data().getBytes()));

            client.send(Buffer.buffer(request), server.localAddress().port(), "127.0.0.1")
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            return received.get(5, TimeUnit.SECONDS);
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static byte[] buildQuery(String name, short type) {
        String[] labels = name.split("\\.");
        int nameLength = 1;
        for (String label : labels) {
            nameLength += label.length() + 1;
        }
        ByteBuffer query = ByteBuffer.allocate(12 + nameLength + 4);
        query.putShort((short) 0x1234);
        query.putShort((short) 0x0100);
        query.putShort((short) 1);
        query.putShort((short) 0);
        query.putShort((short) 0);
        query.putShort((short) 0);
        for (String label : labels) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            query.put((byte) bytes.length);
            query.put(bytes);
        }
        query.put((byte) 0);
        query.putShort(type);
        query.putShort((short) 1);
        return query.array();
    }
}
