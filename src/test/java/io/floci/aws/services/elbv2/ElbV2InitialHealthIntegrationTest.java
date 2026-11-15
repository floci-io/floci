package io.floci.aws.services.elbv2;

import com.sun.net.httpserver.HttpServer;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.RealElbV2DataPlaneProfile;
import io.floci.aws.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.floci.aws.testing.AlbFixtures.createListener;
import static io.floci.aws.testing.AlbFixtures.createLoadBalancer;
import static io.floci.aws.testing.AlbFixtures.deleteLoadBalancer;
import static io.floci.aws.testing.AlbFixtures.deleteTargetGroup;
import static io.floci.aws.testing.AlbFixtures.registerTarget;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
class ElbV2InitialHealthIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261025/us-east-1/elasticloadbalancing/aws4_request";

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
            groupArn = createTargetGroup("initial-health-wire", backend.getAddress().getPort(),
                    "HealthyThresholdCount", 10);
            given()
                    .header("Authorization", AUTH)
                    .formParam("Action", "RegisterTargets")
                    .formParam("TargetGroupArn", groupArn)
                    .formParam("Targets.member.1.Id", "127.0.0.1")
                    .formParam("Targets.member.1.Port", backend.getAddress().getPort())
                    .when().post("/")
                    .then().statusCode(200);

            String registeredGroupArn = groupArn;
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertEquals(List.of("healthy"), targetHealth(registeredGroupArn, "State"));
                assertEquals(List.of(), targetHealth(registeredGroupArn, "Reason"));
            });
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

    @Test
    void initialTargetGetsNoRequestsWhileAnotherTargetIsHealthy() throws Exception {
        HttpServer serving = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serving.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        serving.start();
        int notListening = FreePorts.loopbackIpv4Port();
        int listenerPort = FreePorts.anyInterfacePort();
        String loadBalancerArn = null;
        String groupArn = null;
        try {
            loadBalancerArn = createLoadBalancer("initial-health-routing");
            groupArn = createTargetGroup("initial-health-routing", serving.getAddress().getPort(),
                    "UnhealthyThresholdCount", 10);
            registerTarget(groupArn, "127.0.0.1", serving.getAddress().getPort());
            createListener(loadBalancerArn, groupArn, listenerPort);
            String registeredGroupArn = groupArn;
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals(List.of("healthy"), targetHealth(registeredGroupArn, "State")));

            // A replacement that is not listening yet stays initial: one failed probe is below the threshold.
            registerTarget(groupArn, "127.0.0.1", notListening);
            assertThat(targetHealth(groupArn, "State"), containsInAnyOrder("healthy", "initial"));
            for (int i = 0; i < 6; i++) {
                given().baseUri("http://127.0.0.1").port(listenerPort)
                        .when().get("/")
                        .then().statusCode(200);
            }
        } finally {
            try {
                if (loadBalancerArn != null) {
                    deleteLoadBalancer(loadBalancerArn);
                }
                if (groupArn != null) {
                    deleteTargetGroup(groupArn);
                }
            } finally {
                serving.stop(0);
            }
        }
    }

    /** An HTTP ip target group probed every 300 s, with {@code threshold} set to {@code value}. */
    private static String createTargetGroup(String name, int port, String threshold, int value) {
        return given()
                .header("Authorization", AUTH)
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", name)
                .formParam("Protocol", "HTTP")
                .formParam("Port", port)
                .formParam("VpcId", Ec2Service.defaultVpcId("us-east-1"))
                .formParam("TargetType", "ip")
                .formParam("HealthCheckIntervalSeconds", 300)
                .formParam(threshold, value)
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    /** One {@code TargetHealth} field, such as {@code State} or {@code Reason}, for each registered target. */
    private static List<String> targetHealth(String groupArn, String field) {
        return given()
                .header("Authorization", AUTH)
                .formParam("Action", "DescribeTargetHealth")
                .formParam("TargetGroupArn", groupArn)
                .when().post("/")
                .then().statusCode(200)
                .extract().xmlPath().getList("DescribeTargetHealthResponse.DescribeTargetHealthResult"
                        + ".TargetHealthDescriptions.member.TargetHealth." + field);
    }
}
