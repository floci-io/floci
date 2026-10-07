package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Custom authorizers on the real routes and broker: {@code TestInvokeAuthorizer} on its REST-JSON
 * path, and an MQTT CONNECT on the TCP listener or over WebSocket admitted or refused by the
 * authorizer's answer the way AWS IoT does it (MQTT 3.1.1 closed without a CONNACK, MQTT 5
 * answered with reason 135). The function is mocked and decides by the MQTT password; what it
 * received is asserted as the event.
 */
@QuarkusTest
@TestProfile(IotMqttWebSocketIntegrationTest.Profile.class)
class IotCustomAuthorizerIntegrationTest {

    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:it-custom-auth";
    private static final String NAME_PARAM = "x-amz-customauthorizer-name=";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int testHttpPort;

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int testSslPort;

    @InjectMock
    LambdaService lambdaService;

    private final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
    private String authorizer;

    @BeforeEach
    void functionDecidesByThePassword() {
        when(lambdaService.invokeArn(eq(FUNCTION_ARN), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    JsonNode event = MAPPER.readTree((byte[]) invocation.getArgument(1));
                    events.add(event);
                    JsonNode mqtt = event.path("protocolData").path("mqtt");
                    String secret = new String(Base64.getDecoder().decode(mqtt.path("password").asText("")),
                            StandardCharsets.UTF_8);
                    String answer = switch (secret) {
                        case "allow" -> allow(mqtt.path("clientId").asText());
                        case "garbage" -> "{\"foo\": 1}";
                        default -> "{\"isAuthenticated\": false, \"principalId\": \"itprincipal\", \"policyDocuments\": []}";
                    };
                    return new InvokeResult(200, null, answer.getBytes(StandardCharsets.UTF_8), null, "request-id");
                });
        authorizer = "it-cauth-" + System.nanoTime();
        given().contentType("application/json")
            .body("{\"authorizerFunctionArn\": \"" + FUNCTION_ARN + "\", \"signingDisabled\": true, \"status\": \"ACTIVE\"}")
        .when().post("/authorizer/" + authorizer)
        .then().statusCode(200);
    }

    @Test
    void testInvokeAuthorizerAnswersWithTheVerdictPoliciesAndDefaultedTtls() {
        given().contentType("application/json")
            .body("{\"mqttContext\": {\"username\": \"c1?" + NAME_PARAM + authorizer + "\", \"password\": \""
                    + base64("allow") + "\", \"clientId\": \"c1\"}}")
        .when().post("/authorizer/" + authorizer + "/test")
        .then()
            .statusCode(200)
            .body(equalTo("{\"disconnectAfterInSeconds\":86400,\"isAuthenticated\":true,\"policyDocuments\":["
                    + MAPPER.valueToTree(policy("c1")) + "],\"principalId\":\"itprincipal\",\"refreshAfterInSeconds\":300}"));
    }

    @Test
    void testInvokeAuthorizerReportsAnInvalidAnswerAsInvalidResponseException() {
        given().contentType("application/json")
            .body("{\"mqttContext\": {\"clientId\": \"c1\", \"password\": \"" + base64("garbage") + "\"}}")
        .when().post("/authorizer/" + authorizer + "/test")
        .then()
            .statusCode(400)
            .header("X-Amzn-ErrorType", "InvalidResponseException")
            .body("message", equalTo("Lambda invocation result is in invalid format"));
    }

    @Test
    void testInvokeAuthorizerOnAMissingAuthorizerIsNotFound() {
        given().contentType("application/json").body("{\"httpContext\": {}}")
        .when().post("/authorizer/it-cauth-missing/test")
        .then()
            .statusCode(404)
            .header("X-Amzn-ErrorType", "ResourceNotFoundException")
            .body("message", equalTo("Authorizer it-cauth-missing not found"));
    }

    @Test
    void aTestInvokeBodyWithTrailingContentIsAnInvalidRequest() {
        given().contentType("application/json").body("{\"mqttContext\": {}} trailing")
        .when().post("/authorizer/" + authorizer + "/test")
        .then()
            .statusCode(400)
            .header("X-Amzn-ErrorType", "InvalidRequestException");
    }

