package io.floci.aws.services.emrcontainers;

import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.common.SparkConnectTargets;
import io.floci.aws.core.common.WebSocketUpgrades;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Requests to an endpoint's {@code serverUrl} through {@link EmrContainersGatewayProxy}: a server that echoes what it
 * gets stands in for the endpoint's gateway, and the service's routes for its tokens.
 */
class EmrContainersGatewayProxyTest {

    private static final String ENDPOINT = "ep0123456789a";
    private static final String TOKEN = "a-session-token";
    private static final String BASE = EmrContainersService.GATEWAY_PATH + ENDPOINT;

    private final AtomicInteger requestsEnded = new AtomicInteger();
    private Vertx vertx;
    private HttpServer gateway;
    private HttpServer floci;
    private HttpClient client;
    private EmrContainersService service;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        // A gateway that answers a request with what it got, and echoes each WebSocket message; at /partial it
        // breaks off its answer after a part of the body it gave the length of.
        gateway = await(vertx.createHttpServer().requestHandler(request -> {
            if (WebSocketUpgrades.isHandshake(request)) {
                request.toWebSocket().onSuccess(socket -> socket.textMessageHandler(message ->
                        socket.writeTextMessage("echo " + message + " on " + request.uri())));
                return;
            }
            if (request.path().equals("/partial")) {
                request.response().putHeader("content-length", "100").write("partial")
                        .onComplete(written -> request.connection().close());
                return;
            }
            request.body().onSuccess(body -> request.response().setStatusCode(201)
                    .putHeader("content-type", "application/json")
                    .end(new JsonObject().put("method", request.method().name()).put("uri", request.uri())
                            .put("authorization", request.getHeader("Authorization"))
                            .put("body", body.toString()).encode()));
        }).listen(0, "127.0.0.1"));
        service = mock(EmrContainersService.class);
        when(service.gatewayRoute(anyString(), any())).thenReturn(Optional.empty());
        when(service.gatewayRoute(ENDPOINT, TOKEN)).thenAnswer(invocation -> Optional.of(
                SparkConnectTargets.Route.to("127.0.0.1", gateway.actualPort(), requestsEnded::incrementAndGet)));
        ServiceConfigAccess serviceConfigAccess = mock(ServiceConfigAccess.class);
        when(serviceConfigAccess.isEnabled(eq(EmrContainersService.SERVICE))).thenReturn(true);
        EmrContainersGatewayProxy proxy = new EmrContainersGatewayProxy(service, serviceConfigAccess, vertx);
        Router router = Router.router(vertx);
        proxy.register(router);
        router.route().handler(context -> context.response().setStatusCode(404).end("not a gateway"));
        floci = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
        client = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    void requestWithItsEndpointsTokenGoesToItsGatewayWithoutTheToken() throws Exception {
        Answer byParameter = request(HttpMethod.POST, BASE + "/api/kernels?token=" + TOKEN + "&path=nb", null,
                "{\"name\": \"python3\"}");
        Answer byHeader = request(HttpMethod.GET, BASE + "/api/kernelspecs", "token " + TOKEN, null);

        assertEquals(201, byParameter.status);
        JsonObject gotByParameter = new JsonObject(byParameter.body);
        assertEquals("POST", gotByParameter.getString("method"));
        assertEquals("/api/kernels?path=nb", gotByParameter.getString("uri"));
        assertEquals("{\"name\": \"python3\"}", gotByParameter.getString("body"));
        JsonObject gotByHeader = new JsonObject(byHeader.body);
        assertEquals("/api/kernelspecs", gotByHeader.getString("uri"));
        // The token is Floci's to check, so the gateway never sees it.
        assertNull(gotByHeader.getString("authorization"));
        awaitCount(requestsEnded, 2);
    }

    @Test
    void queryIsReadAsTheGatewayReadsIt() throws Exception {
        // Its last token parameter is the one checked, and a malformed escape stays as it came.
        Answer answer = request(HttpMethod.GET, BASE + "/api/kernels?%GG=1&token=not-a-token&token=" + TOKEN, null,
                null);

        assertEquals(201, answer.status);
        assertEquals("/api/kernels?%GG=1", new JsonObject(answer.body).getString("uri"));
        awaitCount(requestsEnded, 1);
    }

