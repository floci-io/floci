package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.services.iot.IotMqttWebSocketIntegrationTest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The deployment claims behind {@code wss://<endpoint>:443/mqtt}: in production one port fronts
 * both Quarkus servers through {@link TlsProxyServer}, which sniffs the first byte, and a custom
 * domain learned at runtime is served by the same HTTPS listener. Neither is proven by tests that
 * dial the Quarkus test ports directly.
 */
@QuarkusTest
@TestProfile(IotMqttWebSocketIntegrationTest.Profile.class)
class IotMqttWebSocketProxyIntegrationTest {

    static final String LEARNED_HOST = "iot.example.localhost.floci.io";
    private static final byte[] CONNACK_ACCEPTED = {0x20, 0x02, 0x00, 0x00};

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int testSslPort;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int testHttpPort;

    @Inject
    Vertx vertx;

    @Inject
    TlsCertificateManager certificateManager;

    private TlsProxyServer proxy;

    @AfterEach
    void stopProxy() {
        if (proxy != null) {
            proxy.stop();
        }
    }

    @Test
    void wssAndWsShareOnePortThroughTheTlsProxy() throws Exception {
        int publicPort = startProxy();
        String topic = "proxy/" + System.nanoTime();
        byte[] payload = "through the proxy".getBytes(StandardCharsets.UTF_8);

        try (PahoClient subscriber = PahoClient.connect("wss://127.0.0.1:" + publicPort + "/mqtt", "proxy-wss-" + System.nanoTime())) {
            subscriber.client.subscribe(topic, 1);
            try (PahoClient publisher = PahoClient.connect("ws://127.0.0.1:" + publicPort + "/mqtt", "proxy-ws-" + System.nanoTime())) {
                publisher.client.publish(topic, payload, 1, false);
            }
            assertArrayEquals(payload, subscriber.take(), "a TLS ClientHello and a plain GET on the same port both reach /mqtt");
        }
    }

    @Test
    void learnedHostnameServesMqttOverWssOnceEnsureHostRan() throws Exception {
        assertThrows(SSLHandshakeException.class, () -> upgradeAs(LEARNED_HOST),
                "precondition: the boot leaf covers one label under localhost.floci.io, not two");

        certificateManager.ensureHost(LEARNED_HOST);

        assertArrayEquals(CONNACK_ACCEPTED, upgradeAs(LEARNED_HOST),
                "after ensureHost a client verifying the learned name gets an MQTT session on /mqtt");
    }

    private int startProxy() throws Exception {
        int publicPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            publicPort = probe.getLocalPort();
        }
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        when(config.port()).thenReturn(publicPort);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(true);
        when(tls.awsHttpsPort()).thenReturn(0);
        proxy = new TlsProxyServer(vertx, config, testHttpPort, testSslPort);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try (Socket probe = new Socket("127.0.0.1", publicPort)) {
                return publicPort;
            } catch (IOException notYet) {
                assertTrue(System.nanoTime() < deadline, "the proxy never bound " + publicPort);
                Thread.sleep(50);
            }
        }
    }

    /**
     * Dials 127.0.0.1 the way a client that resolved {@code host} would: the TLS layer is given the
     * name, so SNI and HTTPS endpoint identification apply to it, trusting only the Floci CA. Then a hand-written upgrade with
     * that Host, one masked binary frame carrying an MQTT CONNECT, and the CONNACK frame back.
     */
    private byte[] upgradeAs(String host) throws Exception {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress("127.0.0.1", testSslPort), 5000);
        try (SSLSocket socket = (SSLSocket) IotMqttWebSocketIntegrationTest.trustOnlyFlociCa().getSocketFactory()
                .createSocket(plain, host, testSslPort, true)) {
            socket.setSoTimeout(10_000);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setServerNames(List.of(new SNIHostName(host)));
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();

            OutputStream out = socket.getOutputStream();
            out.write(("GET /mqtt HTTP/1.1\r\nHost: " + host + ":" + testSslPort + "\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Protocol: mqtt\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String status = reader.readLine();
            assertNotNull(status);
            assertTrue(status.startsWith("HTTP/1.1 101"), status);
            boolean echoed = false;
            for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                echoed |= line.equalsIgnoreCase("sec-websocket-protocol: mqtt");
            }
            assertTrue(echoed, "the learned host echoes the mqtt subprotocol");

            out.write(maskedBinaryFrame(connectPacket("learned-" + System.nanoTime())));
            out.flush();
            return readBinaryFramePayload(socket.getInputStream());
        }
    }

    private static byte[] connectPacket(String clientId) {
        byte[] id = clientId.getBytes(StandardCharsets.UTF_8);
        byte[] variable = new byte[10 + 2 + id.length];
        System.arraycopy(new byte[] {0x00, 0x04, 'M', 'Q', 'T', 'T', 0x04, 0x02, 0x00, 0x3C}, 0, variable, 0, 10);
        variable[10] = (byte) (id.length >> 8);
        variable[11] = (byte) id.length;
        System.arraycopy(id, 0, variable, 12, id.length);
        byte[] packet = new byte[2 + variable.length];
        packet[0] = 0x10;
        packet[1] = (byte) variable.length;
        System.arraycopy(variable, 0, packet, 2, variable.length);
        return packet;
    }

    /** RFC 6455 client frames are masked; payloads here are under 126 bytes. */
    private static byte[] maskedBinaryFrame(byte[] payload) {
        byte[] mask = {0x12, 0x34, 0x56, 0x78};
        byte[] frame = new byte[6 + payload.length];
        frame[0] = (byte) 0x82;
        frame[1] = (byte) (0x80 | payload.length);
        System.arraycopy(mask, 0, frame, 2, 4);
        for (int i = 0; i < payload.length; i++) {
            frame[6 + i] = (byte) (payload[i] ^ mask[i % 4]);
        }
        return frame;
    }

    private static byte[] readBinaryFramePayload(InputStream in) throws IOException {
        int first = in.read();
        int second = in.read();
        assertEquals(0x82, first, "a final binary frame");
        int length = second & 0x7F;
        byte[] payload = in.readNBytes(length);
        assertEquals(length, payload.length);
        return payload;
    }

    private static final class PahoClient implements AutoCloseable {
        final MqttClient client;
        private final BlockingQueue<byte[]> payloads = new LinkedBlockingQueue<>();

        private PahoClient(MqttClient client) {
            this.client = client;
        }

        static PahoClient connect(String url, String clientId) throws Exception {
            MqttClient client = new MqttClient(url, clientId, new MemoryPersistence());
            PahoClient paho = new PahoClient(client);
            client.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    paho.payloads.add(message.getPayload());
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });
            MqttConnectOptions options = new MqttConnectOptions();
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
        public void close() throws Exception {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        }
    }
}
