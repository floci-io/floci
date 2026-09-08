package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the SES V1 Query-protocol ReorderReceiptRuleSet and CloneReceiptRuleSet
 * actions. Wire behavior follows real AWS as probed (2026-09): reorder demands an exact
 * permutation of the set with a duplicate-then-missing-then-unknown error precedence, and clone
 * deep-copies the rules under a new creation timestamp while the active flag stays behind.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesReceiptRuleSetReorderCloneV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-west-2/email/aws4_request";
    private static final String RS = "floci-v1-reorder";
    private static final String CLONE = "floci-v1-reorder-copy";

    private static io.restassured.specification.RequestSpecification req(String action) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH)
                .formParam("Action", action);
    }

    private static String describeRuleNames(String ruleSetName) {
        return req("DescribeReceiptRuleSet").formParam("RuleSetName", ruleSetName)
        .when().post("/").then().statusCode(200).extract().asString();
    }

    @Test
    @Order(1)
    void reorder_appliesPermutation_andAnswersEmptyResult() {
        req("CreateReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(200);
        for (String name : new String[] {"r3", "r2", "r1"}) {
            // Without After each create lands at the front, so the set reads r1, r2, r3.
            req("CreateReceiptRule").formParam("RuleSetName", RS)
                    .formParam("Rule.Name", name)
            .when().post("/").then().statusCode(200);
        }

        req("ReorderReceiptRuleSet").formParam("RuleSetName", RS)
                .formParam("RuleNames.member.1", "r2")
                .formParam("RuleNames.member.2", "r3")
                .formParam("RuleNames.member.3", "r1")
        .when().post("/").then().statusCode(200)
                .body(containsString("ReorderReceiptRuleSetResponse"));

        String body = describeRuleNames(RS);
        assertTrue(body.indexOf("<Name>r2</Name>") < body.indexOf("<Name>r3</Name>"));
        assertTrue(body.indexOf("<Name>r3</Name>") < body.indexOf("<Name>r1</Name>"));
    }

    @Test
    @Order(2)
    void reorder_errorPrecedence_onTheWire() {
        req("ReorderReceiptRuleSet").formParam("RuleSetName", "floci-v1-ghost")
                .formParam("RuleNames.member.1", "r1")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>RuleSetDoesNotExist</Code>"))
                .body(containsString("Rule set does not exist: floci-v1-ghost"));

        req("ReorderReceiptRuleSet").formParam("RuleSetName", RS)
                .formParam("RuleNames.member.1", "r1")
                .formParam("RuleNames.member.2", "r1")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("Multiple positions found for rule: r1"));

        // Missing set rules (listed in set order) win over the unknown name.
        req("ReorderReceiptRuleSet").formParam("RuleSetName", RS)
                .formParam("RuleNames.member.1", "r3")
                .formParam("RuleNames.member.2", "rX")
        .when().post("/").then().statusCode(400)
                .body(containsString("Positions for rules not found: r2, r1"));

        req("ReorderReceiptRuleSet").formParam("RuleSetName", RS)
                .formParam("RuleNames.member.1", "r1")
                .formParam("RuleNames.member.2", "r2")
                .formParam("RuleNames.member.3", "r3")
                .formParam("RuleNames.member.4", "rX")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>RuleDoesNotExist</Code>"))
                .body(containsString("Rule does not exist: rX"));

        req("ReorderReceiptRuleSet").formParam("RuleSetName", RS)
                .formParam("RuleNames.member.1", "bad name!")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("Value at &apos;ruleNames&apos; failed to satisfy constraint: "
                        + "Member must satisfy constraint: "
                        + "[Member must have length less than or equal to 100"));
    }

    @Test
    @Order(3)
    void clone_deepCopies_andActiveStaysBehind() {
        req("SetActiveReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(200);

        req("CloneReceiptRuleSet").formParam("RuleSetName", CLONE)
                .formParam("OriginalRuleSetName", RS)
        .when().post("/").then().statusCode(200)
                .body(containsString("CloneReceiptRuleSetResponse"));

        String body = describeRuleNames(CLONE);
        assertTrue(body.contains("<Name>" + CLONE + "</Name>"));
        assertTrue(body.indexOf("<Name>r2</Name>") < body.indexOf("<Name>r3</Name>"));

        req("DescribeActiveReceiptRuleSet")
        .when().post("/").then().statusCode(200)
                .body(containsString("<Name>" + RS + "</Name>"));

        // Clear the active flag so the shared-state teardown delete succeeds.
        req("SetActiveReceiptRuleSet")
        .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(4)
    void clone_targetConflictAndMissingSource_errors() {
        req("CloneReceiptRuleSet").formParam("RuleSetName", CLONE)
                .formParam("OriginalRuleSetName", "floci-v1-ghost")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>AlreadyExists</Code>"))
                .body(containsString("Rule set already exists: " + CLONE));

        req("CloneReceiptRuleSet").formParam("RuleSetName", "floci-v1-fresh")
                .formParam("OriginalRuleSetName", "floci-v1-ghost")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>RuleSetDoesNotExist</Code>"))
                .body(containsString("Rule set does not exist: floci-v1-ghost"));
    }

    @Test
    @Order(5)
    void reorder_ruleNamesIndexGrammar_matchesProbedWire() {
        // A gap of up to 10 is padded with empty members, which then fail the ruleNames
        // Smithy constraint; nothing is truncated.
        req("ReorderReceiptRuleSet").formParam("RuleSetName", RS)
                .formParam("RuleNames.member.1", "r1")
                .formParam("RuleNames.member.3", "r3")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("Value at &apos;ruleNames&apos; failed to satisfy constraint"));

        // A larger gap is its own MalformedInput; the skip counts from the previous index.
        req("ReorderReceiptRuleSet").formParam("RuleSetName", RS)
                .formParam("RuleNames.member.1", "r1")
                .formParam("RuleNames.member.2", "r2")
                .formParam("RuleNames.member.3", "r3")
                .formParam("RuleNames.member.14", "rX")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>MalformedInput</Code>"))
                .body(containsString("Excessively sparse input would skip 11 list elements"));

        // The skip count is comma-grouped like AWS renders it.
        req("ReorderReceiptRuleSet").formParam("RuleSetName", RS)
                .formParam("RuleNames.member.1", "r1")
                .formParam("RuleNames.member.1500", "rX")
        .when().post("/").then().statusCode(400)
                .body(containsString("Excessively sparse input would skip 1,499 list elements"));

        req("ReorderReceiptRuleSet").formParam("RuleSetName", RS)
                .formParam("RuleNames.member.0", "r1")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>MalformedInput</Code>"))
                .body(containsString("0 is not a valid index"));

        req("ReorderReceiptRuleSet").formParam("RuleSetName", RS)
                .formParam("RuleNames.member.01", "r1")
        .when().post("/").then().statusCode(400)
                .body(containsString("Value found where not expected"));

        req("ReorderReceiptRuleSet").formParam("RuleSetName", RS)
                .formParam("RuleNames.member.x", "r1")
        .when().post("/").then().statusCode(400)
                .body(containsString("Start of list found where not expected"));

        req("ReorderReceiptRuleSet").formParam("RuleSetName", RS)
                .formParam("RuleNames.member.999999999", "r1")
        .when().post("/").then().statusCode(400)
                .body(containsString("Index 999999999 is illegal"));

        // A repeated index keeps its first value: the list reads r1, r1, r3 and trips the
        // duplicate-name check, proving the second value never displaced the first.
        req("ReorderReceiptRuleSet").formParam("RuleSetName", RS)
                .formParam("RuleNames.member.1", "r1", "r2")
                .formParam("RuleNames.member.2", "r1")
                .formParam("RuleNames.member.3", "r3")
        .when().post("/").then().statusCode(400)
                .body(containsString("Multiple positions found for rule: r1"));
    }

    @Test
    @Order(6)
    void cleanup_deletesBothSets() {
        for (String name : new String[] {RS, CLONE}) {
            req("DeleteReceiptRuleSet").formParam("RuleSetName", name)
            .when().post("/").then().statusCode(200);
        }
    }
}
