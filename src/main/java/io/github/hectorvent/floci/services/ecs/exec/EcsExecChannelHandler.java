package io.github.hectorvent.floci.services.ecs.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The data channel behind {@code aws ecs execute-command}.
 *
 * <p>{@code ExecuteCommand} hands the caller a {@code streamUrl} pointing here; the AWS CLI passes
 * it to {@code session-manager-plugin}, which opens this WebSocket and speaks the Session Manager
 * protocol: a JSON handshake to claim the channel, then {@link AgentMessage} frames carrying the
 * terminal in both directions. Floci plays the agent half and bridges those frames to a
 * {@code docker exec} in the task's container.
 *
 * <p>The channel is claimed once. The token in the opening frame is the one
 * {@code ExecuteCommand} minted, and claiming it removes the session, so a captured stream URL
 * cannot be replayed into a second shell.
 *
 * <p>Two threads meet here. Every call into the Docker daemon blocks, so it runs on a worker
 * rather than on the event loop, and the daemon streams the container's output back on its own
 * thread. Frames are therefore written from the channel's Vert.x context only, which also keeps
 * the outbound sequence numbers in the order they go out on the wire.
 */
@ApplicationScoped
public class EcsExecChannelHandler {

    private static final Logger LOG = Logger.getLogger(EcsExecChannelHandler.class);

    /** Where the stream URL points. The path mirrors Session Manager's own data-channel path. */
    public static final String CHANNEL_PATH_PREFIX = "/v1/data-channel/";
    private static final String AGENT_VERSION = "3.3.0.0";

    private final EcsExecSessionRegistry sessions;
    private final ContainerLifecycleManager lifecycleManager;
    private final ObjectMapper objectMapper;
    private final Vertx vertx;

    @Inject
    public EcsExecChannelHandler(EcsExecSessionRegistry sessions,
                                 ContainerLifecycleManager lifecycleManager,
                                 ObjectMapper objectMapper,
                                 Vertx vertx) {
        this.sessions = sessions;
        this.lifecycleManager = lifecycleManager;
        this.objectMapper = objectMapper;
        this.vertx = vertx;
    }

    void init(@Observes Router router) {
        router.route(CHANNEL_PATH_PREFIX + ":sessionId").handler(this::upgrade);
        LOG.debugv("Registered the ECS Exec data channel on {0}*", CHANNEL_PATH_PREFIX);
    }

