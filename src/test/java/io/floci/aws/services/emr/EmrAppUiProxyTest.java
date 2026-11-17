package io.floci.aws.services.emr;

import io.floci.aws.core.common.ServiceConfigAccess;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Requests to an on-cluster application UI's own host through {@link EmrAppUiProxy}: a server stands in for the
 * interface on the cluster's primary node, and a route for what the link's host resolves to.
 */
class EmrAppUiProxyTest {

    private static final String HOST = "s".repeat(26) + "-8088.emrappui.localhost";
    private static final String NODE_ORIGIN = "http://ip-10-0-0-1.ec2.internal:19888";
    private static final String BROWSER_ORIGIN = "http://" + "s".repeat(26) + "-19888.emrappui.localhost:4566";
    private static final Map<String, String> REWRITES = Map.of(NODE_ORIGIN, BROWSER_ORIGIN);

    private final EmrAppUiService appUis = mock(EmrAppUiService.class);
    private final AtomicReference<MultiMap> interfaceSaw = new AtomicReference<>();
    private final AtomicReference<String> interfaceSawUri = new AtomicReference<>();
    private final AtomicReference<Buffer> interfaceSawBody = new AtomicReference<>();
    // What the interface answers each request with.
    private volatile Answer answer = (response, body) -> response.end();
    private Vertx vertx;
    private HttpServer floci;
    private HttpServer node;
    private HttpClient client;

    /** How the stand-in interface answers. */
    private interface Answer {
        void answer(HttpServerResponse response, Buffer body);
    }

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        node = await(vertx.createHttpServer().requestHandler(request -> request.body().onSuccess(body -> {
            interfaceSaw.set(MultiMap.caseInsensitiveMultiMap().addAll(request.headers()));
            interfaceSawUri.set(request.uri());
            interfaceSawBody.set(body);
            answer.answer(request.response(), body);
        })).listen(0, "127.0.0.1"));
        ServiceConfigAccess services = mock(ServiceConfigAccess.class);
        when(services.isEnabled("emr")).thenReturn(true);
        Router router = Router.router(vertx);
        // An interface that sends nothing for two seconds is given up.
        new EmrAppUiProxy(appUis, services, vertx, Duration.ofSeconds(2)).register(router);
        router.route().handler(context -> context.response().setStatusCode(404).end("not a link"));
        floci = await(vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1"));
        client = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        await(vertx.close());
    }

    @Test
    void aPageComesBackWithItsLinksToTheNodesInterfacesMadeTheBrowsers() throws Exception {
        routeTo(node.actualPort());
        answer = (response, body) -> response.putHeader("content-type", "text/html; charset=utf-8")
                .end("<a href=\"" + NODE_ORIGIN + "/jobhistory/job/job_1_0001\">History</a> é");

        Answered response = send(HttpMethod.GET, "/cluster/app/application_1_0001?a=b", null);

        assertEquals(200, response.statusCode());
        assertEquals("<a href=\"" + BROWSER_ORIGIN + "/jobhistory/job/job_1_0001\">History</a> é",
                response.body().toString());
        assertEquals("/cluster/app/application_1_0001?a=b", interfaceSawUri.get());
        // The interface is asked under the browser's host, so the links it writes itself are the browser's.
        assertEquals(HOST + ":4566", interfaceSaw.get().get("host"));
        assertEquals("identity", interfaceSaw.get().get("accept-encoding"));
    }

    @Test
    void aRedirectToAnotherInterfaceGoesToItsOwnHost() throws Exception {
        routeTo(node.actualPort());
        answer = (response, body) -> response.setStatusCode(302)
                .putHeader("location", NODE_ORIGIN + "/jobhistory/job/job_1_0001").end();

        Answered response = send(HttpMethod.GET, "/proxy/application_1_0001/", null);

        assertEquals(302, response.statusCode());
        assertEquals(BROWSER_ORIGIN + "/jobhistory/job/job_1_0001", response.getHeader("location"));
    }

    @Test
    void whatIsNotTextGoesBackAsItCameAndARequestsBodyGoesOn() throws Exception {
        routeTo(node.actualPort());
        byte[] image = Buffer.buffer(NODE_ORIGIN).appendByte((byte) 0).appendByte((byte) 0xff).getBytes();
        answer = (response, body) -> response.putHeader("content-type", "image/png").end(Buffer.buffer(image));

        Answered response = send(HttpMethod.POST, "/ws/v1/cluster/apps/new-application", "{\"a\":1}");

        assertArrayEquals(image, response.body().getBytes());
        assertEquals(String.valueOf(image.length), response.getHeader("content-length"));
        assertEquals("{\"a\":1}", interfaceSawBody.get().toString());
    }

