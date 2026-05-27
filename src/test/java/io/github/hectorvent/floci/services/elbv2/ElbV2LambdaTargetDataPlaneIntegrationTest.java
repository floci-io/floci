package io.github.hectorvent.floci.services.elbv2;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

/**
 * End-to-end test for the ALB (ELBv2) → Lambda target data plane.
 * Sends a real HTTP request to the listener port and verifies the
 * Lambda's response shape (statusCode, headers, body) is returned
 * to the HTTP client. Guards against regressions such as blocking
 * the Vert.x event loop while invoking Lambda, which would deadlock
 * against the Lambda Runtime API and surface as a function timeout.
 *
 * The default test profile mocks the ELBv2 data plane, so this test
 * disables that override to exercise the real listener.
 */
@QuarkusTest
@TestProfile(ElbV2LambdaTargetDataPlaneIntegrationTest.RealElbV2DataPlaneProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2LambdaTargetDataPlaneIntegrationTest {

    public static final class RealElbV2DataPlaneProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.elbv2.mock", "false");
        }
    }

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/elasticloadbalancing/aws4_request";
    private static final String FN = "alb-target-dataplane-fn";
    private static final int LISTENER_PORT = 7782;

    private static String lbArn;
    private static String tgArn;
    private static String listenerArn;
    private static String functionArn;

    private static String makeZipBase64() throws Exception {
        // Matches the reproduction script in the bug report (Python 3.11 / index.handler).
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("index.py"));
            zos.write((
                "def handler(event, context):\n" +
                "    return {\"statusCode\": 200, \"body\": \"ok\"}\n"
            ).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    @Test
    @Order(1)
    void createLambdaFunctionWithCode() throws Exception {
        String zipB64 = makeZipBase64();
        functionArn = given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "python3.14",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Timeout": 30,
                    "Code": {
                        "ZipFile": "%s"
                    }
                }
                """.formatted(FN, zipB64))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .body("FunctionName", equalTo(FN))
            .extract()
            .path("FunctionArn");
    }

    @Test
    @Order(2)
    void createLoadBalancer() {
        lbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "alb-dataplane-lb")
                .formParam("Type", "application")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    @Test
    @Order(3)
    void createTargetGroupAndRegisterLambda() {
        tgArn = given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "alb-dataplane-tg")
                .formParam("TargetType", "lambda")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");

        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", tgArn)
                .formParam("Targets.member.1.Id", functionArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(4)
    void createListener() {
        listenerArn = given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", String.valueOf(LISTENER_PORT))
                .formParam("DefaultActions.member.1.Type", "forward")
                .formParam("DefaultActions.member.1.TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
    }

    /**
     * The actual regression test: an HTTP request that hits the listener port
     * must be forwarded to the Lambda and the Lambda's response must be returned
     * to the HTTP client. If `invokeLambdaTarget` blocks the Vert.x event loop
     * (the pre-fix behaviour), the Lambda Runtime API hosted on the same event
     * loop is starved and the function times out — this assertion catches that.
     */
    @Test
    @Order(5)
    void httpRequestThroughListenerInvokesLambda() {
        given()
                .baseUri("http://localhost")
                .port(LISTENER_PORT)
                .contentType("text/plain")
                .body("hello")
            .when()
                .post("/check")
            .then()
                .statusCode(200)
                .body(equalTo("ok"));
    }

    /**
     * Cleanup. Lives as the highest-ordered @Test rather than {@code @AfterAll} because
     * Quarkus tears the test endpoint down before JUnit's @AfterAll runs, so HTTP-based
     * cleanup must happen inside the test phase. JUnit 5 still runs subsequent @Test
     * methods after a failed one, so this executes even when earlier steps fail; null
     * guards keep it safe when a resource was never created.
     */
    @Test
    @Order(Integer.MAX_VALUE)
    void cleanup() {
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
        if (lbArn != null) {
            given()
                    .formParam("Action", "DeleteLoadBalancer")
                    .formParam("LoadBalancerArn", lbArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
        given()
            .delete("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(anyOf(equalTo(200), equalTo(204), equalTo(404)));
    }
}
