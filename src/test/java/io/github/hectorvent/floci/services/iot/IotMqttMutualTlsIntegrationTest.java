package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.IotMqttTlsProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testutil.LogCapture;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.net.ssl.SSLSocketFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.LogRecord;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MQTT over TLS listener admits a device only when its certificate is registered and ACTIVE
 * and a policy attached to it allows {@code iot:Connect} for the client id, evaluated with the
 * connection's policy variables. The plaintext listener stays permissive.
 */
@QuarkusTest
@TestProfile(IotMqttTlsProfile.class)
class IotMqttMutualTlsIntegrationTest {

    static final Path TLS_DIR = Path.of(IotMqttTlsProfile.TLS_DIR);
    static final int PLAIN_PORT = IotMqttTlsProfile.PLAIN_PORT;
    static final int TLS_PORT = IotMqttTlsProfile.TLS_PORT;
    /** The name the device dials, which Paho also sends as the TLS server name. */
    static final String DOMAIN = "localhost";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String OTHER_ACCOUNT = "111100000013";
    private static final String CONNECT_ANY_CLIENT = policy("Allow", "arn:aws:iot:*:*:client/*", null);
    private static final String CONNECT_AS_ATTACHED_THING =
            policy("Allow", "arn:aws:iot:*:*:client/${iot:Connection.Thing.ThingName}", null);

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void activeCertificateWithConnectPolicyIsAdmitted() throws Exception {
        IotDeviceIdentity device = provisionWithPolicy(true, CONNECT_ANY_CLIENT);
        String clientId = "sensor-" + System.nanoTime();

        MqttClient client = connectTls(device, clientId);
        try {
            assertTrue(client.isConnected());
            given()
                .when().get("/connections/{clientId}", clientId)
                .then().statusCode(200).body("connected", equalTo(true));
        } finally {
            client.disconnect();
            client.close();
        }
    }

    @Test
    void mqtt5DeviceWithConnectPolicyIsAdmitted() throws Exception {
        IotDeviceIdentity device = provisionWithPolicy(true, CONNECT_ANY_CLIENT);

        org.eclipse.paho.mqttv5.client.MqttClient client = mqtt5Client("v5-" + System.nanoTime());
        try {
            client.connect(mqtt5Options(device.sslContext(TLS_DIR).getSocketFactory()));
            assertTrue(client.isConnected());
            client.disconnect();
        } finally {
            client.close();
        }
    }

    @Test
    void mqtt5DeviceWithoutPolicyIsRefusedWithTheNotAuthorizedReasonCode() throws Exception {
        IotDeviceIdentity device = IotDeviceIdentity.provision(true, cleanup);

        org.eclipse.paho.mqttv5.client.MqttClient client = mqtt5Client("v5-no-policy-" + System.nanoTime());
        try {
            org.eclipse.paho.mqttv5.common.MqttException refused = assertThrows(
                    org.eclipse.paho.mqttv5.common.MqttException.class,
                    () -> client.connect(mqtt5Options(device.sslContext(TLS_DIR).getSocketFactory())));
            assertEquals(0x87, refused.getReasonCode(), "MQTT 5 CONNACK reason code Not authorized");
        } finally {
            client.close();
        }
    }

    @Test
    void activeCertificateWithoutPolicyIsRefused() {
        IotDeviceIdentity device = IotDeviceIdentity.provision(true, cleanup);

        assertNotAuthorized(() -> connectTls(device, "no-policy-" + System.nanoTime()));
    }

    @Test
    void inactiveCertificateIsRefused() {
        IotDeviceIdentity device = provisionWithPolicy(false, CONNECT_ANY_CLIENT);

        assertNotAuthorized(() -> connectTls(device, "inactive-" + System.nanoTime()));
    }

