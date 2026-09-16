package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Exercises {@link BedrockAgentCoreResourcePolicyController} end to end over HTTP: the
 * put/get/delete lifecycle under {@code /resourcepolicy/{resourceArn}}, the required-{@code policy}
 * validation error, and the not-found path for an unknown resource ARN.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreResourcePolicyIntegrationTest {

    private static final String RESOURCE_ARN =
            "arn:aws:bedrock-agentcore:us-east-1:000000000000:gateway/it-policy-gateway";

    private static final String UNKNOWN_ARN =
            "arn:aws:bedrock-agentcore:us-east-1:000000000000:gateway/does-not-exist";

    private static final String POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",\
            "Principal":{"AWS":"arn:aws:iam::111111111111:root"},\
            "Action":"bedrock-agentcore:InvokeGateway","Resource":"*"}]}""";

    private static final String UPDATED_POLICY = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Deny",\
            "Principal":"*","Action":"bedrock-agentcore:InvokeGateway","Resource":"*"}]}""";

    private static String path(String resourceArn) {
        return "/resourcepolicy/" + resourceArn;
    }

    private static String putBody(String policy) {
        // The policy document travels as a JSON string, so its quotes must be escaped.
        return "{\"policy\":\"" + policy.replace("\"", "\\\"") + "\"}";
    }

    @Test
    @Order(1)
    void getUnknownResourceReturns404() {
        given().when().get(path(UNKNOWN_ARN))
                .then().statusCode(404)
                .header("X-Amzn-Errortype", "ResourceNotFoundException")
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", containsString(UNKNOWN_ARN));
    }

    @Test
    @Order(2)
    void putReturns201WithPolicy() {
        given().contentType("application/json").body(putBody(POLICY))
                .when().put(path(RESOURCE_ARN))
                .then().statusCode(201)
                .body("policy", equalTo(POLICY));
    }

    @Test
    @Order(3)
    void getReturnsStoredPolicy() {
        given().when().get(path(RESOURCE_ARN))
                .then().statusCode(200)
                .body("policy", equalTo(POLICY));
    }

    @Test
    @Order(4)
    void putReplacesExistingPolicy() {
        given().contentType("application/json").body(putBody(UPDATED_POLICY))
                .when().put(path(RESOURCE_ARN))
                .then().statusCode(201)
                .body("policy", equalTo(UPDATED_POLICY));

        given().when().get(path(RESOURCE_ARN))
                .then().statusCode(200)
                .body("policy", equalTo(UPDATED_POLICY));
    }

    @Test
    @Order(5)
    void putWithoutPolicyIsRejectedWithoutMutation() {
        // Missing field, explicit JSON null, and an empty body all mean "no policy".
        for (String body : new String[]{"{}", "{\"policy\":null}", ""}) {
            given().contentType("application/json").body(body)
                    .when().put(path(RESOURCE_ARN))
                    .then().statusCode(400)
                    .header("X-Amzn-Errortype", "ValidationException")
                    .body("__type", equalTo("ValidationException"))
                    .body("message", equalTo("policy is required"));
        }

        // The rejected puts must not have touched the stored policy.
        given().when().get(path(RESOURCE_ARN))
                .then().statusCode(200)
                .body("policy", equalTo(UPDATED_POLICY));
    }

    @Test
    @Order(6)
    void putRejectsPolicyOutsideLengthBounds() {
        given().contentType("application/json").body("{\"policy\":\"\"}")
                .when().put(path(RESOURCE_ARN))
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("policy must be between 1 and 20480 characters"));

        given().contentType("application/json").body(putBody("x".repeat(20481)))
                .when().put(path(RESOURCE_ARN))
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("policy must be between 1 and 20480 characters"));
    }

    @Test
    @Order(7)
    void resourceArnLengthIsValidated() {
        // Shorter than the 20-character minimum.
        given().contentType("application/json").body(putBody(POLICY))
                .when().put(path("arn:aws:short"))
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("resourceArn must be between 20 and 1011 characters"));

        given().when().get(path("arn:aws:short"))
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(8)
    void deleteReturns204AndPolicyIsGone() {
        given().when().delete(path(RESOURCE_ARN))
                .then().statusCode(204);

        given().when().get(path(RESOURCE_ARN))
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(9)
    void deleteUnknownResourceReturns404() {
        given().when().delete(path(UNKNOWN_ARN))
                .then().statusCode(404)
                .header("X-Amzn-Errortype", "ResourceNotFoundException")
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", containsString(UNKNOWN_ARN));
    }
}
