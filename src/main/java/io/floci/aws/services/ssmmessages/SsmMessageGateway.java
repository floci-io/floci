package io.floci.aws.services.ssmmessages;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Base64Url;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.Resettable;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** The in-memory control connections and paired Session Manager data channels. */
@ApplicationScoped
public class SsmMessageGateway implements Resettable {

    private static final Logger LOG = Logger.getLogger(SsmMessageGateway.class);
    private static final Duration GRANT_TTL = Duration.ofMinutes(5);
    private static final Duration HANDSHAKE_TTL = Duration.ofSeconds(30);
    private static final int MAX_QUEUED_BYTES = 4 * 1024 * 1024;
    private static final int MAX_QUEUED_MESSAGES = 4096;
    private static final long SERVICE_MESSAGE_FLAGS = 3;
    private static final String SESSION_STATE_MESSAGE = "agent_session_state";
    private static final String TASK_COMPLETE_MESSAGE = "agent_task_complete";

    public record Node(String accountId, String region, String instanceId) { }

    public record SessionRequest(String sessionId, String sessionArn, String ownerArn, Node node,
                                 String documentName, JsonNode documentContent, Map<String, Object> parameters) { }

    public enum SessionState { CONNECTED, DISCONNECTED, TERMINATED, FAILED }

    record SessionTarget(String sessionArn, Node node) { }

    private final ObjectMapper mapper;
    private final Vertx vertx;
    private final Clock clock;
    private final Context eventContext;
    private final SecureRandom random = new SecureRandom();

    // Opening and closing sockets, grants and queues all use the same monitor. No socket I/O waits on it.
    private final Set<Connection> connections = new HashSet<>();
    private final Map<Node, Connection> controls = new HashMap<>();
    private final Map<Node, Grant> controlGrants = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private long generation;
    private long sweepTimer;
    private boolean resetting;
    private boolean stopping;

    @Inject
    public SsmMessageGateway(ObjectMapper mapper, Vertx vertx, Clock clock) {
        this.mapper = mapper;
        this.vertx = vertx;
        this.clock = clock;
        this.eventContext = vertx.getOrCreateContext();
    }

    @PostConstruct
    void scheduleExpiry() {
        sweepTimer = vertx.setPeriodic(1000, ignored -> expire());
    }

    public synchronized boolean isConnected(Node node) {
        Connection connection = controls.get(node);
        return !resetting && connection != null && !connection.socket.isClosed();
    }

    /**
     * Creates the session grant and dispatches the AWS Session document to the connected agent.
     * State callbacks are ordered, asynchronous and scoped to the node's account and region.
     */
    public synchronized String startSession(SessionRequest request, Consumer<SessionState> onStateChange) {
        if (!isConnected(request.node())) {
            throw targetNotConnected();
        }
        Map<String, Object> task = Map.of(
                "SessionId", request.sessionId(),
                "SessionOwner", request.ownerArn(),
                "DocumentName", request.documentName(),
                "DocumentContent", request.documentContent(),
                "Parameters", request.parameters());
        Buffer dispatch = frame("interactive_shell", Map.of("SchemaVersion", 1, "TaskId", request.sessionId(),
                "Topic", "StartSession", "Content", new String(json(task), StandardCharsets.UTF_8)));
        Session session = new Session(request.sessionId(), new SessionTarget(request.sessionArn(), request.node()),
                onStateChange);
        if (sessions.putIfAbsent(request.sessionId(), session) != null) {
            throw new IllegalArgumentException("Session id is already registered");
        }
        Future<Void> sent = sendControl(request.node(), dispatch);
        if (sent.failed()) {
            sessions.remove(request.sessionId(), session);
            throw targetNotConnected();
        }
        sent.onFailure(ignored -> dispatchFailed(session));
        return session.client.grant.value();
    }

    public synchronized Optional<String> resumeSession(Node node, String sessionId) {
        Session session = activeSession(sessionId);
        if (session == null || session.terminating || !session.target.node().equals(node)) {
            return Optional.empty();
        }
        session.client.grant = grant(null);
        return Optional.of(session.client.grant.value());
    }

    public synchronized void terminateSession(Node node, String sessionId) {
        Session session = sessions.get(sessionId);
        if (session != null && session.target.node().equals(node)) {
            stopAgent(session);
            finish(session, SessionState.TERMINATED);
        }
    }

    synchronized Optional<SessionTarget> sessionTarget(String sessionId) {
        return Optional.ofNullable(activeSession(sessionId)).map(session -> session.target);
    }

