package io.github.hectorvent.floci.services.apigatewayv2;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** End-to-end: HTTP API → ALB listener ARN → ELBv2 data plane → registered stub target. */
@QuarkusTest
@TestProfile(ApiGatewayV2AlbIntegrationTest.RealElbV2Profile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayV2AlbIntegrationTest {

    /** Overrides the default {@code elbv2.mock=true} so the listener actually binds. */
    public static class RealElbV2Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.elbv2.mock", "false");
        }
    }

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260520/us-east-1/elasticloadbalancing/aws4_request";

    /** Fixed listener port. Distinct from {@code ElbV2LambdaTargetIntegrationTest} (7780). */
    private static final int LISTENER_PORT = 7792;

    private static HttpServer stub;
    private static int stubPort;

    private static String lbArn;
    private static String tgArn;
    private static String listenerArn;
    private static String vpcLinkId;
    private static String apiId;
    private static String integrationId;

    @BeforeAll
    static void startStubBackend() throws IOException {
        // Ephemeral-port HTTP stub that echoes the requested path. Acts as the
        // "ECS container" sitting behind the ALB.
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            String reqPath = exchange.getRequestURI().getPath();
            byte[] body = ("{\"status\":\"ok\",\"path\":\"" + reqPath + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stub.start();
        stubPort = stub.getAddress().getPort();
    }

    @AfterAll
    static void stopStubBackend() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Test
    @Order(1)
    void createLoadBalancer() {
        lbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "apigw-alb-test")
                .formParam("Type", "application")
                .formParam("Scheme", "internet-facing")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    @Test
    @Order(2)
    void createTargetGroup() {
        tgArn = given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "apigw-alb-tg")
                .formParam("Protocol", "HTTP")
                .formParam("Port", String.valueOf(stubPort))
                .formParam("TargetType", "ip")
                .formParam("HealthCheckPath", "/")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    @Test
    @Order(3)
    void registerStubAsTarget() {
        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", tgArn)
                .formParam("Targets.member.1.Id", "127.0.0.1")
                .formParam("Targets.member.1.Port", String.valueOf(stubPort))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(4)
    void createListenerOnFixedPort() {
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

    @Test
    @Order(5)
    void createVpcLink() {
        vpcLinkId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"alb-vpc-link","subnetIds":["subnet-1"],"securityGroupIds":["sg-1"]}
                        """)
                .when().post("/v2/vpclinks")
                .then()
                .statusCode(201)
                .extract().path("vpcLinkId");
    }

    @Test
    @Order(6)
    void createHttpApi() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"alb-test-api","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .extract().path("apiId");
    }

    @Test
    @Order(7)
    void createAlbIntegration() {
        integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"HTTP_PROXY",
                         "integrationUri":"%s",
                         "connectionType":"VPC_LINK",
                         "connectionId":"%s",
                         "payloadFormatVersion":"1.0",
                         "integrationMethod":"ANY"}
                        """.formatted(listenerArn, vpcLinkId))
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .body("integrationUri", equalTo(listenerArn))
                .body("connectionType", equalTo("VPC_LINK"))
                .extract().path("integrationId");
    }

    @Test
    @Order(8)
    void createHealthRoute() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /health","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(9)
    void httpProxyToStubDirectlyWorksThroughDispatch() {
        // Baseline: bypass the ALB chain and prove the v2 HTTP_PROXY dispatch path
        // works when integrationUri is a plain http://127.0.0.1 URL. If this hangs,
        // it's a pre-existing pipeline issue independent of the ALB resolver.
        // PATCH the integration to bypass the listener, GET execute-api, then PATCH it back.
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationUri":"http://127.0.0.1:%d/probe"}
                        """.formatted(stubPort))
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then()
                .statusCode(200);

        given()
                .when().get("/execute-api/" + apiId + "/$default/health")
                .then()
                .statusCode(200)
                .body("status", equalTo("ok"));

        // Restore the listener-ARN integrationUri for the ALB tests that follow.
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationUri":"%s"}
                        """.formatted(listenerArn))
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then()
                .statusCode(200);
    }

    @Test
    @Order(10)
    void listenerForwardsDirectlyToStub() throws Exception {
        // Verifies the ALB data plane independently of the API Gateway path: hit the
        // listener directly. Polls because server.listen() is async — if the listener
        // never accepts a connection we get a fast ConnectException and retry.
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + LISTENER_PORT + "/health"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        long deadline = System.currentTimeMillis() + 15_000;
        Exception lastErr = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body().contains("\"status\":\"ok\"")) {
                    return;
                }
                lastErr = new IllegalStateException("status=" + resp.statusCode() + " body=" + resp.body());
            } catch (Exception e) {
                lastErr = e;
            }
            Thread.sleep(250);
        }
        Assertions.fail("listener direct probe never succeeded: " + lastErr);
    }

    /** Full HttpAlbIntegration happy path: execute-api → ALB resolver → listener → stub returns 200. */
    @Test
    @Order(11)
    void executeApiReachesStubViaAlb() {
        // Now go through the actual HttpAlbIntegration path: dispatchHttpProxyV2 must
        // detect the listener ARN, resolve it to the bound port, and forward to it.
        Response last = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            last = given().when().get("/execute-api/" + apiId + "/$default/health");
            if (last.statusCode() == 200) {
                last.then()
                        .body("status", equalTo("ok"))
                        .body("path", equalTo("/health"));
                return;
            }
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        Assertions.fail("execute-api → ALB → stub never returned 200; last status="
                + (last != null ? last.statusCode() : "n/a")
                + " body=" + (last != null ? last.body().asString() : "n/a"));
    }

    @Test
    @Order(12)
    void unresolvableListenerArnReturns502() {
        // Replace the integrationUri with a listener ARN that doesn't exist.
        // The ALB resolver must return a clean 502, not crash.
        String ghostArn = "arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/ghost-lb/0000000000000000/0000000000000000";
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationUri":"%s"}
                        """.formatted(ghostArn))
                .when().patch("/v2/apis/" + apiId + "/integrations/" + integrationId)
                .then()
                .statusCode(200);

        given()
                .when().get("/execute-api/" + apiId + "/$default/health")
                .then()
                .statusCode(502);
    }

    @Test
    @Order(90)
    void cleanup() {
        if (integrationId != null) {
            given().when().delete("/v2/apis/" + apiId + "/integrations/" + integrationId);
        }
        if (apiId != null) {
            given().when().delete("/v2/apis/" + apiId);
        }
        if (vpcLinkId != null) {
            given().when().delete("/v2/vpclinks/" + vpcLinkId);
        }
        if (listenerArn != null) {
            given()
                    .formParam("Action", "DeleteListener")
                    .formParam("ListenerArn", listenerArn)
                    .header("Authorization", AUTH)
                    .when().post("/");
        }
        if (lbArn != null) {
            given()
                    .formParam("Action", "DeleteLoadBalancer")
                    .formParam("LoadBalancerArn", lbArn)
                    .header("Authorization", AUTH)
                    .when().post("/");
        }
    }
}
