package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.moquette.BrokerConstants;
import io.moquette.broker.Server;
import io.moquette.broker.config.IConfig;
import io.moquette.broker.config.MemoryConfig;
import io.moquette.interception.AbstractInterceptHandler;
import io.moquette.interception.messages.InterceptPublishMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.mqtt.MqttMessageBuilders;
import io.netty.handler.codec.mqtt.MqttProperties;
import io.netty.handler.codec.mqtt.MqttPublishMessage;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

@ApplicationScoped
public class IotMqttBrokerService {

    private static final Logger LOG = Logger.getLogger(IotMqttBrokerService.class);
    private static final String INTERNAL_CLIENT_ID = "floci-iot";

    private final EmulatorConfig config;
    private final IotPublishEventRecorder eventRecorder;
    private final Instance<IotService> iotService;
    private Server server;

    @Inject
    public IotMqttBrokerService(EmulatorConfig config, IotPublishEventRecorder eventRecorder,
                                Instance<IotService> iotService) {
        this.config = config;
        this.eventRecorder = eventRecorder;
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

        Properties properties = new Properties();
        properties.setProperty("host", config.services().iot().mqtt().host());
        properties.setProperty("port", Integer.toString(config.services().iot().mqtt().port()));
        properties.setProperty("allow_anonymous", "true");
        properties.setProperty(BrokerConstants.ALLOW_RESERVED_PUBLISH_PREFIXES, "$aws/");
        IConfig brokerConfig = new MemoryConfig(properties);

        try {
            Server broker = new Server();
            broker.startServer(brokerConfig, List.of(new PublishInterceptor()));
            server = broker;
            LOG.infov("IoT MQTT broker started on {0}:{1}",
                    config.services().iot().mqtt().host(), config.services().iot().mqtt().port());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start IoT MQTT broker", e);
        }
    }

    synchronized void stop() {
        if (server == null) {
            return;
        }
        server.stopServer();
        server = null;
        LOG.info("IoT MQTT broker stopped");
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    void publish(String topic, byte[] payload) {
        Server broker = server;
        if (broker == null) {
            return;
        }
        ByteBuf buffer = Unpooled.wrappedBuffer(payload == null ? new byte[0] : payload);
        MqttPublishMessage message = MqttMessageBuilders.publish()
                .topicName(topic)
                .qos(MqttQoS.AT_MOST_ONCE)
                .retained(false)
                .payload(buffer)
                .properties(MqttProperties.NO_PROPERTIES)
                .build();
        broker.internalPublish(message, INTERNAL_CLIENT_ID);
    }

    boolean disconnectClient(String clientId, boolean cleanSession) {
        Server broker = server;
        if (broker == null) {
            return false;
        }
        boolean disconnected = cleanSession
                ? broker.disconnectAndPurgeClientState(clientId)
                : broker.disconnectClient(clientId);
        return disconnected;
    }

    private final class PublishInterceptor extends AbstractInterceptHandler {
        @Override
        public String getID() {
            return "floci-iot-publish";
        }

        @Override
        public void onPublish(InterceptPublishMessage message) {
            ByteBuf payload = message.getPayload();
            byte[] bytes = new byte[payload.readableBytes()];
            payload.getBytes(payload.readerIndex(), bytes);
            String topic = message.getTopicName();

            if (topic.startsWith("$aws/")) {
                iotService.get().handleReservedMqttPublish(topic, bytes, IotMqttBrokerService.this::publish);
                return;
            }

            iotService.get().publish(topic, bytes);
        }

        @Override
        public void onSessionLoopError(Throwable error) {
            LOG.warnv("IoT MQTT session loop error: {0}", error.getMessage());
        }
    }
}
