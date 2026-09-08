package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iot.model.IotRetainedMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.tls.CertificateUpdatedEvent;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.tls.runtime.config.TlsConfig;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.net.TrustOptions;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttServer;
import io.vertx.mqtt.MqttServerOptions;
import io.vertx.mqtt.messages.MqttPublishMessage;
import io.vertx.mqtt.messages.MqttSubscribeMessage;
import io.vertx.mqtt.messages.MqttUnsubscribeMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@ApplicationScoped
public class IotMqttBrokerService {

    private static final Logger LOG = Logger.getLogger(IotMqttBrokerService.class);

    /**
     * The TLS listener asks for a client certificate and lets the handshake complete with whichever
     * one is presented: AWS IoT trusts a device certificate because it is registered, not because
     * of its issuer, and that lookup happens on the CONNECT in {@link #handleEndpoint}, where a
     * certificate that is missing, unregistered, inactive or not allowed to connect is answered
     * with CONNACK not authorized.
     */
    static final X509ExtendedTrustManager ACCEPT_ANY_CLIENT_CERTIFICATE = new X509ExtendedTrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("server-side trust manager");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("server-side trust manager");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            throw new CertificateException("server-side trust manager");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final EmulatorConfig config;
    private final Vertx vertx;
    private final Instance<IotService> iotService;
    private final TlsConfigurationRegistry tlsRegistry;
    private final Map<String, ClientSession> sessionsByClient = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Subscription>> subscriptionsByClient = new ConcurrentHashMap<>();
    private MqttServer server;
    private MqttServer tlsServer;
    private ReloadingKeyManager keyManager;

