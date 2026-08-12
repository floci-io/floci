package io.github.hectorvent.floci.services.elbv2;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

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
                .requestHandler(request -> request.response().end(request.getHeader("Host")))
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
