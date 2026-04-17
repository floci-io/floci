package io.github.hectorvent.floci.services.lambda.runtime;

import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.PendingInvocation;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void tearDown() {
        server.stop();
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

    @Test
    @Timeout(45)
    void nextEndpoint_doesNotReturn204AfterPollCycle() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/2018-06-01/runtime/invocation/next"))
                .GET()
                .build();

        CompletableFuture<HttpResponse<String>> asyncResponse =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        Thread.sleep(33_000);

        assertFalse(asyncResponse.isDone(),
                "GET /next must NOT return (old bug: 204 after 30s single poll)");

        PendingInvocation invocation = new PendingInvocation(
                "req-delayed", "{\"after_poll_cycle\":true}".getBytes(),
                System.currentTimeMillis() + 60_000,
                "arn:aws:lambda:us-east-1:000000000000:function:test",
                new CompletableFuture<>());
        server.enqueue(invocation);

        HttpResponse<String> response = asyncResponse.get(10, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals("req-delayed",
                response.headers().firstValue("Lambda-Runtime-Aws-Request-Id").orElse(""));
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

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