    @Test
    void mqtt3AllowedConnectSendsTheWebSocketEventWithTheUsernameVerbatim() throws Exception {
        String clientId = "cauth-v3-" + System.nanoTime();
        String username = clientId + "?" + NAME_PARAM + authorizer;

        connectV3(ws(""), clientId, username, "allow", Map.of()).disconnect();

        JsonNode event = takeEvent();
        assertEquals(List.of("http", "mqtt"), protocols(event));
        JsonNode headers = event.path("protocolData").path("http").path("headers");
        headers.fieldNames().forEachRemaining(name -> assertEquals(name.toLowerCase(Locale.ROOT), name, "header names are lower-cased"));
        assertTrue(headers.path("sec-websocket-protocol").asText().contains("mqtt"), headers.toString());
        assertEquals("", event.path("protocolData").path("http").path("queryString").asText(null));
        assertEquals(username, event.path("protocolData").path("mqtt").path("username").asText());
        assertEquals(base64("allow"), event.path("protocolData").path("mqtt").path("password").asText());
        assertEquals(clientId, event.path("protocolData").path("mqtt").path("clientId").asText());
        assertFalse(event.path("signatureVerified").asBoolean(true));
    }

    @Test
    void mqtt3RefusedConnectIsClosedWithoutAConnack() {
        String clientId = "cauth-v3-deny-" + System.nanoTime();

        MqttException refused = assertThrows(MqttException.class,
                () -> connectV3(ws(""), clientId, clientId + "?" + NAME_PARAM + authorizer, "deny", Map.of()));

        assertEquals(MqttException.REASON_CODE_CONNECTION_LOST, refused.getReasonCode());
    }

    @Test
    void mqtt3ConnectForAClientIdThePolicyDoesNotCoverIsRefused() {
        String clientId = "cauth-v3-other-" + System.nanoTime();
        when(lambdaService.invokeArn(eq(FUNCTION_ARN), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, null, allow("someone-else").getBytes(StandardCharsets.UTF_8), null, "id"));

        MqttException refused = assertThrows(MqttException.class,
                () -> connectV3(ws(""), clientId, clientId + "?" + NAME_PARAM + authorizer, "allow", Map.of()));

        assertEquals(MqttException.REASON_CODE_CONNECTION_LOST, refused.getReasonCode());
    }

    @Test
    void mqtt3ConnectNamingAnUnknownAuthorizerIsRefusedWithoutInvokingAnyFunction() {
        String clientId = "cauth-v3-unknown-" + System.nanoTime();

        MqttException refused = assertThrows(MqttException.class,
                () -> connectV3(ws(""), clientId, clientId + "?" + NAME_PARAM + "it-cauth-nope", "allow", Map.of()));

        assertEquals(MqttException.REASON_CODE_CONNECTION_LOST, refused.getReasonCode());
        verify(lambdaService, never()).invokeArn(anyString(), any(byte[].class), any(InvocationType.class));
    }

    @Test
    void mqtt5AllowedConnectWithTheNameInTheUrlQueryConnects() throws Exception {
        String clientId = "cauth-v5-" + System.nanoTime();

        MqttAsyncClient client = connectV5(ws("?" + NAME_PARAM + authorizer), clientId, clientId, "allow");
        client.disconnect().waitForCompletion(5000);
        client.close();

        JsonNode event = takeEvent();
        assertEquals(NAME_PARAM + authorizer, event.path("protocolData").path("http").path("queryString").asText());
        assertEquals(clientId, event.path("protocolData").path("mqtt").path("username").asText());
    }

    @Test
    void mqtt5RefusedConnectGetsReasonCode135() {
        String clientId = "cauth-v5-deny-" + System.nanoTime();

        org.eclipse.paho.mqttv5.common.MqttException refused = assertThrows(org.eclipse.paho.mqttv5.common.MqttException.class,
                () -> connectV5(ws(""), clientId, clientId + "?" + NAME_PARAM + authorizer, "deny"));

        assertEquals(135, refused.getReasonCode());
    }

    @Test
    void theNameInTheUpgradeHeaderIsSentAmongTheLowerCasedHeaders() throws Exception {
        String clientId = "cauth-header-" + System.nanoTime();

        connectV3(ws(""), clientId, clientId, "allow", Map.of("X-Amz-CustomAuthorizer-Name", authorizer)).disconnect();

        assertEquals(authorizer, takeEvent().path("protocolData").path("http").path("headers")
                .path("x-amz-customauthorizer-name").asText());
    }

