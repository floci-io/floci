package io.floci.aws.services.apigatewayv2;

import io.restassured.http.ContentType;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;

/** A deployed HTTP API whose one route is an {@code HTTP_PROXY} integration on a test's fixture backend. */
public final class HttpProxyApiFixtures {

    private HttpProxyApiFixtures() {
    }

    /**
     * Creates an HTTP API, {@code apiFields} appended to its CreateApi body, with the auto-deployed
     * stage {@code stageName} and a {@code GET path} route to an {@code HTTP_PROXY} integration on
     * {@code http://127.0.0.1:backendPort path}; returns its id.
     */
    public static String createProxyApi(String name, String apiFields, String stageName, int backendPort, String path) {
        return createProxyApi(name, apiFields, stageName, Map.of(), "GET", backendPort, path);
    }

    /**
     * Creates an HTTP API as {@link #createProxyApi(String, String, String, int, String)} does, with
     * {@code stageVariables} on its stage and a {@code routeMethod path} route; returns its id.
     */
    public static String createProxyApi(String name, String apiFields, String stageName,
                                        Map<String, String> stageVariables, String routeMethod, int backendPort,
                                        String path) {
        String apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"protocolType\":\"HTTP\"" + apiFields + "}")
                .when().post("/v2/apis")
                .then().statusCode(201)
                .extract().path("apiId");
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("stageName", stageName);
        stage.put("autoDeploy", true);
        if (!stageVariables.isEmpty()) {
            stage.put("stageVariables", stageVariables);
        }
        given()
                .contentType(ContentType.JSON)
                .body(stage)
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201);
        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("{\"integrationType\":\"HTTP_PROXY\",\"integrationUri\":\"http://127.0.0.1:"
                        + backendPort + path + "\",\"payloadFormatVersion\":\"1.0\"}")
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then().statusCode(201)
                .extract().path("integrationId");
        given()
                .contentType(ContentType.JSON)
                .body("{\"routeKey\":\"" + routeMethod + " " + path + "\",\"target\":\"integrations/"
                        + integrationId + "\"}")
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201);
        return apiId;
    }
}