    @Test
    void deactivatedCertificateIsRefusedOnTheNextConnect() throws Exception {
        IotDeviceIdentity device = provisionWithPolicy(true, CONNECT_ANY_CLIENT);
        MqttClient first = connectTls(device, "deactivate-1-" + System.nanoTime());
        first.disconnect();
        first.close();

        given().queryParam("newStatus", "INACTIVE").when().put("/certificates/" + device.certificateId).then().statusCode(200);

        assertNotAuthorized(() -> connectTls(device, "deactivate-2-" + System.nanoTime()));
    }

    @Test
    void detachedPolicyIsRefusedOnTheNextConnect() throws Exception {
        IotDeviceIdentity device = IotDeviceIdentity.provision(true, cleanup);
        String policy = device.attachNewPolicy(CONNECT_ANY_CLIENT, cleanup);
        MqttClient first = connectTls(device, "detach-1-" + System.nanoTime());
        first.disconnect();
        first.close();

        given().queryParam("target", device.certificateArn).when().post("/target-policies/" + policy).then().statusCode(200);

        assertNotAuthorized(() -> connectTls(device, "detach-2-" + System.nanoTime()));
    }

    @Test
    void unregisteredCertificateIsRefusedEvenThoughTheFlociCaSignedIt() {
        IotDeviceIdentity stranger = IotDeviceIdentity.stranger(TLS_DIR);

        assertNotAuthorized(() -> connectTls(stranger, "stranger-" + System.nanoTime()));
    }

    @Test
    void noClientCertificateIsRefused() {
        assertNotAuthorized(() -> connect("ssl://127.0.0.1:" + TLS_PORT, "anon-" + System.nanoTime(),
                IotDeviceIdentity.trustOnlySslContext(TLS_DIR).getSocketFactory()));
    }

    @Test
    void plaintextListenerStaysPermissive() throws Exception {
        MqttClient client = connect("tcp://127.0.0.1:" + PLAIN_PORT, "plain-" + System.nanoTime(), null);
        assertTrue(client.isConnected());
        client.disconnect();
        client.close();
    }

    @Test
    void aRefusedConnectDoesNotDisconnectTheClientHoldingThatId() throws Exception {
        IotDeviceIdentity device = provisionWithPolicy(true, CONNECT_ANY_CLIENT);
        String clientId = "held-" + System.nanoTime();
        MqttClient holder = connectTls(device, clientId);
        try {
            assertNotAuthorized(() -> connectTls(IotDeviceIdentity.stranger(TLS_DIR), clientId));

            Thread.sleep(200);
            assertTrue(holder.isConnected(), "the admitted session survives a refused connect with its client id");
            given()
                .when().get("/connections/{clientId}", clientId)
                .then().statusCode(200).body("connected", equalTo(true));
        } finally {
            holder.disconnect();
            holder.close();
        }
    }

    @Test
    void thingPolicyVariableAdmitsOnlyTheAttachedThingName() throws Exception {
        IotDeviceIdentity device = provisionWithPolicy(true, CONNECT_AS_ATTACHED_THING);
        String thingName = "thing" + System.nanoTime();
        given().contentType("application/json").body("{}").when().post("/things/" + thingName).then().statusCode(200);
        cleanup.register(() -> given().when().delete("/things/" + thingName).then().statusCode(200));
        given().header("x-amzn-principal", device.certificateArn).when().put("/things/" + thingName + "/principals").then().statusCode(200);
        cleanup.register(() -> given().header("x-amzn-principal", device.certificateArn)
                .when().delete("/things/" + thingName + "/principals").then().statusCode(200));

        MqttClient asThing = connectTls(device, thingName);
        assertTrue(asThing.isConnected());
        asThing.disconnect();
        asThing.close();

        assertNotAuthorized(() -> connectTls(device, "not-" + thingName));
    }

