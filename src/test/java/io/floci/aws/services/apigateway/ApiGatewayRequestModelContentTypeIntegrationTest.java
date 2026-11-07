package io.floci.aws.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;

@QuarkusTest
class ApiGatewayRequestModelContentTypeIntegrationTest {

    private String apiId;
    private String resourceId;
    private String validatorId;

    @BeforeEach
    void createApi() {
        apiId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "request-model-content-type"))
                .post("/restapis").then().statusCode(201).extract().path("id");
        resourceId = given().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item.find { it.path == '/' }.id");
        validatorId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "body", "validateRequestBody", true,
                        "validateRequestParameters", false))
                .post("/restapis/" + apiId + "/requestvalidators")
                .then().statusCode(201).extract().path("id");
        createModel("Named", "name");
        createModel("Fallback", "fallback");
    }

    @AfterEach
    void deleteApi() {
        if (apiId != null) {
            given().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void unmatchedContentTypeSkipsJsonModelValidation() {
        deploy(Map.of("application/json", "Named"));
        invoke("text/plain", "{\"unexpected\":true}", 200);
        invoke("text/plain", "not json", 200);
        invoke("application/json", "{\"unexpected\":true}", 400);
        invoke("application/json", "{\"name\":\"present\"}", 200);
    }

    @Test
    void defaultModelValidatesAnUnmatchedContentType() {
        deploy(Map.of("$default", "Fallback"));
        invoke("text/plain", "{\"unexpected\":true}", 400);
        invoke("text/plain", "{\"fallback\":\"present\"}", 200);
        invoke("application/json", "{\"unexpected\":true}", 400);
        invoke("application/json", "{\"fallback\":\"present\"}", 200);
    }

    @Test
    void exactModelTakesPrecedenceOverDefaultModel() {
        deploy(Map.of("application/json", "Named", "$default", "Fallback"));
        invoke("application/json", "{\"name\":\"present\"}", 200);
        invoke("application/json", "{\"fallback\":\"present\"}", 400);
        invoke("text/plain", "{\"name\":\"present\"}", 400);
        invoke("text/plain", "{\"fallback\":\"present\"}", 200);
    }

    @Test
    void mediaTypeParametersKeepTheMatchingModel() {
        deploy(Map.of("application/json", "Named"));
        invoke("application/json; charset=utf-8", "{\"unexpected\":true}", 400);
        invoke("application/json; charset=utf-8", "{\"name\":\"present\"}", 200);
    }

    private void createModel(String name, String requiredProperty) {
        String schema = """
                {"type":"object","required":["%s"],"properties":{"%s":{"type":"string"}}}
                """.formatted(requiredProperty, requiredProperty);
        given().contentType(ContentType.JSON)
                .body(Map.of("name", name, "contentType", "application/json", "schema", schema))
                .post("/restapis/" + apiId + "/models").then().statusCode(201);
    }

    private void deploy(Map<String, String> models) {
        String method = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST";
        given().contentType(ContentType.JSON)
                .body(Map.of("authorizationType", "NONE", "requestValidatorId", validatorId,
                        "requestModels", models))
                .put(method).then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body(Map.of("statusCode", "200"))
                .put(method + "/responses/200").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body(Map.of("type", "MOCK", "passthroughBehavior", "WHEN_NO_MATCH",
                        "requestTemplates", Map.of(
                                "application/json", "{\"statusCode\":200}",
                                "text/plain", "{\"statusCode\":200}")))
                .put(method + "/integration").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body(Map.of("statusCode", "200",
                        "responseTemplates", Map.of("application/json", "{\"ok\":true}")))
                .put(method + "/integration/responses/200").then().statusCode(201);
        given().contentType(ContentType.JSON)
                .body(Map.of("stageName", "test"))
                .post("/restapis/" + apiId + "/deployments").then().statusCode(201);
    }

    private void invoke(String contentType, String body, int expectedStatus) {
        given().contentType(contentType).body(body)
                .post("/execute-api/" + apiId + "/test/")
                .then().statusCode(expectedStatus);
    }
}
