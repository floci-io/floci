package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.AlbFixtures.createListener;
import static io.github.hectorvent.floci.testing.AlbFixtures.createLoadBalancer;
import static io.github.hectorvent.floci.testing.AlbFixtures.createTargetGroup;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteListener;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteLoadBalancer;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteTargetGroup;
import static io.github.hectorvent.floci.testing.AlbFixtures.exchange;
import static io.github.hectorvent.floci.testing.AlbFixtures.registerTarget;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;

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

    private static final int LISTENER_PORT = 7797;
    private static final String CACHING_DISABLED = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad";
    private static final String ALL_VIEWER = "216adef6-5c7f-47e4-b989-5492eafa07d3";

    @Inject
    Vertx vertx;

    @Inject
    CloudFrontService cloudFrontService;

    @InjectSpy
    ServiceConfigAccess serviceConfigAccess;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private final AtomicReference<String> originCustomHeader = new AtomicReference<>();
    private final AtomicInteger originUpgrades = new AtomicInteger();
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
                        return;
                    }
                    originUpgrades.incrementAndGet();
                    if (request.path().startsWith("/stall/")) {
                        // Refuses the upgrade, then never finishes the body.
                        request.response().setChunked(true).setStatusCode(503).write("partial");
                    } else if (!request.path().startsWith("/socket.io/")) {
                        request.response().headers().add("X-Multi", "one").add("X-Multi", "two");
                        request.response().setStatusCode(404).end("upgrade refused");
                    } else {
                        String query = request.query();
                        // A handshake without Sec-WebSocket-Key is answered 400 by Vert.x itself.
                        request.toWebSocket().onSuccess(socket -> socket.textMessageHandler(
                                text -> socket.writeTextMessage(query + ":" + text)));
                    }
                })
                .listen(0, "127.0.0.1")
                .toCompletionStage()
                .toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        client = vertx.createHttpClient();
        loadBalancerArn = createLoadBalancer("cloudfront-websocket-lb");
        targetGroupArn = createTargetGroup("cloudfront-websocket-tg", "ip", backend.actualPort());
        registerTarget(targetGroupArn, "127.0.0.1", backend.actualPort());
        listenerArn = createListener(loadBalancerArn, targetGroupArn, LISTENER_PORT);
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
        deleteListener(listenerArn);
        deleteTargetGroup(targetGroupArn);
        deleteLoadBalancer(loadBalancerArn);
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

    @Test
    void endsRefusalWhoseBodyStallsPastOriginReadTimeout() {
        Distribution distribution = createDistribution(policyBehavior(ALL_VIEWER), 1);

        // A refusal that never ends would leave connect() waiting past its 5 s limit (a TimeoutException).
        assertThrows(ExecutionException.class, () -> connect(distribution, "/stall/?transport=websocket"));
        assertEquals(1, originUpgrades.get());
    }

    @Test
    void disabledCloudFrontDoesNotTunnel() {
        Distribution distribution = createDistribution(policyBehavior(ALL_VIEWER));
        doReturn(false).when(serviceConfigAccess).isEnabled("cloudfront");

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> connect(distribution, "/socket.io/?transport=websocket"));

        assertInstanceOf(UpgradeRejectedException.class, failure.getCause());
        assertEquals(0, originUpgrades.get());
    }

    private Distribution createDistribution(DefaultCacheBehavior behavior) {
        return createDistribution(behavior, null);
    }

    private Distribution createDistribution(DefaultCacheBehavior behavior, Integer originReadTimeoutSeconds) {
        Origin origin = new Origin();
        origin.setId("alb");
        origin.setDomainName("127.0.0.1");
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", String.valueOf(LISTENER_PORT));
        customOriginConfig.put("HTTPSPort", "443");
        customOriginConfig.put("OriginProtocolPolicy", "http-only");
        if (originReadTimeoutSeconds != null) {
            customOriginConfig.put("OriginReadTimeout", String.valueOf(originReadTimeoutSeconds));
        }
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

}
