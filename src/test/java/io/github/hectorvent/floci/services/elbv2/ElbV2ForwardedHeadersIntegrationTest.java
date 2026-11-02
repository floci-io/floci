package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.net.SocketAddress;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.AlbFixtures.createListener;
import static io.github.hectorvent.floci.testing.AlbFixtures.createLoadBalancer;
import static io.github.hectorvent.floci.testing.AlbFixtures.createTargetGroup;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteListener;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteLoadBalancer;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteTargetGroup;
import static io.github.hectorvent.floci.testing.AlbFixtures.registerTarget;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a target receives through a real listener socket, against the header tables in the ALB guide. */
@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2ForwardedHeadersIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261102/us-east-1/elasticloadbalancing/aws4_request";
    private static final String REGION = "us-east-1";
    private static final String CLIENT = "127.0.0.1";
    private static final String SPOOFED = "198.51.100.7";

    @Inject
    Vertx vertx;

    @Inject
    AcmService acmService;

    private final CompletableFuture<MultiMap> handshake = new CompletableFuture<>();
    private HttpServer backend;
    private int listenerPort;
    private String loadBalancerArn;
    private String targetGroupArn;
    private String listenerArn;

    @BeforeEach
    void startBackend() throws Exception {
        backend = vertx.createHttpServer()
                .requestHandler(request -> {
                    if ("websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
                        handshake.complete(MultiMap.caseInsensitiveMultiMap().addAll(request.headers()));
                        request.toWebSocket();
                        return;
                    }
                    request.response()
                            .putHeader("X-Seen-For", String.join("|", request.headers().getAll("X-Forwarded-For")))
                            .putHeader("X-Seen-Multi", String.join("|", request.headers().getAll("X-Multi")))
                            .putHeader("X-Seen-Proto", String.valueOf(request.getHeader("X-Forwarded-Proto")))
                            .putHeader("X-Seen-Port", String.valueOf(request.getHeader("X-Forwarded-Port")))
                            .end("ok");
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage()
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        listenerPort = FreePorts.anyInterfacePort();
        loadBalancerArn = createLoadBalancer("forwarded-headers-lb");
        targetGroupArn = createTargetGroup("forwarded-headers-tg", "ip", backend.actualPort());
        registerTarget(targetGroupArn, "127.0.0.1", backend.actualPort());
    }

    @AfterEach
    void stopBackend() throws Exception {
        deleteListener(listenerArn);
        deleteTargetGroup(targetGroupArn);
        deleteLoadBalancer(loadBalancerArn);
        backend.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private void startHttpListener() {
        listenerArn = createListener(loadBalancerArn, targetGroupArn, listenerPort);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(200, send().statusCode()));
    }

    private Response send(String... forwardedFor) {
        RequestSpecification request = given()
                .baseUri("http://127.0.0.1")
                .port(listenerPort);
        for (String value : forwardedFor) {
            request.header("X-Forwarded-For", value);
        }
        return request.get("/headers");
    }

    private void setAttribute(String key, String value) {
        given()
                .formParam("Action", "ModifyLoadBalancerAttributes")
                .formParam("LoadBalancerArn", loadBalancerArn)
                .formParam("Attributes.member.1.Key", key)
                .formParam("Attributes.member.1.Value", value)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    void appendIsTheDefaultAndAddsTheClientAddress() {
        startHttpListener();

        assertEquals(CLIENT, send().getHeader("X-Seen-For"));
        assertEquals(SPOOFED + ", " + CLIENT, send(SPOOFED).getHeader("X-Seen-For"));
    }

    @Test
    void appendAddsTheClientPortWhenTheAttributeIsEnabled() {
        startHttpListener();
        setAttribute("routing.http.xff_client_port.enabled", "true");

        String seen = send().getHeader("X-Seen-For");

        assertTrue(seen.matches("127\\.0\\.0\\.1:\\d+"), seen);
    }

    @Test
    void preserveForwardsTheClientHeaderUnchanged() {
        startHttpListener();
        setAttribute("routing.http.xff_header_processing.mode", "preserve");

        assertEquals("", send().getHeader("X-Seen-For"));
        assertEquals(SPOOFED, send(SPOOFED).getHeader("X-Seen-For"));
    }

    @Test
    void removeDropsTheClientHeader() {
        startHttpListener();
        setAttribute("routing.http.xff_header_processing.mode", "remove");

        assertEquals("", send(SPOOFED).getHeader("X-Seen-For"));
    }

    @Test
    void repeatedHeaderLinesAreJoinedInAppendAndKeptInPreserve() {
        startHttpListener();

        assertEquals("10.0.0.1, 10.0.0.2, " + CLIENT, send("10.0.0.1", "10.0.0.2").getHeader("X-Seen-For"));

        setAttribute("routing.http.xff_header_processing.mode", "preserve");

        assertEquals("10.0.0.1|10.0.0.2", send("10.0.0.1", "10.0.0.2").getHeader("X-Seen-For"));
    }

    @Test
    void otherRepeatedRequestHeadersAllReachTheTarget() {
        startHttpListener();

        Response response = given()
                .baseUri("http://127.0.0.1")
                .port(listenerPort)
                .header("X-Multi", "one")
                .header("X-Multi", "two")
                .get("/headers");

        assertEquals("one|two", response.getHeader("X-Seen-Multi"));
    }

    @Test
    void invalidAttributeValuesAreRejected() {
        for (String key : List.of("routing.http.xff_header_processing.mode", "routing.http.xff_client_port.enabled")) {
            given()
                    .formParam("Action", "ModifyLoadBalancerAttributes")
                    .formParam("LoadBalancerArn", loadBalancerArn)
                    .formParam("Attributes.member.1.Key", key)
                    .formParam("Attributes.member.1.Value", "bogus")
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(400)
                    .body(containsString("InvalidConfigurationRequest"));
        }
    }

    @Test
    void protoAndPortReplaceWhatTheClientSent() {
        startHttpListener();

        Response response = given()
                .baseUri("http://127.0.0.1")
                .port(listenerPort)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Port", "1")
                .get("/headers");

        assertEquals("http", response.getHeader("X-Seen-Proto"));
        assertEquals(String.valueOf(listenerPort), response.getHeader("X-Seen-Port"));
    }

    @Test
    void webSocketHandshakeCarriesTheForwardedHeaders() throws Exception {
        startHttpListener();
        HttpClient client = vertx.createHttpClient();
        try {
            WebSocket socket = client.webSocket(new WebSocketConnectOptions()
                            .setHost("127.0.0.1")
                            .setPort(listenerPort)
                            .setURI("/socket")
                            .addHeader("X-Forwarded-For", SPOOFED))
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            MultiMap headers = handshake.get(5, TimeUnit.SECONDS);
            socket.close();

            assertEquals(List.of(SPOOFED + ", " + CLIENT), headers.getAll("X-Forwarded-For"));
            assertEquals("http", headers.get("X-Forwarded-Proto"));
            assertEquals(String.valueOf(listenerPort), headers.get("X-Forwarded-Port"));
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void httpsListenerReportsHttpsToAPlainHttpTarget() throws Exception {
        CertificateGenerator.GeneratedCertificate pair = new CertificateGenerator().generateSelfSignedCertificate(
                "forwarded.elb.invalid", List.of(), KeyAlgorithm.RSA_2048);
        Certificate certificate = acmService.importCertificate(pair.certificatePem(), pair.privateKeyPem(),
                null, null, Map.of(), REGION);
        HttpClient client = vertx.createHttpClient(new HttpClientOptions().setSsl(true).setTrustAll(true)
                .setVerifyHost(false).setForceSni(true));
        try {
            listenerArn = given()
                    .formParam("Action", "CreateListener")
                    .formParam("LoadBalancerArn", loadBalancerArn)
                    .formParam("Protocol", "HTTPS")
                    .formParam("Port", String.valueOf(listenerPort))
                    .formParam("SslPolicy", "ELBSecurityPolicy-TLS13-1-2-2021-06")
                    .formParam("Certificates.member.1.CertificateArn", certificate.getArn())
                    .formParam("DefaultActions.member.1.Type", "forward")
                    .formParam("DefaultActions.member.1.TargetGroupArn", targetGroupArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(200)
                    .extract()
                    .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");

            ElbV2TlsReadiness.await(Duration.ofSeconds(10), () -> {
                HttpClientResponse response = client.request(new RequestOptions()
                                .setMethod(HttpMethod.GET)
                                .setServer(SocketAddress.inetSocketAddress(listenerPort, "127.0.0.1"))
                                .setHost("forwarded.elb.invalid")
                                .setPort(listenerPort)
                                .setURI("/headers"))
                        .compose(HttpClientRequest::send)
                        .toCompletionStage()
                        .toCompletableFuture()
                        .get(2, TimeUnit.SECONDS);

                assertEquals("https", response.getHeader("X-Seen-Proto"));
                assertEquals(String.valueOf(listenerPort), response.getHeader("X-Seen-Port"));
                assertEquals(CLIENT, response.getHeader("X-Seen-For"));
            });
        } finally {
            deleteListener(listenerArn);
            listenerArn = null;
            client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(
                    () -> acmService.deleteCertificate(certificate.getArn(), REGION));
        }
    }
}
