package io.floci.aws.core.common;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spark Connect calls through {@link SparkConnectProxy}, over HTTP/2 from the first byte as gRPC's are: a server of
 * gRPC's shape stands in for a session's Spark Connect server, and a service's routes for its tokens.
 */
class SparkConnectProxyTest {

    private static final String PATH = "/spark.connect.SparkConnectService/ExecutePlan";
    private static final String TOKEN = "a-session-token";

    private final Map<String, SparkConnectTargets.Route> routes = new ConcurrentHashMap<>();
    private final AtomicInteger callsEnded = new AtomicInteger();
    private final AtomicReference<MultiMap> serverSaw = new AtomicReference<>();
    private Vertx vertx;
    private SparkConnectProxy proxy;
    private HttpServer floci;
    private HttpServer sparkConnect;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        // A server of gRPC's shape: it answers a call with the message it was sent, then status 0 in its trailers.
        sparkConnect = await(vertx.createHttpServer().requestHandler(request -> request.body().onSuccess(body -> {
            serverSaw.set(MultiMap.caseInsensitiveMultiMap().addAll(request.headers()));
            request.response().putHeader("content-type", "application/grpc").write(body);
            request.response().trailers().add("grpc-status", "0");
            request.response().end();
        })).listen(0, "127.0.0.1"));
        @SuppressWarnings("unchecked")
        Instance<SparkConnectTargets> targets = mock(Instance.class);
        SparkConnectTargets service = token -> Optional.ofNullable(routes.get(token));
        when(targets.iterator()).thenAnswer(invocation -> List.of(service).iterator());
        proxy = new SparkConnectProxy(targets, vertx);
        Router router = Router.router(vertx);
        proxy.register(router);
        router.route().handler(context -> context.response().setStatusCode(404).end("not Spark Connect"));
        floci = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
        client = vertx.createHttpClient(new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false));
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    void aCallWithItsSessionsTokenGoesToTheServerAndItsAnswerAndTrailersComeBack() throws Exception {
        routes.put(TOKEN, SparkConnectTargets.Route.to("127.0.0.1", sparkConnect.actualPort(),
                callsEnded::incrementAndGet));

        Answer answer = call(TOKEN, "a message");

        assertEquals(200, answer.status);
        assertEquals("a message", answer.body);
        assertEquals("application/grpc", answer.headers.get("content-type"));
        assertEquals("0", answer.trailers.get("grpc-status"));
        // The token is Floci's to check, so the server never sees it.
        assertNull(serverSaw.get().get(SparkConnectProxy.TOKEN_METADATA));
        assertEquals("trailers", serverSaw.get().get("te"));
        assertNull(serverSaw.get().get("transfer-encoding"));
        awaitCount(callsEnded, 1);
    }

    @Test
    void aCallWithNoTokenOrOneNoServiceGaveIsUnauthenticated() throws Exception {
        Answer none = call(null, "a message");
        Answer unknown = call("not-a-token", "a message");

        assertEquals("16", none.headers.get("grpc-status"));
        assertEquals(SparkConnectProxy.NO_TOKEN, none.headers.get("grpc-message"));
        assertEquals("16", unknown.headers.get("grpc-status"));
        assertEquals(SparkConnectProxy.UNKNOWN_TOKEN, unknown.headers.get("grpc-message"));
        assertEquals("", unknown.body);
    }

    @Test
    void aCallTheServiceRefusesEndsWithItsStatusAndOneToAServerNotThereIsUnavailable() throws Exception {
        routes.put(TOKEN, SparkConnectTargets.Route.refused(SparkConnectTargets.Route.UNAVAILABLE, "Not 100% ready"));
        Answer refused = call(TOKEN, "a message");
        int closedPort = sparkConnect.actualPort();
        await(sparkConnect.close());
        routes.put(TOKEN, SparkConnectTargets.Route.to("127.0.0.1", closedPort, callsEnded::incrementAndGet));
        Answer unreachable = call(TOKEN, "a message");

        assertEquals("14", refused.headers.get("grpc-status"));
        // gRPC's grpc-message percent-encodes a '%'.
        assertEquals("Not 100%25 ready", refused.headers.get("grpc-message"));
        assertEquals("14", unreachable.headers.get("grpc-status"));
        assertTrue(unreachable.headers.get("grpc-message").startsWith("The session's Spark Connect server could not"));
        awaitCount(callsEnded, 1);
    }

    @Test
    void aRequestThatIsNotASparkConnectCallGoesOnToFlocisOtherRoutes() throws Exception {
        RequestOptions options = new RequestOptions().setMethod(HttpMethod.POST).setHost("127.0.0.1")
                .setPort(floci.actualPort()).setURI("/2021-07-13/applications")
                .putHeader("content-type", "application/json");
        Answer answer = send(options, "{}");

        assertEquals(404, answer.status);
        assertEquals("not Spark Connect", answer.body);
    }

    @Test
    void aServerAnswersOnceItsGrpcServerRunsAndAClosedPortSaysWhyNot() throws Exception {
        assertNull(await(proxy.unanswered("127.0.0.1", sparkConnect.actualPort())));
        int closedPort = sparkConnect.actualPort();
        await(sparkConnect.close());

        String why = await(proxy.unanswered("127.0.0.1", closedPort));
        assertTrue(why != null && !why.isEmpty(), String.valueOf(why));
    }

    @Test
    void aServersErrorAnsweredTrailersOnlyReachesTheClientAndEndsTheCall() throws Exception {
        // As grpc-java answers an error raised before any message: its status in its only headers.
        HttpServer failing = await(vertx.createHttpServer().requestHandler(request -> request.body().onSuccess(body ->
                request.response().putHeader("content-type", "application/grpc").putHeader("grpc-status", "3")
                        .putHeader("grpc-message", "TABLE_OR_VIEW_NOT_FOUND").end()))
                .listen(0, "127.0.0.1"));
        routes.put(TOKEN, SparkConnectTargets.Route.to("127.0.0.1", failing.actualPort(),
                callsEnded::incrementAndGet));

        Answer answer = call(TOKEN, "a message");

        assertEquals("3", answer.headers.get("grpc-status") != null ? answer.headers.get("grpc-status")
                : answer.trailers.get("grpc-status"));
        assertEquals("", answer.body);
        awaitCount(callsEnded, 1);
    }

    private Answer call(String token, String message) throws Exception {
        RequestOptions options = new RequestOptions().setMethod(HttpMethod.POST).setHost("127.0.0.1")
                .setPort(floci.actualPort()).setURI(PATH)
                .putHeader("content-type", "application/grpc")
                .putHeader("te", "trailers");
        if (token != null) {
            options.putHeader(SparkConnectProxy.TOKEN_METADATA, token);
        }
        // As a gRPC client sends a call: its message streamed, with no Content-Length.
        return await(client.request(options)
                .compose(request -> {
                    request.setChunked(true);
                    request.write(Buffer.buffer(message));
                    return request.end().compose(ended -> request.response());
                })
                .compose(response -> response.body().map(received -> new Answer(response.statusCode(),
                        response.headers(), received.toString(), response.trailers()))));
    }

    private Answer send(RequestOptions options, String body) throws Exception {
        return await(client.request(options)
                .compose(request -> request.send(Buffer.buffer(body)))
                .compose(response -> response.body().map(received -> new Answer(response.statusCode(),
                        response.headers(), received.toString(), response.trailers()))));
    }

    private static void awaitCount(AtomicInteger count, int expected) throws InterruptedException {
        CountDownLatch never = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (count.get() < expected && System.nanoTime() - deadline < 0) {
            never.await(10, TimeUnit.MILLISECONDS);
        }
        assertEquals(expected, count.get());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private record Answer(int status, MultiMap headers, String body, MultiMap trailers) {
    }
}
