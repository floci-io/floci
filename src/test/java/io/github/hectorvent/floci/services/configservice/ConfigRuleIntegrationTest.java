package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigRuleIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "StarlingDoveService.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void putConfigRule() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ConfigRule": {
                        "ConfigRuleName": "rule-crud-test",
                        "Description": "Ensure S3 access points block public access",
                        "Scope": {
                            "ComplianceResourceTypes": ["AWS::S3::AccessPoint"],
                            "TagKey": "env"
                        },
                        "Source": {
                            "Owner": "AWS",
                            "SourceIdentifier": "S3_ACCESS_POINT_PUBLIC_ACCESS_BLOCKS",
                            "SourceDetails": [
                                {
                                    "EventSource": "aws.config",
                                    "MessageType": "ScheduledNotification"
                                }
                            ]
                        },
                        "InputParameters": "{\\"mode\\":\\"strict\\"}",
                        "MaximumExecutionFrequency": "TwentyFour_Hours"
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void describeConfigRules() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-crud-test"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRules", hasSize(1))
            .body("ConfigRules[0].ConfigRuleName", equalTo("rule-crud-test"))
            .body("ConfigRules[0].ConfigRuleArn", notNullValue())
            .body("ConfigRules[0].ConfigRuleId", notNullValue())
            .body("ConfigRules[0].ConfigRuleState", equalTo("ACTIVE"))
            .body("ConfigRules[0].Description", equalTo("Ensure S3 access points block public access"))
            .body("ConfigRules[0].Scope.ComplianceResourceTypes", contains("AWS::S3::AccessPoint"))
            .body("ConfigRules[0].Scope.TagKey", equalTo("env"))
            .body("ConfigRules[0].Source.Owner", equalTo("AWS"))
            .body("ConfigRules[0].Source.SourceIdentifier", equalTo("S3_ACCESS_POINT_PUBLIC_ACCESS_BLOCKS"))
            .body("ConfigRules[0].Source.SourceDetails[0].EventSource", equalTo("aws.config"))
            .body("ConfigRules[0].Source.SourceDetails[0].MessageType", equalTo("ScheduledNotification"))
            .body("ConfigRules[0].InputParameters", equalTo("{\"mode\":\"strict\"}"))
            .body("ConfigRules[0].MaximumExecutionFrequency", equalTo("TwentyFour_Hours"))
            .body("ConfigRules[0].EvaluationModes[0].Mode", equalTo("DETECTIVE"));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRules", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(3)
    void describeConfigRulesUnknownName() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["no-such-rule"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchConfigRuleException"));
    }

    @Test
    @Order(4)
    void describeComplianceByConfigRule() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeComplianceByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-crud-test"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ComplianceByConfigRules", hasSize(1))
            .body("ComplianceByConfigRules[0].ConfigRuleName", equalTo("rule-crud-test"))
            .body("ComplianceByConfigRules[0].Compliance.ComplianceType", equalTo("INSUFFICIENT_DATA"));
    }

    @Test
    @Order(5)
    void putConfigRuleUpdate() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ConfigRule": {
                        "ConfigRuleName": "rule-crud-test",
                        "Source": {
                            "Owner": "CUSTOM_LAMBDA",
                            "SourceIdentifier": "arn:aws:lambda:us-east-1:123456789012:function:my-rule"
                        }
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-crud-test"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRules[0].Source.Owner", equalTo("CUSTOM_LAMBDA"))
            .body("ConfigRules[0].ConfigRuleArn", notNullValue())
            .body("ConfigRules[0].ConfigRuleId", notNullValue());
    }

    @Test
    @Order(6)
    void describeConfigRuleEvaluationStatusBeforeStart() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRuleEvaluationStatus")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-crud-test"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRulesEvaluationStatus", hasSize(1))
            .body("ConfigRulesEvaluationStatus[0].ConfigRuleName", equalTo("rule-crud-test"))
            .body("ConfigRulesEvaluationStatus[0].ConfigRuleArn", notNullValue())
            .body("ConfigRulesEvaluationStatus[0].ConfigRuleId", notNullValue())
            .body("ConfigRulesEvaluationStatus[0].FirstEvaluationStarted", equalTo(false))
            .body("ConfigRulesEvaluationStatus[0].FirstActivatedTime", notNullValue())
            .body("ConfigRulesEvaluationStatus[0].LastSuccessfulInvocationTime", nullValue());
    }

    @Test
    @Order(7)
    void startConfigRulesEvaluation() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "StartConfigRulesEvaluation")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-crud-test"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRuleEvaluationStatus")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-crud-test"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRulesEvaluationStatus[0].FirstEvaluationStarted", equalTo(true))
            .body("ConfigRulesEvaluationStatus[0].LastSuccessfulInvocationTime", notNullValue());
    }

    @Test
    @Order(8)
    void startConfigRulesEvaluationNonexistent() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "StartConfigRulesEvaluation")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["no-such-rule"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchConfigRuleException"));
    }

    @Test
    @Order(9)
    void deleteConfigRule() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleName": "rule-crud-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(10)
    void deleteNonexistentConfigRule() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleName": "no-such-rule"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchConfigRuleException"));
    }

    @Test
    @Order(11)
    void deleteConfigRuleWithoutNameIsRejected() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchConfigRuleException"));
    }

    /**
     * PutConfigRule now deserializes the whole modeled ConfigRule and stores it, so every
     * enum-typed member it keeps has to be checked: {@code Source/Owner},
     * {@code MaximumExecutionFrequency}, {@code ConfigRuleState} and
     * {@code EvaluationModes[].Mode}. A rule stored with a value outside its enum is echoed
     * straight back by DescribeConfigRules as a rule AWS would never have accepted.
     */
    @Test
    @Order(11)
    void putConfigRuleRejectsValuesOutsideTheModeledEnums() {
        record Case(String name, String body) { }
        Case[] cases = {
            new Case("Owner", """
                {"ConfigRule": {"ConfigRuleName": "rule-enum-owner",
                 "Source": {"Owner": "CUSTOM", "SourceIdentifier": "X"}}}"""),
            new Case("MaximumExecutionFrequency", """
                {"ConfigRule": {"ConfigRuleName": "rule-enum-freq",
                 "Source": {"Owner": "AWS", "SourceIdentifier": "X"},
                 "MaximumExecutionFrequency": "Two_Hours"}}"""),
            new Case("ConfigRuleState", """
                {"ConfigRule": {"ConfigRuleName": "rule-enum-state",
                 "Source": {"Owner": "AWS", "SourceIdentifier": "X"},
                 "ConfigRuleState": "PAUSED"}}"""),
            new Case("EvaluationMode", """
                {"ConfigRule": {"ConfigRuleName": "rule-enum-mode",
                 "Source": {"Owner": "AWS", "SourceIdentifier": "X"},
                 "EvaluationModes": [{"Mode": "REACTIVE"}]}}"""),
        };
        for (Case c : cases) {
            given()
                .header("X-Amz-Target", TARGET_PREFIX + "PutConfigRule")
                .contentType(CONTENT_TYPE)
                .body(c.body())
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
        }

        // Nothing was stored under any of the rejected names.
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-enum-owner"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchConfigRuleException"));
    }

    /**
     * The nested members are stored and echoed back too: {@code SourceDetails[]} carries the
     * EventSource ({@code aws.config} only), MessageType and MaximumExecutionFrequency enums,
     * and {@code Scope.ComplianceResourceTypes} is a list with {@code max: 100}.
     */
    @Test
    @Order(11)
    void putConfigRuleRejectsNestedValuesOutsideTheModeledShapes() {
        String[] bodies = {
            """
            {"ConfigRule": {"ConfigRuleName": "rule-enum-eventsource",
             "Source": {"Owner": "AWS", "SourceIdentifier": "X",
              "SourceDetails": [{"EventSource": "aws.cloudtrail", "MessageType": "ScheduledNotification"}]}}}""",
            """
            {"ConfigRule": {"ConfigRuleName": "rule-enum-messagetype",
             "Source": {"Owner": "AWS", "SourceIdentifier": "X",
              "SourceDetails": [{"EventSource": "aws.config", "MessageType": "PeriodicNotification"}]}}}""",
            """
            {"ConfigRule": {"ConfigRuleName": "rule-enum-detail-freq",
             "Source": {"Owner": "AWS", "SourceIdentifier": "X",
              "SourceDetails": [{"EventSource": "aws.config", "MessageType": "ScheduledNotification",
                                 "MaximumExecutionFrequency": "Two_Hours"}]}}}""",
        };
        for (String body : bodies) {
            given()
                .header("X-Amz-Target", TARGET_PREFIX + "PutConfigRule")
                .contentType(CONTENT_TYPE)
                .body(body)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
        }

        StringBuilder tooManyTypes = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            tooManyTypes.append(i == 0 ? "" : ", ").append("\"AWS::S3::Bucket").append(i).append('"');
        }
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutConfigRule")
            .contentType(CONTENT_TYPE)
            .body("{\"ConfigRule\": {\"ConfigRuleName\": \"rule-scope-cardinality\","
                    + " \"Source\": {\"Owner\": \"AWS\", \"SourceIdentifier\": \"X\"},"
                    + " \"Scope\": {\"ComplianceResourceTypes\": [" + tooManyTypes + "]}}}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["rule-enum-eventsource"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchConfigRuleException"));
    }

    /**
     * {@code ComplianceTypes} is a list of the ComplianceType enum with {@code max: 3}. It is
     * read as a filter, so an unmodeled value silently narrows the result to nothing — the
     * caller reads that as "no rules match" rather than as the rejection AWS sends.
     */
    @Test
    @Order(12)
    void describeComplianceByConfigRuleRejectsAnUnmodeledComplianceTypeFilter() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeComplianceByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ComplianceTypes": ["PARTIALLY_COMPLIANT"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    @Order(12)
    void describeComplianceByConfigRuleRejectsMoreThanThreeComplianceTypes() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeComplianceByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ComplianceTypes": ["COMPLIANT", "NON_COMPLIANT", "NOT_APPLICABLE", "INSUFFICIENT_DATA"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }
}
