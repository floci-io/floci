package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.testing.IotMqttTlsProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.net.ssl.SSLSocketFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    private IotDeviceIdentity provisionWithPolicy(boolean active, String document) {
        IotDeviceIdentity device = IotDeviceIdentity.provision(active, cleanup);
        device.attachNewPolicy(document, cleanup);
        return device;
    }

    private static MqttClient connectTls(IotDeviceIdentity device, String clientId) throws Exception {
        return connect("ssl://127.0.0.1:" + TLS_PORT, clientId, device.sslContext(TLS_DIR).getSocketFactory());
    }

    private static MqttClient connect(String uri, String clientId, SSLSocketFactory socketFactory) throws Exception {
        MqttClient client = new MqttClient(uri, clientId, new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setAutomaticReconnect(false);
        if (socketFactory != null) {
            options.setSocketFactory(socketFactory);
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
