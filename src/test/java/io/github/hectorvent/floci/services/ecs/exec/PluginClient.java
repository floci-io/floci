package io.github.hectorvent.floci.services.ecs.exec;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.WebSocket;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The half of the Session Manager protocol that {@code session-manager-plugin} implements. */
final class PluginClient {

    private final Vertx vertx;
    private final ExecSession session;
    private final URI streamUrl;
    private final BlockingQueue<AgentMessage> received = new LinkedBlockingQueue<>();

    private HttpClient httpClient;
    private WebSocket webSocket;
    private long sequence;

    PluginClient(Vertx vertx, ExecSession session, URI streamUrl) {
        this.vertx = vertx;
        this.session = session;
        this.streamUrl = streamUrl;
    }

    void open() throws Exception {
        httpClient = vertx.createHttpClient();
        webSocket = httpClient.webSocket(streamUrl.getPort(), streamUrl.getHost(), streamUrl.getRawPath())
                .toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
        webSocket.binaryMessageHandler(buffer -> received.add(AgentMessage.decode(buffer.getBytes())));
        webSocket.writeTextMessage("{\"MessageSchemaVersion\":\"1.0\",\"RequestId\":\""
                + UUID.randomUUID() + "\",\"TokenValue\":\"" + session.tokenValue()
                + "\",\"ClientId\":\"" + UUID.randomUUID() + "\"}");
    }

    void sendHandshakeResponse() {
        String payload = "{\"ClientVersion\":\"1.2.0.0\",\"ProcessedClientActions\":"
                + "[{\"ActionType\":\"SessionType\",\"ActionStatus\":1}],\"Errors\":\"\"}";
        AgentMessage response = AgentMessage.outbound(AgentMessage.INPUT_STREAM_DATA, sequence++,
                AgentMessage.PAYLOAD_HANDSHAKE_RESPONSE, payload.getBytes(StandardCharsets.UTF_8));
        webSocket.writeBinaryMessage(Buffer.buffer(response.encode()));
    }

    AgentMessage next() throws InterruptedException {
        AgentMessage message = received.poll(30, TimeUnit.SECONDS);
        assertNotNull(message, "the agent must send a frame");
        return message;
    }

    byte[] readOutput() throws InterruptedException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            AgentMessage message = received.poll(1, TimeUnit.SECONDS);
            if (message == null) {
                continue;
            }
            if (AgentMessage.CHANNEL_CLOSED.equals(message.messageType())) {
                return output.toByteArray();
            }
            if (AgentMessage.OUTPUT_STREAM_DATA.equals(message.messageType())
                    && message.payloadType() == AgentMessage.PAYLOAD_OUTPUT) {
                output.writeBytes(message.payload());
            }
        }
        throw new AssertionError("the command must finish and close its channel");
    }

    /** Collects output frames until the expected text shows up or the channel goes quiet. */
    String readUntil(String expected) throws InterruptedException {
        StringBuilder output = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && !output.toString().contains(expected)) {
            AgentMessage message = received.poll(1, TimeUnit.SECONDS);
            if (message == null) {
                continue;
            }
            if (AgentMessage.OUTPUT_STREAM_DATA.equals(message.messageType())
                    && message.payloadType() == AgentMessage.PAYLOAD_OUTPUT) {
                output.append(message.payloadAsString());
            }
        }
        return output.toString();
    }

    void close() {
        if (webSocket != null) {
            webSocket.close();
        }
        if (httpClient != null) {
            httpClient.close();
        }
    }
}
