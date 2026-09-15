package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreGatewayRuleIntegrationTest {

    private static final String CREATE_GATEWAY = """
            {"name":"ruleGateway","authorizerType":"AWS_IAM","roleArn":"arn:aws:iam::000000000000:role/gw"}""";

    private static final String VALID_RULE = """
            {"priority":10,"actions":[{"routeToTarget":{"staticRoute":{"targetName":"t1"}}}]}""";

    private static String gatewayId;
    private static String ruleId;

    @Test
    @Order(1)
    void createGateway() {
        gatewayId = given()
                .contentType("application/json")
                .body(CREATE_GATEWAY)
                .when()
                .post("/gateways/")
                .then()
                .statusCode(202)
                .extract()
                .path("gatewayId");
    }

    @Test
    @Order(2)
    void createGatewayRule() {
        ruleId = given()
                .contentType("application/json")
                .body(VALID_RULE)
                .when()
                .post("/gateways/" + gatewayId + "/rules")
                .then()
                .statusCode(202)
                .body("ruleId", notNullValue())
                .body("gatewayArn", containsString(":gateway/"))
                .body("status", equalTo("ACTIVE"))
                .body("priority", equalTo(10))
                // The controller strips clientToken and updatedAt from the create response.
                .body("clientToken", nullValue())
                .body("updatedAt", nullValue())
                .extract()
                .path("ruleId");
    }

    @Test
    @Order(3)
    void getGatewayRule() {
        given()
                .when()
                .get("/gateways/" + gatewayId + "/rules/" + ruleId)
                .then()
                .statusCode(200)
                .body("ruleId", equalTo(ruleId))
                .body("priority", equalTo(10))
                .body("actions[0].routeToTarget.staticRoute.targetName", equalTo("t1"))
                .body("status", equalTo("ACTIVE"))
                .body("updatedAt", notNullValue())
                .body("clientToken", nullValue());
    }

    @Test
    @Order(4)
    void listGatewayRulesIncludesTheCreatedRule() {
        given()
                .when()
                .get("/gateways/" + gatewayId + "/rules")
                .then()
                .statusCode(200)
                .body("gatewayRules.ruleId", hasItem(ruleId));
    }

    @Test
    @Order(5)
    void updateGatewayRuleChangesPriorityAndDescription() {
        given()
                .contentType("application/json")
                .body("""
                        {"priority":20,"description":"updated rule"}""")
                .when()
                .patch("/gateways/" + gatewayId + "/rules/" + ruleId)
                .then()
                .statusCode(202)
                .body("priority", equalTo(20))
                .body("description", equalTo("updated rule"));

        given()
                .when()
                .get("/gateways/" + gatewayId + "/rules/" + ruleId)
                .then()
                .statusCode(200)
                .body("priority", equalTo(20))
                .body("description", equalTo("updated rule"));
    }

    @Test
    @Order(6)
    void createGatewayRuleMissingActionsIsRejected() {
        given()
                .contentType("application/json")
                .body("""
                        {"priority":1}""")
                .when()
                .post("/gateways/" + gatewayId + "/rules")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("actions must contain between 1 and 2 items"));
    }

    @Test
    @Order(7)
    void createGatewayRuleMissingPriorityIsRejected() {
        given()
                .contentType("application/json")
                .body("""
                        {"actions":[{"routeToTarget":{"staticRoute":{"targetName":"t1"}}}]}""")
                .when()
                .post("/gateways/" + gatewayId + "/rules")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("priority is required"));
    }

    @Test
    @Order(8)
    void createGatewayRulePriorityOutOfRangeIsRejected() {
        given()
                .contentType("application/json")
                .body("""
                        {"priority":0,"actions":[{"routeToTarget":{"staticRoute":{"targetName":"t1"}}}]}""")
                .when()
                .post("/gateways/" + gatewayId + "/rules")
                .then()
                .statusCode(400)
                .body("message", equalTo("priority must be between 1 and 1000000"));

        given()
                .contentType("application/json")
                .body("""
                        {"priority":1000001,"actions":[{"routeToTarget":{"staticRoute":{"targetName":"t1"}}}]}""")
                .when()
                .post("/gateways/" + gatewayId + "/rules")
                .then()
                .statusCode(400)
                .body("message", equalTo("priority must be between 1 and 1000000"));
    }

    @Test
    @Order(9)
    void createGatewayRuleWithInvalidActionUnionIsRejected() {
        // An action naming both union members is invalid: exactly one is required.
        given()
                .contentType("application/json")
                .body("""
                        {"priority":1,"actions":[{"routeToTarget":{"staticRoute":{"targetName":"t1"}},"configurationBundle":{"staticOverride":{"bundleArn":"x","bundleVersion":"y"}}}]}""")
                .when()
                .post("/gateways/" + gatewayId + "/rules")
                .then()
                .statusCode(400)
                .body("message", equalTo("each action must contain exactly one union member"));
    }

    @Test
    @Order(10)
    void createGatewayRuleForUnknownGatewayIsNotFound() {
        given()
                .contentType("application/json")
                .body(VALID_RULE)
                .when()
                .post("/gateways/no-such-gateway/rules")
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("Gateway not found: no-such-gateway"));
    }

    @Test
    @Order(11)
    void getGatewayRuleWithMalformedRuleIdIsValidationError() {
        given()
                .when()
                .get("/gateways/" + gatewayId + "/rules/not-a-uuid")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("ruleId does not satisfy the required UUID pattern"));
    }

    @Test
    @Order(12)
    void getGatewayRuleWithWellFormedButUnknownRuleIdIsNotFound() {
        String unknownRuleId = "00000000-0000-0000-0000-000000000000";

        given()
                .when()
                .get("/gateways/" + gatewayId + "/rules/" + unknownRuleId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("Gateway rule not found: " + unknownRuleId));
    }

    @Test
    @Order(13)
    void listGatewayRulesRejectsInvalidMaxResults() {
        given()
                .queryParam("maxResults", 0)
                .when()
                .get("/gateways/" + gatewayId + "/rules")
                .then()
                .statusCode(400)
                .body("message", containsString("maxResults must be between 1 and 100"));

        given()
                .queryParam("maxResults", 101)
                .when()
                .get("/gateways/" + gatewayId + "/rules")
                .then()
                .statusCode(400)
                .body("message", containsString("maxResults must be between 1 and 100"));
    }

    @Test
    @Order(14)
    void createGatewayRuleWithClientTokenIsIdempotent() {
        String clientToken = "a".repeat(40);
        String body = """
                {"priority":5,"clientToken":"%s",
                 "actions":[{"routeToTarget":{"staticRoute":{"targetName":"t2"}}}]}"""
                .formatted(clientToken);

        String firstRuleId = given()
                .contentType("application/json")
                .body(body)
                .when()
                .post("/gateways/" + gatewayId + "/rules")
                .then()
                .statusCode(202)
                .extract()
                .path("ruleId");

        // Same clientToken replays the original rule rather than creating a second one.
        given()
                .contentType("application/json")
                .body(body)
                .when()
                .post("/gateways/" + gatewayId + "/rules")
                .then()
                .statusCode(202)
                .body("ruleId", equalTo(firstRuleId));
    }

    @Test
    @Order(15)
    void deleteGatewayRuleRemovesIt() {
        given()
                .when()
                .delete("/gateways/" + gatewayId + "/rules/" + ruleId)
                .then()
                .statusCode(202)
                .body("ruleId", equalTo(ruleId))
                .body("status", equalTo("DELETING"));

        given()
                .when()
                .get("/gateways/" + gatewayId + "/rules/" + ruleId)
                .then()
                .statusCode(404);
    }
}
