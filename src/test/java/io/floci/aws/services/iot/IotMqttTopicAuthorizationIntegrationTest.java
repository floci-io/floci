package io.floci.aws.services.iot;

import io.floci.aws.testing.IotMqttTlsProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.iot.IotMqttMutualTlsIntegrationTest.connect;
import static io.floci.aws.services.iot.IotMqttMutualTlsIntegrationTest.connectTls;
import static io.floci.aws.services.iot.IotMqttMutualTlsIntegrationTest.mqtt5Options;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IotMqttTlsProfile.class)
class IotMqttTopicAuthorizationIntegrationTest {

    private static final Path TLS_DIR = Path.of(IotMqttTlsProfile.TLS_DIR);
    private static final int NOT_AUTHORIZED = 0x87;
    private static final String THING_TOPICS_ONLY = "{\"Version\":\"2012-10-17\",\"Statement\":["
            + "{\"Effect\":\"Allow\",\"Action\":\"iot:Connect\",\"Resource\":\"arn:aws:iot:*:*:client/${iot:Connection.Thing.ThingName}\"},"
            + "{\"Effect\":\"Allow\",\"Action\":[\"iot:Publish\",\"iot:Receive\"],"
            + "\"Resource\":\"arn:aws:iot:*:*:topic/things/${iot:Connection.Thing.ThingName}/*\"},"
            + "{\"Effect\":\"Allow\",\"Action\":\"iot:Subscribe\","
            + "\"Resource\":\"arn:aws:iot:*:*:topicfilter/things/${iot:Connection.Thing.ThingName}/*\"}]}";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aDeviceSubscribesPublishesAndReceivesOnItsOwnThingTopics() throws Exception {
        String thing = "a" + System.nanoTime();
        IotDeviceIdentity device = thingDevice(thing);
        byte[] payload = "own".getBytes(StandardCharsets.UTF_8);

        MqttClient client = connectTls(device, thing);
        try {
            CompletableFuture<byte[]> received = new CompletableFuture<>();
            client.subscribe("things/" + thing + "/#", 1, (topic, message) -> received.complete(message.getPayload()));
            client.publish("things/" + thing + "/telemetry", payload, 1, false);

            assertArrayEquals(payload, received.get(10, TimeUnit.SECONDS));
        } finally {
            closeV3(client);
        }
    }

    @Test
    void mqtt3SubscribeToAnotherThingsTopicClosesTheConnection() throws Exception {
        String thing = "a" + System.nanoTime();
        MqttClient client = connectTls(thingDevice(thing), thing);
        try {
            assertThrows(MqttException.class, () -> client.subscribe("things/b" + thing + "/#", 1));
            assertFalse(client.isConnected());
        } finally {
            closeV3(client);
        }
    }

    @Test
    void mqtt3PublishToAnotherThingsTopicClosesTheConnectionUndelivered() throws Exception {
        String thing = "a" + System.nanoTime();
        String otherTopic = "things/b" + thing + "/telemetry";
        MqttClient listener = connectPlain("listener-" + System.nanoTime());
        MqttClient client = connectTls(thingDevice(thing), thing);
        try {
            BlockingQueue<String> received = new LinkedBlockingQueue<>();
            listener.subscribe(otherTopic, 1, (topic, message) -> received.add(topic));

            assertThrows(MqttException.class, () -> client.publish(otherTopic, "x".getBytes(StandardCharsets.UTF_8), 1, false));

            assertFalse(client.isConnected());
            assertNull(received.poll(1, TimeUnit.SECONDS));
        } finally {
            closeV3(client);
            closeV3(listener);
        }
    }

    @Test
    void mqtt5DeniedSubscribeAndQos1PublishAreAnsweredNotAuthorizedAndTheSessionStays() throws Exception {
        String thing = "a" + System.nanoTime();
        DisconnectRecorder recorder = new DisconnectRecorder();
        MqttAsyncClient client = connectV5(thingDevice(thing), thing, recorder);
        try {
            IMqttToken subscribe = client.subscribe("things/b" + thing + "/#", 1);
            subscribe.waitForCompletion(10_000);
            assertEquals(NOT_AUTHORIZED, subscribe.getReasonCodes()[0]);

            IMqttToken publish = client.publish("things/b" + thing + "/telemetry", "x".getBytes(StandardCharsets.UTF_8), 1, false);
            publish.waitForCompletion(10_000);
            assertEquals(NOT_AUTHORIZED, publish.getReasonCodes()[0]);

            IMqttToken own = client.subscribe("things/" + thing + "/#", 1);
            own.waitForCompletion(10_000);
            assertEquals(1, own.getReasonCodes()[0]);
            assertTrue(client.isConnected());
        } finally {
            closeV5(client);
        }
    }

    @Test
    void mqtt5DeniedQos0PublishIsDisconnectedNotAuthorized() throws Exception {
        String thing = "a" + System.nanoTime();
        DisconnectRecorder recorder = new DisconnectRecorder();
        MqttAsyncClient client = connectV5(thingDevice(thing), thing, recorder);
        try {
            client.publish("things/b" + thing + "/telemetry", "x".getBytes(StandardCharsets.UTF_8), 0, false);

            MqttDisconnectResponse disconnect = recorder.disconnects.poll(10, TimeUnit.SECONDS);
            assertEquals(NOT_AUTHORIZED, disconnect.getReturnCode());
        } finally {
            closeV5(client);
        }
    }

