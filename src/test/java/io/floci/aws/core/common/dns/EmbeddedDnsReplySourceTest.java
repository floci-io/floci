package io.floci.aws.core.common.dns;

import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Linux routes all of 127/8 locally, allowing distinct client and server addresses. */
@EnabledOnOs(OS.LINUX)
class EmbeddedDnsReplySourceTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replyComesFromTheQueriedAddress(boolean connectedClient) throws Exception {
        Vertx vertx = Vertx.vertx();
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of());
        InetAddress serverAddress = InetAddress.getByName("127.0.0.2");
        DatagramSocket server = null;
        // Vert.x owns the server socket; the JDK socket models a resolver that filters its peer.
        try (java.net.DatagramSocket client =
                     new java.net.DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            server = dns.listen(vertx, serverAddress.getHostAddress(), 0)
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            int serverPort = server.localAddress().port();
            assertEquals(Optional.of(serverAddress.getHostAddress()), dns.getServerIp());
            client.setSoTimeout(5000);
            if (connectedClient) {
                client.connect(serverAddress, serverPort);
            }
            byte[] request = query();
            client.send(new DatagramPacket(request, request.length, serverAddress, serverPort));

            DatagramPacket reply = new DatagramPacket(new byte[512], 512);
            client.receive(reply);

            assertEquals(serverAddress, reply.getAddress());
            assertEquals(serverPort, reply.getPort());
            ByteBuffer response = ByteBuffer.wrap(reply.getData(), 0, reply.getLength());
            assertEquals((short) 0x1234, response.getShort(0));
            assertEquals(0, response.getShort(2) & 0x000F);
            assertEquals(1, response.getShort(6));
            assertArrayEquals(serverAddress.getAddress(),
                    Arrays.copyOfRange(reply.getData(), request.length + 12, request.length + 16));
        } finally {
            if (server != null) {
                server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static byte[] query() {
        ByteBuffer query = ByteBuffer.allocate(512);
        query.putShort((short) 0x1234).putShort((short) 0x0100).putShort((short) 1);
        query.putShort((short) 0).putShort((short) 0).putShort((short) 0);
        for (String label : EmbeddedDnsServer.DEFAULT_SUFFIX.split("\\.")) {
            byte[] encoded = label.getBytes(StandardCharsets.US_ASCII);
            query.put((byte) encoded.length).put(encoded);
        }
        query.put((byte) 0).putShort((short) 1).putShort((short) 1);
        return Arrays.copyOf(query.array(), query.position());
    }
}
