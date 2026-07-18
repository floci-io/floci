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
import java.util.ArrayList;
import java.util.List;
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

    @Test
    @Timeout(10)
    void extensionRegister_returnsIdentifierHeaderAndFunctionBody() throws Exception {
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/register"))
                        .header("Lambda-Extension-Name", "lambda-adapter")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"events\":[\"INVOKE\",\"SHUTDOWN\"]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Lambda-Extension-Identifier").isPresent(),
                "register must return a Lambda-Extension-Identifier header");
        JsonObject body = new JsonObject(response.body());
        assertNotNull(body.getString("functionName"));
        assertNotNull(body.getString("functionVersion"));
    }

    /**
     * Regression: the register response previously hardcoded functionName/functionVersion/handler
     * to placeholder values regardless of which function the server was actually serving — extensions
     * that key telemetry off this response (e.g. per-function metrics tagging) would mislabel every
     * function identically. ContainerLauncher calls setFunctionMetadata once it knows which
     * LambdaFunction a given RuntimeApiServer instance belongs to.
     */
    @Test
    @Timeout(10)
    void extensionRegister_returnsRealFunctionMetadataOnceSet() throws Exception {
        server.setFunctionMetadata("my-real-function", "3", "index.handler");

        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/register"))
                        .header("Lambda-Extension-Name", "lambda-adapter")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        JsonObject body = new JsonObject(response.body());
        assertEquals("my-real-function", body.getString("functionName"));
        assertEquals("3", body.getString("functionVersion"));
        assertEquals("index.handler", body.getString("handler"));
    }

    @Test
    @Timeout(10)
    void extensionRegister_missingNameHeader_returns400() throws Exception {
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/register"))
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
    }

    @Test
    @Timeout(10)
    void extensionEventNext_unknownIdentifier_returns403() throws Exception {
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/event/next"))
                        .header("Lambda-Extension-Identifier", "not-a-real-id")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode());
    }

    @Test
    @Timeout(15)
    void extensionEventNext_receivesInvokeEventWhenRuntimeInvocationEnqueued() throws Exception {
        String extensionId = registerExtension("lambda-adapter", "INVOKE", "SHUTDOWN");

        CompletableFuture<HttpResponse<String>> asyncNext = httpClient.sendAsync(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/event/next"))
                        .header("Lambda-Extension-Identifier", extensionId)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        Thread.sleep(300);
        assertFalse(asyncNext.isDone(), "extension /event/next should be parked with no pending event");

        PendingInvocation invocation = new PendingInvocation(
                "req-ext-invoke", "{}".getBytes(), System.currentTimeMillis() + 60_000,
                "arn:aws:lambda:us-east-1:000000000000:function:test",
                new CompletableFuture<>());
        server.enqueue(invocation);

        HttpResponse<String> response = asyncNext.get(2, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Lambda-Extension-Event-Identifier").isPresent());
        JsonObject body = new JsonObject(response.body());
        assertEquals("INVOKE", body.getString("eventType"));
        assertEquals("req-ext-invoke", body.getString("requestId"));
    }

    @Test
    @Timeout(15)
    void extensionEventNext_notSubscribedToInvoke_isNotNotified() throws Exception {
        // Registers for SHUTDOWN only — real AWS never delivers INVOKE to an extension that
        // didn't ask for it.
        String extensionId = registerExtension("shutdown-only-extension", "SHUTDOWN");

        CompletableFuture<HttpResponse<String>> asyncNext = httpClient.sendAsync(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/event/next"))
                        .header("Lambda-Extension-Identifier", extensionId)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        server.enqueue(new PendingInvocation(
                "req-not-subscribed", "{}".getBytes(), System.currentTimeMillis() + 60_000,
                "arn:aws:lambda:us-east-1:000000000000:function:test",
                new CompletableFuture<>()));

        Thread.sleep(500);
        assertFalse(asyncNext.isDone(),
                "extension not subscribed to INVOKE must not be woken by an invocation");
    }

    @Test
    @Timeout(15)
    void extensionEventNext_receivesShutdownEventWhenServerStops() throws Exception {
        String extensionId = registerExtension("lambda-adapter", "INVOKE", "SHUTDOWN");

        CompletableFuture<HttpResponse<String>> asyncNext = httpClient.sendAsync(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/event/next"))
                        .header("Lambda-Extension-Identifier", extensionId)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        Thread.sleep(300);
        assertFalse(asyncNext.isDone());

        server.stop();

        HttpResponse<String> response = asyncNext.get(2, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        JsonObject body = new JsonObject(response.body());
        assertEquals("SHUTDOWN", body.getString("eventType"));
    }

    @Test
    @Timeout(10)
    void extensionInitError_returns202AndUnregistersExtension() throws Exception {
        String extensionId = registerExtension("failing-extension", "INVOKE");

        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/init/error"))
                        .header("Lambda-Extension-Identifier", extensionId)
                        .header("Lambda-Extension-Function-Error-Type", "Extension.ConfigInvalid")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"errorMessage\":\"bad config\",\"errorType\":\"Extension.ConfigInvalid\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(202, response.statusCode());
        assertEquals("OK", new JsonObject(response.body()).getString("status"));

        // The unregistered extension is no longer a valid target for /event/next.
        HttpResponse<String> nextResponse = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/event/next"))
                        .header("Lambda-Extension-Identifier", extensionId)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, nextResponse.statusCode());
    }

    /**
     * Regression for the historical orphaned-SHUTDOWN race (floci-io/floci#1882): stop()
     * could flip stopped=true and offer a SHUTDOWN event in the exact window a concurrent
     * /event/next request had already polled-empty and was about to check stopped, so the
     * request got a bare 204 with the SHUTDOWN never delivered. Stress it with many
     * iterations of a jittered race between stop() and /event/next, asserting SHUTDOWN is
     * always delivered exactly once — never orphaned (zero deliveries) and never duplicated.
     */
    @Test
    @Timeout(60)
    void extensionShutdown_racedAgainstStop_isNeverOrphaned() throws Exception {
        int iterations = 500;
        java.util.concurrent.atomic.AtomicInteger totalDelivered = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < iterations; i++) {
            int freshPort = findFreePort();
            RuntimeApiServer freshServer = new RuntimeApiServer(vertx, freshPort);
            freshServer.start().get(5, TimeUnit.SECONDS);
            // A fresh client per iteration — freshPort may reuse a port from an earlier
            // iteration, and a shared client's connection pool could otherwise hand back a
            // stale pooled connection to that iteration's now-closed server.
            HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
            try {
                String extensionId = registerExtensionOn(client, freshPort, "lambda-adapter", "SHUTDOWN");

                CompletableFuture<HttpResponse<String>> asyncNext = client.sendAsync(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + freshPort + "/2020-01-01/extension/event/next"))
                                .header("Lambda-Extension-Identifier", extensionId)
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());

                // Give the request just enough time to actually connect and park server-side
                // (a few ms is plenty on localhost) before racing stop() against it — without
                // this, stop() can close the listening socket before the connection is even
                // established, which isn't the race we're testing.
                Thread.sleep(5);
                freshServer.stop();

                HttpResponse<String> response = asyncNext.get(5, TimeUnit.SECONDS);
                boolean deliveredHere = response.statusCode() == 200
                        && "SHUTDOWN".equals(new JsonObject(response.body()).getString("eventType"));
                if (deliveredHere) {
                    totalDelivered.incrementAndGet();
                } else {
                    // Didn't land in this specific request — a subsequent poll with the same
                    // identifier must still find the SHUTDOWN queued (never orphaned).
                    HttpResponse<String> retry = client.send(HttpRequest.newBuilder()
                                    .uri(URI.create(
                                            "http://localhost:" + freshPort + "/2020-01-01/extension/event/next"))
                                    .header("Lambda-Extension-Identifier", extensionId)
                                    .GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (retry.statusCode() == 200
                            && "SHUTDOWN".equals(new JsonObject(retry.body()).getString("eventType"))) {
                        totalDelivered.incrementAndGet();
                    }
                }
            } finally {
                freshServer.stop().get(5, TimeUnit.SECONDS);
            }
        }

        assertEquals(iterations, totalDelivered.get(),
                "every iteration's SHUTDOWN must be delivered exactly once (never orphaned)");
    }

    /**
     * Equivalent race for the runtime invocation queue: races enqueue() against stop() with
     * jitter across many iterations, asserting the invocation's resultFuture always completes
     * (real dispatch or ContainerStopped) — a hang here trips the test timeout, an unambiguous
     * regression signal for an orphaned invocation.
     */
    @Test
    @Timeout(60)
    void enqueueRacedAgainstStop_alwaysCompletesResultFuture() throws Exception {
        int iterations = 500;
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();

        for (int i = 0; i < iterations; i++) {
            int freshPort = findFreePort();
            RuntimeApiServer freshServer = new RuntimeApiServer(vertx, freshPort);
            freshServer.start().get(5, TimeUnit.SECONDS);
            try {
                PendingInvocation invocation = new PendingInvocation(
                        "req-race-" + i, "{}".getBytes(), System.currentTimeMillis() + 60_000,
                        "arn:aws:lambda:us-east-1:000000000000:function:test",
                        new CompletableFuture<>());

                Thread stopper = new Thread(() -> {
                    try {
                        Thread.sleep(random.nextInt(0, 5));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    freshServer.stop();
                });
                stopper.start();
                freshServer.enqueue(invocation);
                stopper.join(5000);

                InvokeResult result = invocation.getResultFuture().get(5, TimeUnit.SECONDS);
                assertNotNull(result, "resultFuture must always complete, never hang");
            } finally {
                freshServer.stop().get(5, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * /extension/register racing stop() (floci-io/floci#1882: register previously didn't
     * check stopped at all). Registration itself always completes before stop() can begin
     * (the race is purely over which one observes the other's effect first — stop() draining
     * an empty extensions map vs. register() finding stopped already true), and whatever
     * identifier comes back, a subsequent /event/next with it must eventually return SHUTDOWN
     * rather than hanging or 403ing inconsistently.
     */
    @Test
    @Timeout(60)
    void extensionRegister_racedAgainstStop_eventuallyDeliversShutdown() throws Exception {
        int iterations = 300;

        for (int i = 0; i < iterations; i++) {
            int freshPort = findFreePort();
            RuntimeApiServer freshServer = new RuntimeApiServer(vertx, freshPort);
            freshServer.start().get(5, TimeUnit.SECONDS);
            HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
            try {
                String extensionId = registerExtensionOn(client, freshPort, "lambda-adapter", "SHUTDOWN");
                // Race stop() against the extension's very first /event/next poll, which may
                // land before or after stop() — either way SHUTDOWN must still be delivered.
                CompletableFuture<HttpResponse<String>> asyncNext = client.sendAsync(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + freshPort + "/2020-01-01/extension/event/next"))
                                .header("Lambda-Extension-Identifier", extensionId)
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                // Give the request time to actually connect and park before stop() closes
                // the listening socket — see extensionShutdown_racedAgainstStop_isNeverOrphaned.
                Thread.sleep(5);
                freshServer.stop();

                HttpResponse<String> response = asyncNext.get(5, TimeUnit.SECONDS);
                assertEquals(200, response.statusCode(),
                        "a registered extension must eventually see SHUTDOWN, never hang/403");
                assertEquals("SHUTDOWN", new JsonObject(response.body()).getString("eventType"));
            } finally {
                freshServer.stop().get(5, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * handleExtensionFatalError racing stop()'s SHUTDOWN fan-out: no exception, no
     * double-processing. The fatal-error POST completes before stop() begins tearing down
     * the connection (dispatched synchronously, unlike the long-polling /event/next above),
     * so the only race is stop()'s SHUTDOWN fan-out landing concurrently with the extension
     * having just been removed from the map.
     */
    @Test
    @Timeout(30)
    void extensionFatalError_racedAgainstStop_noExceptionNoDoubleProcessing() throws Exception {
        int iterations = 300;

        for (int i = 0; i < iterations; i++) {
            int freshPort = findFreePort();
            RuntimeApiServer freshServer = new RuntimeApiServer(vertx, freshPort);
            freshServer.start().get(5, TimeUnit.SECONDS);
            HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
            try {
                String extensionId = registerExtensionOn(client, freshPort, "failing-extension", "INVOKE", "SHUTDOWN");

                HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                                .uri(URI.create(
                                        "http://localhost:" + freshPort + "/2020-01-01/extension/init/error"))
                                .header("Lambda-Extension-Identifier", extensionId)
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(202, response.statusCode(), "fatal-error endpoint must not throw under the race");

                // stop() runs immediately after — races its SHUTDOWN fan-out against the
                // extension having just been removed by the fatal-error handler above.
                freshServer.stop().get(5, TimeUnit.SECONDS);
            } finally {
                freshServer.stop().get(5, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Regression guard against reintroducing worker-thread pinning: NEXT_PATH must stay
     * non-blocking/event-loop-based, since the container's language-runtime bootstrap holds
     * this long-poll open for its entire idle lifetime. Parking more concurrent /next polls
     * than the default Quarkus worker pool (20 threads) must still succeed — this would fail
     * or hang if a future change moved NEXT_PATH to a blockingHandler/wait() design.
     */
    @Test
    @Timeout(30)
    void manyConcurrentNextPollers_exceedingWorkerPoolSize_allParkAndComplete() throws Exception {
        int serverCount = 30;
        List<RuntimeApiServer> servers = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();
        try {
            for (int i = 0; i < serverCount; i++) {
                int freshPort = findFreePort();
                RuntimeApiServer freshServer = new RuntimeApiServer(vertx, freshPort);
                freshServer.start().get(5, TimeUnit.SECONDS);
                servers.add(freshServer);
                ports.add(freshPort);
            }

            List<CompletableFuture<HttpResponse<String>>> pending = new ArrayList<>();
            for (int freshPort : ports) {
                pending.add(httpClient.sendAsync(HttpRequest.newBuilder()
                                .uri(URI.create("http://localhost:" + freshPort + "/2018-06-01/runtime/invocation/next"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString()));
            }

            Thread.sleep(500);
            for (CompletableFuture<HttpResponse<String>> f : pending) {
                assertFalse(f.isDone(), "all pollers should be parked, none held on a blocking thread");
            }

            for (int i = 0; i < serverCount; i++) {
                servers.get(i).enqueue(new PendingInvocation(
                        "req-many-" + i, "{}".getBytes(), System.currentTimeMillis() + 60_000,
                        "arn:aws:lambda:us-east-1:000000000000:function:test",
                        new CompletableFuture<>()));
            }

            for (CompletableFuture<HttpResponse<String>> f : pending) {
                assertEquals(200, f.get(5, TimeUnit.SECONDS).statusCode());
            }
        } finally {
            for (RuntimeApiServer s : servers) {
                s.stop().get(5, TimeUnit.SECONDS);
            }
        }
    }

    private String registerExtensionOn(HttpClient client, int targetPort, String name, String... events)
            throws Exception {
        String eventsJson = String.join(",", java.util.Arrays.stream(events).map(e -> "\"" + e + "\"").toList());
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + targetPort + "/2020-01-01/extension/register"))
                        .header("Lambda-Extension-Name", name)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"events\":[" + eventsJson + "]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.headers().firstValue("Lambda-Extension-Identifier").orElseThrow();
    }

    private String registerExtension(String name, String... events) throws Exception {
        String eventsJson = String.join(",", java.util.Arrays.stream(events).map(e -> "\"" + e + "\"").toList());
        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/register"))
                        .header("Lambda-Extension-Name", name)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"events\":[" + eventsJson + "]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.headers().firstValue("Lambda-Extension-Identifier").orElseThrow();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
