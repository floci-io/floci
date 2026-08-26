package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSocketFactory;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IotMqttTlsIntegrationTest.TlsMqttProfile.class)
class IotMqttTlsIntegrationTest {

    private static final int PLAINTEXT_PORT = 18833;
    private static final int TLS_PORT = 18834;
    private static final String PLAINTEXT_BROKER_URI = "tcp://127.0.0.1:" + PLAINTEXT_PORT;
    private static final String TLS_BROKER_URI = "ssl://127.0.0.1:" + TLS_PORT;

    @Test
    void tlsClientWithTrustedCertConnectsAndPubSubs() throws Exception {
        String topic = "tls/devices/one/events/" + System.nanoTime();
        byte[] payload = "hello-over-tls".getBytes(StandardCharsets.UTF_8);

        try (MqttTestClient subscriber = connectTls("tls-sub")) {
            assertTrue(subscriber.isConnected());
            subscriber.subscribe(topic);

            try (MqttTestClient publisher = connectTls("tls-pub")) {
                publisher.publish(topic, payload);
            }

            MqttPublish received = subscriber.takePublish();
            assertEquals(topic, received.topic());
            assertArrayEquals(payload, received.payload());
        }
    }




    @Test
    void tlsClientWithoutClientCertIsRejected() {
        assertThrows(Exception.class, () -> {
            try (MqttTestClient client = connect(TLS_BROKER_URI, uniqueClientId("tls-no-cert"),
                    IotMqttTlsTestPki.INSTANCE.withoutClientCertSocketFactory())) {
                // connect() must fail: the listener requires a client certificate
            }
        });
    }

    @Test
    void tlsClientWithUntrustedClientCertIsRejected() {
        assertThrows(Exception.class, () -> {
            try (MqttTestClient client = connect(TLS_BROKER_URI, uniqueClientId("tls-untrusted"),
                    IotMqttTlsTestPki.INSTANCE.untrustedClientSocketFactory())) {
                // connect() must fail: the client certificate is not signed by the configured CA
            }
        });
    }

    @Test
    void plaintextListenerStillWorksAndSharesFanOutWithTls() throws Exception {
        String topic = "tls/devices/mixed/events/" + System.nanoTime();
        byte[] payload = "hello-across-listeners".getBytes(StandardCharsets.UTF_8);

        try (MqttTestClient tlsSubscriber = connectTls("mixed-tls-sub")) {
            tlsSubscriber.subscribe(topic);

            try (MqttTestClient plaintextPublisher =
                         connect(PLAINTEXT_BROKER_URI, uniqueClientId("mixed-plain-pub"), null)) {
                assertTrue(plaintextPublisher.isConnected());
                plaintextPublisher.publish(topic, payload);
            }

            MqttPublish received = tlsSubscriber.takePublish();
            assertEquals(topic, received.topic());
            assertArrayEquals(payload, received.payload());
        }
    }

    private MqttTestClient connectTls(String clientIdPrefix) throws MqttException {
        return connect(TLS_BROKER_URI, uniqueClientId(clientIdPrefix),
                IotMqttTlsTestPki.INSTANCE.trustedClientSocketFactory());
    }

    private String uniqueClientId(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private static MqttTestClient connect(String brokerUri, String clientId, SSLSocketFactory socketFactory)
            throws MqttException {
        return MqttTestClient.connect(brokerUri, clientId, socketFactory);
    }

    private record MqttPublish(String topic, byte[] payload) {
    }

    private static final class MqttTestClient implements AutoCloseable {
        private final MqttClient client;
        private final BlockingQueue<MqttPublish> publishes = new LinkedBlockingQueue<>();

        private MqttTestClient(MqttClient client) {
            this.client = client;
        }

        static MqttTestClient connect(String brokerUri, String clientId, SSLSocketFactory socketFactory)
                throws MqttException {
            MqttClient client = new MqttClient(brokerUri, clientId, new MemoryPersistence());
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
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(2);
            options.setKeepAliveInterval(60);
            options.setAutomaticReconnect(false);
            if (socketFactory != null) {
                options.setSocketFactory(socketFactory);
            }
            client.connect(options);
            return testClient;
        }

        boolean isConnected() {
            return client.isConnected();
        }

        void subscribe(String topic) throws MqttException {
            client.subscribe(topic, 0);
        }

        void publish(String topic, byte[] payload) throws MqttException {
            client.publish(topic, payload, 0, false);
        }

        MqttPublish takePublish() throws InterruptedException {
            return Optional.ofNullable(publishes.poll(2, TimeUnit.SECONDS))
                    .orElseThrow(() -> new AssertionError("No MQTT publish received"));
        }

        @Override
        public void close() throws MqttException {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        }
    }

    public static final class TlsMqttProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            IotMqttTlsTestPki pki = IotMqttTlsTestPki.INSTANCE;
            return Map.of(
                    "floci.services.iot.mqtt.enabled", "true",
                    "floci.services.iot.mqtt.auto-start", "true",
                    "floci.services.iot.mqtt.host", "127.0.0.1",
                    "floci.services.iot.mqtt.port", Integer.toString(PLAINTEXT_PORT),
                    "floci.services.iot.mqtt.tls.enabled", "true",
                    "floci.services.iot.mqtt.tls.port", Integer.toString(TLS_PORT),
                    "floci.services.iot.mqtt.tls.cert-path", pki.serverCertPath(),
                    "floci.services.iot.mqtt.tls.key-path", pki.serverKeyPath(),
                    "floci.services.iot.mqtt.tls.ca-path", pki.caPath()
            );
        }
    }
}