    private void upgrade(RoutingContext ctx) {
        String upgrade = ctx.request().getHeader("Upgrade");
        if (upgrade == null || !"websocket".equalsIgnoreCase(upgrade)) {
            ctx.next();
            return;
        }
        String sessionId = ctx.pathParam("sessionId");
        if (sessions.find(sessionId).isEmpty()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        ctx.request().toWebSocket()
                .onSuccess(webSocket -> new Channel(sessionId, webSocket).start())
                .onFailure(cause -> LOG.warnv("ECS Exec channel upgrade failed for session {0}: {1}",
                        sessionId, cause.getMessage()));
    }

    /** One client's channel: the WebSocket, the exec it drives, and the sequencing between them. */
    private final class Channel {

        private final String sessionId;
        private final ServerWebSocket webSocket;
        private final Context context;
        private final AtomicLong outboundSequence = new AtomicLong();
        private final AtomicBoolean streaming = new AtomicBoolean();

        private volatile ExecSession session;
        private volatile String execId;
        private volatile PipedOutputStream containerInput;
        private volatile Closeables exec;

        Channel(String sessionId, ServerWebSocket webSocket) {
            this.sessionId = sessionId;
            this.webSocket = webSocket;
            this.context = vertx.getOrCreateContext();
        }

        void start() {
            webSocket.textMessageHandler(this::onOpenChannel);
            webSocket.binaryMessageHandler(this::onBinaryFrame);
            webSocket.closeHandler(_ -> shutdown());
            webSocket.exceptionHandler(cause -> {
                LOG.debugv("ECS Exec channel {0} failed: {1}", sessionId, cause.getMessage());
                shutdown();
            });
        }

        /**
         * The plugin's opening frame, which carries the token. Until it arrives and matches, the
         * channel does nothing: no exec is created and no output flows.
         */
        private void onOpenChannel(String message) {
            if (session != null) {
                return;
            }
            String token;
            try {
                JsonNode node = objectMapper.readTree(message);
                token = node.path("TokenValue").asText(null);
            } catch (Exception e) {
                LOG.debugv("ECS Exec channel {0} sent an unreadable opening frame: {1}",
                        sessionId, e.getMessage());
                webSocket.close((short) 1002, "Malformed opening message");
                return;
            }
            Optional<ExecSession> claimed = sessions.claim(sessionId, token);
            if (claimed.isEmpty()) {
                webSocket.close((short) 1008, "Invalid session token");
                return;
            }
            session = claimed.get();
            createExec();
        }

        /**
         * Creates the exec and offers the handshake. The command is not started yet: the agent
         * offers the session type first and only runs anything once the client has accepted it,
         * so the client cannot be handed output for a channel it has not finished opening.
         */
        private void createExec() {
            ExecSession current = session;
            vertx.<String>executeBlocking(() -> lifecycleManager.getDockerClient()
                    .execCreateCmd(current.runtimeId())
                    .withCmd(current.command().toArray(new String[0]))
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withTty(current.interactive())
                    .exec()
                    .getId(), false)
                    .onSuccess(id -> {
                        execId = id;
                        sendHandshakeRequest();
                    })
                    .onFailure(cause -> failChannel(cause, current.containerName()));
        }

        /** Attaches to the exec, which starts the command and begins streaming its output back. */
        private void startStreaming() {
            if (!streaming.compareAndSet(false, true)) {
                return;
            }
            ExecSession current = session;
            String id = execId;
            // The pipe is wired up before the attach is dispatched, so input the client sends the
            // moment the handshake completes has somewhere to go rather than being dropped.
            PipedOutputStream toContainer = new PipedOutputStream();
            PipedInputStream execStdin;
            try {
                execStdin = new PipedInputStream(toContainer);
            } catch (IOException e) {
                failChannel(e, current.containerName());
                return;
            }
            containerInput = toContainer;
            vertx.<Closeables>executeBlocking(() -> new Closeables(lifecycleManager.getDockerClient()
                    .execStartCmd(id)
                    .withStdIn(execStdin)
                    .withTty(current.interactive())
                    .exec(new ExecFrameCallback(this)), execStdin), false)
                    .onSuccess(started -> exec = started)
                    .onFailure(cause -> failChannel(cause, current.containerName()));
        }

        private void failChannel(Throwable cause, String containerName) {
            LOG.warnv("ECS Exec session {0} could not start in container {1}: {2}",
                    sessionId, containerName, cause.getMessage());
            sendError("Failed to start the command in container " + containerName
                    + ": " + cause.getMessage());
            onContext(() -> webSocket.close((short) 1011, "Exec failed to start"));
        }

        private void onBinaryFrame(Buffer buffer) {
            if (session == null) {
                return;
            }
            AgentMessage message;
            try {
                message = AgentMessage.decode(buffer.getBytes());
            } catch (IllegalArgumentException e) {
                LOG.debugv("ECS Exec channel {0} sent an undecodable frame: {1}", sessionId, e.getMessage());
                return;
            }
            switch (message.messageType()) {
                case AgentMessage.INPUT_STREAM_DATA -> {
                    acknowledge(message);
                    handleInput(message);
                }
                case AgentMessage.CHANNEL_CLOSED -> shutdown();
                case AgentMessage.ACKNOWLEDGE -> { /* the client confirming our output; nothing to do */ }
                default -> LOG.debugv("Ignoring ECS Exec message of type {0}", message.messageType());
            }
        }

        private void handleInput(AgentMessage message) {
            switch (message.payloadType()) {
                case AgentMessage.PAYLOAD_HANDSHAKE_RESPONSE -> {
                    sendHandshakeComplete();
                    startStreaming();
                }
                case AgentMessage.PAYLOAD_SIZE -> resizeTerminal(message.payloadAsString());
                case AgentMessage.PAYLOAD_OUTPUT -> writeToContainer(message.payload());
                default -> LOG.debugv("Ignoring ECS Exec payload of type {0}", message.payloadType());
            }
        }

        private void writeToContainer(byte[] payload) {
            PipedOutputStream stream = containerInput;
            if (stream == null || payload == null || payload.length == 0) {
                return;
            }
            try {
                stream.write(payload);
                stream.flush();
            } catch (IOException e) {
                LOG.debugv("ECS Exec session {0} could not write to the container: {1}",
                        sessionId, e.getMessage());
                shutdown();
            }
        }

        private void resizeTerminal(String payload) {
            String id = execId;
            if (id == null) {
                return;
            }
            int rows;
            int cols;
            try {
                JsonNode size = objectMapper.readTree(payload);
                rows = size.path("rows").asInt(0);
                cols = size.path("cols").asInt(0);
            } catch (Exception e) {
                LOG.debugv("ECS Exec session {0} sent an unreadable terminal size: {1}",
                        sessionId, e.getMessage());
                return;
            }
            if (rows <= 0 || cols <= 0) {
                return;
            }
            vertx.executeBlocking(() -> lifecycleManager.getDockerClient().resizeExecCmd(id)
                    .withSize(rows, cols).exec(), false)
                    .onFailure(cause -> LOG.debugv("ECS Exec session {0} could not resize the terminal: {1}",
                            sessionId, cause.getMessage()));
        }

        private void sendHandshakeRequest() {
            ObjectNode action = objectMapper.createObjectNode();
            action.put("ActionType", "SessionType");
            ObjectNode parameters = action.putObject("ActionParameters");
            parameters.put("SessionType", "Standard_Stream");
            parameters.putNull("Properties");

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("AgentVersion", AGENT_VERSION);
            payload.putArray("RequestedClientActions").add(action);

            send(AgentMessage.PAYLOAD_HANDSHAKE_REQUEST, asBytes(payload));
        }

        private void sendHandshakeComplete() {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("HandshakeTimeToComplete", 0);
            payload.put("CustomerMessage", "");
            send(AgentMessage.PAYLOAD_HANDSHAKE_COMPLETE, asBytes(payload));
        }

        private void sendError(String message) {
            send(AgentMessage.PAYLOAD_ERROR, message.getBytes(StandardCharsets.UTF_8));
        }

        void sendOutput(byte[] payload) {
            send(AgentMessage.PAYLOAD_OUTPUT, payload);
        }

        /**
         * The sequence number is taken on the context, immediately before the frame is written, so
         * that the numbers the client sees arrive in order however many threads produced them.
         */
        private void send(int payloadType, byte[] payload) {
            onContext(() -> writeFrame(AgentMessage.outbound(AgentMessage.OUTPUT_STREAM_DATA,
                    outboundSequence.getAndIncrement(), payloadType, payload)));
        }

        private void acknowledge(AgentMessage message) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("AcknowledgedMessageType", message.messageType());
            payload.put("AcknowledgedMessageId", message.messageId().toString());
            payload.put("AcknowledgedMessageSequenceNumber", message.sequenceNumber());
            payload.put("IsSequentialMessage", true);
            onContext(() -> writeFrame(AgentMessage.outbound(AgentMessage.ACKNOWLEDGE,
                    message.sequenceNumber(), AgentMessage.PAYLOAD_OUTPUT, asBytes(payload))));
        }

        private void writeFrame(AgentMessage message) {
            if (webSocket.isClosed()) {
                return;
            }
            try {
                webSocket.writeBinaryMessage(Buffer.buffer(message.encode()));
            } catch (RuntimeException e) {
                LOG.debugv("ECS Exec session {0} could not write a frame: {1}", sessionId, e.getMessage());
            }
        }

        /**
         * Runs on the channel's context. Everything that touches the WebSocket goes through here,
         * including calls already on that context, so the frames keep their submission order.
         */
        private void onContext(Runnable action) {
            context.runOnContext(ignored -> action.run());
        }

        void closeAfterExec() {
            onContext(() -> {
                if (!webSocket.isClosed()) {
                    writeFrame(AgentMessage.outbound(AgentMessage.CHANNEL_CLOSED,
                            outboundSequence.getAndIncrement(), AgentMessage.PAYLOAD_OUTPUT, new byte[0]));
                    webSocket.close();
                }
            });
            shutdown();
        }

        /** Closing the exec streams talks to the daemon, so it too stays off the event loop. */
        private void shutdown() {
            Closeables open = exec;
            PipedOutputStream stream = containerInput;
            exec = null;
            containerInput = null;
            if (open == null && stream == null) {
                return;
            }
            vertx.executeBlocking(() -> {
                if (open != null) {
                    open.close();
                }
                if (stream != null) {
                    Closeables.closeQuietly(stream);
                }
                return null;
            }, false);
        }

        private byte[] asBytes(ObjectNode node) {
            try {
                return objectMapper.writeValueAsBytes(node);
            } catch (Exception e) {
                throw new IllegalStateException("Could not serialize an ECS Exec payload", e);
            }
        }
    }

    /** The exec's output callback and its stdin pipe, closed together when the channel ends. */
    private record Closeables(Closeable callback, Closeable stdin) {
        void close() {
            closeQuietly(callback);
            closeQuietly(stdin);
        }

        private static void closeQuietly(Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException | RuntimeException e) {
                LOG.debugv("Could not close an ECS Exec stream: {0}", e.getMessage());
            }
        }
    }

    /** Streams the container's output back over the channel, one frame at a time. */
    private static final class ExecFrameCallback extends ResultCallback.Adapter<Frame> {

        private final Channel channel;

        private ExecFrameCallback(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void onNext(Frame frame) {
            byte[] payload = frame.getPayload();
            if (payload != null && payload.length > 0) {
                channel.sendOutput(payload);
            }
        }

        @Override
        public void onComplete() {
            channel.closeAfterExec();
        }

        @Override
        public void onError(Throwable throwable) {
            LOG.debugv("ECS Exec output stream ended with an error: {0}", throwable.getMessage());
            channel.closeAfterExec();
        }
    }
}
