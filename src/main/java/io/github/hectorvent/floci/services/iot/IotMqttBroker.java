package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.iot.model.AuthorizerResult;
import io.github.hectorvent.floci.services.iot.model.IotAuthorizer;
import io.moquette.BrokerConstants;
import io.moquette.broker.Server;
import io.moquette.broker.config.IConfig;
import io.moquette.broker.config.MemoryConfig;
import io.moquette.broker.security.IAuthenticator;
import io.moquette.broker.security.IAuthorizatorPolicy;
import io.moquette.broker.subscriptions.Topic;
import io.moquette.interception.AbstractInterceptHandler;
import io.moquette.interception.messages.InterceptConnectMessage;
import io.moquette.interception.messages.InterceptConnectionLostMessage;
import io.moquette.interception.messages.InterceptDisconnectMessage;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedded MQTT broker (Moquette) implementing the AWS IoT data plane with token-based
 * custom-authorizer enforcement.
 *
 * <p>At CONNECT, custom-auth parameters are extracted from the MQTT username, the authorizer
 * Lambda is invoked via {@link IotAuthorizerService}, and the returned IAM policy is evaluated
 * for {@code iot:Connect}. Per-session policies are cached and re-evaluated for
 * {@code iot:Publish} / {@code iot:Subscribe} / {@code iot:Receive} on every publish/subscribe.
 */
@ApplicationScoped
public class IotMqttBroker {

    private static final Logger LOG = Logger.getLogger(IotMqttBroker.class);

    private static final String INTERNAL_PUBLISHER = "floci-iot-broker";

    private final EmulatorConfig config;
    private final IotAuthorizerService authorizerService;
    private final RegionResolver regionResolver;
    private final IotBrokerEndpoint endpoint;
    private final IotWebSocketBridge webSocketBridge;
    private final ObjectMapper objectMapper;

    private final Map<String, AuthorizerResult> sessionPolicies = new ConcurrentHashMap<>();
    private volatile boolean lifecycleEventsEnabled;
    private Server server;

    @Inject
    public IotMqttBroker(EmulatorConfig config,
                         IotAuthorizerService authorizerService,
                         RegionResolver regionResolver,
                         IotBrokerEndpoint endpoint,
                         IotWebSocketBridge webSocketBridge,
                         ObjectMapper objectMapper) {
        this.config = config;
        this.authorizerService = authorizerService;
        this.regionResolver = regionResolver;
        this.endpoint = endpoint;
        this.objectMapper = objectMapper;
        this.webSocketBridge = webSocketBridge;
    }