    @Test
    void domainNameConditionSeesTheServerNameTheDeviceSent() throws Exception {
        IotDeviceIdentity device = provisionWithPolicy(true, policy("Allow", "arn:aws:iot:*:*:client/*",
                "{\"StringEquals\":{\"iot:DomainName\":\"" + DOMAIN + "\"}}"));

        MqttClient named = connect("ssl://" + DOMAIN + ":" + TLS_PORT, "sni-" + System.nanoTime(),
                device.sslContext(TLS_DIR).getSocketFactory());
        assertTrue(named.isConnected());
        named.disconnect();
        named.close();

        assertNotAuthorized(() -> connectTls(device, "by-ip-" + System.nanoTime()));
    }

    @Test
    void sourceIpConditionSeesTheClientAddress() throws Exception {
        IotDeviceIdentity local = provisionWithPolicy(true, policy("Allow", "arn:aws:iot:*:*:client/*",
                "{\"IpAddress\":{\"aws:SourceIp\":\"127.0.0.1/32\"}}"));
        IotDeviceIdentity remote = provisionWithPolicy(true, policy("Allow", "arn:aws:iot:*:*:client/*",
                "{\"IpAddress\":{\"aws:SourceIp\":\"10.0.0.0/8\"}}"));

        MqttClient admitted = connectTls(local, "local-" + System.nanoTime());
        assertTrue(admitted.isConnected());
        admitted.disconnect();
        admitted.close();

        assertNotAuthorized(() -> connectTls(remote, "remote-" + System.nanoTime()));
    }

