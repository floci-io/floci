package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.transport.DockerHttpClient;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlociDockerHttpClientTest {

    // Catches: a full pool making a call wait out httpclient5's three-minute default instead of
    // failing after the configured lease timeout.
    @Test
    void aCallThatCannotLeaseAConnectionFailsAfterTheLeaseTimeout() throws Exception {
        List<Socket> accepted = new CopyOnWriteArrayList<>();
        CountDownLatch firstConnected = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        accepted.add(server.accept());
                        firstConnected.countDown();
                    }
                } catch (IOException expected) {
                    // The server socket closed at the end of the test.
                }
            });
            try (FlociDockerHttpClient client = new FlociDockerHttpClient.Builder()
                    .dockerHost(URI.create("tcp://127.0.0.1:" + server.getLocalPort()))
                    .maxConnections(1)
                    .responseTimeout(Duration.ofSeconds(30))
                    .connectionRequestTimeout(Duration.ofSeconds(1))
                    .build()) {
                // The one pooled connection is held by a request the server never answers.
                Thread.ofVirtual().start(() -> {
                    try (DockerHttpClient.Response ignored = client.execute(get("/containers/held/wait"))) {
                        // Never reached: the server does not answer before the test ends.
                    } catch (RuntimeException expected) {
                        // The client closed when the test ended.
                    }
                });
                assertTrue(firstConnected.await(5, TimeUnit.SECONDS), "the first request should connect");

                long start = System.nanoTime();
                RuntimeException failure = assertThrows(RuntimeException.class,
                        () -> client.execute(get("/_ping")));
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

                assertTrue(failure.getCause() instanceof ConnectionRequestTimeoutException,
                        "a full pool should fail with ConnectionRequestTimeoutException, got " + failure.getCause());
                assertTrue(elapsedMillis < 10_000,
                        "should fail near the 1 s lease timeout, took " + elapsedMillis + " ms");
            } finally {
                for (Socket socket : accepted) {
                    socket.close();
                }
                acceptor.interrupt();
            }
        }
    }

    private static DockerHttpClient.Request get(String path) {
        return DockerHttpClient.Request.builder().method(DockerHttpClient.Request.Method.GET).path(path).build();
    }
}
