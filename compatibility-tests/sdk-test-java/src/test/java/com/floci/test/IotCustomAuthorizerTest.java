package com.floci.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.AuthorizerStatus;
import software.amazon.awssdk.services.iot.model.InvalidRequestException;
import software.amazon.awssdk.services.iot.model.InvalidResponseException;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iot.model.TestInvokeAuthorizerRequest;
import software.amazon.awssdk.services.iot.model.TestInvokeAuthorizerResponse;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvalidParameterValueException;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.lambda.model.State;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.fail;

/**
 * AWS IoT custom authorizers: {@code TestInvokeAuthorizer} and enforcement of a real MQTT CONNECT over
 * WebSocket. Every expectation was measured against real AWS IoT Core (eu-west-1).
 *
 * <p>One Python Lambda serves every case. It decides by the decoded MQTT password, or by the token when
 * there is no password, or by an {@code x-secret} header for an HTTP context. The {@code shape:<kind>}
 * secrets make the Lambda check the received event against the AWS shape for that transport and answer
 * with principal {@code shapeok} (allowed) or {@code shapebad} (denied), so the event shape is asserted
 * through the authorizer response or the connection outcome.
 *
 * <p>Paho v3 and v5 share several class names ({@code MqttException}, {@code MemoryPersistence}); the v3
 * ones are imported and the v5 ones are written fully qualified.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("IoT custom authorizer invocation and enforcement")
class IotCustomAuthorizerTest {

    private static final Logger LOG = Logger.getLogger(IotCustomAuthorizerTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PRINCIPAL = "measuredprincipal";
    private static final String TOKEN_KEY_NAME = "tok";
    private static final String SHAPE_SERVER_NAME = "shape.example.com";
    private static final String NAME_PARAM = "x-amz-customauthorizer-name=";
    private static final String SIGNATURE_PARAM = "x-amz-customauthorizer-signature=";
    private static final String NAME_HEADER = "x-amz-customauthorizer-name";
    private static final int TIMEOUT_SECONDS = 20;
    /** AWS IoT allows one CONNECT per second per client id and answers a faster one with CONNACK 151. */
    private static final long CONNECT_SPACING_NANOS = TimeUnit.MILLISECONDS.toNanos(1200);
    /**
     * MQTT 5 refusal: CONNACK reason 0x87. AWS also sends the reason string
     * {@code CONNACK:Client is not authenticated/authorized to send the message:<id>}, which Paho 1.2.5 drops.
     */
    private static final int MQTT5_NOT_AUTHORIZED = 135;
    /** MQTT 3.1.1 refusal: AWS sends no CONNACK and closes the WebSocket, which Paho reports as a lost connection. */
    private static final int MQTT3_REFUSAL_REASON_CODE = MqttException.REASON_CODE_CONNECTION_LOST;

    private static final String LAMBDA_SOURCE = """
            import base64
            import json
            import os
            import re

            ALLOW_POLICY = os.environ["ALLOW_POLICY"]
            ARN_PREFIX = os.environ["IOT_ARN_PREFIX"]
            CLIENT_ID = os.environ["ALLOWED_CLIENT_ID"]
            NAME_PREFIX = os.environ["NAME_PREFIX"]
            SERVER_NAME = os.environ["SHAPE_SERVER_NAME"]
            NAME_PARAM = "x-amz-customauthorizer-name="
            SIGNATURE_PARAM = "x-amz-customauthorizer-signature="
            ID_PATTERN = re.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
            SIGNED_KINDS = ("ti-token", "ti-signed-mqtt", "ws-signed")


            def response(policies, principal="measuredprincipal", refresh=300, disconnect=3600):
                out = {"isAuthenticated": True, "principalId": principal, "policyDocuments": policies}
                if refresh is not None:
                    out["refreshAfterInSeconds"] = refresh
                if disconnect is not None:
                    out["disconnectAfterInSeconds"] = disconnect
                return out


            def secret_of(event):
                data = event.get("protocolData") or {}
                mqtt = data.get("mqtt") or {}
                if mqtt.get("password") is not None:
                    return base64.b64decode(mqtt["password"]).decode("utf-8")
                if event.get("token") is not None:
                    return event["token"]
                http = data.get("http") or {}
                for key, value in (http.get("headers") or {}).items():
                    if key.lower() == "x-secret":
                        return value
                for part in (http.get("queryString") or "").lstrip("?").split("&"):
                    if part.startswith("x-secret="):
                        return part.split("=", 1)[1]
                return None


            def expect(problems, condition, what):
                if not condition:
                    problems.append(what)


            def named_username(username, signed, secret):
                prefix = CLIENT_ID + "?" + NAME_PARAM + NAME_PREFIX
                if not isinstance(username, str) or not username.startswith(prefix):
                    return False
                if signed:
                    return ("&" + SIGNATURE_PARAM) in username and username.endswith("&tok=" + secret)
                return "&" not in username


            def shape_problems(kind, event, secret):
                problems = []
                signed = kind in SIGNED_KINDS
                expect(problems, event.get("signatureVerified") is signed, "signatureVerified %r" % event.get("signatureVerified"))
                if signed:
                    expect(problems, event.get("token") == secret, "token %r" % event.get("token"))
                else:
                    expect(problems, "token" not in event, "unexpected token")
                meta = event.get("connectionMetadata")
                expect(problems, isinstance(meta, dict) and set(meta) == {"id"} and ID_PATTERN.match(str(meta.get("id"))) is not None, "connectionMetadata %r" % meta)
                if kind == "ti-token":
                    expect(problems, set(event) == {"token", "signatureVerified", "connectionMetadata"}, "keys %r" % sorted(event))
                    return problems
                top = {"protocolData", "protocols", "signatureVerified", "connectionMetadata"}
                if signed:
                    top.add("token")
                expect(problems, set(event) == top, "keys %r" % sorted(event))
                data = event.get("protocolData")
                protocols = event.get("protocols")
                if not isinstance(data, dict):
                    problems.append("protocolData %r" % data)
                    return problems
                if kind == "ti-http":
                    expect(problems, protocols == ["http"], "protocols %r" % protocols)
                    expected = {"http": {"headers": {"X-Secret": secret, "X-Mixed-Case": "Value"}, "queryString": "?a=b&c=d"}}
                    expect(problems, data == expected, "protocolData %r" % data)
                    return problems
                mqtt = data.get("mqtt") or {}
                username = mqtt.get("username")
                expect(problems, mqtt.get("clientId") == CLIENT_ID, "clientId %r" % mqtt.get("clientId"))
                if signed:
                    expect(problems, set(mqtt) == {"username", "clientId"}, "mqtt keys %r" % sorted(mqtt))
                else:
                    expect(problems, set(mqtt) == {"username", "password", "clientId"}, "mqtt keys %r" % sorted(mqtt))
                    expected_password = base64.b64encode(secret.encode("utf-8")).decode("ascii")
                    expect(problems, mqtt.get("password") == expected_password, "password %r" % mqtt.get("password"))
                if kind in ("ti-mqtt", "ti-mqtt-tls", "ti-signed-mqtt"):
                    tls = kind == "ti-mqtt-tls"
                    expect(problems, protocols == (["tls", "mqtt"] if tls else ["mqtt"]), "protocols %r" % protocols)
                    expect(problems, set(data) == ({"tls", "mqtt"} if tls else {"mqtt"}), "protocolData keys %r" % sorted(data))
                    if tls:
                        expect(problems, data.get("tls") == {"serverName": SERVER_NAME}, "tls %r" % data.get("tls"))
                    expect(problems, named_username(username, signed, secret), "username %r" % username)
                    return problems
                expect(problems, isinstance(protocols, list) and "http" in protocols and "mqtt" in protocols
                       and set(protocols) <= {"tls", "http", "mqtt"} and len(set(protocols)) == len(protocols), "protocols %r" % protocols)
                expect(problems, isinstance(protocols, list) and set(data) == set(protocols), "protocolData keys %r" % sorted(data))
                if "tls" in data:
                    tls = data["tls"]
                    expect(problems, isinstance(tls, dict) and set(tls) == {"serverName"} and bool(tls.get("serverName")), "tls %r" % tls)
                http = data.get("http") or {}
                expect(problems, set(http) == {"headers", "queryString"}, "http keys %r" % sorted(http))
                headers = http.get("headers") or {}
                expect(problems, all(key == key.lower() for key in headers), "header names %r" % sorted(headers))
                expect(problems, "mqtt" in str(headers.get("sec-websocket-protocol")), "sec-websocket-protocol %r" % headers.get("sec-websocket-protocol"))
                query = http.get("queryString")
                if kind == "ws-query":
                    expect(problems, isinstance(query, str) and query.startswith(NAME_PARAM + NAME_PREFIX) and "&" not in query, "queryString %r" % query)
                else:
                    expect(problems, query == "", "queryString %r" % query)
                if kind == "ws-header":
                    expect(problems, str(headers.get("x-amz-customauthorizer-name")).startswith(NAME_PREFIX), "name header %r" % headers.get("x-amz-customauthorizer-name"))
                if kind in ("ws-query", "ws-header"):
                    expect(problems, username == CLIENT_ID, "username %r" % username)
                else:
                    expect(problems, named_username(username, signed, secret), "username %r" % username)
                return problems


            def handler(event, context):
                print(json.dumps(event))
                secret = secret_of(event)
                print("secret=%r" % (secret,))
                policy = ALLOW_POLICY
                if secret is None:
                    return {"isAuthenticated": False}
                if secret.startswith("shape:"):
                    problems = shape_problems(secret[len("shape:"):], event, secret)
                    print("shape problems: %r" % (problems,))
                    if problems:
                        return {"isAuthenticated": False, "principalId": "shapebad", "policyDocuments": []}
                    return response([policy], principal="shapeok")
                if secret.startswith("ttl:"):
                    _, refresh, disconnect = secret.split(":")
                    return response([policy],
                                    refresh=None if refresh == "-" else int(refresh),
                                    disconnect=None if disconnect == "-" else int(disconnect))
                if secret == "allow-me":
                    return response([policy])
                if secret == "raise":
                    raise Exception("compat-raise")
                if secret == "garbage":
                    return {"foo": 1}
                if secret == "noauthflag":
                    return {"principalId": "measuredprincipal", "policyDocuments": [policy]}
                if secret == "noprincipal":
                    return {"isAuthenticated": True, "policyDocuments": [policy]}
                if secret == "badprincipal":
                    return response([policy], principal="bad-principal!")
                if secret == "nopolicy":
                    return {"isAuthenticated": True, "principalId": "measuredprincipal"}
                if secret == "denynopolicy":
                    return {"isAuthenticated": False, "principalId": "measuredprincipal"}
                if secret == "denypol":
                    return {"isAuthenticated": False, "principalId": "measuredprincipal", "policyDocuments": [policy]}
                if secret == "emptypol":
                    return response([])
                if secret == "badjson":
                    return response(["this is not json"])
                if secret == "manypol":
                    return response([policy] * 11)
                if secret == "bigpol":
                    big = json.loads(policy)
                    big["Statement"][1]["Resource"] = [ARN_PREFIX + ":topic/big/%040d" % i for i in range(60)]
                    return response([json.dumps(big)])
                if secret == "objpolicy":
                    return response([json.loads(policy)])
                return {"isAuthenticated": False}
            """;

    private final String prefix = TestFixtures.uniqueName("compat-cauth");
    private final String roleName = prefix + "-role";
    private final String rolePolicyName = prefix + "-logs";
    private final String functionName = prefix + "-fn";
    private final String authorizer = prefix + "-a";
    private final String signedAuthorizer = prefix + "-s";
    private final String inactiveAuthorizer = prefix + "-i";
    private final String unknownAuthorizer = prefix + "-nope";
    private final String clientId = prefix + "-client";
    private final String otherClientId = prefix + "-other";
    private final List<String> createdAuthorizers = new ArrayList<>();
    private final Map<String, Long> lastConnectNanos = new HashMap<>();

    private IotClient iot;
    private LambdaClient lambda;
    private IamClient iam;
    private CloudWatchLogsClient logs;
    private boolean roleCreated;
    private boolean functionCreated;
    private boolean logGroupCreated;
    private String allowPolicy;
    private String mqttUri;
    private KeyPair key1;
    private KeyPair key2;
    private KeyPair unregisteredKey;

    enum Mqtt { V3_1_1, V5 }

    @BeforeAll
    void setUp() throws Exception {
        iot = TestFixtures.iotClient();
        lambda = TestFixtures.lambdaClient();
        iam = TestFixtures.iamClient();
        logs = TestFixtures.cloudWatchLogsClient();

        String region;
        try (StsClient sts = TestFixtures.stsClient()) {
            region = sts.serviceClientConfiguration().region().id();
            GetCallerIdentityResponse identity = sts.getCallerIdentity();
            String partition = identity.arn().split(":")[1];
            String arnPrefix = "arn:" + partition + ":iot:" + region + ":" + identity.account();
            allowPolicy = "{\"Version\": \"2012-10-17\", \"Statement\": [{\"Effect\": \"Allow\", "
                    + "\"Action\": \"iot:Connect\", \"Resource\": \"" + arnPrefix + ":client/" + clientId + "\"}, "
                    + "{\"Effect\": \"Allow\", \"Action\": [\"iot:Publish\", \"iot:Subscribe\", \"iot:Receive\"], "
                    + "\"Resource\": \"*\"}]}";

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            key1 = generator.generateKeyPair();
            key2 = generator.generateKeyPair();
            unregisteredKey = generator.generateKeyPair();

            String roleArn = createRole();
            String functionArn = createFunction(roleArn, arnPrefix);
            createAuthorizer(authorizer, functionArn, AuthorizerStatus.ACTIVE, false);
            createAuthorizer(signedAuthorizer, functionArn, AuthorizerStatus.ACTIVE, true);
            createAuthorizer(inactiveAuthorizer, functionArn, AuthorizerStatus.INACTIVE, false);
        }
        mqttUri = mqttBaseUri();
        LOG.infov("custom authorizer fixtures ready: prefix={0} mqtt={1}", prefix, mqttUri);
        awaitAuthorizerReady();
    }

    @AfterAll
    void tearDown() {
        for (String name : createdAuthorizers) {
            quietly("deactivate authorizer " + name,
                    () -> iot.updateAuthorizer(r -> r.authorizerName(name).status(AuthorizerStatus.INACTIVE)));
            quietly("delete authorizer " + name, () -> iot.deleteAuthorizer(r -> r.authorizerName(name)));
        }
        if (functionCreated) {
            quietly("delete function", () -> lambda.deleteFunction(r -> r.functionName(functionName)));
        }
        if (roleCreated) {
            quietly("delete role policy",
                    () -> iam.deleteRolePolicy(r -> r.roleName(roleName).policyName(rolePolicyName)));
            quietly("delete role", () -> iam.deleteRole(r -> r.roleName(roleName)));
        }
        if (logGroupCreated) {
            quietly("delete log group",
                    () -> logs.deleteLogGroup(r -> r.logGroupName("/aws/lambda/" + functionName)));
        }
        quietly("close clients", () -> {
            iot.close();
            lambda.close();
            iam.close();
            logs.close();
        });
    }

    // ---------------------------------------------------------------------------------------------
    // TestInvokeAuthorizer
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("test-invoke with an MQTT context returns the Lambda's verdict, policies verbatim and TTLs")
    void testInvokeAllow() {
        TestInvokeAuthorizerResponse response = invokeMqtt(authorizer, "allow-me");

        assertThat(response.isAuthenticated()).isTrue();
        assertThat(response.principalId()).isEqualTo(PRINCIPAL);
        assertThat(response.policyDocuments()).containsExactly(allowPolicy);
        assertThat(response.refreshAfterInSeconds()).isEqualTo(300);
        assertThat(response.disconnectAfterInSeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("test-invoke with only an MQTT context sends protocols [mqtt] and the MQTT fields as given")
    void testInvokeMqttContextEventShape() {
        assertShapeOk(invokeMqtt(authorizer, "shape:ti-mqtt"));
    }

    @Test
    @DisplayName("test-invoke with MQTT and TLS contexts sends protocols [tls, mqtt] and the server name")
    void testInvokeMqttAndTlsContextEventShape() {
        String secret = "shape:ti-mqtt-tls";
        assertShapeOk(iot.testInvokeAuthorizer(r -> r.authorizerName(authorizer)
                .mqttContext(m -> m.clientId(clientId)
                        .username(namedUsername(authorizer))
                        .password(SdkBytes.fromUtf8String(secret)))
                .tlsContext(t -> t.serverName(SHAPE_SERVER_NAME))));
    }

    @Test
    @DisplayName("test-invoke with only an HTTP context keeps header case and the query string with its '?'")
    void testInvokeHttpContextEventShape() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Secret", "shape:ti-http");
        headers.put("X-Mixed-Case", "Value");
        assertShapeOk(iot.testInvokeAuthorizer(r -> r.authorizerName(authorizer)
                .httpContext(h -> h.headers(headers).queryString("?a=b&c=d"))));
    }

    @Test
    @DisplayName("test-invoke fills omitted TTLs with 300 and 86400")
    void testInvokeOmittedTtlsAreDefaulted() {
        TestInvokeAuthorizerResponse response = invokeMqtt(authorizer, "ttl:-:-");

        assertThat(response.isAuthenticated()).isTrue();
        assertThat(response.policyDocuments()).containsExactly(allowPolicy);
        assertThat(response.refreshAfterInSeconds()).isEqualTo(300);
        assertThat(response.disconnectAfterInSeconds()).isEqualTo(86400);
    }

    @Test
    @DisplayName("test-invoke accepts TTLs at the inclusive bounds 300 and 86400")
    void testInvokeTtlBoundsAccepted() {
        TestInvokeAuthorizerResponse response = invokeMqtt(authorizer, "ttl:300:86400");

        assertThat(response.refreshAfterInSeconds()).isEqualTo(300);
        assertThat(response.disconnectAfterInSeconds()).isEqualTo(86400);
    }

    @Test
    @DisplayName("test-invoke returns a policy given as a JSON object as one compact string")
    void testInvokeObjectPolicyIsCompacted() throws IOException {
        TestInvokeAuthorizerResponse response = invokeMqtt(authorizer, "objpolicy");

        assertThat(response.isAuthenticated()).isTrue();
        assertThat(response.policyDocuments()).containsExactly(MAPPER.writeValueAsString(MAPPER.readTree(allowPolicy)));
    }

    static Stream<Arguments> invalidLambdaResponses() {
        return Stream.of(
                Arguments.of("garbage", "Lambda invocation result is in invalid format"),
                Arguments.of("noauthflag", "Lambda invocation result is in invalid format"),
                Arguments.of("deny",
                        "null at principalId failed to satisfy constraint: Member must satisfy ([a-zA-Z0-9]){1,128}"),
                Arguments.of("noprincipal",
                        "null at principalId failed to satisfy constraint: Member must satisfy ([a-zA-Z0-9]){1,128}"),
                Arguments.of("badprincipal", "bad-principal! at principalId failed to satisfy constraint: "
                        + "Member must satisfy ([a-zA-Z0-9]){1,128}"),
                Arguments.of("nopolicy",
                        "policyDocuments failed to satisfy constraint: List of policies cannot be null."),
                Arguments.of("denynopolicy",
                        "policyDocuments failed to satisfy constraint: List of policies cannot be null."),
                Arguments.of("badjson", "policyDocuments failed to satisfy constraint: "
                        + "Policy document is Malformed: Invalid policy syntax."),
                Arguments.of("manypol",
                        "policyDocuments failed to satisfy constraint: Number of policies allowed cannot exceed 10"),
                Arguments.of("bigpol", "policyDocuments failed to satisfy constraint: "
                        + "Size of a policy document cannot exceeds hard limit of 2048"),
                Arguments.of("ttl:299:3600", "299 at refreshAfterInSeconds failed to satisfy constraint: "
                        + "Member must be greater than 5 minutes and less than 24 hours."),
                Arguments.of("ttl:86401:3600", "86401 at refreshAfterInSeconds failed to satisfy constraint: "
                        + "Member must be greater than 5 minutes and less than 24 hours."),
                Arguments.of("ttl:300:299", "299 at disconnectAfterInSeconds failed to satisfy constraint: "
                        + "Member must be greater than 5 minutes and less than 24 hours."),
                Arguments.of("ttl:300:86401", "86401 at disconnectAfterInSeconds failed to satisfy constraint: "
                        + "Member must be greater than 5 minutes and less than 24 hours."),
                Arguments.of("ttl:10:10", "10 at refreshAfterInSeconds failed to satisfy constraint: "
                        + "Member must be greater than 5 minutes and less than 24 hours."),
                Arguments.of("raise", "Lambda function encountered error in execution: compat-raise: Exception"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLambdaResponses")
    @DisplayName("test-invoke rejects an invalid Lambda response with InvalidResponseException")
    void testInvokeRejectsInvalidLambdaResponse(String secret, String message) {
        assertAwsError(() -> invokeMqtt(authorizer, secret), InvalidResponseException.class, 400, message);
    }

    @Test
    @DisplayName("test-invoke returns isAuthenticated false when the Lambda denies with a principal and policies")
    void testInvokeDenyWithPolicies() {
        TestInvokeAuthorizerResponse response = invokeMqtt(authorizer, "denypol");

        assertThat(response.isAuthenticated()).isFalse();
        assertThat(response.principalId()).isEqualTo(PRINCIPAL);
        assertThat(response.policyDocuments()).containsExactly(allowPolicy);
    }

    @Test
    @DisplayName("test-invoke accepts an empty policyDocuments list")
    void testInvokeEmptyPolicies() {
        TestInvokeAuthorizerResponse response = invokeMqtt(authorizer, "emptypol");

        assertThat(response.isAuthenticated()).isTrue();
        assertThat(response.principalId()).isEqualTo(PRINCIPAL);
        assertThat(response.policyDocuments()).isEmpty();
    }

    @Test
    @DisplayName("test-invoke rejects a token without its signature")
    void testInvokeTokenWithoutSignature() {
        assertAwsError(() -> iot.testInvokeAuthorizer(r -> r.authorizerName(authorizer).token("allow-me")),
                InvalidRequestException.class, 400, "Request must provide both token and tokenSignature together.");
    }

    @Test
    @DisplayName("test-invoke rejects a token and signature on a signing-disabled authorizer")
    void testInvokeTokenOnSigningDisabledAuthorizer() throws GeneralSecurityException {
        String signature = sign(key1.getPrivate(), "allow-me");
        assertAwsError(() -> iot.testInvokeAuthorizer(r -> r.authorizerName(authorizer)
                        .token("allow-me").tokenSignature(signature)),
                InvalidRequestException.class, 400,
                "Token and token signature provided but signing is disabled for the authorizer.");
    }

    @Test
    @DisplayName("test-invoke rejects a token combined with a context")
    void testInvokeTokenWithContext() throws GeneralSecurityException {
        String signature = sign(key1.getPrivate(), "allow-me");
        assertAwsError(() -> iot.testInvokeAuthorizer(r -> r.authorizerName(signedAuthorizer)
                        .token("allow-me").tokenSignature(signature)
                        .mqttContext(m -> m.clientId(clientId).password(SdkBytes.fromUtf8String("allow-me")))),
                InvalidRequestException.class, 400, "Request cannot have token and Http/Mqtt/Tls context.");
    }

    @Test
    @DisplayName("test-invoke rejects a request without token or context")
    void testInvokeEmptyRequest() {
        assertAwsError(() -> iot.testInvokeAuthorizer(r -> r.authorizerName(authorizer)),
                InvalidRequestException.class, 400, "Request must have Http/Mqtt/Tls context.");
    }

    @Test
    @DisplayName("test-invoke rejects an HTTP query string without the leading '?'")
    void testInvokeQueryStringWithoutQuestionMark() {
        assertAwsError(() -> iot.testInvokeAuthorizer(r -> r.authorizerName(authorizer)
                        .httpContext(h -> h.headers(Map.of("X-Secret", "allow-me")).queryString("a=b"))),
                InvalidRequestException.class, 400, "Invalid arguments in the call");
    }

    @Test
    @DisplayName("test-invoke rejects an empty HTTP query string")
    void testInvokeEmptyQueryString() {
        assertAwsError(() -> iot.testInvokeAuthorizer(r -> r.authorizerName(authorizer)
                        .httpContext(h -> h.headers(Map.of("X-Secret", "allow-me")).queryString(""))),
                InvalidRequestException.class, 400,
                "1 validation error detected: Value at 'httpContext.queryString' failed to satisfy constraint: "
                        + "Member must have length greater than or equal to 1");
    }

    @Test
    @DisplayName("test-invoke rejects a token signature that is not base64")
    void testInvokeNonBase64Signature() {
        assertAwsError(() -> iot.testInvokeAuthorizer(r -> r.authorizerName(signedAuthorizer)
                        .token("allow-me").tokenSignature("not base64!")),
                InvalidRequestException.class, 400,
                "1 validation error detected: Value at 'tokenSignature' failed to satisfy constraint: "
                        + "Member must satisfy regular expression pattern: [A-Za-z0-9+/]+={0,2}");
    }

    @Test
    @DisplayName("test-invoke works on an INACTIVE authorizer")
    void testInvokeInactiveAuthorizer() {
        TestInvokeAuthorizerResponse response = invokeMqtt(inactiveAuthorizer, "allow-me");

        assertThat(response.isAuthenticated()).isTrue();
        assertThat(response.principalId()).isEqualTo(PRINCIPAL);
        assertThat(response.policyDocuments()).containsExactly(allowPolicy);
    }

    @Test
    @DisplayName("test-invoke on a nonexistent authorizer is 404")
    void testInvokeUnknownAuthorizer() {
        assertAwsError(() -> invokeMqtt(unknownAuthorizer, "allow-me"), ResourceNotFoundException.class, 404,
                "Authorizer " + unknownAuthorizer + " not found");
    }

    @Test
    @DisplayName("test-invoke in token mode verifies a signature by either registered key")
    void testInvokeSignedTokenEitherKey() throws GeneralSecurityException {
        for (KeyPair key : List.of(key1, key2)) {
            String signature = sign(key.getPrivate(), "allow-me");
            TestInvokeAuthorizerResponse response = iot.testInvokeAuthorizer(r -> r.authorizerName(signedAuthorizer)
                    .token("allow-me").tokenSignature(signature));

            assertThat(response.isAuthenticated()).isTrue();
            assertThat(response.principalId()).isEqualTo(PRINCIPAL);
            assertThat(response.policyDocuments()).containsExactly(allowPolicy);
        }
    }

    @Test
    @DisplayName("test-invoke in token mode sends only token, signatureVerified true and connectionMetadata")
    void testInvokeTokenModeEventShape() throws GeneralSecurityException {
        String token = "shape:ti-token";
        String signature = sign(key2.getPrivate(), token);
        assertShapeOk(iot.testInvokeAuthorizer(r -> r.authorizerName(signedAuthorizer)
                .token(token).tokenSignature(signature)));
    }

    @Test
    @DisplayName("test-invoke rejects a signature over another token or by an unregistered key")
    void testInvokeSignatureMismatch() throws GeneralSecurityException {
        String message = "Token signature mismatch for authorizer " + signedAuthorizer;
        String otherToken = sign(key1.getPrivate(), "other-token");
        String unregistered = sign(unregisteredKey.getPrivate(), "allow-me");

        assertAwsError(() -> iot.testInvokeAuthorizer(r -> r.authorizerName(signedAuthorizer)
                .token("allow-me").tokenSignature(otherToken)), InvalidRequestException.class, 400, message);
        assertAwsError(() -> iot.testInvokeAuthorizer(r -> r.authorizerName(signedAuthorizer)
                .token("allow-me").tokenSignature(unregistered)), InvalidRequestException.class, 400, message);
    }

    @Test
    @DisplayName("test-invoke with signature and token in the MQTT username verifies them and sends the token")
    void testInvokeSignedMqttUsername() throws GeneralSecurityException {
        String token = "shape:ti-signed-mqtt";
        String username = signedUsername(sign(key1.getPrivate(), token), token);
        assertShapeOk(iot.testInvokeAuthorizer(r -> r.authorizerName(signedAuthorizer)
                .mqttContext(m -> m.clientId(clientId).username(username))));
    }

    // ---------------------------------------------------------------------------------------------
    // MQTT CONNECT over WebSocket
    // ---------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mqtt.class)
    @DisplayName("an allowed CONNECT succeeds and publish/subscribe round-trips")
    void mqttAllowRoundTrip(Mqtt version) throws Exception {
        String topic = prefix + "/rt/" + version.name().toLowerCase();
        String payload = "hello-" + version.name();
        try (Session session = open(version, target(namedUsername(authorizer), "allow-me"))) {
            assertThat(session.roundTrip(topic, payload)).isEqualTo(payload);
        }
    }

    enum Refusal {
        DENY("deny", AuthorizerKind.ACTIVE, false),
        CLIENT_ID_NOT_IN_POLICY("allow-me", AuthorizerKind.ACTIVE, true),
        LAMBDA_RAISES("raise", AuthorizerKind.ACTIVE, false),
        GARBAGE_RESPONSE("garbage", AuthorizerKind.ACTIVE, false),
        EMPTY_POLICIES("emptypol", AuthorizerKind.ACTIVE, false),
        REFRESH_BELOW_RANGE("ttl:299:3600", AuthorizerKind.ACTIVE, false),
        DISCONNECT_ABOVE_RANGE("ttl:300:86401", AuthorizerKind.ACTIVE, false),
        UNKNOWN_AUTHORIZER("allow-me", AuthorizerKind.UNKNOWN, false),
        INACTIVE_AUTHORIZER("allow-me", AuthorizerKind.INACTIVE, false);

        final String secret;
        final AuthorizerKind authorizer;
        final boolean otherClient;

        Refusal(String secret, AuthorizerKind authorizer, boolean otherClient) {
            this.secret = secret;
            this.authorizer = authorizer;
            this.otherClient = otherClient;
        }
    }

    enum AuthorizerKind { ACTIVE, UNKNOWN, INACTIVE }

    static Stream<Arguments> refusals() {
        List<Arguments> cases = new ArrayList<>();
        for (Mqtt version : Mqtt.values()) {
            for (Refusal refusal : Refusal.values()) {
                cases.add(Arguments.of(version, refusal));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("refusals")
    @DisplayName("a CONNECT the authorizer does not allow is refused")
    void mqttRefused(Mqtt version, Refusal refusal) {
        String name = switch (refusal.authorizer) {
            case ACTIVE -> authorizer;
            case UNKNOWN -> unknownAuthorizer;
            case INACTIVE -> inactiveAuthorizer;
        };
        String client = refusal.otherClient ? otherClientId : clientId;
        assertRefused(version, new Target(mqttUri, client, client + "?" + NAME_PARAM + name,
                refusal.secret.getBytes(StandardCharsets.UTF_8), Map.of()));
    }

    static Stream<Arguments> acceptedForms() {
        List<Arguments> cases = new ArrayList<>();
        for (Mqtt version : Mqtt.values()) {
            for (String secret : List.of("objpolicy", "ttl:-:-", "ttl:300:86400")) {
                cases.add(Arguments.of(version, secret));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("acceptedForms")
    @DisplayName("an object-form policy, omitted TTLs and boundary TTLs are accepted on a real CONNECT")
    void mqttAcceptedForms(Mqtt version, String secret) throws Exception {
        assertConnects(version, target(namedUsername(authorizer), secret));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mqtt.class)
    @DisplayName("the authorizer name is read from the WebSocket URL query")
    void mqttNameInWebSocketQuery(Mqtt version) throws Exception {
        assertConnects(version, new Target(mqttUri + "?" + NAME_PARAM + authorizer, clientId, clientId,
                "allow-me".getBytes(StandardCharsets.UTF_8), Map.of()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mqtt.class)
    @DisplayName("the authorizer name is read from the WebSocket upgrade header")
    void mqttNameInUpgradeHeader(Mqtt version) throws Exception {
        assertConnects(version, new Target(mqttUri, clientId, clientId,
                "allow-me".getBytes(StandardCharsets.UTF_8), Map.of(NAME_HEADER, authorizer)));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mqtt.class)
    @DisplayName("WebSocket event: name in the username, empty query, lower-case headers")
    void mqttEventShapeNameInUsername(Mqtt version) throws Exception {
        assertConnects(version, target(namedUsername(authorizer), "shape:ws-user"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mqtt.class)
    @DisplayName("WebSocket event: name in the URL query, query string without '?', plain username")
    void mqttEventShapeNameInQuery(Mqtt version) throws Exception {
        assertConnects(version, new Target(mqttUri + "?" + NAME_PARAM + authorizer, clientId, clientId,
                "shape:ws-query".getBytes(StandardCharsets.UTF_8), Map.of()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mqtt.class)
    @DisplayName("WebSocket event: name in the upgrade header, lower-cased among the headers")
    void mqttEventShapeNameInHeader(Mqtt version) throws Exception {
        assertConnects(version, new Target(mqttUri, clientId, clientId,
                "shape:ws-header".getBytes(StandardCharsets.UTF_8), Map.of(NAME_HEADER, authorizer)));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mqtt.class)
    @DisplayName("a signed authorizer accepts a valid signature and token in the username")
    void mqttSignedAllow(Mqtt version) throws Exception {
        String username = signedUsername(sign(key1.getPrivate(), "allow-me"), "allow-me");
        assertConnects(version, new Target(mqttUri, clientId, username, null, Map.of()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mqtt.class)
    @DisplayName("a signed CONNECT sends the token with signatureVerified true")
    void mqttSignedEventShape(Mqtt version) throws Exception {
        String token = "shape:ws-signed";
        String username = signedUsername(sign(key2.getPrivate(), token), token);
        assertConnects(version, new Target(mqttUri, clientId, username, null, Map.of()));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Mqtt.class)
    @DisplayName("a signed authorizer refuses a signature over another token")
    void mqttSignedWrongSignature(Mqtt version) throws GeneralSecurityException {
        String username = signedUsername(sign(key1.getPrivate(), "other-token"), "allow-me");
        assertRefused(version, new Target(mqttUri, clientId, username, null, Map.of()));
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private String createRole() {
        String trust = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";
        String roleArn = iam.createRole(r -> r.roleName(roleName).assumeRolePolicyDocument(trust)
                .description("floci compatibility test, IoT custom authorizer")).role().arn();
        roleCreated = true;
        String logsPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":"
                + "[\"logs:CreateLogStream\",\"logs:PutLogEvents\"],\"Resource\":\"*\"}]}";
        iam.putRolePolicy(r -> r.roleName(roleName).policyName(rolePolicyName).policyDocument(logsPolicy));
        return roleArn;
    }

    private String createFunction(String roleArn, String arnPrefix) throws InterruptedException {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("ALLOW_POLICY", allowPolicy);
        environment.put("IOT_ARN_PREFIX", arnPrefix);
        environment.put("ALLOWED_CLIENT_ID", clientId);
        environment.put("NAME_PREFIX", prefix);
        environment.put("SHAPE_SERVER_NAME", SHAPE_SERVER_NAME);
        SdkBytes zip = SdkBytes.fromByteArray(zip("lambda_function.py", LAMBDA_SOURCE));
        // The role may write to this group but not create one, so a late log delivery after the
        // tear-down cannot re-create it.
        logs.createLogGroup(r -> r.logGroupName("/aws/lambda/" + functionName));
        logGroupCreated = true;

        String functionArn = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (functionArn == null) {
            try {
                functionArn = lambda.createFunction(r -> r.functionName(functionName)
                        .runtime(Runtime.PYTHON3_12)
                        .role(roleArn)
                        .handler("lambda_function.handler")
                        .timeout(10)
                        .memorySize(128)
                        .environment(e -> e.variables(environment))
                        .code(c -> c.zipFile(zip))).functionArn();
            } catch (InvalidParameterValueException e) {
                if (System.nanoTime() > deadline || !e.getMessage().contains("cannot be assumed")) {
                    throw e;
                }
                LOG.infov("waiting for the IAM role to become assumable: {0}", e.awsErrorDetails().errorMessage());
                Thread.sleep(3000);
            }
        }
        functionCreated = true;

        long activeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (lambda.getFunctionConfiguration(r -> r.functionName(functionName)).state() != State.ACTIVE) {
            if (System.nanoTime() > activeDeadline) {
                throw new IllegalStateException("function " + functionName + " did not become Active");
            }
            Thread.sleep(1000);
        }
        return functionArn;
    }

    private void createAuthorizer(String name, String functionArn, AuthorizerStatus status, boolean signed)
            throws GeneralSecurityException {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("k1", pem(key1.getPublic()));
        keys.put("k2", pem(key2.getPublic()));
        String authorizerArn = iot.createAuthorizer(r -> {
            r.authorizerName(name).authorizerFunctionArn(functionArn).status(status);
            if (signed) {
                r.signingDisabled(false).tokenKeyName(TOKEN_KEY_NAME).tokenSigningPublicKeys(keys);
            } else {
                r.signingDisabled(true);
            }
        }).authorizerArn();
        createdAuthorizers.add(name);
        lambda.addPermission(r -> r.functionName(functionName)
                .statementId(name + "-invoke")
                .action("lambda:InvokeFunction")
                .principal("iot.amazonaws.com")
                .sourceArn(authorizerArn));
    }

    /**
     * Right after creation AWS IoT sporadically refuses a valid CONNECT through a new authorizer without invoking
     * the Lambda. Wait for three consecutive allowed CONNECTs through each ACTIVE authorizer before the tests
     * start. Not getting there is left to the tests to report.
     */
    private void awaitAuthorizerReady() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        int consecutive = 0;
        int attempt = 0;
        while (consecutive < 3 && System.nanoTime() < deadline) {
            attempt++;
            boolean ok = connects(target(namedUsername(authorizer), "allow-me"))
                    && connects(new Target(mqttUri, clientId, signedUsername(signOrFail("allow-me"), "allow-me"),
                            null, Map.of()));
            consecutive = ok ? consecutive + 1 : 0;
            if (!ok) {
                Thread.sleep(2000);
            }
        }
        LOG.infov("authorizers ready={0} after {1} round(s)", consecutive >= 3, attempt);
    }

    private boolean connects(Target target) throws InterruptedException {
        try (Session ignored = open(Mqtt.V3_1_1, target)) {
            return true;
        } catch (ConnectRefused | GeneralSecurityException e) {
            LOG.infov("not ready: {0}", e.toString());
            return false;
        }
    }

    private String signOrFail(String token) {
        try {
            return sign(key1.getPrivate(), token);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String mqttBaseUri() {
        if (TestFixtures.isRealAws()) {
            String host = iot.describeEndpoint(r -> r.endpointType("iot:Data-ATS")).endpointAddress();
            return "wss://" + host + ":443/mqtt";
        }
        URI endpoint = TestFixtures.endpoint();
        boolean secure = "https".equalsIgnoreCase(endpoint.getScheme());
        int port = endpoint.getPort() != -1 ? endpoint.getPort() : secure ? 443 : 80;
        return (secure ? "wss://" : "ws://") + endpoint.getHost() + ":" + port + "/mqtt";
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private TestInvokeAuthorizerResponse invokeMqtt(String name, String secret) {
        return iot.testInvokeAuthorizer(TestInvokeAuthorizerRequest.builder()
                .authorizerName(name)
                .mqttContext(m -> m.clientId(clientId)
                        .username(clientId + "?" + NAME_PARAM + name)
                        .password(SdkBytes.fromUtf8String(secret)))
                .build());
    }

    private static void assertShapeOk(TestInvokeAuthorizerResponse response) {
        assertThat(response.principalId())
                .as("the Lambda reports shapebad when the event differs from the AWS shape (see its log)")
                .isEqualTo("shapeok");
        assertThat(response.isAuthenticated()).isTrue();
    }

    private static <T extends AwsServiceException> void assertAwsError(ThrowingCallable call, Class<T> type,
            int status, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(type, e -> {
            assertThat(e.statusCode()).isEqualTo(status);
            assertThat(e.awsErrorDetails().errorMessage()).isEqualTo(message);
        });
    }

    private String namedUsername(String name) {
        return clientId + "?" + NAME_PARAM + name;
    }

    private String signedUsername(String signature, String token) {
        return clientId + "?" + NAME_PARAM + signedAuthorizer
                + "&" + SIGNATURE_PARAM + URLEncoder.encode(signature, StandardCharsets.UTF_8)
                + "&" + TOKEN_KEY_NAME + "=" + token;
    }

    private Target target(String username, String secret) {
        return new Target(mqttUri, clientId, username, secret.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static String sign(PrivateKey key, String token) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(token.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static String pem(PublicKey key) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----";
    }

    private static byte[] zip(String entryName, String content) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(content.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("cannot build the Lambda deployment package", e);
        }
    }

    private static void quietly(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.warnv("cleanup step failed: {0}: {1}", what, e.toString());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // MQTT clients
    // ---------------------------------------------------------------------------------------------

    private record Target(String uri, String clientId, String username, byte[] password,
            Map<String, String> headers) {}

    private interface Session extends AutoCloseable {
        String roundTrip(String topic, String payload) throws Exception;

        @Override
        void close();
    }

    /** A refused CONNECT, carrying the client's own exception as the cause. */
    private static final class ConnectRefused extends Exception {
        ConnectRefused(Throwable cause) {
            super("CONNECT failed: " + cause, cause);
        }
    }

    private void assertConnects(Mqtt version, Target target) throws Exception {
        try (Session ignored = open(version, target)) {
            LOG.infov("{0} connected as {1}", version, target.username());
        }
    }

    private void assertRefused(Mqtt version, Target target) {
        Throwable thrown = catchThrowable(() -> open(version, target).close());
        if (!(thrown instanceof ConnectRefused refused)) {
            fail("expected the CONNECT to be refused, got " + thrown);
            return;
        }
        Throwable cause = refused.getCause();
        LOG.infov("{0} refused: {1} cause={2}", version, cause, cause.getCause());
        if (version == Mqtt.V3_1_1) {
            assertThat(cause).isInstanceOfSatisfying(MqttException.class,
                    e -> assertThat(e.getReasonCode()).isEqualTo(MQTT3_REFUSAL_REASON_CODE));
        } else {
            assertThat(cause).isInstanceOfSatisfying(org.eclipse.paho.mqttv5.common.MqttException.class,
                    e -> assertThat(e.getReasonCode()).isEqualTo(MQTT5_NOT_AUTHORIZED));
        }
    }

    private Session open(Mqtt version, Target target)
            throws ConnectRefused, GeneralSecurityException, InterruptedException {
        Long last = lastConnectNanos.get(target.clientId());
        if (last != null) {
            long waitNanos = last + CONNECT_SPACING_NANOS - System.nanoTime();
            if (waitNanos > 0) {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            }
        }
        lastConnectNanos.put(target.clientId(), System.nanoTime());
        return version == Mqtt.V3_1_1 ? openV3(target) : openV5(target);
    }

    private Session openV3(Target target) throws ConnectRefused, GeneralSecurityException {
        MqttClient client;
        try {
            client = new MqttClient(target.uri(), target.clientId(), new MemoryPersistence());
        } catch (MqttException e) {
            throw new IllegalStateException(e);
        }
        MqttConnectOptions options = new MqttConnectOptions();
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setCleanSession(true);
        options.setAutomaticReconnect(false);
        options.setConnectionTimeout(TIMEOUT_SECONDS);
        options.setKeepAliveInterval(30);
        if (target.username() != null) {
            options.setUserName(target.username());
        }
        if (target.password() != null) {
            options.setPassword(new String(target.password(), StandardCharsets.UTF_8).toCharArray());
        }
        if (!target.headers().isEmpty()) {
            Properties headers = new Properties();
            headers.putAll(target.headers());
            options.setCustomWebSocketHeaders(headers);
        }
        if (needsEmulatorTls(target)) {
            options.setSocketFactory(emulatorSocketFactory());
            options.setHttpsHostnameVerificationEnabled(false);
        }
        try {
            client.connect(options);
        } catch (MqttException e) {
            closeV3(client);
            throw new ConnectRefused(e);
        }
        return new Session() {
            @Override
            public String roundTrip(String topic, String payload) throws Exception {
                CompletableFuture<String> received = new CompletableFuture<>();
                client.subscribe(topic, 1, (t, message) ->
                        received.complete(new String(message.getPayload(), StandardCharsets.UTF_8)));
                client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, false);
                return received.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            @Override
            public void close() {
                closeV3(client);
            }
        };
    }

    private static void closeV3(MqttClient client) {
        try {
            if (client.isConnected()) {
                client.disconnect(5000);
            }
        } catch (MqttException e) {
            LOG.debugv("MQTT 3.1.1 disconnect failed: {0}", e.toString());
        }
        try {
            client.close();
        } catch (MqttException e) {
            LOG.debugv("MQTT 3.1.1 close failed: {0}", e.toString());
        }
    }

    private Session openV5(Target target) throws ConnectRefused, GeneralSecurityException {
        MqttAsyncClient client;
        try {
            client = new MqttAsyncClient(target.uri(), target.clientId(),
                    new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        } catch (org.eclipse.paho.mqttv5.common.MqttException e) {
            throw new IllegalStateException(e);
        }
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        client.setCallback(new MessageCallback(message -> messages.add(message)));
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setCleanStart(true);
        options.setAutomaticReconnect(false);
        options.setConnectionTimeout(TIMEOUT_SECONDS);
        options.setKeepAliveInterval(30);
        if (target.username() != null) {
            options.setUserName(target.username());
        }
        if (target.password() != null) {
            options.setPassword(target.password());
        }
        if (!target.headers().isEmpty()) {
            options.setCustomWebSocketHeaders(target.headers());
        }
        if (needsEmulatorTls(target)) {
            options.setSocketFactory(emulatorSocketFactory());
            options.setHttpsHostnameVerificationEnabled(false);
        }
        try {
            client.connect(options).waitForCompletion(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        } catch (org.eclipse.paho.mqttv5.common.MqttException e) {
            closeV5(client);
            throw new ConnectRefused(e);
        }
        if (!client.isConnected()) {
            closeV5(client);
            throw new ConnectRefused(new IllegalStateException("CONNECT did not complete"));
        }
        return new Session() {
            @Override
            public String roundTrip(String topic, String payload) throws Exception {
                client.subscribe(topic, 1).waitForCompletion(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, false)
                        .waitForCompletion(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                return messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            @Override
            public void close() {
                closeV5(client);
            }
        };
    }

    private static void closeV5(MqttAsyncClient client) {
        try {
            if (client.isConnected()) {
                client.disconnect().waitForCompletion(5000);
            }
        } catch (org.eclipse.paho.mqttv5.common.MqttException e) {
            LOG.debugv("MQTT 5 disconnect failed: {0}", e.toString());
        }
        try {
            client.close();
        } catch (org.eclipse.paho.mqttv5.common.MqttException e) {
            LOG.debugv("MQTT 5 close failed: {0}", e.toString());
        }
    }

    private static final class MessageCallback implements MqttCallback {
        private final Consumer<String> sink;

        MessageCallback(Consumer<String> sink) {
            this.sink = sink;
        }

        @Override
        public void disconnected(MqttDisconnectResponse response) {
            LOG.debugv("MQTT 5 disconnected: {0}", response);
        }

        @Override
        public void mqttErrorOccurred(org.eclipse.paho.mqttv5.common.MqttException exception) {
            LOG.debugv("MQTT 5 error: {0}", exception.toString());
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            sink.accept(new String(message.getPayload(), StandardCharsets.UTF_8));
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

    private static boolean needsEmulatorTls(Target target) {
        return !TestFixtures.isRealAws() && target.uri().startsWith("wss://");
    }

    /** Floci's local TLS endpoint uses a test CA, so the emulator-only WebSocket trusts it like emulatorHttpClient. */
    private static SSLSocketFactory emulatorSocketFactory() throws GeneralSecurityException {
        X509TrustManager trustAll = new X509TrustManager() {
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
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trustAll}, new SecureRandom());
        return context.getSocketFactory();
    }
}
