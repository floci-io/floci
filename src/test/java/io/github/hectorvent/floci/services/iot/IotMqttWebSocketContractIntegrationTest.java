package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AWS IoT contract for {@code /mqtt} at two levels. The handshake: only a GET with WebSocket
 * version 13 upgrades, the offered subprotocol is echoed, control frames work. The session: once
 * the bytes reach the broker it behaves as on AWS (client id takeover, retained messages, QoS 2
 * refused, shadow topics, rules, MQTT 5), whichever transport the peer uses. Frame boundaries are
 * covered by {@link IotMqttWebSocketFramingIntegrationTest}.
 */
@QuarkusTest
@TestProfile(IotMqttWebSocketIntegrationTest.Profile.class)
class IotMqttWebSocketContractIntegrationTest {

    private static final Logger LOG = Logger.getLogger(IotMqttWebSocketContractIntegrationTest.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int PLAIN_PORT = IotMqttWebSocketIntegrationTest.PLAIN_PORT;
    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int testSslPort;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int testHttpPort;

    // ==================== Handshake ====================

    @Test
    void onlyGetUpgrades() throws Exception {
        assertEquals(405, rawUpgradeStatus("POST", "13"));
    }

    @Test
    void unsupportedWebSocketVersionIsUpgradeRequired() throws Exception {
        assertEquals(426, rawUpgradeStatus("GET", "99"));
    }

    @Test
    void nonUpgradeRequestsFallThroughToTheRestOfTheServer() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        for (String method : List.of("POST", "PUT", "DELETE", "HEAD")) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + testHttpPort + "/mqtt"))
                            .method(method, HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertNotEquals(101, response.statusCode(), method);
            assertTrue(response.statusCode() < 500, method + " answered " + response.statusCode());
        }
    }

    @Test
    void eachMqttSubprotocolNameIsEchoed() throws Exception {
        for (String name : List.of("mqtt", "mqttv3.1", "mqttv3.1.1")) {
            WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .subprotocols(name)
                    .buildAsync(URI.create(ws()), new WebSocket.Listener() { })
                    .get(10, TimeUnit.SECONDS);
            assertEquals(name, socket.getSubprotocol());
            socket.abort();
        }
    }

    @Test
    void firstOfferedSupportedSubprotocolWins() throws Exception {
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .subprotocols("graphql-ws", "mqttv3.1.1", "mqtt")
                .buildAsync(URI.create(ws()), new WebSocket.Listener() { })
                .get(10, TimeUnit.SECONDS);
        assertEquals("mqttv3.1.1", socket.getSubprotocol());
        socket.abort();
    }

    @Test
    void unrelatedSubprotocolOnAnotherPathStillConnects() throws Exception {
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\":\"ws-subprotocol-probe\",\"protocolType\":\"WEBSOCKET\",\"routeSelectionExpression\":\"$request.body.action\"}")
        .when()
                .post("/v2/apis")
        .then()
                .statusCode(201)
                .extract().path("apiId");
        given()
                .contentType("application/json")
                .body("{\"stageName\":\"probe\"}")
        .when()
                .post("/v2/apis/" + apiId + "/stages")
        .then()
                .statusCode(201);

        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .subprotocols("graphql-ws")
                .buildAsync(URI.create("ws://127.0.0.1:" + testHttpPort + "/ws/" + apiId + "/probe"), new WebSocket.Listener() { })
                .get(10, TimeUnit.SECONDS);

        assertEquals("", socket.getSubprotocol(), "the server lists only MQTT names, so it echoes none and still upgrades");
        socket.abort();
    }

    @Test
    void webSocketPingIsAnsweredWithPong() throws Exception {
        CompletableFuture<byte[]> pong = new CompletableFuture<>();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .subprotocols("mqtt")
                .buildAsync(URI.create(ws()), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
                        byte[] bytes = new byte[message.remaining()];
                        message.get(bytes);
                        pong.complete(bytes);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);
        byte[] probe = "keepalive".getBytes(StandardCharsets.UTF_8);

        socket.sendPing(ByteBuffer.wrap(probe)).get(10, TimeUnit.SECONDS);

        assertArrayEquals(probe, pong.get(10, TimeUnit.SECONDS));
        socket.abort();
    }

    // ==================== Broker semantics over the bridge ====================

    @Test
    void mqtt5ClientWorksOverWss() throws Exception {
        String topic = "contract/mqtt5/" + System.nanoTime();
        byte[] payload = "mqtt5 over wss".getBytes(StandardCharsets.UTF_8);
        BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();

        org.eclipse.paho.mqttv5.client.MqttClient client = new org.eclipse.paho.mqttv5.client.MqttClient(
                wss(), "ws-v5-" + System.nanoTime(), null);
        client.setCallback(new MqttCallback() {
            @Override
            public void disconnected(MqttDisconnectResponse disconnectResponse) {
            }

            @Override
            public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {
            }

            @Override
            public void messageArrived(String topic, org.eclipse.paho.mqttv5.common.MqttMessage message) {
                received.add(message.getPayload());
            }

            @Override
            public void deliveryComplete(IMqttToken token) {
            }

            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
            }

            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {
            }
        });
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setSocketFactory(IotMqttWebSocketIntegrationTest.trustOnlyFlociCa().getSocketFactory());
        client.connect(options);
        try {
            assertTrue(client.isConnected());
            client.subscribe(topic, 1);
            client.publish(topic, payload, 1, false);
            byte[] delivered = received.poll(10, TimeUnit.SECONDS);
            assertNotNull(delivered, "no MQTT 5 publish received within 10s");
            assertArrayEquals(payload, delivered);
        } finally {
            client.disconnect();
            client.close();
        }
    }

    @Test
    void sameClientIdOverWebSocketTakesOverTheTcpSession() throws Exception {
        String clientId = "takeover-" + System.nanoTime();
        CountDownLatch tcpLost = new CountDownLatch(1);
        MqttClient tcp = new MqttClient("tcp://127.0.0.1:" + PLAIN_PORT, clientId, new MemoryPersistence());
        tcp.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                tcpLost.countDown();
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
            }

            @Override
            public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
            }
        });
        tcp.connect();
        try {
            MqttClient ws = new MqttClient(ws(), clientId, new MemoryPersistence());
            ws.connect();
            try {
                assertTrue(ws.isConnected());
                assertTrue(tcpLost.await(10, TimeUnit.SECONDS), "AWS drops the older session with the same client id");
            } finally {
                ws.disconnect();
                ws.close();
            }
        } finally {
            tcp.close();
        }
    }

    @Test
    void retainedMessagePublishedOverTcpReachesALaterWebSocketSubscriber() throws Exception {
        String topic = "contract/retained/" + System.nanoTime();
        byte[] payload = "kept".getBytes(StandardCharsets.UTF_8);
        MqttClient tcp = new MqttClient("tcp://127.0.0.1:" + PLAIN_PORT, "retain-pub-" + System.nanoTime(), new MemoryPersistence());
        tcp.connect();
        tcp.publish(topic, payload, 1, true);
        tcp.disconnect();
        tcp.close();

        try (PahoWs subscriber = PahoWs.connect(wss(), "retain-sub-" + System.nanoTime())) {
            subscriber.client.subscribe(topic, 1);
            assertArrayEquals(payload, subscriber.take());
        }
    }

    @Test
    void qos2PublishIsDisconnectedAsOnAws() throws Exception {
        try (PahoWs client = PahoWs.connect(ws(), "qos2-" + System.nanoTime())) {
            MqttMessage message = new MqttMessage("exactly once".getBytes(StandardCharsets.UTF_8));
            message.setQos(2);
            try {
                client.client.publish("contract/qos2/" + System.nanoTime(), message);
            } catch (MqttException dropWhileWaitingForPubrec) {
                LOG.debugv("QoS 2 publish reported the disconnect: {0}", dropWhileWaitingForPubrec.getMessage());
            }
            assertTrue(client.lost.await(10, TimeUnit.SECONDS), "AWS IoT disconnects a client that publishes QoS 2");
        }
    }

    @Test
    void shadowTopicsWorkOverWebSocket() throws Exception {
        String thing = "wsThing" + System.nanoTime();
        try (PahoWs client = PahoWs.connect(wss(), "shadow-" + System.nanoTime())) {
            client.client.subscribe("$aws/things/" + thing + "/shadow/update/accepted", 1);
            client.client.publish("$aws/things/" + thing + "/shadow/update",
                    "{\"state\":{\"desired\":{\"led\":\"on\"}},\"clientToken\":\"ws-token\"}".getBytes(StandardCharsets.UTF_8), 1, false);

            JsonNode accepted = OBJECT_MAPPER.readTree(client.take());
            assertEquals("on", accepted.path("state").path("desired").path("led").asText());
            assertEquals("ws-token", accepted.path("clientToken").asText());
        }
    }

    @Test
    void topicRuleFiresForAWebSocketPublish() throws Exception {
        String source = "contract/rule/source/" + System.nanoTime();
        String target = "contract/rule/target/" + System.nanoTime();
        given()
            .contentType("application/json")
            .body("""
                {
                  "topicRulePayload": {
                    "sql": "SELECT * FROM '%s'",
                    "actions": [
                      {
                        "republish": {
                          "roleArn": "arn:aws:iam::000000000000:role/iot-rule-role",
                          "topic": "%s"
                        }
                      }
                    ]
                  }
                }
                """.formatted(source, target))
        .when()
            .put("/rules/wsRepublish" + System.nanoTime())
        .then()
            .statusCode(200);

        try (PahoWs subscriber = PahoWs.connect(wss(), "rule-sub-" + System.nanoTime())) {
            subscriber.client.subscribe(target, 1);
            try (PahoWs publisher = PahoWs.connect(wss(), "rule-pub-" + System.nanoTime())) {
                publisher.client.publish(source, "{\"v\":1}".getBytes(StandardCharsets.UTF_8), 1, false);
            }
            assertArrayEquals("{\"v\":1}".getBytes(StandardCharsets.UTF_8), subscriber.take());
        }
    }

    @Test
    void mqttKeepAlivePingsCrossTheBridge() throws Exception {
        MqttClient client = new MqttClient(ws(), "keepalive-" + System.nanoTime(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setKeepAliveInterval(1);
        client.connect(options);
        try {
            Thread.sleep(3500);
            assertTrue(client.isConnected(), "PINGREQ and PINGRESP keep a quiet session alive");
        } finally {
            client.disconnect();
            client.close();
        }
    }

    @Test
    void connectionApisSeeTheWebSocketSession() throws Exception {
        String clientId = "ws-api-" + System.nanoTime();
        String topic = "contract/api/" + System.nanoTime();
        byte[] payload = "direct".getBytes(StandardCharsets.UTF_8);

        try (PahoWs client = PahoWs.connect(wss(), clientId)) {
            client.client.subscribe(topic, 0);

            given()
                .queryParam("includeSocketInformation", true)
            .when()
                .get("/connections/{clientId}", clientId)
            .then()
                .statusCode(200)
                .body("clientId", equalTo(clientId))
                .body("connected", equalTo(true))
                .body("sourceIp", equalTo("127.0.0.1"));

            given()
            .when()
                .get("/connections/{clientId}/subscriptions", clientId)
            .then()
                .statusCode(200)
                .body("subscriptions[0].topicFilter", equalTo(topic));

            given()
                .queryParam("topic", topic)
                .body(payload)
            .when()
                .post("/connections/{clientId}/messages", clientId)
            .then()
                .statusCode(200);
            assertArrayEquals(payload, client.take());

            given()
                .queryParam("cleanSession", true)
            .when()
                .delete("/connections/{clientId}", clientId)
            .then()
                .statusCode(200);
            assertTrue(client.lost.await(10, TimeUnit.SECONDS), "DeleteConnection reaches a WebSocket session");
        }
    }

    // ==================== helpers ====================

    private String ws() {
        return "ws://127.0.0.1:" + testHttpPort + "/mqtt";
    }

    private String wss() {
        return "wss://127.0.0.1:" + testSslPort + "/mqtt";
    }

    /**
     * The status line of a hand-written upgrade request. The JDK HttpClient refuses to send the
     * Upgrade header itself, so this goes over a plain socket.
     */
    private int rawUpgradeStatus(String method, String version) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", testHttpPort)) {
            socket.setSoTimeout(10_000);
            String request = method + " /mqtt HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + testHttpPort + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "Sec-WebSocket-Version: " + version + "\r\n"
                    + "Sec-WebSocket-Protocol: mqtt\r\n"
                    + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String statusLine = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            assertNotNull(statusLine, "no status line");
            assertTrue(statusLine.startsWith("HTTP/1.1 "), statusLine);
            return Integer.parseInt(statusLine.substring(9, 12));
        }
    }

    /** Paho 3.1.1 client over the bridge with a payload queue and a connection-lost latch. */
    private static final class PahoWs implements AutoCloseable {
        final MqttClient client;
        final CountDownLatch lost = new CountDownLatch(1);
        private final BlockingQueue<byte[]> payloads = new LinkedBlockingQueue<>();

        private PahoWs(MqttClient client) {
            this.client = client;
        }

        static PahoWs connect(String url, String clientId) throws Exception {
            MqttClient client = new MqttClient(url, clientId, new MemoryPersistence());
            PahoWs paho = new PahoWs(client);
            client.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    paho.lost.countDown();
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    paho.payloads.add(message.getPayload());
                }

                @Override
                public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
                }
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            if (url.startsWith("wss://")) {
                options.setSocketFactory(IotMqttWebSocketIntegrationTest.trustOnlyFlociCa().getSocketFactory());
            }
            client.connect(options);
            return paho;
        }

        byte[] take() throws InterruptedException {
            byte[] payload = payloads.poll(10, TimeUnit.SECONDS);
            assertNotNull(payload, "no publish received within 10s");
            return payload;
        }

        @Override
        public void close() throws MqttException {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        }
    }
}
