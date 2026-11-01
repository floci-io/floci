package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.AlbFixtures.createListener;
import static io.github.hectorvent.floci.testing.AlbFixtures.createLoadBalancer;
import static io.github.hectorvent.floci.testing.AlbFixtures.createTargetGroup;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteListener;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteLoadBalancer;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteTargetGroup;
import static io.github.hectorvent.floci.testing.AlbFixtures.loadBalancerDnsName;
import static io.github.hectorvent.floci.testing.AlbFixtures.registerTarget;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2PreserveHostHeaderIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260803/us-east-1/elasticloadbalancing/aws4_request";
    private static final int LISTENER_PORT = 7794;
    private static final String CLIENT_HOST = "client.example.test:8443";

    @Inject
    Vertx vertx;

    @Test
    void preservesRepeatedTargetResponseHeaders() throws Exception {
        HttpServer backend = vertx.createHttpServer().requestHandler(request -> {
            request.response().headers()
                    .add("Set-Cookie", "a=1; Path=/")
                    .add("Set-Cookie", "b=2; Path=/")
                    .add("Set-Cookie", "c=3; Path=/")
                    .add("X-Multi", "one")
                    .add("X-Multi", "two")
                    .add("Keep-Alive", "timeout=5");
            request.response().end("ok");
        }).listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        String loadBalancerArn = null;
        String targetGroupArn = null;
        String listenerArn = null;
        try {
            loadBalancerArn = createLoadBalancer("preserve-host-header-lb");
            targetGroupArn = createTargetGroup("preserve-host-header-tg", "ip", backend.actualPort());
            registerTarget(targetGroupArn, "127.0.0.1", backend.actualPort());
            listenerArn = createListener(loadBalancerArn, targetGroupArn, LISTENER_PORT);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                Response response = given().baseUri("http://127.0.0.1").port(LISTENER_PORT).get("/headers");
                assertEquals(200, response.statusCode());
                assertEquals("ok", response.asString());
                assertEquals(List.of("a=1; Path=/", "b=2; Path=/", "c=3; Path=/"),
                        response.headers().getValues("Set-Cookie"));
                assertEquals(List.of("one", "two"), response.headers().getValues("X-Multi"));
                assertEquals(List.of(), response.headers().getValues("Keep-Alive"));
            });
        } finally {
            deleteListener(listenerArn);
            deleteTargetGroup(targetGroupArn);
            deleteLoadBalancer(loadBalancerArn);
            backend.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void preservesHostHeaderWhenLoadBalancerAttributeIsEnabled() throws Exception {
        HttpServer backend = vertx.createHttpServer(new HttpServerOptions()
                        .setMaxHeaderSize(ElbV2DataPlane.MAX_REQUEST_HEADER_SIZE)
                        .setMaxInitialLineLength(ElbV2DataPlane.MAX_REQUEST_LINE_LENGTH))
                .requestHandler(request -> {
                    if ("/stream".equals(request.path())) {
                        request.body().onSuccess(body -> {
                            request.response()
                                    .putHeader("X-Received-Content-Length",
                                            String.valueOf(request.getHeader("Content-Length")))
                                    .setChunked(true);
                            request.response().write("stream-");
                            request.response().end(body.toString());
                        });
                    } else if ("/large-headers".equals(request.path())) {
                        String cookie = request.getHeader("Cookie");
                        request.response()
                                .putHeader("X-Received-Cookie-Length", String.valueOf(cookie.length()))
                                .putHeader("X-Received-Query-Length", String.valueOf(request.query().length()))
                                .putHeader("X-Large", cookie)
                                .end("ok");
                    } else {
                        request.response().end(request.getHeader("Host"));
                    }
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage()
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);

        String loadBalancerArn = null;
        String targetGroupArn = null;
        String listenerArn = null;
        try {
            loadBalancerArn = createLoadBalancer("preserve-host-header-lb");
            targetGroupArn = createTargetGroup("preserve-host-header-tg", "ip", backend.actualPort());
            registerTarget(targetGroupArn, "127.0.0.1", backend.actualPort());
            listenerArn = createListener(loadBalancerArn, targetGroupArn, LISTENER_PORT);

            assertStreamsRequestAndResponse(loadBalancerArn);
            assertForwardsLargeHeaders(loadBalancerArn);
            assertForwardedHost("127.0.0.1:" + backend.actualPort());

            enableHostHeaderPreservation(loadBalancerArn);

            assertForwardedHost(CLIENT_HOST);
        } finally {
            deleteListener(listenerArn);
            deleteTargetGroup(targetGroupArn);
            deleteLoadBalancer(loadBalancerArn);
            backend.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void drainsRequestWhenTargetConnectionFails() throws Exception {
        HttpServer backend = vertx.createHttpServer()
                .requestHandler(request -> request.response().end("healthy"))
                .listen(0, "127.0.0.1")
                .toCompletionStage()
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);

        String loadBalancerArn = null;
        String targetGroupArn = null;
        String listenerArn = null;
        int failedPort = FreePorts.anyInterfacePort();
        try {
            loadBalancerArn = createLoadBalancer("preserve-host-header-lb");
            targetGroupArn = createTargetGroup("preserve-host-header-tg", "ip", backend.actualPort());
            registerTarget(targetGroupArn, "127.0.0.1", failedPort);
            registerTarget(targetGroupArn, "127.0.0.1", backend.actualPort());
            listenerArn = createListener(loadBalancerArn, targetGroupArn, LISTENER_PORT);

            String host = loadBalancerDnsName(loadBalancerArn);
            try (Socket socket = new Socket("127.0.0.1", LISTENER_PORT)) {
                socket.setSoTimeout(2_000);
                OutputStream output = socket.getOutputStream();
                output.write(("POST /failed HTTP/1.1\r\n"
                        + "Host: " + host + "\r\n"
                        + "Content-Length: 11\r\n"
                        + "Connection: keep-alive\r\n\r\n"
                        + "hello world").getBytes(StandardCharsets.ISO_8859_1));
                output.flush();

                BufferedReader input = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                assertEquals(503, readStatus(input));

                output.write(("GET /healthy HTTP/1.1\r\n"
                        + "Host: " + host + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                output.flush();

                assertEquals(200, readStatus(input));
            }
        } finally {
            deleteListener(listenerArn);
            deleteTargetGroup(targetGroupArn);
            deleteLoadBalancer(loadBalancerArn);
            backend.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    private static int readStatus(BufferedReader input) throws IOException {
        String statusLine = input.readLine();
        if (statusLine == null) {
            throw new IOException("Response ended before the status line");
        }
        int contentLength = 0;
        String header;
        while (!(header = input.readLine()).isEmpty()) {
            int separator = header.indexOf(':');
            if (separator > 0 && "content-length".equalsIgnoreCase(header.substring(0, separator))) {
                contentLength = Integer.parseInt(header.substring(separator + 1).trim());
            }
        }
        for (int i = 0; i < contentLength; i++) {
            if (input.read() == -1) {
                throw new IOException("Response ended before the body");
            }
        }
        return Integer.parseInt(statusLine.split(" ", 3)[1]);
    }

    private void assertStreamsRequestAndResponse(String loadBalancerArn) throws Exception {
        String dnsName = loadBalancerDnsName(loadBalancerArn);
        HttpClient client = vertx.createHttpClient();
        try {
            // The DNS name resolves only through public DNS, so connect to loopback and send it as Host.
            HttpClientRequest request = client
                    .request(new RequestOptions()
                            .setServer(SocketAddress.inetSocketAddress(LISTENER_PORT, "127.0.0.1"))
                            .setHost(dnsName)
                            .setPort(LISTENER_PORT)
                            .setMethod(HttpMethod.POST)
                            .setURI("/stream"))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertEquals(dnsName, request.getHost());
            CompletableFuture<Buffer> bodyFuture = new CompletableFuture<>();
            CompletableFuture<HttpClientResponse> responseFuture = request.response()
                    .onSuccess(response -> response.bodyHandler(bodyFuture::complete))
                    .toCompletionStage()
                    .toCompletableFuture();
            request.putHeader("Content-Length", "11");
            request.write(Buffer.buffer("hello "));
            request.end(Buffer.buffer("world"));

            HttpClientResponse response = responseFuture.get(2, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode());
            assertEquals("11", response.getHeader("X-Received-Content-Length"));
            assertEquals("stream-hello world", bodyFuture.get(2, TimeUnit.SECONDS).toString());
        } finally {
            // Not awaited, so a slow close cannot replace the failure raised above.
            client.close();
        }
    }

    private void assertForwardsLargeHeaders(String loadBalancerArn) throws Exception {
        // Several JWT cookies on a shared parent domain exceed Vert.x's 8 KB default header block,
        // while staying under the ALB's 16 KB single-header limit. The query passes Vert.x's 4 KB
        // default request line.
        String cookie = "a=" + "x".repeat(3600) + "; b=" + "y".repeat(3600) + "; c=" + "z".repeat(3600);
        String query = "q=" + "q".repeat(10_000);
        String dnsName = loadBalancerDnsName(loadBalancerArn);
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setMaxHeaderSize(ElbV2DataPlane.MAX_RESPONSE_HEADER_SIZE));
        try {
            HttpClientRequest request = client
                    .request(new RequestOptions()
                            .setServer(SocketAddress.inetSocketAddress(LISTENER_PORT, "127.0.0.1"))
                            .setHost(dnsName)
                            .setPort(LISTENER_PORT)
                            .setMethod(HttpMethod.GET)
                            .setURI("/large-headers?" + query))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            request.putHeader("Cookie", cookie);
            HttpClientResponse response = request.send()
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            response.body().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(200, response.statusCode());
            assertEquals(String.valueOf(cookie.length()), response.getHeader("X-Received-Cookie-Length"));
            assertEquals(String.valueOf(query.length()), response.getHeader("X-Received-Query-Length"));
            assertEquals(cookie, response.getHeader("X-Large"));
        } finally {
            client.close();
        }
    }

    private static void enableHostHeaderPreservation(String loadBalancerArn) {
        given()
                .formParam("Action", "ModifyLoadBalancerAttributes")
                .formParam("LoadBalancerArn", loadBalancerArn)
                .formParam("Attributes.member.1.Key", "routing.http.preserve_host_header.enabled")
                .formParam("Attributes.member.1.Value", "true")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    private static void assertForwardedHost(String expectedHost) {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> given()
                .baseUri("http://127.0.0.1")
                .port(LISTENER_PORT)
                .header("Host", CLIENT_HOST)
            .when()
                .get("/")
            .then()
                .statusCode(200)
                .body(equalTo(expectedHost)));
    }

}