    synchronized long generation() {
        return generation;
    }

    synchronized String createControlGrant(Node node) {
        if (stopping || resetting) {
            throw targetNotConnected();
        }
        Grant grant = grant(null);
        controlGrants.put(node, grant);
        return grant.value();
    }

    synchronized Optional<String> createAgentGrant(Node node, String sessionId, String clientId) {
        Session session = activeSession(sessionId);
        if (session == null || session.terminating || !session.target.node().equals(node)) {
            return Optional.empty();
        }
        session.agent.grant = grant(clientId);
        return Optional.of(session.agent.grant.value());
    }

    synchronized void openControl(Node node, ServerWebSocket socket, long expectedGeneration) {
        Connection connection = new Connection(socket);
        register(connection, expectedGeneration, (token, opening) -> {
            Grant grant = controlGrants.get(node);
            if (!valid(grant, token)) {
                return false;
            }
            controlGrants.remove(node);
            Connection previous = controls.put(node, connection);
            if (previous != null) {
                previous.socket.close();
            }
            return true;
        }, frame -> readControl(node, connection, frame), () -> controls.remove(node, connection));
    }

    synchronized void openData(String sessionId, SessionTarget target, boolean agentAllowed, boolean clientAllowed,
                               ServerWebSocket socket, long expectedGeneration) {
        Session session = activeSession(sessionId);
        if (session == null || !session.target.equals(target)) {
            socket.close((short) 1008, "Session unavailable");
            return;
        }
        Connection connection = new Connection(socket);
        register(connection, expectedGeneration, (token, opening) ->
                attachData(session, connection, token, opening, agentAllowed, clientAllowed),
                frame -> receiveData(session, connection.dataPeer, session.otherPeer(connection.dataPeer),
                        connection, frame),
                () -> {
                    Peer side = connection.dataPeer;
                    if (side != null) {
                        detach(session, side, session.otherPeer(side), connection);
                    }
                });
    }

    private void register(Connection connection, long expectedGeneration, BiPredicate<String, JsonNode> claim,
                          Consumer<Buffer> binary, Runnable detach) {
        ServerWebSocket socket = connection.socket;
        if (stopping || resetting || generation != expectedGeneration || socket.isClosed()) {
            socket.close();
            return;
        }
        connections.add(connection);
        connection.timer = vertx.setTimer(HANDSHAKE_TTL.toMillis(), ignored -> handshakeExpired(connection));
        socket.closeHandler(ignored -> disconnected(connection, detach));
        socket.exceptionHandler(ignored -> {
            disconnected(connection, detach);
            socket.close();
        });
        socket.textMessageHandler(text -> authenticate(connection, claim, text));
        socket.binaryMessageHandler(frame -> receive(connection, binary, frame));
    }

    private synchronized void authenticate(Connection connection, BiPredicate<String, JsonNode> claim, String text) {
        if (resetting || !connections.contains(connection) || connection.authenticated || connection.rejected) {
            return;
        }
        try {
            JsonNode opening = text.length() <= 16 * 1024 ? mapper.readTree(text) : null;
            if (opening == null || !"1.0".equals(opening.path("MessageSchemaVersion").textValue())) {
                reject(connection, (short) 1002, "Malformed opening message");
                return;
            }
            String token = opening.path("TokenValue").textValue();
            if (token != null && claim.test(token, opening)) {
                connection.authenticated = true;
                vertx.cancelTimer(connection.timer);
            } else {
                reject(connection, (short) 1008, "Invalid channel token");
            }
        } catch (JsonProcessingException e) {
            reject(connection, (short) 1002, "Malformed opening message");
        }
    }

    private synchronized void receive(Connection connection, Consumer<Buffer> binary, Buffer frame) {
        if (resetting || !connections.contains(connection) || connection.rejected) {
            return;
        }
        if (!connection.authenticated) {
            reject(connection, (short) 1008, "Channel is not authenticated");
            return;
        }
        try {
            binary.accept(frame);
        } catch (IllegalArgumentException e) {
            reject(connection, (short) 1002, "Malformed agent message");
        }
    }

    private static void reject(Connection connection, short code, String reason) {
        connection.rejected = true;
        connection.socket.close(code, reason);
    }

    private synchronized void disconnected(Connection connection, Runnable detach) {
        if (connections.remove(connection)) {
            vertx.cancelTimer(connection.timer);
            detach.run();
        }
    }

    private synchronized void handshakeExpired(Connection connection) {
        if (!connection.authenticated && connections.remove(connection)) {
            reject(connection, (short) 1008, "Opening message timed out");
        }
    }

