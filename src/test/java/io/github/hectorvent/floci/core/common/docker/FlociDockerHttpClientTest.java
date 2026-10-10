package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.transport.DockerHttpClient;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // Catches: on a unix:// host, a call to a silent daemon blocking forever because the socket ignored
    // the response timeout, and a long-lived stream (a container wait) cut off by that timeout once it
    // applies.
    @Test
    @Timeout(60)
    void overAUnixSocketOrdinaryCallsTimeOutWhileLongLivedStreamsStayOpen(@TempDir Path dir) throws Exception {
        Path socketPath = dir.resolve("docker.sock");
        List<SocketChannel> accepted = new CopyOnWriteArrayList<>();
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socketPath));
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        accepted.add(server.accept());
                    }
                } catch (IOException expected) {
                    // The server channel closed at the end of the test.
                }
            });
            try (FlociDockerHttpClient client = new FlociDockerHttpClient.Builder()
                    .dockerHost(URI.create("unix://" + socketPath))
                    .responseTimeout(Duration.ofSeconds(1))
                    .build()) {
                long start = System.nanoTime();
                RuntimeException failure = assertThrows(RuntimeException.class,
                        () -> client.execute(get("/containers/abc/json")));
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertTrue(failure.getCause() instanceof SocketTimeoutException,
                        "an ordinary call should time out, got " + failure.getCause());
                assertTrue(elapsedMillis < 10_000, "timed out after " + elapsedMillis + " ms");

                CompletableFuture<Integer> wait = CompletableFuture.supplyAsync(() -> {
                    try (DockerHttpClient.Response response = client.execute(DockerHttpClient.Request.builder()
                            .method(DockerHttpClient.Request.Method.POST).path("/containers/abc/wait").build())) {
                        return response.getStatusCode();
                    }
                });
                assertThrows(TimeoutException.class, () -> wait.get(2_500, TimeUnit.MILLISECONDS),
                        "a container wait must outlive the response timeout");

                SocketChannel waitConnection = accepted.get(accepted.size() - 1);
                waitConnection.write(ByteBuffer.wrap(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                        + "Content-Length: 15\r\n\r\n{\"StatusCode\":0}").getBytes(StandardCharsets.UTF_8)));
                assertEquals(200, wait.get(10, TimeUnit.SECONDS));
            } finally {
                for (SocketChannel channel : accepted) {
                    channel.close();
                }
                acceptor.interrupt();
            }
        }
    }

    // Catches: a call that opens a new connection every time (as Connection: close forced) instead of
    // reusing the pooled one.
    @Test
    @Timeout(60)
    void consecutiveCallsReuseOnePooledConnection(@TempDir Path dir) throws Exception {
        try (KeepAliveServer server = new KeepAliveServer(dir.resolve("docker.sock"), false);
             FlociDockerHttpClient client = new FlociDockerHttpClient.Builder()
                     .dockerHost(URI.create("unix://" + server.path))
                     .responseTimeout(Duration.ofSeconds(5))
                     .build()) {
            for (int i = 0; i < 3; i++) {
                try (DockerHttpClient.Response response = client.execute(get("/_ping"))) {
                    assertEquals("ok", new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            assertEquals(1, server.connections.get(), "three calls should share one connection");
        }
    }

    // Catches: leasing a pooled connection the daemon closed while it sat idle, which then fails on
    // its next write; the validation after a second of inactivity must replace it.
    @Test
    @Timeout(60)
    void aConnectionTheDaemonClosedWhileIdleIsReplacedBeforeReuse(@TempDir Path dir) throws Exception {
        try (KeepAliveServer server = new KeepAliveServer(dir.resolve("docker.sock"), true);
             FlociDockerHttpClient client = new FlociDockerHttpClient.Builder()
                     .dockerHost(URI.create("unix://" + server.path))
                     .responseTimeout(Duration.ofSeconds(5))
                     .build()) {
            try (DockerHttpClient.Response response = client.execute(get("/_ping"))) {
                response.getBody().readAllBytes();
            }
            Thread.sleep(1_500);
            // A POST, which httpclient5 never re-sends by itself, so only the validation can save it.
            try (DockerHttpClient.Response response = client.execute(DockerHttpClient.Request.builder()
                    .method(DockerHttpClient.Request.Method.POST).path("/containers/abc/stop").build())) {
                assertEquals("ok", new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            assertEquals(2, server.connections.get(), "the closed connection should have been replaced");
        }
    }

    /** Answers every request with a keep-alive 200 "ok"; optionally closes each connection after one answer. */
    private static final class KeepAliveServer implements AutoCloseable {

        private final Path path;
        private final ServerSocketChannel server;
        private final AtomicInteger connections = new AtomicInteger();
        private final List<SocketChannel> channels = new CopyOnWriteArrayList<>();

        KeepAliveServer(Path path, boolean closeAfterOneAnswer) throws IOException {
            this.path = path;
            this.server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(path));
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        SocketChannel channel = server.accept();
                        connections.incrementAndGet();
                        channels.add(channel);
                        Thread.ofVirtual().start(() -> serve(channel, closeAfterOneAnswer));
                    }
                } catch (IOException expected) {
                    // The server channel closed at the end of the test.
                }
            });
        }

        private static void serve(SocketChannel channel, boolean closeAfterOneAnswer) {
            try {
                ByteBuffer buffer = ByteBuffer.allocate(4096);
                StringBuilder pending = new StringBuilder();
                while (channel.read(buffer) != -1) {
                    buffer.flip();
                    pending.append(StandardCharsets.UTF_8.decode(buffer));
                    buffer.clear();
                    int end;
                    while ((end = pending.indexOf("\r\n\r\n")) >= 0) {
                        pending.delete(0, end + 4);
                        channel.write(ByteBuffer.wrap(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
                                .getBytes(StandardCharsets.UTF_8)));
                        if (closeAfterOneAnswer) {
                            channel.close();
                            return;
                        }
                    }
                }
            } catch (IOException expected) {
                // The client or the test closed the connection.
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (SocketChannel channel : channels) {
                channel.close();
            }
        }
    }

    @Test
    void streamingEndpointsAreRecognisedAndOrdinaryCallsAreNot() {
        for (String streaming : List.of("POST /v1.43/containers/abc/wait", "POST /exec/xyz/start",
                "POST /containers/abc/attach?stream=1", "GET /events?since=1", "GET /containers/abc/logs?follow=true",
                "GET /containers/abc/stats", "POST /images/create?fromImage=alpine", "POST /build")) {
            assertTrue(FlociDockerHttpClient.isLongLivedStream(request(streaming)), streaming);
        }
        for (String ordinary : List.of("GET /containers/abc/json", "GET /containers/abc/logs?tail=100",
                "GET /containers/abc/stats?stream=0", "POST /containers/abc/start", "POST /containers/create",
                "DELETE /containers/abc", "GET /_ping")) {
            assertFalse(FlociDockerHttpClient.isLongLivedStream(request(ordinary)), ordinary);
        }
    }

    private static DockerHttpClient.Request request(String methodAndPath) {
        String[] parts = methodAndPath.split(" ", 2);
        return DockerHttpClient.Request.builder().method(parts[0]).path(parts[1]).build();
    }
}