    void onStart(@Observes StartupEvent ignored) {
        EmulatorConfig.IotServiceConfig iot = config.services().iot();
        if (!iot.enabled() || !iot.brokerEnabled()) {
            LOG.debug("IoT MQTT broker disabled by configuration");
            return;
        }
        this.lifecycleEventsEnabled = iot.brokerLifecycleEventsEnabled();
        int tcpPort = findFreePort(iot.brokerTcpBasePort(), iot.brokerTcpMaxPort());
        int wsPort = findFreePort(iot.brokerWsBasePort(), iot.brokerWsMaxPort());
        if (tcpPort < 0 || wsPort < 0) {
            LOG.warnv("IoT MQTT broker not started: no free ports in TCP {0}-{1} / WS {2}-{3}",
                    iot.brokerTcpBasePort(), iot.brokerTcpMaxPort(),
                    iot.brokerWsBasePort(), iot.brokerWsMaxPort());
            return;
        }

        Properties props = new Properties();
        props.setProperty(BrokerConstants.HOST_PROPERTY_NAME, iot.brokerHost());
        props.setProperty(BrokerConstants.PORT_PROPERTY_NAME, String.valueOf(tcpPort));
        props.setProperty(BrokerConstants.PERSISTENCE_ENABLED_PROPERTY_NAME, "false");
        props.setProperty(BrokerConstants.ENABLE_TELEMETRY_NAME, "false");
        // Force every connection through the custom authorizer (no anonymous access).
        props.setProperty(BrokerConstants.ALLOW_ANONYMOUS_PROPERTY_NAME, "false");

        // WebSocket transport (ws/wss) is handled by IotWebSocketBridge, not Moquette's own
        // WebSocket listener, which mis-frames packets from mqtt.js / the AWS IoT Device SDK.
        int wssPort = -1;
        int mqttsPort = -1;
        String keystorePath = null;
        String keystorePassword = iot.brokerTlsKeystorePassword();
        if (iot.brokerTlsEnabled()) {
            keystorePath = resolveKeystorePath(iot);
            if (keystorePath != null) {
                mqttsPort = findFreePort(iot.brokerMqttsBasePort(), iot.brokerMqttsMaxPort());
                wssPort = findFreePort(iot.brokerWssBasePort(), iot.brokerWssMaxPort());
                if (mqttsPort > 0) {
                    // Moquette serves MQTT-over-TLS (mqtts) directly; TLS-over-TCP frames fine.
                    props.setProperty(BrokerConstants.SSL_PORT_PROPERTY_NAME, String.valueOf(mqttsPort));
                    props.setProperty(BrokerConstants.JKS_PATH_PROPERTY_NAME, keystorePath);
                    props.setProperty(BrokerConstants.KEY_STORE_TYPE, "pkcs12");
                    props.setProperty(BrokerConstants.KEY_STORE_PASSWORD_PROPERTY_NAME, keystorePassword);
                    props.setProperty(BrokerConstants.KEY_MANAGER_PASSWORD_PROPERTY_NAME, keystorePassword);
                }
            } else {
                LOG.warn("IoT MQTT broker TLS disabled: could not build keystore");
            }
        }

        IConfig brokerConfig = new MemoryConfig(props);
        Server srv = new Server();
        try {
            srv.startServer(brokerConfig, List.of(new LifecycleHandler()), null,
                    new IotAuthenticator(), new IotAuthorizator());
        } catch (IOException e) {
            LOG.errorv("Failed to start IoT MQTT broker: {0}", e.getMessage());
            return;
        }
        this.server = srv;

        // Bridge ws:// (+ wss:// when TLS is on) to the Moquette TCP listener.
        webSocketBridge.start(iot.brokerHost(), wsPort, wssPort, tcpPort, keystorePath, keystorePassword);

        endpoint.set(iot.brokerHost(), wsPort, tcpPort, iot.brokerWsPath());
        if (wssPort > 0) {
            endpoint.setTlsPorts(wssPort, mqttsPort);
            LOG.infov("IoT MQTT broker started — mqtt tcp://{0}:{1}, ws://{0}:{2}{3}, "
                            + "mqtts ssl://{0}:{4}, wss://{0}:{5}{3}",
                    endpoint.advertisedHost(), String.valueOf(tcpPort), String.valueOf(wsPort),
                    iot.brokerWsPath(), String.valueOf(mqttsPort), String.valueOf(wssPort));
        } else {
            LOG.infov("IoT MQTT broker started — mqtt tcp://{0}:{1}, ws://{0}:{2}{3} (TLS disabled)",
                    endpoint.advertisedHost(), String.valueOf(tcpPort), String.valueOf(wsPort),
                    iot.brokerWsPath());
        }
    }

    private String resolveKeystorePath(EmulatorConfig.IotServiceConfig iot) {
        try {
            String configuredPath = iot.brokerTlsKeystorePath().orElse(null);
            Path keystorePath = (configuredPath == null || configuredPath.isBlank())
                    ? Path.of(System.getProperty("java.io.tmpdir"), "floci-iot-broker-keystore.p12")
                    : Path.of(configuredPath);
            IotBrokerKeystore.ensure(keystorePath, iot.brokerTlsKeystorePassword());
            return keystorePath.toAbsolutePath().toString();
        } catch (Exception e) {
            LOG.warnv("Failed to build IoT broker TLS keystore: {0}", e.getMessage());
            return null;
        }
    }

    void onStop(@Observes ShutdownEvent ignored) {
        endpoint.clear();
        webSocketBridge.stop();
        if (server != null) {
            server.stopServer();
            server = null;
            LOG.info("IoT MQTT broker stopped");
        }
        sessionPolicies.clear();
    }

