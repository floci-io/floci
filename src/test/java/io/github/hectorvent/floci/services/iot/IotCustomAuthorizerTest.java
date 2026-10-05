package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iot.IotCustomAuthorizer.WebSocketUpgrade;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The custom authorizer decision as AWS IoT makes it, with the function mocked: the event each
 * request or connection produces, the checks on the function's answer in AWS's order and wording,
 * and token signature verification.
 */
class IotCustomAuthorizerTest {

    private static final String REGION = "us-east-1";
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:auth";
    private static final String POLICY = "{\"Version\": \"2012-10-17\", \"Statement\": [{\"Effect\": \"Allow\", "
            + "\"Action\": \"iot:Connect\", \"Resource\": \"*\"}]}";
    private static final String NAME_PARAM = "x-amz-customauthorizer-name=";
    private static final String SIGNATURE_PARAM = "x-amz-customauthorizer-signature=";
    private static final String PRINCIPAL_CONSTRAINT =
            " at principalId failed to satisfy constraint: Member must satisfy ([a-zA-Z0-9]){1,128}";
    private static final String TTL_CONSTRAINT =
            " failed to satisfy constraint: Member must be greater than 5 minutes and less than 24 hours.";

    private static KeyPair key1;
    private static KeyPair key2;
    private static KeyPair unregistered;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
    private final IotAuthorizerService authorizers = new IotAuthorizerService(new InMemoryStorage<>(), regionResolver);
    private final LambdaService lambda = mock(LambdaService.class);
    private final IotCustomAuthorizer customAuthorizer = new IotCustomAuthorizer(authorizers, lambda, mapper, regionResolver);
    private final List<JsonNode> events = new ArrayList<>();
    private String answer = response("[" + quoted(POLICY) + "]", "\"refreshAfterInSeconds\": 600, \"disconnectAfterInSeconds\": 3600");
    private String functionError;

