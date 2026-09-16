package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MQTT 3.1.1 section 6: control packets are sent in binary WebSocket frames, and a frame may carry
 * several or partial packets, so the receiver must not assume packets align with frame boundaries.
 * These tests speak raw bytes through the JDK WebSocket to prove the bridge keeps the stream intact,
 * and that a broken stream or an oversized frame ends the session rather than hanging it.
 */
@QuarkusTest
@TestProfile(IotMqttWebSocketIntegrationTest.Profile.class)
class IotMqttWebSocketFramingIntegrationTest {

    private static final Logger LOG = Logger.getLogger(IotMqttWebSocketFramingIntegrationTest.class);
    private static final byte[] CONNACK_ACCEPTED = {0x20, 0x02, 0x00, 0x00};

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int testHttpPort;

    @Inject
    IotMqttBrokerService broker;

    @Test
    void connectSplitAcrossTwoFramesStillGetsConnack() throws Exception {
        try (RawWs raw = RawWs.open(ws())) {
            byte[] connect = connectPacket("split-" + System.nanoTime());
            int cut = connect.length / 2;

            raw.socket.sendBinary(ByteBuffer.wrap(connect, 0, cut), false).get(10, TimeUnit.SECONDS);
            raw.socket.sendBinary(ByteBuffer.wrap(connect, cut, connect.length - cut), true).get(10, TimeUnit.SECONDS);

            assertArrayEquals(CONNACK_ACCEPTED, raw.take(4));
        }
    }

