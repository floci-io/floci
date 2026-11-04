package io.github.hectorvent.floci.services.ecs.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcsExecChannelHandlerTest {

    private static final String RUNTIME_ID = "container-1";

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void closeVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void anExecStreamThatAttachesAfterTheClientLeftIsClosed() throws Exception {
        EcsExecSessionRegistry sessions = new EcsExecSessionRegistry();
        ExecSession session = sessions.create("task", "cluster", "app", "container", RUNTIME_ID,
                List.of("/bin/sh"), false);

        DockerClient docker = mock(DockerClient.class);
        ExecCreateCmd createCmd = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn("exec-1");
        when(createCmd.exec()).thenReturn(created);
        when(docker.execCreateCmd(RUNTIME_ID)).thenReturn(createCmd);

        ExecStartCmd startCmd = mock(ExecStartCmd.class, RETURNS_SELF);
        AtomicReference<InputStream> execStdin = new AtomicReference<>();
        when(startCmd.withStdIn(any())).thenAnswer(invocation -> {
            execStdin.set(invocation.getArgument(0));
            return startCmd;
        });
        CountDownLatch attaching = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        ResultCallback<Frame> attach = mock(ResultCallback.class);
        when(startCmd.exec(any())).thenAnswer(invocation -> {
            attaching.countDown();
            // The attach returns only once the channel has shut down, which closes the exec's stdin.
            while (execStdin.get().read() != -1) {
                // drain
            }
            return attach;
        });
        when(docker.execStartCmd("exec-1")).thenReturn(startCmd);

        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.getDockerClient()).thenReturn(docker);

        Router router = Router.router(vertx);
        new EcsExecChannelHandler(sessions, lifecycleManager, new ObjectMapper(), vertx).init(router);
        HttpServer server = vertx.createHttpServer().requestHandler(router).listen(0)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        PluginClient client = new PluginClient(vertx, session, URI.create("http://localhost:" + server.actualPort()
                + EcsExecChannelHandler.CHANNEL_PATH_PREFIX + session.sessionId()));
        try {
            client.open();
            assertEquals(AgentMessage.PAYLOAD_HANDSHAKE_REQUEST, client.next().payloadType());
            client.sendHandshakeResponse();
            assertTrue(attaching.await(10, TimeUnit.SECONDS), "the agent must attach to the exec");
        } finally {
            client.close();
        }

        verify(attach, timeout(10_000)).close();
    }
}
