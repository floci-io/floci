package io.github.hectorvent.floci.services.lambda.runtime;

import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.PendingInvocation;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeApiServerTest {

    private Vertx vertx;
    private RuntimeApiServer server;
    private int port;
    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        port = findFreePort();
        server = new RuntimeApiServer(vertx, port);
        server.start().get(5, TimeUnit.SECONDS);
        httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
        scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop().get(5, TimeUnit.SECONDS);
        scheduler.shutdownNow();
        vertx.close();
    }

    @Test
    @Timeout(15)
    void nextEndpoint_blocksUntilInvocationArrives() throws Exception {
        PendingInvocation invocation = new PendingInvocation(
                "req-1", "{\"key\":\"value\"}".getBytes(), System.currentTimeMillis() + 60_000,
                "arn:aws:lambda:us-east-1:000000000000:function:test",
                new CompletableFuture<>());

        scheduler.schedule(() -> server.enqueue(invocation), 2, TimeUnit.SECONDS);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/2018-06-01/runtime/invocation/next"))
                .GET()
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(200, response.statusCode());
        assertTrue(elapsed >= 1500, "should have blocked ~2s waiting for invocation");
        assertEquals("req-1", response.headers().firstValue("Lambda-Runtime-Aws-Request-Id").orElse(""));
        assertTrue(response.body().contains("key"));
    }

    /**
     * Regression: an Invoke with no body (e.g. {@code aws lambda invoke} without
     * {@code --payload}) reaches the /next handler as a {@code byte[0]}, not
     * {@code null}. The server must still write a valid JSON body ({@code {}})
     * so the managed Node.js runtime's {@code JSON.parse(event)} doesn't throw
     * "Unexpected end of JSON input" before the handler runs.
     */
    @Test
    @Timeout(15)
    void nextEndpoint_emptyPayload_isDeliveredAsEmptyJsonObject() throws Exception {
        PendingInvocation invocation = new PendingInvocation(
                "req-empty", new byte[0], System.currentTimeMillis() + 60_000,
                "arn:aws:lambda:us-east-1:000000000000:function:test",
                new CompletableFuture<>());
        server.enqueue(invocation);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/2018-06-01/runtime/invocation/next"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("req-empty",
                response.headers().firstValue("Lambda-Runtime-Aws-Request-Id").orElse(""));
        assertEquals("{}", response.body(),
                "empty Invoke payload must be normalised to '{}' so JSON.parse() in the runtime succeeds");
    }

    @Test
    @Timeout(10)
    void nextEndpoint_parksWithNoResponse_thenReturns200WhenInvocationEnqueued() throws Exception {
        // AWS Runtime API spec: GET /next must park (no response) until an invocation
        // arrives — it must never return 204 during normal operation.
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/2018-06-01/runtime/invocation/next"))
                .GET()
                .build();
        CompletableFuture<HttpResponse<String>> asyncResponse =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        Thread.sleep(300);
        assertFalse(asyncResponse.isDone(), "GET /next should be parked, not returned");

        PendingInvocation invocation = new PendingInvocation(
                "req-parked", "{\"reactive\":true}".getBytes(),
                System.currentTimeMillis() + 60_000,
                "arn:aws:lambda:us-east-1:000000000000:function:test",
                new CompletableFuture<>());
        server.enqueue(invocation);

        HttpResponse<String> response = asyncResponse.get(2, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode(), "GET /next must return 200 when invocation arrives");
        assertEquals("req-parked", response.headers().firstValue("Lambda-Runtime-Aws-Request-Id").orElse(""));
    }

    /**
     * The /error endpoint must return HTTP 202 with a {@code {"status":"OK"}} body, not
     * an empty body. The AWS .NET runtime client (Amazon.Lambda.RuntimeSupport)
     * deserializes the acknowledgement and crashes the runtime process with "Could not
     * deserialize the response body" when it is empty.
     */
    @Test
    @Timeout(15)
    void errorEndpoint_returns202WithStatusOkBody() throws Exception {
        PendingInvocation invocation = new PendingInvocation(
                "req-error", "{}".getBytes(), System.currentTimeMillis() + 60_000,
                "arn:aws:lambda:us-east-1:000000000000:function:test",
                new CompletableFuture<>());
        server.enqueue(invocation);

        // Deliver the invocation to a /next poller so it moves to inFlight.
        httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2018-06-01/runtime/invocation/next"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port
                                + "/2018-06-01/runtime/invocation/req-error/error"))
                        .header("Lambda-Runtime-Function-Error-Type", "Function.Handled")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"errorMessage\":\"intentional failure\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(202, response.statusCode());
        assertEquals("application/json",
                response.headers().firstValue("Content-Type").orElse(""));
        assertEquals("OK", new JsonObject(response.body()).getString("status"),
                "/error must return a JSON ack body so the .NET runtime client can deserialize it");
    }

    /**
     * The /response acknowledgement carries the same {@code {"status":"OK"}} body as
     * /error so runtime clients that deserialize it succeed.
     */
    @Test
    @Timeout(15)
    void responseEndpoint_returns202WithStatusOkBody() throws Exception {
        PendingInvocation invocation = new PendingInvocation(
                "req-response", "{}".getBytes(), System.currentTimeMillis() + 60_000,
                "arn:aws:lambda:us-east-1:000000000000:function:test",
                new CompletableFuture<>());
        server.enqueue(invocation);

        httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2018-06-01/runtime/invocation/next"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port
                                + "/2018-06-01/runtime/invocation/req-response/response"))
                        .POST(HttpRequest.BodyPublishers.ofString("\"result\""))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(202, response.statusCode());
        assertEquals("application/json",
                response.headers().firstValue("Content-Type").orElse(""));
        assertEquals("OK", new JsonObject(response.body()).getString("status"));
    }

    @Test
    @Timeout(15)
    void stopCompletesInFlightWithContainerStopped() throws Exception {
        PendingInvocation invocation = new PendingInvocation(
                "req-stop", "{}".getBytes(), System.currentTimeMillis() + 60_000,
                "arn:aws:lambda:us-east-1:000000000000:function:test",
                new CompletableFuture<>());

        // Enqueue and have a GET request pick it up (moving it to inFlight)
        server.enqueue(invocation);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/2018-06-01/runtime/invocation/next"))
                .GET()
                .build();
        HttpResponse<String> getResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getResponse.statusCode());

        // Invocation is now in-flight (RIC got it but hasn't POSTed /response yet).
        // Stopping the server should complete the future with ContainerStopped.
        server.stop();

        InvokeResult result = invocation.getResultFuture().get(5, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals("Unhandled", result.getFunctionError());
        String payload = new String(result.getPayload());
        assertTrue(payload.contains("ContainerStopped"));
    }

    @Test
    @Timeout(15)
    void stopWakesParkedPollerImmediately() throws Exception {
        // GET /next on a background thread — parks in waitingContexts (no thread held).
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/2018-06-01/runtime/invocation/next"))
                .GET()
                .build();
        CompletableFuture<HttpResponse<String>> asyncResponse =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        // Give the handler time to park
        Thread.sleep(500);
        assertFalse(asyncResponse.isDone(), "handler should be parked");

        long start = System.currentTimeMillis();
        server.stop();
        HttpResponse<String> response = asyncResponse.get(2, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;

        // 204 is only valid on shutdown — the container is being terminated.
        assertEquals(204, response.statusCode());
        assertTrue(elapsed < 1000, "stop() should wake parked poller in <1s, took " + elapsed + "ms");
    }

    @Test
    @Timeout(15)
    void stopCompletesQueuedInvocationsWithContainerStopped() throws Exception {
        // Enqueue an invocation, but never call /next — it sits in pendingQueue.
        PendingInvocation invocation = new PendingInvocation(
                "req-queued", "{}".getBytes(), System.currentTimeMillis() + 60_000,
                "arn:aws:lambda:us-east-1:000000000000:function:test",
                new CompletableFuture<>());
        server.enqueue(invocation);

        // stop() must drain the queue and complete the future — not discard it silently.
        server.stop();

        InvokeResult result = invocation.getResultFuture().get(2, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals("Unhandled", result.getFunctionError());
        assertTrue(new String(result.getPayload()).contains("ContainerStopped"));
    }

    @Test
    @Timeout(15)
    void enqueueAfterStopCompletesImmediately() throws Exception {
        server.stop();

        PendingInvocation invocation = new PendingInvocation(
                "req-late", "{}".getBytes(), System.currentTimeMillis() + 60_000,
                "arn:aws:lambda:us-east-1:000000000000:function:test",
                new CompletableFuture<>());
        server.enqueue(invocation);

        // Future is completed synchronously by enqueue() when stopped, so no /next is needed.
        assertTrue(invocation.getResultFuture().isDone(), "future should be already done");
        InvokeResult result = invocation.getResultFuture().get(0, TimeUnit.SECONDS);
        assertEquals("Unhandled", result.getFunctionError());
        assertTrue(new String(result.getPayload()).contains("ContainerStopped"));
    }

    @Test
    @Timeout(10)
    void stopReleasesPortSynchronously() throws Exception {
        server.stop().get(5, TimeUnit.SECONDS);
        boolean bound = false;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(port));
                bound = true;
                break;
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        assertTrue(bound, "Should be able to bind to the port after stop()");
    }

    @Test
    @Timeout(10)
    void newServerOnSamePortAcceptsTrafficAfterStop() throws Exception {
        server.stop().get(5, TimeUnit.SECONDS);

        // Try to start a new server, retrying if it fails to bind due to temporary port conflicts
        boolean started = false;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try {
                server = new RuntimeApiServer(vertx, port);
                server.start().get(5, TimeUnit.SECONDS);
                started = true;
                break;
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        assertTrue(started, "New server should start successfully on the same port");

        HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/x")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, resp.statusCode());
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
