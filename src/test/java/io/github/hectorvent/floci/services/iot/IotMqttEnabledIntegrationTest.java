package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IotMqttEnabledIntegrationTest.EnabledMqttProfile.class)
class IotMqttEnabledIntegrationTest {

    private static final int PORT = 18831;
    private static final String BROKER_URI = "tcp://127.0.0.1:" + PORT;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    IotPublishEventRecorder eventRecorder;

    @BeforeEach
    void clearEvents() {
        eventRecorder.clear();
    }

    @Test
    void enabledMqttAcceptsConnect() throws Exception {
        try (MqttTestClient client = connectMqtt("phase6-connect")) {
            assertTrue(client.isConnected());
        }
    }

    @Test
    void enabledMqttAcceptsMqtt5Connect() throws Exception {
        try (Mqtt5TestClient client = connectMqtt5("phase6-connect-v5")) {
            assertTrue(client.isConnected());
        }
    }

    @Test
    void deleteConnectionClosesConnectedMqttClient() throws Exception {
        String clientId = "phase6-delete-" + System.nanoTime();

        try (MqttTestClient client = connectMqttClientId(clientId)) {
            given()
                .queryParam("cleanSession", true)
            .when()
                .delete("/connections/{clientId}", clientId)
            .then()
                .statusCode(200);

            client.awaitDisconnected();
        }

        given()
        .when()
            .delete("/connections/{clientId}", clientId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void connectionApisReturnLiveMqttStateAndSendDirectMessage() throws Exception {
        String clientId = "phase6-connection-" + System.nanoTime();
        String topic = "phase6/direct/" + System.nanoTime();
        byte[] payload = "direct-payload".getBytes(StandardCharsets.UTF_8);

        try (MqttTestClient client = connectMqttClientId(clientId)) {
            client.subscribe(topic);

            given()
                .queryParam("includeSocketInformation", true)
            .when()
                .get("/connections/{clientId}", clientId)
            .then()
                .statusCode(200)
                .body("clientId", equalTo(clientId))
                .body("connected", equalTo(true))
                .body("cleanSession", equalTo(true))
                .body("sourceIp", equalTo("127.0.0.1"));

            given()
            .when()
                .get("/connections/{clientId}/subscriptions", clientId)
            .then()
                .statusCode(200)
                .body("subscriptions[0].topicFilter", equalTo(topic))
                .body("subscriptions[0].qos", equalTo(0));

            given()
                .queryParam("topic", topic)
                .body(payload)
            .when()
                .post("/connections/{clientId}/messages", clientId)
            .then()
                .statusCode(200)
                .body("message", equalTo("OK"));

            MqttPublish received = client.takePublish();
            assertEquals(topic, received.topic());
            assertArrayEquals(payload, received.payload());
        }

        given()
        .when()
            .get("/connections/{clientId}", clientId)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void mqttPublishEmitsEventAndDeliversToSubscriber() throws Exception {
        String topic = "phase6/devices/one/events";
        byte[] payload = "hello-phase-six".getBytes(StandardCharsets.UTF_8);

        try (MqttTestClient subscriber = connectMqtt("phase6-sub")) {
            subscriber.subscribe(topic);

            try (MqttTestClient publisher = connectMqtt("phase6-pub")) {
                publisher.publish(topic, payload);
            }

            MqttPublish received = subscriber.takePublish();
            assertEquals(topic, received.topic());
            assertArrayEquals(payload, received.payload());
        }

        awaitPublishedEvent(topic, payload);
    }

    @Test
    void mqttQos1PublishIsAcknowledgedAndDelivered() throws Exception {
        String topic = "phase6/devices/qos1/events";
        byte[] payload = "hello-qos-one".getBytes(StandardCharsets.UTF_8);

        try (MqttTestClient subscriber = connectMqtt("phase6-qos1-sub")) {
            subscriber.subscribe(topic, 1);

            try (MqttTestClient publisher = connectMqtt("phase6-qos1-pub")) {
                publisher.publish(topic, payload, 1);
            }

            MqttPublish received = subscriber.takePublish();
            assertEquals(topic, received.topic());
            assertArrayEquals(payload, received.payload());
        }

        awaitPublishedEvent(topic, payload);
    }

    @Test
    void shadowUpdateTopicPublishesAcceptedResponse() throws Exception {
        try (MqttTestClient subscriber = connectMqtt("phase7-update-sub")) {
            subscriber.subscribe("$aws/things/phase7Thing/shadow/update/accepted");

            try (MqttTestClient publisher = connectMqtt("phase7-update-pub")) {
                publisher.publish("$aws/things/phase7Thing/shadow/update",
                        json("{\"state\":{\"desired\":{\"color\":\"blue\"}},\"clientToken\":\"token-1\"}"));
            }

            MqttPublish accepted = subscriber.takePublish();
            JsonNode payload = readJson(accepted.payload());
            assertEquals("$aws/things/phase7Thing/shadow/update/accepted", accepted.topic());
            assertEquals("blue", payload.path("state").path("desired").path("color").asText());
            assertEquals("token-1", payload.path("clientToken").asText());
        }
    }

    @Test
    void mqtt5ShadowUpdateTopicPublishesAcceptedResponse() throws Exception {
        try (Mqtt5TestClient subscriber = connectMqtt5("phase7-mqtt5-update-sub")) {
            subscriber.subscribe("$aws/things/phase7Mqtt5Thing/shadow/update/accepted");

            try (Mqtt5TestClient publisher = connectMqtt5("phase7-mqtt5-update-pub")) {
                publisher.publish("$aws/things/phase7Mqtt5Thing/shadow/update",
                        json("{\"state\":{\"desired\":{\"color\":\"purple\"}},\"clientToken\":\"mqtt5-token\"}"));
            }

            MqttPublish accepted = subscriber.takePublish();
            JsonNode payload = readJson(accepted.payload());
            assertEquals("$aws/things/phase7Mqtt5Thing/shadow/update/accepted", accepted.topic());
            assertEquals("purple", payload.path("state").path("desired").path("color").asText());
            assertEquals("mqtt5-token", payload.path("clientToken").asText());
        }
    }


    @Test
    void malformedShadowUpdateTopicPublishesRejectedResponse() throws Exception {
        try (MqttTestClient subscriber = connectMqtt("phase7-rejected-sub")) {
            subscriber.subscribe("$aws/things/phase7Thing/shadow/update/rejected");

            try (MqttTestClient publisher = connectMqtt("phase7-rejected-pub")) {
                publisher.publish("$aws/things/phase7Thing/shadow/update", "{".getBytes(StandardCharsets.UTF_8));
            }

            MqttPublish rejected = subscriber.takePublish();
            JsonNode payload = readJson(rejected.payload());
            assertEquals("$aws/things/phase7Thing/shadow/update/rejected", rejected.topic());
            assertEquals("InvalidRequestException", payload.path("code").asText());
            assertTrue(payload.path("message").asText().length() > 0);
        }
    }

    @Test
    void shadowGetAndDeleteTopicsPublishAcceptedResponses() throws Exception {
        try (MqttTestClient subscriber = connectMqtt("phase7-get-delete-sub")) {
            subscriber.subscribe("$aws/things/phase7GetDelete/shadow/get/accepted");
            subscriber.subscribe("$aws/things/phase7GetDelete/shadow/delete/accepted");

            try (MqttTestClient publisher = connectMqtt("phase7-get-delete-pub")) {
                publisher.publish("$aws/things/phase7GetDelete/shadow/update",
                        json("{\"state\":{\"reported\":{\"online\":true}}}"));
                publisher.publish("$aws/things/phase7GetDelete/shadow/get", json("{\"clientToken\":\"get-token\"}"));
                MqttPublish getAccepted = subscriber.takePublish();
                JsonNode getPayload = readJson(getAccepted.payload());
                assertEquals("$aws/things/phase7GetDelete/shadow/get/accepted", getAccepted.topic());
                assertTrue(getPayload.path("state").path("reported").path("online").asBoolean());
                assertEquals("get-token", getPayload.path("clientToken").asText());

                publisher.publish("$aws/things/phase7GetDelete/shadow/delete", json("{\"clientToken\":\"delete-token\"}"));
                MqttPublish deleteAccepted = subscriber.takePublish();
                JsonNode deletePayload = readJson(deleteAccepted.payload());
                assertEquals("$aws/things/phase7GetDelete/shadow/delete/accepted", deleteAccepted.topic());
                assertTrue(deletePayload.path("state").path("reported").path("online").asBoolean());
                assertEquals("delete-token", deletePayload.path("clientToken").asText());
            }
        }
    }

    @Test
    void shadowUpdatePublishesDocumentsAndDeltaResponses() throws Exception {
        try (MqttTestClient subscriber = connectMqtt("phase7-docs-delta-sub")) {
            subscriber.subscribe("$aws/things/phase7Documents/shadow/update/documents");
            subscriber.subscribe("$aws/things/phase7Documents/shadow/update/delta");

            try (MqttTestClient publisher = connectMqtt("phase7-docs-delta-pub")) {
                publisher.publish("$aws/things/phase7Documents/shadow/update",
                        json("{\"state\":{\"reported\":{\"color\":\"red\"}}}"));
                subscriber.takePublish();

                publisher.publish("$aws/things/phase7Documents/shadow/update",
                        json("{\"state\":{\"desired\":{\"color\":\"blue\"}}}"));
                MqttPublish documents = subscriber.takePublish();
                MqttPublish delta = subscriber.takePublish();
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

        try (MqttTestClient subscriber = connectMqtt("phase7-named-sub")) {
            subscriber.subscribe(updateTopic + "/accepted");
            subscriber.subscribe(getTopic + "/accepted");

            try (MqttTestClient publisher = connectMqtt("phase7-named-pub")) {
                publisher.publish(updateTopic,
                        json("{\"state\":{\"desired\":{\"mode\":\"auto\"}},\"clientToken\":\"named-update\"}"));
                MqttPublish updateAccepted = subscriber.takePublish();
                JsonNode updatePayload = readJson(updateAccepted.payload());
                assertEquals(updateTopic + "/accepted", updateAccepted.topic());
                assertEquals("auto", updatePayload.path("state").path("desired").path("mode").asText());
                assertEquals("named-update", updatePayload.path("clientToken").asText());

                publisher.publish(getTopic, json("{\"clientToken\":\"named-get\"}"));
                MqttPublish getAccepted = subscriber.takePublish();
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

        try (MqttTestClient subscriber = connectMqtt("phase8-republish-sub")) {
            subscriber.subscribe("devices/phase8/mqtt/target");

            try (MqttTestClient publisher = connectMqtt("phase8-republish-pub")) {
                publisher.publish("devices/phase8/mqtt/source", "mqtt-rule-payload".getBytes(StandardCharsets.UTF_8));
            }

            MqttPublish republished = subscriber.takePublish();
            assertEquals("devices/phase8/mqtt/target", republished.topic());
            assertArrayEquals("mqtt-rule-payload".getBytes(StandardCharsets.UTF_8), republished.payload());
        }
    }

    private MqttTestClient connectMqtt(String clientId) throws MqttException {
        return connectMqttClientId(uniqueClientId(clientId));
    }

    private MqttTestClient connectMqttClientId(String clientId) throws MqttException {
        return MqttTestClient.connect(clientId);
    }

    private String uniqueClientId(String clientId) {
        return clientId + "-" + System.nanoTime();
    }

    private Mqtt5TestClient connectMqtt5(String clientId) throws org.eclipse.paho.mqttv5.common.MqttException {
        return Mqtt5TestClient.connect(uniqueClientId(clientId));
    }

    private byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode readJson(byte[] payload) throws IOException {
        return OBJECT_MAPPER.readTree(payload);
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

    private static final class MqttTestClient implements AutoCloseable {
        private final MqttClient client;
        private final BlockingQueue<MqttPublish> publishes = new LinkedBlockingQueue<>();

        private MqttTestClient(MqttClient client) {
            this.client = client;
        }

        static MqttTestClient connect(String clientId) throws MqttException {
            MqttClient client = new MqttClient(BROKER_URI, clientId, new MemoryPersistence());
            MqttTestClient testClient = new MqttTestClient(client);
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    testClient.publishes.add(new MqttPublish(topic, message.getPayload()));
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            client.connect(connectOptions());
            return testClient;
        }

        boolean isConnected() {
            return client.isConnected();
        }

        void subscribe(String topic) throws MqttException {
            subscribe(topic, 0);
        }

        void subscribe(String topic, int qos) throws MqttException {
            client.subscribe(topic, qos);
        }

        void publish(String topic, byte[] payload) throws MqttException {
            publish(topic, payload, 0);
        }

        void publish(String topic, byte[] payload, int qos) throws MqttException {
            client.publish(topic, payload, qos, false);
        }

        MqttPublish takePublish() throws InterruptedException {
            return Optional.ofNullable(publishes.poll(2, TimeUnit.SECONDS))
                    .orElseThrow(() -> new AssertionError("No MQTT publish received"));
        }

        void awaitDisconnected() throws InterruptedException {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(2));
            while (Instant.now().isBefore(deadline)) {
                if (!client.isConnected()) {
                    return;
                }
                Thread.sleep(25);
            }
            throw new AssertionError("MQTT connection stayed open after DeleteConnection");
        }

        @Override
        public void close() throws MqttException {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        }

        private static MqttConnectOptions connectOptions() {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(2);
            options.setKeepAliveInterval(60);
            options.setAutomaticReconnect(false);
            return options;
        }
    }

    private static final class Mqtt5TestClient implements AutoCloseable {
        private final org.eclipse.paho.mqttv5.client.MqttClient client;
        private final BlockingQueue<MqttPublish> publishes = new LinkedBlockingQueue<>();

        private Mqtt5TestClient(org.eclipse.paho.mqttv5.client.MqttClient client) {
            this.client = client;
        }

        static Mqtt5TestClient connect(String clientId) throws org.eclipse.paho.mqttv5.common.MqttException {
            org.eclipse.paho.mqttv5.client.MqttClient client =
                    new org.eclipse.paho.mqttv5.client.MqttClient(BROKER_URI, clientId, null);
            Mqtt5TestClient testClient = new Mqtt5TestClient(client);
            client.setCallback(new org.eclipse.paho.mqttv5.client.MqttCallback() {
                @Override
                public void disconnected(org.eclipse.paho.mqttv5.client.MqttDisconnectResponse disconnectResponse) {
                }

                @Override
                public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {
                }

                @Override
                public void messageArrived(String topic, org.eclipse.paho.mqttv5.common.MqttMessage message) {
                    testClient.publishes.add(new MqttPublish(topic, message.getPayload()));
                }

                @Override
                public void deliveryComplete(org.eclipse.paho.mqttv5.client.IMqttToken token) {
                }

                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                }

                @Override
                public void authPacketArrived(int reasonCode, org.eclipse.paho.mqttv5.common.packet.MqttProperties properties) {
                }
            });
            client.connect(connectOptions());
            return testClient;
        }

        boolean isConnected() {
            return client.isConnected();
        }

        void subscribe(String topic) throws org.eclipse.paho.mqttv5.common.MqttException {
            client.subscribe(topic, 0);
        }

        void publish(String topic, byte[] payload) throws org.eclipse.paho.mqttv5.common.MqttException {
            org.eclipse.paho.mqttv5.common.MqttMessage message = new org.eclipse.paho.mqttv5.common.MqttMessage(payload);
            message.setQos(0);
            client.publish(topic, message);
        }

        MqttPublish takePublish() throws InterruptedException {
            return Optional.ofNullable(publishes.poll(2, TimeUnit.SECONDS))
                    .orElseThrow(() -> new AssertionError("No MQTT publish received"));
        }

        @Override
        public void close() throws org.eclipse.paho.mqttv5.common.MqttException {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        }

        private static org.eclipse.paho.mqttv5.client.MqttConnectionOptions connectOptions() {
            org.eclipse.paho.mqttv5.client.MqttConnectionOptions options =
                    new org.eclipse.paho.mqttv5.client.MqttConnectionOptions();
            options.setCleanStart(true);
            options.setConnectionTimeout(2);
            options.setKeepAliveInterval(60);
            options.setAutomaticReconnect(false);
            return options;
        }
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
