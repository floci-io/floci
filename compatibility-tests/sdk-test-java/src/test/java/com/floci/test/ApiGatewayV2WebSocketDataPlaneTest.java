package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.DeleteConnectionRequest;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GetConnectionRequest;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GetConnectionResponse;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.*;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WebSocket data-plane end-to-end compatibility tests using the AWS SDK v2 Java client.
 *
 * Mirrors the Node.js compatibility tests in
 * {@code compatibility-tests/sdk-test-node/tests/apigatewayv2-websocket-dataplane.test.ts}.
 *
 * Covers 8 test suites:
 * 1. Basic WebSocket flow — connect, send, receive, disconnect
 * 2. Chat-style broadcast — two clients, Lambda uses @connections API to broadcast
 * 3. $connect authorization — Lambda authorizer allows/denies based on query string token
 * 4. Route selection — multiple routes dispatched correctly
 * 5. @connections API — POST sends message, GET returns info, DELETE disconnects
 * 6. Stage variables — integration URI with ${stageVariables.functionName} resolves
 * 7. Mock integration — $connect with MOCK integration, no Lambda needed
 * 8. Disconnect cleanup — after disconnect, @connections POST returns 410
 */
@DisplayName("API Gateway v2 — WebSocket data-plane")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2WebSocketDataPlaneTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";
    private static final String STAGE = "test";

    private static ApiGatewayV2Client gw;
    private static LambdaClient lambda;
    private static HttpClient http;

    private static final List<String> createdApis = new ArrayList<>();
    private static final List<String> createdFunctions = new ArrayList<>();

    private static boolean lambdaAvailable;

    // ── Lambda handler source code ───────────────────────────────────────────

    private static final String ECHO_HANDLER = """
            exports.handler = async (event) => {
                const body = event.body || '';
                try {
                    const parsed = JSON.parse(body);
                    if (parsed.action === 'getConnectionId') {
                        return { statusCode: 200, body: JSON.stringify({ connectionId: event.requestContext.connectionId }) };
                    }
                } catch (e) {}
                return { statusCode: 200, body: body || 'echo' };
            };
            """;

    private static final String BROADCAST_HANDLER = """
            const http = require('http');
            exports.handler = async (event) => {
                const body = JSON.parse(event.body);
                const apiId = event.requestContext.apiId;
                const stage = event.requestContext.stage;
                const endpoint = new URL(process.env.FLOCI_ENDPOINT || 'http://host.docker.internal:4566');
                const promises = body.targets.map(connId => {
                    return new Promise((resolve, reject) => {
                        const postData = body.message;
                        const options = {
                            hostname: endpoint.hostname,
                            port: endpoint.port || 80,
                            path: '/execute-api/' + apiId + '/' + stage + '/@connections/' + encodeURIComponent(connId),
                            method: 'POST',
                            headers: { 'Content-Type': 'application/octet-stream', 'Content-Length': Buffer.byteLength(postData) },
                        };
                        const req = http.request(options, (res) => {
                            let data = '';
                            res.on('data', chunk => data += chunk);
                            res.on('end', () => resolve({ statusCode: res.statusCode, body: data }));
                        });
                        req.on('error', reject);
                        req.write(postData);
                        req.end();
                    });
                });
                await Promise.all(promises);
                return { statusCode: 200 };
            };
            """;

    private static final String AUTHORIZER_HANDLER = """
            exports.handler = async (event) => {
                const token = event.queryStringParameters && event.queryStringParameters.token;
                const effect = token === 'allow' ? 'Allow' : 'Deny';
                return {
                    principalId: 'user',
                    policyDocument: {
                        Version: '2012-10-17',
                        Statement: [{ Action: 'execute-api:Invoke', Effect: effect, Resource: event.methodArn || '*' }],
                    },
                };
            };
            """;

    private static final String PING_HANDLER = """
            exports.handler = async (event) => {
                return { statusCode: 200, body: 'pong' };
            };
            """;

    private static final String SEND_MESSAGE_HANDLER = """
            exports.handler = async (event) => {
                const body = JSON.parse(event.body);
                return { statusCode: 200, body: 'received: ' + body.data };
            };
            """;

    private static final String DEFAULT_HANDLER = """
            exports.handler = async (event) => {
                return { statusCode: 200, body: 'default-route' };
            };
            """;

    // ── Setup / Teardown ─────────────────────────────────────────────────────

    @BeforeAll
    static void setup() {
        gw = TestFixtures.apiGatewayV2Client();
        lambda = TestFixtures.lambdaClient();
        http = HttpClient.newHttpClient();
        lambdaAvailable = TestFixtures.isLambdaDispatchAvailable();
    }

    @AfterAll
    static void cleanup() {
        for (String apiId : createdApis) {
            try { gw.deleteApi(DeleteApiRequest.builder().apiId(apiId).build()); } catch (Exception ignored) {}
        }
        for (String fn : createdFunctions) {
            try { lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(fn).build()); } catch (Exception ignored) {}
        }
        if (gw != null) gw.close();
        if (lambda != null) lambda.close();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String createLambda(String prefix, String code) {
        return createLambda(prefix, code, null);
    }

    private static String createLambda(String prefix, String code, Map<String, String> environment) {
        String fnName = TestFixtures.uniqueName(prefix);
        CreateFunctionRequest.Builder builder = CreateFunctionRequest.builder()
                .functionName(fnName)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .timeout(30)
                .code(FunctionCode.builder()
                        .zipFile(SdkBytes.fromByteArray(createZip(code)))
                        .build());
        if (environment != null && !environment.isEmpty()) {
            builder.environment(e -> e.variables(environment));
        }
        lambda.createFunction(builder.build());
        createdFunctions.add(fnName);
        return fnName;
    }

    private static String createWsApi(String prefix) {
        CreateApiResponse res = gw.createApi(CreateApiRequest.builder()
                .name(TestFixtures.uniqueName(prefix))
                .protocolType(ProtocolType.WEBSOCKET)
                .routeSelectionExpression("$request.body.action")
                .build());
        createdApis.add(res.apiId());
        return res.apiId();
    }

    private static String createLambdaIntegration(String apiId, String fnName) {
        CreateIntegrationResponse res = gw.createIntegration(CreateIntegrationRequest.builder()
                .apiId(apiId)
                .integrationType(IntegrationType.AWS_PROXY)
                .integrationUri("arn:aws:lambda:us-east-1:000000000000:function:" + fnName)
                .build());
        return res.integrationId();
    }

    private static void setupStage(String apiId, Map<String, String> stageVariables) {
        CreateDeploymentResponse deploy = gw.createDeployment(CreateDeploymentRequest.builder().apiId(apiId).build());
        CreateStageRequest.Builder req = CreateStageRequest.builder()
                .apiId(apiId)
                .stageName(STAGE)
                .deploymentId(deploy.deploymentId());
        if (stageVariables != null && !stageVariables.isEmpty()) {
            req.stageVariables(stageVariables);
        }
        gw.createStage(req.build());
    }

    private static void setupStage(String apiId) {
        setupStage(apiId, null);
    }

    private static String wsUrl(String apiId) {
        URI endpoint = TestFixtures.endpoint();
        String host = endpoint.getHost();
        int port = endpoint.getPort();
        return "ws://" + host + ":" + port + "/ws/" + apiId + "/" + STAGE;
    }

    private static ApiGatewayManagementApiClient managementClient(String apiId) {
        URI mgmtEndpoint = URI.create(TestFixtures.endpoint() + "/execute-api/" + apiId + "/" + STAGE);
        return ApiGatewayManagementApiClient.builder()
                .endpointOverride(mgmtEndpoint)
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static WebSocket connectWebSocket(String url, MultiMessageCapture capture) throws Exception {
        return http.newWebSocketBuilder()
                .buildAsync(URI.create(url), capture)
                .get(60, TimeUnit.SECONDS);
    }

    private static String getConnectionId(WebSocket ws, MultiMessageCapture capture) throws Exception {
        ws.sendText("{\"action\":\"getConnectionId\"}", true).join();
        String response = capture.getNextMessage(15, TimeUnit.SECONDS);
        if (response == null) return null;
        JsonNode node = JSON.readTree(response);
        return node.has("connectionId") ? node.get("connectionId").asText() : null;
    }

    private static byte[] createZip(String code) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry("index.js"));
                zos.write(code.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build ZIP", e);
        }
    }

    // ──────────────────────────── 1. Basic WebSocket flow ────────────────────────────

    @Test @Order(1)
    @DisplayName("1. Basic WebSocket flow — connect, send, receive, disconnect")
    void basicWebSocketFlow() throws Exception {
        Assumptions.assumeTrue(lambdaAvailable, "Lambda dispatch required");

        String echoFn = createLambda("basic-echo", ECHO_HANDLER);
        String apiId = createWsApi("basic-flow");
        String integId = createLambdaIntegration(apiId, echoFn);

        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$connect")
                .target("integrations/" + integId).build());
        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$default")
                .target("integrations/" + integId)
                .routeResponseSelectionExpression("$default").build());
        setupStage(apiId);

        MultiMessageCapture capture = new MultiMessageCapture();
        WebSocket ws = connectWebSocket(wsUrl(apiId), capture);
        assertThat(ws).isNotNull();

        ws.sendText("{\"action\":\"test\",\"body\":\"hello\"}", true).join();
        String response = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertThat(response).isNotNull();

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── 2. Chat-style broadcast ────────────────────────────

    @Test @Order(2)
    @DisplayName("2. Chat-style broadcast — Lambda uses @connections to broadcast to multiple clients")
    void chatStyleBroadcast() throws Exception {
        Assumptions.assumeTrue(lambdaAvailable, "Lambda dispatch required");

        String broadcastFn = createLambda("broadcast", BROADCAST_HANDLER,
                Map.of("FLOCI_ENDPOINT", "http://host.docker.internal:4566"));
        String echoFn = createLambda("bc-echo", ECHO_HANDLER);
        String apiId = createWsApi("broadcast");

        String broadcastIntegId = createLambdaIntegration(apiId, broadcastFn);
        String echoIntegId = createLambdaIntegration(apiId, echoFn);

        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$connect")
                .target("integrations/" + echoIntegId).build());
        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$default")
                .target("integrations/" + echoIntegId)
                .routeResponseSelectionExpression("$default").build());
        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("broadcast")
                .target("integrations/" + broadcastIntegId)
                .routeResponseSelectionExpression("$default").build());
        setupStage(apiId);

        MultiMessageCapture capture1 = new MultiMessageCapture();
        MultiMessageCapture capture2 = new MultiMessageCapture();
        WebSocket ws1 = connectWebSocket(wsUrl(apiId), capture1);
        WebSocket ws2 = connectWebSocket(wsUrl(apiId), capture2);
        Thread.sleep(300);

        String connId1 = getConnectionId(ws1, capture1);
        String connId2 = getConnectionId(ws2, capture2);
        assertThat(connId1).isNotNull();
        assertThat(connId2).isNotNull();

        // Send broadcast action
        ws1.sendText("{\"action\":\"broadcast\",\"targets\":[\"%s\",\"%s\"],\"message\":\"hello-all\"}"
                .formatted(connId1, connId2), true).join();

        String msg1 = capture1.getNextMessage(15, TimeUnit.SECONDS);
        String msg2 = capture2.getNextMessage(15, TimeUnit.SECONDS);
        assertThat(msg1).isEqualTo("hello-all");
        assertThat(msg2).isEqualTo("hello-all");

        ws1.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── 3. $connect authorization ────────────────────────────

    @Test @Order(3)
    @DisplayName("3. $connect authorization — allows with valid token, denies with invalid")
    void connectAuthorization() throws Exception {
        Assumptions.assumeTrue(lambdaAvailable, "Lambda dispatch required");

        String authFn = createLambda("authorizer", AUTHORIZER_HANDLER);
        String echoFn = createLambda("auth-echo", ECHO_HANDLER);
        String apiId = createWsApi("auth-test");

        String echoIntegId = createLambdaIntegration(apiId, echoFn);

        CreateAuthorizerResponse authRes = gw.createAuthorizer(CreateAuthorizerRequest.builder()
                .apiId(apiId)
                .authorizerType(AuthorizerType.REQUEST)
                .name("ws-auth")
                .authorizerUri("arn:aws:lambda:us-east-1:000000000000:function:" + authFn)
                .identitySource("route.request.querystring.token")
                .build());

        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$connect")
                .target("integrations/" + echoIntegId)
                .authorizationType("CUSTOM")
                .authorizerId(authRes.authorizerId()).build());
        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$default")
                .target("integrations/" + echoIntegId).build());
        setupStage(apiId);

        // Should allow with valid token
        MultiMessageCapture allowCapture = new MultiMessageCapture();
        WebSocket wsAllow = connectWebSocket(wsUrl(apiId) + "?token=allow", allowCapture);
        assertThat(wsAllow).isNotNull();
        wsAllow.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();

        // Should deny with invalid token
        MultiMessageCapture denyCapture = new MultiMessageCapture();
        CompletableFuture<WebSocket> denyFuture = http.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl(apiId) + "?token=deny"), denyCapture);
        assertThatThrownBy(() -> denyFuture.get(15, TimeUnit.SECONDS))
                .hasMessageContaining("WebSocket");

        Thread.sleep(500);
    }

    // ──────────────────────────── 4. Route selection ────────────────────────────

    @Test @Order(4)
    @DisplayName("4. Route selection — ping, sendMessage, $default dispatched correctly")
    void routeSelection() throws Exception {
        Assumptions.assumeTrue(lambdaAvailable, "Lambda dispatch required");

        String pingFn = createLambda("ping", PING_HANDLER);
        String sendMsgFn = createLambda("sendmsg", SEND_MESSAGE_HANDLER);
        String defaultFn = createLambda("default", DEFAULT_HANDLER);
        String echoFn = createLambda("rs-echo", ECHO_HANDLER);
        String apiId = createWsApi("route-sel");

        String pingIntegId = createLambdaIntegration(apiId, pingFn);
        String sendMsgIntegId = createLambdaIntegration(apiId, sendMsgFn);
        String defaultIntegId = createLambdaIntegration(apiId, defaultFn);
        String echoIntegId = createLambdaIntegration(apiId, echoFn);

        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$connect")
                .target("integrations/" + echoIntegId).build());
        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("ping")
                .target("integrations/" + pingIntegId)
                .routeResponseSelectionExpression("$default").build());
        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("sendMessage")
                .target("integrations/" + sendMsgIntegId)
                .routeResponseSelectionExpression("$default").build());
        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$default")
                .target("integrations/" + defaultIntegId)
                .routeResponseSelectionExpression("$default").build());
        setupStage(apiId);

        // Test ping route
        MultiMessageCapture pingCapture = new MultiMessageCapture();
        WebSocket wsPing = connectWebSocket(wsUrl(apiId), pingCapture);
        wsPing.sendText("{\"action\":\"ping\"}", true).join();
        assertThat(pingCapture.getNextMessage(15, TimeUnit.SECONDS)).isEqualTo("pong");

        // Test sendMessage route
        wsPing.sendText("{\"action\":\"sendMessage\",\"data\":\"test-data\"}", true).join();
        assertThat(pingCapture.getNextMessage(15, TimeUnit.SECONDS)).isEqualTo("received: test-data");

        // Test $default route (unknown action)
        wsPing.sendText("{\"action\":\"unknownAction\"}", true).join();
        assertThat(pingCapture.getNextMessage(15, TimeUnit.SECONDS)).isEqualTo("default-route");

        wsPing.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── 5. @connections API ────────────────────────────

    @Test @Order(5)
    @DisplayName("5. @connections API — POST sends message, GET returns info, DELETE disconnects")
    void connectionsApi() throws Exception {
        Assumptions.assumeTrue(lambdaAvailable, "Lambda dispatch required");

        String echoFn = createLambda("conn-echo", ECHO_HANDLER);
        String apiId = createWsApi("connections-api");
        String integId = createLambdaIntegration(apiId, echoFn);

        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$connect")
                .target("integrations/" + integId).build());
        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$default")
                .target("integrations/" + integId)
                .routeResponseSelectionExpression("$default").build());
        setupStage(apiId);

        ApiGatewayManagementApiClient mgmt = managementClient(apiId);

        // POST sends message to connection
        MultiMessageCapture capture = new MultiMessageCapture();
        WebSocket ws = connectWebSocket(wsUrl(apiId), capture);
        Thread.sleep(300);
        String connId = getConnectionId(ws, capture);
        assertThat(connId).isNotNull();

        MultiMessageCapture pushCapture = new MultiMessageCapture();
        WebSocket ws2 = connectWebSocket(wsUrl(apiId), pushCapture);
        Thread.sleep(300);
        String connId2 = getConnectionId(ws2, pushCapture);
        assertThat(connId2).isNotNull();

        mgmt.postToConnection(PostToConnectionRequest.builder()
                .connectionId(connId2)
                .data(SdkBytes.fromUtf8String("server-push"))
                .build());
        String pushed = pushCapture.getNextMessage(15, TimeUnit.SECONDS);
        assertThat(pushed).isEqualTo("server-push");

        // GET returns connection info
        GetConnectionResponse info = mgmt.getConnection(GetConnectionRequest.builder()
                .connectionId(connId2).build());
        assertThat(info.connectedAt()).isNotNull();

        // DELETE disconnects the client
        CompletableFuture<Integer> closeFuture = new CompletableFuture<>();
        pushCapture.setCloseFuture(closeFuture);
        mgmt.deleteConnection(DeleteConnectionRequest.builder()
                .connectionId(connId2).build());
        Integer closeCode = closeFuture.get(15, TimeUnit.SECONDS);
        assertThat(closeCode).isNotNull();

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
        mgmt.close();
    }

    // ──────────────────────────── 6. Stage variables ────────────────────────────

    @Test @Order(6)
    @DisplayName("6. Stage variables — integration URI with ${stageVariables.functionName} resolves")
    void stageVariables() throws Exception {
        Assumptions.assumeTrue(lambdaAvailable, "Lambda dispatch required");

        String echoFn = createLambda("sv-echo", ECHO_HANDLER);
        String apiId = createWsApi("stage-vars");

        // Integration with stage variable reference in URI
        CreateIntegrationResponse integRes = gw.createIntegration(CreateIntegrationRequest.builder()
                .apiId(apiId)
                .integrationType(IntegrationType.AWS_PROXY)
                .integrationUri("arn:aws:lambda:us-east-1:000000000000:function:${stageVariables.functionName}")
                .build());
        String integId = integRes.integrationId();

        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$connect")
                .target("integrations/" + integId).build());
        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$default")
                .target("integrations/" + integId)
                .routeResponseSelectionExpression("$default").build());
        setupStage(apiId, Map.of("functionName", echoFn));

        MultiMessageCapture capture = new MultiMessageCapture();
        WebSocket ws = connectWebSocket(wsUrl(apiId), capture);
        assertThat(ws).isNotNull();

        ws.sendText("{\"action\":\"test\",\"body\":\"stage-var-test\"}", true).join();
        String response = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertThat(response).isNotNull();

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── 7. Mock integration ────────────────────────────

    @Test @Order(7)
    @DisplayName("7. Mock integration — $connect with MOCK integration, no Lambda needed")
    void mockIntegration() throws Exception {
        Assumptions.assumeTrue(lambdaAvailable, "Lambda dispatch required for $default route");

        String apiId = createWsApi("mock-integ");

        // MOCK integration for $connect — no Lambda needed
        CreateIntegrationResponse mockIntegRes = gw.createIntegration(CreateIntegrationRequest.builder()
                .apiId(apiId)
                .integrationType(IntegrationType.MOCK)
                .build());
        String mockIntegId = mockIntegRes.integrationId();

        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$connect")
                .target("integrations/" + mockIntegId).build());

        // $default still needs a Lambda for message handling
        String echoFn = createLambda("mock-echo", ECHO_HANDLER);
        String echoIntegId = createLambdaIntegration(apiId, echoFn);
        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$default")
                .target("integrations/" + echoIntegId)
                .routeResponseSelectionExpression("$default").build());
        setupStage(apiId);

        MultiMessageCapture capture = new MultiMessageCapture();
        WebSocket ws = connectWebSocket(wsUrl(apiId), capture);
        assertThat(ws).isNotNull();

        ws.sendText("{\"action\":\"test\"}", true).join();
        String response = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertThat(response).isNotNull();

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── 8. Disconnect cleanup ────────────────────────────

    @Test @Order(8)
    @DisplayName("8. Disconnect cleanup — after disconnect, @connections POST returns 410")
    void disconnectCleanup() throws Exception {
        Assumptions.assumeTrue(lambdaAvailable, "Lambda dispatch required");

        String echoFn = createLambda("dc-echo", ECHO_HANDLER);
        String apiId = createWsApi("disconnect");
        String integId = createLambdaIntegration(apiId, echoFn);

        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$connect")
                .target("integrations/" + integId).build());
        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$default")
                .target("integrations/" + integId)
                .routeResponseSelectionExpression("$default").build());
        setupStage(apiId);

        MultiMessageCapture capture = new MultiMessageCapture();
        WebSocket ws = connectWebSocket(wsUrl(apiId), capture);
        Thread.sleep(300);
        String connId = getConnectionId(ws, capture);
        assertThat(connId).isNotNull();

        // Disconnect
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);

        // POST to disconnected connection should return 410
        ApiGatewayManagementApiClient mgmt = managementClient(apiId);
        assertThatThrownBy(() -> mgmt.postToConnection(PostToConnectionRequest.builder()
                .connectionId(connId)
                .data(SdkBytes.fromUtf8String("should-fail"))
                .build()))
                .isInstanceOf(GoneException.class);

        mgmt.close();
    }

    // ──────────────────────────── 9. Payload size limit ────────────────────────────

    @Test @Order(9)
    @DisplayName("9. Payload size limit — messages exceeding 128 KB receive error frame")
    void payloadSizeLimit() throws Exception {
        Assumptions.assumeTrue(lambdaAvailable, "Lambda dispatch required");

        String echoFn = createLambda("pl-echo", ECHO_HANDLER);
        String apiId = createWsApi("payload-limit");
        String integId = createLambdaIntegration(apiId, echoFn);

        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$connect")
                .target("integrations/" + integId).build());
        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$default")
                .target("integrations/" + integId)
                .routeResponseSelectionExpression("$default").build());
        setupStage(apiId);

        MultiMessageCapture capture = new MultiMessageCapture();
        WebSocket ws = connectWebSocket(wsUrl(apiId), capture);
        assertThat(ws).isNotNull();

        // Send a message larger than 128 KB
        String oversizeMessage = "x".repeat(128 * 1024 + 1);
        ws.sendText(oversizeMessage, true).join();
        String response = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertThat(response).isNotNull();
        assertThat(response).contains("Message too long");

        // Verify connection is still alive after rejection
        ws.sendText("{\"action\":\"test\",\"body\":\"after-oversize\"}", true).join();
        String normalResponse = capture.getNextMessage(15, TimeUnit.SECONDS);
        assertThat(normalResponse).isNotNull();

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        Thread.sleep(500);
    }

    // ──────────────────────────── 10. Server-initiated close via @connections DELETE ────────────────────────────

    @Test @Order(10)
    @DisplayName("10. Server-initiated close — DELETE disconnects and subsequent POST returns 410")
    void serverInitiatedClose() throws Exception {
        Assumptions.assumeTrue(lambdaAvailable, "Lambda dispatch required");

        String echoFn = createLambda("sc-echo", ECHO_HANDLER);
        String apiId = createWsApi("server-close");
        String integId = createLambdaIntegration(apiId, echoFn);

        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$connect")
                .target("integrations/" + integId).build());
        gw.createRoute(CreateRouteRequest.builder()
                .apiId(apiId).routeKey("$default")
                .target("integrations/" + integId)
                .routeResponseSelectionExpression("$default").build());
        setupStage(apiId);

        MultiMessageCapture capture = new MultiMessageCapture();
        CompletableFuture<Integer> closeFuture = new CompletableFuture<>();
        capture.setCloseFuture(closeFuture);
        WebSocket ws = connectWebSocket(wsUrl(apiId), capture);
        Thread.sleep(300);
        String connId = getConnectionId(ws, capture);
        assertThat(connId).isNotNull();

        // DELETE the connection via @connections API
        ApiGatewayManagementApiClient mgmt = managementClient(apiId);
        mgmt.deleteConnection(DeleteConnectionRequest.builder()
                .connectionId(connId).build());

        // Wait for the WebSocket to close
        Integer closeCode = closeFuture.get(15, TimeUnit.SECONDS);
        assertThat(closeCode).isNotNull();
        Thread.sleep(500);

        // POST to the deleted connection should return 410
        assertThatThrownBy(() -> mgmt.postToConnection(PostToConnectionRequest.builder()
                .connectionId(connId)
                .data(SdkBytes.fromUtf8String("should-fail"))
                .build()))
                .isInstanceOf(GoneException.class);

        mgmt.close();
    }

    // ── WebSocket message capture listener ───────────────────────────────────

    private static class MultiMessageCapture implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();
        private volatile CompletableFuture<Integer> closeFuture;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(10);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                messages.offer(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (closeFuture != null) {
                closeFuture.complete(statusCode);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (closeFuture != null) {
                closeFuture.completeExceptionally(error);
            }
        }

        public void setCloseFuture(CompletableFuture<Integer> future) {
            this.closeFuture = future;
        }

        public String getNextMessage(long timeout, TimeUnit unit) throws Exception {
            return messages.poll(timeout, unit);
        }
    }
}
