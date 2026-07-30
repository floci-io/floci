package io.github.hectorvent.floci.services.apigateway;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiGatewayExecuteApiHostIntegrationTest {

    private static final String REGION = "us-west-2";
    private static final String AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=test/20260730/" + REGION + "/apigateway/aws4_request";

    private static HttpServer backend;
    private static int backendPort;
    private static String apiId;
    private static String integrationId;

    @BeforeAll
    static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            byte[] response = """
                    {"path":"%s","query":"%s"}
                    """.formatted(exchange.getRequestURI().getRawPath(), query == null ? "" : query)
                    .strip()
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        backend.start();
        backendPort = backend.getAddress().getPort();
    }

    @AfterAll
    static void stopBackend() {
        if (backend != null) {
            backend.stop(0);
        }
    }

    @Test
    @Order(1)
    void createsHttpApiOutsideDefaultRegion() {
        apiId = given()
                .header("Authorization", AUTHORIZATION)
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"execute-api-host-test","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .body("disableExecuteApiEndpoint", equalTo(false))
                .extract().path("apiId");
    }

    @Test
    @Order(2)
    void configuresDefaultAndExplicitStages() {
        createStage("$default");
        createStage("dev");
    }

    @Test
    @Order(3)
    void configuresHttpProxyRoute() {
        integrationId = given()
                .header("Authorization", AUTHORIZATION)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "integrationType":"HTTP_PROXY",
                          "integrationUri":"http://127.0.0.1:%d/backend",
                          "integrationMethod":"GET",
                          "payloadFormatVersion":"1.0"
                        }
                        """.formatted(backendPort))
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .body("integrationId", notNullValue())
                .extract().path("integrationId");

        given()
                .header("Authorization", AUTHORIZATION)
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET /accounts","target":"integrations/%s"}
                        """.formatted(integrationId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(4)
    void invokesDefaultStageByHostWithoutAuthorizationAndPreservesQuery() {
        given()
                .header("Host", apiId + ".execute-api.localhost.floci.io:4566")
                .queryParam("tenant", "alpha")
                .when().get("/accounts")
                .then()
                .statusCode(200)
                .body("path", equalTo("/backend"))
                .body("query", equalTo("tenant=alpha"));
    }

    @Test
    @Order(5)
    void invokesExplicitStageByCompatibleHost() {
        given()
                .header("Host", apiId + ".execute-api.localhost.localstack.cloud")
                .when().get("/dev/accounts")
                .then()
                .statusCode(200)
                .body("path", equalTo("/backend"));
    }

    @Test
    @Order(6)
    void disabledExecuteApiEndpointReturnsForbiddenAndCanBeReenabled() {
        given()
                .header("Authorization", AUTHORIZATION)
                .contentType(ContentType.JSON)
                .body("""
                        {"disableExecuteApiEndpoint":true}
                        """)
                .when().patch("/v2/apis/" + apiId)
                .then()
                .statusCode(200)
                .body("disableExecuteApiEndpoint", equalTo(true));

        given()
                .header("Host", apiId + ".execute-api.localhost.floci.io")
                .when().get("/accounts")
                .then()
                .statusCode(403)
                .body("message", equalTo("Forbidden"));

        given()
                .header("Authorization", AUTHORIZATION)
                .contentType(ContentType.JSON)
                .body("""
                        {"disableExecuteApiEndpoint":false}
                        """)
                .when().patch("/v2/apis/" + apiId)
                .then()
                .statusCode(200)
                .body("disableExecuteApiEndpoint", equalTo(false));
    }

    private static void createStage(String stageName) {
        given()
                .header("Authorization", AUTHORIZATION)
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"%s","autoDeploy":true}
                        """.formatted(stageName))
                .when().post("/v2/apis/" + apiId + "/stages")
                .then()
                .statusCode(201);
    }
}
