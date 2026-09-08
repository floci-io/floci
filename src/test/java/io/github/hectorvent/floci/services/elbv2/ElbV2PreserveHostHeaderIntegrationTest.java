package io.github.hectorvent.floci.services.elbv2;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(ElbV2PreserveHostHeaderIntegrationTest.RealElbV2DataPlaneProfile.class)
class ElbV2PreserveHostHeaderIntegrationTest {

    public static final class RealElbV2DataPlaneProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.elbv2.mock", "false");
        }
    }

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260803/us-east-1/elasticloadbalancing/aws4_request";
    private static final int LISTENER_PORT = 7794;
    private static final String CLIENT_HOST = "client.example.test:8443";

    @Inject
    Vertx vertx;

    @Test
    void preservesHostHeaderWhenLoadBalancerAttributeIsEnabled() throws Exception {
        HttpServer backend = vertx.createHttpServer()
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
            loadBalancerArn = createLoadBalancer();
            targetGroupArn = createTargetGroup(backend.actualPort());
            registerTarget(targetGroupArn, backend.actualPort());
            listenerArn = createListener(loadBalancerArn, targetGroupArn);

            assertStreamsRequestAndResponse(loadBalancerArn);
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
        int failedPort;
        try (ServerSocket reservation = new ServerSocket(0)) {
            failedPort = reservation.getLocalPort();
        }
        try {
            loadBalancerArn = createLoadBalancer();
            targetGroupArn = createTargetGroup(backend.actualPort());
            registerTargets(targetGroupArn, failedPort, backend.actualPort());
            listenerArn = createListener(loadBalancerArn, targetGroupArn);

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
        HttpClientRequest request = vertx.createHttpClient()
                .request(new RequestOptions()
                        .setHost(loadBalancerDnsName(loadBalancerArn))
                        .setPort(LISTENER_PORT)
                        .setMethod(io.vertx.core.http.HttpMethod.POST)
                        .setURI("/stream"))
                .toCompletionStage()
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
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
    }

    private static String createLoadBalancer() {
        return given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "preserve-host-header-lb")
                .formParam("Type", "application")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    private static String loadBalancerDnsName(String loadBalancerArn) {
        return given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("LoadBalancerArns.member.1", loadBalancerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.DNSName");
    }

    private static String createTargetGroup(int backendPort) {
        return given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "preserve-host-header-tg")
                .formParam("Protocol", "HTTP")
                .formParam("Port", backendPort)
                .formParam("TargetType", "ip")
                .formParam("HealthCheckEnabled", "false")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    private static void registerTarget(String targetGroupArn, int backendPort) {
        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", targetGroupArn)
                .formParam("Targets.member.1.Id", "127.0.0.1")
                .formParam("Targets.member.1.Port", backendPort)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    private static void registerTargets(String targetGroupArn, int failedPort, int backendPort) {
        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", targetGroupArn)
                .formParam("Targets.member.1.Id", "127.0.0.1")
                .formParam("Targets.member.1.Port", failedPort)
                .formParam("Targets.member.2.Id", "127.0.0.1")
                .formParam("Targets.member.2.Port", backendPort)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    private static String createListener(String loadBalancerArn, String targetGroupArn) {
        return given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", loadBalancerArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", LISTENER_PORT)
                .formParam("DefaultActions.member.1.Type", "forward")
                .formParam("DefaultActions.member.1.TargetGroupArn", targetGroupArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
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

    private static void deleteListener(String listenerArn) {
        if (listenerArn != null) {
            given()
                    .formParam("Action", "DeleteListener")
                    .formParam("ListenerArn", listenerArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }

    private static void deleteTargetGroup(String targetGroupArn) {
        if (targetGroupArn != null) {
            given()
                    .formParam("Action", "DeleteTargetGroup")
                    .formParam("TargetGroupArn", targetGroupArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }

    private static void deleteLoadBalancer(String loadBalancerArn) {
        if (loadBalancerArn != null) {
            given()
                    .formParam("Action", "DeleteLoadBalancer")
                    .formParam("LoadBalancerArn", loadBalancerArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
}
