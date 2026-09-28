package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.ConfiguredHostnameProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

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
                .when().get("/restapis/" + apiId + "/test/_user_request_/ping")
                .then().statusCode(200)
                .body("route", equalTo("ping"));
    }

    private static String spec() {
        ObjectNode root = JSON.createObjectNode();
        root.put("openapi", "3.0.1");
        root.putObject("info").put("title", "REST virtual host").put("version", "1.0");
        ObjectNode paths = root.putObject("paths");
        addMockMethod(paths, "/ping", "ping");
        addMockMethod(paths, "/deep/path", "nested");
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
