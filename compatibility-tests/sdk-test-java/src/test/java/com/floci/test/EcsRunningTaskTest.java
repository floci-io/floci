package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.CreateClusterRequest;
import software.amazon.awssdk.services.ecs.model.DeleteClusterRequest;
import software.amazon.awssdk.services.ecs.model.ExecuteCommandRequest;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.NetworkMode;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.RunTaskRequest;
import software.amazon.awssdk.services.ecs.model.Session;
import software.amazon.awssdk.services.ecs.model.StopTaskRequest;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("ECS running task: metadata stats and ECS Exec")
class EcsRunningTaskTest {

    private static final String STATS_MARKER = "STATS:";

    private static EcsClient ecs;
    private static CloudWatchLogsClient logs;
    private static String clusterName;
    private static String family;
    private static String taskArn;

    @BeforeAll
    static void setup() {
        assumeTrue(TestFixtures.isLambdaDispatchAvailable(),
                "Skipping ECS running task test: Docker dispatch not available in this environment");
        ecs = TestFixtures.ecsClient();
        logs = TestFixtures.cloudWatchLogsClient();
        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        clusterName = "stats-cluster-" + suffix;
        family = "stats-task-" + suffix;
        ecs.createCluster(CreateClusterRequest.builder().clusterName(clusterName).build());
        ecs.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
                .family(family)
                .networkMode(NetworkMode.BRIDGE)
                .containerDefinitions(ContainerDefinition.builder()
                        .name("main")
                        .image("busybox:latest")
                        .command("sh", "-c", "sleep 2; echo " + STATS_MARKER
                                + "$(wget -qO- $ECS_CONTAINER_METADATA_URI_V4/stats); sleep 120")
                        .essential(true)
                        .memory(64)
                        .build())
                .build());
        taskArn = ecs.runTask(RunTaskRequest.builder()
                .cluster(clusterName)
                .taskDefinition(family)
                .launchType(LaunchType.FARGATE)
                .enableExecuteCommand(true)
                .count(1)
                .build()).tasks().get(0).taskArn();
    }

    @AfterAll
    static void cleanup() {
        if (ecs == null) {
            return;
        }
        if (taskArn != null) {
            try {
                ecs.stopTask(StopTaskRequest.builder().cluster(clusterName).task(taskArn).build());
            } catch (Exception ignored) {
                // The task may already have stopped. Cleanup must not hide the test's own result.
            }
        }
        try {
            ecs.deleteCluster(DeleteClusterRequest.builder().cluster(clusterName).build());
        } catch (Exception ignored) {
            // Cleanup must not hide the test's own result.
        }
        ecs.close();
        logs.close();
    }

    @Test
    @DisplayName("GET ${ECS_CONTAINER_METADATA_URI_V4}/stats returns Docker's stats for the container")
    void containerReadsItsOwnStats() throws Exception {
        String stats = awaitStatsLine();

        JsonNode node = new ObjectMapper().readTree(stats);
        assertThat(node.path("cpu_stats").path("cpu_usage").path("total_usage").asLong()).isPositive();
        assertThat(node.path("memory_stats").path("usage").asLong()).isPositive();
    }

    @Test
    @DisplayName("ExecuteCommand - a size message resizes the session's terminal")
    void execSessionResizesTheTerminal() throws Exception {
        Session session = ecs.executeCommand(ExecuteCommandRequest.builder()
                .cluster(clusterName)
                .task(taskArn)
                .container("main")
                .interactive(true)
                .command("/bin/sh")
                .build()).session();

        SessionClient client = new SessionClient(session);
        try {
            client.open();
            assertThat(client.next().payloadType).isEqualTo(SessionClient.PAYLOAD_HANDSHAKE_REQUEST);
            client.send(SessionClient.PAYLOAD_HANDSHAKE_RESPONSE, "{\"ClientVersion\":\"1.2.0.0\","
                    + "\"ProcessedClientActions\":[{\"ActionType\":\"SessionType\",\"ActionStatus\":1}],\"Errors\":\"\"}");
            assertThat(client.readUntil("# ", 30)).contains("# ");
            client.send(SessionClient.PAYLOAD_SIZE, "{\"cols\":100,\"rows\":40}");

            // The resize reaches Docker asynchronously, so ask again until the new size shows.
            String output = "";
            for (int attempt = 0; attempt < 5 && !output.contains("40 100"); attempt++) {
                client.send(SessionClient.PAYLOAD_OUTPUT, "stty size\n");
                output = client.readUntil("40 100", 2);
            }
            assertThat(output).contains("40 100");
        } finally {
            client.close();
        }
    }

    private static String awaitStatsLine() throws InterruptedException {
        String logGroup = "/ecs/" + family;
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<FilteredLogEvent> events = logs.filterLogEvents(FilterLogEventsRequest.builder()
                        .logGroupName(logGroup)
                        .build()).events();
                for (FilteredLogEvent event : events) {
                    String message = event.message().strip();
                    if (message.startsWith(STATS_MARKER)) {
                        return message.substring(STATS_MARKER.length());
                    }
                }
            } catch (Exception ignored) {
                // The log group does not exist until the container writes its first line.
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("No stats line in " + logGroup + " within 60s");
    }

    /** The client half of the Session Manager data channel that session-manager-plugin speaks. */
    private static final class SessionClient implements WebSocket.Listener {

        static final int PAYLOAD_OUTPUT = 1;
        static final int PAYLOAD_SIZE = 3;
        static final int PAYLOAD_HANDSHAKE_REQUEST = 5;
        static final int PAYLOAD_HANDSHAKE_RESPONSE = 6;

        private static final String INPUT_STREAM_DATA = "input_stream_data";
        private static final String OUTPUT_STREAM_DATA = "output_stream_data";
        private static final int PAYLOAD_OFFSET = 120;

        private final Session session;
        private final BlockingQueue<Frame> received = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
        private WebSocket webSocket;
        private long sequence;

        SessionClient(Session session) {
            this.session = session;
        }

        void open() throws Exception {
            webSocket = TestFixtures.emulatorHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create(session.streamUrl()), this)
                    .get(20, TimeUnit.SECONDS);
            webSocket.sendText("{\"MessageSchemaVersion\":\"1.0\",\"RequestId\":\"" + UUID.randomUUID()
                    + "\",\"TokenValue\":\"" + session.tokenValue() + "\",\"ClientId\":\"" + UUID.randomUUID() + "\"}",
                    true).get(10, TimeUnit.SECONDS);
        }

        Frame next() throws InterruptedException {
            while (true) {
                Frame frame = received.poll(30, TimeUnit.SECONDS);
                assertThat(frame).as("a frame from the agent").isNotNull();
                if (OUTPUT_STREAM_DATA.equals(frame.messageType)) {
                    return frame;
                }
            }
        }

        void send(int payloadType, String payload) throws Exception {
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            byte[] type = new byte[32];
            Arrays.fill(type, (byte) ' ');
            byte[] name = INPUT_STREAM_DATA.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(name, 0, type, 0, name.length);
            UUID id = UUID.randomUUID();
            ByteBuffer buffer = ByteBuffer.allocate(PAYLOAD_OFFSET + body.length);
            buffer.putInt(116);
            buffer.put(type);
            buffer.putInt(1);
            buffer.putLong(System.currentTimeMillis());
            buffer.putLong(sequence++);
            buffer.putLong(0L);
            buffer.putLong(id.getLeastSignificantBits());
            buffer.putLong(id.getMostSignificantBits());
            buffer.put(MessageDigest.getInstance("SHA-256").digest(body));
            buffer.putInt(payloadType);
            buffer.putInt(body.length);
            buffer.put(body);
            webSocket.sendBinary(ByteBuffer.wrap(buffer.array()), true).get(10, TimeUnit.SECONDS);
        }

        String readUntil(String expected, int seconds) throws InterruptedException {
            StringBuilder output = new StringBuilder();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline && !output.toString().contains(expected)) {
                Frame frame = received.poll(1, TimeUnit.SECONDS);
                if (frame != null && OUTPUT_STREAM_DATA.equals(frame.messageType)
                        && frame.payloadType == PAYLOAD_OUTPUT) {
                    output.append(new String(frame.payload, StandardCharsets.UTF_8));
                }
            }
            return output.toString();
        }

        void close() {
            if (webSocket != null) {
                webSocket.abort();
            }
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            partial.writeBytes(chunk);
            if (last) {
                received.add(Frame.decode(partial.toByteArray()));
                partial.reset();
            }
            socket.request(1);
            return null;
        }

        private record Frame(String messageType, int payloadType, byte[] payload) {

            static Frame decode(byte[] bytes) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                buffer.position(4);
                byte[] type = new byte[32];
                buffer.get(type);
                buffer.position(112);
                int payloadType = buffer.getInt();
                int length = buffer.getInt();
                byte[] payload = Arrays.copyOfRange(bytes, PAYLOAD_OFFSET,
                        Math.min(bytes.length, PAYLOAD_OFFSET + length));
                return new Frame(new String(type, StandardCharsets.UTF_8).trim(), payloadType, payload);
            }
        }
    }
}