    @Test
    void anAnswerStreamedWithNoLengthGoesOnInChunksAsItCame() throws Exception {
        routeTo(node.actualPort());
        answer = (response, body) -> response.putHeader("content-type", "application/octet-stream").setChunked(true)
                .write("first part, ").compose(written -> response.end("then the rest"));

        Answered response = send(HttpMethod.GET, "/node/containerlogs/container_1/hadoop/stderr", null);

        assertEquals(200, response.statusCode());
        assertEquals("first part, then the rest", response.body().toString());
    }

    @Test
    void aLargeUploadGoesOnAsItArrives() throws Exception {
        routeTo(node.actualPort());
        String upload = "u".repeat(9 * 1024 * 1024);

        Answered response = send(HttpMethod.PUT, "/ws/v1/cluster/apps/application_1_0001/state", upload);

        assertEquals(200, response.statusCode());
        assertEquals(upload.length(), interfaceSawBody.get().length());
    }

    @Test
    void anInterfaceThatStopsAnsweringIsGivenUp() throws Exception {
        routeTo(node.actualPort());
        // It takes the request and sends nothing.
        answer = (response, body) -> { };

        Answered response = send(HttpMethod.GET, "/cluster", null);

        assertEquals(502, response.statusCode());
    }

    @Test
    void anAnswerTooLargeToHoldGoesOnAsItCame() throws Exception {
        routeTo(node.actualPort());
        String large = NODE_ORIGIN + "x".repeat(9 * 1024 * 1024);
        answer = (response, body) -> response.putHeader("content-type", "text/plain").setChunked(true).end(large);

        Answered response = send(HttpMethod.GET, "/logs/yarn.log", null);

        assertEquals(large, response.body().toString());
    }

    @Test
    void aRefusedLinkIsAnsweredWithAPageSayingWhy() throws Exception {
        when(appUis.route(eq(HOST + ":4566"), anyString()))
                .thenReturn(EmrAppUiService.UiRoute.refused(403, EmrAppUiController.INVALID));

        Answered response = send(HttpMethod.GET, "/cluster", null);

        assertEquals(403, response.statusCode());
        assertTrue(response.body().toString().contains("not valid, or its hour has passed"));
    }

    @Test
    void anInterfaceThatDoesNotAnswerIsABadGatewayAndForgotten() throws Exception {
        int closed = node.actualPort();
        await(node.close());
        AtomicBoolean forgotten = new AtomicBoolean();
        when(appUis.route(eq(HOST + ":4566"), anyString())).thenReturn(new EmrAppUiService.UiRoute("127.0.0.1",
                closed, REWRITES, () -> forgotten.set(true), 0, null));

        Answered response = send(HttpMethod.GET, "/cluster", null);

        assertEquals(502, response.statusCode());
        assertTrue(forgotten.get());
    }

    @Test
    void aHostThatIsNoLinksGoesOnToTheRestOfFloci() throws Exception {
        when(appUis.route(eq(HOST + ":4566"), anyString())).thenReturn(null);

        Answered response = send(HttpMethod.GET, "/", null);

        assertEquals(404, response.statusCode());
        assertEquals("not a link", response.body().toString());
    }

    private void routeTo(int port) {
        when(appUis.route(eq(HOST + ":4566"), eq("http"))).thenReturn(new EmrAppUiService.UiRoute("127.0.0.1", port,
                REWRITES, () -> { }, 0, null));
    }

    /** An answer through the proxy, its body read as it came. */
    private record Answered(HttpClientResponse response, Buffer body) {

        int statusCode() {
            return response.statusCode();
        }

        String getHeader(String name) {
            return response.getHeader(name);
        }
    }

    private Answered send(HttpMethod method, String uri, String body) throws Exception {
        RequestOptions options = new RequestOptions().setMethod(method)
                .setServer(SocketAddress.inetSocketAddress(floci.actualPort(), "127.0.0.1"))
                .setHost(HOST).setPort(4566).setURI(uri);
        return await(client.request(options).compose(request -> body == null ? request.send()
                : request.send(Buffer.buffer(body)))
                .compose(response -> response.body().map(read -> new Answered(response, read))));
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }
}
