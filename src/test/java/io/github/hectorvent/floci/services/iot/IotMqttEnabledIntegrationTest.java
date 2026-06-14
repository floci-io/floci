package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IotMqttEnabledIntegrationTest.EnabledMqttProfile.class)
class IotMqttEnabledIntegrationTest {

    private static final int PORT = 18831;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
    void enabledMqttAcceptsMqtt5Connect() throws Exception {
        try (Socket client = connectMqtt5("phase6-connect-v5")) {
            assertTrue(client.isConnected());
        }
    }

    @Test
    void deleteConnectionClosesConnectedMqttClient() throws Exception {
        String clientId = "phase6-delete-" + System.nanoTime();

        try (Socket client = connectMqttClientId(clientId)) {
            given()
                .queryParam("cleanSession", true)
            .when()
                .delete("/connections/{clientId}", clientId)
            .then()
                .statusCode(200);

            assertSocketClosed(client);
        }

        given()
        .when()
            .delete("/connections/{clientId}", clientId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
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

    @Test
    void shadowUpdateTopicPublishesAcceptedResponse() throws Exception {
        try (Socket subscriber = connectMqtt("phase7-update-sub")) {
            subscribe(subscriber, "$aws/things/phase7Thing/shadow/update/accepted");

            try (Socket publisher = connectMqtt("phase7-update-pub")) {
                publish(publisher, "$aws/things/phase7Thing/shadow/update",
                        json("{\"state\":{\"desired\":{\"color\":\"blue\"}},\"clientToken\":\"token-1\"}"));
            }

            MqttPublish accepted = readPublish(subscriber.getInputStream());
            JsonNode payload = readJson(accepted.payload());
            assertEquals("$aws/things/phase7Thing/shadow/update/accepted", accepted.topic());
            assertEquals("blue", payload.path("state").path("desired").path("color").asText());
            assertEquals("token-1", payload.path("clientToken").asText());
        }
    }

    @Test
    void mqtt5ShadowUpdateTopicPublishesAcceptedResponse() throws Exception {
        try (Socket subscriber = connectMqtt5("phase7-mqtt5-update-sub")) {
            subscribeMqtt5(subscriber, "$aws/things/phase7Mqtt5Thing/shadow/update/accepted");

            try (Socket publisher = connectMqtt5("phase7-mqtt5-update-pub")) {
                publishMqtt5(publisher, "$aws/things/phase7Mqtt5Thing/shadow/update",
                        json("{\"state\":{\"desired\":{\"color\":\"purple\"}},\"clientToken\":\"mqtt5-token\"}"));
            }

            MqttPublish accepted = readMqtt5Publish(subscriber.getInputStream());
            JsonNode payload = readJson(accepted.payload());
            assertEquals("$aws/things/phase7Mqtt5Thing/shadow/update/accepted", accepted.topic());
            assertEquals("purple", payload.path("state").path("desired").path("color").asText());
            assertEquals("mqtt5-token", payload.path("clientToken").asText());
        }
    }


    @Test
    void malformedShadowUpdateTopicPublishesRejectedResponse() throws Exception {
        try (Socket subscriber = connectMqtt("phase7-rejected-sub")) {
            subscribe(subscriber, "$aws/things/phase7Thing/shadow/update/rejected");

            try (Socket publisher = connectMqtt("phase7-rejected-pub")) {
                publish(publisher, "$aws/things/phase7Thing/shadow/update", "{".getBytes(StandardCharsets.UTF_8));
            }

            MqttPublish rejected = readPublish(subscriber.getInputStream());
            JsonNode payload = readJson(rejected.payload());
            assertEquals("$aws/things/phase7Thing/shadow/update/rejected", rejected.topic());
            assertEquals("InvalidRequestException", payload.path("code").asText());
            assertTrue(payload.path("message").asText().length() > 0);
        }
    }

    @Test
    void shadowGetAndDeleteTopicsPublishAcceptedResponses() throws Exception {
        try (Socket subscriber = connectMqtt("phase7-get-delete-sub")) {
            subscribe(subscriber, "$aws/things/phase7GetDelete/shadow/get/accepted");
            subscribe(subscriber, "$aws/things/phase7GetDelete/shadow/delete/accepted");

            try (Socket publisher = connectMqtt("phase7-get-delete-pub")) {
                publish(publisher, "$aws/things/phase7GetDelete/shadow/update",
                        json("{\"state\":{\"reported\":{\"online\":true}}}"));
                publish(publisher, "$aws/things/phase7GetDelete/shadow/get", json("{\"clientToken\":\"get-token\"}"));
                MqttPublish getAccepted = readPublish(subscriber.getInputStream());
                JsonNode getPayload = readJson(getAccepted.payload());
                assertEquals("$aws/things/phase7GetDelete/shadow/get/accepted", getAccepted.topic());
                assertTrue(getPayload.path("state").path("reported").path("online").asBoolean());
                assertEquals("get-token", getPayload.path("clientToken").asText());

                publish(publisher, "$aws/things/phase7GetDelete/shadow/delete", json("{\"clientToken\":\"delete-token\"}"));
                MqttPublish deleteAccepted = readPublish(subscriber.getInputStream());
                JsonNode deletePayload = readJson(deleteAccepted.payload());
                assertEquals("$aws/things/phase7GetDelete/shadow/delete/accepted", deleteAccepted.topic());
                assertTrue(deletePayload.path("state").path("reported").path("online").asBoolean());
                assertEquals("delete-token", deletePayload.path("clientToken").asText());
            }
        }
    }

    @Test
    void shadowUpdatePublishesDocumentsAndDeltaResponses() throws Exception {
        try (Socket subscriber = connectMqtt("phase7-docs-delta-sub")) {
            subscribe(subscriber, "$aws/things/phase7Documents/shadow/update/documents");
            subscribe(subscriber, "$aws/things/phase7Documents/shadow/update/delta");

            try (Socket publisher = connectMqtt("phase7-docs-delta-pub")) {
                publish(publisher, "$aws/things/phase7Documents/shadow/update",
                        json("{\"state\":{\"reported\":{\"color\":\"red\"}}}"));
                readPublish(subscriber.getInputStream());

                publish(publisher, "$aws/things/phase7Documents/shadow/update",
                        json("{\"state\":{\"desired\":{\"color\":\"blue\"}}}"));
                MqttPublish documents = readPublish(subscriber.getInputStream());
                MqttPublish delta = readPublish(subscriber.getInputStream());
                if (documents.topic().endsWith("/delta")) {
                    MqttPublish swap = documents;
                    documents = delta;
                    delta = swap;
                }

                JsonNode documentsPayload = readJson(documents.payload());
                JsonNode deltaPayload = readJson(delta.payload());
                assertEquals("$aws/things/phase7Documents/shadow/update/documents", documents.topic());
                assertEquals("$aws/things/phase7Documents/shadow/update/delta", delta.topic());
                assertEquals("red", documentsPayload.path("previous").path("state").path("reported").path("color").asText());
                assertEquals("blue", documentsPayload.path("current").path("state").path("desired").path("color").asText());
                assertEquals("blue", deltaPayload.path("state").path("color").asText());
            }
        }
    }

    @Test
    void namedShadowTopicsPublishAcceptedResponses() throws Exception {
        String thingName = "phase7NamedThing";
        String updateTopic = "$aws/things/" + thingName + "/shadow/name/settings/update";
        String getTopic = "$aws/things/" + thingName + "/shadow/name/settings/get";

        try (Socket subscriber = connectMqtt("phase7-named-sub")) {
            subscribe(subscriber, updateTopic + "/accepted");
            subscribe(subscriber, getTopic + "/accepted");

            try (Socket publisher = connectMqtt("phase7-named-pub")) {
                publish(publisher, updateTopic,
                        json("{\"state\":{\"desired\":{\"mode\":\"auto\"}},\"clientToken\":\"named-update\"}"));
                MqttPublish updateAccepted = readPublish(subscriber.getInputStream());
                JsonNode updatePayload = readJson(updateAccepted.payload());
                assertEquals(updateTopic + "/accepted", updateAccepted.topic());
                assertEquals("auto", updatePayload.path("state").path("desired").path("mode").asText());
                assertEquals("named-update", updatePayload.path("clientToken").asText());

                publish(publisher, getTopic, json("{\"clientToken\":\"named-get\"}"));
                MqttPublish getAccepted = readPublish(subscriber.getInputStream());
                JsonNode getPayload = readJson(getAccepted.payload());
                assertEquals(getTopic + "/accepted", getAccepted.topic());
                assertEquals("auto", getPayload.path("state").path("desired").path("mode").asText());
                assertEquals("named-get", getPayload.path("clientToken").asText());
            }
        }
    }

    @Test
    void topicRuleRepublishPublishesToMqttSubscribers() throws Exception {
        given()
            .contentType("application/json")
            .body("""
                {
                  "topicRulePayload": {
                    "sql": "SELECT * FROM 'devices/phase8/mqtt/source'",
                    "actions": [
                      {
                        "republish": {
                          "roleArn": "arn:aws:iam::000000000000:role/iot-rule-role",
                          "topic": "devices/phase8/mqtt/target"
                        }
                      }
                    ]
                  }
                }
                """)
        .when()
            .put("/rules/phase8MqttRepublishRule")
        .then()
            .statusCode(200);

        try (Socket subscriber = connectMqtt("phase8-republish-sub")) {
            subscribe(subscriber, "devices/phase8/mqtt/target");

            try (Socket publisher = connectMqtt("phase8-republish-pub")) {
                publish(publisher, "devices/phase8/mqtt/source", "mqtt-rule-payload".getBytes(StandardCharsets.UTF_8));
            }

            MqttPublish republished = readPublish(subscriber.getInputStream());
            assertEquals("devices/phase8/mqtt/target", republished.topic());
            assertArrayEquals("mqtt-rule-payload".getBytes(StandardCharsets.UTF_8), republished.payload());
        }
    }

    private Socket connectMqtt(String clientId) throws IOException {
        return connectMqttClientId(uniqueClientId(clientId));
    }

    private Socket connectMqttClientId(String clientId) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", PORT), 2_000);
        socket.setSoTimeout(2_000);
        socket.getOutputStream().write(connectPacketForClientId(clientId));
        byte[] connack = readPacket(socket.getInputStream());
        assertArrayEquals(new byte[] {0x20, 0x02, 0x00, 0x00}, connack);
        return socket;
    }

    private Socket connectMqtt5(String clientId) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", PORT), 2_000);
        socket.setSoTimeout(2_000);
        ByteArrayOutputStream variable = new ByteArrayOutputStream();
        writeUtf8(variable, "MQTT");
        variable.write(0x05);
        variable.write(0x02);
        variable.write(0x00);
        variable.write(0x3c);
        variable.write(0x00);
        writeUtf8(variable, uniqueClientId(clientId));
        socket.getOutputStream().write(packet(0x10, variable.toByteArray()));
        byte[] connack = readPacket(socket.getInputStream());
        assertMqtt5SuccessConnack(connack);
        return socket;
    }