    private boolean attachData(Session session, Connection connection, String token, JsonNode opening,
                               boolean agentAllowed, boolean clientAllowed) {
        if (session.finished || session.terminating) {
            return false;
        }
        Peer side;
        if (valid(session.agent.grant, token)) {
            if (!agentAllowed || !matchesAgentOpening(session, session.agent.grant, opening)) {
                return false;
            }
            side = session.agent;
        } else if (clientAllowed && valid(session.client.grant, token)) {
            side = session.client;
        } else {
            return false;
        }
        if (side.connection != null && !side.connection.socket.isClosed() && side.attachedGrant == side.grant) {
            return false;
        }
        Connection previous = side.connection;
        connection.dataPeer = side;
        side.connection = connection;
        side.attachedGrant = side.grant;
        if (previous != null) {
            previous.socket.close();
        }
        flush(session, side, session.otherPeer(side));
        updateConnectivity(session);
        return true;
    }

    private static boolean matchesAgentOpening(Session session, Grant grant, JsonNode opening) {
        // The stock session worker can use an empty ClientId for both creation and opening.
        JsonNode clientId = opening.path("ClientId");
        return (clientId.isMissingNode() || clientId.isNull() || clientId.isTextual())
                && Objects.equals(grant.binding(), clientId.textValue())
                && session.target.node().instanceId().equals(opening.path("ClientInstanceId").textValue());
    }

    private void detach(Session session, Peer side, Peer other, Connection connection) {
        if (side.connection != connection) {
            return;
        }
        side.connection = null;
        if (session.finished) {
            side.queue.clear();
            side.queuedBytes = 0;
            return;
        }
        if (side == session.agent && session.completion != null) {
            finish(session, session.completion);
            return;
        }
        updateConnectivity(session);
        updateBackpressure(other, side);
    }

    private void receiveData(Session session, Peer side, Peer other, Connection connection, Buffer buffer) {
        if (session.finished || side.connection != connection) {
            return;
        }
        String messageType = AgentMessage.gatewayMessageType(buffer);
        if (SESSION_STATE_MESSAGE.equals(messageType)) {
            if (side != session.agent) {
                throw new IllegalArgumentException("Session state must come from the agent");
            }
            readSessionState(session, readJson(AgentMessage.decode(buffer.getBytes())));
            return;
        }
        if (other.queue.size() >= MAX_QUEUED_MESSAGES || buffer.length() > MAX_QUEUED_BYTES - other.queuedBytes) {
            // An absent or stalled peer cannot leave unbounded stream data in the emulator.
            connection.socket.close((short) 1013, "Data channel buffer full");
            return;
        }
        queue(other, buffer);
        updateBackpressure(side, other);
        flush(session, other, side);
    }

    private static void queue(Peer peer, Buffer buffer) {
        peer.queue.addLast(buffer);
        peer.queuedBytes += buffer.length();
    }

    private void flush(Session session, Peer destination, Peer source) {
        Connection connection = destination.connection;
        if (connection == null || connection.socket.isClosed() || destination.writing) {
            return;
        }
        Buffer first = destination.queue.peekFirst();
        if (first == null) {
            if (session.finished) {
                connection.socket.close();
            }
            return;
        }
        destination.writing = true;
        connection.socket.writeBinaryMessage(first).onComplete(result -> connection.context.runOnContext(ignored ->
                written(session, destination, source, connection, first, result.succeeded())));
    }

    private synchronized void written(Session session, Peer destination, Peer source, Connection connection,
                                      Buffer buffer, boolean successful) {
        destination.writing = false;
        if (successful && destination.queue.peekFirst() == buffer && destination.connection == connection) {
            destination.queuedBytes -= destination.queue.removeFirst().length();
        } else if (!successful && destination.connection == connection) {
            detach(session, destination, source, connection);
            connection.socket.close();
        }
        updateBackpressure(source, destination);
        flush(session, destination, source);
    }

    private static void updateBackpressure(Peer source, Peer destination) {
        Connection connection = source.connection;
        if (connection == null || connection.socket.isClosed()) {
            return;
        }
        boolean congested = destination.connection != null
                && (destination.queuedBytes >= MAX_QUEUED_BYTES / 2
                    || destination.queue.size() >= MAX_QUEUED_MESSAGES / 2);
        if (congested && !connection.paused) {
            connection.paused = true;
            connection.socket.pause();
        } else if (!congested && connection.paused) {
            connection.paused = false;
            connection.socket.resume();
        }
    }

