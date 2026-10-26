package io.github.hectorvent.floci.services.lambda.runtime;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class RuntimeInitializationTest {
    private Vertx vertx;
    private RuntimeApiServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        base = "http://127.0.0.1:" + port;
        server = new RuntimeApiServer(vertx, port);
        server.start().get(5, TimeUnit.SECONDS);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop().get(5, TimeUnit.SECONDS);
        client.shutdownNow();
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private CompletableFuture<HttpResponse<String>> next() {
        return client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/2018-06-01/runtime/invocation/next"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String register(String name) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/2020-01-01/extension/register"))
                .header("Lambda-Extension-Name", name).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"events\":[\"INVOKE\",\"SHUTDOWN\"]}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.headers().firstValue("Lambda-Extension-Identifier").orElseThrow();
    }

    private CompletableFuture<HttpResponse<String>> extensionNext(String id) {
        return client.sendAsync(HttpRequest.newBuilder(URI.create(base + "/2020-01-01/extension/event/next"))
                .header("Lambda-Extension-Identifier", id).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void initializationNeedsRuntimeNextAndDoesNotProduceAnInvocation() throws Exception {
        server.expectExtensions(0);
        assertThrows(TimeoutException.class, () -> server.awaitInitialization(20));
        CompletableFuture<HttpResponse<String>> pending = next();
        server.awaitInitialization(2000);
        assertTrue(server.isInitialized());
        assertFalse(pending.isDone(), "No handler event was enqueued by initialization");
    }

    @Test
    void runtimeNextBeforeExtensionDiscoveryCannotCompleteInitialization() throws Exception {
        CompletableFuture<HttpResponse<String>> pending = next();
        await().atMost(Duration.ofSeconds(2)).until(() -> server.waitingContextsSize() == 1);
        assertThrows(TimeoutException.class, () -> server.awaitInitialization(20));
        server.expectExtensions(1);
        String extension = register("example-extension");
        assertThrows(TimeoutException.class, () -> server.awaitInitialization(20));
        extensionNext(extension);
        server.awaitInitialization(2000);
        assertFalse(pending.isDone());
    }

    @Test
    void everyExtensionAndTheRuntimeMustFinishInit() throws Exception {
        server.expectExtensions(2);
        String first = register("first-extension");
        String second = register("second-extension");
        extensionNext(first);
        assertThrows(TimeoutException.class, () -> server.awaitInitialization(20));
        CompletableFuture<HttpResponse<String>> pending = next();
        assertThrows(TimeoutException.class, () -> server.awaitInitialization(20));
        extensionNext(second);
        server.awaitInitialization(2000);
        assertFalse(pending.isDone());
    }

    @Test
    void initErrorFailsTheBarrierWithoutAnInvocation() throws Exception {
        server.expectExtensions(0);
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/2018-06-01/runtime/init/error"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                new JsonObject().put("errorMessage", "controlled import failure").encode())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode());
        assertThrows(ExecutionException.class, () -> server.awaitInitialization(100));
        assertFalse(server.isInitialized());
    }

    @Test
    void processExitFailsTheBarrierAndCannotBeMadeReadyByALateNext() throws Exception {
        server.expectExtensions(0);
        server.handleRuntimeProcessExited(1);
        assertThrows(ExecutionException.class, () -> server.awaitInitialization(100));
        assertEquals(204, next().get(2, TimeUnit.SECONDS).statusCode());
        assertFalse(server.isInitialized());
    }

    @Test
    void stopInvalidatesCompletedReadiness() throws Exception {
        server.expectExtensions(0);
        next();
        server.awaitInitialization(2000);
        server.quiesce();
        assertFalse(server.isInitialized());
        assertThrows(IllegalStateException.class, () -> server.awaitInitialization(100));
    }
}
