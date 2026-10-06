package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class ApiGatewayResourceMethodsIntegrationTest {

    private String apiId;
    private String rootId;
    private String resourceId;

    @BeforeEach
    void setup() {
        apiId = given().contentType(ContentType.JSON).body("{\"name\":\"resource-methods\"}")
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        rootId = given().when().get(resourcesPath()).then().statusCode(200).extract().path("item[0].id");
        resourceId = given().contentType(ContentType.JSON).body("{\"pathPart\":\"widgets\"}")
                .when().post(resourcesPath() + "/" + rootId)
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON).body("""
                {"authorizationType":"NONE","requestParameters":{"method.request.header.X-Trace":true},
                 "requestModels":{"application/json":"Empty"}}
                """)
                .when().put(methodPath("GET")).then().statusCode(201);
        given().contentType(ContentType.JSON).body("{\"authorizationType\":\"AWS_IAM\",\"apiKeyRequired\":true}")
                .when().put(methodPath("POST")).then().statusCode(201);
        given().contentType(ContentType.JSON).body("""
                {"responseParameters":{"method.response.header.X-Trace":true}}
                """)
                .when().put(methodPath("GET") + "/responses/200").then().statusCode(201);
        given().contentType(ContentType.JSON).body("""
                {"type":"HTTP","httpMethod":"POST","uri":"https://example.invalid/widgets",
                 "timeoutInMillis":12000,"requestTemplates":{"application/json":"{}"},
                 "requestParameters":{"integration.request.header.X-Trace":"method.request.header.X-Trace"}}
                """)
                .when().put(methodPath("GET") + "/integration").then().statusCode(201);
    }

    @AfterEach
    void cleanup() {
        if (apiId != null) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    @Test
    void collectionEmbedReturnsMethodAndIntegrationMetadata() {
        given().queryParam("embed", "methods").when().get(resourcesPath())
                .then().statusCode(200)
                .body("item.find { it.id == '" + resourceId + "' }.resourceMethods.GET.httpMethod", equalTo("GET"))
                .body("item.find { it.id == '" + resourceId + "' }.resourceMethods.GET.authorizationType", equalTo("NONE"))
                .body("item.find { it.id == '" + resourceId + "' }.resourceMethods.POST.authorizationType", equalTo("AWS_IAM"))
                .body("item.find { it.id == '" + resourceId + "' }.resourceMethods.POST.apiKeyRequired", equalTo(true))
                .body("item.find { it.id == '" + resourceId + "' }.resourceMethods.GET.methodIntegration.uri",
                        equalTo("https://example.invalid/widgets"));
    }

    @Test
    void individualEmbedPreservesStoredRequestAndResponseConfiguration() {
        given().queryParam("embed", "methods").when().get(resourcePath())
                .then().statusCode(200)
                .body("resourceMethods.GET.requestParameters.'method.request.header.X-Trace'", equalTo(true))
                .body("resourceMethods.GET.requestModels.'application/json'", equalTo("Empty"))
                .body("resourceMethods.GET.methodResponses.'200'.statusCode", equalTo("200"))
                .body("resourceMethods.GET.methodResponses.'200'.responseParameters.'method.response.header.X-Trace'",
                        equalTo(true))
                .body("resourceMethods.GET.methodIntegration.timeoutInMillis", equalTo(12000))
                .body("resourceMethods.GET.methodIntegration.requestTemplates.'application/json'", equalTo("{}"))
                .body("resourceMethods.GET.methodIntegration.requestParameters.'integration.request.header.X-Trace'",
                        equalTo("method.request.header.X-Trace"));
    }

    @Test
    void ordinaryReadsReturnMethodNamesWithoutErasingStoredMetadata() {
        given().when().get(resourcesPath()).then().statusCode(200)
                .body("item.find { it.id == '" + resourceId + "' }.resourceMethods.GET", anEmptyMap())
                .body("item.find { it.id == '" + resourceId + "' }.resourceMethods.POST", anEmptyMap());
        given().when().get(resourcePath()).then().statusCode(200)
                .body("resourceMethods.GET", anEmptyMap()).body("resourceMethods.POST", anEmptyMap());
        given().when().get(methodPath("GET")).then().statusCode(200)
                .body("authorizationType", equalTo("NONE"))
                .body("methodIntegration.uri", equalTo("https://example.invalid/widgets"));
        given().queryParam("embed", "methods").when().get(resourcePath()).then().statusCode(200)
                .body("resourceMethods.GET.authorizationType", equalTo("NONE"));
    }

    @Test
    void emptyResourcesDoNotInventMethods() {
        given().queryParam("embed", "methods").when().get(resourcesPath() + "/" + rootId)
                .then().statusCode(200).body("resourceMethods", nullValue());
        given().queryParam("embed", "methods").when().get(resourcesPath()).then().statusCode(200)
                .body("item.find { it.id == '" + rootId + "' }.resourceMethods", nullValue());
    }

    @Test
    void embeddedReadsReflectMethodUpdatesAndDeletion() {
        given().contentType(ContentType.JSON).body("""
                {"patchOperations":[{"op":"replace","path":"/authorizationType","value":"AWS_IAM"}]}
                """)
                .when().patch(methodPath("GET")).then().statusCode(200);
        given().when().delete(methodPath("POST")).then().statusCode(202);
        given().queryParam("embed", "methods").when().get(resourcePath()).then().statusCode(200)
                .body("resourceMethods.GET.authorizationType", equalTo("AWS_IAM"))
                .body("resourceMethods", not(hasKey("POST")));
        given().queryParam("embed", "methods").when().get(resourcesPath()).then().statusCode(200)
                .body("item.find { it.id == '" + resourceId + "' }.resourceMethods", not(hasKey("POST")));
    }

    @Test
    void embeddedReadsKeepNotFoundErrors() {
        given().queryParam("embed", "methods").when().get("/restapis/missing-api/resources")
                .then().statusCode(404);
        given().queryParam("embed", "methods").when().get(resourcesPath() + "/missing-resource")
                .then().statusCode(404);
    }

    private String resourcesPath() {
        return "/restapis/" + apiId + "/resources";
    }

    private String resourcePath() {
        return resourcesPath() + "/" + resourceId;
    }

    private String methodPath(String httpMethod) {
        return resourcePath() + "/methods/" + httpMethod;
    }
}