    @Test
    void concurrentConnectsAreEachDecidedOnTheirOwnCertificate() throws Exception {
        int devices = 8;
        List<IotDeviceIdentity> admitted = new ArrayList<>();
        List<IotDeviceIdentity> refused = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            admitted.add(provisionWithPolicy(true, CONNECT_ANY_CLIENT));
            refused.add(IotDeviceIdentity.provision(true, cleanup));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(devices * 2);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < devices; i++) {
                IotDeviceIdentity good = admitted.get(i);
                IotDeviceIdentity bad = refused.get(i);
                outcomes.add(pool.submit(() -> {
                    start.await();
                    MqttClient client = connectTls(good, "burst-ok-" + System.nanoTime());
                    boolean connected = client.isConnected();
                    client.disconnect();
                    client.close();
                    return connected;
                }));
                outcomes.add(pool.submit(() -> {
                    start.await();
                    try {
                        MqttClient client = connectTls(bad, "burst-no-" + System.nanoTime());
                        client.disconnect();
                        client.close();
                        return true;
                    } catch (MqttSecurityException e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int connected = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(30, TimeUnit.SECONDS)) {
                    connected++;
                }
            }
            assertEquals(devices, connected, "exactly the devices with a policy were admitted");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aDevicePublishUsesTheRulesAndRetainedMessagesOfItsCertificatesAccount() throws Exception {
        String authorization = signedBy(OTHER_ACCOUNT, "iot");
        IotDeviceIdentity device = IotDeviceIdentity.provision(true, authorization, cleanup);
        device.attachNewPolicy(CONNECT_ANY_CLIENT, cleanup);
        String topic = "devices/account-" + System.nanoTime() + "/telemetry";
        String deviceQueue = createQueue(signedBy(OTHER_ACCOUNT, "sqs"), "device-account-" + System.nanoTime());
        String defaultQueue = createQueue(null, "default-account-" + System.nanoTime());
        createSqsRule(authorization, "deviceAccountRule" + System.nanoTime(), topic, deviceQueue);
        createSqsRule(null, "defaultAccountRule" + System.nanoTime(), topic, defaultQueue);
        cleanup.register(() -> given().header("Authorization", authorization).contentType("application/octet-stream")
                .queryParam("retain", true).body(new byte[0]).when().post("/topics/" + topic).then().statusCode(200));

        MqttClient publisher = connectTls(device, "account-pub-" + System.nanoTime());
        publisher.publish(topic, "telemetry".getBytes(StandardCharsets.UTF_8), 1, true);
        publisher.disconnect();
        publisher.close();

        assertEquals(List.of("telemetry"), awaitMessages(deviceQueue), "the rule of the device's account fired");
        assertEquals(List.of(), receiveMessages(defaultQueue), "the default account's rule did not");
        given()
            .header("Authorization", authorization)
        .when()
            .get("/retainedMessage/" + topic)
        .then()
            .statusCode(200)
            .body("payload", equalTo(Base64.getEncoder().encodeToString("telemetry".getBytes(StandardCharsets.UTF_8))));
        given().when().get("/retainedMessage/" + topic).then().statusCode(404);

        BlockingQueue<String> deviceAccount = new LinkedBlockingQueue<>();
        BlockingQueue<String> defaultAccount = new LinkedBlockingQueue<>();
        MqttClient subscriber = connectTls(device, "account-sub-" + System.nanoTime());
        MqttClient plain = connect("tcp://127.0.0.1:" + PLAIN_PORT, "default-sub-" + System.nanoTime(), null);
        try {
            subscriber.subscribe(topic, 1, (received, message) -> deviceAccount.add(new String(message.getPayload(), StandardCharsets.UTF_8)));
            plain.subscribe(topic, 1, (received, message) -> defaultAccount.add(new String(message.getPayload(), StandardCharsets.UTF_8)));

            assertEquals("telemetry", deviceAccount.poll(5, TimeUnit.SECONDS), "a session of the device's account gets its retained message");
            assertNull(defaultAccount.poll(1, TimeUnit.SECONDS), "a plaintext session, in the default account, does not");
        } finally {
            subscriber.disconnect();
            subscriber.close();
            plain.disconnect();
            plain.close();
        }
    }

    @Test
    void aDevicePublishRunsAKinesisActionAgainstTheStreamOfTheRulesAccount() throws Exception {
        String authorization = signedBy(OTHER_ACCOUNT, "iot");
        String kinesisAuthorization = signedBy(OTHER_ACCOUNT, "kinesis");
        IotDeviceIdentity device = IotDeviceIdentity.provision(true, authorization, cleanup);
        device.attachNewPolicy(CONNECT_ANY_CLIENT, cleanup);
        String topic = "devices/kinesis-" + System.nanoTime() + "/telemetry";
        String stream = "iot-account-stream-" + System.nanoTime();
        kinesis(kinesisAuthorization, "CreateStream", "{\"StreamName\": \"" + stream + "\", \"ShardCount\": 1}");
        cleanup.register(() -> kinesis(kinesisAuthorization, "DeleteStream", "{\"StreamName\": \"" + stream + "\"}"));
        createRule(authorization, "deviceAccountKinesisRule" + System.nanoTime(), topic,
                "{\"kinesis\": {\"roleArn\": \"arn:aws:iam::000000000000:role/iot-rule-role\", \"streamName\": \"" + stream
                        + "\", \"partitionKey\": \"device\"}}");

        MqttClient publisher = connectTls(device, "kinesis-pub-" + System.nanoTime());
        publisher.publish(topic, "telemetry".getBytes(StandardCharsets.UTF_8), 1, false);
        publisher.disconnect();
        publisher.close();

        assertEquals(List.of(Base64.getEncoder().encodeToString("telemetry".getBytes(StandardCharsets.UTF_8))),
                awaitKinesisRecords(kinesisAuthorization, stream), "the record reached the stream of the rule's account");
    }

    /**
     * DeleteConnection ends the session from an HTTP request in the default account; the Last Will's
     * rule still runs in its own account, and no rule evaluation fails.
     */
    @Test
    void deleteConnectionRunsTheLastWillsKinesisActionInTheRulesAccount() throws Exception {
        String authorization = signedBy(OTHER_ACCOUNT, "iot");
        String kinesisAuthorization = signedBy(OTHER_ACCOUNT, "kinesis");
        IotDeviceIdentity device = IotDeviceIdentity.provision(true, authorization, cleanup);
        device.attachNewPolicy(CONNECT_ANY_CLIENT, cleanup);
        String willTopic = "devices/will-" + System.nanoTime() + "/status";
        String stream = "iot-will-stream-" + System.nanoTime();
        kinesis(kinesisAuthorization, "CreateStream", "{\"StreamName\": \"" + stream + "\", \"ShardCount\": 1}");
        cleanup.register(() -> kinesis(kinesisAuthorization, "DeleteStream", "{\"StreamName\": \"" + stream + "\"}"));
        createRule(authorization, "deviceWillKinesisRule" + System.nanoTime(), willTopic,
                "{\"kinesis\": {\"roleArn\": \"arn:aws:iam::000000000000:role/iot-rule-role\", \"streamName\": \"" + stream
                        + "\", \"partitionKey\": \"device\"}}");
        String clientId = "will-" + System.nanoTime();
        MqttClient client = connect("ssl://127.0.0.1:" + TLS_PORT, clientId, device.sslContext(TLS_DIR).getSocketFactory(),
                willTopic, "offline".getBytes(StandardCharsets.UTF_8));

        AtomicReference<List<String>> records = new AtomicReference<>();
        List<LogRecord> brokerLog;
        try {
            brokerLog = LogCapture.capture(IotMqttBrokerService.class, () -> {
                // Relies on DeleteConnection finding the client id across accounts, so the request's account differs from the rule's.
                given().when().delete("/connections/{clientId}", clientId).then().statusCode(200);
                try {
                    records.set(awaitKinesisRecords(kinesisAuthorization, stream));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for the Last Will's record", e);
                }
            });
        } finally {
            client.close(true);
        }

        assertEquals(List.of(Base64.getEncoder().encodeToString("offline".getBytes(StandardCharsets.UTF_8))),
                records.get(), "the Last Will's record reached the stream of the rule's account");
        assertEquals(List.of(), brokerLog.stream()
                .filter(entry -> entry.getMessage().startsWith("IoT rule evaluation for topic"))
                .map(entry -> entry.getMessage() + " " + entry.getThrown())
                .toList(), "no rule evaluation failed");
    }

    private static String signedBy(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260215/us-east-1/" + service + "/aws4_request, "
                + "SignedHeaders=host, Signature=abc";
    }

    private String createQueue(String authorization, String queueName) {
        return (authorization == null ? given() : given().header("Authorization", authorization))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", queueName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    private void createSqsRule(String authorization, String ruleName, String topic, String queueUrl) {
        createRule(authorization, ruleName, topic,
                "{\"sqs\": {\"roleArn\": \"arn:aws:iam::000000000000:role/iot-rule-role\", \"queueUrl\": \"" + queueUrl + "\"}}");
    }

    /** A rule selecting every message on {@code topic} into its one {@code action}; {@code cleanup} deletes it. */
    private void createRule(String authorization, String ruleName, String topic, String action) {
        RequestSpecification request = authorization == null ? given() : given().header("Authorization", authorization);
        request
            .contentType("application/json")
            .body("""
                {"topicRulePayload": {"sql": "SELECT * FROM '%s'", "actions": [%s]}}
                """.formatted(topic, action))
        .when()
            .put("/rules/" + ruleName)
        .then()
            .statusCode(200);
        cleanup.register(() -> (authorization == null ? given() : given().header("Authorization", authorization))
                .when().delete("/rules/" + ruleName).then().statusCode(200));
    }

    private static JsonNode kinesis(String authorization, String action, String body) {
        String response = given()
            .header("Authorization", authorization)
            .header("X-Amz-Target", "Kinesis_20131202." + action)
            .contentType("application/x-amz-json-1.1")
            .body(body.getBytes(StandardCharsets.UTF_8))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();
        try {
            return OBJECT_MAPPER.readTree(response.isEmpty() ? "{}" : response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Kinesis " + action + " answered " + response, e);
        }
    }

    /** The base64 data of the records on the stream's one shard, once there are any, within five seconds. */
    private static List<String> awaitKinesisRecords(String authorization, String stream) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            String iterator = kinesis(authorization, "GetShardIterator", "{\"StreamName\": \"" + stream
                    + "\", \"ShardId\": \"shardId-000000000000\", \"ShardIteratorType\": \"TRIM_HORIZON\"}").path("ShardIterator").asText();
            List<String> data = new ArrayList<>();
            kinesis(authorization, "GetRecords", "{\"ShardIterator\": \"" + iterator + "\"}").path("Records")
                    .forEach(item -> data.add(item.path("Data").asText()));
            if (!data.isEmpty()) {
                return data;
            }
            Thread.sleep(25);
        }
        return List.of();
    }

    private static List<String> receiveMessages(String queueUrl) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "10")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getList("ReceiveMessageResponse.ReceiveMessageResult.Message.Body", String.class);
    }

