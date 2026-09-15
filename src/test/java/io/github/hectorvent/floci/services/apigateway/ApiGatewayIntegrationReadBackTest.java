package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Round-trips the REST (v1) integration configuration through PutIntegration → GetIntegration and
 * PutIntegrationResponse → GetIntegrationResponse.
 *
 * <p>Both getters were lossy: {@code toIntegrationNode} returned only type/httpMethod/uri/
 * passthroughBehavior and {@code toIntegrationResponseNode} only statusCode/selectionPattern, so the
 * mapping configuration Floci stores and honours at invoke time was invisible on read-back.
 * Infrastructure-as-code tools diff against exactly these reads and saw the mappings as absent,
 * producing drift that never converged.
 */
@QuarkusTest
class ApiGatewayIntegrationReadBackTest {

    private String apiId;
    private String resourceId;

    @BeforeEach
    void setup() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"integration-read-back\"}")
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

        given().contentType(ContentType.JSON)
                .body("""
                        {"type":"AWS","httpMethod":"POST",
                         "uri":"arn:aws:apigateway:us-east-1:sqs:path/000000000000/my-queue",
                         "passthroughBehavior":"WHEN_NO_TEMPLATES",
                         "requestParameters":{"integration.request.header.X-Tenant":"method.request.querystring.tenant"},
                         "requestTemplates":{"application/json":"{\\"passed\\":true}"}}
                        """)
                .when().put(integrationPath())
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

    @Test
    void getIntegrationReturnsTheMappingConfigurationItStores() {
        given().when().get(integrationPath())
                .then().statusCode(200)
                .body("requestParameters.'integration.request.header.X-Tenant'",
                        equalTo("method.request.querystring.tenant"))
                .body("requestTemplates.'application/json'", equalTo("{\"passed\":true}"))
                .body("passthroughBehavior", equalTo("WHEN_NO_TEMPLATES"));
    }

    @Test
    void getIntegrationEmbedsItsIntegrationResponses() {
        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"404\"}")
                .when().put(integrationPath() + "/responses/404")
                .then().statusCode(201);

        given().when().get(integrationPath())
                .then().statusCode(200)
                .body("integrationResponses.'404'.statusCode", equalTo("404"))
                .body("integrationResponses.'404'.selectionPattern", equalTo("404"));
    }

    @Test
    void getIntegrationResponseReturnsItsTemplatesAndParameters() {
        given().contentType(ContentType.JSON)
                .body("{\"selectionPattern\":\"5\\\\d{2}\","
                        + "\"responseTemplates\":{\"application/json\":\"{\\\"mapped\\\":true}\"},"
                        + "\"responseParameters\":{\"method.response.header.X-Out\":\"integration.response.header.X-Backend\"}}")
                .when().put(integrationPath() + "/responses/502")
                .then().statusCode(201);

        given().when().get(integrationPath() + "/responses/502")
                .then().statusCode(200)
                .body("statusCode", equalTo("502"))
                .body("selectionPattern", equalTo("5\\d{2}"))
                .body("responseTemplates.'application/json'", equalTo("{\"mapped\":true}"))
                .body("responseParameters.'method.response.header.X-Out'",
                        equalTo("integration.response.header.X-Backend"));
    }

    @Test
    void methodSettingsAcceptTheEscapedResourcePathAwsSends() {
        String deploymentId = given().contentType(ContentType.JSON).body("{}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"test\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);

        // AWS escapes the resource path's slashes as ~1. That spelling previously produced the
        // unusable key "~1widget/POST" instead of "widget/POST".
        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\","
                        + "\"path\":\"/~1widget/POST/throttling/burstLimit\",\"value\":\"77\"}]}")
                .when().patch("/restapis/" + apiId + "/stages/test")
                .then().statusCode(200);

        given().when().get("/restapis/" + apiId + "/stages/test")
                .then().statusCode(200)
                .body("methodSettings.'widget/POST'.throttlingBurstLimit", equalTo(77));
    }

    @Test
    void methodSettingsUnescapeTheJsonPointerTildeEscape() {
        String deploymentId = given().contentType(ContentType.JSON).body("{}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"tilde\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);

        // RFC 6901 escapes a literal "~" as ~0 and "/" as ~1. "/~1pets~0promo/GET/..." therefore
        // addresses the resource "/pets~promo"; only ~1 used to be decoded, leaving a stray "~0".
        // "~01" is an escaped literal "~1", not a slash, so decoding order matters too.
        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":["
                        + "{\"op\":\"replace\",\"path\":\"/~1pets~0promo/GET/throttling/burstLimit\",\"value\":\"12\"},"
                        + "{\"op\":\"replace\",\"path\":\"/~1v~01/POST/throttling/rateLimit\",\"value\":\"3.5\"}]}")
                .when().patch("/restapis/" + apiId + "/stages/tilde")
                .then().statusCode(200);

        given().when().get("/restapis/" + apiId + "/stages/tilde")
                .then().statusCode(200)
                .body("methodSettings.'pets~promo/GET'.throttlingBurstLimit", equalTo(12))
                .body("methodSettings.'v~1/POST'.throttlingRateLimit", equalTo(3.5f));
    }

    @Test
    void anIntegrationWithNoTypeReportsRatherThanCrashing() {
        given().contentType(ContentType.JSON).body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET")
                .then().statusCode(201);
        // An OpenAPI import can leave "type" unset; that used to NPE inside the dispatch switch.
        given().contentType(ContentType.JSON).body("{\"httpMethod\":\"GET\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/integration")
                .then().statusCode(201);

        String deploymentId = given().contentType(ContentType.JSON).body("{}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body("{\"stageName\":\"notype\",\"deploymentId\":\"" + deploymentId + "\"}")
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);

        given().when().get("/execute-api/" + apiId + "/notype/widget")
                .then().statusCode(500);
    }
}
