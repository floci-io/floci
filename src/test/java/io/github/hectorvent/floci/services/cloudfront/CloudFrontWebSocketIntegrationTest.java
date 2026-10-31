package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tunnels WebSocket connections through a distribution whose custom origin is an ALB listener, as
 * CloudFront does on every distribution: viewer, distribution, load balancer and an echo target.
 */
@QuarkusTest
@TestProfile(CloudFrontWebSocketIntegrationTest.AlbOriginProfile.class)
class CloudFrontWebSocketIntegrationTest {

    /** The real ELBv2 data plane, and an origin at 127.0.0.1 that CloudFront would otherwise refuse. */
    public static class AlbOriginProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.elbv2.mock", "false",
                    "floci.services.cloudfront.allowed-private-origin-hosts", "127.0.0.1");
        }
    }

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261101/us-east-1/elasticloadbalancing/aws4_request";
    private static final int LISTENER_PORT = 7797;
    private static final String CACHING_DISABLED = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad";
    private static final String ALL_VIEWER = "216adef6-5c7f-47e4-b989-5492eafa07d3";

    @Inject
    Vertx vertx;

    @Inject
    CloudFrontService cloudFrontService;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private final AtomicReference<String> originCustomHeader = new AtomicReference<>();
    private HttpServer backend;
    private HttpClient client;
    private String loadBalancerArn;
    private String targetGroupArn;
    private String listenerArn;

    @BeforeEach
    void startAlbOrigin() throws Exception {
        backend = vertx.createHttpServer()
                .requestHandler(request -> {
                    originCustomHeader.set(request.getHeader("X-Origin-Verify"));
                    if (!"websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
                        request.response().setStatusCode(400).end("not an upgrade");
                    } else if (!request.path().startsWith("/socket.io/")) {
                        request.response().headers().add("X-Multi", "one").add("X-Multi", "two");
                        request.response().setStatusCode(404).end("upgrade refused");
                    } else {
                        String query = request.query();
                        request.toWebSocket()
                                .onSuccess(socket -> socket.textMessageHandler(
                                        text -> socket.writeTextMessage(query + ":" + text)))
                                .onFailure(err -> request.response().setStatusCode(400).end("bad handshake"));
                    }
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage()
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        client = vertx.createHttpClient();
        loadBalancerArn = createLoadBalancer();
        targetGroupArn = createTargetGroup(backend.actualPort());
        registerTarget(targetGroupArn, backend.actualPort());
        listenerArn = createListener(loadBalancerArn, targetGroupArn);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> given()
                .baseUri("http://127.0.0.1")
                .port(LISTENER_PORT)
            .when()
                .get("/")
            .then()
                .statusCode(400));
    }

    @AfterEach
    void stopAlbOrigin() throws Exception {
        elbv2("DeleteListener", "ListenerArn", listenerArn);
        elbv2("DeleteTargetGroup", "TargetGroupArn", targetGroupArn);
        elbv2("DeleteLoadBalancer", "LoadBalancerArn", loadBalancerArn);
        client.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        backend.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void tunnelsWebSocketThroughDistributionToAlbOrigin() throws Exception {
        Distribution distribution = createDistribution(policyBehavior(ALL_VIEWER));

        WebSocket socket = connect(distribution, "/socket.io/?EIO=4&transport=websocket");
        try {
            assertEquals("EIO=4&transport=websocket:hello", exchange(socket, "hello"));
            assertEquals("EIO=4&transport=websocket:again", exchange(socket, "again"));
        } finally {
            socket.close();
        }
        assertEquals("shared-secret", originCustomHeader.get());
    }

    @Test
    void tunnelsWebSocketWithLegacyCacheSettings() throws Exception {
        Distribution distribution = createDistribution(legacyBehavior());

        WebSocket socket = connect(distribution, "/socket.io/?transport=websocket");
        try {
            // Legacy cache settings forward no query string unless ForwardedValues asks for it.
            assertEquals("null:hello", exchange(socket, "hello"));
        } finally {
            socket.close();
        }
    }

    @Test
    void handshakeFailsWhenPolicyDoesNotForwardWebSocketKey() {
        // AWS requires the origin request policy to forward Sec-WebSocket-Key and -Version.
        Distribution distribution = createDistribution(policyBehavior(null));

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> connect(distribution, "/socket.io/?transport=websocket"));

        UpgradeRejectedException rejected = assertInstanceOf(UpgradeRejectedException.class, failure.getCause());
        assertEquals(400, rejected.getStatus());
    }

    @Test
    void relaysOriginRefusalOfUpgrade() {
        Distribution distribution = createDistribution(policyBehavior(ALL_VIEWER));

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> connect(distribution, "/elsewhere/?transport=websocket"));

        UpgradeRejectedException rejected = assertInstanceOf(UpgradeRejectedException.class, failure.getCause());
        assertEquals(404, rejected.getStatus());
        assertEquals(List.of("one", "two"), rejected.getHeaders().getAll("X-Multi"));
        assertEquals("upgrade refused", rejected.getBody().toString());
    }

    @Test
    void plainRequestThroughDistributionCarriesNoUpgrade() {
        Distribution distribution = createDistribution(policyBehavior(ALL_VIEWER));

        given()
                .header("Host", distribution.getDomainName())
            .when()
                .get("/socket.io/?transport=polling")
            .then()
                .statusCode(400)
                .body(equalTo("not an upgrade"));
    }

    private Distribution createDistribution(DefaultCacheBehavior behavior) {
        Origin origin = new Origin();
        origin.setId("alb");
        origin.setDomainName("127.0.0.1");
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", String.valueOf(LISTENER_PORT));
        customOriginConfig.put("HTTPSPort", "443");
        customOriginConfig.put("OriginProtocolPolicy", "http-only");
        origin.setCustomOriginConfig(customOriginConfig);
        origin.setCustomHeaders(List.of(new LinkedHashMap<>(Map.of(
                "HeaderName", "X-Origin-Verify", "HeaderValue", "shared-secret"))));

        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        Distribution created = cloudFrontService.createDistribution(distribution, Map.of());
        cleanup.register(() -> cloudFrontService.removeDistribution(created.getId()));
        return created;
    }

    private static DefaultCacheBehavior legacyBehavior() {
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("alb");
        behavior.setViewerProtocolPolicy("allow-all");
        return behavior;
    }

    private static DefaultCacheBehavior policyBehavior(String originRequestPolicyId) {
        DefaultCacheBehavior behavior = legacyBehavior();
        behavior.setCachePolicyId(CACHING_DISABLED);
        behavior.setOriginRequestPolicyId(originRequestPolicyId);
        return behavior;
    }

    private WebSocket connect(Distribution distribution, String uri) throws Exception {
        return client.webSocket(new WebSocketConnectOptions()
                        .setHost("127.0.0.1")
                        .setPort(RestAssured.port)
                        .setURI(uri)
                        .putHeader("Host", distribution.getDomainName()))
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
    }

    private static String exchange(WebSocket socket, String text) throws Exception {
        CompletableFuture<String> reply = new CompletableFuture<>();
        socket.textMessageHandler(reply::complete);
        socket.writeTextMessage(text);
        return reply.get(5, TimeUnit.SECONDS);
    }

    private static String createLoadBalancer() {
        return given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "cloudfront-websocket-lb")
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    private static String createTargetGroup(int backendPort) {
        return given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "cloudfront-websocket-tg")
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

    private static void elbv2(String action, String arnParameter, String arn) {
        if (arn == null) {
            return;
        }
        given()
                .formParam("Action", action)
                .formParam(arnParameter, arn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }
}