    private void updateConnectivity(Session session) {
        if (session.terminating || session.finished) {
            return;
        }
        if (session.agentReady && session.agent.connection != null && session.client.connection != null) {
            session.deadline = null;
            publishState(session, SessionState.CONNECTED);
        } else if (session.state == SessionState.CONNECTED) {
            session.deadline = clock.instant().plus(GRANT_TTL);
            publishState(session, SessionState.DISCONNECTED);
        }
    }

    private void readSessionState(Session session, JsonNode payload) {
        if (!session.id.equals(payload.path("SessionId").textValue())) {
            throw new IllegalArgumentException("Session state is for a different session");
        }
        switch (payload.path("SessionState").asText()) {
            case "Connected" -> {
                session.agentReady = true;
                updateConnectivity(session);
            }
            case "Terminating" -> {
                session.terminating = true;
                session.deadline = clock.instant().plus(HANDSHAKE_TTL);
            }
            default -> LOG.debugv("Ignored unknown agent state for session {0}", session.id);
        }
    }

    private void readControl(Node node, Connection connection, Buffer frame) {
        if (controls.get(node) != connection) {
            return;
        }
        String kind = AgentMessage.gatewayMessageType(frame);
        AgentMessage message = AgentMessage.decode(frame.getBytes());
        switch (kind) {
            case TASK_COMPLETE_MESSAGE -> completeTask(node, message);
            default -> LOG.debugv("Ignored message of type {0} on the agent control channel", kind);
        }
    }

    private void completeTask(Node node, AgentMessage message) {
        JsonNode payload = readJson(message);
        String id = payload.path("TaskId").asText("");
        String topic = payload.path("Topic").asText("");
        if (id.isBlank() || topic.isBlank()) {
            throw new IllegalArgumentException("Invalid task completion");
        }
        Session session = sessions.get(id);
        if (session != null && !session.target.node().equals(node)) {
            throw new IllegalArgumentException("Task completion is for a different instance");
        }
        sendControl(node, frame("agent_task_acknowledge", Map.of("SchemaVersion", 1, "TaskId", id,
                "Topic", topic, "MessageId", message.messageId().toString())));
        if (session != null && session.completion == null) {
            session.completion = "Success".equals(payload.path("FinalTaskStatus").asText())
                    ? SessionState.TERMINATED : SessionState.FAILED;
            if (session.agent.connection == null) {
                finish(session, session.completion);
            } else {
                // Completion arrives over a separate socket. Let the agent's final stream data drain first.
                session.terminating = true;
                session.deadline = clock.instant().plus(HANDSHAKE_TTL);
            }
        }
    }

    private void stopAgent(Session session) {
        Node node = session.target.node();
        Buffer close = frame(AgentMessage.CHANNEL_CLOSED, Map.of(
                "MessageType", AgentMessage.CHANNEL_CLOSED,
                "MessageId", UUID.randomUUID().toString(),
                "DestinationId", node.instanceId(), "SessionId", session.id,
                "SchemaVersion", 1, "CreatedDate", clock.instant().toString()));
        Future<Void> sent = sendControl(node, close);
        if (sent.failed() && session.agent.connection != null) {
            session.agent.connection.socket.writeBinaryMessage(close);
        }
    }

    private void finish(Session session, SessionState state) {
        if (!sessions.remove(session.id, session)) {
            return;
        }
        session.finished = true;
        publishState(session, state);
        if (session.agent.connection != null) {
            session.agent.connection.socket.close();
        }
        if (session.client.connection != null) {
            queue(session.client, frame(AgentMessage.CHANNEL_CLOSED, Map.of("SessionId", session.id, "Output", "")));
            flush(session, session.client, session.agent);
            Connection connection = session.client.connection;
            connection.timer = vertx.setTimer(HANDSHAKE_TTL.toMillis(), ignored -> connection.socket.close());
        }
    }

    private synchronized void dispatchFailed(Session session) {
        finish(session, SessionState.FAILED);
    }

    private void publishState(Session session, SessionState state) {
        if (session.state != state) {
            session.state = state;
            publish(session.target.node(), () -> session.onStateChange.accept(state));
        }
    }

    private void publish(Node node, Runnable event) {
        long expectedGeneration = generation;
        eventContext.runOnContext(ignored -> {
            if (callbacksEnabled(expectedGeneration)) {
                try {
                    RequestScopes.runAs(node.accountId(), node.region(), event);
                } catch (RuntimeException e) {
                    LOG.warnv(e, "Message gateway callback failed for instance {0}", node.instanceId());
                }
            }
        });
    }

