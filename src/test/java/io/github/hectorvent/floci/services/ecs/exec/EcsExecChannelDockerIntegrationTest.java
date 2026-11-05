package io.github.hectorvent.floci.services.ecs.exec;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskHandle;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static final String BUSYBOX_IMAGE = TestImages.BUSYBOX;
    private static final String TASK_ARN =
            "arn:aws:ecs:us-east-1:000000000000:task/exec-cluster/execdockertask";

    @Inject
    EcsContainerManager containerManager;

    @Inject
    EcsService service;

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aClientDrivingTheProtocolGetsTheCommandsOutput(boolean interactive) throws Exception {
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
                    List.of("/bin/sh", "-c", "printf '\\000\\001\\002\\003\\377\\376'; "
                            + "head -c 5000 /dev/zero; printf 'exec-channel-ok'"), interactive);

            PluginClient client = new PluginClient(vertx, session,
                    URI.create(service.execStreamUrl(session, URI.create("http://localhost:" + RestAssured.port + "/"))));
            try {
                client.open();

                AgentMessage handshakeRequest = client.next();
                assertEquals(AgentMessage.OUTPUT_STREAM_DATA, handshakeRequest.messageType());
                assertEquals(AgentMessage.PAYLOAD_HANDSHAKE_REQUEST, handshakeRequest.payloadType());
                assertTrue(handshakeRequest.payloadAsString().contains("Standard_Stream"),
                        "the agent asks for a standard stream session: "
                                + handshakeRequest.payloadAsString());

                client.sendHandshakeResponse();

                byte[] suffixBytes = "exec-channel-ok".getBytes(StandardCharsets.UTF_8);
                byte[] expected = new byte[6 + 5000 + suffixBytes.length];
                expected[1] = 1;
                expected[2] = 2;
                expected[3] = 3;
                expected[4] = (byte) 0xff;
                expected[5] = (byte) 0xfe;
                System.arraycopy(suffixBytes, 0, expected, 5006, suffixBytes.length);
                assertArrayEquals(expected, client.readOutput(),
                        "binary output must survive Docker decoding and asynchronous channel writes");
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

    @Test
    void theSessionRunsAsRootEvenWhenTheContainerDoesNot() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage(BUSYBOX_IMAGE);
        app.setUser("1000:1000");
        app.setCommand(List.of("sh", "-c", "trap 'exit 0' TERM; sleep 120 & wait"));

        TaskDefinition taskDefinition = new TaskDefinition();
        taskDefinition.setFamily("exec-root-" + suffix);
        taskDefinition.setContainerDefinitions(List.of(app));

        EcsTask task = new EcsTask();
        task.setTaskArn(TASK_ARN + suffix);
        task.setEnableExecuteCommand(true);

        EcsTaskHandle handle = containerManager.startTask(task, taskDefinition, List.of(), "us-east-1");
        try {
            String runtimeId = handle.getContainerIds().get("app");
            assertEquals("1000:1000",
                    dockerClient.inspectContainerCmd(runtimeId).exec().getConfig().getUser(),
                    "the container itself must run as the task definition's user");
            ExecSession session = sessions.create(task.getTaskArn(),
                    "arn:aws:ecs:us-east-1:000000000000:cluster/exec-cluster", "app",
                    "arn:aws:ecs:us-east-1:000000000000:container/exec/app", runtimeId,
                    List.of("/bin/sh", "-c", "echo exec-uid-$(id -u)-end"), false);

            PluginClient client = new PluginClient(vertx, session,
                    URI.create(service.execStreamUrl(session, URI.create("http://localhost:" + RestAssured.port + "/"))));
            try {
                client.open();
                client.next();
                client.sendHandshakeResponse();

                String output = client.readUntil("-end");
                assertTrue(output.contains("exec-uid-0-end"),
                        "the session must run as root, got: " + output);
            } finally {
                client.close();
            }
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

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
