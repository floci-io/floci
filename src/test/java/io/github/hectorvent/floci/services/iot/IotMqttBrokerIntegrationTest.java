package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the embedded MQTT broker starts with plain and TLS listeners, advertises its
 * (TLS) address via {@code DescribeEndpoint}, and routes every CONNECT through the custom
 * authorizer. A successful end-to-end connect additionally requires a deployed authorizer
 * Lambda (Docker) and is exercised manually — see docs/services/iot.md.
 */
@QuarkusTest
@TestProfile(IotMqttBrokerIntegrationTest.BrokerEnabledProfile.class)
class IotMqttBrokerIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iot/aws4_request";

    @Inject
    IotBrokerEndpoint brokerEndpoint;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void brokerIsRunningWithTlsListeners() {
        assertTrue(brokerEndpoint.isRunning(), "broker should be running under the broker-enabled profile");
        assertTrue(brokerEndpoint.wsPort() > 0);
        assertTrue(brokerEndpoint.tcpPort() > 0);
        assertTrue(brokerEndpoint.tlsEnabled(), "TLS listeners should be active");
        assertTrue(brokerEndpoint.wssPort() > 0);
        assertTrue(brokerEndpoint.mqttsPort() > 0);
    }

    @Test
    void describeEndpointReturnsSecureBrokerAddress() {
        String expected = brokerEndpoint.advertisedHost() + ":" + brokerEndpoint.wssPort();
        given()
            .header("Authorization", AUTH)
            .queryParam("endpointType", "iot:Data-ATS")
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", equalTo(expected));
    }

    @Test
    void publishViaRestReturns200() {
        // HTTP data-plane Publish: POST /topics/{topic} injects the message into the broker.
        given()
            .header("Authorization", AUTH)
            .contentType("application/octet-stream")
            .queryParam("qos", 1)
            .body("{\"temp\":22.5}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .when()
            .post("/topics/devices/sensor-1/data")
        .then()
            .statusCode(200);
    }

    @Test
    void plainConnectWithoutValidAuthorizerIsRejected() {
        MqttException ex = assertThrows(MqttException.class,
                () -> connect("tcp://localhost:" + brokerEndpoint.tcpPort(), null));
        assertAuthRefused(ex);
    }

    @Test
    void tlsConnectCompletesHandshakeThenAuthRefuses() throws Exception {
        MqttException ex = assertThrows(MqttException.class,
                () -> connect("ssl://localhost:" + brokerEndpoint.mqttsPort(), trustAllSocketFactory()));
        // A reason code of bad-credentials / not-authorized proves the TLS handshake succeeded
        // and the broker reached the custom-authorizer stage (rather than failing at TLS).
        assertAuthRefused(ex);
    }

    @Test
    void webSocketConnectThroughBridgeIsRefused() {
        // Exercises the Vert.x ws->tcp bridge end to end: the MQTT CONNECT is reassembled and
        // delivered to Moquette + the custom authorizer, which refuses the unknown authorizer, so
        // the connection does not succeed. (The exact CONNACK reason code over a WebSocket that the
        // broker closes immediately is client-specific; the authenticated success path and the
        // precise deny code are verified with mqtt.js — see docs/services/iot.md.)
        assertThrows(MqttException.class,
                () -> connect("ws://localhost:" + brokerEndpoint.wsPort() + "/mqtt", null));
    }

    @Test
    void secureWebSocketConnectThroughBridgeIsRefused() throws Exception {
        var factory = trustAllSocketFactory();
        assertThrows(MqttException.class,
                () -> connect("wss://localhost:" + brokerEndpoint.wssPort() + "/mqtt", factory));
    }

    private void connect(String url, javax.net.ssl.SSLSocketFactory socketFactory) throws MqttException {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setConnectionTimeout(5);
        options.setUserName("dev?x-amz-customauthorizer-name=ghost-authorizer&MyToken=abc");
        options.setPassword("pw".toCharArray());
        if (socketFactory != null) {
            options.setSocketFactory(socketFactory);
        }
        MqttClient client = new MqttClient(url, MqttClient.generateClientId(), new MemoryPersistence());
        try {
            client.connect(options);
        } finally {
            client.close();
        }
    }

    private static void assertAuthRefused(MqttException ex) {
        int code = ex.getReasonCode();
        assertTrue(code == MqttException.REASON_CODE_FAILED_AUTHENTICATION
                        || code == MqttException.REASON_CODE_NOT_AUTHORIZED,
                "expected an auth-refused reason code (4 or 5) but was " + code);
    }

    private static javax.net.ssl.SSLSocketFactory trustAllSocketFactory() throws Exception {
        TrustManager[] trustAll = {
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        return ctx.getSocketFactory();
    }

    public static final class BrokerEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.iot.broker-enabled", "true",
                    "floci.services.iot.broker-tcp-base-port", "11883",
                    "floci.services.iot.broker-tcp-max-port", "11899",
                    "floci.services.iot.broker-ws-base-port", "18083",
                    "floci.services.iot.broker-ws-max-port", "18099",
                    "floci.services.iot.broker-mqtts-base-port", "18883",
                    "floci.services.iot.broker-mqtts-max-port", "18899",
                    "floci.services.iot.broker-wss-base-port", "18443",
                    "floci.services.iot.broker-wss-max-port", "18459");
        }
    }
}
