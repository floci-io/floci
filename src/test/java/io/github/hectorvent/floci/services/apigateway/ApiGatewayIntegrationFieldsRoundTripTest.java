package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Round-trips the {@code PutIntegration} fields AWS documents that Floci previously accepted and
 * silently dropped: {@code contentHandling}, {@code timeoutInMillis}, {@code connectionType},
 * {@code connectionId}, {@code credentials}, {@code cacheNamespace}, {@code cacheKeyParameters} and
 * {@code tlsConfig}, plus {@code contentHandling} on an integration response and
 * {@code binaryMediaTypes} on the RestApi.
 *
 * <p>The mapping configuration these sit alongside ({@code requestParameters},
 * {@code requestTemplates}, {@code integrationResponses}) is covered by
 * {@link ApiGatewayIntegrationReadBackTest}.
 */
@QuarkusTest
class ApiGatewayIntegrationFieldsRoundTripTest {

    @Inject
    ApiGatewayService service;

    private String apiId;
    private String resourceId;

    @BeforeEach
    void setup() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"integration-fields-roundtrip\",\"binaryMediaTypes\":[\"image/jpeg\",\"application/octet-stream\"]}")
                .when().post("/restapis")
                .then().statusCode(201).body("id", notNullValue())
                .extract().path("id");

        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");

        resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"widget\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST")
                .then().statusCode(201);
    }

    @AfterEach
    void cleanup() {
        if (apiId != null) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    private String integrationPath() {
        return "/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST/integration";
    }

    private void putFullIntegration() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"type":"HTTP","httpMethod":"POST","uri":"http://backend.internal/widget",
                         "contentHandling":"CONVERT_TO_BINARY",
                         "timeoutInMillis":12000,
                         "connectionType":"VPC_LINK",
                         "connectionId":"abc123",
                         "credentials":"arn:aws:iam::000000000000:role/ApiGatewayRole",
                         "cacheNamespace":"widget-ns",
                         "cacheKeyParameters":["method.request.querystring.tenant"],
                         "tlsConfig":{"insecureSkipVerification":true}}
                        """)
                .when().put(integrationPath())
                .then().statusCode(201);
    }

    @Test
    void putIntegrationEchoesEveryConfiguredField() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"type":"HTTP","httpMethod":"POST","uri":"http://backend.internal/widget",
                         "contentHandling":"CONVERT_TO_TEXT","timeoutInMillis":9000,
                         "connectionType":"VPC_LINK","connectionId":"abc123",
                         "tlsConfig":{"insecureSkipVerification":true}}
                        """)
                .when().put(integrationPath())
                .then().statusCode(201)
                .body("type", equalTo("HTTP"))
                .body("contentHandling", equalTo("CONVERT_TO_TEXT"))
                .body("timeoutInMillis", equalTo(9000))
                .body("connectionType", equalTo("VPC_LINK"))
                .body("connectionId", equalTo("abc123"))
                .body("tlsConfig.insecureSkipVerification", equalTo(true));
    }

    @Test
    void getIntegrationReturnsTheSettingsItStores() {
        putFullIntegration();

        given().when().get(integrationPath())
                .then().statusCode(200)
                .body("cacheNamespace", equalTo("widget-ns"))
                .body("cacheKeyParameters", contains("method.request.querystring.tenant"))
                .body("credentials", equalTo("arn:aws:iam::000000000000:role/ApiGatewayRole"))
                .body("contentHandling", equalTo("CONVERT_TO_BINARY"))
                .body("timeoutInMillis", equalTo(12000));
    }

    @Test
    void integrationDefaultsAreReturnedByPutGetAndMethodReadBack() {
        given().contentType(ContentType.JSON).body("{\"type\":\"MOCK\"}")
                .when().put(integrationPath())
                .then().statusCode(201)
                .body("timeoutInMillis", equalTo(29000))
                .body("cacheNamespace", equalTo(resourceId))
                .body("cacheKeyParameters", empty())
                .body("responseTransferMode", equalTo("BUFFERED"));

        given().when().get(integrationPath())
                .then().statusCode(200)
                .body("timeoutInMillis", equalTo(29000))
                .body("cacheNamespace", equalTo(resourceId))
                .body("cacheKeyParameters", empty())
                .body("responseTransferMode", equalTo("BUFFERED"));

        given().when().get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST")
                .then().statusCode(200)
                .body("methodIntegration.timeoutInMillis", equalTo(29000))
                .body("methodIntegration.cacheNamespace", equalTo(resourceId))
                .body("methodIntegration.cacheKeyParameters", empty())
                .body("methodIntegration.responseTransferMode", equalTo("BUFFERED"));
    }

    @Test
    void explicitTransferModeRoundTripsAndCanBePatched() {
        given().contentType(ContentType.JSON)
                .body("{\"type\":\"HTTP_PROXY\",\"httpMethod\":\"GET\","
                        + "\"uri\":\"http://example.invalid/widget\",\"responseTransferMode\":\"STREAM\"}")
                .when().put(integrationPath())
                .then().statusCode(201).body("responseTransferMode", equalTo("STREAM"));
        given().when().get(integrationPath())
                .then().statusCode(200).body("responseTransferMode", equalTo("STREAM"));
        given().when().get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST")
                .then().statusCode(200)
                .body("methodIntegration.responseTransferMode", equalTo("STREAM"));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\","
                        + "\"path\":\"/responseTransferMode\",\"value\":\"BUFFERED\"}]}")
                .when().patch(integrationPath())
                .then().statusCode(200).body("responseTransferMode", equalTo("BUFFERED"));
        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\","
                        + "\"path\":\"/responseTransferMode\",\"value\":\"UNKNOWN\"}]}")
                .when().patch(integrationPath()).then().statusCode(400);
        given().when().get(integrationPath())
                .then().statusCode(200).body("responseTransferMode", equalTo("BUFFERED"));
    }

    @Test
    void nonStringTransferModeIsRejectedWithBadRequest() {
        given().contentType(ContentType.JSON)
                .body("{\"type\":\"HTTP_PROXY\",\"responseTransferMode\":42}")
                .when().put(integrationPath()).then().statusCode(400);

        String importedSpec = """
                {"openapi":"3.0.1","info":{"title":"invalid-mode","version":"1"},
                 "paths":{"/stream":{"get":{"x-amazon-apigateway-integration":{
                   "type":"http_proxy","httpMethod":"GET","uri":"http://example.invalid/stream",
                   "responseTransferMode":42}}}}}
                """;
        given().contentType(ContentType.JSON).queryParam("mode", "import")
                .body(importedSpec).when().post("/restapis").then().statusCode(400);
    }

    @Test
    void streamRequiresProxyTypeOnPutAndOpenApiImport() {
        for (String type : new String[]{"MOCK", "HTTP", "AWS"}) {
            given().contentType(ContentType.JSON)
                    .body("{\"type\":\"" + type + "\",\"responseTransferMode\":\"STREAM\"}")
                    .when().put(integrationPath())
                    .then().statusCode(400).body("__type", equalTo("BadRequestException"));
        }
        given().when().get(integrationPath()).then().statusCode(404);

        given().contentType(ContentType.JSON)
                .body("{\"type\":\"AWS_PROXY\",\"responseTransferMode\":\"STREAM\"}")
                .when().put(integrationPath())
                .then().statusCode(201).body("responseTransferMode", equalTo("STREAM"));

        String importedSpec = """
                {"openapi":"3.0.1","info":{"title":"invalid-stream","version":"1"},
                 "paths":{"/stream":{"get":{"x-amazon-apigateway-integration":{
                   "type":"mock","responseTransferMode":"STREAM"}}}}}
                """;
        given().contentType(ContentType.JSON).queryParam("mode", "import")
                .body(importedSpec).when().post("/restapis").then().statusCode(400);
    }

    @Test
    void streamPatchValidatesFinalTypeWithoutMutatingOnFailure() {
        given().contentType(ContentType.JSON).body("{\"type\":\"MOCK\"}")
                .when().put(integrationPath()).then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\","
                        + "\"path\":\"/responseTransferMode\",\"value\":\"STREAM\"}]}")
                .when().patch(integrationPath()).then().statusCode(400);
        given().when().get(integrationPath())
                .then().statusCode(200).body("type", equalTo("MOCK"))
                .body("responseTransferMode", equalTo("BUFFERED"));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\","
                        + "\"path\":\"/responseTransferMode\",\"value\":\"STREAM\"},"
                        + "{\"op\":\"replace\",\"path\":\"/type\",\"value\":\"HTTP_PROXY\"}]}")
                .when().patch(integrationPath()).then().statusCode(200)
                .body("type", equalTo("HTTP_PROXY"))
                .body("responseTransferMode", equalTo("STREAM"));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\","
                        + "\"path\":\"/type\",\"value\":\"MOCK\"}]}")
                .when().patch(integrationPath()).then().statusCode(400);
        given().when().get(integrationPath())
                .then().statusCode(200).body("type", equalTo("HTTP_PROXY"))
                .body("responseTransferMode", equalTo("STREAM"));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\","
                        + "\"path\":\"/responseTransferMode\",\"value\":\"BUFFERED\"},"
                        + "{\"op\":\"replace\",\"path\":\"/type\",\"value\":\"MOCK\"}]}")
                .when().patch(integrationPath()).then().statusCode(200)
                .body("type", equalTo("MOCK"))
                .body("responseTransferMode", equalTo("BUFFERED"));
    }

    @Test
    void openApiImportPreservesExplicitTransferMode() {
        String importedApi = given().contentType(ContentType.JSON).queryParam("mode", "import")
                .body("""
                        {"openapi":"3.0.1","info":{"title":"streaming-import","version":"1"},
                         "paths":{"/stream":{"get":{"x-amazon-apigateway-integration":{
                           "type":"http_proxy","httpMethod":"GET","uri":"http://example.invalid/stream",
                           "responseTransferMode":"STREAM"}}}}}
                        """)
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        try {
            String importedResource = given().when().get("/restapis/" + importedApi + "/resources")
                    .then().statusCode(200).extract().path("item.find { it.path == '/stream' }.id");
            given().when().get("/restapis/" + importedApi + "/resources/" + importedResource
                            + "/methods/GET/integration")
                    .then().statusCode(200).body("responseTransferMode", equalTo("STREAM"));
        } finally {
            given().when().delete("/restapis/" + importedApi).then().statusCode(202);
        }
    }

    @Test
    void olderIntegrationWithNullDefaultsStillReadsBackAwsValues() {
        given().contentType(ContentType.JSON).body("{\"type\":\"MOCK\"}")
                .when().put(integrationPath()).then().statusCode(201);
        service.getIntegration("us-east-1", apiId, resourceId, "POST").setTimeoutInMillis(null);
        service.getIntegration("us-east-1", apiId, resourceId, "POST").setCacheNamespace(null);

        given().when().get(integrationPath())
                .then().statusCode(200)
                .body("timeoutInMillis", equalTo(29000))
                .body("cacheNamespace", equalTo(resourceId));
        given().when().get("/restapis/" + apiId + "/resources/" + resourceId + "/methods/POST")
                .then().statusCode(200)
                .body("methodIntegration.timeoutInMillis", equalTo(29000))
                .body("methodIntegration.cacheNamespace", equalTo(resourceId));
    }

    @Test
    void updateIntegrationCanPatchTimeoutAndRejectsInvalidValuesWithoutMutation() {
        given().contentType(ContentType.JSON).body("{\"type\":\"MOCK\",\"timeoutInMillis\":49}")
                .when().put(integrationPath())
                .then().statusCode(400).body("message", equalTo("Invalid timeout value: 49"));
        given().contentType(ContentType.JSON).body("{\"type\":\"MOCK\"}")
                .when().put(integrationPath()).then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\","
                        + "\"path\":\"/timeoutInMillis\",\"value\":\"30000\"}]}")
                .when().patch(integrationPath())
                .then().statusCode(200).body("timeoutInMillis", equalTo(30000));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\","
                        + "\"path\":\"/passthroughBehavior\",\"value\":\"NEVER\"},"
                        + "{\"op\":\"replace\",\"path\":\"/timeoutInMillis\",\"value\":\"49\"}]}")
                .when().patch(integrationPath())
                .then().statusCode(400).body("message", equalTo("Invalid timeout value: 49"));

        given().when().get(integrationPath())
                .then().statusCode(200)
                .body("timeoutInMillis", equalTo(30000))
                .body("passthroughBehavior", equalTo("WHEN_NO_MATCH"));
    }

    @Test
    void getIntegrationResponseReturnsItsContentHandling() {
        putFullIntegration();

        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"5\\\\d{2}\",\"contentHandling\":\"CONVERT_TO_TEXT\"}")
                .when().put(integrationPath() + "/responses/502")
                .then().statusCode(201);

        given().when().get(integrationPath() + "/responses/502")
                .then().statusCode(200)
                .body("statusCode", equalTo("502"))
                .body("contentHandling", equalTo("CONVERT_TO_TEXT"));
    }

    @Test
    void restApiRoundTripsBinaryMediaTypes() {
        given().when().get("/restapis/" + apiId)
                .then().statusCode(200)
                .body("binaryMediaTypes", containsInAnyOrder("image/jpeg", "application/octet-stream"));
    }
}
