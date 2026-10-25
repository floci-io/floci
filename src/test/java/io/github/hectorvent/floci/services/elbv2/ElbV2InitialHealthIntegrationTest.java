package io.github.hectorvent.floci.services.elbv2;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(ElbV2InitialHealthIntegrationTest.RealHealthChecks.class)
class ElbV2InitialHealthIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261025/us-east-1/elasticloadbalancing/aws4_request";

    public static class RealHealthChecks implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.elbv2.mock", "false");
        }
    }

    @Test
    void registeredTargetBecomesHealthyAfterOneActualHttpProbe() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            probes.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        backend.start();
        String groupArn = null;
        try {
            groupArn = given()
                    .header("Authorization", AUTH)
                    .formParam("Action", "CreateTargetGroup")
                    .formParam("Name", "initial-health-wire")
                    .formParam("Protocol", "HTTP")
                    .formParam("Port", backend.getAddress().getPort())
                    .formParam("VpcId", Ec2Service.defaultVpcId("us-east-1"))
                    .formParam("TargetType", "ip")
                    .formParam("HealthCheckIntervalSeconds", 300)
                    .formParam("HealthyThresholdCount", 10)
                    .when().post("/")
                    .then().statusCode(200)
                    .extract().path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
            given()
                    .header("Authorization", AUTH)
                    .formParam("Action", "RegisterTargets")
                    .formParam("TargetGroupArn", groupArn)
                    .formParam("Targets.member.1.Id", "127.0.0.1")
                    .formParam("Targets.member.1.Port", backend.getAddress().getPort())
                    .when().post("/")
                    .then().statusCode(200);

            String registeredGroupArn = groupArn;
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> given()
                    .header("Authorization", AUTH)
                    .formParam("Action", "DescribeTargetHealth")
                    .formParam("TargetGroupArn", registeredGroupArn)
                    .when().post("/")
                    .then().statusCode(200)
                    .body("DescribeTargetHealthResponse.DescribeTargetHealthResult.TargetHealthDescriptions.member.TargetHealth.State",
                            equalTo("healthy"))
                    .body("DescribeTargetHealthResponse.DescribeTargetHealthResult.TargetHealthDescriptions.member.TargetHealth.Reason.size()",
                            equalTo(0)));
            assertEquals(1, probes.get(), "Initial admission must not wait for the recovery threshold");
        } finally {
            try {
                if (groupArn != null) {
                    given()
                            .header("Authorization", AUTH)
                            .formParam("Action", "DeleteTargetGroup")
                            .formParam("TargetGroupArn", groupArn)
                            .when().post("/")
                            .then().statusCode(200);
                }
            } finally {
                backend.stop(0);
            }
        }
    }
}
