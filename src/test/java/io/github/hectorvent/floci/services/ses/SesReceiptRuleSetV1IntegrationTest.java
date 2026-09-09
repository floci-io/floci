package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Integration tests for the SES V1 Query-protocol receipt-rule-set actions. Floci stores rule sets
 * inertly (no rules, no mail routing); these tests pin the management-API round-trip and the
 * probe-confirmed AWS error/idempotency behavior. Uses an isolated region so the account-level
 * active rule set does not collide with other SES tests.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesReceiptRuleSetV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-west-2/email/aws4_request";
    private static final String RS = "floci-v1-rule-set";

    private static io.restassured.specification.RequestSpecification req(String action) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH)
                .formParam("Action", action);
    }

    @Test
    @Order(1)
    void createReceiptRuleSet() {
        req("CreateReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(200)
                .body(containsString("CreateReceiptRuleSetResponse"));
    }

    @Test
    @Order(2)
    void createDuplicate_returnsAlreadyExists() {
        req("CreateReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>AlreadyExists</Code>"))
                .body(containsString("Rule set already exists: " + RS));
    }

    @Test
    @Order(3)
    void describeReceiptRuleSet_returnsMetadataWithEmptyRules() {
        req("DescribeReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(200)
                .body(containsString("<Metadata>"))
                .body(containsString("<Name>" + RS + "</Name>"))
                .body(containsString("CreatedTimestamp"))
                .body(containsString("Rules"));
    }

    @Test
    @Order(4)
    void listReceiptRuleSets_containsTheRuleSet() {
        req("ListReceiptRuleSets")
        .when().post("/").then().statusCode(200)
                .body(containsString("<RuleSets>"))
                .body(containsString("<Name>" + RS + "</Name>"));
    }

    @Test
    @Order(5)
    void describeNonExistent_returnsRuleSetDoesNotExist() {
        req("DescribeReceiptRuleSet").formParam("RuleSetName", "floci-v1-nope")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>RuleSetDoesNotExist</Code>"))
                .body(containsString("Rule set does not exist: floci-v1-nope"));
    }

    @Test
    @Order(6)
    void setActive_thenDescribeActive_returnsIt() {
        req("SetActiveReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(200);

        req("DescribeActiveReceiptRuleSet")
        .when().post("/").then().statusCode(200)
                .body(containsString("<Metadata>"))
                .body(containsString("<Name>" + RS + "</Name>"));

        // While active, deleting the rule set is rejected (verified against AWS) and it is preserved.
        req("DeleteReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>CannotDelete</Code>"))
                .body(containsString("Cannot delete active rule set: " + RS));

        req("DescribeReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(200)
                .body(containsString("<Name>" + RS + "</Name>"));
    }

    @Test
    @Order(7)
    void setActiveNonExistent_returnsRuleSetDoesNotExist() {
        req("SetActiveReceiptRuleSet").formParam("RuleSetName", "floci-v1-nope")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>RuleSetDoesNotExist</Code>"));
    }

    @Test
    @Order(8)
    void setActiveWithoutName_clearsActive() {
        req("SetActiveReceiptRuleSet")
        .when().post("/").then().statusCode(200);

        // With no active rule set, AWS returns an empty result (no Metadata / Name).
        req("DescribeActiveReceiptRuleSet")
        .when().post("/").then().statusCode(200)
                .body(containsString("DescribeActiveReceiptRuleSetResponse"))
                .body(not(containsString("<Name>")));
    }

    @Test
    @Order(9)
    void deleteReceiptRuleSet_thenIdempotentDelete() {
        req("DeleteReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(200)
                .body(containsString("DeleteReceiptRuleSetResponse"));

        // AWS delete is idempotent: deleting again (now absent) still succeeds.
        req("DeleteReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(10)
    void describeAfterDelete_returnsRuleSetDoesNotExist() {
        req("DescribeReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>RuleSetDoesNotExist</Code>"));
    }

    @Test
    @Order(11)
    void createRuleSet_charOutsideAllowedSet_returnsValidationError() {
        // A character outside ^[a-zA-Z0-9_.-]+$ is a Smithy ValidationError (verified against AWS).
        req("CreateReceiptRuleSet").formParam("RuleSetName", "floci bad name")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("ruleSetName"));
    }

    @Test
    @Order(12)
    void createRuleSet_badFormat_returnsNotValidRuleSetName() {
        // Valid characters, but a name must start/end with an alphanumeric (verified against AWS).
        req("CreateReceiptRuleSet").formParam("RuleSetName", "-floci-bad")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("Not a valid ruleSetName: -floci-bad"));
    }

    @Test
    @Order(13)
    void createRuleSet_dottedName_isAccepted() {
        // A dot is inside the allowed character set (verified against AWS).
        req("CreateReceiptRuleSet").formParam("RuleSetName", "floci.v1.dotted")
        .when().post("/").then().statusCode(200);
        req("DeleteReceiptRuleSet").formParam("RuleSetName", "floci.v1.dotted")
        .when().post("/").then().statusCode(200);
    }
}
