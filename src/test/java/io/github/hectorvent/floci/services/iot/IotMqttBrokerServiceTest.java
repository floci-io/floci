package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
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
import io.netty.handler.codec.mqtt.MqttQoS;
import io.quarkus.tls.CertificateUpdatedEvent;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.tls.runtime.config.TlsConfig;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttTopicSubscription;
import io.vertx.mqtt.messages.MqttSubscribeMessage;
import jakarta.enterprise.inject.Instance;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
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
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import java.io.IOException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The MQTT over TLS listener against a real Vert.x and a mocked TLS registry: what it serves,
 * when it is not opened, how a bind failure is reported, and how a reloaded server certificate
 * reaches the next handshake without a restart. Also that a publish's topic rules run on the
 * broker's own single-thread worker, off the event loop.
 */
class IotMqttBrokerServiceTest {

    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

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
        when(tls.getKeyStoreOptions()).thenReturn(pem(bootLeaf));
        broker = new IotMqttBrokerService(config, vertx, iotService, registry);
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
        var device = GENERATOR.generateSelfSignedCertificate("device", List.of(), KeyAlgorithm.RSA_2048);

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
     * A restart whose TLS listener cannot bind fails and leaves rules off; a later successful start
     * turns them on again.
     */
    @Test
    void aFailedRestartKeepsRulesOff() throws Exception {
        broker.startIfEnabled();
        broker.stop();
        try (ServerSocket blocker = new ServerSocket()) {
            blocker.bind(new InetSocketAddress("127.0.0.1", tlsPort));

            assertThrows(IllegalStateException.class, broker::startIfEnabled);
            assertFalse(rulesRun(), "a failed restart leaves rules off");
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
                mapper, new IotPublishEventRecorder(), broker, mock(SqsService.class), mock(SnsService.class),
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
        PrivateKey key = GENERATOR.parsePrivateKey(leaf.privateKeyPem());
        keys.setKeyEntry("device", key, new char[0], new Certificate[] {GENERATOR.parseCertificate(leaf.certificatePem())});
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
        return GENERATOR.parseCertificate(leaf.certificatePem()).getSerialNumber();
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

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
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