    @Test
    void twoPacketsInOneFrameAreBothAnswered() throws Exception {
        try (RawWs raw = RawWs.open(ws())) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(connectPacket("packed-" + System.nanoTime()));
            frame.write(subscribePacket(7, "contract/packed/" + System.nanoTime()));

            raw.socket.sendBinary(ByteBuffer.wrap(frame.toByteArray()), true).get(10, TimeUnit.SECONDS);

            assertArrayEquals(CONNACK_ACCEPTED, raw.take(4));
            assertArrayEquals(new byte[] {(byte) 0x90, 0x03, 0x00, 0x07, 0x01}, raw.take(5), "SUBACK for id 7 granting QoS 1");
        }
    }

    @Test
    void textFrameAfterConnectClosesWithUnsupportedDataAndFreesTheBrokerSession() throws Exception {
        String clientId = "text-mid-" + System.nanoTime();
        try (RawWs raw = RawWs.open(ws())) {
            raw.socket.sendBinary(ByteBuffer.wrap(connectPacket(clientId)), true).get(10, TimeUnit.SECONDS);
            assertArrayEquals(CONNACK_ACCEPTED, raw.take(4));
            assertTrue(broker.getConnection(clientId).isPresent(), "precondition: a live broker session");

            raw.socket.sendText("{\"not\":\"mqtt\"}", true).get(10, TimeUnit.SECONDS);

            assertTrue(raw.closed.await(10, TimeUnit.SECONDS));
            assertEquals(1003, raw.closeCode.get(10, TimeUnit.SECONDS), "MQTT over WebSocket is binary only");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (broker.getConnection(clientId).isPresent() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(broker.getConnection(clientId).isEmpty(), "the broker side is closed with the WebSocket");
        }
    }

    @Test
    void abruptTcpDropWithoutACloseFrameFreesTheBrokerSession() throws Exception {
        String clientId = "abrupt-" + System.nanoTime();
        RawWs raw = RawWs.open(ws());
        raw.socket.sendBinary(ByteBuffer.wrap(connectPacket(clientId)), true).get(10, TimeUnit.SECONDS);
        assertArrayEquals(CONNACK_ACCEPTED, raw.take(4));
        assertTrue(broker.getConnection(clientId).isPresent(), "precondition: a live broker session");

        raw.socket.abort();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (broker.getConnection(clientId).isPresent() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(broker.getConnection(clientId).isEmpty(), "a vanished client frees the broker session without an MQTT DISCONNECT");
    }

    @Test
    void garbageBytesCloseTheSession() throws Exception {
        try (RawWs raw = RawWs.open(ws())) {
            raw.socket.sendBinary(ByteBuffer.wrap(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x00}), true)
                    .get(10, TimeUnit.SECONDS);

            assertTrue(raw.closed.await(10, TimeUnit.SECONDS), "the broker refuses the packet and the WebSocket follows");
        }
    }

    @Test
    void frameAboveTheTransportLimitClosesTheSession() throws Exception {
        try (RawWs raw = RawWs.open(ws())) {
            raw.socket.sendBinary(ByteBuffer.wrap(connectPacket("huge-" + System.nanoTime())), true).get(10, TimeUnit.SECONDS);
            assertArrayEquals(CONNACK_ACCEPTED, raw.take(4));

            try {
                raw.socket.sendBinary(ByteBuffer.wrap(new byte[300 * 1024]), true).get(10, TimeUnit.SECONDS);
            } catch (ExecutionException serverClosedMidWrite) {
                // The frame header already says 300 KB, so the server may drop the connection before
                // the client has finished writing it; the latch below is the assertion either way.
                LOG.debugv("oversized frame write ended early: {0}", serverClosedMidWrite.getCause());
            }

            assertTrue(raw.closed.await(10, TimeUnit.SECONDS), "a 300 KB frame is over the 256 KB limit");
        }
    }

    private String ws() {
        return "ws://127.0.0.1:" + testHttpPort + "/mqtt";
    }

    /** MQTT 3.1.1 CONNECT, clean session, keep alive 60, no will, no credentials. */
    private static byte[] connectPacket(String clientId) throws Exception {
        ByteArrayOutputStream variable = new ByteArrayOutputStream();
        variable.write(new byte[] {0x00, 0x04, 'M', 'Q', 'T', 'T', 0x04, 0x02, 0x00, 0x3C});
        writeString(variable, clientId);
        return packet(0x10, variable.toByteArray());
    }

    /** MQTT 3.1.1 SUBSCRIBE for one topic filter at QoS 1. */
    private static byte[] subscribePacket(int packetId, String topicFilter) throws Exception {
        ByteArrayOutputStream variable = new ByteArrayOutputStream();
        variable.write(packetId >> 8);
        variable.write(packetId & 0xFF);
        writeString(variable, topicFilter);
        variable.write(0x01);
        return packet(0x82, variable.toByteArray());
    }

    private static void writeString(ByteArrayOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length >> 8);
        out.write(bytes.length & 0xFF);
        out.write(bytes);
    }

    private static byte[] packet(int fixedHeader, byte[] body) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(fixedHeader);
        int remaining = body.length;
        do {
            int digit = remaining % 128;
            remaining /= 128;
            out.write(remaining > 0 ? digit | 0x80 : digit);
        } while (remaining > 0);
        out.write(body);
        return out.toByteArray();
    }

    /** A raw JDK WebSocket that collects the server's bytes and notices the close. */
    private static final class RawWs implements AutoCloseable {
        final WebSocket socket;
        final CountDownLatch closed = new CountDownLatch(1);
        final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();

        private RawWs(WebSocket socket) {
            this.socket = socket;
        }

        static RawWs open(String url) throws Exception {
            CompletableFuture<RawWs> ready = new CompletableFuture<>();
            WebSocket.Listener listener = new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                    RawWs raw = ready.join();
                    synchronized (raw.received) {
                        while (data.hasRemaining()) {
                            raw.received.write(data.get());
                        }
                        raw.received.notifyAll();
                    }
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    RawWs raw = ready.join();
                    raw.closeCode.complete(statusCode);
                    raw.closed.countDown();
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    ready.join().closed.countDown();
                }
            };
            WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .subprotocols("mqtt")
                    .buildAsync(URI.create(url), listener)
                    .get(10, TimeUnit.SECONDS);
            RawWs raw = new RawWs(socket);
            ready.complete(raw);
            return raw;
        }

        byte[] take(int count) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            synchronized (received) {
                while (received.size() < count) {
                    long left = deadline - System.nanoTime();
                    assertTrue(left > 0, "expected " + count + " bytes, got " + received.size());
                    received.wait(Math.max(1, left / 1_000_000));
                }
                byte[] all = received.toByteArray();
                byte[] head = new byte[count];
                System.arraycopy(all, 0, head, 0, count);
                received.reset();
                received.write(all, count, all.length - count);
                return head;
            }
        }

        @Override
        public void close() {
            socket.abort();
        }
    }
}
