package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IotMqttEnabledIntegrationTest.EnabledMqttProfile.class)
class IotMqttEnabledIntegrationTest {

    private static final int PORT = 18831;

    @Inject
    IotPublishEventRecorder eventRecorder;

    @BeforeEach
    void clearEvents() {
        eventRecorder.clear();
    }

    @Test
    void enabledMqttAcceptsConnect() throws Exception {
        try (Socket client = connectMqtt("phase6-connect")) {
            assertTrue(client.isConnected());
        }
    }

    @Test
    void mqttPublishEmitsEventAndDeliversToSubscriber() throws Exception {
        String topic = "phase6/devices/one/events";
        byte[] payload = "hello-phase-six".getBytes(StandardCharsets.UTF_8);

        try (Socket subscriber = connectMqtt("phase6-sub")) {
            subscribe(subscriber, topic);

            try (Socket publisher = connectMqtt("phase6-pub")) {
                publish(publisher, topic, payload);
            }

            MqttPublish received = readPublish(subscriber.getInputStream());
            assertEquals(topic, received.topic());
            assertArrayEquals(payload, received.payload());
        }

        awaitPublishedEvent(topic, payload);
    }

    private Socket connectMqtt(String clientId) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", PORT), 2_000);
        socket.setSoTimeout(2_000);
        socket.getOutputStream().write(connectPacket(clientId));
        byte[] connack = readPacket(socket.getInputStream());
        assertArrayEquals(new byte[] {0x20, 0x02, 0x00, 0x00}, connack);
        return socket;
    }

    private byte[] connectPacket(String clientId) throws IOException {
        ByteArrayOutputStream variable = new ByteArrayOutputStream();
        writeUtf8(variable, "MQTT");
        variable.write(0x04);
        variable.write(0x02);
        variable.write(0x00);
        variable.write(0x3c);
        writeUtf8(variable, clientId);
        return packet(0x10, variable.toByteArray());
    }

    private void subscribe(Socket socket, String topic) throws IOException {
        ByteArrayOutputStream variable = new ByteArrayOutputStream();
        variable.write(0x00);
        variable.write(0x01);
        writeUtf8(variable, topic);
        variable.write(0x00);
        socket.getOutputStream().write(packet(0x82, variable.toByteArray()));
        byte[] suback = readPacket(socket.getInputStream());
        assertArrayEquals(new byte[] {(byte) 0x90, 0x03, 0x00, 0x01, 0x00}, suback);
    }

    private void publish(Socket socket, String topic, byte[] payload) throws IOException {
        ByteArrayOutputStream variable = new ByteArrayOutputStream();
        writeUtf8(variable, topic);
        variable.write(payload);
        socket.getOutputStream().write(packet(0x30, variable.toByteArray()));
    }

    private MqttPublish readPublish(InputStream input) throws IOException {
        byte[] packet = readPacket(input);
        assertEquals(0x30, packet[0] & 0xf0);
        int remainingLengthBytes = remainingLengthBytes(packet);
        int position = 1 + remainingLengthBytes;
        int topicLength = ((packet[position] & 0xff) << 8) | (packet[position + 1] & 0xff);
        position += 2;
        String topic = new String(packet, position, topicLength, StandardCharsets.UTF_8);
        position += topicLength;
        return new MqttPublish(topic, Arrays.copyOfRange(packet, position, packet.length));
    }

    private byte[] packet(int type, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type);
        writeRemainingLength(out, body.length);
        out.write(body);
        return out.toByteArray();
    }

    private byte[] readPacket(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int first = input.read();
        if (first < 0) {
            throw new IOException("No MQTT packet received");
        }
        out.write(first);

        int multiplier = 1;
        int remainingLength = 0;
        int encoded;
        do {
            encoded = input.read();
            if (encoded < 0) {
                throw new IOException("Incomplete MQTT remaining length");
            }
            out.write(encoded);
            remainingLength += (encoded & 127) * multiplier;
            multiplier *= 128;
        } while ((encoded & 128) != 0);

        byte[] body = input.readNBytes(remainingLength);
        if (body.length != remainingLength) {
            throw new IOException("Incomplete MQTT packet body");
        }
        out.write(body);
        return out.toByteArray();
    }

    private int remainingLengthBytes(byte[] packet) {
        int count = 0;
        int encoded;
        do {
            encoded = packet[1 + count] & 0xff;
            count++;
        } while ((encoded & 128) != 0);
        return count;
    }

    private void writeUtf8(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write((bytes.length >>> 8) & 0xff);
        out.write(bytes.length & 0xff);
        out.write(bytes);
    }

    private void writeRemainingLength(OutputStream out, int length) throws IOException {
        int value = length;
        do {
            int encoded = value % 128;
            value /= 128;
            if (value > 0) {
                encoded |= 128;
            }
            out.write(encoded);
        } while (value > 0);
    }

    private void awaitPublishedEvent(String topic, byte[] payload) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
        while (Instant.now().isBefore(deadline)) {
            boolean found = eventRecorder.recentEvents().stream()
                    .anyMatch(event -> topic.equals(event.topic()) && Arrays.equals(payload, event.payload()));
            if (found) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("MQTT publish event was not recorded");
    }

    private record MqttPublish(String topic, byte[] payload) {
    }

    public static final class EnabledMqttProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.iot.mqtt.enabled", "true",
                    "floci.services.iot.mqtt.auto-start", "true",
                    "floci.services.iot.mqtt.host", "127.0.0.1",
                    "floci.services.iot.mqtt.port", Integer.toString(PORT)
            );
        }
    }
}
