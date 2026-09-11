package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Round-trips the {@code PutIntegration} fields AWS documents that Floci previously accepted and
 * silently dropped — {@code contentHandling}, {@code timeoutInMillis}, {@code connectionType},
 * {@code connectionId}, {@code credentials}, {@code cacheNamespace}, {@code cacheKeyParameters} and
 * {@code tlsConfig} — plus {@code contentHandling} on an integration response and
 * {@code binaryMediaTypes} on the RestApi.
 *
 * <p>The mapping configuration these sit alongside ({@code requestParameters},
 * {@code requestTemplates}, {@code integrationResponses}) is covered by
 * {@link ApiGatewayIntegrationReadBackTest}.
 */
@QuarkusTest
class ApiGatewayIntegrationFieldsRoundTripTest {

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
