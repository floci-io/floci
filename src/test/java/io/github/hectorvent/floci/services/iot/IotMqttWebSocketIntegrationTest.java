package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.CertificateMetadata;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clients reach the broker as MQTT over WebSocket at {@code /mqtt} on the HTTP and HTTPS ports,
 * the way AWS IoT serves {@code wss://<endpoint>:443/mqtt}: the {@code mqtt} subprotocol is
 * echoed, the HTTPS certificate is the one every other Floci API serves, and the session behaves
 * like one on the plaintext TCP listener because that is where the frames end up.
 */
@QuarkusTest
@TestProfile(IotMqttWebSocketIntegrationTest.Profile.class)
public class IotMqttWebSocketIntegrationTest {

    static final Path DATA_DIR = Path.of("target", "floci-iot-mqtt-ws-test").toAbsolutePath();
    static final Path TLS_DIR = DATA_DIR.resolve("tls");
    static final int PLAIN_PORT = 18836;
    static final int TLS_PORT = 18837;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final short CLOSE_UNSUPPORTED_DATA = 1003;

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int testSslPort;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int testHttpPort;

    @Inject
    IotMqttBrokerService broker;

    @Test
    void connectSubscribePublishOverWssTrustingOnlyTheFlociCa() throws Exception {
        String topic = "ws/roundtrip/" + System.nanoTime();
        byte[] payload = "over websocket".getBytes(StandardCharsets.UTF_8);

        try (WsClient subscriber = WsClient.connect(wss("/mqtt"), "ws-sub-" + System.nanoTime(), null, null)) {
            subscriber.subscribe(topic);
            try (WsClient publisher = WsClient.connect(wss("/mqtt"), "ws-pub-" + System.nanoTime(), null, null)) {
                publisher.publish(topic, payload, 1);
            }
            assertArrayEquals(payload, subscriber.takePayload());
        }
    }

    @Test
    void plainWebSocketWorksOnTheHttpPort() throws Exception {
        String topic = "ws/plain/" + System.nanoTime();
        byte[] payload = "plain websocket".getBytes(StandardCharsets.UTF_8);

        try (WsClient client = WsClient.connect(ws("/mqtt"), "ws-plain-" + System.nanoTime(), null, null)) {
            assertTrue(client.isConnected());
            client.subscribe(topic);
            client.publish(topic, payload, 0);
            assertArrayEquals(payload, client.takePayload());
        }
    }

    @Test
    void webSocketAndTcpClientsShareTheBroker() throws Exception {
        String topic = "ws/shared/" + System.nanoTime();
        byte[] payload = "from tcp".getBytes(StandardCharsets.UTF_8);

        try (WsClient subscriber = WsClient.connect(wss("/mqtt"), "ws-shared-" + System.nanoTime(), null, null)) {
            subscriber.subscribe(topic);
            MqttClient tcp = new MqttClient("tcp://127.0.0.1:" + PLAIN_PORT, "tcp-pub-" + System.nanoTime(), new MemoryPersistence());
            tcp.connect();
            tcp.publish(topic, payload, 1, false);
            tcp.disconnect();
            tcp.close();
            assertArrayEquals(payload, subscriber.takePayload());
        }
    }

    @Test
    void customAuthorizerStyleConnectIsAccepted() throws Exception {
        String clientId = "tunnel-" + System.nanoTime();
        String username = clientId + "?x-amz-customauthorizer-name=my-authorizer";
        try (WsClient client = WsClient.connect(wss("/mqtt"), clientId, username, "token-" + System.nanoTime())) {
            assertTrue(client.isConnected());
        }
    }

    @Test
    void sigV4QueryStringOnTheMqttPathIsAccepted() throws Exception {
        String query = "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20260906%2Fus-east-1%2Fiotdevicegateway%2Faws4_request"
                + "&X-Amz-Date=20260906T120000Z&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=0000000000000000000000000000000000000000000000000000000000000000";
        try (WsClient client = WsClient.connect(wss("/mqtt" + query), "ws-sigv4-" + System.nanoTime(), null, null)) {
            assertTrue(client.isConnected());
        }
    }

    @Test
    void wrongPathIsNotAnMqttEndpoint() {
        assertEquals(404, handshakeStatus("/not-mqtt"));
        assertEquals(404, handshakeStatus("/mqtt/extra"));
    }

    @Test
    void plainGetOnTheMqttPathIsNotAnUpgrade() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + testHttpPort + "/mqtt")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
    }

    @Test
    void textFrameClosesTheSessionAsUnsupportedData() throws Exception {
        CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .subprotocols("mqtt")
                .buildAsync(URI.create(ws("/mqtt")), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        closeCode.complete(statusCode);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        closeCode.completeExceptionally(error);
                    }
                })
                .get(10, TimeUnit.SECONDS);
        assertEquals("mqtt", socket.getSubprotocol());

        socket.sendText("not an mqtt packet", true).get(10, TimeUnit.SECONDS);

        assertEquals(CLOSE_UNSUPPORTED_DATA, closeCode.get(10, TimeUnit.SECONDS));
    }

    @Test
    void brokerSideDisconnectDropsTheWebSocket() throws Exception {
        String clientId = "ws-dropped-" + System.nanoTime();
        try (WsClient client = WsClient.connect(ws("/mqtt"), clientId, null, null)) {
            assertTrue(broker.getConnection(clientId).isPresent(), "the bridged session is a broker session");

            assertTrue(broker.disconnectClient(clientId, true));

            assertTrue(client.awaitConnectionLost(10, TimeUnit.SECONDS), "closing the broker side closes the WebSocket");
        }
    }

    @Test
    void clientDisconnectFreesTheBrokerSession() throws Exception {
        String clientId = "ws-leaving-" + System.nanoTime();
        WsClient client = WsClient.connect(ws("/mqtt"), clientId, null, null);
        assertTrue(broker.getConnection(clientId).isPresent());

        client.close();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (broker.getConnection(clientId).isPresent() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(broker.getConnection(clientId).isEmpty(), "the broker session is gone once the WebSocket closes");
    }

    @Test
    void burstOfPublishesArrivesCompleteAndInOrder() throws Exception {
        String topic = "ws/burst/" + System.nanoTime();
        int count = 1000;
        byte[] filler = new byte[4096];

        try (WsClient subscriber = WsClient.connect(wss("/mqtt"), "ws-burst-sub-" + System.nanoTime(), null, null)) {
            subscriber.subscribe(topic);
            try (WsClient publisher = WsClient.connect(wss("/mqtt"), "ws-burst-pub-" + System.nanoTime(), null, null)) {
                for (int i = 0; i < count; i++) {
                    byte[] payload = ByteBuffer.allocate(4 + filler.length).putInt(i).put(filler).array();
                    publisher.publish(topic, payload, 0);
                }
                for (int i = 0; i < count; i++) {
                    byte[] payload = subscriber.takePayload();
                    assertEquals(4 + filler.length, payload.length);
                    assertEquals(i, ByteBuffer.wrap(payload).getInt(), "publishes keep their order across the bridge");
                }
            }
        }
    }

    @Test
    void manyConcurrentWebSocketClientsEachReceiveThePublish() throws Exception {
        String topic = "ws/fanout/" + System.nanoTime();
        byte[] payload = "to everyone".getBytes(StandardCharsets.UTF_8);
        int clients = 16;
        List<WsClient> subscribers = new ArrayList<>();
        try {
            CountDownLatch connected = new CountDownLatch(clients);
            List<Thread> threads = new ArrayList<>();
            List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
            for (int i = 0; i < clients; i++) {
                String clientId = "ws-fanout-" + i + "-" + System.nanoTime();
                Thread thread = new Thread(() -> {
                    try {
                        WsClient subscriber = WsClient.connect(wss("/mqtt"), clientId, null, null);
                        subscriber.subscribe(topic);
                        synchronized (subscribers) {
                            subscribers.add(subscriber);
                        }
                    } catch (Exception e) {
                        failures.add(e);
                    } finally {
                        connected.countDown();
                    }
                });
                threads.add(thread);
                thread.start();
            }
            assertTrue(connected.await(30, TimeUnit.SECONDS));
            assertTrue(failures.isEmpty(), "every concurrent handshake succeeds: " + failures);
            assertEquals(clients, subscribers.size());

            try (WsClient publisher = WsClient.connect(ws("/mqtt"), "ws-fanout-pub-" + System.nanoTime(), null, null)) {
                publisher.publish(topic, payload, 1);
            }
            for (WsClient subscriber : subscribers) {
                assertArrayEquals(payload, subscriber.takePayload());
            }
        } finally {
            for (WsClient subscriber : subscribers) {
                subscriber.close();
            }
        }
    }

    @Test
    void connectDisconnectChurnLeavesNoBrokerSessionBehind() throws Exception {
        List<String> clientIds = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String clientId = "churn-" + i + "-" + System.nanoTime();
            clientIds.add(clientId);
            String url = i % 2 == 0 ? ws("/mqtt") : wss("/mqtt");
            try (WsClient client = WsClient.connect(url, clientId, null, null)) {
                assertTrue(client.isConnected());
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (String clientId : clientIds) {
            while (broker.getConnection(clientId).isPresent() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(broker.getConnection(clientId).isEmpty(), clientId + " still has a broker session");
        }
    }

    @Test
    void plaintextTcpListenerStillWorks() throws Exception {
        MqttClient client = new MqttClient("tcp://127.0.0.1:" + PLAIN_PORT, "tcp-" + System.nanoTime(), new MemoryPersistence());
        client.connect();
        assertTrue(client.isConnected());
        client.disconnect();
        client.close();
    }

    private String ws(String pathAndQuery) {
        return "ws://127.0.0.1:" + testHttpPort + pathAndQuery;
    }

    private String wss(String pathAndQuery) {
        return "wss://127.0.0.1:" + testSslPort + pathAndQuery;
    }

    /**
     * The status of a failed handshake, sent the way Paho sends it (with the mqtt subprotocol), so
     * a 404 proves the server routes on the path and not on the subprotocol.
     */
    private int handshakeStatus(String path) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .subprotocols("mqtt")
                .buildAsync(URI.create(ws(path)), new WebSocket.Listener() { })
                .get(10, TimeUnit.SECONDS));
        WebSocketHandshakeException handshake = assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
        return handshake.getResponse().statusCode();
    }

    public static SSLContext trustOnlyFlociCa() throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("floci", FlociCertificateAuthority.loadOrCreate(TLS_DIR).certificate());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static final class WsClient implements AutoCloseable {
        private final MqttClient client;
        private final BlockingQueue<byte[]> payloads = new LinkedBlockingQueue<>();
        private final CountDownLatch connectionLost = new CountDownLatch(1);

        private WsClient(MqttClient client) {
            this.client = client;
        }

        static WsClient connect(String url, String clientId, String username, String password) throws Exception {
            MqttClient client = new MqttClient(url, clientId, new MemoryPersistence());
            WsClient wsClient = new WsClient(client);
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    wsClient.connectionLost.countDown();
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    wsClient.payloads.add(message.getPayload());
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            if (url.startsWith("wss://")) {
                options.setSocketFactory(trustOnlyFlociCa().getSocketFactory());
            }
            if (username != null) {
                options.setUserName(username);
                options.setPassword(password.toCharArray());
            }
            client.connect(options);
            return wsClient;
        }

        boolean isConnected() {
            return client.isConnected();
        }

        void subscribe(String topic) throws MqttException {
            client.subscribe(topic, 1);
        }

        void publish(String topic, byte[] payload, int qos) throws MqttException {
            client.publish(topic, payload, qos, false);
        }

        byte[] takePayload() throws InterruptedException {
            byte[] payload = payloads.poll(10, TimeUnit.SECONDS);
            assertNotNull(payload, "no publish received over WebSocket within 10s");
            return payload;
        }

        boolean awaitConnectionLost(long timeout, TimeUnit unit) throws InterruptedException {
            return connectionLost.await(timeout, unit);
        }

        @Override
        public void close() throws MqttException {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        }
    }

    /**
     * TLS on with a CA-issued leaf on disk, exactly what TlsConfigSource produces in production.
     * TlsConfigSource reads system properties, not profile overrides, so the profile lays the files
     * down and points the TLS registry at them. The broker's plaintext listener is the bridge's
     * target.
     */
    public static final class Profile implements QuarkusTestProfile {

        static {
            try {
                Files.createDirectories(TLS_DIR);
                for (String name : List.of("floci-server.crt", "floci-server.key", "floci-server.metadata.json")) {
                    Files.deleteIfExists(TLS_DIR.resolve(name));
                }
                FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(TLS_DIR);
                List<String> sans = List.of("localhost", "127.0.0.1", "*.localhost.floci.io");
                var leaf = ca.issueServerCertificate("localhost", sans, KeyAlgorithm.RSA_2048, null);
                Files.writeString(TLS_DIR.resolve("floci-server.crt"), leaf.certificatePem());
                Files.writeString(TLS_DIR.resolve("floci-server.key"), leaf.privateKeyPem());
                Files.writeString(TLS_DIR.resolve("floci-server.metadata.json"),
                        OBJECT_MAPPER.writeValueAsString(CertificateMetadata.create(sans, "dev")));
            } catch (IOException e) {
                throw new IllegalStateException("could not prepare the TLS fixtures", e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("floci.tls.enabled", "true"),
                    Map.entry("floci.tls.self-signed", "true"),
                    Map.entry("floci.tls.aws-https-port", "0"),
                    Map.entry("floci.storage.persistent-path", DATA_DIR.toString()),
                    Map.entry("quarkus.tls.key-store.pem.0.cert", TLS_DIR.resolve("floci-server.crt").toString()),
                    Map.entry("quarkus.tls.key-store.pem.0.key", TLS_DIR.resolve("floci-server.key").toString()),
                    Map.entry("quarkus.http.insecure-requests", "enabled"),
                    Map.entry("floci.services.iot.mqtt.enabled", "true"),
                    Map.entry("floci.services.iot.mqtt.auto-start", "true"),
                    Map.entry("floci.services.iot.mqtt.host", "127.0.0.1"),
                    Map.entry("floci.services.iot.mqtt.port", Integer.toString(PLAIN_PORT)),
                    Map.entry("floci.services.iot.mqtt.tls-port", Integer.toString(TLS_PORT)));
        }
    }
}
