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
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GelfLogListenerTest {

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final GelfLogListener listener = new GelfLogListener(mock(DockerClient.class), true, "127.0.0.1");
    private GelfLogListener restarted;

    @AfterEach
    void close() {
        listener.close();
        if (restarted != null) {
            restarted.close();
        }
    }

    private static final Map<String, String> ROTATION = Map.of("max-size", "10m", "max-file", "3");

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
        BiConsumer<StreamType, String> sink = (stream, line) -> lines.add(line);
        listener.register("token-a", sink);
        listener.unregister("token-a", sink);

        listener.readMessages(new ByteArrayInputStream(messages("{\"short_message\":\"late\",\"_tag\":\"token-a\"}")));

        assertTrue(lines.isEmpty());
    }

    // Catches: the delayed close of a container's earlier log handle removing the attach that replaced it,
    // so the container's later lines are dropped.
    @Test
    void anEarlierHandlesCloseLeavesALaterAttachOfTheSameContainer() throws IOException {
        BiConsumer<StreamType, String> earlier = (stream, line) -> lines.add("earlier " + line);
        listener.register("token-a", earlier);
        listener.register("token-a", (stream, line) -> lines.add("later " + line));
        listener.unregister("token-a", earlier);

        listener.readMessages(new ByteArrayInputStream(messages("{\"short_message\":\"up\",\"_tag\":\"token-a\"}")));

        assertEquals(List.of("later up"), lines);
    }

    // Catches: a container kept across a restart of Floci, which still dials the old listener's port,
    // attached to a listener on another port, so its log never arrives.
    @Test
    void aRestartedListenerTakesBackThePortAnExistingContainerDials() throws IOException {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        restarted = new GelfLogListener(mock(DockerClient.class), true, "127.0.0.1");

        assertTrue(restarted.listensAt("tcp://127.0.0.1:" + port), "a listener not started yet binds that port");
        assertEquals("127.0.0.1:" + port, restarted.ensureStarted());

        String elsewhere = listener.logConfigFor("token-b", ROTATION).getConfig().get("gelf-address");
        assertFalse(restarted.listensAt(elsewhere), "a started listener answers only for its own address");
        assertFalse(restarted.listensAt(null));
    }

    // Catches: the log configuration not pointing the daemon at the listener it opened.
    @Test
    void theLogConfigSendsGelfOverTcpToTheListener() throws Exception {
        LogConfig logConfig = listener.logConfigFor("token-a", ROTATION);
        assertEquals(LogConfig.LoggingType.GELF, logConfig.getType());
        assertEquals("token-a", logConfig.getConfig().get("tag"));
        assertEquals("10m", logConfig.getConfig().get("cache-max-size"), "the rotation bounds the daemon's copy");
        assertEquals("3", logConfig.getConfig().get("cache-max-file"));
        String address = logConfig.getConfig().get("gelf-address");
        assertTrue(address.startsWith("tcp://127.0.0.1:"), address);

        listener.register("token-a", (stream, line) -> lines.add(line));
        int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
        try (Socket daemon = new Socket("127.0.0.1", port); OutputStream out = daemon.getOutputStream()) {
            out.write(messages("{\"short_message\":\"over tcp\",\"level\":6,\"_tag\":\"token-a\"}"));
        }
        await().until(() -> !lines.isEmpty());
        assertEquals(List.of("over tcp"), lines);
    }

    // Catches: the mode switching a container nobody attaches (a Flink TaskManager, an ephemeral RDS
    // instance, a Lambda) to the gelf driver, or leaving an attached one on json-file without its tag.
    @Test
    @SuppressWarnings("unchecked")
    void onlyContainersMarkedForPushedLogsGetTheGelfDriverAndATag() {
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

        ContainerSpec jobManager = builder.newContainer("flink:1.20")
                .withLabels(Map.of(ContainerStorageHelper.SERVICE_LABEL, "kinesisanalytics"))
                .withLogRotation()
                .withPushedLogs()
                .build();
        ContainerSpec taskManager = builder.newContainer("flink:1.20")
                .withLabels(Map.of(ContainerStorageHelper.SERVICE_LABEL, "kinesisanalytics"))
                .withLogRotation()
                .build();

        assertEquals(LogConfig.LoggingType.GELF, jobManager.logConfig().getType());
        assertEquals(jobManager.logConfig().getConfig().get("tag"),
                jobManager.labels().get(GelfLogListener.LOG_TOKEN_LABEL));
        assertEquals("10m", jobManager.logConfig().getConfig().get("cache-max-size"));
        assertEquals(LogConfig.LoggingType.JSON_FILE, taskManager.logConfig().getType());
        assertNull(taskManager.labels().get(GelfLogListener.LOG_TOKEN_LABEL));
        assertFalse(taskManager.labels().containsKey(GelfLogListener.LOG_TOKEN_LABEL));
    }

    // Catches: the lines a container writes between its start and its attach, its startup banner, lost.
    @Test
    void linesSentBeforeTheAttachAreHeldForIt() throws IOException {
        listener.logConfigFor("token-a", ROTATION);
        listener.readMessages(new ByteArrayInputStream(messages(
                "{\"short_message\":\"starting\",\"level\":6,\"_tag\":\"token-a\"}")));

        listener.register("token-a", (stream, line) -> lines.add(line));
        listener.readMessages(new ByteArrayInputStream(messages(
                "{\"short_message\":\"ready\",\"level\":6,\"_tag\":\"token-a\"}")));

        assertEquals(List.of("starting", "ready"), lines);
    }
}