    private synchronized boolean callbacksEnabled(long expectedGeneration) {
        return !resetting && !stopping && generation == expectedGeneration;
    }

    private Future<Void> sendControl(Node node, Buffer frame) {
        Connection connection = controls.get(node);
        if (resetting || connection == null || connection.socket.isClosed() || connection.socket.writeQueueFull()) {
            return Future.failedFuture(targetNotConnected());
        }
        return connection.socket.writeBinaryMessage(frame);
    }

    private JsonNode readJson(AgentMessage message) {
        try {
            JsonNode parsed = mapper.readTree(message.payloadAsString());
            if (parsed != null && parsed.isObject()) {
                return parsed;
            }
            throw new IllegalArgumentException("Invalid agent message payload");
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid agent message payload", e);
        }
    }

    private Buffer frame(String kind, Object payload) {
        AgentMessage message = new AgentMessage(kind, 1, clock.millis(), 0, SERVICE_MESSAGE_FLAGS,
                UUID.randomUUID(), 0, json(payload));
        return Buffer.buffer(message.encode());
    }

    private byte[] json(Object payload) {
        try {
            return mapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Message gateway payload could not be serialized", e);
        }
    }

    private Session activeSession(String sessionId) {
        Session session = sessions.get(sessionId);
        return resetting || session == null || expired(session.deadline, clock.instant()) ? null : session;
    }

    private synchronized void expire() {
        if (resetting || stopping) {
            return;
        }
        Instant now = clock.instant();
        controlGrants.values().removeIf(grant -> expired(grant.expires(), now));
        for (Session session : List.copyOf(sessions.values())) {
            if (expired(session.deadline, now)) {
                stopAgent(session);
                SessionState terminal = session.terminating ? SessionState.TERMINATED : SessionState.FAILED;
                finish(session, session.completion == null ? terminal : session.completion);
            }
        }
    }

    private static boolean expired(Instant deadline, Instant now) {
        return deadline != null && !now.isBefore(deadline);
    }

    private Grant grant(String binding) {
        byte[] token = new byte[32];
        random.nextBytes(token);
        return new Grant(Base64Url.encode(token), clock.instant().plus(GRANT_TTL), binding);
    }

    private boolean valid(Grant grant, String token) {
        return grant != null && !expired(grant.expires(), clock.instant()) && MessageDigest.isEqual(
                grant.value().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private static AwsException targetNotConnected() {
        return new AwsException("TargetNotConnected", "The target instance is not connected.", 400);
    }

    @Override
    public synchronized void beforeReset() {
        resetting = true;
    }

    @Override
    public synchronized void afterReset() {
        resetting = false;
    }

    @Override
    public synchronized void clear() {
        generation++;
        List<Connection> disconnect = List.copyOf(connections);
        connections.clear();
        controls.clear();
        controlGrants.clear();
        sessions.clear();
        for (Connection connection : disconnect) {
            vertx.cancelTimer(connection.timer);
            connection.socket.close();
        }
    }

    @PreDestroy
    synchronized void shutdown() {
        stopping = true;
        vertx.cancelTimer(sweepTimer);
        clear();
    }

    private record Grant(String value, Instant expires, String binding) { }

    private static final class Connection {
        private final ServerWebSocket socket;
        private final Context context = Vertx.currentContext();
        private Peer dataPeer;
        private long timer;
        private boolean authenticated;
        private boolean rejected;
        private boolean paused;

        private Connection(ServerWebSocket socket) {
            this.socket = socket;
        }
    }

    private static final class Peer {
        private final Deque<Buffer> queue = new ArrayDeque<>();
        private Connection connection;
        private Grant grant;
        private Grant attachedGrant;
        private int queuedBytes;
        private boolean writing;
    }

    private final class Session {
        private final String id;
        private final SessionTarget target;
        private final Consumer<SessionState> onStateChange;
        private final Peer agent = new Peer();
        private final Peer client = new Peer();
        private Instant deadline = clock.instant().plus(GRANT_TTL);
        private SessionState state;
        private SessionState completion;
        private boolean agentReady;
        private boolean terminating;
        private boolean finished;

        private Session(String id, SessionTarget target, Consumer<SessionState> onStateChange) {
            this.id = id;
            this.target = target;
            this.onStateChange = onStateChange;
            client.grant = grant(null);
        }

        private Peer otherPeer(Peer peer) {
            return peer == agent ? client : agent;
        }
    }
}
