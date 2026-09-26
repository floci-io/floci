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
