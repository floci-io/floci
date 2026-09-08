package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.iot.IotService.RegisteredDevice;
import io.github.hectorvent.floci.services.iot.model.IotCertificate;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.PemKeyCertOptions;
import jakarta.enterprise.inject.Instance;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * How the TLS listener turns the registry's answers into CONNACKs, against a real Vert.x broker
 * and a mocked {@link IotService}: which connections are refused, with which code, what the
 * evaluation is given, and that a refusal touches nothing else.
 */
class IotMqttBrokerDeviceVerificationTest {

    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    private static Vertx vertx;
    private static FlociCertificateAuthority ca;
    private static CertificateGenerator.GeneratedCertificate serverLeaf;
    private static CertificateGenerator.GeneratedCertificate deviceLeaf;
    private static CertificateGenerator.GeneratedCertificate otherLeaf;

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final TlsConfigurationRegistry registry = mock(TlsConfigurationRegistry.class);
    private final TlsConfiguration tls = mock(TlsConfiguration.class);
    private final IotService service = mock(IotService.class);
    @SuppressWarnings("unchecked")
    private final Instance<IotService> iotService = mock(Instance.class);
    private int plainPort;
    private int tlsPort;
    private IotMqttBrokerService broker;

    @BeforeAll
    static void keyMaterial(@TempDir Path dir) {
        vertx = Vertx.vertx();
        ca = FlociCertificateAuthority.loadOrCreate(dir);
        serverLeaf = ca.issueServerCertificate("localhost", List.of("localhost", "127.0.0.1"), KeyAlgorithm.RSA_2048, null);
        deviceLeaf = ca.issueClientCertificate("device");
        otherLeaf = ca.issueClientCertificate("other");
    }

