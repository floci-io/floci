package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iot.model.IotRetainedMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.SocketAddress;
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

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class IotMqttBrokerService {

    private static final Logger LOG = Logger.getLogger(IotMqttBrokerService.class);

    private final EmulatorConfig config;
    private final Vertx vertx;
    private final Instance<IotService> iotService;
    private final Map<String, ClientSession> sessionsByClient = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Subscription>> subscriptionsByClient = new ConcurrentHashMap<>();
    private MqttServer server;

    @Inject
    public IotMqttBrokerService(EmulatorConfig config, Vertx vertx, Instance<IotService> iotService) {
        this.config = config;
        this.vertx = vertx;
        this.iotService = iotService;
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

        MqttServer mqttServer = MqttServer.create(vertx, new MqttServerOptions()
                .setHost(config.services().iot().mqtt().host())
                .setPort(config.services().iot().mqtt().port()));
        mqttServer.endpointHandler(this::handleEndpoint);
        mqttServer.exceptionHandler(error -> LOG.warnv("IoT MQTT broker error: {0}", error.getMessage()));

        try {
            mqttServer.listen().toCompletionStage().toCompletableFuture().join();
            server = mqttServer;
            LOG.infov("IoT MQTT broker started on {0}:{1}",
                    config.services().iot().mqtt().host(), config.services().iot().mqtt().port());
        } catch (Exception e) {
            mqttServer.close();
            throw new IllegalStateException("Failed to start IoT MQTT broker", e);
        }
    }

    synchronized void stop() {
        MqttServer mqttServer = server;
        if (mqttServer == null) {
            return;
        }
        server = null;
        sessionsByClient.values().forEach(session -> session.endpoint().close());
        sessionsByClient.clear();
        subscriptionsByClient.clear();
        mqttServer.close().toCompletionStage().toCompletableFuture().join();
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

    private void handleEndpoint(MqttEndpoint endpoint) {
        String clientId = endpoint.clientIdentifier();
        SocketAddress remoteAddress = endpoint.remoteAddress();
        ClientSession session = new ClientSession(
                clientId,
                endpoint,
                remoteAddress == null ? null : remoteAddress.host(),
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

        iotService.get().publish(topic, payload, message.isRetain(), message.qosLevel().value());
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