    private String region() {
        return regionResolver.getDefaultRegion();
    }

    private String iotArn(String resourceType, String resource) {
        return regionResolver.buildArn("iot", region(), resourceType + "/" + resource);
    }

    private static Map<String, String> conditionContext(String clientId) {
        return clientId == null ? Map.of() : Map.of("iot:ClientId", clientId);
    }

    // ──────────────────────────── Presence / lifecycle events ────────────────────────────

    private void publishConnected(String clientId) {
        if (!lifecycleEventsEnabled || clientId == null) {
            return;
        }
        ObjectNode event = baseLifecycleEvent(clientId, "connected");
        publishLifecycle("$aws/events/presence/connected/" + clientId, event);
    }

    private void publishDisconnected(String clientId, boolean clientInitiated) {
        if (!lifecycleEventsEnabled || clientId == null) {
            return;
        }
        ObjectNode event = baseLifecycleEvent(clientId, "disconnected");
        event.put("clientInitiatedDisconnect", clientInitiated);
        event.put("disconnectReason", clientInitiated ? "CLIENT_INITIATED_DISCONNECT" : "CONNECTION_LOST");
        publishLifecycle("$aws/events/presence/disconnected/" + clientId, event);
    }

    private ObjectNode baseLifecycleEvent(String clientId, String eventType) {
        AuthorizerResult session = sessionPolicies.get(clientId);
        String principal = (session != null && session.principalId() != null)
                ? session.principalId() : regionResolver.getAccountId();
        ObjectNode event = objectMapper.createObjectNode();
        event.put("clientId", clientId);
        event.put("timestamp", Instant.now().toEpochMilli());
        event.put("eventType", eventType);
        event.put("sessionIdentifier", UUID.randomUUID().toString());
        event.put("principalIdentifier", principal);
        event.put("versionNumber", 0);
        return event;
    }

    /**
     * Publishes a message to a topic via the data-plane HTTP {@code Publish} action.
     */
    public void publish(String topic, byte[] payload, int qos) {
        Server srv = this.server;
        if (srv == null) {
            throw new AwsException("ServiceUnavailableException", "IoT MQTT broker is not running.", 503);
        }
        MqttQoS mqttQos = qos == 1 ? MqttQoS.AT_LEAST_ONCE : MqttQoS.AT_MOST_ONCE;
        MqttPublishMessage message = MqttMessageBuilders.publish()
                .topicName(topic)
                .retained(false)
                .qos(mqttQos)
                .payload(Unpooled.copiedBuffer(payload == null ? new byte[0] : payload))
                .build();
        srv.internalPublish(message, INTERNAL_PUBLISHER);
    }

    private void publishLifecycle(String topic, ObjectNode event) {
        Server srv = this.server;
        if (srv == null) {
            return;
        }
        try {
            byte[] payload = objectMapper.writeValueAsBytes(event);
            MqttPublishMessage message = MqttMessageBuilders.publish()
                    .topicName(topic)
                    .retained(false)
                    .qos(MqttQoS.AT_MOST_ONCE)
                    .payload(Unpooled.copiedBuffer(payload))
                    .build();
            srv.internalPublish(message, INTERNAL_PUBLISHER);
        } catch (Exception e) {
            LOG.debugv("Failed to publish IoT lifecycle event to {0}: {1}", topic, e.getMessage());
        }
    }

    private static int findFreePort(int basePort, int maxPort) {
        for (int port = basePort; port <= maxPort; port++) {
            try (ServerSocket socket = new ServerSocket(port)) {
                socket.setReuseAddress(true);
                return port;
            } catch (IOException e) {
                // port busy, try next
            }
        }
        return -1;
    }

    // ──────────────────────────── Auth enforcement ────────────────────────────