    @BeforeAll
    static void generateKeys() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key1 = generator.generateKeyPair();
        key2 = generator.generateKeyPair();
        unregistered = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        when(lambda.invokeArn(eq(FUNCTION_ARN), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    events.add(mapper.readTree((byte[]) invocation.getArgument(1)));
                    return new InvokeResult(200, functionError, answer.getBytes(StandardCharsets.UTF_8), null, "request-id");
                });
        create("a", "ACTIVE", false);
        create("s", "ACTIVE", true);
        create("off", "INACTIVE", false);
    }

    // TestInvokeAuthorizer

    @Test
    void testInvokeReturnsTheVerdictWithPoliciesVerbatimAndSendsTheMqttFieldsAsGiven() {
        ObjectNode response = customAuthorizer.testInvoke("a", request("""
                {"mqttContext": {"username": "c?x-amz-customauthorizer-name=a", "password": "c2VjcmV0", "clientId": "c"}}
                """), REGION);

        assertEquals("{\"disconnectAfterInSeconds\":3600,\"isAuthenticated\":true,\"policyDocuments\":["
                + quoted(POLICY) + "],\"principalId\":\"p1\",\"refreshAfterInSeconds\":600}", response.toString());
        assertEquals("{\"protocolData\":{\"mqtt\":{\"username\":\"c?x-amz-customauthorizer-name=a\",\"password\":\"c2VjcmV0\","
                + "\"clientId\":\"c\"}},\"protocols\":[\"mqtt\"],\"signatureVerified\":false,"
                + "\"connectionMetadata\":{\"id\":\"ID\"}}", lastEvent());
    }

    @Test
    void testInvokeOrdersProtocolDataTlsMqttHttpAndProtocolsTlsHttpMqtt() {
        customAuthorizer.testInvoke("a", request("""
                {"httpContext": {"headers": {"X-Secret": "s", "X-Mixed-Case": "Value"}, "queryString": "?a=b&c=d"},
                 "mqttContext": {"clientId": "c"}, "tlsContext": {"serverName": "iot.example.com"}}
                """), REGION);

        assertEquals("{\"protocolData\":{\"tls\":{\"serverName\":\"iot.example.com\"},\"mqtt\":{\"clientId\":\"c\"},"
                + "\"http\":{\"headers\":{\"X-Secret\":\"s\",\"X-Mixed-Case\":\"Value\"},\"queryString\":\"?a=b&c=d\"}},"
                + "\"protocols\":[\"tls\",\"http\",\"mqtt\"],\"signatureVerified\":false,"
                + "\"connectionMetadata\":{\"id\":\"ID\"}}", lastEvent());
    }

    @Test
    void testInvokeWithAnEmptyHttpContextSendsAnEmptyHttpObject() {
        customAuthorizer.testInvoke("a", request("{\"httpContext\": {}}"), REGION);

        assertEquals("{\"protocolData\":{\"http\":{}},\"protocols\":[\"http\"],\"signatureVerified\":false,"
                + "\"connectionMetadata\":{\"id\":\"ID\"}}", lastEvent());
    }

    @Test
    void connectionMetadataIdIsAFreshUuidForEveryInvocation() {
        customAuthorizer.testInvoke("a", request("{\"httpContext\": {}}"), REGION);
        customAuthorizer.testInvoke("a", request("{\"httpContext\": {}}"), REGION);

        String first = events.get(0).path("connectionMetadata").path("id").asText();
        String second = events.get(1).path("connectionMetadata").path("id").asText();
        assertTrue(first.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}"), first);
        assertNotEquals(first, second);
    }

    @Test
    void testInvokeWorksOnAnInactiveAuthorizer() {
        assertTrue(customAuthorizer.testInvoke("off", request("{\"httpContext\": {}}"), REGION)
                .path("isAuthenticated").asBoolean());
    }

    @Test
    void testInvokeOnAMissingAuthorizerIsNotFound() {
        assertAwsError("ResourceNotFoundException", 404, "Authorizer nope not found",
                () -> customAuthorizer.testInvoke("nope", request("{\"httpContext\": {}}"), REGION));
        verifyNotInvoked();
    }

    @Test
    void omittedTtlsAreDefaultedTo300And86400() {
        answer = response("[" + quoted(POLICY) + "]", null);

        ObjectNode response = testInvoke();

        assertEquals(300, response.path("refreshAfterInSeconds").asInt());
        assertEquals(86400, response.path("disconnectAfterInSeconds").asInt());
    }

    @Test
    void ttlsAtTheInclusiveBoundsAreAccepted() {
        answer = response("[" + quoted(POLICY) + "]", "\"refreshAfterInSeconds\": 300, \"disconnectAfterInSeconds\": 86400");

        ObjectNode response = testInvoke();

        assertEquals(300, response.path("refreshAfterInSeconds").asInt());
        assertEquals(86400, response.path("disconnectAfterInSeconds").asInt());
    }

    @Test
    void aPolicyGivenAsAnObjectIsReturnedAsOneCompactString() throws Exception {
        answer = response("[" + POLICY + "]", null);

        ObjectNode response = testInvoke();

        assertEquals(mapper.writeValueAsString(mapper.readTree(POLICY)), response.path("policyDocuments").get(0).asText());
    }

    @Test
    void aDenyWithPrincipalAndPoliciesIsReturnedAsIs() {
        answer = "{\"isAuthenticated\": false, \"principalId\": \"p1\", \"policyDocuments\": [" + quoted(POLICY) + "]}";

        ObjectNode response = testInvoke();

        assertFalse(response.path("isAuthenticated").asBoolean());
        assertEquals(POLICY, response.path("policyDocuments").get(0).asText());
    }

    @Test
    void anEmptyPolicyListIsReturnedAsIs() {
        answer = response("[]", null);

        assertEquals(0, testInvoke().path("policyDocuments").size());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"foo\": 1}|Lambda invocation result is in invalid format",
        "{\"principalId\": \"p1\", \"policyDocuments\": []}|Lambda invocation result is in invalid format",
        "{\"isAuthenticated\": false}|null" + PRINCIPAL_CONSTRAINT,
        "{\"isAuthenticated\": true, \"policyDocuments\": []}|null" + PRINCIPAL_CONSTRAINT,
        "{\"isAuthenticated\": true, \"principalId\": \"bad-principal!\"}|bad-principal!" + PRINCIPAL_CONSTRAINT,
        "{\"isAuthenticated\": true, \"principalId\": \"p1\"}|policyDocuments failed to satisfy constraint: List of policies cannot be null.",
        "{\"isAuthenticated\": false, \"principalId\": \"p1\"}|policyDocuments failed to satisfy constraint: List of policies cannot be null.",
        "{\"isAuthenticated\": true, \"principalId\": \"p1\", \"policyDocuments\": [\"this is not json\"]}"
                + "|policyDocuments failed to satisfy constraint: Policy document is Malformed: Invalid policy syntax.",
        "{\"isAuthenticated\": true, \"principalId\": \"p1\", \"policyDocuments\": [\"{} {}\"]}"
                + "|policyDocuments failed to satisfy constraint: Policy document is Malformed: Invalid policy syntax.",
        "{\"isAuthenticated\": true, \"principalId\": \"p1\", \"policyDocuments\": [\"{}\",\"{}\",\"{}\",\"{}\",\"{}\",\"{}\",\"{}\",\"{}\",\"{}\",\"{}\",\"{}\"]}"
                + "|policyDocuments failed to satisfy constraint: Number of policies allowed cannot exceed 10",
        "{\"isAuthenticated\": true, \"principalId\": \"p1\", \"policyDocuments\": [{}], \"refreshAfterInSeconds\": 299}"
                + "|299 at refreshAfterInSeconds" + TTL_CONSTRAINT,
        "{\"isAuthenticated\": true, \"principalId\": \"p1\", \"policyDocuments\": [{}], \"refreshAfterInSeconds\": 86401}"
                + "|86401 at refreshAfterInSeconds" + TTL_CONSTRAINT,
        "{\"isAuthenticated\": true, \"principalId\": \"p1\", \"policyDocuments\": [{}], \"disconnectAfterInSeconds\": 299}"
                + "|299 at disconnectAfterInSeconds" + TTL_CONSTRAINT,
        "{\"isAuthenticated\": true, \"principalId\": \"p1\", \"policyDocuments\": [{}], \"disconnectAfterInSeconds\": 86401}"
                + "|86401 at disconnectAfterInSeconds" + TTL_CONSTRAINT,
        "{\"isAuthenticated\": true, \"principalId\": \"p1\", \"policyDocuments\": [{}], \"refreshAfterInSeconds\": 10, \"disconnectAfterInSeconds\": 10}"
                + "|10 at refreshAfterInSeconds" + TTL_CONSTRAINT,
        "not json|Lambda invocation result is in invalid format",
        "{\"isAuthenticated\": true, \"principalId\": \"p1\", \"policyDocuments\": []} junk|Lambda invocation result is in invalid format"
    })
    void anInvalidAnswerIsAnInvalidResponseInAwsOrderAndWording(String invalidAnswer, String message) {
        answer = invalidAnswer;

        assertAwsError("InvalidResponseException", 400, message, this::testInvoke);
    }

    @Test
    void aPolicyOverTheSizeLimitIsAnInvalidResponse() {
        answer = response("[" + quoted("{\"Version\": \"2012-10-17\", \"Statement\": [], \"Id\": \"" + "x".repeat(2048) + "\"}")
                + "]", null);

        assertAwsError("InvalidResponseException", 400,
                "policyDocuments failed to satisfy constraint: Size of a policy document cannot exceeds hard limit of 2048",
                this::testInvoke);
    }

    @Test
    void aFunctionErrorReportsItsMessageAndType() {
        functionError = "Unhandled";
        answer = "{\"errorMessage\": \"compat-raise\", \"errorType\": \"Exception\", \"stackTrace\": []}";

        assertAwsError("InvalidResponseException", 400,
                "Lambda function encountered error in execution: compat-raise: Exception", this::testInvoke);
    }

    @Test
    void tokenWithoutSignatureIsRefused() {
        assertInvalidRequest("Request must provide both token and tokenSignature together.",
                () -> customAuthorizer.testInvoke("a", request("{\"token\": \"allow-me\"}"), REGION));
    }

    @Test
    void tokenAndSignatureOnASigningDisabledAuthorizerAreRefused() throws GeneralSecurityException {
        assertInvalidRequest("Token and token signature provided but signing is disabled for the authorizer.",
                () -> customAuthorizer.testInvoke("a", tokenRequest("allow-me", sign(key1, "allow-me")), REGION));
    }

    @Test
    void tokenWithAContextIsRefused() throws GeneralSecurityException {
        ObjectNode request = tokenRequest("allow-me", sign(key1, "allow-me"));
        request.putObject("mqttContext").put("clientId", "c");

        assertInvalidRequest("Request cannot have token and Http/Mqtt/Tls context.",
                () -> customAuthorizer.testInvoke("s", request, REGION));
    }

    @Test
    void aRequestWithoutTokenOrContextIsRefused() {
        assertInvalidRequest("Request must have Http/Mqtt/Tls context.",
                () -> customAuthorizer.testInvoke("a", request("{}"), REGION));
    }

    @Test
    void aSignatureThatIsNotBase64IsAValidationError() {
        assertInvalidRequest("1 validation error detected: Value at 'tokenSignature' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: [A-Za-z0-9+/]+={0,2}",
                () -> customAuthorizer.testInvoke("s", tokenRequest("allow-me", "not base64!"), REGION));
    }

    @Test
    void aQueryStringWithoutTheQuestionMarkIsRefused() {
        assertInvalidRequest("Invalid arguments in the call", () -> customAuthorizer.testInvoke("a",
                request("{\"httpContext\": {\"headers\": {\"X-Secret\": \"s\"}, \"queryString\": \"a=b\"}}"), REGION));
    }

    @Test
    void anEmptyQueryStringIsAValidationError() {
        assertInvalidRequest("1 validation error detected: Value at 'httpContext.queryString' failed to satisfy "
                        + "constraint: Member must have length greater than or equal to 1",
                () -> customAuthorizer.testInvoke("a", request("{\"httpContext\": {\"queryString\": \"\"}}"), REGION));
    }

    @Test
    void anEmptyTokenIsAValidationError() throws GeneralSecurityException {
        assertInvalidRequest("1 validation error detected: Value at 'token' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 1",
                () -> customAuthorizer.testInvoke("s", tokenRequest("", sign(key1, "")), REGION));
    }

    @Test
    void aTokenOverItsMaximumLengthIsAValidationError() throws GeneralSecurityException {
        String token = "t".repeat(6145);

        assertInvalidRequest("1 validation error detected: Value at 'token' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 6144",
                () -> customAuthorizer.testInvoke("s", tokenRequest(token, sign(key1, token)), REGION));
    }

    @Test
    void aSignatureOverItsMaximumLengthIsAValidationError() {
        assertInvalidRequest("1 validation error detected: Value at 'tokenSignature' failed to satisfy constraint: "
                        + "Member must have length less than or equal to 2560",
                () -> customAuthorizer.testInvoke("s", tokenRequest("allow-me", "A".repeat(2561)), REGION));
    }

    @Test
    void tokenModeVerifiesASignatureByEitherKeyAndSendsOnlyTheToken() throws GeneralSecurityException {
        for (KeyPair key : List.of(key1, key2)) {
            assertTrue(customAuthorizer.testInvoke("s", tokenRequest("allow-me", sign(key, "allow-me")), REGION)
                    .path("isAuthenticated").asBoolean());
            assertEquals("{\"token\":\"allow-me\",\"signatureVerified\":true,\"connectionMetadata\":{\"id\":\"ID\"}}",
                    lastEvent());
        }
    }

    @Test
    void aSignatureOverAnotherTokenOrByAnUnregisteredKeyIsAMismatch() throws GeneralSecurityException {
        assertInvalidRequest("Token signature mismatch for authorizer s",
                () -> customAuthorizer.testInvoke("s", tokenRequest("allow-me", sign(key1, "other-token")), REGION));
        assertInvalidRequest("Token signature mismatch for authorizer s",
                () -> customAuthorizer.testInvoke("s", tokenRequest("allow-me", sign(unregistered, "allow-me")), REGION));
    }

    @Test
    void aSignedMqttUsernameIsVerifiedAndItsTokenSent() throws GeneralSecurityException {
        String username = signedUsername(sign(key1, "allow-me"), "allow-me");
        ObjectNode request = mapper.createObjectNode();
        request.putObject("mqttContext").put("username", username).put("clientId", "c");

        customAuthorizer.testInvoke("s", request, REGION);

        assertEquals("{\"protocolData\":{\"mqtt\":{\"username\":" + quoted(username) + ",\"clientId\":\"c\"}},"
                + "\"protocols\":[\"mqtt\"],\"token\":\"allow-me\",\"signatureVerified\":true,"
                + "\"connectionMetadata\":{\"id\":\"ID\"}}", lastEvent());
    }

    @Test
    void aSignatureInHttpHeadersOrQueryStringIsVerified() throws GeneralSecurityException {
        String signature = sign(key2, "allow-me");
        ObjectNode headers = mapper.createObjectNode();
        headers.putObject("httpContext").putObject("headers").put("tok", "allow-me").put("x-amz-customauthorizer-signature", signature);
        ObjectNode query = mapper.createObjectNode();
        query.putObject("httpContext").put("queryString", "?tok=allow-me&" + SIGNATURE_PARAM + urlEncoded(signature));

        customAuthorizer.testInvoke("s", headers, REGION);
        assertEquals("allow-me", events.getLast().path("token").asText());
        customAuthorizer.testInvoke("s", query, REGION);
        assertEquals("allow-me", events.getLast().path("token").asText());
    }

    @Test
    void aSignedAuthorizerWithoutATokenInTheContextIsRefusedWithoutAMessage() {
        AwsException failure = assertThrows(AwsException.class, () -> customAuthorizer.testInvoke("s",
                request("{\"mqttContext\": {\"clientId\": \"c\", \"password\": \"c2VjcmV0\"}}"), REGION));

        assertEquals("InvalidRequestException", failure.getErrorCode());
        assertNull(failure.getMessage());
        verifyNotInvoked();
    }

    // MQTT CONNECT

    @Test
    void aWebSocketConnectSendsTlsMqttAndHttpWithTheUsernameVerbatim() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", "iot.example.com");
        headers.put("sec-websocket-protocol", "mqtt");

        List<String> policies = customAuthorizer.connectPolicies("a", "c", "c?" + NAME_PARAM + "a", "secret",
                new WebSocketUpgrade(headers, "", "iot.example.com", null));

        assertEquals(List.of(POLICY), policies);
        assertEquals("{\"protocolData\":{\"tls\":{\"serverName\":\"iot.example.com\"},\"mqtt\":{\"username\":"
                + "\"c?x-amz-customauthorizer-name=a\",\"password\":\"c2VjcmV0\",\"clientId\":\"c\"},\"http\":{\"headers\":"
                + "{\"host\":\"iot.example.com\",\"sec-websocket-protocol\":\"mqtt\"},\"queryString\":\"\"}},"
                + "\"protocols\":[\"tls\",\"http\",\"mqtt\"],\"signatureVerified\":false,"
                + "\"connectionMetadata\":{\"id\":\"ID\"}}", lastEvent());
    }

    @Test
    void aConnectWithoutUsernamePasswordOrTlsLeavesThemOut() {
        customAuthorizer.connectPolicies("a", "c", null, null, new WebSocketUpgrade(Map.of(), NAME_PARAM + "a", null, null));

        assertEquals("{\"protocolData\":{\"mqtt\":{\"clientId\":\"c\"},\"http\":{\"headers\":{},"
                + "\"queryString\":\"x-amz-customauthorizer-name=a\"}},\"protocols\":[\"http\",\"mqtt\"],"
                + "\"signatureVerified\":false,\"connectionMetadata\":{\"id\":\"ID\"}}", lastEvent());
    }

    @Test
    void aPlainTcpConnectSendsOnlyMqtt() {
        customAuthorizer.connectPolicies("a", "c", "c?" + NAME_PARAM + "a", null, null);

        assertEquals("{\"protocolData\":{\"mqtt\":{\"username\":\"c?x-amz-customauthorizer-name=a\",\"clientId\":\"c\"}},"
                + "\"protocols\":[\"mqtt\"],\"signatureVerified\":false,\"connectionMetadata\":{\"id\":\"ID\"}}", lastEvent());
    }

    @Test
    void aDenyOrAnEmptyPolicyListAdmitsNoPolicies() {
        answer = "{\"isAuthenticated\": false, \"principalId\": \"p1\", \"policyDocuments\": [" + quoted(POLICY) + "]}";
        assertEquals(List.of(), connect("a"));

        answer = response("[]", null);
        assertEquals(List.of(), connect("a"));
    }

    @Test
    void anObjectPolicyReachesTheConnectAsACompactString() throws Exception {
        answer = response("[" + POLICY + "]", null);

        assertEquals(List.of(mapper.writeValueAsString(mapper.readTree(POLICY))), connect("a"));
    }

    @Test
    void anInvalidAnswerFailsTheConnect() {
        answer = "{\"isAuthenticated\": false}";

        assertAwsError("InvalidResponseException", 400, "null" + PRINCIPAL_CONSTRAINT, () -> connect("a"));
    }

    @Test
    void anInactiveAuthorizerAdmitsNothingWithoutInvokingTheFunction() {
        assertEquals(List.of(), connect("off"));
        verifyNotInvoked();
    }

    @Test
    void anUnknownAuthorizerFailsTheConnectWithoutInvokingTheFunction() {
        assertThrows(AwsException.class, () -> connect("nope"));
        verifyNotInvoked();
    }

    @Test
    void aSignedConnectSendsTheTokenAfterTheProtocols() throws GeneralSecurityException {
        String username = signedUsername(sign(key2, "allow-me"), "allow-me");

        customAuthorizer.connectPolicies("s", "c", username, null, null);

        assertEquals("{\"protocolData\":{\"mqtt\":{\"username\":" + quoted(username) + ",\"clientId\":\"c\"}},"
                + "\"protocols\":[\"mqtt\"],\"token\":\"allow-me\",\"signatureVerified\":true,"
                + "\"connectionMetadata\":{\"id\":\"ID\"}}", lastEvent());
    }

    @Test
    void aSignedConnectReadsTokenAndSignatureFromTheWebSocketQueryOrHeaders() throws GeneralSecurityException {
        String signature = sign(key1, "allow-me");

        customAuthorizer.connectPolicies("s", "c", "c", null, new WebSocketUpgrade(Map.of(),
                NAME_PARAM + "s&" + SIGNATURE_PARAM + urlEncoded(signature) + "&tok=allow-me", null, null));
        assertEquals("allow-me", events.getLast().path("token").asText());

        customAuthorizer.connectPolicies("s", "c", "c", null, new WebSocketUpgrade(
                Map.of("x-amz-customauthorizer-signature", signature, "tok", "allow-me"), "", null, null));
        assertEquals("allow-me", events.getLast().path("token").asText());
    }

    @Test
    void aSignedConnectWithABadOrMissingSignatureNeverInvokesTheFunction() throws GeneralSecurityException {
        String overOtherToken = signedUsername(sign(key1, "other-token"), "allow-me");
        String withoutToken = "c?" + NAME_PARAM + "s&" + SIGNATURE_PARAM + urlEncoded(sign(key1, "allow-me"));

        for (String username : List.of(overOtherToken, withoutToken, "c?" + NAME_PARAM + "s&tok=allow-me")) {
            assertThrows(AwsException.class, () -> customAuthorizer.connectPolicies("s", "c", username, null, null), username);
        }
        verifyNotInvoked();
    }

    @Test
    void theAuthorizerNameComesFromTheUsernameTheWebSocketQueryOrTheUpgradeHeader() {
        assertEquals("u", IotCustomAuthorizer.authorizerName("c?" + NAME_PARAM + "u&tok=t", null));
        assertEquals("q", IotCustomAuthorizer.authorizerName("c", new WebSocketUpgrade(Map.of(), NAME_PARAM + "q", null, null)));
        assertEquals("h", IotCustomAuthorizer.authorizerName(null,
                new WebSocketUpgrade(Map.of("x-amz-customauthorizer-name", "h"), "", null, null)));
    }

    @Test
    void aConnectNamingNoAuthorizerHasNoName() {
        assertNull(IotCustomAuthorizer.authorizerName(null, null));
        assertNull(IotCustomAuthorizer.authorizerName("plain-user", null));
        assertNull(IotCustomAuthorizer.authorizerName("c?X-Amz-Algorithm=AWS4-HMAC-SHA256", new WebSocketUpgrade(
                Map.of("sec-websocket-protocol", "mqtt"), "X-Amz-Signature=00", null, null)));
        assertEquals("%zz", IotCustomAuthorizer.authorizerName("c?" + NAME_PARAM + "%zz", null),
                "a malformed escape is kept as sent instead of failing the CONNECT");
    }

    // Helpers

    private void create(String name, String status, boolean signed) {
        ObjectNode body = mapper.createObjectNode().put("authorizerFunctionArn", FUNCTION_ARN).put("status", status);
        if (signed) {
            body.put("tokenKeyName", "tok");
            body.putObject("tokenSigningPublicKeys").put("k1", pem(key1)).put("k2", pem(key2));
        } else {
            body.put("signingDisabled", true);
        }
        authorizers.createAuthorizer(name, body, REGION);
    }

    private ObjectNode testInvoke() {
        return customAuthorizer.testInvoke("a", request("{\"mqttContext\": {\"clientId\": \"c\", \"password\": \"c2VjcmV0\"}}"),
                REGION);
    }

    private List<String> connect(String name) {
        return customAuthorizer.connectPolicies(name, "c", "c?" + NAME_PARAM + name, "secret", null);
    }

    private ObjectNode request(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    private ObjectNode tokenRequest(String token, String signature) {
        return mapper.createObjectNode().put("token", token).put("tokenSignature", signature);
    }

    /** The last event the function received, with its random connection id replaced by {@code ID}. */
    private String lastEvent() {
        ObjectNode event = (ObjectNode) events.getLast().deepCopy();
        ((ObjectNode) event.get("connectionMetadata")).put("id", "ID");
        return event.toString();
    }

    private void verifyNotInvoked() {
        verify(lambda, never()).invokeArn(anyString(), any(byte[].class), any(InvocationType.class));
    }

    private static String response(String policies, String ttls) {
        return "{\"isAuthenticated\": true, \"principalId\": \"p1\", \"policyDocuments\": " + policies
                + (ttls == null ? "" : ", " + ttls) + "}";
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String signedUsername(String signature, String token) {
        return "c?" + NAME_PARAM + "s&" + SIGNATURE_PARAM + urlEncoded(signature) + "&tok=" + token;
    }

    private static String urlEncoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String sign(KeyPair key, String token) throws GeneralSecurityException {
        PrivateKey privateKey = key.getPrivate();
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(token.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static String pem(KeyPair key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    private void assertInvalidRequest(String message, Executable call) {
        assertAwsError("InvalidRequestException", 400, message, call);
        verifyNotInvoked();
    }

    private static void assertAwsError(String code, int status, String message, Executable call) {
        AwsException failure = assertThrows(AwsException.class, call);
        assertEquals(code, failure.getErrorCode());
        assertEquals(status, failure.getHttpStatus());
        assertEquals(message, failure.getMessage());
    }
}
