package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbItemAccess;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbTableAccess;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.testing.ThreadStates;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.quarkus.tls.CertificateUpdatedEvent;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.tls.runtime.config.TlsConfig;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttTopicSubscription;
import io.vertx.mqtt.messages.MqttPublishMessage;
import io.vertx.mqtt.messages.MqttSubscribeMessage;
import io.vertx.mqtt.messages.MqttUnsubscribeMessage;
import jakarta.enterprise.inject.Instance;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The MQTT over TLS listener against a real Vert.x and a mocked TLS registry: what it serves,
 * when it is not opened, how a bind failure is reported, and how a reloaded server certificate
 * reaches the next handshake without a restart. Also that a publish's topic rules run on the
 * broker's own single-thread worker, off the event loop, and the AWS lifecycle events of a session.
 */
class IotMqttBrokerServiceTest {

    private static final CertificateGenerator GENERATOR = new CertificateGenerator();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * AWS IoT's default security policy, IoTSecurityPolicy_TLS13_1_2_2022_10, in JSSE names: the
     * TLS 1.3 suites and the RSA-authenticated TLS 1.2 suites it lists (transport-security page).
     */
    private static final Set<String> AWS_TLS13_SUITES = Set.of(
            "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256");
    private static final Set<String> AWS_TLS12_RSA_SUITES = Set.of(
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_RSA_WITH_AES_128_GCM_SHA256", "TLS_RSA_WITH_AES_128_CBC_SHA256", "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_GCM_SHA384", "TLS_RSA_WITH_AES_256_CBC_SHA256", "TLS_RSA_WITH_AES_256_CBC_SHA");

    private static Vertx vertx;
    private static FlociCertificateAuthority ca;
    private static CertificateGenerator.GeneratedCertificate bootLeaf;
    private static CertificateGenerator.GeneratedCertificate reissuedLeaf;

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final TlsConfigurationRegistry registry = mock(TlsConfigurationRegistry.class);
    private final TlsConfiguration tls = mock(TlsConfiguration.class);
    @SuppressWarnings("unchecked")
    private final Instance<IotService> iotService = mock(Instance.class);
    private final IotCustomAuthorizer customAuthorizer = mock(IotCustomAuthorizer.class);
    private final IotPublishEventRecorder recorder = new IotPublishEventRecorder();
    /** The broker's clock, which a keep-alive test moves forward instead of sleeping. */
    private final AtomicLong nanoTime = new AtomicLong();
    private int plainPort;
    private int tlsPort;
    private IotMqttBrokerService broker;

    @BeforeAll
    static void keyMaterial(@TempDir Path dir) {
        vertx = Vertx.vertx();
        ca = FlociCertificateAuthority.loadOrCreate(dir);
        bootLeaf = ca.issueServerCertificate("localhost", List.of("localhost", "127.0.0.1"), KeyAlgorithm.RSA_2048, null);
        reissuedLeaf = ca.issueServerCertificate("localhost",
                List.of("localhost", "127.0.0.1", "iot.dev.localhost.floci.io"), KeyAlgorithm.RSA_2048, null);
    }

    @AfterAll
    static void closeVertx() {
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }

    @BeforeEach
    void brokerWithTlsOn() throws IOException {
        int[] ports = FreePorts.anyInterfacePorts(2);
        plainPort = ports[0];
        tlsPort = ports[1];
        when(config.services().iot().enabled()).thenReturn(true);
        when(config.services().iot().mqtt().enabled()).thenReturn(true);
        when(config.services().iot().mqtt().host()).thenReturn("127.0.0.1");
        when(config.services().iot().mqtt().port()).thenReturn(plainPort);
        when(config.services().iot().mqtt().tlsPort()).thenReturn(tlsPort);
        when(config.tls().enabled()).thenReturn(true);
        when(registry.getDefault()).thenReturn(Optional.of(tls));
        when(tls.getKeyStoreOptions()).thenReturn(pem(bootLeaf));
        broker = new IotMqttBrokerService(config, vertx, iotService, customAuthorizer, registry, nanoTime::get);
    }

    @AfterEach
    void stopBroker() {
        broker.stop();
    }

    @Test
    void tlsListenerServesTheRegistryLeafAndAsksForAClientCertificateWithoutRequiringOne() throws Exception {
        broker.startIfEnabled();

        RecordingKeyManager clientKeys = new RecordingKeyManager();
        X509Certificate served = handshake(new KeyManager[] {clientKeys});

        assertEquals(serial(bootLeaf), served.getSerialNumber(), "the leaf from the TLS registry's default configuration");
        assertTrue(clientKeys.asked.get(), "the server sent a CertificateRequest");
        assertTrue(accepts(plainPort), "the plaintext listener is up as well");
    }

    @Test
    void aDeviceOnAwsDefaultSecurityPolicyNegotiatesTls13() throws Exception {
        broker.startIfEnabled();

        SSLSession session = negotiate("TLSv1.3", AWS_TLS13_SUITES);

        assertEquals("TLSv1.3", session.getProtocol());
        assertTrue(AWS_TLS13_SUITES.contains(session.getCipherSuite()), session.getCipherSuite());
    }

    @Test
    void aDeviceOnAwsDefaultSecurityPolicyNegotiatesTls12() throws Exception {
        broker.startIfEnabled();

        SSLSession session = negotiate("TLSv1.2", AWS_TLS12_RSA_SUITES);

        assertEquals("TLSv1.2", session.getProtocol());
        assertTrue(AWS_TLS12_RSA_SUITES.contains(session.getCipherSuite()), session.getCipherSuite());
    }

    @Test
    void aClientCertificateFromAnyIssuerCompletesTheHandshake() throws Exception {
        broker.startIfEnabled();
        CertificateGenerator.GeneratedCertificate device = GENERATOR.generateSelfSignedCertificate("device", List.of(), KeyAlgorithm.RSA_2048);

        X509Certificate served = handshake(keyManagers(device));

        assertEquals(serial(bootLeaf), served.getSerialNumber());
    }

    @Test
    void tlsPortZeroOpensOnlyThePlaintextListener() {
        when(config.services().iot().mqtt().tlsPort()).thenReturn(0);

        broker.startIfEnabled();

        assertTrue(accepts(plainPort));
        verifyNoInteractions(registry);
    }

    @Test
    void tlsDisabledOpensOnlyThePlaintextListener() {
        when(config.tls().enabled()).thenReturn(false);

        broker.startIfEnabled();

        assertTrue(accepts(plainPort));
        assertFalse(accepts(tlsPort), "no TLS listener while floci.tls.enabled is false");
        verifyNoInteractions(registry);
    }

    @Test
    void noDefaultTlsConfigurationLeavesTheTlsPortClosed() {
        when(registry.getDefault()).thenReturn(Optional.empty());

        broker.startIfEnabled();

        assertTrue(broker.isRunning());
        assertTrue(accepts(plainPort));
        assertFalse(accepts(tlsPort));
    }

    @Test
    void aTlsBindFailureLeavesNoListenerBehindAndTheNextStartSucceeds() throws Exception {
        try (ServerSocket blocker = new ServerSocket()) {
            blocker.bind(new InetSocketAddress("127.0.0.1", tlsPort));

            IllegalStateException failure = assertThrows(IllegalStateException.class, broker::startIfEnabled);

            assertTrue(failure.getMessage().contains(Integer.toString(tlsPort)), failure.getMessage());
            assertFalse(broker.isRunning());
            awaitClosed(plainPort);
        }

        broker.startIfEnabled();

        assertTrue(broker.isRunning());
        assertTrue(accepts(plainPort));
        assertEquals(serial(bootLeaf), handshake(null).getSerialNumber());
    }

    @Test
    void certificateUpdatedEventSwapsTheLeafForTheNextHandshake() throws Exception {
        broker.startIfEnabled();
        assertEquals(serial(bootLeaf), handshake(null).getSerialNumber());
        when(tls.getKeyStoreOptions()).thenReturn(pem(reissuedLeaf));

        broker.onCertificateUpdated(new CertificateUpdatedEvent(TlsConfig.DEFAULT_NAME, tls));

        assertEquals(serial(reissuedLeaf), handshake(null).getSerialNumber());
    }

    @Test
    void anEventForAnotherTlsConfigurationIsIgnored() throws Exception {
        broker.startIfEnabled();
        when(tls.getKeyStoreOptions()).thenReturn(pem(reissuedLeaf));

        broker.onCertificateUpdated(new CertificateUpdatedEvent("rds-proxy", tls));

        assertEquals(serial(bootLeaf), handshake(null).getSerialNumber());
    }

    @Test
    void aFailedSwapKeepsThePreviousLeaf() throws Exception {
        broker.startIfEnabled();
        when(tls.getKeyStoreOptions()).thenReturn(new PemKeyCertOptions()
                .addCertValue(Buffer.buffer("not a certificate"))
                .addKeyValue(Buffer.buffer("not a key")));

        broker.onCertificateUpdated(new CertificateUpdatedEvent(TlsConfig.DEFAULT_NAME, tls));

        assertEquals(serial(bootLeaf), handshake(null).getSerialNumber());
    }

    @Test
    void eventsBeforeStartAndAfterStopAreIgnored() {
        broker.onCertificateUpdated(new CertificateUpdatedEvent(TlsConfig.DEFAULT_NAME, tls));

        broker.startIfEnabled();
        broker.stop();
        broker.onCertificateUpdated(new CertificateUpdatedEvent(TlsConfig.DEFAULT_NAME, tls));

        assertFalse(broker.isRunning());
        awaitClosed(tlsPort);
        awaitClosed(plainPort);
    }

    @Test
    void stopClosesBothListenersAndStartReopensThem() throws Exception {
        broker.startIfEnabled();
        broker.stop();
        awaitClosed(tlsPort);
        awaitClosed(plainPort);
        when(tls.getKeyStoreOptions()).thenReturn(pem(reissuedLeaf));

        broker.startIfEnabled();

        assertTrue(accepts(plainPort));
        assertEquals(serial(reissuedLeaf), handshake(null).getSerialNumber(), "a restart reads the registry again");
    }

    /**
     * Reloads while connections arrive: every handshake completes with one of the two leaves.
     * Rebuilding the listener's SSL context instead would drop the connections accepted while
     * the new context is being built (Vert.x serves a pending update to them as null).
     */
    @Test
    void concurrentCertificateEventsAndHandshakesAllSucceed() throws Exception {
        broker.startIfEnabled();
        when(tls.getKeyStoreOptions()).thenAnswer(ignored -> pem(reissuedLeaf));
        int threads = 8;
        int rounds = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads * 2);
        List<Future<?>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                outcomes.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        broker.onCertificateUpdated(new CertificateUpdatedEvent(TlsConfig.DEFAULT_NAME, tls));
                    }
                    return null;
                }));
                outcomes.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        X509Certificate served = handshake(null);
                        assertTrue(served.getSerialNumber().equals(serial(bootLeaf))
                                || served.getSerialNumber().equals(serial(reissuedLeaf)), "one of the two leaves");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> outcome : outcomes) {
                outcome.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(serial(reissuedLeaf), handshake(null).getSerialNumber());
    }

    /**
     * A rule action that blocks (here a DynamoDB put held on a latch) runs off the event loop: the
     * publisher gets its PUBACK and a subscriber its copy while the put is still held, and the put
     * then lands as the rule's owner in the rule's region.
     */
    @Test
    void aBlockedRuleActionHoldsNeitherThePubackNorTheFanOut() throws Exception {
        CountDownLatch putStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch putDone = new CountDownLatch(1);
        AtomicReference<Scope> written = new AtomicReference<>();
        DynamoDbItemAccess items = mock(DynamoDbItemAccess.class);
        doAnswer(invocation -> {
            putStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
            written.set(invocation.getArgument(0));
            putDone.countDown();
            return null;
        }).when(items).putItem(any(), any(), any(), any(), any(), any());
        startBrokerWithRuleWriting(items);
        BlockingQueue<String> fannedOut = new LinkedBlockingQueue<>();
        MqttClient subscriber = connectPlain("subscriber");
        try {
            subscriber.subscribe("devices/+/metrics", 0,
                    (topic, message) -> fannedOut.add(new String(message.getPayload(), StandardCharsets.UTF_8)));
            MqttClient publisher = connectPlain("publisher");
            try {
                // A QoS 1 publish returns once its PUBACK arrives.
                publisher.publish("devices/d1/metrics", "{\"t\":1}".getBytes(StandardCharsets.UTF_8), 1, false);

                assertTrue(putStarted.await(10, TimeUnit.SECONDS), "the rule action started");
                assertEquals("{\"t\":1}", fannedOut.poll(10, TimeUnit.SECONDS), "fanned out while the put is held");
                assertEquals(1, putDone.getCount(), "the put is still held");
            } finally {
                release.countDown();
                disconnect(publisher);
            }
        } finally {
            disconnect(subscriber);
        }

        assertTrue(putDone.await(10, TimeUnit.SECONDS));
        assertEquals(new Scope("111122223333", "eu-west-1"), written.get());
    }

    /**
     * A rule action held on a latch occupies only the broker's own worker: an ordered blocking task
     * of the context that started the broker still runs.
     */
    @Test
    void aHeldRuleActionDoesNotHoldBlockingTasksOfTheContextThatStartedTheBroker() throws Exception {
        CountDownLatch putStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DynamoDbItemAccess items = mock(DynamoDbItemAccess.class);
        doAnswer(invocation -> {
            putStarted.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(items).putItem(any(), any(), any(), any(), any(), any());
        startBrokerWithRuleWriting(items);
        MqttClient publisher = connectPlain("publisher");
        try {
            publisher.publish("devices/d1/metrics", "{\"t\":1}".getBytes(StandardCharsets.UTF_8), 1, false);
            assertTrue(putStarted.await(10, TimeUnit.SECONDS), "the rule action started");

            assertEquals("ran", vertx.executeBlocking(() -> "ran", true)
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS),
                    "an ordered blocking task of the starting context ran while the put is held");
        } finally {
            release.countDown();
            disconnect(publisher);
        }
    }

    /**
     * A subscriber whose endpoint closes between the connected check and the write is skipped: the
     * publish still reaches every other subscriber.
     */
    @Test
    void aSubscriberClosingMidFanOutDoesNotStopTheOthersGettingTheirCopy() throws Exception {
        startBrokerWithRuleWriting(mock(DynamoDbItemAccess.class));
        AtomicReference<Handler<MqttSubscribeMessage>> subscribeHandler = new AtomicReference<>();
        MqttEndpoint closing = mock(MqttEndpoint.class);
        when(closing.clientIdentifier()).thenReturn("closing");
        when(closing.isConnected()).thenReturn(true);
        when(closing.subscribeHandler(any())).thenAnswer(invocation -> {
            subscribeHandler.set(invocation.getArgument(0));
            return closing;
        });
        when(closing.publish(anyString(), any(), any(), anyBoolean(), anyBoolean()))
                .thenThrow(new IllegalStateException("the connection closed"));
        MqttTopicSubscription requested = mock(MqttTopicSubscription.class);
        when(requested.topicName()).thenReturn("devices/+/metrics");
        when(requested.qualityOfService()).thenReturn(MqttQoS.AT_MOST_ONCE);
        MqttSubscribeMessage subscribe = mock(MqttSubscribeMessage.class);
        when(subscribe.topicSubscriptions()).thenReturn(List.of(requested));
        broker.handleEndpoint(closing, false);
        subscribeHandler.get().handle(subscribe);

        BlockingQueue<String> fannedOut = new LinkedBlockingQueue<>();
        MqttClient subscriber = connectPlain("subscriber");
        try {
            subscriber.subscribe("devices/+/metrics", 0,
                    (topic, message) -> fannedOut.add(new String(message.getPayload(), StandardCharsets.UTF_8)));

            broker.publish("devices/d1/metrics", "{\"t\":1}".getBytes(StandardCharsets.UTF_8));

            verify(closing).publish(eq("devices/d1/metrics"), any(), any(), anyBoolean(), anyBoolean());
            assertEquals("{\"t\":1}", fannedOut.poll(10, TimeUnit.SECONDS), "the other subscriber got its copy");
        } finally {
            disconnect(subscriber);
        }
    }

    /**
     * A WebSocket client's CONNECT reaches the broker from the bridge's loopback socket, so the
     * policies its custom authorizer returns are evaluated against the address the bridge recorded
     * for the WebSocket client.
     */
    @Test
    void aWebSocketConnectThroughACustomAuthorizerIsEvaluatedAgainstTheWebSocketClientsAddress() {
        IotService service = mock(IotService.class);
        when(iotService.get()).thenReturn(service);
        when(customAuthorizer.connectPolicies(eq("ws-auth"), eq("ws-client"), any(), any(), any()))
                .thenReturn(List.of("{}"));
        SocketAddress bridge = SocketAddress.inetSocketAddress(50123, "127.0.0.1");
        broker.webSocketUpgrades().put(bridge, new IotCustomAuthorizer.WebSocketUpgrade(
                Map.of(), IotCustomAuthorizer.NAME_PARAM + "=ws-auth", null, "203.0.113.7", 41233));
        MqttEndpoint endpoint = mock(MqttEndpoint.class);
        when(endpoint.clientIdentifier()).thenReturn("ws-client");
        when(endpoint.remoteAddress()).thenReturn(bridge);

        broker.handleEndpoint(endpoint, false);

        verify(service, timeout(10_000)).isConnectAllowedBy(List.of("{}"), "ws-client", "203.0.113.7", null);
    }

    @Test
    void aWebSocketClientsConnectedEventCarriesTheWebSocketClientsAddress() throws Exception {
        startBrokerRecordingPublishes();
        SocketAddress bridge = SocketAddress.inetSocketAddress(50124, "127.0.0.1");
        broker.webSocketUpgrades().put(bridge, new IotCustomAuthorizer.WebSocketUpgrade(Map.of(), "", null, "203.0.113.7", 41234));
        MqttEndpoint endpoint = mock(MqttEndpoint.class);
        when(endpoint.clientIdentifier()).thenReturn("ws-client");
        when(endpoint.remoteAddress()).thenReturn(bridge);
        when(endpoint.isConnected()).thenReturn(true);

        broker.handleEndpoint(endpoint, false);

        assertEquals("203.0.113.7", awaitPresence("connected", "ws-client", 1).get(0).get("ipAddress").asText(),
                "the WebSocket client's address, not the bridge's loopback socket");
        assertEquals(new IotMqttBrokerService.ConnectionInfo("ws-client", "203.0.113.7", 41234),
                broker.getConnection("ws-client").orElseThrow(), "the WebSocket client's address and port");
    }

    @Test
    void aConnectPublishesTheConnectedEventInAwsFieldOrder() throws Exception {
        startBrokerRecordingPublishes();
        long before = System.currentTimeMillis();
        MqttClient client = connectPlain("presence-client");
        try {
            JsonNode connected = awaitPresence("connected", "presence-client", 1).get(0);

            assertEquals(List.of("clientId", "timestamp", "eventType", "sessionIdentifier", "versionNumber", "ipAddress"),
                    fieldNames(connected), "no principalIdentifier on the plaintext listener");
            assertEquals("presence-client", connected.get("clientId").asText());
            assertTrue(connected.get("timestamp").isIntegralNumber(), connected.toString());
            assertTrue(connected.get("timestamp").asLong() >= before
                    && connected.get("timestamp").asLong() <= System.currentTimeMillis(), "epoch milliseconds: " + connected);
            assertEquals("connected", connected.get("eventType").asText());
            assertDashedUuid(connected.get("sessionIdentifier").asText());
            assertTrue(connected.get("versionNumber").isIntegralNumber(), connected.toString());
            assertEquals(0, connected.get("versionNumber").asLong(), "a fresh client id starts at 0");
            assertEquals("127.0.0.1", connected.get("ipAddress").asText());
        } finally {
            disconnect(client);
        }
    }

    @Test
    void aCleanDisconnectEndsTheSameSessionAndAReconnectCountsUpByTwo() throws Exception {
        startBrokerRecordingPublishes();
        disconnect(connectPlain("presence-client"));

        JsonNode connected = awaitPresence("connected", "presence-client", 1).get(0);
        JsonNode disconnected = awaitPresence("disconnected", "presence-client", 1).get(0);
        assertEquals(List.of("clientId", "timestamp", "eventType", "sessionIdentifier", "versionNumber",
                "clientInitiatedDisconnect", "disconnectReason"), fieldNames(disconnected), "no ipAddress on disconnected");
        assertEquals("presence-client", disconnected.get("clientId").asText());
        assertTrue(disconnected.get("timestamp").isIntegralNumber(), disconnected.toString());
        assertEquals("disconnected", disconnected.get("eventType").asText());
        assertEquals(connected.get("sessionIdentifier"), disconnected.get("sessionIdentifier"));
        assertEquals(0, disconnected.get("versionNumber").asLong());
        assertTrue(disconnected.get("clientInitiatedDisconnect").isBoolean(), disconnected.toString());
        assertTrue(disconnected.get("clientInitiatedDisconnect").asBoolean());
        assertEquals("CLIENT_INITIATED_DISCONNECT", disconnected.get("disconnectReason").asText());

        disconnect(connectPlain("presence-client"));

        JsonNode reconnected = awaitPresence("connected", "presence-client", 2).get(1);
        assertEquals(2, reconnected.get("versionNumber").asLong(), "a reconnect after a session ended adds 2");
        assertNotEquals(connected.get("sessionIdentifier"), reconnected.get("sessionIdentifier"));
        JsonNode redisconnected = awaitPresence("disconnected", "presence-client", 2).get(1);
        assertEquals(reconnected.get("sessionIdentifier"), redisconnected.get("sessionIdentifier"));
        assertEquals(2, redisconnected.get("versionNumber").asLong());
    }

    @Test
    void aConnectionClosedWithoutDisconnectIsReportedAsConnectionLost() throws Exception {
        startBrokerRecordingPublishes();
        try (Socket socket = rawConnect("abrupt")) {
            awaitPresence("connected", "abrupt", 1);
        }

        JsonNode disconnected = awaitPresence("disconnected", "abrupt", 1).get(0);
        assertEquals("CONNECTION_LOST", disconnected.get("disconnectReason").asText());
        assertTrue(disconnected.get("clientInitiatedDisconnect").isBoolean(), disconnected.toString());
        assertFalse(disconnected.get("clientInitiatedDisconnect").asBoolean());
        assertEquals(0, disconnected.get("versionNumber").asLong());
    }

    @Test
    void aSecondConnectWithTheSameClientIdEndsTheFirstSessionAsADuplicateBeforeTheNewOneConnects() throws Exception {
        startBrokerRecordingPublishes();
        MqttClient first = connectPlain("shared");
        MqttClient second = null;
        try {
            awaitPresence("connected", "shared", 1);
            second = connectPlain("shared");
            awaitPresence("connected", "shared", 2);
            awaitDisconnected(first);
            barrier();

            List<JsonNode> events = presenceOf("shared");
            assertEquals(List.of("connected", "disconnected", "connected"), eventTypes(events),
                    "the replaced session's own close publishes nothing more: " + events);
            JsonNode replaced = events.get(1);
            assertEquals("DUPLICATE_CLIENTID", replaced.get("disconnectReason").asText());
            assertFalse(replaced.get("clientInitiatedDisconnect").asBoolean());
            assertEquals(events.get(0).get("sessionIdentifier"), replaced.get("sessionIdentifier"));
            assertEquals(0, replaced.get("versionNumber").asLong());
            assertEquals(1, events.get(2).get("versionNumber").asLong(), "a takeover adds 1");
            assertNotEquals(events.get(0).get("sessionIdentifier"), events.get(2).get("sessionIdentifier"));
        } finally {
            disconnect(first);
            if (second != null) {
                disconnect(second);
            }
        }
    }

    @Test
    void deleteConnectionReportsOneApiInitiatedDisconnect() throws Exception {
        startBrokerRecordingPublishes();
        MqttClient client = connectPlain("deleted");
        try {
            awaitPresence("connected", "deleted", 1);

            assertTrue(broker.disconnectClient("deleted", false));
            awaitPresence("disconnected", "deleted", 1);
            awaitDisconnected(client);
            barrier();

            List<JsonNode> disconnected = presence("disconnected", "deleted");
            assertEquals(1, disconnected.size(), disconnected.toString());
            assertEquals("API_INITIATED_DISCONNECT", disconnected.get(0).get("disconnectReason").asText());
            assertFalse(disconnected.get(0).get("clientInitiatedDisconnect").asBoolean());
        } finally {
            disconnect(client);
        }
    }

    /**
     * A DeleteConnection that finds the session after another ending won it, here a broker stop
     * still closing the endpoint, reports the client as not connected instead of closing the
     * endpoint again.
     */
    @Test
    void deleteConnectionLosingTheSessionToAnotherEndingReportsNotConnected() throws Exception {
        startBrokerRecordingPublishes();
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        MqttEndpoint endpoint = mock(MqttEndpoint.class);
        when(endpoint.clientIdentifier()).thenReturn("racing");
        doAnswer(invocation -> {
            if (!closed.compareAndSet(false, true)) {
                throw new IllegalStateException("MQTT endpoint is closed");
            }
            closing.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(endpoint).close();
        broker.handleEndpoint(endpoint, false);
        ExecutorService stopping = Executors.newSingleThreadExecutor();
        try {
            Future<?> stop = stopping.submit(broker::stop);
            assertTrue(closing.await(10, TimeUnit.SECONDS), "the stop is closing the endpoint");

            assertFalse(broker.disconnectClient("racing", false));

            release.countDown();
            stop.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            stopping.shutdown();
        }
    }

    /**
     * A DeleteConnection that wins the session can still find the endpoint closed: the client's
     * DISCONNECT, arriving in between, loses the session, yet Vert.x closes the endpoint after it.
     */
    @Test
    void deleteConnectionOfAnEndpointAlreadyClosedByTheClientStillSucceeds() throws Exception {
        startBrokerRecordingPublishes();
        MqttEndpoint endpoint = mock(MqttEndpoint.class);
        when(endpoint.clientIdentifier()).thenReturn("closed-by-client");
        doThrow(new IllegalStateException("MQTT endpoint is closed")).when(endpoint).close();
        broker.handleEndpoint(endpoint, false);

        assertTrue(broker.disconnectClient("closed-by-client", false));

        List<JsonNode> disconnected = awaitPresence("disconnected", "closed-by-client", 1);
        assertEquals(1, disconnected.size(), disconnected.toString());
        assertEquals("API_INITIATED_DISCONNECT", disconnected.get(0).get("disconnectReason").asText());
    }

    /** A takeover can find the previous endpoint already closed by its own client, and still admits the new one. */
    @Test
    void aTakeoverOfAnEndpointAlreadyClosedStillAcceptsTheNewConnection() throws Exception {
        startBrokerRecordingPublishes();
        MqttEndpoint previous = mock(MqttEndpoint.class);
        when(previous.clientIdentifier()).thenReturn("closed-takeover");
        doThrow(new IllegalStateException("MQTT endpoint is closed")).when(previous).close();
        broker.handleEndpoint(previous, false);
        MqttEndpoint replacement = mock(MqttEndpoint.class);
        when(replacement.clientIdentifier()).thenReturn("closed-takeover");

        assertDoesNotThrow(() -> broker.handleEndpoint(replacement, false));

        verify(replacement).accept();
        awaitPresence("connected", "closed-takeover", 2);
        List<JsonNode> events = presenceOf("closed-takeover");
        assertEquals(List.of("connected", "disconnected", "connected"), eventTypes(events), events.toString());
        assertEquals("DUPLICATE_CLIENTID", events.get(1).get("disconnectReason").asText());
    }

    /**
     * Two CONNECTs for one client id admitted at once on two event loops: the first is held after it
     * took its versionNumber while the second completes. The session left current is the one with
     * the highest versionNumber, so DeleteConnection ends that one. The first finds its endpoint
     * closed by the takeover, as Vert.x refuses to configure a closed endpoint, and its connected
     * and DUPLICATE_CLIENTID disconnected events still pair up.
     */
    @Test
    void overlappingConnectsForOneClientIdLeaveTheNewestVersionCurrent() throws Exception {
        startBrokerRecordingPublishes();
        CountDownLatch admitting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstClosed = new AtomicBoolean();
        MqttEndpoint first = mock(MqttEndpoint.class);
        when(first.clientIdentifier()).thenReturn("overlap");
        doAnswer(invocation -> {
            firstClosed.set(true);
            return null;
        }).when(first).close();
        doAnswer(invocation -> {
            admitting.countDown();
            release.await(10, TimeUnit.SECONDS);
            if (firstClosed.get()) {
                throw new IllegalStateException("MQTT endpoint is closed");
            }
            return null;
        }).when(first).subscriptionAutoAck(false);
        MqttEndpoint second = mock(MqttEndpoint.class);
        when(second.clientIdentifier()).thenReturn("overlap");
        ExecutorService firstEventLoop = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstAdmit = firstEventLoop.submit(() -> broker.handleEndpoint(first, false));
            assertTrue(admitting.await(10, TimeUnit.SECONDS), "the first CONNECT is being admitted");
            broker.handleEndpoint(second, false);
            release.countDown();
            firstAdmit.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            firstEventLoop.shutdown();
        }

        List<JsonNode> connected = awaitPresence("connected", "overlap", 2);
        assertTrue(broker.disconnectClient("overlap", false));

        long newest = Math.max(connected.get(0).get("versionNumber").asLong(), connected.get(1).get("versionNumber").asLong());
        assertNotEquals(connected.get(0).get("versionNumber"), connected.get(1).get("versionNumber"), connected.toString());
        JsonNode deleted = awaitPresence("disconnected", "overlap", 2).stream()
                .filter(event -> "API_INITIATED_DISCONNECT".equals(event.get("disconnectReason").asText()))
                .findFirst()
                .orElseThrow();
        assertEquals(newest, deleted.get("versionNumber").asLong(), "the session left current: " + presenceOf("overlap"));
        long overlapped = newest == connected.get(0).get("versionNumber").asLong()
                ? connected.get(1).get("versionNumber").asLong()
                : connected.get(0).get("versionNumber").asLong();
        assertEquals(List.of("connected", "disconnected"), eventTypes(presenceOf("overlap").stream()
                .filter(event -> event.get("versionNumber").asLong() == overlapped)
                .toList()).stream().sorted().toList(), presenceOf("overlap").toString());
    }

    /**
     * A DeleteConnection with cleanSession racing a reconnect of the client id it ends: the reconnect
     * waits until DeleteConnection is done with the old session, so the subscriptions the new
     * session makes stay. The DeleteConnection is held where it schedules the disconnected event.
     */
    @Test
    void deleteConnectionLeavesTheSubscriptionsOfAReconnectRacingIt() throws Exception {
        IotService service = mock(IotService.class);
        when(iotService.get()).thenReturn(service);
        when(service.listRetainedMessages(null, null)).thenReturn(new IotService.Page<>(List.of(), null));
        CountDownLatch ending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> deletingThread = new AtomicReference<>();
        Vertx heldVertx = mock(Vertx.class, AdditionalAnswers.delegatesTo(vertx));
        doAnswer(invocation -> {
            if (Thread.currentThread() == deletingThread.get()) {
                ending.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            vertx.runOnContext(invocation.getArgument(0));
            return null;
        }).when(heldVertx).runOnContext(any());
        IotMqttBrokerService racing = new IotMqttBrokerService(config, heldVertx, iotService, customAuthorizer, registry);
        MqttEndpoint old = mock(MqttEndpoint.class);
        when(old.clientIdentifier()).thenReturn("reconnecting");
        racing.handleEndpoint(old, false);
        MqttEndpoint reconnect = mock(MqttEndpoint.class);
        when(reconnect.clientIdentifier()).thenReturn("reconnecting");
        AtomicReference<Handler<MqttSubscribeMessage>> subscribeHandler = new AtomicReference<>();
        when(reconnect.subscribeHandler(any())).thenAnswer(invocation -> {
            subscribeHandler.set(invocation.getArgument(0));
            return reconnect;
        });
        MqttTopicSubscription requested = mock(MqttTopicSubscription.class);
        when(requested.topicName()).thenReturn("devices/reconnecting/commands");
        when(requested.qualityOfService()).thenReturn(MqttQoS.AT_MOST_ONCE);
        MqttSubscribeMessage subscribe = mock(MqttSubscribeMessage.class);
        when(subscribe.topicSubscriptions()).thenReturn(List.of(requested));
        AtomicReference<Thread> reconnectingThread = new AtomicReference<>();
        ExecutorService deleting = Executors.newSingleThreadExecutor();
        ExecutorService reconnecting = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> delete = deleting.submit(() -> {
                deletingThread.set(Thread.currentThread());
                return racing.disconnectClient("reconnecting", true);
            });
            assertTrue(ending.await(10, TimeUnit.SECONDS), "DeleteConnection is ending the old session");
            Future<?> reconnected = reconnecting.submit(() -> {
                reconnectingThread.set(Thread.currentThread());
                racing.handleEndpoint(reconnect, false);
                subscribeHandler.get().handle(subscribe);
            });
            ThreadStates.awaitBlockedOrDone(reconnectingThread, reconnected);
            release.countDown();

            assertTrue(delete.get(10, TimeUnit.SECONDS));
            reconnected.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            deleting.shutdown();
            reconnecting.shutdown();
        }
        assertEquals(List.of("devices/reconnecting/commands"), racing.listSubscriptions("reconnecting"));
    }

    @Test
    void aPayloadAboveTheAwsLimitEndsTheSessionWithAClientError() throws Exception {
        startBrokerRecordingPublishes();
        try (Socket socket = rawConnect("oversized")) {
            rawPublish(socket, 0, "devices/d1/big", new byte[128 * 1024 + 1]);

            awaitPresence("disconnected", "oversized", 1);
        }
        barrier();

        List<JsonNode> disconnected = presence("disconnected", "oversized");
        assertEquals(1, disconnected.size(), disconnected.toString());
        assertEquals("CLIENT_ERROR", disconnected.get(0).get("disconnectReason").asText());
        assertFalse(disconnected.get(0).get("clientInitiatedDisconnect").asBoolean());
    }

    @Test
    void aQos2PublishEndsTheSessionWithAClientError() throws Exception {
        startBrokerRecordingPublishes();
        try (Socket socket = rawConnect("qos2")) {
            rawPublish(socket, 2, "devices/d1/metrics", "{}".getBytes(StandardCharsets.UTF_8));

            awaitPresence("disconnected", "qos2", 1);
        }
        barrier();

        List<JsonNode> disconnected = presence("disconnected", "qos2");
        assertEquals(1, disconnected.size(), disconnected.toString());
        assertEquals("CLIENT_ERROR", disconnected.get(0).get("disconnectReason").asText());
    }

    /** The decoder rejects an oversized advertised length before reading the body or invoking the publish handler. */
    @Test
    void aPacketAboveTheDecoderLimitEndsTheSessionWithAClientError() throws Exception {
        startBrokerRecordingPublishes();
        try (Socket socket = rawConnect("undecodable")) {
            byte[] body = new byte[150 * 1024];
            byte[] frame = packet(0x30, body);
            socket.getOutputStream().write(frame, 0, frame.length - body.length);

            awaitPresence("disconnected", "undecodable", 1);
            assertEquals(-1, socket.getInputStream().read(), "the decoder closes without waiting for the body");
        }
        barrier();

        List<JsonNode> disconnected = presence("disconnected", "undecodable");
        assertEquals(1, disconnected.size(), disconnected.toString());
        assertEquals("CLIENT_ERROR", disconnected.get(0).get("disconnectReason").asText());
        assertFalse(disconnected.get(0).get("clientInitiatedDisconnect").asBoolean());
    }

    @Test
    void aClientIdHoldingAWildcardGetsNoLifecycleEvents() throws Exception {
        startBrokerRecordingPublishes();
        try (Socket plus = rawConnect("bad+id"); Socket hash = rawConnect("bad#id")) {
            assertTrue(broker.getConnection("bad+id").isPresent(), "the client is admitted");
            assertTrue(broker.getConnection("bad#id").isPresent(), "the client is admitted");
        }
        barrier();

        assertEquals(List.of(), recorder.recentEvents().stream()
                .map(IotPublishEvent::topic)
                .filter(topic -> topic.contains("bad"))
                .toList());
    }

    /**
     * As AWS IoT does for lifecycle topics: a filter whose first level is a wildcard does not
     * match them. A later ordinary publish both wildcard subscribers match shows they got nothing
     * before it.
     */
    @Test
    void onlyASubscriptionWithALiteralFirstLevelReceivesLifecycleEvents() throws Exception {
        startBrokerRecordingPublishes();
        BlockingQueue<String> catchAll = new LinkedBlockingQueue<>();
        BlockingQueue<String> wildcardFirst = new LinkedBlockingQueue<>();
        BlockingQueue<String> literal = new LinkedBlockingQueue<>();
        MqttClient catchAllSubscriber = subscribedPlain("catch-all", "#", catchAll);
        MqttClient wildcardSubscriber = subscribedPlain("wildcard-first", "+/events/presence/+/+", wildcardFirst);
        MqttClient literalSubscriber = subscribedPlain("literal", "$aws/events/presence/connected/+", literal);
        try {
            MqttClient watched = connectPlain("watched");
            try {
                assertEquals("$aws/events/presence/connected/watched", literal.poll(10, TimeUnit.SECONDS));
                watched.publish("barrier/events/presence/connected/watched", new byte[0], 1, false);
            } finally {
                disconnect(watched);
            }

            assertEquals("barrier/events/presence/connected/watched", catchAll.poll(10, TimeUnit.SECONDS),
                    "no lifecycle event reached the # subscriber");
            assertEquals("barrier/events/presence/connected/watched", wildcardFirst.poll(10, TimeUnit.SECONDS),
                    "no lifecycle event reached the +/events/presence/+/+ subscriber");
        } finally {
            disconnect(catchAllSubscriber);
            disconnect(wildcardSubscriber);
            disconnect(literalSubscriber);
        }
    }

    /** As measured on AWS IoT, a {@code #} subscriber receives the shadow response topics, unlike the lifecycle ones. */
    @Test
    void aSubscriptionOnHashReceivesShadowResponseTopics() throws Exception {
        startBrokerRecordingPublishes();
        BlockingQueue<String> topics = new LinkedBlockingQueue<>();
        MqttClient subscriber = subscribedPlain("shadow-watcher", "#", topics);
        try {
            broker.publish("$aws/things/thermostat/shadow/update/accepted", "{}".getBytes(StandardCharsets.UTF_8));

            assertEquals("$aws/things/thermostat/shadow/update/accepted", topics.poll(10, TimeUnit.SECONDS));
        } finally {
            disconnect(subscriber);
        }
    }

    @Test
    void aBrokerStopPublishesNoDisconnectedEvent() throws Exception {
        startBrokerRecordingPublishes();
        MqttClient client = connectPlain("stopped");
        try {
            awaitPresence("connected", "stopped", 1);

            broker.stop();
            awaitDisconnected(client);
            awaitClosed(plainPort);
            awaitClosed(tlsPort);
            broker.startIfEnabled();
            barrier();

            assertEquals(List.of(), presence("disconnected", "stopped"));
        } finally {
            disconnect(client);
        }
    }

    /**
     * Vert.x runs an endpoint's disconnect callback holding that endpoint's monitor, and a
     * subscriber's isConnected and publish take the subscriber's own. With the subscriber's monitor
     * held, as by its event loop fanning out to the departing client, the callback still returns:
     * the disconnected event reaches the subscriber only afterwards, so the two cannot deadlock.
     */
    @Test
    void aDisconnectCallbackReturnsWhileASubscribersMonitorIsHeld() throws Exception {
        startBrokerRecordingPublishes();
        ReentrantLock subscriberMonitor = new ReentrantLock();
        MqttEndpoint subscriber = mock(MqttEndpoint.class);
        when(subscriber.clientIdentifier()).thenReturn("busy-subscriber");
        when(subscriber.isConnected()).thenAnswer(invocation -> {
            subscriberMonitor.lock();
            try {
                return true;
            } finally {
                subscriberMonitor.unlock();
            }
        });
        AtomicReference<Handler<MqttSubscribeMessage>> subscribeHandler = new AtomicReference<>();
        when(subscriber.subscribeHandler(any())).thenAnswer(invocation -> {
            subscribeHandler.set(invocation.getArgument(0));
            return subscriber;
        });
        MqttTopicSubscription requested = mock(MqttTopicSubscription.class);
        when(requested.topicName()).thenReturn("$aws/events/presence/+/departing");
        when(requested.qualityOfService()).thenReturn(MqttQoS.AT_MOST_ONCE);
        MqttSubscribeMessage subscribe = mock(MqttSubscribeMessage.class);
        when(subscribe.topicSubscriptions()).thenReturn(List.of(requested));
        broker.handleEndpoint(subscriber, false);
        subscribeHandler.get().handle(subscribe);
        MqttEndpoint departing = mock(MqttEndpoint.class);
        when(departing.clientIdentifier()).thenReturn("departing");
        AtomicReference<Handler<Void>> disconnectHandler = new AtomicReference<>();
        when(departing.disconnectHandler(any())).thenAnswer(invocation -> {
            disconnectHandler.set(invocation.getArgument(0));
            return departing;
        });
        broker.handleEndpoint(departing, false);
        verify(subscriber, timeout(10_000))
                .publish(eq("$aws/events/presence/connected/departing"), any(), any(), anyBoolean(), anyBoolean());

        ExecutorService departingEventLoop = Executors.newSingleThreadExecutor();
        subscriberMonitor.lock();
        try {
            Future<?> callback = departingEventLoop.submit(() -> disconnectHandler.get().handle(null));

            assertDoesNotThrow(() -> callback.get(5, TimeUnit.SECONDS),
                    "the disconnect callback returned while the subscriber's monitor is held");
        } finally {
            subscriberMonitor.unlock();
            departingEventLoop.shutdown();
        }
        verify(subscriber, timeout(10_000))
                .publish(eq("$aws/events/presence/disconnected/departing"), any(), any(), anyBoolean(), anyBoolean());
    }

    /** A PUBLISH, SUBSCRIBE, UNSUBSCRIBE or PUBACK counts as traffic for the keep-alive, as a PINGREQ does. */
    @Test
    void anAbruptCloseAfterAPublishSubscribeUnsubscribeOrPubackIsAConnectionLossNotAKeepAliveTimeout() throws Exception {
        startBrokerRecordingPublishes();
        MqttEndpoint publishing = keepAliveEndpoint("publishing");
        MqttEndpoint subscribing = keepAliveEndpoint("subscribing");
        MqttEndpoint unsubscribing = keepAliveEndpoint("unsubscribing");
        MqttEndpoint acknowledging = keepAliveEndpoint("acknowledging");
        nanoTime.addAndGet(Duration.ofSeconds(2).toNanos());

        ArgumentCaptor<Handler<MqttPublishMessage>> publish = handlerCaptor();
        verify(publishing).publishHandler(publish.capture());
        MqttPublishMessage message = mock(MqttPublishMessage.class);
        when(message.payload()).thenReturn(Buffer.buffer("{}"));
        when(message.qosLevel()).thenReturn(MqttQoS.AT_MOST_ONCE);
        when(message.topicName()).thenReturn("keepalive/publishing");
        publish.getValue().handle(message);
        ArgumentCaptor<Handler<MqttSubscribeMessage>> subscribe = handlerCaptor();
        verify(subscribing).subscribeHandler(subscribe.capture());
        MqttTopicSubscription requested = mock(MqttTopicSubscription.class);
        when(requested.topicName()).thenReturn("keepalive/subscribing");
        when(requested.qualityOfService()).thenReturn(MqttQoS.AT_MOST_ONCE);
        MqttSubscribeMessage subscription = mock(MqttSubscribeMessage.class);
        when(subscription.topicSubscriptions()).thenReturn(List.of(requested));
        subscribe.getValue().handle(subscription);
        ArgumentCaptor<Handler<MqttUnsubscribeMessage>> unsubscribe = handlerCaptor();
        verify(unsubscribing).unsubscribeHandler(unsubscribe.capture());
        MqttUnsubscribeMessage unsubscription = mock(MqttUnsubscribeMessage.class);
        when(unsubscription.topics()).thenReturn(List.of("keepalive/unsubscribing"));
        unsubscribe.getValue().handle(unsubscription);
        ArgumentCaptor<Handler<Integer>> puback = handlerCaptor();
        verify(acknowledging).publishAcknowledgeHandler(puback.capture());
        puback.getValue().handle(1);
        for (MqttEndpoint endpoint : List.of(publishing, subscribing, unsubscribing, acknowledging)) {
            ArgumentCaptor<Handler<Void>> close = handlerCaptor();
            verify(endpoint).closeHandler(close.capture());
            close.getValue().handle(null);
        }

        for (String clientId : List.of("publishing", "subscribing", "unsubscribing", "acknowledging")) {
            assertEquals("CONNECTION_LOST",
                    awaitPresence("disconnected", clientId, 1).get(0).get("disconnectReason").asText(), clientId);
        }
    }

    /** Vert.x times a client out after 1.5 times its keep-alive, so a socket lost before then is a connection loss. */
    @Test
    void anAbruptCloseBeforeTheKeepAliveTimeoutIsDueIsAConnectionLoss() throws Exception {
        startBrokerRecordingPublishes();
        MqttEndpoint dropped = keepAliveEndpoint("dropped");
        nanoTime.addAndGet(Duration.ofMillis(1_500).toNanos());

        ArgumentCaptor<Handler<Void>> close = handlerCaptor();
        verify(dropped).closeHandler(close.capture());
        close.getValue().handle(null);

        assertEquals("CONNECTION_LOST", awaitPresence("disconnected", "dropped", 1).get(0).get("disconnectReason").asText());
    }

    /**
     * Vert.x times the keep-alive from the CONNECT, so a client its custom authorizer took a while
     * to admit, silent since, has timed out when Vert.x closes it.
     */
    @Test
    void aSilentClientTimesOutFromItsConnectNotFromItsCustomAuthorizersDecision() throws Exception {
        startBrokerRecordingPublishes();
        when(customAuthorizer.connectPolicies(eq("slow-auth"), eq("slow"), any(), any(), any())).thenAnswer(invocation -> {
            nanoTime.addAndGet(Duration.ofMillis(1_500).toNanos());
            return List.of("""
                    {"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Action": "iot:Connect", "Resource": "*"}]}""");
        });
        SocketAddress bridge = SocketAddress.inetSocketAddress(50125, "127.0.0.1");
        broker.webSocketUpgrades().put(bridge, new IotCustomAuthorizer.WebSocketUpgrade(
                Map.of(), IotCustomAuthorizer.NAME_PARAM + "=slow-auth", null, "203.0.113.7", 41235));
        MqttEndpoint endpoint = mock(MqttEndpoint.class);
        when(endpoint.clientIdentifier()).thenReturn("slow");
        when(endpoint.remoteAddress()).thenReturn(bridge);
        when(endpoint.keepAliveTimeSeconds()).thenReturn(1);

        broker.handleEndpoint(endpoint, false);
        awaitPresence("connected", "slow", 1);
        nanoTime.addAndGet(Duration.ofMillis(600).toNanos());
        ArgumentCaptor<Handler<Void>> close = handlerCaptor();
        verify(endpoint, times(2)).closeHandler(close.capture());
        close.getValue().handle(null);

        assertEquals("MQTT_KEEP_ALIVE_TIMEOUT", awaitPresence("disconnected", "slow", 1).get(0).get("disconnectReason").asText());
    }

    /**
     * A SUBSCRIBE or UNSUBSCRIBE the replaced connection still had in flight when a newer one took
     * its client id over changes nothing: the newer session neither inherits the one nor loses a
     * subscription to the other.
     */
    @Test
    void aTakenOverSessionNeitherAddsNorRemovesSubscriptions() throws Exception {
        startBrokerRecordingPublishes();
        MqttEndpoint replaced = mock(MqttEndpoint.class);
        when(replaced.clientIdentifier()).thenReturn("taken");
        when(replaced.isCleanSession()).thenReturn(true);
        broker.handleEndpoint(replaced, false);
        ArgumentCaptor<Handler<MqttSubscribeMessage>> lateSubscribe = handlerCaptor();
        verify(replaced).subscribeHandler(lateSubscribe.capture());
        ArgumentCaptor<Handler<MqttUnsubscribeMessage>> lateUnsubscribe = handlerCaptor();
        verify(replaced).unsubscribeHandler(lateUnsubscribe.capture());
        MqttEndpoint current = mock(MqttEndpoint.class);
        when(current.clientIdentifier()).thenReturn("taken");
        when(current.isCleanSession()).thenReturn(true);
        when(current.isConnected()).thenReturn(true);
        broker.handleEndpoint(current, false);
        ArgumentCaptor<Handler<MqttSubscribeMessage>> subscribe = handlerCaptor();
        verify(current).subscribeHandler(subscribe.capture());
        subscribe.getValue().handle(subscribeMessage("kept/topic"));

        lateSubscribe.getValue().handle(subscribeMessage("stale/topic"));
        MqttUnsubscribeMessage unsubscription = mock(MqttUnsubscribeMessage.class);
        when(unsubscription.topics()).thenReturn(List.of("kept/topic"));
        lateUnsubscribe.getValue().handle(unsubscription);
        broker.publish("stale/topic", "{}".getBytes(StandardCharsets.UTF_8));
        broker.publish("kept/topic", "{}".getBytes(StandardCharsets.UTF_8));

        verify(current, timeout(10_000)).publish(eq("kept/topic"), any(), any(), anyBoolean(), anyBoolean());
        verify(current, never()).publish(eq("stale/topic"), any(), any(), anyBoolean(), anyBoolean());
    }

    /**
     * Fleet indexing connectivity is per account and region: a plaintext session counts in the
     * default account, so switching that default stands in for a second account. Account A's
     * recorded disconnect survives a session of the same client id in account B, while B's session
     * is live and after it ends.
     */
    @Test
    void connectivityHistoryOfAClientIdIsKeptPerAccountAndRegion() {
        when(config.defaultRegion()).thenReturn("us-east-1");
        when(config.defaultAccountId()).thenReturn("111111111111");
        AtomicReference<Handler<Void>> disconnectInA = new AtomicReference<>();
        broker.handleEndpoint(endpointCapturing("device-1", disconnectInA, new AtomicReference<>()), false);
        disconnectInA.get().handle(null);

        when(config.defaultAccountId()).thenReturn("222222222222");
        AtomicReference<Handler<Void>> closeInB = new AtomicReference<>();
        broker.handleEndpoint(endpointCapturing("device-1", new AtomicReference<>(), closeInB), false);

        assertEquals("CLIENT_INITIATED_DISCONNECT",
                broker.connectivity("111111111111", "us-east-1", "device-1").orElseThrow().disconnectReason());
        assertTrue(broker.connectivity("222222222222", "us-east-1", "device-1").orElseThrow().connected());
        closeInB.get().handle(null);
        assertEquals("CLIENT_INITIATED_DISCONNECT",
                broker.connectivity("111111111111", "us-east-1", "device-1").orElseThrow().disconnectReason());
        assertEquals("CONNECTION_LOST",
                broker.connectivity("222222222222", "us-east-1", "device-1").orElseThrow().disconnectReason());
        assertTrue(broker.connectivity("111111111111", "eu-west-1", "device-1").isEmpty());
    }

    /**
     * A client id names one broker session across accounts and regions: a connection in account B
     * displaces account A's live session of the same client id, which then reports
     * DUPLICATE_CLIENTID, also after the displaced connection's close arrives.
     */
    @Test
    void aSessionDisplacedByTheSameClientIdInAnotherAccountReportsDuplicateClientId() {
        when(config.defaultRegion()).thenReturn("us-east-1");
        when(config.defaultAccountId()).thenReturn("111111111111");
        AtomicReference<Handler<Void>> closeInA = new AtomicReference<>();
        MqttEndpoint inA = endpointCapturing("device-1", new AtomicReference<>(), closeInA);
        broker.handleEndpoint(inA, false);

        when(config.defaultAccountId()).thenReturn("222222222222");
        broker.handleEndpoint(endpointCapturing("device-1", new AtomicReference<>(), new AtomicReference<>()), false);

        verify(inA).close();
        assertFalse(broker.connectivity("111111111111", "us-east-1", "device-1").orElseThrow().connected());
        assertEquals("DUPLICATE_CLIENTID",
                broker.connectivity("111111111111", "us-east-1", "device-1").orElseThrow().disconnectReason());
        closeInA.get().handle(null);
        assertEquals("DUPLICATE_CLIENTID",
                broker.connectivity("111111111111", "us-east-1", "device-1").orElseThrow().disconnectReason());
        assertTrue(broker.connectivity("222222222222", "us-east-1", "device-1").orElseThrow().connected());
    }

    /**
     * DeleteConnection ends the session with API_INITIATED_DISCONNECT, the reason AWS gives a
     * disconnect through that API, also after the close it causes reaches the broker.
     */
    @Test
    void deleteConnectionEndsTheSessionWithApiInitiatedDisconnect() {
        when(config.defaultRegion()).thenReturn("us-east-1");
        when(config.defaultAccountId()).thenReturn("111111111111");
        AtomicReference<Handler<Void>> close = new AtomicReference<>();
        MqttEndpoint endpoint = endpointCapturing("device-1", new AtomicReference<>(), close);
        broker.handleEndpoint(endpoint, false);

        assertTrue(broker.disconnectClient("device-1", false));

        verify(endpoint).close();
        IotMqttBrokerService.Connectivity ended =
                broker.connectivity("111111111111", "us-east-1", "device-1").orElseThrow();
        assertFalse(ended.connected());
        assertEquals("API_INITIATED_DISCONNECT", ended.disconnectReason());
        close.get().handle(null);
        assertEquals("API_INITIATED_DISCONNECT",
                broker.connectivity("111111111111", "us-east-1", "device-1").orElseThrow().disconnectReason());
        assertFalse(broker.disconnectClient("device-1", false));
    }

    /**
     * A state reset forgets how sessions ended but keeps a live session connected, and that
     * session's end after the reset is recorded again.
     */
    @Test
    void aResetForgetsEndedSessionsButNotLiveOnes() {
        when(config.defaultRegion()).thenReturn("us-east-1");
        when(config.defaultAccountId()).thenReturn("111111111111");
        AtomicReference<Handler<Void>> disconnectGone = new AtomicReference<>();
        broker.handleEndpoint(endpointCapturing("gone", disconnectGone, new AtomicReference<>()), false);
        disconnectGone.get().handle(null);
        AtomicReference<Handler<Void>> disconnectLive = new AtomicReference<>();
        broker.handleEndpoint(endpointCapturing("live", disconnectLive, new AtomicReference<>()), false);

        broker.clear();

        assertTrue(broker.connectivity("111111111111", "us-east-1", "gone").isEmpty());
        assertTrue(broker.connectivity("111111111111", "us-east-1", "live").orElseThrow().connected());
        disconnectLive.get().handle(null);
        assertEquals("CLIENT_INITIATED_DISCONNECT",
                broker.connectivity("111111111111", "us-east-1", "live").orElseThrow().disconnectReason());
    }

    /**
     * A fleet index lookup and a state reset each wait for a session end in flight, held where it
     * schedules the disconnected event: the lookup then reports API_INITIATED_DISCONNECT rather
     * than a session that is still connected, and the reset forgets that end rather than having
     * it written back after the reset.
     */
    @Test
    void connectivityLookupsAndResetsWaitForASessionEndInFlight() throws Exception {
        when(config.defaultRegion()).thenReturn("us-east-1");
        when(config.defaultAccountId()).thenReturn("111111111111");
        when(iotService.get()).thenReturn(mock(IotService.class));

        assertEquals("API_INITIATED_DISCONNECT", duringHeldDeleteConnection(racing ->
                racing.connectivity("111111111111", "us-east-1", "held").orElseThrow().disconnectReason()));
        IotMqttBrokerService reset = duringHeldDeleteConnection(racing -> {
            racing.clear();
            return racing;
        });
        assertTrue(reset.connectivity("111111111111", "us-east-1", "held").isEmpty());
    }

    /**
     * Runs {@code during} on another thread while a DeleteConnection of client id {@code held} is
     * inside its session end, asserts it waits for that end, and returns its result.
     */
    private <T> T duringHeldDeleteConnection(Function<IotMqttBrokerService, T> during) throws Exception {
        CountDownLatch ending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> deletingThread = new AtomicReference<>();
        Vertx heldVertx = mock(Vertx.class, AdditionalAnswers.delegatesTo(vertx));
        doAnswer(invocation -> {
            if (Thread.currentThread() == deletingThread.get()) {
                ending.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            vertx.runOnContext(invocation.getArgument(0));
            return null;
        }).when(heldVertx).runOnContext(any());
        IotMqttBrokerService racing = new IotMqttBrokerService(config, heldVertx, iotService, customAuthorizer, registry);
        racing.handleEndpoint(endpointCapturing("held", new AtomicReference<>(), new AtomicReference<>()), false);
        AtomicReference<Thread> duringThread = new AtomicReference<>();
        ExecutorService deleting = Executors.newSingleThreadExecutor();
        ExecutorService concurrent = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> delete = deleting.submit(() -> {
                deletingThread.set(Thread.currentThread());
                return racing.disconnectClient("held", false);
            });
            assertTrue(ending.await(10, TimeUnit.SECONDS), "DeleteConnection is ending the session");
            Future<T> result = concurrent.submit(() -> {
                duringThread.set(Thread.currentThread());
                return during.apply(racing);
            });
            ThreadStates.awaitBlockedOrDone(duringThread, result);
            assertFalse(result.isDone(), "waits for the session end in flight");
            release.countDown();
            assertTrue(delete.get(10, TimeUnit.SECONDS));
            return result.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            deleting.shutdown();
            concurrent.shutdown();
        }
    }

    private static MqttEndpoint endpointCapturing(String clientId, AtomicReference<Handler<Void>> disconnect,
                                                  AtomicReference<Handler<Void>> close) {
        MqttEndpoint endpoint = mock(MqttEndpoint.class);
        when(endpoint.clientIdentifier()).thenReturn(clientId);
        when(endpoint.isConnected()).thenReturn(true);
        when(endpoint.disconnectHandler(any())).thenAnswer(invocation -> {
            disconnect.set(invocation.getArgument(0));
            return endpoint;
        });
        when(endpoint.closeHandler(any())).thenAnswer(invocation -> {
            close.set(invocation.getArgument(0));
            return endpoint;
        });
        return endpoint;
    }

    /**
     * Two publishes from two connections, the first one's rule action held on a latch: the second
     * publish is fanned out, so its rules were handed over, yet they start only once the first
     * publish's rules finished.
     */
    @Test
    void rulesOfALaterPublishWaitForTheEarlierPublishToFinish() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        DynamoDbItemAccess items = mock(DynamoDbItemAccess.class);
        doAnswer(invocation -> {
            String publish = invocation.<JsonNode>getArgument(2).path("t").path("N").asText();
            order.add(publish + "-start");
            if (publish.equals("1")) {
                firstStarted.countDown();
                releaseFirst.await(10, TimeUnit.SECONDS);
            } else {
                secondStarted.countDown();
            }
            order.add(publish + "-end");
            if (publish.equals("2")) {
                secondDone.countDown();
            }
            return null;
        }).when(items).putItem(any(), any(), any(), any(), any(), any());
        startBrokerWithRuleWriting(items);
        BlockingQueue<String> fannedOut = new LinkedBlockingQueue<>();
        MqttClient subscriber = connectPlain("subscriber");
        try {
            subscriber.subscribe("devices/+/metrics", 0,
                    (topic, message) -> fannedOut.add(new String(message.getPayload(), StandardCharsets.UTF_8)));
            MqttClient publisher = connectPlain("publisher");
            MqttClient publisher2 = connectPlain("publisher2");
            try {
                publisher.publish("devices/d1/metrics", "{\"t\":1}".getBytes(StandardCharsets.UTF_8), 1, false);
                publisher2.publish("devices/d1/metrics", "{\"t\":2}".getBytes(StandardCharsets.UTF_8), 1, false);

                assertTrue(firstStarted.await(10, TimeUnit.SECONDS), "the first publish's rules started");
                assertEquals("{\"t\":1}", fannedOut.poll(10, TimeUnit.SECONDS));
                assertEquals("{\"t\":2}", fannedOut.poll(10, TimeUnit.SECONDS), "the second publish was fanned out");
                assertFalse(secondStarted.await(300, TimeUnit.MILLISECONDS), "the second waits while the first is held");
                order.add("released");
            } finally {
                releaseFirst.countDown();
                disconnect(publisher);
                disconnect(publisher2);
            }
        } finally {
            disconnect(subscriber);
        }

        assertTrue(secondDone.await(10, TimeUnit.SECONDS), "the second publish's rules ran");
        assertEquals(List.of("1-start", "released", "1-end", "2-start", "2-end"), order);
    }

    /**
     * One evaluation held on a latch and the rest of the bound queued behind it: the rules of the
     * next publish are skipped, and once the backlog drains a later publish's rules run again.
     */
    @Test
    void rulesOfAPublishBeyondThePendingBoundAreSkippedUntilTheBacklogDrains() throws Exception {
        String topic = "devices/d1/metrics";
        int queued = IotMqttBrokerService.MAX_PENDING_RULE_EVALUATIONS - 1;
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger queuedRan = new AtomicInteger();
        AtomicBoolean beyondBoundRan = new AtomicBoolean();
        CountDownLatch afterDrainRan = new CountDownLatch(1);

        broker.evaluateRulesOnWorker(topic, () -> {
            firstStarted.countDown();
            try {
                releaseFirst.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while the first evaluation was held", e);
            }
        });
        try {
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS), "the first evaluation started");
            for (int i = 0; i < queued; i++) {
                broker.evaluateRulesOnWorker(topic, queuedRan::incrementAndGet);
            }
            broker.evaluateRulesOnWorker(topic, () -> beyondBoundRan.set(true));
        } finally {
            releaseFirst.countDown();
        }

        long deadline = System.currentTimeMillis() + 10_000;
        while (queuedRan.get() < queued) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("only " + queuedRan.get() + " of " + queued + " queued evaluations ran");
            }
            Thread.sleep(10);
        }
        assertFalse(beyondBoundRan.get(), "the evaluation beyond the bound was skipped");

        broker.evaluateRulesOnWorker(topic, afterDrainRan::countDown);
        assertTrue(afterDrainRan.await(10, TimeUnit.SECONDS), "capacity came back once the backlog drained");
        assertFalse(beyondBoundRan.get(), "the evaluation beyond the bound never ran");
    }

    @Test
    void drainedRulesReleaseCapacityWhileTheirCallerContextIsBlocked() throws Exception {
        String topic = "devices/d1/metrics";
        Context context = vertx.getOrCreateContext();
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch releaseCaller = new CountDownLatch(1);
        CountDownLatch callerReturned = new CountDownLatch(1);
        CountDownLatch rulesRan = new CountDownLatch(IotMqttBrokerService.MAX_PENDING_RULE_EVALUATIONS);
        CountDownLatch afterDrainRan = new CountDownLatch(1);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        boolean callerQueued = false;
        try {
            broker.ruleWorker.executeBlocking(() -> {
                workerStarted.countDown();
                releaseWorker.await();
                return null;
            }, false);
            context.runOnContext(ignored -> {
                try {
                    for (int i = 0; i < IotMqttBrokerService.MAX_PENDING_RULE_EVALUATIONS; i++) {
                        broker.evaluateRulesOnWorker(topic, rulesRan::countDown);
                    }
                    submitted.countDown();
                    releaseCaller.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    callerFailure.set(interrupted);
                } catch (RuntimeException failure) {
                    callerFailure.set(failure);
                } finally {
                    submitted.countDown();
                    callerReturned.countDown();
                }
            });
            callerQueued = true;
            assertTrue(workerStarted.await(10, TimeUnit.SECONDS), "the worker held the queued rules");
            assertTrue(submitted.await(10, TimeUnit.SECONDS), "the caller submitted the bounded backlog");
            assertNull(callerFailure.get());
            releaseWorker.countDown();
            assertTrue(rulesRan.await(10, TimeUnit.SECONDS), "the rules drained while their caller remained blocked");
            CountDownLatch workerDrained = new CountDownLatch(1);
            broker.ruleWorker.executeBlocking(() -> {
                workerDrained.countDown();
                return null;
            }, false);
            assertTrue(workerDrained.await(10, TimeUnit.SECONDS), "the worker passed the last rule's cleanup");
            broker.evaluateRulesOnWorker(topic, afterDrainRan::countDown);
            assertTrue(afterDrainRan.await(10, TimeUnit.SECONDS), "drained worker capacity is immediately reusable");
        } finally {
            releaseWorker.countDown();
            releaseCaller.countDown();
            if (callerQueued) {
                assertTrue(callerReturned.await(10, TimeUnit.SECONDS), "the caller context was released");
            }
        }
    }

    @Test
    void rejectedRuleDispatchDoesNotConsumePendingCapacity() {
        Vertx rejectedVertx = mock(Vertx.class);
        WorkerExecutor rejectedWorker = mock(WorkerExecutor.class);
        when(rejectedVertx.createSharedWorkerExecutor("floci-iot-mqtt-rules", 1)).thenReturn(rejectedWorker);
        when(rejectedWorker.executeBlocking(ArgumentMatchers.<Callable<Void>>any(), eq(false)))
                .thenThrow(new IllegalStateException("dispatch unavailable"));
        IotMqttBrokerService rejectedBroker = new IotMqttBrokerService(
                config, rejectedVertx, iotService, customAuthorizer, registry, nanoTime::get);
        for (int i = 0; i <= IotMqttBrokerService.MAX_PENDING_RULE_EVALUATIONS; i++) {
            assertThrows(IllegalStateException.class,
                    () -> rejectedBroker.evaluateRulesOnWorker("devices/d1/metrics", () -> {}));
        }
    }

    @Test
    void rulesAreSkippedFromAResetUntilItEnds() throws Exception {
        assertSkipsWaitingAndLaterRules(broker::beforeReset, broker::afterReset);
        assertTrue(rulesRun(), "rules run again once the reset ends");
    }

    @Test
    void rulesAreSkippedFromAStopUntilARestart() throws Exception {
        broker.startIfEnabled();
        assertSkipsWaitingAndLaterRules(broker::stop, broker::startIfEnabled);
        assertTrue(rulesRun(), "a publish after the restart runs its rules");
    }

    @Test
    void rulesAreSkippedFromTheShutdownObserverUntilARestart() throws Exception {
        broker.startIfEnabled();
        assertSkipsWaitingAndLaterRules(() -> broker.onShutdownStarted(null), () -> {
            broker.stop();
            broker.startIfEnabled();
        });
        assertTrue(rulesRun(), "a publish after the restart runs its rules");
    }

    @Test
    void aStartDuringAResetDoesNotResumeRules() throws Exception {
        broker.beforeReset();
        broker.startIfEnabled();
        assertFalse(rulesRun(), "a start during the reset leaves rules suspended");

        broker.afterReset();
        assertTrue(rulesRun(), "rules run again once the reset ends");
    }

    @Test
    void aResetEndingAfterShutdownDoesNotResumeRules() throws Exception {
        broker.startIfEnabled();
        broker.beforeReset();
        broker.stop();
        broker.afterReset();
        assertFalse(rulesRun(), "the end of the reset leaves the stopped broker's rules suspended");
    }

    /**
     * The reset controller calls afterReset() on every service, even one whose beforeReset() never
     * ran; such a lone afterReset() leaves the rule state alone, so an evaluation waiting then
     * still runs its rules.
     */
    @Test
    void anAfterResetWithoutABeforeResetKeepsWaitingRules() throws Exception {
        String topic = "devices/d1/metrics";
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean waitingRan = new AtomicBoolean();

        broker.evaluateRulesOnWorker(topic, () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while the first evaluation was held", e);
            }
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS), "the first evaluation started");
            broker.evaluateRulesOnWorker(topic, () -> waitingRan.set(true));
            broker.afterReset();
        } finally {
            release.countDown();
        }

        broker.ruleWorker.executeBlocking(() -> null, false).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(waitingRan.get(), "the evaluation waiting at the lone afterReset() still ran");
    }

    /**
     * A lifecycle event is published once the callback that ended its session returns. Here that
     * callback runs a whole reset first, so the event, queued behind it on the same context, is
     * still recorded but skips the rules it was admitted to before the reset.
     */
    @Test
    void aLifecycleEventQueuedBeforeAResetSkipsItsRules() throws Exception {
        String topic = "$aws/events/presence/disconnected/reset-race";
        IotService service = mock(IotService.class);
        when(iotService.get()).thenReturn(service);
        AtomicBoolean rulesRan = new AtomicBoolean();
        doAnswer(invocation -> {
            invocation.<Executor>getArgument(6).execute(() -> rulesRan.set(true));
            return null;
        }).when(service).publish(eq(topic), any(), anyBoolean(), anyInt(), any(), any(), any());
        MqttEndpoint endpoint = mock(MqttEndpoint.class);
        when(endpoint.clientIdentifier()).thenReturn("reset-race");
        broker.handleEndpoint(endpoint, false);
        Context context = vertx.getOrCreateContext();
        AtomicBoolean disconnected = new AtomicBoolean();
        CountDownLatch eventHandled = new CountDownLatch(1);

        context.runOnContext(ignored -> {
            disconnected.set(broker.disconnectClient("reset-race", false));
            broker.beforeReset();
            broker.afterReset();
            context.runOnContext(queued -> eventHandled.countDown());
        });

        assertTrue(eventHandled.await(10, TimeUnit.SECONDS), "the queued lifecycle event was handled");
        broker.ruleWorker.executeBlocking(() -> null, false).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(disconnected.get(), "DeleteConnection ended the session");
        verify(service).publish(eq(topic), any(), anyBoolean(), anyInt(), any(), any(), any());
        assertFalse(rulesRan.get(), "the event's rules, admitted before the reset, were skipped");
    }

    /**
     * A restart whose TLS listener cannot bind fails and leaves rules off; a later successful start
     * turns them on again.
     */
    @Test
    void aFailedRestartKeepsRulesOff() throws Exception {
        broker.startIfEnabled();
        broker.stop();
        awaitClosed(plainPort);
        awaitClosed(tlsPort);
        try (ServerSocket blocker = new ServerSocket()) {
            blocker.bind(new InetSocketAddress("127.0.0.1", tlsPort));

            assertThrows(IllegalStateException.class, broker::startIfEnabled);
            assertFalse(rulesRun(), "a failed restart leaves rules off");
            awaitClosed(plainPort);
        }

        broker.startIfEnabled();
        assertTrue(rulesRun(), "a later successful start turns rules on again");
    }

    /**
     * One evaluation held on a latch, one queued before {@code suspend} runs and one submitted after
     * it, which is refused at admission, then {@code resume} ends the suspension while the queued
     * one still waits: the held evaluation finishes, the queued one skips its rules and the refused
     * one never runs. The barrier is a task on the broker's single-thread rule worker, which runs
     * tasks in submission order, so it completes only after the evaluations before it.
     */
    private void assertSkipsWaitingAndLaterRules(Runnable suspend, Runnable resume) throws Exception {
        String topic = "devices/d1/metrics";
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean firstFinished = new AtomicBoolean();
        AtomicBoolean waitingRan = new AtomicBoolean();
        AtomicBoolean laterRan = new AtomicBoolean();

        broker.evaluateRulesOnWorker(topic, () -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while the first evaluation was held", e);
            }
            firstFinished.set(true);
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS), "the first evaluation started");
            broker.evaluateRulesOnWorker(topic, () -> waitingRan.set(true));
            suspend.run();
            broker.evaluateRulesOnWorker(topic, () -> laterRan.set(true));
            resume.run();
        } finally {
            release.countDown();
        }

        broker.ruleWorker.executeBlocking(() -> null, false).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(firstFinished.get(), "the running evaluation finished");
        assertFalse(waitingRan.get(), "the waiting evaluation was skipped");
        assertFalse(laterRan.get(), "the evaluation submitted during the suspension was refused");
    }

    /** Submits one evaluation and reports whether its rules ran, once the broker's rule worker processed it. */
    private boolean rulesRun() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        broker.evaluateRulesOnWorker("devices/d1/metrics", () -> ran.set(true));
        broker.ruleWorker.executeBlocking(() -> null, false).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        return ran.get();
    }

    /** Starts the broker over a real {@link IotService} whose one rule writes every metrics publish to DynamoDB. */
    private void startBrokerWithRuleWriting(DynamoDbItemAccess items) throws Exception {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        EmulatorConfig serviceConfig = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(serviceConfig.defaultRegion()).thenReturn("us-east-1");
        when(serviceConfig.defaultAccountId()).thenReturn("000000000000");
        when(serviceConfig.services().iot().ruleSqlStrict()).thenReturn(false);
        ObjectMapper mapper = new ObjectMapper();
        IotService service = new IotService(storageFactory, serviceConfig, new RegionResolver("eu-west-1", "111122223333"),
                mapper, recorder, broker, mock(SqsService.class), mock(SnsService.class),
                mock(S3Service.class), mock(KinesisService.class),
                new DynamoDbFacade(items, mock(DynamoDbTableAccess.class), new RegionResolver("us-east-1", "000000000000")),
                mock(LambdaService.class), mock(FirehoseService.class), mock(CloudWatchLogsService.class), ca,
                new IamPolicyEvaluator(mapper));
        when(iotService.get()).thenReturn(service);
        service.createTopicRule("toTable", mapper.readTree("""
                {"sql": "SELECT * FROM 'devices/+/metrics'",
                 "actions": [{"dynamoDBv2": {"putItem": {"tableName": "metrics"}, "roleArn": "r"}}]}
                """), "eu-west-1");
        broker.startIfEnabled();
    }

    /** Starts the broker over a real {@link IotService} whose recorder sees every publish the broker hands it. */
    private void startBrokerRecordingPublishes() throws Exception {
        startBrokerWithRuleWriting(mock(DynamoDbItemAccess.class));
    }

    /** The recorded presence events of one type for one client id, in publish order. */
    private List<JsonNode> presence(String eventType, String clientId) {
        String topic = "$aws/events/presence/" + eventType + "/" + clientId;
        return recorder.recentEvents().stream()
                .filter(event -> event.topic().equals(topic))
                .map(event -> json(event.payload()))
                .toList();
    }

    /** Every recorded presence event for one client id, in publish order. */
    private List<JsonNode> presenceOf(String clientId) {
        return recorder.recentEvents().stream()
                .filter(event -> event.topic().equals("$aws/events/presence/connected/" + clientId)
                        || event.topic().equals("$aws/events/presence/disconnected/" + clientId))
                .map(event -> json(event.payload()))
                .toList();
    }

    private List<JsonNode> awaitPresence(String eventType, String clientId, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<JsonNode> events = presence(eventType, clientId);
        while (events.size() < count) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("expected " + count + " " + eventType + " events for " + clientId + ", got " + events);
            }
            Thread.sleep(10);
            events = presence(eventType, clientId);
        }
        return events;
    }

    /**
     * Connects and cleanly disconnects one more client and waits for its disconnected event. The
     * plaintext listener handles every connection on one event loop, so by then it has also handled
     * whatever the earlier connections sent and their closes.
     */
    private void barrier() throws Exception {
        String clientId = "barrier-" + System.nanoTime();
        disconnect(connectPlain(clientId));
        awaitPresence("disconnected", clientId, 1);
    }

    private static void awaitDisconnected(MqttClient client) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (client.isConnected()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("client " + client.getClientId() + " is still connected");
            }
            Thread.sleep(10);
        }
    }

    private static MqttSubscribeMessage subscribeMessage(String topicFilter) {
        MqttTopicSubscription requested = mock(MqttTopicSubscription.class);
        when(requested.topicName()).thenReturn(topicFilter);
        when(requested.qualityOfService()).thenReturn(MqttQoS.AT_MOST_ONCE);
        MqttSubscribeMessage message = mock(MqttSubscribeMessage.class);
        when(message.topicSubscriptions()).thenReturn(List.of(requested));
        return message;
    }

    /** A connected client with a one-second keep-alive. */
    private MqttEndpoint keepAliveEndpoint(String clientId) {
        MqttEndpoint endpoint = mock(MqttEndpoint.class);
        when(endpoint.clientIdentifier()).thenReturn(clientId);
        when(endpoint.keepAliveTimeSeconds()).thenReturn(1);
        broker.handleEndpoint(endpoint, false);
        return endpoint;
    }

    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<Handler<T>> handlerCaptor() {
        return ArgumentCaptor.forClass(Handler.class);
    }

    private static List<String> eventTypes(List<JsonNode> events) {
        return events.stream().map(event -> event.get("eventType").asText()).toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void assertDashedUuid(String value) {
        assertEquals(UUID.fromString(value).toString(), value, "a dashed lowercase UUID");
    }

    private static JsonNode json(byte[] payload) {
        try {
            return MAPPER.readTree(payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** An MQTT 3.1.1 CONNECT over a plain socket, so the test decides exactly which packets the broker sees. */
    private Socket rawConnect(String clientId) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", plainPort), 5_000);
        socket.setSoTimeout(5_000);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8(body, "MQTT");
        body.write(0x04);
        body.write(0x02);
        body.write(0x00);
        body.write(0x3c);
        writeUtf8(body, clientId);
        socket.getOutputStream().write(packet(0x10, body.toByteArray()));
        assertArrayEquals(new byte[] {0x20, 0x02, 0x00, 0x00}, socket.getInputStream().readNBytes(4), "CONNACK accepted");
        return socket;
    }

    private static void rawPublish(Socket socket, int qos, String topic, byte[] payload) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8(body, topic);
        if (qos > 0) {
            body.write(0x00);
            body.write(0x01);
        }
        body.writeBytes(payload);
        socket.getOutputStream().write(packet(0x30 | (qos << 1), body.toByteArray()));
    }

    private static byte[] packet(int type, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type);
        int remaining = body.length;
        do {
            int encoded = remaining % 128;
            remaining /= 128;
            if (remaining > 0) {
                encoded |= 128;
            }
            out.write(encoded);
        } while (remaining > 0);
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static void writeUtf8(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.write(bytes.length >>> 8);
        out.write(bytes.length & 0xff);
        out.writeBytes(bytes);
    }

    /** A client subscribed to one filter whose callback records the topic of every message the broker sends it. */
    private MqttClient subscribedPlain(String clientId, String topicFilter, BlockingQueue<String> topics) throws MqttException {
        MqttClient client = connectPlain(clientId);
        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                // The test disconnects the client itself.
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                topics.add(topic);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // Subscribers do not publish.
            }
        });
        client.subscribe(topicFilter, 0);
        return client;
    }

    private MqttClient connectPlain(String clientId) throws MqttException {
        MqttClient client = new MqttClient("tcp://127.0.0.1:" + plainPort, clientId, new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(10);
        options.setAutomaticReconnect(false);
        client.connect(options);
        client.setTimeToWait(10_000);
        return client;
    }

    private static void disconnect(MqttClient client) throws MqttException {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } finally {
            client.close();
        }
    }

    private X509Certificate handshake(KeyManager[] clientKeys) throws Exception {
        try (SSLSocket socket = connectTls(clientKeys)) {
            socket.startHandshake();
            return (X509Certificate) socket.getSession().getPeerCertificates()[0];
        }
    }

    /** A handshake as a device pinned to one protocol version and AWS's suites for it would do. */
    private SSLSession negotiate(String protocol, Set<String> suites) throws Exception {
        try (SSLSocket socket = connectTls(null)) {
            socket.setEnabledProtocols(new String[] {protocol});
            socket.setEnabledCipherSuites(List.of(socket.getSupportedCipherSuites()).stream()
                    .filter(suites::contains).toArray(String[]::new));
            socket.startHandshake();
            return socket.getSession();
        }
    }

    private SSLSocket connectTls(KeyManager[] clientKeys) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("floci", ca.certificate());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(clientKeys, tmf.getTrustManagers(), null);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress("127.0.0.1", tlsPort), 5_000);
        return socket;
    }

    private static KeyManager[] keyManagers(CertificateGenerator.GeneratedCertificate leaf) throws Exception {
        KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
        keys.load(null, null);
        PrivateKey key = Pem.parsePrivateKey(leaf.privateKeyPem());
        keys.setKeyEntry("device", key, new char[0], new Certificate[] {Pem.parseCertificate(leaf.certificatePem())});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, new char[0]);
        return kmf.getKeyManagers();
    }

    private static KeyCertOptions pem(CertificateGenerator.GeneratedCertificate leaf) {
        return new PemKeyCertOptions()
                .addCertValue(Buffer.buffer(leaf.certificatePem()))
                .addKeyValue(Buffer.buffer(leaf.privateKeyPem()));
    }

    private static java.math.BigInteger serial(CertificateGenerator.GeneratedCertificate leaf) {
        return Pem.parseCertificate(leaf.certificatePem()).getSerialNumber();
    }

    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    /** Vert.x resolves close() a moment before the OS releases the port; poll like the lazy-start test. */
    private static void awaitClosed(int port) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (accepts(port)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("port " + port + " still accepts connections");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for port " + port + " to close", e);
            }
        }
    }

    /** A client key manager with no certificate that records whether the server asked for one. */
    private static final class RecordingKeyManager extends X509ExtendedKeyManager {
        final AtomicBoolean asked = new AtomicBoolean();

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            asked.set(true);
            return null;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            asked.set(true);
            return null;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[0];
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return new String[0];
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return null;
        }
    }
}