    /** The first non-empty receive on the queue within five seconds, as the rule runs on the broker's rule worker. */
    private static List<String> awaitMessages(String queueUrl) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            List<String> received = receiveMessages(queueUrl);
            if (!received.isEmpty()) {
                return received;
            }
            Thread.sleep(25);
        }
        return List.of();
    }

    private IotDeviceIdentity provisionWithPolicy(boolean active, String document) {
        IotDeviceIdentity device = IotDeviceIdentity.provision(active, cleanup);
        device.attachNewPolicy(document, cleanup);
        return device;
    }

    private static MqttClient connectTls(IotDeviceIdentity device, String clientId) throws Exception {
        return connect("ssl://127.0.0.1:" + TLS_PORT, clientId, device.sslContext(TLS_DIR).getSocketFactory());
    }

    private static MqttClient connect(String uri, String clientId, SSLSocketFactory socketFactory) throws Exception {
        return connect(uri, clientId, socketFactory, null, null);
    }

    /** Connects with a QoS 1 Last Will on {@code willTopic} when it is not null. */
    private static MqttClient connect(String uri, String clientId, SSLSocketFactory socketFactory,
                                      String willTopic, byte[] willPayload) throws Exception {
        MqttClient client = new MqttClient(uri, clientId, new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setAutomaticReconnect(false);
        if (socketFactory != null) {
            options.setSocketFactory(socketFactory);
        }
        if (willTopic != null) {
            options.setWill(willTopic, willPayload, 1, false);
        }
        try {
            client.connect(options);
        } catch (MqttException e) {
            client.close();
            throw e;
        }
        return client;
    }

    private static org.eclipse.paho.mqttv5.client.MqttClient mqtt5Client(String clientId) throws Exception {
        return new org.eclipse.paho.mqttv5.client.MqttClient("ssl://127.0.0.1:" + TLS_PORT, clientId, null);
    }

    private static MqttConnectionOptions mqtt5Options(SSLSocketFactory socketFactory) {
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setCleanStart(true);
        options.setConnectionTimeout(10);
        options.setAutomaticReconnect(false);
        options.setSocketFactory(socketFactory);
        return options;
    }

    private interface Connect {
        MqttClient run() throws Exception;
    }

    /** The broker answered CONNACK not authorized (return code 5), which Paho reports as a security exception. */
    private static void assertNotAuthorized(Connect connect) {
        MqttException refused = assertThrows(MqttException.class, () -> {
            MqttClient client = connect.run();
            client.disconnect();
            client.close();
        });
        assertInstanceOf(MqttSecurityException.class, refused, "expected CONNACK not authorized, got: " + refused);
        assertEquals(MqttException.REASON_CODE_NOT_AUTHORIZED, refused.getReasonCode());
    }

    private static String policy(String effect, String resource, String condition) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"" + effect + "\",\"Action\":\"iot:Connect\","
                + "\"Resource\":\"" + resource + "\"" + (condition == null ? "" : ",\"Condition\":" + condition) + "}]}";
    }
}
