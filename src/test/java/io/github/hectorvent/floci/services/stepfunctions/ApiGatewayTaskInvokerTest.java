package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.core.common.auth.SigV4AuthorizationHeader;
import io.github.hectorvent.floci.core.common.auth.SigV4Canonicalization;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.model.RestApi;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiGatewayTaskInvokerTest {
    private static final String ACCOUNT = "111111111111";
    private static final String ROLE = "arn:aws:iam::111111111111:role/OwnedRole";
    private static final String TRUST = "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"states.amazonaws.com\"},"
            + "\"Action\":\"sts:AssumeRole\"}]}";
    private static final String ALLOW = "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"execute-api:Invoke\","
            + "\"Resource\":\"arn:aws:execute-api:eu-west-1:111111111111:owned/gamma/POST/create-workflow-execution\"}]}";
    private final ObjectMapper mapper = new ObjectMapper();
    private final IamService iam = mock(IamService.class);
    private final ApiGatewayService api = mock(ApiGatewayService.class);
    private final AtomicReference<String[]> credentials = new AtomicReference<>();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{\"workflow_id\":\"owned\"}");
    private ApiGatewayTaskInvoker invoker;
    // Quarkus and the JDK test server share the HttpServer simple name.
    private final io.quarkus.vertx.http.HttpServer listener = mock(io.quarkus.vertx.http.HttpServer.class);
    private HttpServer server;
    private StateMachine machine;
    private ObjectNode request;
    private IamRole role;
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final Instance<ScpProvider> scps = mock(Instance.class);
    private final AtomicReference<List<String>> receivedHeaders = new AtomicReference<>();
    private final AtomicReference<String> observedHeader = new AtomicReference<>("x_test");

    @BeforeEach
    void setup() throws Exception {
        IamPolicyEvaluator policies = new IamPolicyEvaluator(mapper);
        AssumeRolePolicyEvaluator trust = new AssumeRolePolicyEvaluator(mapper, policies);
        Instance<ResourcePolicyProvider> resourcePolicies = mock(Instance.class);
        when(resourcePolicies.isUnsatisfied()).thenReturn(true);
        LambdaTaskAuthorizer authorizer = new LambdaTaskAuthorizer(iam, policies, trust, resourcePolicies, scps);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        invoker = new ApiGatewayTaskInvoker(api, iam, trust, authorizer, config, mapper, listener);
        machine = new StateMachine();
        machine.setStateMachineArn("arn:aws:states:eu-west-1:" + ACCOUNT + ":stateMachine:Owned");
        machine.setRoleArn(ROLE);
        role = new IamRole("AROAOWNED", "OwnedRole", "/", ROLE, TRUST);
        when(iam.findRole(ACCOUNT, "OwnedRole")).thenReturn(Optional.of(role));
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(ALLOW)));
        when(api.findRestApiOwner("owned")).thenReturn(Optional.of(new ApiGatewayService.ApiOwner(ACCOUNT, "eu-west-1")));
        when(api.getRestApi("eu-west-1", "owned")).thenReturn(new RestApi());
        doAnswer(call -> {
            credentials.set(new String[]{call.getArgument(0), call.getArgument(1), call.getArgument(2)});
            return null;
        }).when(iam).registerSession(anyString(), anyString(), anyString(), eq(ROLE), any(Instant.class), isNull(),
                eq(ACCOUNT), anyString(), anyString());
        request = (ObjectNode) mapper.readTree("""
                {"ApiEndpoint":"owned.execute-api.eu-west-1.amazonaws.com","Method":"POST","Stage":"gamma",
                 "Path":"create-workflow-execution","AuthType":"IAM_ROLE",
                 "Headers":{"x-api-key":["owned-key"]},"RequestBody":{"stack":"owned","value":42}}
                """);
        startServer("127.0.0.1");
    }

    private void startServer(String host) throws Exception {
        server = HttpServer.create(new InetSocketAddress(host, 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                received.set(mapper.readTree(body));
                receivedHeaders.set(exchange.getRequestHeaders().get(observedHeader.get()));
                assertEquals("/restapis/owned/gamma/_user_request_/create-workflow-execution", exchange.getRequestURI().getPath());
                assertEquals("owned-key", exchange.getRequestHeaders().getFirst("x-api-key"));
                SigV4AuthorizationHeader auth = SigV4AuthorizationHeader.parse(exchange.getRequestHeaders().getFirst("Authorization"));
                CredentialScope scope = CredentialScope.parse(auth.credential());
                String[] issued = credentials.get();
                assertEquals(issued[0], scope.accessKeyId());
                assertEquals(issued[2], exchange.getRequestHeaders().getFirst("x-amz-security-token"));
                assertTrue(SigV4Canonicalization.signatureMatches("POST", exchange.getRequestURI().getRawPath(), "",
                        SigV4Canonicalization.canonicalHeaders(auth.signedHeaders(), name -> exchange.getRequestHeaders().get(name).stream()
                                .map(SigV4RequestValidator::normalizeHeaderValue).collect(Collectors.joining(","))),
                        auth.signedHeaders(), Digests.sha256Hex(body),
                        exchange.getRequestHeaders().getFirst("x-amz-date"), scope, issued[1], auth.signature()));
            } catch (Throwable error) {
                serverFailure.set(error);
            }
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        when(listener.getLocalBaseUri()).thenReturn(new URI("http", null, host, server.getAddress().getPort(), "/", null, null));
    }

    @AfterEach
    void cleanup() {
        if (invoker != null) {
            invoker.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void signedSelectedRequestUsesOwnedRoleAndRevokesSession() throws Exception {
        JsonNode result = invoker.invoke(request, machine, null);
        assertEquals(200, result.path("StatusCode").asInt());
        assertEquals("OK", result.path("StatusText").asText());
        assertEquals("owned", result.path("ResponseBody").path("workflow_id").asText());
        assertEquals(request.path("RequestBody"), received.get());
        assertEquals(null, serverFailure.get());
        verify(iam).unregisterSession(ACCOUNT, credentials.get()[0]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-AmzFoo", "X-Amznn-Test", "X-ForwardedFoo", "X-Amz", "X-Amzn", "X-Forwarded"})
    void headersOutsideTheReservedPrefixesReachTheApi(String header) throws Exception {
        observedHeader.set(header);
        ((ObjectNode) request.get("Headers")).putArray(header).add("preserved");

        JsonNode result = invoker.invoke(request, machine, null);

        assertEquals(200, result.path("StatusCode").asInt());
        assertEquals(List.of("preserved"), receivedHeaders.get());
        assertEquals(null, serverFailure.get());
        verify(iam).unregisterSession(ACCOUNT, credentials.get()[0]);
    }

    @ParameterizedTest
    @CsvSource({"200 OK,200", "null trailing,null"})
    void scalarResponseUsesTheFirstJsonValueAsAwsDoes(String body, String expectedJson) throws Exception {
        responseBody.set(body);
        JsonNode result = invoker.invoke(request, machine, null);
        assertEquals(200, result.path("StatusCode").asInt());
        assertEquals(mapper.readTree(expectedJson), result.get("ResponseBody"));
        assertEquals(null, serverFailure.get());
        verify(iam).unregisterSession(ACCOUNT, credentials.get()[0]);
    }

    @Test
    void httpErrorRemainsCatchableAndRevokesSession() {
        status.set(500);
        AslExecutor.FailStateException error = assertThrows(AslExecutor.FailStateException.class,
                () -> invoker.invoke(request, machine, null));
        assertEquals("ApiGateway.500", error.error);
        verify(iam).unregisterSession(ACCOUNT, credentials.get()[0]);
        assertEquals(null, serverFailure.get());
    }

    @Test
    void transportFailureUsesExceptionCodeAndRevokesSession() {
        server.stop(0);
        server = null;
        AslExecutor.FailStateException error = assertThrows(AslExecutor.FailStateException.class,
                () -> invoker.invoke(request, machine, null));
        assertEquals("ApiGateway.ConnectException", error.error);
        verify(iam).unregisterSession(ACCOUNT, credentials.get()[0]);
    }

    @Test
    void deniedRoleCannotSendRequestOrMintCredential() {
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
        denied("ApiGateway.403");
        assertEquals(0, calls.get());
        assertEquals(null, credentials.get());
    }

    @Test
    void wrongTrustAndForeignApiAreRefused() {
        role.setAssumeRolePolicyDocument(TRUST.replace("states.amazonaws.com", "lambda.amazonaws.com"));
        denied("ApiGateway.403");
        role.setAssumeRolePolicyDocument(TRUST);
        when(api.findRestApiOwner("owned")).thenReturn(Optional.of(new ApiGatewayService.ApiOwner("222222222222", "eu-west-1")));
        denied("ApiGateway.403");
        assertEquals(0, calls.get());
    }

    @Test
    void unsupportedEndpointsAndCallerSigningHeadersNeverReachTransport() {
        request.put("ApiEndpoint", "owned.execute-api.us-east-1.amazonaws.com");
        denied("States.TaskFailed");
        request.put("ApiEndpoint", "127.0.0.1");
        denied("States.TaskFailed");
        request.put("ApiEndpoint", "owned.execute-api.eu-west-1.amazonaws.com");
        ((ObjectNode) request.path("Headers")).putArray("Authorization").add("forged");
        denied("States.TaskFailed");
        assertEquals(0, calls.get());
        assertEquals(null, credentials.get());
        verify(iam, never()).unregisterSession(anyString(), anyString());
    }

    @Test
    void optionalRequestAndResourcePolicyFeaturesRefuseBeforeTransport() {
        request.putObject("QueryParameters").putArray("key").add("value");
        denied("States.TaskFailed");
        request.remove("QueryParameters");
        request.put("AllowNullValues", true);
        denied("States.TaskFailed");
        request.remove("AllowNullValues");
        ((ObjectNode) request.path("RequestBody")).putNull("optional");
        denied("States.TaskFailed");
        ((ObjectNode) request.path("RequestBody")).remove("optional");
        RestApi resourcePolicyApi = new RestApi();
        resourcePolicyApi.setPolicy("{}");
        when(api.getRestApi("eu-west-1", "owned")).thenReturn(resourcePolicyApi);
        denied("States.TaskFailed");
        assertEquals(0, calls.get());
        assertEquals(null, credentials.get());
    }

    @Test
    void enforcementOffDoesNotRequireAnExistingExecutionRole() throws Exception {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        when(iam.findRole(ACCOUNT, "OwnedRole")).thenReturn(Optional.empty());
        assertEquals(200, invoker.invoke(request, machine, null).path("StatusCode").asInt());
        assertEquals(null, serverFailure.get());
    }

    @Test
    void multipleValuesAndTokenHeaderNamesAreSigned() throws Exception {
        ((ObjectNode) request.path("Headers")).putArray("x_test").add("one").add("two");
        assertEquals(200, invoker.invoke(request, machine, null).path("StatusCode").asInt());
        assertEquals(List.of("one", "two"), receivedHeaders.get());
        assertEquals(null, serverFailure.get());
    }

    @Test
    void missingRequiredMembersAndInvalidEnumsNeverMintCredentials() {
        ObjectNode original = request.deepCopy();
        for (String member : List.of("ApiEndpoint", "Method", "Stage", "Path", "AuthType", "RequestBody")) {
            request = original.deepCopy();
            request.remove(member);
            denied("States.TaskFailed");
        }
        for (String member : List.of("Method", "AuthType")) {
            request = original.deepCopy();
            request.put(member, "invalid");
            denied("States.TaskFailed");
        }
        assertEquals(0, calls.get());
        assertEquals(null, credentials.get());
    }

    @Test
    void reservedAndInvalidHeadersNeverReachTransport() {
        for (String name : List.of("Expect", "Upgrade", "Content-MD5", "TE", "X-Amzn-Test", "X-Forwarded-For",
                "bad header")) {
            request.putObject("Headers").putArray(name).add("value");
            denied("States.TaskFailed");
        }
        request.putObject("Headers").putArray("x-test").add("bad\r\nvalue");
        denied("States.TaskFailed");
        assertEquals(0, calls.get());
        assertEquals(null, credentials.get());
    }

    @Test
    void taskCredentialsSelectTheInvokingRole() throws Exception {
        String selected = ROLE.replace("OwnedRole", "TaskRole");
        IamRole taskRole = new IamRole("AROATASK", "TaskRole", "/", selected,
                "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"" + ROLE
                        + "\"},\"Action\":\"sts:AssumeRole\"}]}");
        when(iam.findRole(ACCOUNT, "TaskRole")).thenReturn(Optional.of(taskRole));
        when(iam.resolvePrincipalContext(selected)).thenReturn(CallerContext.of(List.of(ALLOW)));
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
        doAnswer(call -> {
            credentials.set(new String[]{call.getArgument(0), call.getArgument(1), call.getArgument(2)});
            return null;
        }).when(iam).registerSession(anyString(), anyString(), anyString(), eq(selected), any(Instant.class), isNull(),
                eq(ACCOUNT), anyString(), anyString());
        assertEquals(200, invoker.invoke(request, machine, selected).path("StatusCode").asInt());
        assertEquals(null, serverFailure.get());
        verify(iam).unregisterSession(ACCOUNT, credentials.get()[0]);
        when(iam.resolvePrincipalContext(selected)).thenReturn(CallerContext.of(List.of()));
        AslExecutor.FailStateException error = assertThrows(AslExecutor.FailStateException.class,
                () -> invoker.invoke(request, machine, selected));
        assertEquals("ApiGateway.403", error.error);
        assertEquals(1, calls.get());
    }

    @Test
    void scpDenialPreventsIssuingCredentials() {
        ScpProvider provider = mock(ScpProvider.class);
        when(scps.isResolvable()).thenReturn(true);
        when(scps.get()).thenReturn(provider);
        when(provider.effectiveScpLevels(ACCOUNT)).thenReturn(List.of(List.of(
                "{\"Statement\":[{\"Effect\":\"Deny\",\"Action\":\"execute-api:Invoke\",\"Resource\":\"*\"}]}")));
        denied("ApiGateway.403");
        assertEquals(0, calls.get());
        assertEquals(null, credentials.get());
    }

    @Test
    void ipv6OnlyListenerReceivesSignedRequest() throws Exception {
        server.stop(0);
        startServer("::1");
        assertEquals(200, invoker.invoke(request, machine, null).path("StatusCode").asInt());
        assertEquals(null, serverFailure.get());
    }

    @Test
    void matchingContentLengthUsesTheTransportGeneratedHeader() throws Exception {
        int length = mapper.writeValueAsBytes(request.path("RequestBody")).length;
        ((ObjectNode) request.path("Headers")).putArray("Content-Length").add(Integer.toString(length));
        assertEquals(200, invoker.invoke(request, machine, null).path("StatusCode").asInt());
        assertEquals(null, serverFailure.get());
    }

    private void denied(String expected) {
        AslExecutor.FailStateException error = assertThrows(AslExecutor.FailStateException.class,
                () -> invoker.invoke(request, machine, null));
        assertEquals(expected, error.error);
    }
}
