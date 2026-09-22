package io.github.hectorvent.floci.services.apigatewayv2;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;

/**
 * Shared test utilities for WebSocket integration tests.
 * Eliminates duplication of MessageCapture, MultiMessageCapture, createLambdaZip,
 * and WebSocket connection helpers across multiple test classes.
 */
public final class WebSocketTestSupport {

    private WebSocketTestSupport() {}

    // ──────────────────────────── Lambda ZIP creation ────────────────────────────

    /**
     * Creates a Base64-encoded ZIP file containing a single index.js with the given handler code.
     * Used to create Lambda function deployment packages in tests.
     */
    public static String createLambdaZip(String handlerCode) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("index.js"));
            zos.write(handlerCode.getBytes());
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    // ──────────────────────────── Lambda cleanup ────────────────────────────

    /**
     * Deletes the given Lambda functions concurrently. Deleting a function that has a warm
     * container stops that container, which takes about a second, so deleting several one after
     * another dominates a test class's cleanup.
     */
    public static void deleteFunctions(String... functionNames) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(functionNames.length);
        try {
            List<Future<?>> deletions = new ArrayList<>();
            for (String functionName : functionNames) {
                deletions.add(executor.submit(() -> given().when().delete("/2015-03-31/functions/" + functionName)));
            }
            for (Future<?> deletion : deletions) {
                deletion.get();
            }
        } finally {
            executor.shutdown();
        }
    }

    // ──────────────────────────── WebSocket URL construction ────────────────────────────

    /**
     * Builds a WebSocket URL for connecting to a Floci WebSocket API.
     *
     * @param baseUri the base HTTP URI of the test server (e.g. http://localhost:8081/)
     * @param apiId   the API Gateway API ID
     * @param stageName the stage name
     * @return the WebSocket URL (e.g. ws://localhost:8081/ws/{apiId}/{stageName})
     */
    public static String buildWsUrl(URI baseUri, String apiId, String stageName) {
        String wsUrl = baseUri.toString().replaceFirst("^http", "ws") + "ws/" + apiId + "/" + stageName;
        return wsUrl.replace("//ws/", "/ws/");
    }

    // ──────────────────────────── WebSocket connection helpers ────────────────────────────

    /**
     * Connect a WebSocket with a MessageCapture listener.
     */
    public static WebSocket connectWebSocket(URI baseUri, String apiId, String stageName,
                                             MessageCapture capture) throws Exception {
        String wsUrl = buildWsUrl(baseUri, apiId, stageName);
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), capture)
                .get(60, TimeUnit.SECONDS);
    }

    /**
     * Connect a WebSocket with a MultiMessageCapture listener.
     */
    public static WebSocket connectWebSocket(URI baseUri, String apiId, String stageName,
                                             MultiMessageCapture capture) throws Exception {
        String wsUrl = buildWsUrl(baseUri, apiId, stageName);
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), capture)
                .get(60, TimeUnit.SECONDS);
    }

    /**
     * Connect a WebSocket with a custom header (e.g. for authorizer identity source testing).
     */
    public static WebSocket connectWebSocketWithHeader(URI baseUri, String apiId, String stageName,
                                                       String headerName, String headerValue,
                                                       WebSocket.Listener listener) throws Exception {
        String wsUrl = buildWsUrl(baseUri, apiId, stageName);
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .header(headerName, headerValue)
                .buildAsync(URI.create(wsUrl), listener)
                .get(60, TimeUnit.SECONDS);
    }

    /**
     * Connect a WebSocket with a query string appended to the URL.
     */
    public static WebSocket connectWebSocketWithQuery(URI baseUri, String apiId, String stageName,
                                                      String queryString, WebSocket.Listener listener) throws Exception {
        String wsUrl = buildWsUrl(baseUri, apiId, stageName) + "?" + queryString;
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), listener)
                .get(60, TimeUnit.SECONDS);
    }

    // ──────────────────────────── Message Capture Listeners ────────────────────────────

    /**
     * WebSocket listener that captures the first text message received.
     * Use when you only need one response from the server.
     */
    public static class MessageCapture implements WebSocket.Listener {
        private final CompletableFuture<String> future = new CompletableFuture<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(10);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                future.complete(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            future.completeExceptionally(error);
        }

        public String getResponse(long timeout, TimeUnit unit) throws Exception {
            return future.get(timeout, unit);
        }
    }

    /**
     * WebSocket listener that captures multiple text messages in a queue.
     * Use when you need to receive multiple responses or push messages.
     * Also supports close detection via setCloseFuture().
     */
    public static class MultiMessageCapture implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();
        private volatile CompletableFuture<Integer> closeFuture;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(10);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                messages.offer(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (closeFuture != null) {
                closeFuture.complete(statusCode);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (closeFuture != null) {
                closeFuture.completeExceptionally(error);
            }
        }

        /**
         * Set a future that will be completed when the WebSocket is closed.
         * Useful for testing server-initiated disconnections.
         */
        public void setCloseFuture(CompletableFuture<Integer> future) {
            this.closeFuture = future;
        }

        /**
         * Offer a message externally (e.g. from a custom close listener adapter).
         */
        public void complete(String message) {
            messages.offer(message);
        }

        public String getNextMessage(long timeout, TimeUnit unit) throws Exception {
            return messages.poll(timeout, unit);
        }
    }
}