    @Test
    void answerTheGatewayBreaksOffBreaksOffTheViewers() {
        ExecutionException broken = assertThrows(ExecutionException.class,
                () -> request(HttpMethod.GET, BASE + "/partial", "token " + TOKEN, null));

        assertTrue(broken.getCause().getMessage().toLowerCase().contains("closed"), broken.getCause().toString());
        awaitCount(requestsEnded, 1);
    }

    @Test
    void requestWithoutATokenGivenForItsEndpointGetsTheGatewaysUnauthorized() throws Exception {
        Answer none = request(HttpMethod.GET, BASE + "/api/kernels", null, null);
        Answer unknown = request(HttpMethod.GET, BASE + "/api/kernels", "token not-a-token", null);
        Answer otherScheme = request(HttpMethod.GET, BASE + "/api/kernels", "Bearer " + TOKEN, null);

        for (Answer answer : new Answer[] {none, unknown, otherScheme}) {
            assertEquals(401, answer.status);
            assertEquals(new JsonObject().put("reason", "Unauthorized").put("message", ""),
                    new JsonObject(answer.body));
        }
    }

    @Test
    void requestTheServiceRefusesGetsItsReason() throws Exception {
        when(service.gatewayRoute(ENDPOINT, "expired")).thenReturn(Optional.of(SparkConnectTargets.Route.refused(
                SparkConnectTargets.Route.UNAUTHENTICATED, "The authentication token has expired.")));
        when(service.gatewayRoute(ENDPOINT, "terminating")).thenReturn(Optional.of(SparkConnectTargets.Route.refused(
                SparkConnectTargets.Route.UNAVAILABLE, "Endpoint " + ENDPOINT + " is TERMINATING.")));

        Answer expired = request(HttpMethod.GET, BASE + "/api/kernels", "token expired", null);
        Answer terminating = request(HttpMethod.GET, BASE + "/api/kernels", "token terminating", null);

        assertEquals(401, expired.status);
        assertEquals("The authentication token has expired.", new JsonObject(expired.body).getString("message"));
        assertEquals(503, terminating.status);
        assertEquals("Service Unavailable", new JsonObject(terminating.body).getString("reason"));
        assertEquals("Endpoint " + ENDPOINT + " is TERMINATING.",
                new JsonObject(terminating.body).getString("message"));
    }

    @Test
    void kernelsWebSocketIsTunnelledToTheGateway() throws Exception {
        WebSocket socket = await(vertx.createWebSocketClient().connect(new WebSocketConnectOptions()
                .setHost("127.0.0.1").setPort(floci.actualPort())
                .setURI(BASE + "/api/kernels/k1/channels?session_id=s1")
                .addHeader("Authorization", "token " + TOKEN)));
        CompletableFuture<String> echoed = new CompletableFuture<>();
        socket.textMessageHandler(echoed::complete);

        socket.writeTextMessage("execute_request");

        assertEquals("echo execute_request on /api/kernels/k1/channels?session_id=s1",
                echoed.get(10, TimeUnit.SECONDS));
        await(socket.close());
        awaitCount(requestsEnded, 1);
    }

    @Test
    void kernelsWebSocketWithoutAValidTokenIsRefused() {
        Exception refused = assertThrows(Exception.class, () -> await(
                vertx.createWebSocketClient().connect(new WebSocketConnectOptions().setHost("127.0.0.1")
                        .setPort(floci.actualPort()).setURI(BASE + "/api/kernels/k1/channels"))));
        assertTrue(refused.getMessage().contains("401"), refused.getMessage());
    }

    private Answer request(HttpMethod method, String uri, String authorization, String body) throws Exception {
        RequestOptions options = new RequestOptions().setMethod(method).setHost("127.0.0.1")
                .setPort(floci.actualPort()).setURI(uri);
        if (authorization != null) {
            options.addHeader("Authorization", authorization);
        }
        // The answer's body is read as its head arrives, on the event loop: asked for later, it may have gone by.
        return await(client.request(options)
                .compose(request -> body == null ? request.send() : request.send(Buffer.buffer(body)))
                .compose(response -> response.body()
                        .map(read -> new Answer(response.statusCode(), read.toString()))));
    }

    // Awaitility's, by name: this class's own await takes a Future.
    private static void awaitCount(AtomicInteger count, int expected) {
        Awaitility.await("requests ended").atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(10))
                .until(() -> count.get() >= expected);
        assertEquals(expected, count.get());
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private record Answer(int status, String body) {
    }
}