    @Test
    void aSubscriberReceivesOnlyTheTopicsItsPolicyAllowsReceiveOn() throws Exception {
        String prefix = "receive/" + System.nanoTime();
        IotDeviceIdentity device = IotDeviceIdentity.provision(true, cleanup);
        device.attachNewPolicy(receiveOnly(prefix + "/ok"), cleanup);
        MqttClient subscriber = connectTls(device, "sub-" + System.nanoTime());
        MqttClient publisher = connectPlain("pub-" + System.nanoTime());
        try {
            BlockingQueue<String> received = new LinkedBlockingQueue<>();
            subscriber.subscribe(prefix + "/+", 1, (topic, message) -> received.add(topic));

            publisher.publish(prefix + "/denied", "x".getBytes(StandardCharsets.UTF_8), 1, false);
            publisher.publish(prefix + "/ok", "x".getBytes(StandardCharsets.UTF_8), 1, false);

            assertEquals(prefix + "/ok", received.poll(10, TimeUnit.SECONDS));
            assertNull(received.poll(500, TimeUnit.MILLISECONDS));
            assertTrue(subscriber.isConnected());
        } finally {
            closeV3(publisher);
            closeV3(subscriber);
        }
    }

    @Test
    void retainedMessagesAreDeliveredOnlyOnTopicsThePolicyAllowsReceiveOn() throws Exception {
        String prefix = "retained/" + System.nanoTime();
        IotDeviceIdentity device = IotDeviceIdentity.provision(true, cleanup);
        device.attachNewPolicy(receiveOnly(prefix + "/ok"), cleanup);
        MqttClient publisher = connectPlain("pub-" + System.nanoTime());
        MqttClient subscriber = null;
        try {
            publisher.publish(prefix + "/denied", "x".getBytes(StandardCharsets.UTF_8), 1, true);
            publisher.publish(prefix + "/ok", "x".getBytes(StandardCharsets.UTF_8), 1, true);
            subscriber = connectTls(device, "sub-" + System.nanoTime());
            BlockingQueue<String> received = new LinkedBlockingQueue<>();
            subscriber.subscribe(prefix + "/+", 1, (topic, message) -> received.add(topic));

            assertEquals(prefix + "/ok", received.poll(10, TimeUnit.SECONDS));
            assertNull(received.poll(500, TimeUnit.MILLISECONDS));
        } finally {
            if (subscriber != null) {
                closeV3(subscriber);
            }
            closeV3(publisher);
        }
    }

    @Test
    void aNewDefaultPolicyVersionAppliesToAnEstablishedSession() throws Exception {
        String prefix = "revoke/" + System.nanoTime();
        IotDeviceIdentity device = IotDeviceIdentity.provision(true, cleanup);
        String policy = device.attachNewPolicy(receiveOnly("*"), cleanup);
        MqttClient subscriber = connectTls(device, "sub-" + System.nanoTime());
        MqttClient publisher = connectPlain("pub-" + System.nanoTime());
        try {
            BlockingQueue<String> received = new LinkedBlockingQueue<>();
            subscriber.subscribe(prefix + "/+", 1, (topic, message) -> received.add(topic));
            given().contentType("application/json").body(Map.of("policyDocument", receiveOnly(prefix + "/kept")))
                    .queryParam("setAsDefault", true)
                    .when().post("/policies/" + policy + "/version").then().statusCode(200);

            publisher.publish(prefix + "/revoked", "x".getBytes(StandardCharsets.UTF_8), 1, false);
            publisher.publish(prefix + "/kept", "x".getBytes(StandardCharsets.UTF_8), 1, false);

            assertEquals(prefix + "/kept", received.poll(10, TimeUnit.SECONDS));
            assertNull(received.poll(500, TimeUnit.MILLISECONDS));
        } finally {
            closeV3(publisher);
            closeV3(subscriber);
        }
    }

    private static String receiveOnly(String topic) {
        String resource = topic.equals("*") ? "*" : "arn:aws:iot:*:*:topic/" + topic;
        return "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":[\"iot:Connect\",\"iot:Subscribe\"],\"Resource\":\"*\"},"
                + "{\"Effect\":\"Allow\",\"Action\":\"iot:Receive\",\"Resource\":\"" + resource + "\"}]}";
    }

    private IotDeviceIdentity thingDevice(String thing) {
        IotDeviceIdentity device = IotDeviceIdentity.provision(true, cleanup);
        device.attachNewPolicy(THING_TOPICS_ONLY, cleanup);
        device.attachNewThing(thing, cleanup);
        return device;
    }

    private static MqttClient connectPlain(String clientId) throws Exception {
        return connect("tcp://127.0.0.1:" + IotMqttTlsProfile.PLAIN_PORT, clientId, null);
    }

    private static void closeV3(MqttClient client) {
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException ignored) {
        }
    }

    private static MqttAsyncClient connectV5(IotDeviceIdentity device, String clientId, MqttCallback callback) throws Exception {
        MqttAsyncClient client = new MqttAsyncClient("ssl://127.0.0.1:" + IotMqttTlsProfile.TLS_PORT, clientId,
                new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        client.setCallback(callback);
        client.connect(mqtt5Options(device.sslContext(TLS_DIR).getSocketFactory())).waitForCompletion(10_000);
        return client;
    }

    private static void closeV5(MqttAsyncClient client) {
        try {
            if (client.isConnected()) {
                client.disconnect().waitForCompletion(5_000);
            }
            client.close();
        } catch (org.eclipse.paho.mqttv5.common.MqttException ignored) {
        }
    }

    private static final class DisconnectRecorder implements MqttCallback {
        final BlockingQueue<MqttDisconnectResponse> disconnects = new LinkedBlockingQueue<>();

        @Override
        public void disconnected(MqttDisconnectResponse response) {
            disconnects.add(response);
        }

        @Override
        public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
        }

        @Override
        public void deliveryComplete(IMqttToken token) {
        }

        @Override
        public void connectComplete(boolean reconnect, String serverUri) {
        }

        @Override
        public void authPacketArrived(int reasonCode, MqttProperties properties) {
        }
    }
}
