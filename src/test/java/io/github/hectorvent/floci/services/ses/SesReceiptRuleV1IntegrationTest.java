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
 * Integration tests for the SES V1 Query-protocol receipt-rule actions. Rules are stored inertly
 * (no mail routing), but action targets are validated against the local emulator the way real SES
 * validates them against the account: SNS topics, S3 buckets, and Lambda functions must exist, and
 * a bounce sender must be a verified identity. Error codes, messages, ordering semantics, and the
 * SNS encoding default are all probe-confirmed against real AWS (2026-09).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesReceiptRuleV1IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-west-2/email/aws4_request";
    private static final String RS = "floci-v1-rules";
    private static final String BOUNCE_SENDER = "receipt-bounce@floci-rules.example.com";

    private static String snsTopicArn;

    private static io.restassured.specification.RequestSpecification req(String action) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH)
                .formParam("Action", action);
    }

    @Test
    @Order(1)
    void createRuleSet_andRichRule() {
        req("CreateReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(200);

        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "rich")
                .formParam("Rule.Enabled", "true")
                .formParam("Rule.TlsPolicy", "Require")
                .formParam("Rule.ScanEnabled", "true")
                .formParam("Rule.Recipients.member.1", "probe@example.com")
                .formParam("Rule.Recipients.member.2", "other@example.org")
                .formParam("Rule.Actions.member.1.AddHeaderAction.HeaderName", "X-Floci")
                .formParam("Rule.Actions.member.1.AddHeaderAction.HeaderValue", "v1")
                .formParam("Rule.Actions.member.2.StopAction.Scope", "RuleSet")
        .when().post("/").then().statusCode(200)
                .body(containsString("CreateReceiptRuleResponse"));
    }

    @Test
    @Order(2)
    void describeRule_roundTripsSortedRecipientsAndOrderedActions() {
        String body = req("DescribeReceiptRule").formParam("RuleSetName", RS)
                .formParam("RuleName", "rich")
        .when().post("/").then().statusCode(200)
                .body(containsString("<Name>rich</Name>"))
                .body(containsString("<Enabled>true</Enabled>"))
                .body(containsString("<TlsPolicy>Require</TlsPolicy>"))
                .body(containsString("<ScanEnabled>true</ScanEnabled>"))
                .body(containsString("<HeaderName>X-Floci</HeaderName>"))
                .body(containsString("<Scope>RuleSet</Scope>"))
                .extract().asString();
        // Recipients render sorted regardless of the order they were sent in (probed).
        assertTrue(body.indexOf("other@example.org") < body.indexOf("probe@example.com"));
        // Actions keep their submitted order.
        assertTrue(body.indexOf("AddHeaderAction") < body.indexOf("StopAction"));
    }

    @Test
    @Order(3)
    void createWithoutAfter_insertsAtFront_andAfterInsertsBehind() {
        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "first")
        .when().post("/").then().statusCode(200);

        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "middle")
                .formParam("After", "first")
        .when().post("/").then().statusCode(200);

        String body = req("DescribeReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(200).extract().asString();
        assertTrue(body.indexOf("<Name>first</Name>") < body.indexOf("<Name>middle</Name>"));
        assertTrue(body.indexOf("<Name>middle</Name>") < body.indexOf("<Name>rich</Name>"));
    }

    @Test
    @Order(4)
    void minimalRule_getsAwsDefaults() {
        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "minimal")
        .when().post("/").then().statusCode(200);

        req("DescribeReceiptRule").formParam("RuleSetName", RS)
                .formParam("RuleName", "minimal")
        .when().post("/").then().statusCode(200)
                .body(containsString("<Enabled>false</Enabled>"))
                .body(containsString("<TlsPolicy>Optional</TlsPolicy>"))
                .body(containsString("<ScanEnabled>false</ScanEnabled>"))
                .body(containsString("<Actions></Actions>"));
    }

    @Test
    @Order(5)
    void duplicateCreate_returnsAlreadyExists() {
        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "rich")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>AlreadyExists</Code>"))
                .body(containsString("Rule already exists: rich"));
    }

    @Test
    @Order(6)
    void missingSetAndMissingRule_errors() {
        req("CreateReceiptRule").formParam("RuleSetName", "floci-v1-nope")
                .formParam("Rule.Name", "r")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>RuleSetDoesNotExist</Code>"));

        req("DescribeReceiptRule").formParam("RuleSetName", RS)
                .formParam("RuleName", "ghost")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>RuleDoesNotExist</Code>"))
                .body(containsString("Rule does not exist: ghost"));
    }

    @Test
    @Order(7)
    void invalidRuleShape_returnsSmithyValidationError() {
        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "bad!name")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("&apos;rule.name&apos;"))
                .body(containsString("^[a-zA-Z0-9_.-]+$"));

        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "badtls")
                .formParam("Rule.TlsPolicy", "Bogus")
        .when().post("/").then().statusCode(400)
                .body(containsString("&apos;rule.tlsPolicy&apos;"))
                .body(containsString("[Optional, Require]"));

        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "badlambda")
                .formParam("Rule.Actions.member.1.LambdaAction.InvocationType", "Event")
        .when().post("/").then().statusCode(400)
                .body(containsString("&apos;rule.actions.1.member.lambdaAction.functionArn&apos;"))
                .body(containsString("Member must not be null"));
    }

    @Test
    @Order(8)
    void actionTargets_areValidatedAgainstLocalServices() {
        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "s3neg")
                .formParam("Rule.Actions.member.1.S3Action.BucketName", "floci-no-such-bucket")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidS3Configuration</Code>"))
                .body(containsString("No such bucket: floci-no-such-bucket"));

        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "snsneg")
                .formParam("Rule.Actions.member.1.SNSAction.TopicArn",
                        "arn:aws:sns:us-west-2:000000000000:floci-no-such-topic")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidSnsTopic</Code>"))
                .body(containsString("Could not publish to SNS topic: "
                        + "arn:aws:sns:us-west-2:000000000000:floci-no-such-topic"));

        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "lamneg")
                .formParam("Rule.Actions.member.1.LambdaAction.FunctionArn",
                        "arn:aws:lambda:us-west-2:000000000000:function:floci-no-such-fn")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidLambdaFunction</Code>"))
                .body(containsString("Could not invoke Lambda function: "
                        + "arn:aws:lambda:us-west-2:000000000000:function:floci-no-such-fn"));

        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "bounceneg")
                .formParam("Rule.Actions.member.1.BounceAction.SmtpReplyCode", "550")
                .formParam("Rule.Actions.member.1.BounceAction.Message", "unavailable")
                .formParam("Rule.Actions.member.1.BounceAction.Sender", "stranger@example.net")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("Identity is not verified: stranger@example.net"));
    }

    @Test
    @Order(9)
    void verifiedBounceSender_andLocalSnsTopic_areAccepted() {
        req("VerifyEmailIdentity").formParam("EmailAddress", BOUNCE_SENDER)
        .when().post("/").then().statusCode(200);

        snsTopicArn = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-west-2/sns/aws4_request")
                .formParam("Action", "CreateTopic")
                .formParam("Name", "floci-receipt-rule-topic")
        .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");

        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "actions-ok")
                .formParam("Rule.Actions.member.1.BounceAction.SmtpReplyCode", "550")
                .formParam("Rule.Actions.member.1.BounceAction.Message", "unavailable")
                .formParam("Rule.Actions.member.1.BounceAction.Sender", BOUNCE_SENDER)
                .formParam("Rule.Actions.member.2.SNSAction.TopicArn", snsTopicArn)
        .when().post("/").then().statusCode(200);

        // The stored SNS action picks up AWS's UTF-8 encoding default (probed).
        req("DescribeReceiptRule").formParam("RuleSetName", RS)
                .formParam("RuleName", "actions-ok")
        .when().post("/").then().statusCode(200)
                .body(containsString("<TopicArn>" + snsTopicArn + "</TopicArn>"))
                .body(containsString("<Encoding>UTF-8</Encoding>"))
                .body(containsString("<Sender>" + BOUNCE_SENDER + "</Sender>"));
    }

    @Test
    @Order(9)
    void sparseActionIndexes_areNotDropped() {
        // An action serialized as an empty structure contributes no keys, so a list can arrive
        // starting at member.2; the later actions must still be parsed.
        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "sparse")
                .formParam("Rule.Actions.member.2.StopAction.Scope", "RuleSet")
        .when().post("/").then().statusCode(200);

        req("DescribeReceiptRule").formParam("RuleSetName", RS)
                .formParam("RuleName", "sparse")
        .when().post("/").then().statusCode(200)
                .body(containsString("<Scope>RuleSet</Scope>"));
    }

    @Test
    @Order(9)
    void malformedWireBoolean_isRejectedAsMalformedInput() {
        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "badbool")
                .formParam("Rule.Enabled", "bogus")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>MalformedInput</Code>"))
                .body(containsString("boolean must follow xsd1.1 definition"));

        // Uppercase TRUE is rejected too (probed); the numeric forms are accepted.
        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "badbool")
                .formParam("Rule.ScanEnabled", "TRUE")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>MalformedInput</Code>"));

        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "numbool")
                .formParam("Rule.Enabled", "1")
        .when().post("/").then().statusCode(200);
        req("DescribeReceiptRule").formParam("RuleSetName", RS)
                .formParam("RuleName", "numbool")
        .when().post("/").then().statusCode(200)
                .body(containsString("<Enabled>true</Enabled>"));
    }

    @Test
    @Order(9)
    void twoActionTypesInOneMember_areRejected() {
        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "multitype")
                .formParam("Rule.Actions.member.1.StopAction.Scope", "RuleSet")
                .formParam("Rule.Actions.member.1.AddHeaderAction.HeaderName", "X-A")
                .formParam("Rule.Actions.member.1.AddHeaderAction.HeaderValue", "v")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>InvalidParameterValue</Code>"))
                .body(containsString("Exactly one action type must be specified for each ReceiptAction"));
    }

    @Test
    @Order(9)
    void overflowingActionIndex_isIgnoredNotAServerError() {
        req("CreateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "hugeidx")
                .formParam("Rule.Actions.member.999999999999999999999.StopAction.Scope", "RuleSet")
        .when().post("/").then().statusCode(200);

        req("DescribeReceiptRule").formParam("RuleSetName", RS)
                .formParam("RuleName", "hugeidx")
        .when().post("/").then().statusCode(200)
                .body(containsString("<Actions></Actions>"));
    }

    @Test
    @Order(9)
    void topLevelRuleNameParam_getsSmithyValidation() {
        req("DescribeReceiptRule").formParam("RuleSetName", RS)
                .formParam("RuleName", "bad name")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("&apos;ruleName&apos;"));
    }

    @Test
    @Order(10)
    void updateRule_isFullReplace_andKeepsPosition() {
        req("UpdateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "rich")
        .when().post("/").then().statusCode(200);

        req("DescribeReceiptRule").formParam("RuleSetName", RS)
                .formParam("RuleName", "rich")
        .when().post("/").then().statusCode(200)
                .body(containsString("<Enabled>false</Enabled>"))
                .body(containsString("<TlsPolicy>Optional</TlsPolicy>"))
                .body(containsString("<Actions></Actions>"));

        req("UpdateReceiptRule").formParam("RuleSetName", RS)
                .formParam("Rule.Name", "ghost")
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>RuleDoesNotExist</Code>"));
    }

    @Test
    @Order(11)
    void setRulePosition_movesToFrontWithoutAfter() {
        req("SetReceiptRulePosition").formParam("RuleSetName", RS)
                .formParam("RuleName", "rich")
        .when().post("/").then().statusCode(200);

        String body = req("DescribeReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(200).extract().asString();
        assertTrue(body.indexOf("<Name>rich</Name>") < body.indexOf("<Name>first</Name>"));
    }

    @Test
    @Order(12)
    void deleteRule_isIdempotent_andRuleSetDeleteCascades() {
        req("DeleteReceiptRule").formParam("RuleSetName", RS)
                .formParam("RuleName", "ghost")
        .when().post("/").then().statusCode(200);

        req("DeleteReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(200);

        req("DescribeReceiptRuleSet").formParam("RuleSetName", RS)
        .when().post("/").then().statusCode(400)
                .body(containsString("<Code>RuleSetDoesNotExist</Code>"));
    }
}
