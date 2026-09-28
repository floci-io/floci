package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.ConfiguredHostnameProfile;
import io.github.hectorvent.floci.testutil.ExecuteApiRequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(ConfiguredHostnameProfile.class)
class ApiGatewayRestExecuteApiHostIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void routesRestApiVirtualHostToDeployedMethods() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec())
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201);

        given()
                .when().get("/execute-api/" + apiId + "/test/ping")
                .then().statusCode(200)
                .body("route", equalTo("ping"));

        given()
                .header("Host", apiId + ".execute-api.localhost.floci.io:4566")
                .when().get("/test/ping")
                .then().statusCode(200)
                .body("route", equalTo("ping"));

        given()
                .header("Host", apiId + ".execute-api.localhost.floci.io:4566")
                .when().get("/test/deep/path")
                .then().statusCode(200)
                .body("route", equalTo("nested"));

        given()
                .header("Host", apiId + ".execute-api.localhost.floci.io:4566")
                .when().get("/test/@connections/demo")
                .then().statusCode(200)
                .body("route", equalTo("rest-connection"));

        given()
                .when().get("/execute-api/" + apiId + "/test/@connections/demo")
                .then().statusCode(200)
                .body("route", equalTo("rest-connection"));

        given()
                .when().get("/restapis/" + apiId + "/test/_user_request_/ping")
                .then().statusCode(200)
                .body("route", equalTo("ping"));
    }

    @Test
    void signedRestHostRequestUsesOriginalPath() throws Exception {
        String apiId = given()
                .contentType(ContentType.JSON)
                .queryParam("mode", "import")
                .body(spec())
                .when().post("/restapis")
                .then().statusCode(201)
                .extract().path("id");
        String methodPath = "/restapis/" + apiId + "/resources/" + resourceId(apiId, "/ping")
                + "/methods/GET";
        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/authorizationType\","
                        + "\"value\":\"AWS_IAM\"}]}")
                .when().patch(methodPath)
                .then().statusCode(200)
                .body("authorizationType", equalTo("AWS_IAM"));
        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"signed\"}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201);

        String host = apiId + ".execute-api.localhost.floci.io:4566";
        String path = "/signed/ping";
        given()
                .header("Host", host)
                .when().get(path)
                .then().statusCode(403);

        Map<String, String> signedHeaders = ExecuteApiRequestSigner.signedHeaders(
                "GET", path, Map.of(), host, null, "test", "test", "us-east-1", Instant.now());
        given()
                .header("Host", host)
                .headers(signedHeaders)
                .when().get(path)
                .then().statusCode(200)
                .body("route", equalTo("ping"));
    }

    @Test
    void websocketConnectionManagementStillUsesWebSocketApi() {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"connection-routing\",\"protocolType\":\"WEBSOCKET\","
                        + "\"routeSelectionExpression\":\"$request.body.action\"}")
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");
        given()
                .contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\"}")
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"rest-id-collision\",\"tags\":{\"floci:override-id\":\"" + apiId + "\"}}")
                .when().post("/restapis")
                .then().statusCode(201)
                .body("id", equalTo(apiId));

        given()
                .when().get("/execute-api/" + apiId + "/test/@connections/missing")
                .then().statusCode(410);

        given()
                .header("Host", apiId + ".execute-api.localhost.floci.io:4566")
                .when().get("/test/@connections/missing")
                .then().statusCode(410);
    }

    private static String resourceId(String apiId, String path) throws Exception {
        String resources = given()
                .when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200)
                .extract().asString();
        for (JsonNode resource : JSON.readTree(resources).path("item")) {
            if (path.equals(resource.path("path").asText())) {
                return resource.path("id").asText();
            }
        }
        throw new AssertionError("Imported resource was not found: " + path);
    }

    private static String spec() {
        ObjectNode root = JSON.createObjectNode();
        root.put("openapi", "3.0.1");
        root.putObject("info").put("title", "REST virtual host").put("version", "1.0");
        ObjectNode paths = root.putObject("paths");
        addMockMethod(paths, "/ping", "ping");
        addMockMethod(paths, "/deep/path", "nested");
        addMockMethod(paths, "/@connections/demo", "rest-connection");
        return root.toString();
    }

    private static void addMockMethod(ObjectNode paths, String path, String route) {
        ObjectNode operation = paths.putObject(path).putObject("get");
        operation.putObject("responses").putObject("200").put("description", "ok");
        ObjectNode integration = operation.putObject("x-amazon-apigateway-integration");
        integration.put("type", "mock");
        integration.putObject("requestTemplates").put("application/json", "{\"statusCode\":200}");
        integration.putObject("responses").putObject("default")
                .put("statusCode", "200")
                .putObject("responseTemplates").put("application/json", "{\"route\":\"" + route + "\"}");
    }
}
