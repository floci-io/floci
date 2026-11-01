package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.AlbFixtures.createListener;
import static io.github.hectorvent.floci.testing.AlbFixtures.createLoadBalancer;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteLoadBalancer;
import static io.github.hectorvent.floci.testing.AlbFixtures.deleteTargetGroup;
import static io.github.hectorvent.floci.testing.AlbFixtures.registerTarget;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2StickinessIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261019/us-east-1/elasticloadbalancing/aws4_request";

    @Inject
    Vertx vertx;

    private HttpServer first;
    private HttpServer second;
    private String loadBalancerArn;
    private String targetGroupArn;
    private int listenerPort;
    private final AtomicReference<String> unhealthy = new AtomicReference<>();

    @BeforeEach
    void startTargetsAndListener() throws Exception {
        first = backend("first");
        second = backend("second");
        listenerPort = FreePorts.anyInterfacePort();
        loadBalancerArn = createLoadBalancer("sticky-lb");
        targetGroupArn = query("CreateTargetGroup", "Name", "sticky-tg", "Protocol", "HTTP",
                "Port", first.actualPort(), "TargetType", "ip", "HealthCheckPath", "/health",
                "HealthCheckIntervalSeconds", 1, "HealthyThresholdCount", 1, "UnhealthyThresholdCount", 1)
                .xmlPath().getString("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
        registerTarget(targetGroupArn, "127.0.0.1", first.actualPort());
        registerTarget(targetGroupArn, "127.0.0.1", second.actualPort());
        createListener(loadBalancerArn, targetGroupArn, listenerPort);
        await().atMost(Duration.ofSeconds(3)).ignoreExceptions()
                .untilAsserted(() -> assertEquals(200, request(null).statusCode()));
    }

    @AfterEach
    void cleanUp() throws Exception {
        deleteLoadBalancer(loadBalancerArn);
        deleteTargetGroup(targetGroupArn);
        for (HttpServer backend : List.of(first, second)) {
            backend.close().toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void enabledCookiePinsTargetAndPreservesApplicationCookies() {
        enableStickiness(true);
        Response initial = request(null);
        String cookie = initial.getCookie("AWSALB");
        assertNotNull(cookie);
        assertEquals(cookie, initial.getCookie("AWSALBCORS"));
        assertTrue(initial.headers().getValues("Set-Cookie").contains("app=one; Path=/"));
        assertTrue(initial.headers().getValues("Set-Cookie").contains("other=two; Path=/"));
        for (int i = 0; i < 10; i++) {
            Response next = request("AWSALB=" + cookie);
            assertEquals(200, next.statusCode());
            assertEquals(initial.asString(), next.asString());
            assertNotNull(next.getCookie("AWSALB"));
        }
    }

    @Test
    void disabledStickinessKeepsRoundRobinAndDoesNotIssueCookies() {
        Response initial = request(null);
        Response next = request("AWSALB=invalid");
        assertNotEquals(initial.asString(), next.asString());
        assertEquals(null, initial.getCookie("AWSALB"));
        assertEquals(null, next.getCookie("AWSALBCORS"));
    }

    @Test
    void absentAndInvalidCookiesStillUseRoundRobin() {
        enableStickiness(true);
        Response initial = request(null);
        Response next = request("AWSALB=not-a-valid-cookie");
        assertNotEquals(initial.asString(), next.asString());
        assertNotNull(next.getCookie("AWSALB"));
        assertEquals(initial.asString(), request(null).asString());
    }

    @Test
    void deregisteredTargetIsReplacedAndTheCookieIsUpdated() {
        enableStickiness(true);
        Response initial = request(null);
        int removedPort = "first".equals(initial.asString()) ? first.actualPort() : second.actualPort();
        query("DeregisterTargets", "TargetGroupArn", targetGroupArn,
                "Targets.member.1.Id", "127.0.0.1", "Targets.member.1.Port", removedPort);
        Response replacement = request("AWSALB=" + initial.getCookie("AWSALB"));
        assertNotEquals(initial.asString(), replacement.asString());
        assertNotNull(replacement.getCookie("AWSALB"));
        assertEquals(replacement.asString(), request("AWSALB=" + replacement.getCookie("AWSALB")).asString());
    }

    @Test
    void unhealthyTargetDoesNotKeepItsStickyClient() {
        enableStickiness(true);
        Response initial = request(null);
        unhealthy.set(initial.asString());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<String> states = query("DescribeTargetHealth", "TargetGroupArn", targetGroupArn).xmlPath()
                    .getList("DescribeTargetHealthResponse.DescribeTargetHealthResult"
                            + ".TargetHealthDescriptions.member.TargetHealth.State");
            assertTrue(states.contains("unhealthy"));
        });
        Response replacement = request("AWSALB=" + initial.getCookie("AWSALB"));
        assertNotEquals(initial.asString(), replacement.asString());
        assertNotNull(replacement.getCookie("AWSALB"));
    }

    @Test
    void disablingStickinessIgnoresAnExistingCookie() {
        enableStickiness(true);
        Response initial = request(null);
        enableStickiness(false);
        Response next = request("AWSALB=" + initial.getCookie("AWSALB"));
        assertNotEquals(initial.asString(), next.asString());
        assertEquals(null, next.getCookie("AWSALB"));
    }

    @Test
    void targetGroupCrossZoneSettingControlsExistingCookies() {
        enableStickiness(true);
        Response initial = request(null);
        String cookie = initial.getCookie("AWSALB");
        assertNotNull(cookie);
        query("ModifyTargetGroupAttributes", "TargetGroupArn", targetGroupArn,
                "Attributes.member.1.Key", "load_balancing.cross_zone.enabled", "Attributes.member.1.Value", false);
        Response next = request("AWSALB=" + cookie);
        assertNotEquals(initial.asString(), next.asString());
        assertEquals(null, next.getCookie("AWSALB"));
        assertEquals(null, next.getCookie("AWSALBCORS"));

        query("ModifyTargetGroupAttributes", "TargetGroupArn", targetGroupArn,
                "Attributes.member.1.Key", "load_balancing.cross_zone.enabled",
                "Attributes.member.1.Value", "use_load_balancer_configuration");
        Response restored = request("AWSALB=" + cookie);
        assertEquals(initial.asString(), restored.asString());
        assertNotNull(restored.getCookie("AWSALB"));
    }

    private void enableStickiness(boolean enabled) {
        query("ModifyTargetGroupAttributes", "TargetGroupArn", targetGroupArn,
                "Attributes.member.1.Key", "stickiness.enabled", "Attributes.member.1.Value", enabled);
    }

    private HttpServer backend(String body) throws Exception {
        return vertx.createHttpServer().requestHandler(request -> {
            if ("/health".equals(request.path()) && body.equals(unhealthy.get())) {
                request.response().setStatusCode(503).end();
                return;
            }
            request.response().headers().add("Set-Cookie", "app=one; Path=/")
                    .add("Set-Cookie", "other=two; Path=/");
            request.response().end(body);
        }).listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private Response request(String cookie) {
        RequestSpecification request = given().baseUri("http://127.0.0.1").port(listenerPort);
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return request.get("/");
    }

    private static Response query(String action, Object... parameters) {
        RequestSpecification request = given().header("Authorization", AUTH).formParam("Action", action);
        for (int i = 0; i < parameters.length; i += 2) {
            request.formParam(parameters[i].toString(), parameters[i + 1]);
        }
        return request.post("/").then().statusCode(200).extract().response();
    }
}
