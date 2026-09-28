package io.github.hectorvent.floci.services.mwaa.proxy;

import io.github.hectorvent.floci.services.mwaa.MwaaEnvironmentManager;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms {@code POST /aws_mwaa/cli} runs its Docker exec off the Vert.x event loop.
 * {@code MwaaEnvironmentManager#execInContainer} can block for up to 30 seconds
 * ({@code awaitCompletion(30, TimeUnit.SECONDS)}), so running it synchronously on the event
 * loop that also serves the rest of this environment's proxied Airflow traffic would stall
 * every other request for the duration of one CLI call.
 *
 * <p>Each test pins the proxy, its forwarded backend, and the CLI/forwarded requests onto a
 * single-event-loop-thread {@link Vertx}, so a slow CLI call can only avoid starving unrelated
 * traffic if it no longer runs on that thread.
 */
class MwaaWebProxyCliOffloadTest {

    private Vertx vertx;

    @AfterEach
    void tearDown() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void slowCliExecutionDoesNotStallConcurrentProxyTraffic() throws Exception {
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));

        HttpServer backend = vertx.createHttpServer()
                .requestHandler(req -> req.response().setStatusCode(200).end("backend-ok"))
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

        CountDownLatch cliStarted = new CountDownLatch(1);
        CountDownLatch releaseCli = new CountDownLatch(1);
        MwaaWebProxy.CliExecutor slowExecutor = command -> {
            cliStarted.countDown();
            if (!releaseCli.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the CLI exec in time");
            }
            return new MwaaEnvironmentManager.ExecResult(0, "dag1\ndag2\n", "");
        };

        MwaaWebProxy proxy = new MwaaWebProxy("env1", vertx, "127.0.0.1", backend.actualPort(),
                (environmentName, token) -> true, slowExecutor);
        int proxyPort;
        try (ServerSocket freePort = new ServerSocket(0)) {
            proxyPort = freePort.getLocalPort();
        }
        proxy.start(proxyPort);

        try {
            HttpClient client = vertx.createHttpClient();

            CompletableFuture<ProxyResponse> cliResponseFuture = client.request(new RequestOptions()
                            .setHost("127.0.0.1")
                            .setPort(proxyPort)
                            .setMethod(HttpMethod.POST)
                            .setURI("/aws_mwaa/cli"))
                    .compose(request -> {
                        request.putHeader("Authorization", "Bearer test-token");
                        request.putHeader("Content-Type", "text/plain");
                        return request.send(Buffer.buffer("dags list"));
                    })
                    .compose(response -> response.body()
                            .map(body -> new ProxyResponse(response.statusCode(), body.toString())))
                    .toCompletionStage().toCompletableFuture();

            assertTrue(cliStarted.await(2, TimeUnit.SECONDS), "CLI executor was never invoked");

            // Fired while the CLI request above is still parked inside the fake Docker exec. On a
            // single-event-loop-thread Vertx, this can only complete promptly if that thread is
            // free to accept the connection, route it, and relay the backend's response -- i.e.
            // only once the CLI call is no longer running on that same thread.
            ProxyResponse forwarded = client.request(new RequestOptions()
                            .setHost("127.0.0.1")
                            .setPort(proxyPort)
                            .setMethod(HttpMethod.GET)
                            .setURI("/"))
                    .compose(request -> request.send())
                    .compose(response -> response.body()
                            .map(body -> new ProxyResponse(response.statusCode(), body.toString())))
                    .toCompletionStage().toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);

            assertEquals(200, forwarded.statusCode());
            assertEquals("backend-ok", forwarded.body());

            releaseCli.countDown();

            ProxyResponse cliResponse = cliResponseFuture.get(3, TimeUnit.SECONDS);
            assertEquals(200, cliResponse.statusCode());
            JsonObject json = new JsonObject(cliResponse.body());
            assertEquals("dag1\ndag2\n",
                    new String(Base64.getDecoder().decode(json.getString("stdout")), StandardCharsets.UTF_8));
            assertEquals("",
                    new String(Base64.getDecoder().decode(json.getString("stderr")), StandardCharsets.UTF_8));
        } finally {
            releaseCli.countDown();
            proxy.stop();
            backend.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void burstOfSlowCliCallsLeavesTheSharedWorkerPoolFree() throws Exception {
        int sharedWorkers = 2;
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1).setWorkerPoolSize(sharedWorkers));

        CountDownLatch cliStarted = new CountDownLatch(sharedWorkers);
        CountDownLatch releaseCli = new CountDownLatch(1);
        MwaaWebProxy.CliExecutor slowExecutor = command -> {
            cliStarted.countDown();
            if (!releaseCli.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test did not release the CLI exec in time");
            }
            return new MwaaEnvironmentManager.ExecResult(0, "", "");
        };
        MwaaWebProxy proxy = new MwaaWebProxy("env1", vertx, "127.0.0.1", 1,
                (environmentName, token) -> true, slowExecutor);
        int proxyPort;
        try (ServerSocket freePort = new ServerSocket(0)) {
            proxyPort = freePort.getLocalPort();
        }
        proxy.start(proxyPort);

        try {
            HttpClient client = vertx.createHttpClient();
            List<CompletableFuture<Integer>> cliCalls = new ArrayList<>();
            for (int i = 0; i < sharedWorkers * 2; i++) {
                cliCalls.add(client.request(new RequestOptions()
                                .setHost("127.0.0.1")
                                .setPort(proxyPort)
                                .setMethod(HttpMethod.POST)
                                .setURI("/aws_mwaa/cli"))
                        .compose(request -> {
                            request.putHeader("Authorization", "Bearer test-token");
                            request.putHeader("Content-Type", "text/plain");
                            return request.send(Buffer.buffer("dags list"));
                        })
                        .map(response -> response.statusCode())
                        .toCompletionStage().toCompletableFuture());
            }
            assertTrue(cliStarted.await(2, TimeUnit.SECONDS), "CLI executor was never invoked");

            // Unrelated blocking work, such as a Lambda invocation, must not queue behind CLI execs.
            String unrelated = vertx.<String>executeBlocking(() -> "ran", false)
                    .toCompletionStage().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertEquals("ran", unrelated);

            releaseCli.countDown();
            for (CompletableFuture<Integer> cliCall : cliCalls) {
                assertEquals(200, cliCall.get(3, TimeUnit.SECONDS));
            }
        } finally {
            releaseCli.countDown();
            proxy.stop();
        }
    }

    @Test
    void cliExecutorFailureStillReturnsFiveHundredWithTheErrorMessage() throws Exception {
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));

        MwaaWebProxy.CliExecutor failingExecutor = command -> {
            throw new IllegalStateException("exec timed out in container abc123");
        };
        MwaaWebProxy proxy = new MwaaWebProxy("env1", vertx, "127.0.0.1", 1,
                (environmentName, token) -> true, failingExecutor);
        int proxyPort;
        try (ServerSocket freePort = new ServerSocket(0)) {
            proxyPort = freePort.getLocalPort();
        }
        proxy.start(proxyPort);

        try {
            HttpClient client = vertx.createHttpClient();
            ProxyResponse response = client.request(new RequestOptions()
                            .setHost("127.0.0.1")
                            .setPort(proxyPort)
                            .setMethod(HttpMethod.POST)
                            .setURI("/aws_mwaa/cli"))
                    .compose(request -> {
                        request.putHeader("Authorization", "Bearer test-token");
                        request.putHeader("Content-Type", "text/plain");
                        return request.send(Buffer.buffer("dags list"));
                    })
                    .compose(resp -> resp.body().map(body -> new ProxyResponse(resp.statusCode(), body.toString())))
                    .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(500, response.statusCode());
            JsonObject json = new JsonObject(response.body());
            assertEquals("exec timed out in container abc123", json.getString("message"));
        } finally {
            proxy.stop();
        }
    }

    private record ProxyResponse(int statusCode, String body) {
    }
}