    private void assertMqtt5SuccessConnack(byte[] connack) {
        assertEquals(0x20, connack[0] & 0xff);
        int position = 1 + remainingLengthBytes(connack);
        assertEquals(0x00, connack[position] & 0xff);
        assertEquals(0x00, connack[position + 1] & 0xff);
    }

    private byte[] connectPacket(String clientId) throws IOException {
        return connectPacketForClientId(uniqueClientId(clientId));
    }

    private byte[] connectPacketForClientId(String clientId) throws IOException {
        ByteArrayOutputStream variable = new ByteArrayOutputStream();
        writeUtf8(variable, "MQTT");
        variable.write(0x04);
        variable.write(0x02);
        variable.write(0x00);
        variable.write(0x3c);
        writeUtf8(variable, clientId);
        return packet(0x10, variable.toByteArray());
    }

    private String uniqueClientId(String clientId) {
        return clientId + "-" + System.nanoTime();
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

    private void subscribeMqtt5(Socket socket, String topic) throws IOException {
        ByteArrayOutputStream variable = new ByteArrayOutputStream();
        variable.write(0x00);
        variable.write(0x01);
        variable.write(0x00);
        writeUtf8(variable, topic);
        variable.write(0x00);
        socket.getOutputStream().write(packet(0x82, variable.toByteArray()));
        byte[] suback = readPacket(socket.getInputStream());
        assertEquals(0x90, suback[0] & 0xff);
        int position = 1 + remainingLengthBytes(suback);
        assertEquals(0x00, suback[position] & 0xff);
        assertEquals(0x01, suback[position + 1] & 0xff);
        position += 2;
        int propertiesLengthBytes = variableLengthBytes(suback, position);
        int propertiesLength = variableLength(suback, position);
        position += propertiesLengthBytes + propertiesLength;
        assertEquals(0x00, suback[position] & 0xff);
    }

    private void publish(Socket socket, String topic, byte[] payload) throws IOException {
        ByteArrayOutputStream variable = new ByteArrayOutputStream();
        writeUtf8(variable, topic);
        variable.write(payload);
        socket.getOutputStream().write(packet(0x30, variable.toByteArray()));
    }

    private void publishMqtt5(Socket socket, String topic, byte[] payload) throws IOException {
        ByteArrayOutputStream variable = new ByteArrayOutputStream();
        writeUtf8(variable, topic);
        variable.write(0x02);
        variable.write(0x01);
        variable.write(0x01);
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

    private MqttPublish readMqtt5Publish(InputStream input) throws IOException {
        byte[] packet = readPacket(input);
        assertEquals(0x30, packet[0] & 0xf0);
        int position = 1 + remainingLengthBytes(packet);
        int topicLength = ((packet[position] & 0xff) << 8) | (packet[position + 1] & 0xff);
        position += 2;
        String topic = new String(packet, position, topicLength, StandardCharsets.UTF_8);
        position += topicLength;
        int propertiesLengthBytes = variableLengthBytes(packet, position);
        int propertiesLength = variableLength(packet, position);
        position += propertiesLengthBytes + propertiesLength;
        return new MqttPublish(topic, Arrays.copyOfRange(packet, position, packet.length));
    }

    private byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode readJson(byte[] payload) throws IOException {
        return OBJECT_MAPPER.readTree(payload);
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

    private int variableLength(byte[] packet, int offset) {
        int multiplier = 1;
        int value = 0;
        int position = offset;
        int encoded;
        do {
            encoded = packet[position++] & 0xff;
            value += (encoded & 127) * multiplier;
            multiplier *= 128;
        } while ((encoded & 128) != 0);
        return value;
    }

    private int variableLengthBytes(byte[] packet, int offset) {
        int count = 0;
        int encoded;
        do {
            encoded = packet[offset + count] & 0xff;
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

    private void assertSocketClosed(Socket socket) throws IOException {
        try {
            assertEquals(-1, socket.getInputStream().read());
        } catch (SocketTimeoutException e) {
            throw new AssertionError("MQTT connection stayed open after DeleteConnection", e);
        }
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
