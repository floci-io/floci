package io.github.hectorvent.floci.services.apigateway;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
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
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(ApiGatewayExecuteApiHostIntegrationTest.ConfiguredHostnameProfile.class)
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
    void invokesRegionBearingConfiguredHost() {
        given()
                .header("Host", apiId + ".execute-api." + REGION + ".floci:4566")
                .when().get("/accounts")
                .then()
                .statusCode(200)
                .body("path", equalTo("/backend"));
    }

    @Test
    @Order(7)
    void createsRestApiWithSameIdentifier() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"rest-routing-collision","tags":{"floci:override-id":"%s"}}
                        """.formatted(apiId))
                .when().post("/restapis")
                .then()
                .statusCode(201)
                .body("id", equalTo(apiId));

        String rootId = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then()
                .statusCode(200)
                .extract().path("item[0].id");

        String resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"accounts\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"responseParameters\":{}}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/responses/200")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"type":"MOCK","requestTemplates":{"application/json":"{\\"statusCode\\":200}"}}
                        """)
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "selectionPattern":"",
                          "responseTemplates":{"application/json":"{\\"source\\":\\"rest\\"}"}
                        }
                        """)
                .when().put("/restapis/" + apiId + "/resources/" + resourceId
                        + "/methods/GET/integration/responses/200")
                .then()
                .statusCode(201);

        String deploymentId = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"routing collision regression\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then()
                .statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"dev","deploymentId":"%s"}
                        """.formatted(deploymentId))
                .when().post("/restapis/" + apiId + "/stages")
                .then()
                .statusCode(201);
    }

    @Test
    @Order(8)
    void directPathPrefersRestApiWhenIdentifiersCollide() {
        given()
                .when().get("/execute-api/" + apiId + "/dev/accounts")
                .then()
                .statusCode(200)
                .body("source", equalTo("rest"));
    }

    @Test
    @Order(9)
    void hostPathRetainsHttpApiRoutingWhenIdentifiersCollide() {
        given()
                .header("Host", apiId + ".execute-api.localhost.floci.io")
                .when().get("/dev/accounts")
                .then()
                .statusCode(200)
                .body("path", equalTo("/backend"));
    }

    @Test
    @Order(10)
    void disabledExecuteApiEndpointReturnsNotFoundAndCanBeReenabled() {
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
                .statusCode(404)
                .body("message", equalTo("Not Found"));

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

    /**
     * The direct {@code /execute-api/{apiId}/{stage}/...} form is the same execute-api endpoint as
     * the {@code *.execute-api.localhost.*} host, so {@code disableExecuteApiEndpoint} has to reject
     * there too — otherwise the two entry points disagree about whether the API is invokable.
     *
     * <p>Uses its own HTTP API rather than the shared {@code apiId}: by this point the shared one has
     * a REST API with a colliding identifier (see {@link #directPathPrefersRestApiWhenIdentifiersCollide}),
     * so its direct path resolves to REST and never reaches the v2 dispatch this guards.
     */
    @Test
    @Order(11)
    void disabledExecuteApiEndpointAlsoRejectsTheDirectPath() {
        String isolatedApiId = given()
                .header("Authorization", AUTHORIZATION)
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"execute-api-direct-disable-test","protocolType":"HTTP",
                         "disableExecuteApiEndpoint":true}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("disableExecuteApiEndpoint", equalTo(true))
                .extract().path("apiId");

        given()
                .when().get("/execute-api/" + isolatedApiId + "/dev/accounts")
                .then()
                .statusCode(404)
                .body("message", equalTo("Not Found"));
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

    public static class ConfiguredHostnameProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.hostname", "floci");
        }
    }
}
