package io.github.hectorvent.floci.services.ecs.exec;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.WebSocket;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ECS Exec end to end, against a real container.
 *
 * <p>This drives the client half of the Session Manager protocol the way
 * {@code session-manager-plugin} does: open the channel with the session token, answer the
 * handshake, then read the command's output off the stream. The plugin binary is not available in
 * CI, so the protocol is exercised from here rather than from it.
 */
@QuarkusTest
class EcsExecChannelDockerIntegrationTest {

    private static final String BUSYBOX_IMAGE = "public.ecr.aws/docker/library/busybox:latest";
    private static final String TASK_ARN =
            "arn:aws:ecs:us-east-1:000000000000:task/exec-cluster/execdockertask";

    @Inject
    EcsContainerManager containerManager;

    @Inject
    EcsExecSessionRegistry sessions;

    @Inject
    DockerClient dockerClient;

    @Inject
    Vertx vertx;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for the ECS Exec channel test");
    }

    @Test
    void aClientDrivingTheProtocolGetsTheCommandsOutput() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage(BUSYBOX_IMAGE);
        // PID 1 gets no default SIGTERM handler, so a bare "sleep 120" sits out the whole
        // stopTimeout grace period on teardown.
        app.setCommand(List.of("sh", "-c", "trap 'exit 0' TERM; sleep 120 & wait"));

        TaskDefinition taskDefinition = new TaskDefinition();
        taskDefinition.setFamily("exec-docker-" + suffix);
        taskDefinition.setContainerDefinitions(List.of(app));

        EcsTask task = new EcsTask();
        task.setTaskArn(TASK_ARN + suffix);
        task.setEnableExecuteCommand(true);

        EcsTaskHandle handle = containerManager.startTask(task, taskDefinition, List.of(), "us-east-1");
        try {
            // The agent is reported as running, which is what a client checks before connecting.
            assertEquals(ManagedAgentStatus.RUNNING, ManagedAgentStatus.of(task));

            String runtimeId = handle.getContainerIds().get("app");
            ExecSession session = sessions.create(task.getTaskArn(),
                    "arn:aws:ecs:us-east-1:000000000000:cluster/exec-cluster", "app",
                    "arn:aws:ecs:us-east-1:000000000000:container/exec/app", runtimeId,
                    List.of("/bin/sh", "-c", "echo exec-channel-ok"), false);

            PluginClient client = new PluginClient(vertx, session);
            try {
                client.open();

                AgentMessage handshakeRequest = client.next();
                assertEquals(AgentMessage.OUTPUT_STREAM_DATA, handshakeRequest.messageType());
                assertEquals(AgentMessage.PAYLOAD_HANDSHAKE_REQUEST, handshakeRequest.payloadType());
                assertTrue(handshakeRequest.payloadAsString().contains("Standard_Stream"),
                        "the agent asks for a standard stream session: "
                                + handshakeRequest.payloadAsString());

                client.sendHandshakeResponse();

                String output = client.readUntil("exec-channel-ok");
                assertTrue(output.contains("exec-channel-ok"),
                        "the command's output must reach the channel, got: " + output);
            } finally {
                client.close();
            }

            // The session was single use: the token cannot open a second shell.
            assertTrue(sessions.find(session.sessionId()).isEmpty(),
                    "a claimed session must not be claimable again");
        } finally {
            containerManager.stopTask(handle);
        }
    }

    /** Reads the ExecuteCommandAgent status a task's containers report. */
    private enum ManagedAgentStatus {
        RUNNING, ABSENT;

        static ManagedAgentStatus of(EcsTask task) {
            return task.getContainers() != null
                    && task.getContainers().stream().anyMatch(container ->
                            container.getManagedAgents() != null
                                    && container.getManagedAgents().stream().anyMatch(agent ->
                                            "ExecuteCommandAgent".equals(agent.name())
                                                    && "RUNNING".equals(agent.lastStatus())))
                    ? RUNNING : ABSENT;
        }
    }

    /** The half of the Session Manager protocol that {@code session-manager-plugin} implements. */
    private static final class PluginClient {

        private final Vertx vertx;
        private final ExecSession session;
        private final BlockingQueue<AgentMessage> received = new LinkedBlockingQueue<>();

        private HttpClient httpClient;
        private WebSocket webSocket;
        private long sequence;

        PluginClient(Vertx vertx, ExecSession session) {
            this.vertx = vertx;
            this.session = session;
        }

        void open() throws Exception {
            httpClient = vertx.createHttpClient();
            webSocket = httpClient.webSocket(RestAssured.port, "localhost",
                            EcsExecChannelHandler.CHANNEL_PATH_PREFIX + session.sessionId())
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

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
