package io.floci.aws.services.ssmmessages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AccountResolver;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.IamEnforcementFilter;
import io.floci.aws.core.common.XmlParser;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.Node;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.SessionRequest;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.SessionState;
import io.floci.aws.testing.MutableClock;
import io.floci.aws.testing.ParallelLocks;
import io.floci.aws.testutil.AwsRequestSigner;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class SsmMessageGatewayServiceTest {

    private static final Node NODE = new Node("000000000000", "us-east-1", "i-0123456789abcdef0");
    private static final Node ANOTHER_NODE = new Node("000000000000", "us-east-1", "i-00000000000000000");
    private static final String SECRET = "instance-profile-secret";
    private static final String SECURITY_TOKEN = "instance+profile/token=";
    private static final String SECURITY_TOKEN_HEADER = "X-Amz-Security-Token";
    private static final byte[] EMPTY_BODY = new byte[0];
    private static final String CONTROL_PATH = "/v1/control-channel/" + NODE.instanceId();
    private static final String SESSION_ID = "operator-abcd";
    private static final String SESSION_ARN = "arn:aws:ssm:us-east-1:000000000000:session/" + SESSION_ID;
    private static final String DATA_PATH = DataChannelRouter.CHANNEL_PATH_PREFIX + SESSION_ID;
    private static final String DATA_ROLE = "publish_subscribe";
    private static final String OPEN_DATA_PATH = DATA_PATH + "?role=" + DATA_ROLE;
    private static final String OPEN_DATA_ACTION = "ssmmessages:OpenDataChannel";

    private final Vertx vertx = Vertx.vertx();
    private final HttpClient client = vertx.createHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final IamEnforcementFilter iamEnforcement = mock(IamEnforcementFilter.class);
    private final SsmMessageGateway gateway = new SsmMessageGateway(mapper, vertx, clock);
    private final BlockingQueue<SessionState> states = new LinkedBlockingQueue<>();
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        gateway.scheduleExpiry();
        IamService iam = mock(IamService.class);
        registerInstanceCredentials(iam, NODE);
        registerInstanceCredentials(iam, ANOTHER_NODE);
        denyOpenOn(SESSION_ARN);
        ChannelAuthentication auth = new ChannelAuthentication(iam, new AccountResolver(NODE.accountId()),
                iamEnforcement, clock);
        Router router = Router.router(vertx);
        SsmMessageGatewayRoutes routes = new SsmMessageGatewayRoutes(gateway, auth, mapper, true);
        new DataChannelRouter(List.of(routes)).init(router);
        routes.init(router);
        port = result(vertx.createHttpServer().requestHandler(router).listen(0)).actualPort();
    }

    @AfterEach
    void closeServer() throws Exception {
        gateway.shutdown();
        result(vertx.close());
    }

    @Test
    void controlChannelIsOnlineOnlyOnceTheOpeningTokenIsValidated() throws Exception {
        TestPeer invalid = connect(CONTROL_PATH + "?role=subscribe", NODE);
        String grant = createGrant(CONTROL_PATH);
        invalid.open("incorrect", false);
        assertEquals(1008, invalid.closed());
        assertFalse(gateway.isConnected(NODE));

        TestPeer valid = connect(CONTROL_PATH + "?role=subscribe", NODE);
        valid.open(grant, false);
        await().atMost(Duration.ofSeconds(10)).until(() -> gateway.isConnected(NODE));
        result(valid.socket.close());
        await().atMost(Duration.ofSeconds(10)).until(() -> !gateway.isConnected(NODE));
    }

    @Test
    void instanceCannotMintAnotherInstancesControlGrant() throws Exception {
        ChannelResponse response = createChannel(CONTROL_PATH, ANOTHER_NODE);
        assertEquals(403, response.statusCode());
    }

    @Test
    void missingControlConnectionRejectsSessionStart() {
        AwsException failure = assertThrows(AwsException.class,
                () -> gateway.startSession(sessionRequest(), states::add));
        assertEquals("TargetNotConnected", failure.getErrorCode());
    }

    @Test
    void startDispatchesTheDocumentAndTypedParametersInAnInteractiveShellTask() throws Exception {
        TestPeer control = connectControl();
        gateway.startSession(sessionRequest(), states::add);

        AgentMessage dispatch = AgentMessage.decode(control.next().getBytes());
        assertEquals("interactive_shell", dispatch.messageType());
        JsonNode envelope = mapper.readTree(dispatch.payloadAsString());
        assertEquals("StartSession", envelope.path("Topic").asText());
        assertEquals(SESSION_ID, envelope.path("TaskId").asText());
        JsonNode content = mapper.readTree(envelope.path("Content").asText());
        assertEquals(sessionRequest().documentContent(), content.path("DocumentContent"));
        assertEquals(mapper.valueToTree(sessionRequest().parameters()), content.path("Parameters"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void dataFromEitherPeerReachesTheOtherUnchanged(boolean agentFirst) throws Exception {
        String pluginGrant = start();
        String agentGrant = createGrant(DATA_PATH);
        TestPeer first = dataPeer(agentFirst ? NODE : null);
        first.open(agentFirst ? agentGrant : pluginGrant, agentFirst);
        byte[] original = AgentMessage.outbound(agentFirst ? "output_stream_data" : "input_stream_data",
                9, 1, new byte[] {-1, 0, 12, 14}).encode();
        first.send(Buffer.buffer(original));

        TestPeer second = dataPeer(agentFirst ? null : NODE);
        second.open(agentFirst ? pluginGrant : agentGrant, !agentFirst);
        assertArrayEquals(original, second.next().getBytes());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void instanceProfileClientCanOpenDataChannel(boolean targetsOwnInstance) throws Exception {
        allowClientOpen();
        String clientGrant = start();
        TestPeer clientPeer = dataPeer(targetsOwnInstance ? NODE : ANOTHER_NODE);

        assertClientReceivesAgentOutput(clientPeer, clientGrant);
    }

    @Test
    void presignedInstanceProfileClientCanOpenDataChannel() throws Exception {
        allowClientOpen();
        String clientGrant = start();
        URI presigned = signer(NODE).presignedUri("GET", requestUri(DATA_PATH),
                Map.of("role", DATA_ROLE, SECURITY_TOKEN_HEADER, SECURITY_TOKEN), EMPTY_BODY);
        TestPeer clientPeer = connect(presigned.getRawPath() + "?" + presigned.getRawQuery(), Map.of());

        assertClientReceivesAgentOutput(clientPeer, clientGrant);
    }

    @Test
    void signedClientCannotOpenDataChannelWithoutSessionPermission() throws Exception {
        String clientGrant = start();

        assertDataOpenDenied(NODE, clientGrant, false);
    }

    @Test
    void instanceCannotClaimAnAgentGrantForAnotherInstance() throws Exception {
        start();
        String agentGrant = createGrant(DATA_PATH);

        assertDataOpenDenied(ANOTHER_NODE, agentGrant, true);
    }

    @Test
    void unsignedPeerCannotClaimAnAgentGrantByNamingTheTargetInstance() throws Exception {
        start();
        String agentGrant = createGrant(DATA_PATH);

        assertDataOpenDenied(null, agentGrant, true);
    }

    @Test
    void targetInstanceCannotOpenAgentChannelWithoutPermission() throws Exception {
        start();
        String agentGrant = createGrant(DATA_PATH);
        denyOpenOn("*");

        assertDataOpenDenied(NODE, agentGrant, true);
    }

    @Test
    void targetInstanceCannotOpenAgentChannelWithWrongToken() throws Exception {
        start();
        createGrant(DATA_PATH);
        TestPeer agent = dataPeer(NODE);
        agent.open("incorrect", true);

        assertEquals(1008, agent.closed());
    }

    @Test
    void clientAndAgentGrantsAreNotInterchangeable() throws Exception {
        String pluginGrant = start();
        String agentGrant = createGrant(DATA_PATH);

        TestPeer clientUsingAgentGrant = dataPeer(null);
        clientUsingAgentGrant.open(agentGrant, false);
        assertEquals(1008, clientUsingAgentGrant.closed());

        TestPeer agentUsingClientGrant = dataPeer(NODE);
        agentUsingClientGrant.open(pluginGrant, true);
        assertEquals(1008, agentUsingClientGrant.closed());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void agentWithNoClientIdCanClaimItsDataGrant(String clientId) throws Exception {
        String pluginGrant = start();
        String agentGrant = createGrant(DATA_PATH, clientId);
        TestPeer agent = dataPeer(NODE);
        agent.open(agentGrant, true, clientId);
        TestPeer plugin = dataPeer(null);
        plugin.open(pluginGrant, false);

        Buffer output = message("output_stream_data", Map.of("payload", "stock agent output"));
        agent.send(output);
        assertEquals(output, plugin.next());
    }

    @Test
    void agentCannotClaimAGrantIssuedForADifferentClientId() throws Exception {
        start();
        String agentGrant = createGrant(DATA_PATH);
        TestPeer agent = dataPeer(NODE);
        agent.open(agentGrant, true, "other-client-id");

        assertEquals(1008, agent.closed());
    }

    @Test
    void refreshingAClientGrantKeepsTheOldSocketUntilTheNewGrantIsClaimed() throws Exception {
        String pluginGrant = start();
        TestPeer agent = dataPeer(NODE);
        agent.open(createGrant(DATA_PATH), true);
        TestPeer current = dataPeer(null);
        current.open(pluginGrant, false);
        agent.send(message("agent_session_state", Map.of("SessionId", SESSION_ID, "SessionState", "Connected")));
        assertEquals(SessionState.CONNECTED, states.poll(10, TimeUnit.SECONDS));

        String replacement = gateway.resumeSession(NODE, SESSION_ID).orElseThrow();
        assertNotEquals(pluginGrant, replacement);
        Buffer frame = message("output_stream_data", Map.of("payload", "before-replace"));
        agent.send(frame);
        assertEquals(frame, current.next());

        TestPeer stale = dataPeer(null);
        stale.open(pluginGrant, false);
        assertEquals(1008, stale.closed());

        TestPeer resumed = dataPeer(null);
        resumed.open(replacement, false);
        assertEquals(1000, current.closed());
        Buffer after = message("output_stream_data", Map.of("payload", "after-replace"));
        agent.send(after);
        assertEquals(after, resumed.next());
    }

    @Test
    void completedTaskIsAcknowledgedAndClosesTheClientChannel() throws Exception {
        TestPeer control = connectControl();
        String pluginGrant = gateway.startSession(sessionRequest(), states::add);
        control.next();
        TestPeer agent = dataPeer(NODE);
        agent.open(createGrant(DATA_PATH), true);
        TestPeer plugin = dataPeer(null);
        plugin.open(pluginGrant, false);
        agent.send(message("agent_session_state", Map.of("SessionId", SESSION_ID, "SessionState", "Connected")));
        assertEquals(SessionState.CONNECTED, states.poll(10, TimeUnit.SECONDS));

        Buffer complete = message("agent_task_complete", Map.of("TaskId", SESSION_ID, "Topic", "agent_task_complete",
                "FinalTaskStatus", "Success"));
        control.send(complete);
        AgentMessage reply = AgentMessage.decode(control.next().getBytes());
        assertEquals("agent_task_acknowledge", reply.messageType());
        assertEquals(AgentMessage.decode(complete.getBytes()).messageId().toString(),
                mapper.readTree(reply.payloadAsString()).path("MessageId").asText());

        Buffer tail = message("output_stream_data", Map.of("payload", "last output"));
        agent.send(tail);
        result(agent.socket.close());
        assertEquals(tail, plugin.next());
        assertEquals("channel_closed", AgentMessage.decode(plugin.next().getBytes()).messageType());
        assertEquals(SessionState.TERMINATED, states.poll(10, TimeUnit.SECONDS));
        assertTrue(gateway.resumeSession(NODE, SESSION_ID).isEmpty());
    }

    @Test
    void expiredControlGrantCannotMakeTheNodeOnline() throws Exception {
        String grant = createGrant(CONTROL_PATH);
        clock.advance(Duration.ofMinutes(6));
        TestPeer control = connect(CONTROL_PATH + "?role=subscribe", NODE);
        control.open(grant, false);
        assertEquals(1008, control.closed());
        assertFalse(gateway.isConnected(NODE));
    }

    @Test
    void connectedSessionOutlivesTheOpeningGrant() throws Exception {
        DataPeers peers = connectedPeers();
        clock.advance(Duration.ofMinutes(6));

        Buffer data = message("output_stream_data", Map.of("payload", "after-grant-expiry"));
        peers.agent.send(data);
        assertEquals(data, peers.plugin.next());
    }

    @Test
    void disconnectedSessionExpiresAndAsksTheAgentToStop() throws Exception {
        DataPeers peers = connectedPeers();
        result(peers.plugin.socket.close());
        assertEquals(SessionState.DISCONNECTED, states.poll(10, TimeUnit.SECONDS));

        clock.advance(Duration.ofMinutes(6));
        assertEquals(AgentMessage.CHANNEL_CLOSED, AgentMessage.decode(peers.control.next().getBytes()).messageType());
        assertEquals(SessionState.FAILED, states.poll(10, TimeUnit.SECONDS));
        assertTrue(gateway.resumeSession(NODE, SESSION_ID).isEmpty());
    }

    @Test
    void resetClosesRegisteredAndOpeningSocketsAndRejectsPriorGrants() throws Exception {
        DataPeers peers = connectedPeers();
        TestPeer opening = connect(CONTROL_PATH + "?role=subscribe", NODE);
        String priorGrant = createGrant(CONTROL_PATH);
        gateway.beforeReset();
        gateway.clear();
        gateway.afterReset();

        assertEquals(1000, peers.control.closed());
        assertEquals(1000, peers.agent.closed());
        assertEquals(1000, peers.plugin.closed());
        assertEquals(1000, opening.closed());
        assertFalse(gateway.isConnected(NODE));
        assertTrue(gateway.resumeSession(NODE, SESSION_ID).isEmpty());

        TestPeer replay = connect(CONTROL_PATH + "?role=subscribe", NODE);
        replay.open(priorGrant, false);
        assertEquals(1008, replay.closed());
    }

    private DataPeers connectedPeers() throws Exception {
        TestPeer control = connectControl();
        String grant = gateway.startSession(sessionRequest(), states::add);
        control.next();
        TestPeer agent = dataPeer(NODE);
        agent.open(createGrant(DATA_PATH), true);
        TestPeer plugin = dataPeer(null);
        plugin.open(grant, false);
        agent.send(message("agent_session_state", Map.of("SessionId", SESSION_ID, "SessionState", "Connected")));
        assertEquals(SessionState.CONNECTED, states.poll(10, TimeUnit.SECONDS));
        return new DataPeers(control, agent, plugin);
    }

    private record DataPeers(TestPeer control, TestPeer agent, TestPeer plugin) { }

    private TestPeer connectControl() throws Exception {
        TestPeer control = connect(CONTROL_PATH + "?role=subscribe", NODE);
        control.open(createGrant(CONTROL_PATH), false);
        await().atMost(Duration.ofSeconds(10)).until(() -> gateway.isConnected(NODE));
        return control;
    }

    private String start() throws Exception {
        TestPeer control = connectControl();
        String grant = gateway.startSession(sessionRequest(), states::add);
        control.next();
        return grant;
    }

    private SessionRequest sessionRequest() {
        String accountArn = "arn:aws:iam::000000000000:user/operator";
        return new SessionRequest(SESSION_ID, SESSION_ARN, accountArn, NODE, "document",
                mapper.createObjectNode().put("sessionType", "Port"), Map.of("host", "name", "list", List.of("value")));
    }

    private ChannelResponse createChannel(String path, Node signer) throws Exception {
        return createChannel(path, signer, "client-id");
    }

    private ChannelResponse createChannel(String path, Node signer, String clientId) throws Exception {
        JsonObject body = new JsonObject().put("MessageSchemaVersion", "1.0").put("RequestId", "request-id");
        if (clientId != null) {
            body.put("ClientId", clientId);
        }
        String encoded = body.encode();
        Map<String, String> headers = signedHeaders("POST", path, signer, encoded.getBytes(StandardCharsets.UTF_8));
        return result(client.request(HttpMethod.POST, port, "localhost", path).compose(req -> {
            headers.forEach(req::putHeader);
            Future<ChannelResponse> response = req.response().compose(reply -> reply.body()
                    .map(payload -> new ChannelResponse(reply.statusCode(), payload.toString())));
            return req.end(encoded).compose(ignored -> response);
        }));
    }

    private String createGrant(String path) throws Exception {
        return createGrant(path, "client-id");
    }

    private String createGrant(String path, String clientId) throws Exception {
        ChannelResponse response = createChannel(path, NODE, clientId);
        assertEquals(201, response.statusCode());
        String token = XmlParser.extractFirst(response.body(), "TokenValue", "");
        assertFalse(token.isEmpty(), () -> "A successful channel response must contain a token: " + response.body());
        return token;
    }

    private record ChannelResponse(int statusCode, String body) { }

    private TestPeer dataPeer(Node signer) throws Exception {
        return connect(OPEN_DATA_PATH, signer);
    }

    private TestPeer connect(String path, Node signer) throws Exception {
        return connect(path, signer == null ? Map.of() : signedHeaders("GET", path, signer, EMPTY_BODY));
    }

    private TestPeer connect(String path, Map<String, String> headers) throws Exception {
        WebSocketConnectOptions options = new WebSocketConnectOptions().setPort(port).setHost("localhost").setURI(path);
        headers.forEach(options::addHeader);
        return new TestPeer(result(client.webSocket(options)));
    }

    private void assertClientReceivesAgentOutput(TestPeer clientPeer, String clientGrant) throws Exception {
        TestPeer agent = dataPeer(NODE);
        agent.open(createGrant(DATA_PATH), true);
        clientPeer.open(clientGrant, false);

        Buffer output = message("output_stream_data", Map.of("payload", "agent output"));
        agent.send(output);
        assertEquals(output, clientPeer.next());
    }

    private void assertDataOpenDenied(Node signer, String grant, boolean agent) throws Exception {
        TestPeer peer;
        try {
            peer = dataPeer(signer);
        } catch (ExecutionException failure) {
            UpgradeRejectedException rejected = assertInstanceOf(UpgradeRejectedException.class, failure.getCause());
            assertEquals(403, rejected.getStatus());
            return;
        }
        peer.open(grant, agent);
        assertEquals(1008, peer.closed());
    }

    private void allowClientOpen() {
        doNothing().when(iamEnforcement).authorizeRequestParameters(
                anyString(), eq(OPEN_DATA_ACTION), eq(SESSION_ARN), any());
    }

    private void denyOpenOn(String resource) {
        doThrow(new AwsException("AccessDeniedException", "OpenDataChannel denied by IAM", 403))
                .when(iamEnforcement).authorizeRequestParameters(anyString(), eq(OPEN_DATA_ACTION), eq(resource), any());
    }

    private URI requestUri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private Map<String, String> signedHeaders(String method, String path, Node node, byte[] body) throws Exception {
        Map<String, String> headers = signer(node).headersFor(method, requestUri(path),
                Map.of("x-amz-security-token", SECURITY_TOKEN), body);
        headers.put(SECURITY_TOKEN_HEADER, SECURITY_TOKEN);
        return headers;
    }

    private AwsRequestSigner signer(Node node) {
        return AwsRequestSigner.signedAs(instanceAccessKey(node), SECRET, ChannelAuthentication.SIGNING_SERVICE)
                .inRegion(node.region()).signedAt(clock.instant());
    }

    private static String instanceAccessKey(Node node) {
        return "ASIA" + node.instanceId().substring(2);
    }

    private static void registerInstanceCredentials(IamService iam, Node node) {
        String key = instanceAccessKey(node);
        when(iam.findEc2InstanceIdentity(key, SECURITY_TOKEN)).thenReturn(Optional.of(
                new IamService.Ec2InstanceIdentity(node.accountId(), node.instanceId())));
        when(iam.findSecretKey(key, SECURITY_TOKEN)).thenReturn(Optional.of(SECRET));
        when(iam.resolveAccountId(key)).thenReturn(Optional.of(node.accountId()));
    }

    private Buffer message(String kind, Object value) throws Exception {
        return Buffer.buffer(AgentMessage.outbound(kind, 0, 0, mapper.writeValueAsBytes(value)).encode());
    }

    private static <T> T result(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static final class TestPeer {
        private final WebSocket socket;
        private final BlockingQueue<Buffer> received = new LinkedBlockingQueue<>();
        private final CompletableFuture<Short> close = new CompletableFuture<>();

        private TestPeer(WebSocket socket) {
            this.socket = socket;
            socket.binaryMessageHandler(received::add);
            socket.closeHandler(ignored -> close.complete(socket.closeStatusCode()));
        }

        void open(String token, boolean agent) throws Exception {
            open(token, agent, "client-id");
        }

        void open(String token, boolean agent, String clientId) throws Exception {
            JsonObject request = new JsonObject().put("MessageSchemaVersion", "1.0")
                    .put("RequestId", "request-id").put("TokenValue", token);
            if (clientId != null) {
                request.put("ClientId", clientId);
            }
            if (agent) {
                request.put("ClientInstanceId", NODE.instanceId());
            }
            result(socket.writeTextMessage(request.encode()));
        }

        void send(Buffer frame) throws Exception {
            result(socket.writeBinaryMessage(frame));
        }

        Buffer next() throws InterruptedException {
            Buffer next = received.poll(10, TimeUnit.SECONDS);
            assertNotNull(next, () -> "the peer must receive a frame (close code " + socket.closeStatusCode()
                    + ", reason: " + socket.closeReason() + ")");
            return next;
        }

        short closed() throws Exception {
            return close.get(10, TimeUnit.SECONDS);
        }
    }
}
