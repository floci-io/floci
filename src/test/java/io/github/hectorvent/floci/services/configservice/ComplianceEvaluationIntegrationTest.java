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
class ComplianceEvaluationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "StarlingDoveService.";
    private static final String LAMBDA_RULE = "compliance-loop-rule";
    private static final String EXTERNAL_RULE = "external-eval-rule";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static void putRule(String ruleName) {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ConfigRule": {
                        "ConfigRuleName": "%s",
                        "Source": {
                            "Owner": "CUSTOM_LAMBDA",
                            "SourceIdentifier": "arn:aws:lambda:us-east-1:000000000000:function:checker"
                        }
                    }
                }
                """.formatted(ruleName))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(1)
    void setupRules() {
        putRule(LAMBDA_RULE);
        putRule(EXTERNAL_RULE);
    }

    @Test
    @Order(2)
    void putEvaluationsTestModePersistsNothing() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutEvaluations")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResultToken": "%s",
                    "TestMode": true,
                    "Evaluations": [
                        {
                            "ComplianceResourceType": "AWS::S3::Bucket",
                            "ComplianceResourceId": "bucket-1",
                            "ComplianceType": "NON_COMPLIANT",
                            "OrderingTimestamp": 1699999999.5
                        }
                    ]
                }
                """.formatted(LAMBDA_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedEvaluations", hasSize(0));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeComplianceByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["%s"]}
                """.formatted(LAMBDA_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ComplianceByConfigRules[0].Compliance.ComplianceType", equalTo("INSUFFICIENT_DATA"));
    }

    @Test
    @Order(3)
    void putEvaluationsRecordsCompliance() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutEvaluations")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResultToken": "%s",
                    "Evaluations": [
                        {
                            "ComplianceResourceType": "AWS::S3::Bucket",
                            "ComplianceResourceId": "bucket-1",
                            "ComplianceType": "NON_COMPLIANT",
                            "Annotation": "Bucket allows public ACLs",
                            "OrderingTimestamp": 1699999999.5
                        },
                        {
                            "ComplianceResourceType": "AWS::S3::Bucket",
                            "ComplianceResourceId": "bucket-2",
                            "ComplianceType": "COMPLIANT",
                            "OrderingTimestamp": 1699999999.5
                        }
                    ]
                }
                """.formatted(LAMBDA_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedEvaluations", hasSize(0));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeComplianceByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["%s"]}
                """.formatted(LAMBDA_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ComplianceByConfigRules[0].Compliance.ComplianceType", equalTo("NON_COMPLIANT"))
            .body("ComplianceByConfigRules[0].Compliance.ComplianceContributorCount.CappedCount", equalTo(1))
            .body("ComplianceByConfigRules[0].Compliance.ComplianceContributorCount.CapExceeded", equalTo(false));
    }

    @Test
    @Order(4)
    void getComplianceDetailsByConfigRule() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetComplianceDetailsByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleName": "%s"}
                """.formatted(LAMBDA_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EvaluationResults", hasSize(2))
            .body("EvaluationResults[0].EvaluationResultIdentifier.EvaluationResultQualifier.ConfigRuleName",
                    equalTo(LAMBDA_RULE))
            .body("EvaluationResults[0].EvaluationResultIdentifier.EvaluationResultQualifier.ResourceType",
                    equalTo("AWS::S3::Bucket"))
            .body("EvaluationResults[0].EvaluationResultIdentifier.EvaluationResultQualifier.ResourceId",
                    equalTo("bucket-1"))
            .body("EvaluationResults[0].EvaluationResultIdentifier.EvaluationResultQualifier.EvaluationMode",
                    equalTo("DETECTIVE"))
            .body("EvaluationResults[0].EvaluationResultIdentifier.OrderingTimestamp", notNullValue())
            .body("EvaluationResults[0].ComplianceType", equalTo("NON_COMPLIANT"))
            .body("EvaluationResults[0].Annotation", equalTo("Bucket allows public ACLs"))
            .body("EvaluationResults[0].ResultRecordedTime", notNullValue())
            .body("EvaluationResults[0].ConfigRuleInvokedTime", notNullValue())
            .body("EvaluationResults[1].EvaluationResultIdentifier.EvaluationResultQualifier.ResourceId",
                    equalTo("bucket-2"))
            .body("EvaluationResults[1].ComplianceType", equalTo("COMPLIANT"));
    }

    @Test
    @Order(5)
    void getComplianceDetailsFilteredByComplianceType() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetComplianceDetailsByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleName": "%s", "ComplianceTypes": ["NON_COMPLIANT"]}
                """.formatted(LAMBDA_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EvaluationResults", hasSize(1))
            .body("EvaluationResults[0].EvaluationResultIdentifier.EvaluationResultQualifier.ResourceId",
                    equalTo("bucket-1"));
    }

    @Test
    @Order(6)
    void reEvaluationFlipsRuleToCompliant() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutEvaluations")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResultToken": "%s:on-demand-1",
                    "Evaluations": [
                        {
                            "ComplianceResourceType": "AWS::S3::Bucket",
                            "ComplianceResourceId": "bucket-1",
                            "ComplianceType": "COMPLIANT",
                            "OrderingTimestamp": 1700000100.0
                        }
                    ]
                }
                """.formatted(LAMBDA_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeComplianceByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["%s"]}
                """.formatted(LAMBDA_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ComplianceByConfigRules[0].Compliance.ComplianceType", equalTo("COMPLIANT"))
            .body("ComplianceByConfigRules[0].Compliance.ComplianceContributorCount", nullValue());
    }

    @Test
    @Order(7)
    void allNotApplicableIsInsufficientData() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutEvaluations")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResultToken": "%s",
                    "Evaluations": [
                        {
                            "ComplianceResourceType": "AWS::S3::Bucket",
                            "ComplianceResourceId": "bucket-1",
                            "ComplianceType": "NOT_APPLICABLE",
                            "OrderingTimestamp": 1700000200.0
                        },
                        {
                            "ComplianceResourceType": "AWS::S3::Bucket",
                            "ComplianceResourceId": "bucket-2",
                            "ComplianceType": "NOT_APPLICABLE",
                            "OrderingTimestamp": 1700000200.0
                        }
                    ]
                }
                """.formatted(LAMBDA_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeComplianceByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["%s"]}
                """.formatted(LAMBDA_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ComplianceByConfigRules[0].Compliance.ComplianceType", equalTo("INSUFFICIENT_DATA"));
    }

    @Test
    @Order(8)
    void putExternalEvaluation() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutExternalEvaluation")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ConfigRuleName": "%s",
                    "ExternalEvaluation": {
                        "ComplianceResourceType": "AWS::EC2::VPC",
                        "ComplianceResourceId": "vpc-1",
                        "ComplianceType": "NON_COMPLIANT",
                        "Annotation": "Flow logs disabled",
                        "OrderingTimestamp": 1700000300.0
                    }
                }
                """.formatted(EXTERNAL_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeComplianceByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["%s"]}
                """.formatted(EXTERNAL_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ComplianceByConfigRules[0].Compliance.ComplianceType", equalTo("NON_COMPLIANT"));
    }

    @Test
    @Order(9)
    void describeComplianceByResource() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeComplianceByResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceType": "AWS::EC2::VPC"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ComplianceByResources", hasSize(1))
            .body("ComplianceByResources[0].ResourceType", equalTo("AWS::EC2::VPC"))
            .body("ComplianceByResources[0].ResourceId", equalTo("vpc-1"))
            .body("ComplianceByResources[0].Compliance.ComplianceType", equalTo("NON_COMPLIANT"))
            .body("ComplianceByResources[0].Compliance.ComplianceContributorCount.CappedCount", equalTo(1));
    }

    @Test
    @Order(10)
    void getComplianceDetailsByResource() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetComplianceDetailsByResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceType": "AWS::EC2::VPC", "ResourceId": "vpc-1"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EvaluationResults", hasSize(1))
            .body("EvaluationResults[0].EvaluationResultIdentifier.EvaluationResultQualifier.ConfigRuleName",
                    equalTo(EXTERNAL_RULE))
            .body("EvaluationResults[0].Annotation", equalTo("Flow logs disabled"));
    }

    @Test
    @Order(11)
    void complianceSummaries() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetComplianceSummaryByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ComplianceSummary.NonCompliantResourceCount.CappedCount", greaterThanOrEqualTo(1))
            .body("ComplianceSummary.ComplianceSummaryTimestamp", notNullValue());

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetComplianceSummaryByResourceType")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceTypes": ["AWS::EC2::VPC"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ComplianceSummariesByResourceType", hasSize(1))
            .body("ComplianceSummariesByResourceType[0].ResourceType", equalTo("AWS::EC2::VPC"))
            .body("ComplianceSummariesByResourceType[0].ComplianceSummary.NonCompliantResourceCount.CappedCount",
                    equalTo(1));
    }

    @Test
    @Order(12)
    void deleteEvaluationResultsResetsCompliance() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteEvaluationResults")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleName": "%s"}
                """.formatted(EXTERNAL_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeComplianceByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["%s"]}
                """.formatted(EXTERNAL_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ComplianceByConfigRules[0].Compliance.ComplianceType", equalTo("INSUFFICIENT_DATA"));
    }

    @Test
    @Order(13)
    void putEvaluationsWithoutTokenIsRejected() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutEvaluations")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "Evaluations": [
                        {
                            "ComplianceResourceType": "AWS::S3::Bucket",
                            "ComplianceResourceId": "bucket-1",
                            "ComplianceType": "COMPLIANT",
                            "OrderingTimestamp": 1700000400.0
                        }
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidResultTokenException"));
    }

    @Test
    @Order(14)
    void putEvaluationsForUnknownRuleIsRejected() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutEvaluations")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResultToken": "no-such-rule",
                    "Evaluations": [
                        {
                            "ComplianceResourceType": "AWS::S3::Bucket",
                            "ComplianceResourceId": "bucket-1",
                            "ComplianceType": "COMPLIANT",
                            "OrderingTimestamp": 1700000400.0
                        }
                    ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchConfigRuleException"));
    }

    @Test
    @Order(15)
    void putEvaluationsWithInvalidComplianceTypeIsRejected() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutEvaluations")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResultToken": "%s",
                    "Evaluations": [
                        {
                            "ComplianceResourceType": "AWS::S3::Bucket",
                            "ComplianceResourceId": "bucket-1",
                            "ComplianceType": "SORT_OF_COMPLIANT",
                            "OrderingTimestamp": 1700000400.0
                        }
                    ]
                }
                """.formatted(LAMBDA_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    @Order(16)
    void putExternalEvaluationForUnknownRuleIsRejected() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutExternalEvaluation")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ConfigRuleName": "no-such-rule",
                    "ExternalEvaluation": {
                        "ComplianceResourceType": "AWS::EC2::VPC",
                        "ComplianceResourceId": "vpc-1",
                        "ComplianceType": "COMPLIANT",
                        "OrderingTimestamp": 1700000500.0
                    }
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchConfigRuleException"));
    }

    @Test
    @Order(17)
    void staleEvaluationDoesNotOverwriteNewerResult() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutEvaluations")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResultToken": "%s:stale",
                    "Evaluations": [
                        {
                            "ComplianceResourceType": "AWS::S3::Bucket",
                            "ComplianceResourceId": "bucket-1",
                            "ComplianceType": "COMPLIANT",
                            "OrderingTimestamp": 1700000150.0
                        }
                    ]
                }
                """.formatted(LAMBDA_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetComplianceDetailsByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleName": "%s"}
                """.formatted(LAMBDA_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EvaluationResults.find { it.EvaluationResultIdentifier.EvaluationResultQualifier.ResourceId == 'bucket-1' }.ComplianceType",
                    equalTo("NOT_APPLICABLE"))
            .body("EvaluationResults.find { it.EvaluationResultIdentifier.EvaluationResultQualifier.ResourceId == 'bucket-1' }.EvaluationResultIdentifier.OrderingTimestamp",
                    equalTo(1700000200.0f));
    }

    @Test
    @Order(18)
    void evaluationStatusReflectsRecordedEvaluations() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRuleEvaluationStatus")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["%s"]}
                """.formatted(LAMBDA_RULE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRulesEvaluationStatus[0].FirstEvaluationStarted", equalTo(true))
            .body("ConfigRulesEvaluationStatus[0].LastSuccessfulInvocationTime", notNullValue())
            .body("ConfigRulesEvaluationStatus[0].LastSuccessfulEvaluationTime", notNullValue());
    }

    @Test
    @Order(19)
    void cleanupRules() {
        for (String ruleName : new String[]{LAMBDA_RULE, EXTERNAL_RULE}) {
            given()
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteConfigRule")
                .contentType(CONTENT_TYPE)
                .body("""
                    {"ConfigRuleName": "%s"}
                    """.formatted(ruleName))
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }
}