    @Inject
    public IotMqttBrokerService(EmulatorConfig config, Vertx vertx, Instance<IotService> iotService,
                                TlsConfigurationRegistry tlsRegistry) {
        this.config = config;
        this.vertx = vertx;
        this.iotService = iotService;
        this.tlsRegistry = tlsRegistry;
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!config.services().iot().enabled() || !config.services().iot().mqtt().enabled()) {
            LOG.info("IoT MQTT broker disabled by configuration");
            return;
        }
        if (!config.services().iot().mqtt().autoStart()) {
            LOG.info("IoT MQTT broker auto-start disabled by configuration");
            return;
        }
        startIfEnabled();
    }

    void onStop(@Observes ShutdownEvent ignored) {
        stop();
    }

    synchronized void startIfEnabled() {
        if (!config.services().iot().enabled() || !config.services().iot().mqtt().enabled()) {
            return;
        }
        if (server != null) {
            return;
        }

        MqttServer mqttServer = listen(new MqttServerOptions()
                .setHost(config.services().iot().mqtt().host())
                .setPort(config.services().iot().mqtt().port()), "IoT MQTT broker", false);
        try {
            startTlsListener();
        } catch (RuntimeException e) {
            mqttServer.close().toCompletionStage().toCompletableFuture().join();
            throw e;
        }
        server = mqttServer;
    }

    /**
     * The MQTT over TLS listener, AWS IoT's 8883, sharing every handler with the plaintext one but
     * admitting only registered devices whose policies allow the connect. Its key material is the
     * TLS registry's default configuration, the same certificate the HTTPS endpoint presents. Not
     * opened when the port is 0 or TLS is off.
     */
    private void startTlsListener() {
        int tlsPort = config.services().iot().mqtt().tlsPort();
        if (tlsPort <= 0) {
            return;
        }
        if (!config.tls().enabled()) {
            LOG.infov("IoT MQTT TLS listener not started on port {0}: floci.tls.enabled is false", Integer.toString(tlsPort));
            return;
        }
        TlsConfiguration tls = tlsRegistry.getDefault().orElse(null);
        if (tls == null) {
            LOG.warnv("IoT MQTT TLS listener not started on port {0}: no default TLS configuration is registered",
                    Integer.toString(tlsPort));
            return;
        }
        ReloadingKeyManager manager = new ReloadingKeyManager(ReloadingKeyManager.keyManagerOf(vertx, tls.getKeyStoreOptions()));
        tlsServer = listen(new MqttServerOptions()
                .setHost(config.services().iot().mqtt().host())
                .setPort(tlsPort)
                .setSsl(true)
                .setKeyCertOptions(KeyCertOptions.wrap(manager))
                .setTrustOptions(TrustOptions.wrap(ACCEPT_ANY_CLIENT_CERTIFICATE))
                .setClientAuth(ClientAuth.REQUEST), "IoT MQTT TLS broker", true);
        keyManager = manager;
    }

    private MqttServer listen(MqttServerOptions options, String name, boolean verifyDevice) {
        MqttServer mqttServer = MqttServer.create(vertx, options);
        mqttServer.endpointHandler(endpoint -> handleEndpoint(endpoint, verifyDevice));
        mqttServer.exceptionHandler(error -> LOG.warnv("{0} error: {1}", name, error.getMessage()));
        try {
            mqttServer.listen().toCompletionStage().toCompletableFuture().join();
        } catch (Exception e) {
            mqttServer.close().toCompletionStage().toCompletableFuture().join();
            throw new IllegalStateException("Failed to start " + name + " on port " + options.getPort(), e);
        }
        LOG.infov("{0} started on {1}:{2}", name, options.getHost(), Integer.toString(options.getPort()));
        return mqttServer;
    }

    /**
     * The HTTPS server switches to a reissued server certificate through this event (a hostname
     * learned by {@code TlsCertificateManager.ensureHost}, or a reload). The TLS listener follows
     * by handing its key manager the reloaded key store: the next handshake presents the new
     * certificate, established sessions keep theirs, and a failed reload keeps the previous one.
     */
    synchronized void onCertificateUpdated(@Observes CertificateUpdatedEvent event) {
        ReloadingKeyManager manager = keyManager;
        if (manager == null || !TlsConfig.DEFAULT_NAME.equalsIgnoreCase(event.name())) {
            return;
        }
        try {
            manager.reload(ReloadingKeyManager.keyManagerOf(vertx, event.tlsConfiguration().getKeyStoreOptions()));
            LOG.debug("IoT MQTT TLS broker serves the reloaded server certificate");
        } catch (Exception e) {
            LOG.warnv(e, "IoT MQTT TLS broker keeps its previous server certificate: {0}", e.getMessage());
        }
    }

    synchronized void stop() {
        MqttServer mqttServer = server;
        MqttServer mqttTlsServer = tlsServer;
        if (mqttServer == null && mqttTlsServer == null) {
            return;
        }
        server = null;
        tlsServer = null;
        keyManager = null;
        sessionsByClient.values().forEach(session -> session.endpoint().close());
        sessionsByClient.clear();
        subscriptionsByClient.clear();
        if (mqttServer != null) {
            mqttServer.close().toCompletionStage().toCompletableFuture().join();
        }
        if (mqttTlsServer != null) {
            mqttTlsServer.close().toCompletionStage().toCompletableFuture().join();
        }
        LOG.info("IoT MQTT broker stopped");
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    void publish(String topic, byte[] payload) {
        if (server == null) {
            return;
        }
        fanOut(topic, payload == null ? new byte[0] : payload, false);
    }

    boolean disconnectClient(String clientId, boolean cleanSession) {
        ClientSession session = sessionsByClient.remove(clientId);
        if (session == null) {
            return false;
        }
        if (cleanSession) {
            subscriptionsByClient.remove(clientId);
        }
        session.endpoint().close();
        return true;
    }

    Optional<ConnectionInfo> getConnection(String clientId) {
        if (server == null) {
            return Optional.empty();
        }
        ClientSession session = sessionsByClient.get(clientId);
        if (session == null || !session.endpoint().isConnected()) {
            return Optional.empty();
        }
        return Optional.of(new ConnectionInfo(session.clientId(), session.sourceIp(), session.sourcePort()));
    }

    List<String> listSubscriptions(String clientId) {
        return subscriptionsByClient.getOrDefault(clientId, Map.of()).keySet().stream()
                .sorted()
                .toList();
    }

    /**
     * On the TLS listener the CONNECT is admitted only for a presented certificate that IoT Core
     * has registered and whose attached policies allow {@code iot:Connect} for the client id;
     * anything else is answered with CONNACK not authorized (return code 5, reason code 0x87 on
     * MQTT 5) and never becomes a session, so a client already holding that client id keeps its
     * connection. The plaintext listener, which the WebSocket bridge also lands on, stays open to
     * every client.
     */
    private void handleEndpoint(MqttEndpoint endpoint, boolean verifyDevice) {
        String clientId = endpoint.clientIdentifier();
        SocketAddress remoteAddress = endpoint.remoteAddress();
        String sourceIp = remoteAddress == null ? null : remoteAddress.host();
        String principal = null;
        if (verifyDevice) {
            principal = admittedDevice(endpoint, clientId, sourceIp);
            if (principal == null) {
                endpoint.reject(endpoint.protocolVersion() == 5
                        ? MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED_5
                        : MqttConnectReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED);
                return;
            }
        }
        ClientSession session = new ClientSession(
                clientId,
                endpoint,
                sourceIp,
                remoteAddress == null ? -1 : remoteAddress.port(),
                endpoint.isCleanSession());

        endpoint.subscriptionAutoAck(false);
        endpoint.publishAutoAck(false);
        endpoint.exceptionHandler(error -> LOG.warnv("IoT MQTT client {0} error: {1}", clientId, error.getMessage()));
        endpoint.subscribeHandler(message -> handleSubscribe(session, message));
        endpoint.unsubscribeHandler(message -> handleUnsubscribe(session, message));
        endpoint.publishHandler(message -> handlePublish(session, message));
        endpoint.disconnectHandler(ignored -> removeSession(session));
        endpoint.closeHandler(ignored -> removeSession(session));

        ClientSession previous = sessionsByClient.put(clientId, session);
        if (previous != null && previous.endpoint() != endpoint) {
            previous.endpoint().close();
        }

        endpoint.accept();
        if (principal != null) {
            LOG.debugv("IoT MQTT TLS client {0} admitted as {1}", clientId, principal);
        }
    }

    /**
     * The certificate ARN of the device behind the connection when it may connect, otherwise null.
     * A failure while deciding refuses the client rather than leaving the CONNECT unanswered. A
     * refusal is the client's own doing and reaches it as the CONNACK, so it is logged at debug
     * only: the CONNECT is reachable by anyone.
     */
    private String admittedDevice(MqttEndpoint endpoint, String clientId, String sourceIp) {
        try {
            Optional<IotService.RegisteredDevice> device = presentedDevice(endpoint);
            if (device.isEmpty()) {
                LOG.debugv("IoT MQTT TLS client {0} refused: no registered certificate presented", clientId);
                return null;
            }
            String certificateArn = device.get().certificate().getCertificateArn();
            if (!iotService.get().isConnectAllowed(device.get(), clientId, sourceIp, requestedServerName(endpoint))) {
                LOG.debugv("IoT MQTT TLS client {0} refused: iot:Connect is not allowed for {1}", clientId, certificateArn);
                return null;
            }
            return certificateArn;
        } catch (RuntimeException e) {
            LOG.warnv(e, "IoT MQTT TLS client {0} refused: device verification failed: {1}", clientId, e.getMessage());
            return null;
        }
    }

    /** The registered certificate behind the peer's leaf, if the client presented one at all. */
    private Optional<IotService.RegisteredDevice> presentedDevice(MqttEndpoint endpoint) {
        SSLSession session = endpoint.isSsl() ? endpoint.sslSession() : null;
        if (session == null) {
            return Optional.empty();
        }
        Certificate[] chain;
        try {
            chain = session.getPeerCertificates();
        } catch (SSLPeerUnverifiedException e) {
            LOG.debugv("IoT MQTT TLS client {0} presented no certificate", endpoint.clientIdentifier());
            return Optional.empty();
        }
        if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
            return Optional.empty();
        }
        return iotService.get().findRegisteredCertificate(leaf);
    }

    /**
     * The server name the client sent in the handshake, which AWS IoT exposes as
     * {@code iot:DomainName}. An address literal is not a domain name: some clients send the
     * host they dialled as SNI even when it is an IP.
     */
    private static String requestedServerName(MqttEndpoint endpoint) {
        if (!(endpoint.sslSession() instanceof ExtendedSSLSession session)) {
            return null;
        }
        return session.getRequestedServerNames().stream()
                .filter(SNIHostName.class::isInstance)
                .map(name -> ((SNIHostName) name).getAsciiName())
                .filter(name -> !isAddressLiteral(name))
                .findFirst()
                .orElse(null);
    }

    /** An IPv4 dotted quad, or anything carrying a colon, which only an IPv6 literal does. */
    static boolean isAddressLiteral(String name) {
        return name.indexOf(':') >= 0 || IPV4_LITERAL.matcher(name).matches();
    }

    private void handleSubscribe(ClientSession session, MqttSubscribeMessage message) {
        Map<String, Subscription> clientSubscriptions = subscriptionsByClient.computeIfAbsent(
                session.clientId(), ignored -> new ConcurrentHashMap<>());
        List<MqttQoS> grantedQos = new ArrayList<>();
        List<Subscription> accepted = new ArrayList<>();

        for (io.vertx.mqtt.MqttTopicSubscription requested : message.topicSubscriptions()) {
            String topicFilter = requested.topicName();
            MqttQoS qos = requested.qualityOfService();
            if (!isValidTopicFilter(topicFilter) || qos == MqttQoS.EXACTLY_ONCE) {
                grantedQos.add(MqttQoS.FAILURE);
                continue;
            }

            int granted = qos == MqttQoS.AT_LEAST_ONCE ? 1 : 0;
            Subscription subscription = new Subscription(topicFilter, granted);
            clientSubscriptions.put(topicFilter, subscription);
            accepted.add(subscription);
            grantedQos.add(granted == 1 ? MqttQoS.AT_LEAST_ONCE : MqttQoS.AT_MOST_ONCE);
        }

        session.endpoint().subscribeAcknowledge(message.messageId(), grantedQos);
        deliverRetained(session, accepted);
    }

    private void handleUnsubscribe(ClientSession session, MqttUnsubscribeMessage message) {
        Map<String, Subscription> clientSubscriptions = subscriptionsByClient.get(session.clientId());
        if (clientSubscriptions != null) {
            for (String topic : message.topics()) {
                clientSubscriptions.remove(topic);
            }
            if (clientSubscriptions.isEmpty()) {
                subscriptionsByClient.remove(session.clientId(), clientSubscriptions);
            }
        }
        session.endpoint().unsubscribeAcknowledge(message.messageId());
    }

    private void handlePublish(ClientSession session, MqttPublishMessage message) {
        byte[] payload = message.payload().getBytes();
        if (message.qosLevel() == MqttQoS.EXACTLY_ONCE) {
            session.endpoint().close();
            return;
        }
        if (message.qosLevel() == MqttQoS.AT_LEAST_ONCE) {
            session.endpoint().publishAcknowledge(message.messageId());
        }

        String topic = message.topicName();
        if (topic.startsWith("$aws/")) {
            iotService.get().handleReservedMqttPublish(topic, payload, this::publish);
            return;
        }

        iotService.get().publish(topic, payload, message.isRetain(), message.qosLevel().value(), null, session.clientId());
        fanOut(topic, payload, false);
    }

    private void fanOut(String topic, byte[] payload, boolean retained) {
        byte[] safePayload = payload == null ? new byte[0] : payload.clone();
        for (ClientSession session : sessionsByClient.values()) {
            if (!session.endpoint().isConnected() || !hasMatchingSubscription(session.clientId(), topic)) {
                continue;
            }
            session.endpoint().publish(topic, Buffer.buffer(safePayload), MqttQoS.AT_MOST_ONCE, false, retained);
        }
    }

    private boolean hasMatchingSubscription(String clientId, String topic) {
        Map<String, Subscription> subscriptions = subscriptionsByClient.get(clientId);
        if (subscriptions == null) {
            return false;
        }
        return subscriptions.values().stream().anyMatch(subscription -> topicMatches(subscription.topicFilter(), topic));
    }

    private void deliverRetained(ClientSession session, List<Subscription> subscriptions) {
        if (subscriptions.isEmpty()) {
            return;
        }
        Set<String> deliveredTopics = new HashSet<>();
        for (IotRetainedMessage retained : iotService.get().listRetainedMessages(null, null).items()) {
            if (!deliveredTopics.add(retained.getTopic())) {
                continue;
            }
            boolean matches = subscriptions.stream()
                    .anyMatch(subscription -> topicMatches(subscription.topicFilter(), retained.getTopic()));
            if (!matches) {
                continue;
            }
            byte[] payload = Base64.getDecoder().decode(retained.getPayload());
            session.endpoint().publish(retained.getTopic(), Buffer.buffer(payload), MqttQoS.AT_MOST_ONCE, false, true);
        }
    }

    private void removeSession(ClientSession session) {
        sessionsByClient.remove(session.clientId(), session);
        if (session.cleanSession()) {
            subscriptionsByClient.remove(session.clientId());
        }
    }

    private boolean isValidTopicFilter(String topicFilter) {
        if (topicFilter == null || topicFilter.isBlank()) {
            return false;
        }
        String[] levels = topicFilter.split("/", -1);
        for (int i = 0; i < levels.length; i++) {
            String level = levels[i];
            if (level.contains("#") && (!"#".equals(level) || i != levels.length - 1)) {
                return false;
            }
            if (level.contains("+") && !"+".equals(level)) {
                return false;
            }
        }
        return true;
    }

    private boolean topicMatches(String topicFilter, String topic) {
        if (topicFilter.equals(topic)) {
            return true;
        }
        String[] filterLevels = topicFilter.split("/", -1);
        String[] topicLevels = topic.split("/", -1);
        for (int i = 0; i < filterLevels.length; i++) {
            String filterLevel = filterLevels[i];
            if ("#".equals(filterLevel)) {
                return i == filterLevels.length - 1;
            }
            if (i >= topicLevels.length) {
                return false;
            }
            if (!"+".equals(filterLevel) && !filterLevel.equals(topicLevels[i])) {
                return false;
            }
        }
        return filterLevels.length == topicLevels.length;
    }

    private record ClientSession(
            String clientId,
            MqttEndpoint endpoint,
            String sourceIp,
            int sourcePort,
            boolean cleanSession) {
    }

    private record Subscription(String topicFilter, int qos) {
    }

    record ConnectionInfo(String clientId, String address, int port) {
    }
}