    @Test
    void overWssTheEventCarriesTheServerName() throws Exception {
        String clientId = "cauth-wss-" + System.nanoTime();

        connectV3("wss://localhost:" + testSslPort + "/mqtt", clientId, clientId + "?" + NAME_PARAM + authorizer, "allow",
                Map.of()).disconnect();

        JsonNode event = takeEvent();
        assertEquals(List.of("tls", "http", "mqtt"), protocols(event));
        assertEquals("localhost", event.path("protocolData").path("tls").path("serverName").asText());
        List<String> keys = new ArrayList<>();
        event.path("protocolData").fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("tls", "mqtt", "http"), keys);
    }

    @Test
    void overWssToAnAddressLiteralTheEventCarriesNoServerName() throws Exception {
        String clientId = "cauth-wss-ip-" + System.nanoTime();

        connectV3("wss://127.0.0.1:" + testSslPort + "/mqtt", clientId, clientId + "?" + NAME_PARAM + authorizer, "allow",
                Map.of()).disconnect();

        JsonNode event = takeEvent();
        assertEquals(List.of("http", "mqtt"), protocols(event));
        assertFalse(event.path("protocolData").has("tls"), event.path("protocolData").toString());
    }

    @Test
    void mqtt3AllowedConnectOnTheTcpListenerSendsAnMqttOnlyEvent() throws Exception {
        String clientId = "cauth-tcp-v3-" + System.nanoTime();
        String username = clientId + "?" + NAME_PARAM + authorizer;

        connectV3(tcp(), clientId, username, "allow", Map.of()).disconnect();

        JsonNode event = takeEvent();
        assertEquals(List.of("mqtt"), protocols(event));
        JsonNode protocolData = event.path("protocolData");
        assertFalse(protocolData.has("http"), protocolData.toString());
        assertFalse(protocolData.has("tls"), protocolData.toString());
        assertEquals(username, protocolData.path("mqtt").path("username").asText());
        assertEquals(base64("allow"), protocolData.path("mqtt").path("password").asText());
        assertEquals(clientId, protocolData.path("mqtt").path("clientId").asText());
    }

    @Test
    void mqtt5RefusedConnectOnTheTcpListenerGetsReasonCode135() throws Exception {
        String clientId = "cauth-tcp-v5-deny-" + System.nanoTime();

        org.eclipse.paho.mqttv5.common.MqttException refused = assertThrows(org.eclipse.paho.mqttv5.common.MqttException.class,
                () -> connectV5(tcp(), clientId, clientId + "?" + NAME_PARAM + authorizer, "deny"));

        assertEquals(135, refused.getReasonCode());
        assertEquals(base64("deny"), takeEvent().path("protocolData").path("mqtt").path("password").asText());
    }

    // Helpers

    private MqttClient connectV3(String url, String clientId, String username, String password, Map<String, String> headers)
            throws Exception {
        MqttClient client = new MqttClient(url, clientId, new MemoryPersistence());
        client.setTimeToWait(10_000);
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setUserName(username);
        options.setPassword(password.toCharArray());
        if (!headers.isEmpty()) {
            Properties custom = new Properties();
            custom.putAll(headers);
            options.setCustomWebSocketHeaders(custom);
        }
        if (url.startsWith("wss://")) {
            options.setSocketFactory(IotMqttWebSocketIntegrationTest.trustOnlyFlociCa().getSocketFactory());
        }
        try {
            client.connect(options);
        } catch (MqttException e) {
            client.close();
            throw e;
        }
        return client;
    }

    private MqttAsyncClient connectV5(String url, String clientId, String username, String password)
            throws org.eclipse.paho.mqttv5.common.MqttException {
        MqttAsyncClient client = new MqttAsyncClient(url, clientId, new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setCleanStart(true);
        options.setConnectionTimeout(10);
        options.setUserName(username);
        options.setPassword(password.getBytes(StandardCharsets.UTF_8));
        try {
            client.connect(options).waitForCompletion(10_000);
        } catch (org.eclipse.paho.mqttv5.common.MqttException e) {
            client.close(true);
            throw e;
        }
        return client;
    }

    private String ws(String query) {
        return "ws://127.0.0.1:" + testHttpPort + "/mqtt" + query;
    }

    private static String tcp() {
        return "tcp://127.0.0.1:" + IotMqttWebSocketIntegrationTest.PLAIN_PORT;
    }

    private JsonNode takeEvent() throws InterruptedException {
        JsonNode event = events.poll(10, TimeUnit.SECONDS);
        assertNotNull(event, "the authorizer function was not invoked");
        return event;
    }

    private static List<String> protocols(JsonNode event) {
        List<String> protocols = new ArrayList<>();
        event.path("protocols").forEach(protocol -> protocols.add(protocol.asText()));
        return protocols;
    }

    private static String allow(String clientId) {
        return "{\"isAuthenticated\": true, \"principalId\": \"itprincipal\", \"policyDocuments\": ["
                + MAPPER.valueToTree(policy(clientId)) + "]}";
    }

    private static String policy(String clientId) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"iot:Connect\","
                + "\"Resource\":\"arn:aws:iot:us-east-1:000000000000:client/" + clientId + "\"}]}";
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