    @AfterAll
    static void closeVertx() {
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @BeforeEach
    void brokerWithTlsOn() throws IOException {
        plainPort = freePort();
        do {
            tlsPort = freePort();
        } while (tlsPort == plainPort);
        when(config.services().iot().enabled()).thenReturn(true);
        when(config.services().iot().mqtt().enabled()).thenReturn(true);
        when(config.services().iot().mqtt().host()).thenReturn("127.0.0.1");
        when(config.services().iot().mqtt().port()).thenReturn(plainPort);
        when(config.services().iot().mqtt().tlsPort()).thenReturn(tlsPort);
        when(config.tls().enabled()).thenReturn(true);
        when(registry.getDefault()).thenReturn(Optional.of(tls));
        when(tls.getKeyStoreOptions()).thenReturn(new PemKeyCertOptions()
                .addCertValue(Buffer.buffer(serverLeaf.certificatePem()))
                .addKeyValue(Buffer.buffer(serverLeaf.privateKeyPem())));
        when(iotService.get()).thenReturn(service);
        broker = new IotMqttBrokerService(config, vertx, iotService, registry);
        broker.startIfEnabled();
    }

    @AfterEach
    void stopBroker() {
        broker.stop();
    }

    @Test
    void anUnregisteredCertificateIsAnsweredWithConnackNotAuthorized() throws Exception {
        when(service.findRegisteredCertificate(any())).thenReturn(Optional.empty());

        assertNotAuthorized(() -> connectTls("unknown", deviceLeaf, null));

        verify(service).findRegisteredCertificate(certificate(deviceLeaf));
        verify(service, never()).isConnectAllowed(any(), anyString(), any(), any());
    }

    @Test
    void anMqtt5ClientIsRefusedWithTheMqtt5ReasonCode() throws Exception {
        when(service.findRegisteredCertificate(any())).thenReturn(Optional.empty());
        org.eclipse.paho.mqttv5.client.MqttClient client =
                new org.eclipse.paho.mqttv5.client.MqttClient("ssl://127.0.0.1:" + tlsPort, "v5-unknown", null);
        org.eclipse.paho.mqttv5.client.MqttConnectionOptions options = new org.eclipse.paho.mqttv5.client.MqttConnectionOptions();
        options.setConnectionTimeout(10);
        options.setAutomaticReconnect(false);
        options.setSocketFactory(sslContext(keyManagers(deviceLeaf)).getSocketFactory());
        try {
            org.eclipse.paho.mqttv5.common.MqttException refused =
                    assertThrows(org.eclipse.paho.mqttv5.common.MqttException.class, () -> client.connect(options));

            assertEquals(0x87, refused.getReasonCode());
        } finally {
            client.close();
        }
    }

    @Test
    void aClientWithoutACertificateIsRefusedWithoutConsultingTheRegistry() throws Exception {
        assertNotAuthorized(() -> connectTls("anonymous", null, null));

        verifyNoInteractions(service);
    }

    /** Paho sends the dialled host as the TLS server name; an address literal is not a domain name. */
    @Test
    void anAdmittedDeviceIsEvaluatedWithItsClientIdAndAddress() throws Exception {
        RegisteredDevice device = registered(deviceLeaf);
        when(service.findRegisteredCertificate(certificate(deviceLeaf))).thenReturn(Optional.of(device));
        when(service.isConnectAllowed(eq(device), eq("sensor-1"), eq("127.0.0.1"), isNull())).thenReturn(true);

        MqttClient client = connectTls("sensor-1", deviceLeaf, null);
        try {
            assertTrue(client.isConnected());
            assertTrue(broker.getConnection("sensor-1").isPresent());
            verify(service).isConnectAllowed(device, "sensor-1", "127.0.0.1", null);
        } finally {
            client.disconnect();
            client.close();
        }
    }

    @Test
    void theServerNameTheClientSentIsTheDomainNameOfTheEvaluation() throws Exception {
        RegisteredDevice device = registered(deviceLeaf);
        when(service.findRegisteredCertificate(certificate(deviceLeaf))).thenReturn(Optional.of(device));
        when(service.isConnectAllowed(eq(device), eq("sensor-1"), eq("127.0.0.1"), eq("localhost"))).thenReturn(true);

        MqttClient client = connectTls("sensor-1", deviceLeaf, "localhost");
        try {
            assertTrue(client.isConnected());
            verify(service).isConnectAllowed(device, "sensor-1", "127.0.0.1", "localhost");
        } finally {
            client.disconnect();
            client.close();
        }
    }

    @Test
    void aDeniedDeviceIsAnsweredWithConnackNotAuthorized() throws Exception {
        RegisteredDevice device = registered(deviceLeaf);
        when(service.findRegisteredCertificate(certificate(deviceLeaf))).thenReturn(Optional.of(device));
        when(service.isConnectAllowed(any(), anyString(), any(), any())).thenReturn(false);

        assertNotAuthorized(() -> connectTls("sensor-1", deviceLeaf, null));

        assertTrue(broker.getConnection("sensor-1").isEmpty(), "no session is registered for a refused connect");
    }

    @Test
    void aRefusedConnectLeavesTheSessionHoldingThatClientIdConnected() throws Exception {
        RegisteredDevice device = registered(deviceLeaf);
        when(service.findRegisteredCertificate(certificate(deviceLeaf))).thenReturn(Optional.of(device));
        when(service.findRegisteredCertificate(certificate(otherLeaf))).thenReturn(Optional.empty());
        when(service.isConnectAllowed(eq(device), anyString(), any(), any())).thenReturn(true);

        MqttClient holder = connectTls("shared-id", deviceLeaf, null);
        try {
            assertNotAuthorized(() -> connectTls("shared-id", otherLeaf, null));

            Thread.sleep(200);
            assertTrue(holder.isConnected());
            assertTrue(broker.getConnection("shared-id").isPresent());
        } finally {
            holder.disconnect();
            holder.close();
        }
    }

    @Test
    void aFailureInsideTheRegistryLookupRefusesTheClientAndKeepsTheListenerServing() throws Exception {
        RegisteredDevice device = registered(deviceLeaf);
        when(service.findRegisteredCertificate(any()))
                .thenThrow(new IllegalStateException("storage unavailable"))
                .thenReturn(Optional.of(device));
        when(service.isConnectAllowed(eq(device), anyString(), any(), any())).thenReturn(true);

        assertNotAuthorized(() -> connectTls("sensor-1", deviceLeaf, null));

        MqttClient client = connectTls("sensor-1", deviceLeaf, null);
        try {
            assertTrue(client.isConnected(), "the listener still serves after a failed evaluation");
        } finally {
            client.disconnect();
            client.close();
        }
    }

    @Test
    void addressLiteralsAreNeverADomainName() {
        assertTrue(IotMqttBrokerService.isAddressLiteral("127.0.0.1"));
        assertTrue(IotMqttBrokerService.isAddressLiteral("::1"));
        assertTrue(IotMqttBrokerService.isAddressLiteral("[fe80::1]"));
        assertTrue(IotMqttBrokerService.isAddressLiteral("2001:db8::1"));
        assertFalse(IotMqttBrokerService.isAddressLiteral("localhost"));
        assertFalse(IotMqttBrokerService.isAddressLiteral("iot.dev.localhost.floci.io"));
        assertFalse(IotMqttBrokerService.isAddressLiteral("device1.example"));
    }

    @Test
    void thePlaintextListenerNeverConsultsTheRegistry() throws Exception {
        MqttClient client = new MqttClient("tcp://127.0.0.1:" + plainPort, "plain", new MemoryPersistence());
        try {
            client.connect();

            assertTrue(client.isConnected());
            verifyNoInteractions(service);
        } finally {
            client.disconnect();
            client.close();
        }
    }

    /** Dials {@code host}, which Paho also sends as the TLS server name; MQTT 3.1.1 pinned so a refusal is not retried as 3.1. */
    private MqttClient connectTls(String clientId, CertificateGenerator.GeneratedCertificate leaf, String host) throws Exception {
        MqttClient client = new MqttClient("ssl://" + (host == null ? "127.0.0.1" : host) + ":" + tlsPort, clientId, new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setConnectionTimeout(10);
        options.setAutomaticReconnect(false);
        options.setSocketFactory(sslContext(leaf == null ? null : keyManagers(leaf)).getSocketFactory());
        try {
            client.connect(options);
        } catch (MqttException e) {
            client.close();
            throw e;
        }
        return client;
    }

    private interface Connect {
        MqttClient run() throws Exception;
    }

    private static void assertNotAuthorized(Connect connect) {
        MqttException refused = assertThrows(MqttException.class, () -> {
            MqttClient client = connect.run();
            client.disconnect();
            client.close();
        });
        assertInstanceOf(MqttSecurityException.class, refused, "expected CONNACK not authorized, got: " + refused);
        assertEquals(MqttException.REASON_CODE_NOT_AUTHORIZED, refused.getReasonCode());
        assertFalse(refused.getMessage().isBlank());
    }

    private static RegisteredDevice registered(CertificateGenerator.GeneratedCertificate leaf) {
        IotCertificate certificate = new IotCertificate();
        certificate.setCertificateId("id-" + leaf.hashCode());
        certificate.setCertificateArn("arn:aws:iot:us-east-1:000000000000:cert/id-" + leaf.hashCode());
        certificate.setCertificatePem(leaf.certificatePem());
        certificate.setStatus("ACTIVE");
        return new RegisteredDevice("000000000000", "us-east-1", certificate);
    }

    private static X509Certificate certificate(CertificateGenerator.GeneratedCertificate leaf) {
        return GENERATOR.parseCertificate(leaf.certificatePem());
    }

    private static SSLContext sslContext(KeyManager[] clientKeys) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("floci", ca.certificate());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(clientKeys, tmf.getTrustManagers(), null);
        return context;
    }

    private static KeyManager[] keyManagers(CertificateGenerator.GeneratedCertificate leaf) throws Exception {
        KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
        keys.load(null, null);
        keys.setKeyEntry("device", GENERATOR.parsePrivateKey(leaf.privateKeyPem()), new char[0],
                new Certificate[] {GENERATOR.parseCertificate(leaf.certificatePem())});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, new char[0]);
        return kmf.getKeyManagers();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
