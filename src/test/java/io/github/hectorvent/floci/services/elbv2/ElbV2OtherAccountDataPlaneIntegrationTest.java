package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.github.hectorvent.floci.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A listener port carries no credentials, so a load balancer outside the default account must still serve. */
@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2OtherAccountDataPlaneIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20261101/us-east-1/elasticloadbalancing/aws4_request";

    @Inject
    Vertx vertx;

    @Inject
    ElbV2DataPlane dataPlane;

    @Inject
    ElbV2Service elbV2Service;

    @Test
    void listenerForwardsAndIsRestoredForItsOwnAccount() throws Exception {
        HttpServer backend = vertx.createHttpServer()
                .requestHandler(request -> request.response().end("other-account"))
                .listen(0, "127.0.0.1")
                .toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        int listenerPort = FreePorts.anyInterfacePort();
        String loadBalancerArn = null;
        String targetGroupArn = null;
        String listenerArn = null;
        try {
            loadBalancerArn = query().formParam("Action", "CreateLoadBalancer")
                    .formParam("Name", "other-account-lb")
                    .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                    .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                    .post("/").then().statusCode(200).extract()
                    .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
            targetGroupArn = query().formParam("Action", "CreateTargetGroup")
                    .formParam("Name", "other-account-tg")
                    .formParam("Protocol", "HTTP")
                    .formParam("Port", backend.actualPort())
                    .formParam("TargetType", "ip")
                    .formParam("HealthCheckEnabled", "false")
                    .post("/").then().statusCode(200).extract()
                    .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
            query().formParam("Action", "RegisterTargets")
                    .formParam("TargetGroupArn", targetGroupArn)
                    .formParam("Targets.member.1.Id", "127.0.0.1")
                    .formParam("Targets.member.1.Port", backend.actualPort())
                    .post("/").then().statusCode(200);
            listenerArn = query().formParam("Action", "CreateListener")
                    .formParam("LoadBalancerArn", loadBalancerArn)
                    .formParam("Protocol", "HTTP")
                    .formParam("Port", listenerPort)
                    .formParam("DefaultActions.member.1.Type", "forward")
                    .formParam("DefaultActions.member.1.TargetGroupArn", targetGroupArn)
                    .post("/").then().statusCode(200).extract()
                    .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");

            awaitForwarded(listenerPort);

            assertTrue(dataPlane.removeListener(listenerArn, listenerPort, () -> true));
            elbV2Service.restorePersistedRuntime();

            awaitForwarded(listenerPort);
        } finally {
            delete("DeleteListener", "ListenerArn", listenerArn);
            delete("DeleteTargetGroup", "TargetGroupArn", targetGroupArn);
            delete("DeleteLoadBalancer", "LoadBalancerArn", loadBalancerArn);
            backend.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    private static void awaitForwarded(int listenerPort) {
        await().atMost(Duration.ofSeconds(5)).ignoreExceptions().untilAsserted(() -> given()
                .baseUri("http://127.0.0.1").port(listenerPort)
                .get("/").then().statusCode(200).body(equalTo("other-account")));
    }

    private static RequestSpecification query() {
        return given().header("Authorization", AUTH);
    }

    private static void delete(String action, String arnParameter, String arn) {
        if (arn != null) {
            query().formParam("Action", action).formParam(arnParameter, arn).post("/").then().statusCode(200);
        }
    }
}
