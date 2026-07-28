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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        httpClient.close();
        // Await the close rather than firing it and moving on: Vertx.close() is asynchronous, so
        // an unawaited call lets the next test's setUp() create a new Vertx and bind a port while
        // this one's event loops and server sockets are still tearing down. Across a class with
        // stress tests that stand up hundreds of servers, that backlog surfaces as BindException
        // or a start() timeout in an unrelated test's setUp().
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
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
        server.setFunctionMetadata("my-real-function", "3", "index.handler", "123456789012");

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

    /**
     * AWS includes {@code accountId} in the register response when the extension opts in via
     * {@code Lambda-Extension-Accept-Feature: accountId}. Without it a telemetry extension
     * registers successfully but silently loses account attribution.
     */
    @Test
    @Timeout(10)
    void extensionRegister_withAccountIdFeature_includesAccountId() throws Exception {
        server.setFunctionMetadata("my-fn", "$LATEST", "index.handler", "210987654321");

        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/register"))
                        .header("Lambda-Extension-Name", "telemetry-extension")
                        .header("Lambda-Extension-Accept-Feature", "accountId")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("210987654321", new JsonObject(response.body()).getString("accountId"));
    }

    /**
     * The feature is opt-in: an extension that does not request it must get the unchanged response
     * shape, since real AWS omits the field entirely rather than always sending it.
     */
    @Test
    @Timeout(10)
    void extensionRegister_withoutAccountIdFeature_omitsAccountId() throws Exception {
        server.setFunctionMetadata("my-fn", "$LATEST", "index.handler", "210987654321");

        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/register"))
                        .header("Lambda-Extension-Name", "plain-extension")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertNull(new JsonObject(response.body()).getString("accountId"),
                "accountId must only appear when the extension opts in");
    }

    /**
     * The header carries a comma-separated feature list, so accountId must still be honoured
     * alongside other requested features and regardless of casing/spacing.
     */
    @Test
    @Timeout(10)
    void extensionRegister_accountIdFeatureAmongOthers_isHonoured() throws Exception {
        server.setFunctionMetadata("my-fn", "$LATEST", "index.handler", "210987654321");

        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/register"))
                        .header("Lambda-Extension-Name", "telemetry-extension")
                        .header("Lambda-Extension-Accept-Feature", "someOtherFeature, AccountID")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("210987654321", new JsonObject(response.body()).getString("accountId"));
    }

    /** A function with no account falls back to the same placeholder used elsewhere in Floci. */
    @Test
    @Timeout(10)
    void extensionRegister_withNoFunctionAccountId_fallsBackToPlaceholder() throws Exception {
        server.setFunctionMetadata("my-fn", "$LATEST", "index.handler", null);

        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/register"))
                        .header("Lambda-Extension-Name", "telemetry-extension")
                        .header("Lambda-Extension-Accept-Feature", "accountId")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("000000000000", new JsonObject(response.body()).getString("accountId"));
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

        // Both init/error and exit/error are fatal to the execution environment in real AWS, so
        // the server is marked faulted for WarmPool to retire the container rather than reuse it.
        assertTrue(server.isFaulted(), "init/error must condemn the execution environment");
    }

    @Test
    @Timeout(30)
    void extensionExitError_condemnsExecutionEnvironment() throws Exception {
        String extensionId = registerExtension("failing-extension", "INVOKE");
        assertFalse(server.isFaulted(), "environment must not be faulted before any error is reported");

        HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/exit/error"))
                        .header("Lambda-Extension-Identifier", extensionId)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"errorMessage\":\"crashed\",\"errorType\":\"Extension.Crash\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(202, response.statusCode());
        assertTrue(server.isFaulted(), "exit/error must condemn the execution environment");
    }

    /**
     * The init-readiness barrier: awaitExtensionsRegistered() must block until every expected
     * extension has called /extension/register.
     */
    @Test
    @Timeout(30)
    void awaitExtensionsRegistered_blocksUntilAllExpectedExtensionsRegister() throws Exception {
        server.expectExtensions(2);

        CompletableFuture<Boolean> awaited = CompletableFuture.supplyAsync(() -> {
            try {
                return server.awaitExtensionsRegistered(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });

        registerExtension("first-extension", "INVOKE");
        Thread.sleep(200);
        assertFalse(awaited.isDone(), "must still be waiting while one extension has not registered");

        registerExtension("second-extension", "INVOKE");
        assertTrue(awaited.get(5, TimeUnit.SECONDS), "must unblock once all extensions registered");
    }

    /**
     * The missed-signal case: registration completing *before* anyone waits must not strand the
     * launch. A CountDownLatch is level-triggered, so a late await returns immediately — this
     * pins that behaviour, since an edge-triggered signal here would hang the cold start.
     */
    @Test
    @Timeout(30)
    void awaitExtensionsRegistered_returnsImmediatelyWhenRegistrationAlreadyCompleted() throws Exception {
        server.expectExtensions(1);
        registerExtension("fast-extension", "INVOKE");

        long start = System.currentTimeMillis();
        assertTrue(server.awaitExtensionsRegistered(10_000));
        assertTrue(System.currentTimeMillis() - start < 1000,
                "an await arriving after registration must not wait");
    }

    /** A function with no extensions must not wait at all. */
    @Test
    @Timeout(30)
    void awaitExtensionsRegistered_withNoExpectedExtensions_doesNotBlock() throws Exception {
        server.expectExtensions(0);

        long start = System.currentTimeMillis();
        assertTrue(server.awaitExtensionsRegistered(10_000));
        assertTrue(System.currentTimeMillis() - start < 1000, "zero extensions must not wait");
    }

    /** A never-registering extension must time out rather than block forever. */
    @Test
    @Timeout(30)
    void awaitExtensionsRegistered_timesOutWhenExtensionNeverRegisters() throws Exception {
        server.expectExtensions(1);

        assertFalse(server.awaitExtensionsRegistered(300),
                "a missing registration must report timeout, not hang");
    }

    /**
     * The behaviour abanna reported on PR #1773: "a later invoke can still be served after
     * init/error". A *new* invocation enqueued after the environment was condemned must not be
     * dispatched to the runtime — serving it would hide a failed adapter or security extension.
     * This is distinct from the in-flight invocation covered below, which must still complete.
     */
    @Test
    @Timeout(30)
    void invokeEnqueuedAfterExtensionFatalError_isNotServed() throws Exception {
        String extensionId = registerExtension("failing-extension", "INVOKE");

        httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/init/error"))
                        .header("Lambda-Extension-Identifier", extensionId)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"errorMessage\":\"bad config\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(server.isFaulted());

        // A brand-new invocation arrives at the condemned environment.
        PendingInvocation later = new PendingInvocation(
                "req-after-fault", "{}".getBytes(), System.currentTimeMillis() + 60_000,
                "arn:aws:lambda:us-east-1:000000000000:function:test",
                new CompletableFuture<>());
        CompletableFuture<InvokeResult> laterFuture = server.enqueue(later);

        // It must be rejected outright rather than handed to the runtime's /next poller.
        InvokeResult result = laterFuture.get(5, TimeUnit.SECONDS);
        assertEquals("Unhandled", result.getFunctionError(),
                "an invoke enqueued after a fatal extension error must not be served");

        HttpResponse<String> next = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2018-06-01/runtime/invocation/next"))
                        .timeout(java.time.Duration.ofSeconds(5))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(204, next.statusCode(),
                "the runtime must not receive work from a condemned environment");
    }

    /**
     * The in-flight invocation must still complete normally: real AWS condemns the environment for
     * *future* work, and tearing the runtime down mid-invoke would lose a result that did compute.
     */
    @Test
    @Timeout(30)
    void extensionFatalError_doesNotBreakInFlightInvocation() throws Exception {
        String extensionId = registerExtension("failing-extension", "INVOKE");

        PendingInvocation invocation = new PendingInvocation(
                "req-fatal", "{\"hello\":\"world\"}".getBytes(), System.currentTimeMillis() + 60_000,
                "arn:aws:lambda:us-east-1:000000000000:function:test",
                new CompletableFuture<>());
        CompletableFuture<InvokeResult> resultFuture = server.enqueue(invocation);

        // Runtime picks the invocation up, then the extension dies mid-invoke.
        HttpResponse<String> next = httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2018-06-01/runtime/invocation/next"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, next.statusCode());

        httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/2020-01-01/extension/exit/error"))
                        .header("Lambda-Extension-Identifier", extensionId)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"errorMessage\":\"crashed\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(server.isFaulted());

        // The runtime's own response still lands and completes the invocation successfully.
        httpClient.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port
                                + "/2018-06-01/runtime/invocation/req-fatal/response"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"ok\":true}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        InvokeResult result = resultFuture.get(10, TimeUnit.SECONDS);
        assertEquals(200, result.getStatusCode());
        assertNull(result.getFunctionError(), "in-flight invocation must not be failed by the extension fault");
        assertEquals("{\"ok\":true}", new String(result.getPayload()));
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
            // A fresh client per iteration — the port may be reused from an earlier iteration, and
            // a shared client's connection pool could otherwise hand back a stale pooled
            // connection to that iteration's now-closed server.
            HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
            int freshPort = findFreePort();
            RuntimeApiServer freshServer = new RuntimeApiServer(vertx, freshPort);
            freshServer.start().get(5, TimeUnit.SECONDS);
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
                // Each HttpClient owns a selector thread and connection pool; leaking one per
                // iteration across hundreds of iterations starves the JVM and makes even an
                // unrelated server's bind time out.
                client.close();
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
            HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
            int freshPort = findFreePort();
            RuntimeApiServer freshServer = new RuntimeApiServer(vertx, freshPort);
            freshServer.start().get(5, TimeUnit.SECONDS);
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
                client.close();
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
            HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
            int freshPort = findFreePort();
            RuntimeApiServer freshServer = new RuntimeApiServer(vertx, freshPort);
            freshServer.start().get(5, TimeUnit.SECONDS);
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
                client.close();
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

    /**
     * Lowest port used by {@link #findFreePort()}. Deliberately below the OS ephemeral range
     * (49152-65535 on macOS, 32768-60999 on typical Linux) — see {@link #findFreePort()}.
     */
    private static final int TEST_PORT_BASE = 20000;
    /**
     * Wide enough that the rotation does not wrap back onto a port still in TIME_WAIT: one run of
     * this class performs ~1600 port draws, and a closed listener's port stays unusable for tens of
     * seconds afterwards. Running several test classes in the same JVM multiplies that count, which
     * is when a narrower window started producing sporadic connect failures.
     */
    private static final int TEST_PORT_RANGE = 25000;

    /** Rotates across the range so consecutive calls don't retry a port still in TIME_WAIT. */
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_PORT_OFFSET =
            new java.util.concurrent.atomic.AtomicInteger(
                    java.util.concurrent.ThreadLocalRandom.current().nextInt(TEST_PORT_RANGE));

    /**
     * Returns a port that is free <em>and</em> outside the OS ephemeral range.
     *
     * <p>Binding port 0 (the obvious implementation) draws from the ephemeral range, which is
     * exactly where the OS and background applications allocate their own listeners. Long-running
     * desktop agents routinely hold stable ports in that range — on the machine this was diagnosed
     * on, {@code LogiPlugin} (49200-49204), {@code rapportd} (56698, 63091-63092) and
     * {@code CommCenter} (65000-65001) all sit inside it. The stress tests below perform well over
     * a thousand port draws per run, so a collision is near-certain: the probe socket closes, the
     * other process (or the OS) claims the port before Vert.x binds it, and the test's request is
     * answered by a stranger — observed as a spurious {@code 501} from an unrelated HTTP daemon, or
     * as a bind failure/start() timeout. Neither has anything to do with the concurrency under test.
     *
     * <p>Ports 20000-29999 sit below every common ephemeral range, so nothing else on the host is
     * handing them out. The bind check still guards against a genuinely occupied port.
     */
    private static int findFreePort() throws IOException {
        for (int attempt = 0; attempt < TEST_PORT_RANGE; attempt++) {
            int candidate = TEST_PORT_BASE
                    + Math.floorMod(NEXT_PORT_OFFSET.getAndIncrement(), TEST_PORT_RANGE);
            try (ServerSocket socket = new ServerSocket()) {
                // Without SO_REUSEADDR the probe leaves the port in TIME_WAIT, which would block
                // the server that is about to bind it for real.
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("0.0.0.0", candidate));
                return candidate;
            } catch (IOException inUse) {
                // Occupied — try the next port in the rotation.
            }
        }
        throw new IOException("no free port in " + TEST_PORT_BASE + "-" + (TEST_PORT_BASE + TEST_PORT_RANGE));
    }
}
