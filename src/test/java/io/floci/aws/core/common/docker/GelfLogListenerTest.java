package io.floci.aws.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.StreamType;
import io.floci.aws.config.EmulatorConfig;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GelfLogListenerTest {

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final GelfLogListener listener = new GelfLogListener(mock(DockerClient.class), true, "127.0.0.1");

    @AfterEach
    void close() {
        listener.close();
    }

    private static byte[] messages(String... documents) {
        StringBuilder stream = new StringBuilder();
        for (String document : documents) {
            stream.append(document).append('\0');
        }
        return stream.toString().getBytes(StandardCharsets.UTF_8);
    }

    // Catches: a line reaching the wrong container's log, standard error read as standard output, or a
    // message another container sent under an unknown tag being accepted.
    @Test
    void eachMessageReachesTheContainerItsTagNames() throws IOException {
        listener.register("token-a", (stream, line) -> lines.add("a " + stream + " " + line));
        listener.register("token-b", (stream, line) -> lines.add("b " + stream + " " + line));

        listener.readMessages(new ByteArrayInputStream(messages(
                "{\"version\":\"1.1\",\"short_message\":\"ready\",\"level\":6,\"_tag\":\"token-a\"}",
                "{\"version\":\"1.1\",\"short_message\":\"oops\",\"level\":3,\"_tag\":\"token-b\"}",
                "{\"version\":\"1.1\",\"short_message\":\"spoofed\",\"level\":6,\"_tag\":\"guess\"}",
                "not json")));

        assertEquals(List.of("a STDOUT ready", "b STDERR oops"), lines);
    }

    // Catches: lines still forwarded for a container whose log handle was closed.
    @Test
    void anUnregisteredTagIsDropped() throws IOException {
        listener.register("token-a", (stream, line) -> lines.add(line));
        listener.unregister("token-a");

        listener.readMessages(new ByteArrayInputStream(messages("{\"short_message\":\"late\",\"_tag\":\"token-a\"}")));

        assertTrue(lines.isEmpty());
    }

    // Catches: the log configuration not pointing the daemon at the listener it opened.
    @Test
    void theLogConfigSendsGelfOverTcpToTheListener() throws Exception {
        LogConfig logConfig = listener.logConfigFor("token-a");
        assertEquals(LogConfig.LoggingType.GELF, logConfig.getType());
        assertEquals("token-a", logConfig.getConfig().get("tag"));
        String address = logConfig.getConfig().get("gelf-address");
        assertTrue(address.startsWith("tcp://127.0.0.1:"), address);

        listener.register("token-a", (stream, line) -> lines.add(line));
        int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
        try (Socket daemon = new Socket("127.0.0.1", port); OutputStream out = daemon.getOutputStream()) {
            out.write(messages("{\"short_message\":\"over tcp\",\"level\":6,\"_tag\":\"token-a\"}"));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (lines.isEmpty()) {
            if (System.nanoTime() > deadline) {
                fail("the message sent over TCP should arrive");
            }
            Thread.onSpinWait();
        }
        assertEquals(List.of("over tcp"), lines);
    }

    // Catches: the mode switching a short-lived container (a Lambda) to the gelf driver, or leaving a
    // long-lived one on json-file without its tag.
    @Test
    @SuppressWarnings("unchecked")
    void onlyLongLivedServiceContainersGetTheGelfDriverAndATag() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(docker);
        when(docker.logDriverMode()).thenReturn(true);
        when(docker.logMaxSize()).thenReturn("10m");
        when(docker.logMaxFile()).thenReturn("3");
        ContainerBuilder builder = new ContainerBuilder(config, null, null);
        Instance<GelfLogListener> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(listener);
        builder.gelfLogListener = instance;

        ContainerSpec redis = builder.newContainer("valkey/valkey:8")
                .withLabels(Map.of(ContainerStorageHelper.SERVICE_LABEL, "elasticache"))
                .withLogRotation()
                .build();
        ContainerSpec lambda = builder.newContainer("public.ecr.aws/lambda/nodejs:20")
                .withLabels(Map.of(ContainerStorageHelper.SERVICE_LABEL, "lambda"))
                .withLogRotation()
                .build();

        assertEquals(LogConfig.LoggingType.GELF, redis.logConfig().getType());
        assertEquals(redis.logConfig().getConfig().get("tag"), redis.labels().get(GelfLogListener.LOG_TOKEN_LABEL));
        assertEquals(LogConfig.LoggingType.JSON_FILE, lambda.logConfig().getType());
        assertNull(lambda.labels().get(GelfLogListener.LOG_TOKEN_LABEL));
        assertFalse(lambda.labels().containsKey(GelfLogListener.LOG_TOKEN_LABEL));
    }

    // Catches: the lines a container writes between its start and its attach, its startup banner, lost.
    @Test
    void linesSentBeforeTheAttachAreHeldForIt() throws IOException {
        listener.logConfigFor("token-a");
        listener.readMessages(new ByteArrayInputStream(messages(
                "{\"short_message\":\"starting\",\"level\":6,\"_tag\":\"token-a\"}")));

        listener.register("token-a", (stream, line) -> lines.add(line));
        listener.readMessages(new ByteArrayInputStream(messages(
                "{\"short_message\":\"ready\",\"level\":6,\"_tag\":\"token-a\"}")));

        assertEquals(List.of("starting", "ready"), lines);
    }
}
