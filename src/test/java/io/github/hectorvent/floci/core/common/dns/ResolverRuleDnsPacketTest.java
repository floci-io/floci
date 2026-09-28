package io.github.hectorvent.floci.core.common.dns;

import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
// Vert.x's DatagramSocket is the one used most here; the JDK's is qualified inline where the
// test needs a plain blocking socket to stand in for the rule's target resolver.
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives the whole packet path with a forwarding rule in place: the query really leaves the DNS
 * server over UDP, and what comes back is what the rule's target answered.
 */
@QuarkusTest
class ResolverRuleDnsPacketTest {

    private static final String CLIENT = "127.0.0.1";
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String VPC_ID = "vpc-0dns0000";

    @Inject
    Vertx vertx;

    @Test
    void aForwardedQueryIsAnsweredByTheRulesTarget() throws Exception {
        byte[] request = buildQuery("db.corp.internal");
        byte[] targetAnswer = answerFor(request, "10.4.0.11");

        try (java.net.DatagramSocket target = new java.net.DatagramSocket(0, InetAddress.getByName(CLIENT))) {
            respondOnce(target, targetAnswer);
            EmbeddedDnsServer dns = serverForwarding("corp.internal", target.getLocalPort());

            byte[] response = query(dns, request);

            assertArrayEquals(targetAnswer, response,
                    "the answer the rule's target gave must reach the client unchanged");
        }
    }

    @Test
    void unreachableRuleTargetsFailOnlyTheQueryTheRuleMatched() throws Exception {
        // Port 1 on the loopback has no resolver on it, so every target of the rule fails.
        EmbeddedDnsServer dns = serverForwarding("corp.internal", 1);

        byte[] failed = query(dns, buildQuery("db.corp.internal"));
        assertEquals(2, ByteBuffer.wrap(failed).getShort(2) & 0x000F, "SERVFAIL for the rule's name");
        assertEquals(0, ByteBuffer.wrap(failed).getShort(2) & 0x0400,
                "Floci is not the authority for a name a rule sends elsewhere");

        byte[] request = buildQuery("api.sapphire.internal");
        byte[] resolved = query(dns, request);
        assertEquals(0, ByteBuffer.wrap(resolved).getShort(2) & 0x000F);
        assertEquals(1, ByteBuffer.wrap(resolved).getShort(6));
        assertArrayEquals(new byte[]{(byte) 172, 31, 0, 6},
                Arrays.copyOfRange(resolved, request.length + 12, request.length + 16));
    }

    private EmbeddedDnsServer serverForwarding(String domainName, int targetPort) {
        DnsForwardingRule rule = DnsForwardingRule.forwardTo(domainName,
                List.of(new DnsForwardingRule.Target(CLIENT, targetPort)));
        DnsRecordSource records = name -> "api.sapphire.internal".equals(name)
                ? Optional.of(DnsAnswer.records(List.of("172.31.0.6"), 60)) : Optional.empty();
        return new EmbeddedDnsServer(List.of(), List.of(records),
                List.of((accountId, region, vpcId) -> ACCOUNT.equals(accountId)
                        && REGION.equals(region) && VPC_ID.equals(vpcId) ? List.of(rule) : List.of()),
                List.of(clientAddress ->
                        Optional.of(new DnsClientVpcSource.ClientVpc(ACCOUNT, REGION, VPC_ID))));
    }

    /** Answers the first datagram with {@code payload}, on a daemon thread. */
    private void respondOnce(java.net.DatagramSocket socket, byte[] payload) {
        Thread responder = new Thread(() -> {
            try {
                DatagramPacket received = new DatagramPacket(new byte[4096], 4096);
                socket.receive(received);
                socket.send(new DatagramPacket(payload, payload.length,
                        received.getAddress(), received.getPort()));
            } catch (Exception ignored) {
                // the socket is closed when the test completes
            }
        });
        responder.setDaemon(true);
        responder.start();
    }

    private byte[] query(EmbeddedDnsServer dns, byte[] request) throws Exception {
        DatagramSocket server = vertx.createDatagramSocket(new DatagramSocketOptions().setIpV6(false));
        DatagramSocket client = vertx.createDatagramSocket(new DatagramSocketOptions().setIpV6(false));
        try {
            server.listen(0, CLIENT).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            server.handler(packet -> dns.handleQuery(vertx, server, packet.data().getBytes(),
                    packet.sender().host(), packet.sender().port(), CLIENT));
            client.listen(0, CLIENT).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            CompletableFuture<byte[]> received = new CompletableFuture<>();
            client.handler(packet -> received.complete(packet.data().getBytes()));

            client.send(Buffer.buffer(request), server.localAddress().port(), CLIENT)
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            return received.get(10, TimeUnit.SECONDS);
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static byte[] buildQuery(String name) {
        String[] labels = name.split("\\.");
        int nameLength = 1;
        for (String label : labels) {
            nameLength += label.length() + 1;
        }
        ByteBuffer query = ByteBuffer.allocate(12 + nameLength + 4);
        query.putShort((short) 0x2468);
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
        query.putShort((short) 1);
        query.putShort((short) 1);
        return query.array();
    }

    /** The query echoed back as a one-answer response, which is what a real resolver would send. */
    private static byte[] answerFor(byte[] request, String address) {
        ByteBuffer response = ByteBuffer.allocate(request.length + 16);
        response.put(request);
        response.putShort(2, (short) 0x8180);
        response.putShort(6, (short) 1);
        response.position(request.length);
        response.putShort((short) 0xC00C);
        response.putShort((short) 1);
        response.putShort((short) 1);
        response.putInt(30);
        response.putShort((short) 4);
        for (String octet : address.split("\\.")) {
            response.put((byte) Integer.parseInt(octet));
        }
        return response.array();
    }
}
