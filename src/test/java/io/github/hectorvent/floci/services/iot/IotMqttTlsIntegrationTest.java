package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.CertificateMetadata;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
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
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
 * {@code ssl://}, trusting one CA, verifying the endpoint name, with MQTT 3.1.1 or MQTT 5, and
 * with the same broker behaviour as the plaintext listener.
 */
@QuarkusTest
@TestProfile(IotMqttTlsIntegrationTest.Profile.class)
class IotMqttTlsIntegrationTest {

    static final Path DATA_DIR = Path.of("target", "floci-iot-mqtt-tls-test").toAbsolutePath();
    static final Path TLS_DIR = DATA_DIR.resolve("tls");
    static final int PLAIN_PORT = 18834;
    static final int TLS_PORT = 18835;
    static final String LEARNED_HOST = "iot.dev.localhost.floci.io";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    TlsCertificateManager certificateManager;

    @Inject
    IotMqttBrokerService broker;

    @Test
    void connectSubscribePublishOverTlsTrustingOnlyTheFlociCa() throws Exception {
        String topic = "tls/roundtrip/" + System.nanoTime();
        byte[] payload = "hello over 8883".getBytes(StandardCharsets.UTF_8);

        try (TlsClient subscriber = TlsClient.connect("tls-sub-" + System.nanoTime(), null)) {
            subscriber.subscribe(topic, 1);
            try (TlsClient publisher = TlsClient.connect("tls-pub-" + System.nanoTime(), null)) {
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
        options.setSocketFactory(trustOnlyTheCa(null).getSocketFactory());
        try {
            client.connect(options);
            assertTrue(client.isConnected());
            client.disconnect();
        } finally {
            client.close();
        }
    }

    @Test
    void aDeviceCertificateIssuedByTheCaIsAcceptedAndTheSessionIsVisibleToTheConnectionApi() throws Exception {
        String clientId = "tls-device-" + System.nanoTime();
        var device = FlociCertificateAuthority.loadOrCreate(TLS_DIR).issueClientCertificate("device-" + clientId);

        try (TlsClient client = TlsClient.connect(clientId, keyManagers(device))) {
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

        certificateManager.ensureHost(LEARNED_HOST);

        Set<String> sans = sans(handshakeVerifying(LEARNED_HOST));
        assertTrue(sans.contains(LEARNED_HOST), sans.toString());
        assertTrue(sans.contains("localhost"), "configured names are still served: " + sans);
    }

    @Test
    void aClientIdReconnectingOnThePlaintextListenerTakesOverTheTlsSession() throws Exception {
        String clientId = "tls-takeover-" + System.nanoTime();

        try (TlsClient first = TlsClient.connect(clientId, null)) {
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
        try (TlsClient subscriber = TlsClient.connect("tls-shadow-sub-" + System.nanoTime(), null)) {
            subscriber.subscribe("$aws/things/" + thing + "/shadow/update/accepted", 0);
            try (TlsClient publisher = TlsClient.connect("tls-shadow-pub-" + System.nanoTime(), null)) {
                publisher.publish("$aws/things/" + thing + "/shadow/update",
                        "{\"state\":{\"desired\":{\"color\":\"blue\"}},\"clientToken\":\"tls-token\"}".getBytes(StandardCharsets.UTF_8), 0);
            }
            String accepted = new String(subscriber.takePayload(), StandardCharsets.UTF_8);
            assertEquals("blue", OBJECT_MAPPER.readTree(accepted).path("state").path("desired").path("color").asText());
            assertEquals("tls-token", OBJECT_MAPPER.readTree(accepted).path("clientToken").asText());
        }
    }

    @Test
    void aRestartOfTheBrokerServesTlsAgain() throws Exception {
        broker.stop();
        broker.startIfEnabled();

        try (TlsClient client = TlsClient.connect("tls-restart-" + System.nanoTime(), null)) {
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
             SSLSocket socket = (SSLSocket) trustOnlyTheCa(null).getSocketFactory().createSocket(raw, host, TLS_PORT, true)) {
            SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.startHandshake();
            return (X509Certificate) socket.getSession().getPeerCertificates()[0];
        }
    }

    private static SSLContext trustOnlyTheCa(KeyManager[] clientKeys) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("floci", FlociCertificateAuthority.loadOrCreate(TLS_DIR).certificate());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(clientKeys, tmf.getTrustManagers(), null);
        return context;
    }

    private static KeyManager[] keyManagers(CertificateGenerator.GeneratedCertificate leaf) throws Exception {
        CertificateGenerator generator = new CertificateGenerator();
        KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
        keys.load(null, null);
        keys.setKeyEntry("device", generator.parsePrivateKey(leaf.privateKeyPem()), new char[0],
                new Certificate[] {generator.parseCertificate(leaf.certificatePem())});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, new char[0]);
        return kmf.getKeyManagers();
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

        static TlsClient connect(String clientId, KeyManager[] clientKeys) throws Exception {
            MqttClient client = new MqttClient("ssl://127.0.0.1:" + TLS_PORT, clientId, new MemoryPersistence());
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
            options.setSocketFactory(trustOnlyTheCa(clientKeys).getSocketFactory());
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
            throw new AssertionError("the TLS session stayed open after its client id reconnected elsewhere");
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
     * TLS on with a CA-issued leaf on disk, as TlsConfigSource leaves it in production. The
     * bootstrap reads system properties, not profile overrides, so the profile lays the files
     * down itself and points the TLS registry's default entry at them. The leaf carries
     * {@code 127.0.0.1} so Paho's hostname verification passes against {@code ssl://127.0.0.1}.
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