    /**
     * Resolves the custom authorizer and invokes its Lambda at CONNECT time.
     */
    final class IotAuthenticator implements IAuthenticator {
        @Override
        public boolean checkValid(String clientId, String username, byte[] password) {
            if (username == null) {
                LOG.debugv("Rejecting MQTT CONNECT for {0}: no username/custom-auth params", clientId);
                return false;
            }
            try {
                CustomAuthParams params = CustomAuthParams.parse(username);
                IotAuthorizer authorizer = authorizerService.resolveAuthorizer(params.authorizerName());
                String token = params.token(authorizer.getTokenKeyName());
                String passwordStr = password == null ? null : new String(password, StandardCharsets.UTF_8);
                IotAuthorizerService.AuthInput input = new IotAuthorizerService.AuthInput(
                        token, params.signature(), clientId, params.baseUsername(), passwordStr);

                AuthorizerResult result = authorizerService.invokeAuthorizer(authorizer, region(), input);
                if (!result.isAuthenticated()) {
                    LOG.debugv("Authorizer denied authentication for client {0}", clientId);
                    return false;
                }
                String resource = iotArn("client", clientId);
                if (!authorizerService.isAllowed(result, "iot:Connect", resource, conditionContext(clientId))) {
                    LOG.debugv("Authorizer policy denies iot:Connect for client {0}", clientId);
                    return false;
                }
                sessionPolicies.put(clientId, result);
                LOG.debugv("Client {0} authenticated via authorizer {1}", clientId, authorizer.getAuthorizerName());
                return true;
            } catch (AwsException e) {
                LOG.debugv("MQTT CONNECT rejected for {0}: {1}", clientId, e.getMessage());
                return false;
            } catch (RuntimeException e) {
                LOG.warnv("MQTT CONNECT error for {0}: {1}", clientId, e.getMessage());
                return false;
            }
        }
    }

    /**
     * Evaluates the cached authorizer policy for publish/subscribe operations.
     */
    final class IotAuthorizator implements IAuthorizatorPolicy {
        @Override
        public boolean canWrite(Topic topic, String user, String client) {
            AuthorizerResult result = sessionPolicies.get(client);
            if (result == null) {
                return false;
            }
            String resource = iotArn("topic", topic.toString());
            return authorizerService.isAllowed(result, "iot:Publish", resource, conditionContext(client));
        }

        @Override
        public boolean canRead(Topic topic, String user, String client) {
            AuthorizerResult result = sessionPolicies.get(client);
            if (result == null) {
                return false;
            }
            Map<String, String> ctx = conditionContext(client);
            return authorizerService.isAllowed(result, "iot:Subscribe", iotArn("topicfilter", topic.toString()), ctx)
                    || authorizerService.isAllowed(result, "iot:Receive", iotArn("topic", topic.toString()), ctx);
        }
    }

    /**
     * Publishes AWS IoT presence/lifecycle events and drops the cached session policy when a
     * client disconnects. The cached policy is removed only after the disconnect event (and any
     * Last Will message) is published, so will/lifecycle authorization still sees it.
     */
    final class LifecycleHandler extends AbstractInterceptHandler {
        @Override
        public String getID() {
            return "floci-iot-lifecycle";
        }

        @Override
        public void onConnect(InterceptConnectMessage msg) {
            publishConnected(msg.getClientID());
        }

        @Override
        public void onDisconnect(InterceptDisconnectMessage msg) {
            // Graceful DISCONNECT. Moquette also fires onConnectionLost as the channel closes;
            // the session-policy guard there prevents a duplicate disconnected event.
            String clientId = msg.getClientID();
            publishDisconnected(clientId, true);
            sessionPolicies.remove(clientId);
        }

        @Override
        public void onConnectionLost(InterceptConnectionLostMessage msg) {
            // Only emit when no graceful DISCONNECT already handled this client (i.e. an abrupt
            // drop), so each disconnect yields exactly one lifecycle event.
            String clientId = msg.getClientID();
            if (sessionPolicies.containsKey(clientId)) {
                publishDisconnected(clientId, false);
                sessionPolicies.remove(clientId);
            }
        }

        @Override
        public void onSessionLoopError(Throwable error) {
            LOG.warnv("IoT MQTT session loop error: {0}", error == null ? "unknown" : error.getMessage());
        }
    }
}
