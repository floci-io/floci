package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerExecTtyStreamTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 15, 23, 31, 120, 255})
    void ttyExecPreservesLeadingBytesThroughDockerJava(int leadingByte) throws Exception {
        byte[] marker = "ABCDEFGH\n__TCR_EXIT_test=0\n".getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[marker.length + 3];
        payload[0] = (byte) leadingByte;
        payload[1] = (byte) 0xff;
        payload[2] = (byte) 0xfe;
        System.arraycopy(marker, 0, payload, 3, marker.length);
        assertArrayEquals(payload, execute(payload, true, 200));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 7, 8, 1024, 1025, 8193})
    void ttyExecPreservesEmptyShortAndMultiFrameOutput(int length) throws Exception {
        byte[] payload = new byte[length];
        for (int i = 0; i < length; i++) {
            payload[i] = (byte) i;
        }
        assertArrayEquals(payload, execute(payload, true, 200));
    }

    @Test
    void nonTtyExecStillDecodesDaemonFrames() throws Exception {
        byte[] stdout = {0, 1, 2, (byte) 0xff};
        byte[] stderr = {'e'};
        ByteBuffer wire = ByteBuffer.allocate(16 + stdout.length + stderr.length);
        wire.put(new byte[]{1, 0, 0, 0}).putInt(stdout.length).put(stdout);
        wire.put(new byte[]{2, 0, 0, 0}).putInt(stderr.length).put(stderr);
        assertArrayEquals(new byte[]{0, 1, 2, (byte) 0xff, 'e'}, execute(wire.array(), false, 200));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404})
    void ttyExecKeepsDockerErrorResponsesReadable(int status) {
        byte[] error = "{\"message\":\"missing exec\"}".getBytes(StandardCharsets.UTF_8);
        DockerException failure = assertThrows(DockerException.class, () -> execute(error, true, status));
        assertTrue(failure.getMessage().contains("missing exec"));
    }

    private static byte[] execute(byte[] payload, boolean tty, int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1.45/exec/test/start", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.docker.raw-stream");
            exchange.sendResponseHeaders(status, payload.length);
            try (exchange) {
                exchange.getResponseBody().write(payload);
            }
        });
        server.start();
        URI host = URI.create("tcp://127.0.0.1:" + server.getAddress().getPort());
        try (FlociDockerHttpClient transport = new FlociDockerHttpClient.Builder()
                .dockerHost(host).responseTimeout(Duration.ofSeconds(5)).build();
             DockerClient client = DockerClientImpl.getInstance(DefaultDockerClientConfig.createDefaultConfigBuilder()
                     .withDockerHost(host.toString()).withApiVersion("1.45").build(), transport)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame frame) {
                    output.writeBytes(frame.getPayload());
                }
            };
            client.execStartCmd("test").withTty(tty).exec(callback);
            assertTrue(callback.awaitCompletion(10, TimeUnit.SECONDS));
            return output.toByteArray();
        } finally {
            server.stop(0);
        }
    }
}
