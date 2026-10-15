package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.testing.IotMqttTlsProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.services.iot.IotMqttEnabledIntegrationTest.AWS_MAX_PAYLOAD;
import static io.github.hectorvent.floci.services.iot.IotMqttEnabledIntegrationTest.randomPayload;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Devices connect to the MQTT over TLS listener the way they connect to AWS IoT's 8883: over
 * {@code ssl://}, trusting one CA, verifying the endpoint name, presenting a registered device
 * certificate whose policy allows the connect, with MQTT 3.1.1 or MQTT 5, and with the same
 * broker behaviour as the plaintext listener.
 */
@QuarkusTest
@TestProfile(IotMqttTlsProfile.class)
class IotMqttTlsIntegrationTest {

    static final Path TLS_DIR = Path.of(IotMqttTlsProfile.TLS_DIR);
    static final int PLAIN_PORT = IotMqttTlsProfile.PLAIN_PORT;
    static final int TLS_PORT = IotMqttTlsProfile.TLS_PORT;
    static final String LEARNED_HOST = "iot.dev.localhost.floci.io";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String CONNECT_ANY_CLIENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"iot:Connect\",\"Resource\":\"arn:aws:iot:*:*:client/*\"}]}";

    @Inject
    TlsCertificateManager certificateManager;

    @Inject
    IotMqttBrokerService broker;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private IotDeviceIdentity device;

    /**
     * One registered device with a policy allowing iot:Connect for any client id, shared by the
     * TLS clients of a test and removed after it. Provisioned on first use, since the plaintext
     * tests need none.
     */
    private IotDeviceIdentity device() {
        if (device == null) {
            device = IotDeviceIdentity.provision(true, cleanup);
            device.attachNewPolicy(CONNECT_ANY_CLIENT, cleanup);
        }
        return device;
    }

    private TlsClient connectTls(String clientId) throws Exception {
        return TlsClient.connect(clientId, device().sslContext(TLS_DIR));
    }

    @Test
    void connectSubscribePublishOverTlsTrustingOnlyTheFlociCa() throws Exception {
        String topic = "tls/roundtrip/" + System.nanoTime();
        byte[] payload = "hello over 8883".getBytes(StandardCharsets.UTF_8);

        try (TlsClient subscriber = connectTls("tls-sub-" + System.nanoTime())) {
            subscriber.subscribe(topic, 1);
            try (TlsClient publisher = connectTls("tls-pub-" + System.nanoTime())) {
                publisher.publish(topic, payload, 1);
            }
            assertArrayEquals(payload, subscriber.takePayload());
        }
    }

    @Test
    void mqtt5ConnectsOverTls() throws Exception {
        org.eclipse.paho.mqttv5.client.MqttClient client = new org.eclipse.paho.mqttv5.client.MqttClient(
                "ssl://127.0.0.1:" + TLS_PORT, "tls-v5-" + System.nanoTime(), null);
        org.eclipse.paho.mqttv5.client.MqttConnectionOptions options =
                new org.eclipse.paho.mqttv5.client.MqttConnectionOptions();
        options.setCleanStart(true);
        options.setConnectionTimeout(10);
        options.setSocketFactory(device().sslContext(TLS_DIR).getSocketFactory());
        try {
            client.connect(options);
            assertTrue(client.isConnected());
            client.disconnect();
        } finally {
            client.close();
        }
    }

    @Test
    void aRegisteredDeviceIsAdmittedAndTheSessionIsVisibleToTheConnectionApi() throws Exception {
        String clientId = "tls-device-" + System.nanoTime();

        try (TlsClient client = connectTls(clientId)) {
            assertTrue(client.isConnected());
            given()
                .queryParam("includeSocketInformation", true)
            .when()
                .get("/connections/{clientId}", clientId)
            .then()
                .statusCode(200)
                .body("connected", equalTo(true))
                .body("sourceIp", equalTo("127.0.0.1"));
        }
    }

    @Test
    void plaintextOnTheTlsPortIsRefused() throws Exception {
        MqttClient plain = new MqttClient("tcp://127.0.0.1:" + TLS_PORT, "plain-on-tls-" + System.nanoTime(), new MemoryPersistence());
        try {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setConnectionTimeout(5);

            assertThrows(MqttException.class, () -> plain.connect(options));

            assertFalse(plain.isConnected());
        } finally {
            plain.close();
        }
    }

    @Test
    void plaintextListenerStillWorks() throws Exception {
        MqttClient client = new MqttClient("tcp://127.0.0.1:" + PLAIN_PORT, "plain-" + System.nanoTime(), new MemoryPersistence());
        try {
            client.connect();
            assertTrue(client.isConnected());
            client.disconnect();
        } finally {
            client.close();
        }
    }

    @Test
    void learnedHostnameIsPresentedOnTheNextTlsHandshake() throws Exception {
        assertThrows(SSLHandshakeException.class, () -> handshakeVerifying(LEARNED_HOST),
                "precondition: a device verifying the custom domain rejects the boot leaf");

        cleanup.register(certificateManager::clear);
        certificateManager.ensureHost(LEARNED_HOST);

        Set<String> sans = sans(handshakeVerifying(LEARNED_HOST));
        assertTrue(sans.contains(LEARNED_HOST), sans.toString());
        assertTrue(sans.contains("localhost"), "configured names are still served: " + sans);
    }

    @Test
    void aClientIdReconnectingOnThePlaintextListenerTakesOverTheTlsSession() throws Exception {
        String clientId = "tls-takeover-" + System.nanoTime();

        try (TlsClient first = connectTls(clientId)) {
            MqttClient second = new MqttClient("tcp://127.0.0.1:" + PLAIN_PORT, clientId, new MemoryPersistence());
            try {
                second.connect();
                first.awaitDisconnected();
                assertTrue(second.isConnected());
                second.disconnect();
            } finally {
                second.close();
            }
        }
    }

    @Test
    void shadowUpdateOverTlsPublishesTheAcceptedResponseOverTls() throws Exception {
        String thing = "tlsThing" + System.nanoTime();
        try (TlsClient subscriber = connectTls("tls-shadow-sub-" + System.nanoTime())) {
            subscriber.subscribe("$aws/things/" + thing + "/shadow/update/accepted", 0);
            try (TlsClient publisher = connectTls("tls-shadow-pub-" + System.nanoTime())) {
                publisher.publish("$aws/things/" + thing + "/shadow/update",
                        "{\"state\":{\"desired\":{\"color\":\"blue\"}},\"clientToken\":\"tls-token\"}".getBytes(StandardCharsets.UTF_8), 0);
                cleanup.register(() -> given().when().delete("/things/" + thing + "/shadow").then().statusCode(200));
            }
            String accepted = new String(subscriber.takePayload(), StandardCharsets.UTF_8);
            assertEquals("blue", OBJECT_MAPPER.readTree(accepted).path("state").path("desired").path("color").asText());
            assertEquals("tls-token", OBJECT_MAPPER.readTree(accepted).path("clientToken").asText());
        }
    }

    @Test
    void publishOfTheAwsMaximumPayloadIsDeliveredOverTls() throws Exception {
        String topic = "tls/limit/" + System.nanoTime();
        byte[] payload = randomPayload(AWS_MAX_PAYLOAD);

        try (TlsClient subscriber = connectTls("tls-limit-sub-" + System.nanoTime())) {
            subscriber.subscribe(topic, 1);
            try (TlsClient publisher = connectTls("tls-limit-pub-" + System.nanoTime())) {
                publisher.publish(topic, payload, 1);
            }
            assertArrayEquals(payload, subscriber.takePayload());
        }
    }

    @Test
    void publishAboveTheAwsMaximumPayloadDisconnectsOverTls() throws Exception {
        String topic = "tls/over-limit/" + System.nanoTime();
        byte[] after = "after".getBytes(StandardCharsets.UTF_8);

        try (TlsClient subscriber = connectTls("tls-over-sub-" + System.nanoTime())) {
            subscriber.subscribe(topic, 1);
            try (TlsClient publisher = connectTls("tls-over-pub-" + System.nanoTime())) {
                assertThrows(MqttException.class, () -> publisher.publish(topic, randomPayload(AWS_MAX_PAYLOAD + 1), 1));
                publisher.awaitDisconnected();
            }
            try (TlsClient next = connectTls("tls-over-next-" + System.nanoTime())) {
                next.publish(topic, after, 0);
            }
            assertArrayEquals(after, subscriber.takePayload(), "the oversized publish is never delivered");
        }
    }

    @Test
    void aRestartOfTheBrokerServesTlsAgain() throws Exception {
        broker.stop();
        broker.startIfEnabled();

        try (TlsClient client = connectTls("tls-restart-" + System.nanoTime())) {
            assertTrue(client.isConnected());
        }
    }

    /**
     * Connects by IP and hands JSSE the custom domain as the peer host, which is what a device SDK
     * does once DNS points the name at Floci: SNI carries the name and the certificate must verify
     * for it. The boot leaf's wildcard covers one label, so the three-label name is uncovered.
     */
    private static X509Certificate handshakeVerifying(String host) throws Exception {
        try (Socket raw = new Socket("127.0.0.1", TLS_PORT);
             SSLSocket socket = (SSLSocket) trustOnlyTheCa().getSocketFactory().createSocket(raw, host, TLS_PORT, true)) {
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.startHandshake();
            return (X509Certificate) socket.getSession().getPeerCertificates()[0];
        }
    }

    /** A handshake with no client certificate: enough to read the served leaf, never followed by a CONNECT. */
    private static SSLContext trustOnlyTheCa() throws Exception {
        return IotDeviceIdentity.trustOnlySslContext(TLS_DIR);
    }

    private static Set<String> sans(X509Certificate leaf) throws Exception {
        Set<String> sans = new TreeSet<>();
        for (List<?> entry : leaf.getSubjectAlternativeNames()) {
            sans.add(String.valueOf(entry.get(1)));
        }
        return sans;
    }

    /** A Paho MQTT 3.1.1 client on {@code ssl://}, with Paho's default hostname verification on. */
    private static final class TlsClient implements AutoCloseable {
        private final MqttClient client;
        private final BlockingQueue<byte[]> payloads = new LinkedBlockingQueue<>();

        private TlsClient(MqttClient client) {
            this.client = client;
        }

        static TlsClient connect(String clientId, SSLContext deviceContext) throws Exception {
            MqttClient client = new MqttClient("ssl://127.0.0.1:" + TLS_PORT, clientId, new MemoryPersistence());
            client.setTimeToWait(10_000);
            TlsClient tlsClient = new TlsClient(client);
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    tlsClient.payloads.add(message.getPayload());
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setAutomaticReconnect(false);
            options.setSocketFactory(deviceContext.getSocketFactory());
            client.connect(options);
            return tlsClient;
        }

        boolean isConnected() {
            return client.isConnected();
        }

        void subscribe(String topic, int qos) throws MqttException {
            client.subscribe(topic, qos);
        }

        void publish(String topic, byte[] payload, int qos) throws MqttException {
            client.publish(topic, payload, qos, false);
        }

        byte[] takePayload() throws InterruptedException {
            byte[] payload = payloads.poll(10, TimeUnit.SECONDS);
            assertNotNull(payload, "no publish received over TLS within 10s");
            return payload;
        }

        void awaitDisconnected() throws InterruptedException {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
            while (Instant.now().isBefore(deadline)) {
                if (!client.isConnected()) {
                    return;
                }
                Thread.sleep(25);
            }
            throw new AssertionError("the TLS session stayed open");
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
