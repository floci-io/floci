package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Round-trips method request parameters, the REST API resource policy and the stage's access log,
 * tracing and tag settings through their getters.
 *
 * <p>Each write was accepted but the read came back without the value, so Terraform planned the
 * same in-place update on every run, and a deployment whose redeployment trigger hashes the method
 * failed with "Provider produced inconsistent final plan".
 */
@QuarkusTest
class ApiGatewayConfigReadBackTest {

    private static final String POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                    + "\"Action\":\"execute-api:Invoke\",\"Resource\":\"*\"}]}";

    private String apiId;
    private String resourceId;

    @BeforeEach
    void setup() {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"config-read-back\"}")
                .when().post("/restapis")
                .then().statusCode(201).body("id", notNullValue())
                .extract().path("id");

        String rootId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");

        resourceId = given()
                .contentType(ContentType.JSON)
                .body("{\"pathPart\":\"{proxy+}\"}")
                .when().post("/restapis/" + apiId + "/resources/" + rootId)
                .then().statusCode(201).extract().path("id");
    }

    @AfterEach
    void cleanup() {
        if (apiId != null) {
            given().when().delete("/restapis/" + apiId).then().statusCode(202);
        }
    }

    private String methodPath() {
        return "/restapis/" + apiId + "/resources/" + resourceId + "/methods/ANY";
    }

    private String createStage(String body) {
        String deploymentId = given().contentType(ContentType.JSON).body("{}")
                .when().post("/restapis/" + apiId + "/deployments")
                .then().statusCode(201).extract().path("id");
        given().contentType(ContentType.JSON)
                .body(body.replace("DEPLOYMENT", deploymentId))
                .when().post("/restapis/" + apiId + "/stages")
                .then().statusCode(201);
        return "/restapis/" + apiId + "/stages/local";
    }

    @Test
    void getMethodReturnsRequestParameters() {
        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\",\"requestParameters\":{\"method.request.path.proxy\":true}}")
                .when().put(methodPath())
                .then().statusCode(201)
                .body("requestParameters.'method.request.path.proxy'", equalTo(true));

        given().when().get(methodPath())
                .then().statusCode(200)
                .body("requestParameters.'method.request.path.proxy'", equalTo(true));
    }

    @Test
    void updateMethodPatchesRequestParameters() {
        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\",\"requestParameters\":{\"method.request.path.proxy\":true}}")
                .when().put(methodPath())
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("""
                        {"patchOperations":[
                          {"op":"add","path":"/requestParameters/method.request.header.X-Tenant","value":"false"},
                          {"op":"remove","path":"/requestParameters/method.request.path.proxy"}]}
                        """)
                .when().patch(methodPath())
                .then().statusCode(200);

        given().when().get(methodPath())
                .then().statusCode(200)
                .body("requestParameters.'method.request.header.X-Tenant'", equalTo(false))
                .body("requestParameters.'method.request.path.proxy'", nullValue());
    }

    @Test
    void rejectedUpdateMethodLeavesRequestParametersUntouched() {
        given().contentType(ContentType.JSON)
                .body("{\"authorizationType\":\"NONE\",\"requestParameters\":{\"method.request.path.proxy\":true}}")
                .when().put(methodPath())
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("""
                        {"patchOperations":[
                          {"op":"remove","path":"/requestParameters/method.request.path.proxy"},
                          {"op":"add","path":"/requestParameters/method.request.header.bad name!","value":"true"}]}
                        """)
                .when().patch(methodPath())
                .then().statusCode(400);

        given().when().get(methodPath())
                .then().statusCode(200)
                .body("requestParameters.'method.request.path.proxy'", equalTo(true));
    }

    @Test
    void getRestApiReturnsThePolicyEscapedAsAwsDoes() {
        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/policy\",\"value\":"
                        + quote(POLICY) + "}]}")
                .when().patch("/restapis/" + apiId)
                .then().statusCode(200);

        String policy = given().when().get("/restapis/" + apiId)
                .then().statusCode(200).extract().path("policy");
        // AWS escapes the document's quotes inside the string; unescaping gives back the original.
        assertEquals(POLICY, unquote(policy));

        // A pretty-printed policy must come back with its newlines escaped as well, so that the
        // escaped form is still a valid JSON string once a client wraps it in quotes.
        String pretty = POLICY.replace(",", ",\n  ");
        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/policy\",\"value\":"
                        + quote(pretty) + "}]}")
                .when().patch("/restapis/" + apiId)
                .then().statusCode(200);
        String escaped = given().when().get("/restapis/" + apiId)
                .then().statusCode(200).extract().path("policy");
        assertFalse(escaped.contains("\n"), "raw newline in escaped policy");
        assertEquals(pretty, unquote(escaped));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/policy\",\"value\":\"\"}]}")
                .when().patch("/restapis/" + apiId)
                .then().statusCode(200);

        given().when().get("/restapis/" + apiId)
                .then().statusCode(200).body("policy", nullValue());
    }

    @Test
    void getStageReturnsAccessLogsTracingAndTags() {
        String stagePath = createStage("""
                {"stageName":"local","deploymentId":"DEPLOYMENT","tracingEnabled":true,
                 "tags":{"env":"local"}}
                """);

        given().contentType(ContentType.JSON)
                .body("""
                        {"patchOperations":[
                          {"op":"replace","path":"/accessLogSettings/destinationArn","value":"arn:aws:logs:us-east-1:000000000000:log-group:api"},
                          {"op":"replace","path":"/accessLogSettings/format","value":"$context.requestId"}]}
                        """)
                .when().patch(stagePath)
                .then().statusCode(200);

        given().when().get(stagePath)
                .then().statusCode(200)
                .body("accessLogSettings.destinationArn", equalTo("arn:aws:logs:us-east-1:000000000000:log-group:api"))
                .body("accessLogSettings.format", equalTo("$context.requestId"))
                .body("tracingEnabled", equalTo(true))
                .body("tags.env", equalTo("local"));

        given().when().get("/restapis/" + apiId + "/stages")
                .then().statusCode(200)
                .body("item[0].accessLogSettings.format", equalTo("$context.requestId"))
                .body("item[0].tracingEnabled", equalTo(true));

        given().contentType(ContentType.JSON)
                .body("""
                        {"patchOperations":[
                          {"op":"remove","path":"/accessLogSettings"},
                          {"op":"replace","path":"/tracingEnabled","value":"false"}]}
                        """)
                .when().patch(stagePath)
                .then().statusCode(200);

        given().when().get(stagePath)
                .then().statusCode(200)
                .body("accessLogSettings", nullValue())
                .body("tracingEnabled", equalTo(false));
    }

    @Test
    void tagResourceOnAStageArnTagsTheStageNotTheApi() {
        String stagePath = createStage("{\"stageName\":\"local\",\"deploymentId\":\"DEPLOYMENT\"}");
        String stageArn = "arn:aws:apigateway:us-east-1::/restapis/" + apiId + "/stages/local";

        given().contentType(ContentType.JSON)
                .body("{\"tags\":{\"team\":\"api\"}}")
                .pathParam("resourceArn", stageArn)
                .when().put("/tags/{resourceArn}")
                .then().statusCode(204);

        given().when().get(stagePath)
                .then().statusCode(200).body("tags.team", equalTo("api"));
        given().when().get("/restapis/" + apiId)
                .then().statusCode(200).body("tags", nullValue());
        given().pathParam("resourceArn", stageArn)
                .when().get("/tags/{resourceArn}")
                .then().statusCode(200).body("tags.team", equalTo("api"));

        given().queryParam("tagKeys", "team")
                .pathParam("resourceArn", stageArn)
                .when().delete("/tags/{resourceArn}")
                .then().statusCode(204);

        given().when().get(stagePath)
                .then().statusCode(200).body("tags", nullValue());
    }

    /** Decodes the escaped policy the way the Terraform provider does: wrap in quotes, parse as a JSON string. */
    private static String unquote(String escaped) {
        try {
            return new ObjectMapper().readValue("\"" + escaped + "\"", String.class);
        } catch (JsonProcessingException e) {
            throw new AssertionError("escaped policy is not a valid JSON string body: " + escaped, e);
        }
    }

    private static String quote(String s) {
        try {
            return new ObjectMapper().writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }
}
