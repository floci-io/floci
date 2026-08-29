package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigPaginationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "StarlingDoveService.";
    private static final int SEEDED_RULES = 30;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String seededRuleName(int index) {
        return "pg-rule-%03d".formatted(index);
    }

    @Test
    @Order(1)
    void seedRules() {
        for (int i = 0; i < SEEDED_RULES; i++) {
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
                    """.formatted(seededRuleName(i)))
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }

    @Test
    @Order(2)
    void describeConfigRulesReturnsPageOf25WithNextToken() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConfigRules", hasSize(25))
            .body("NextToken", notNullValue());
    }

    @Test
    @Order(3)
    void nextTokenWalkCollectsAllSeededRules() {
        List<String> collected = new ArrayList<>();
        String nextToken = null;
        do {
            String body = nextToken == null ? "{}" : "{\"NextToken\": \"%s\"}".formatted(nextToken);
            JsonPath response = given()
                .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
                .contentType(CONTENT_TYPE)
                .body(body)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().jsonPath();
            collected.addAll(response.getList("ConfigRules.ConfigRuleName", String.class));
            nextToken = response.getString("NextToken");
        } while (nextToken != null);

        for (int i = 0; i < SEEDED_RULES; i++) {
            String name = seededRuleName(i);
            assertTrue(collected.contains(name), "walk must return seeded rule " + name);
        }
    }

    @Test
    @Order(4)
    void invalidNextTokenIsRejected() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("""
                {"NextToken": "not-a-token"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidNextTokenException"));
    }

    @Test
    @Order(5)
    void complianceDetailsHonorLimitAndNextToken() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutEvaluations")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResultToken": "%s",
                    "Evaluations": [
                        {
                            "ComplianceResourceType": "AWS::Lambda::Function",
                            "ComplianceResourceId": "fn-1",
                            "ComplianceType": "COMPLIANT",
                            "OrderingTimestamp": 1700000000.0
                        },
                        {
                            "ComplianceResourceType": "AWS::Lambda::Function",
                            "ComplianceResourceId": "fn-2",
                            "ComplianceType": "COMPLIANT",
                            "OrderingTimestamp": 1700000000.0
                        },
                        {
                            "ComplianceResourceType": "AWS::Lambda::Function",
                            "ComplianceResourceId": "fn-3",
                            "ComplianceType": "NON_COMPLIANT",
                            "OrderingTimestamp": 1700000000.0
                        }
                    ]
                }
                """.formatted(seededRuleName(0)))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String nextToken = given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetComplianceDetailsByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleName": "%s", "Limit": 2}
                """.formatted(seededRuleName(0)))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EvaluationResults", hasSize(2))
            .body("NextToken", notNullValue())
            .extract().jsonPath().getString("NextToken");

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetComplianceDetailsByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleName": "%s", "Limit": 2, "NextToken": "%s"}
                """.formatted(seededRuleName(0), nextToken))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EvaluationResults", hasSize(1))
            .body("NextToken", nullValue());
    }

    @Test
    @Order(6)
    void complianceDetailsLimitAbove100IsRejected() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetComplianceDetailsByConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleName": "%s", "Limit": 101}
                """.formatted(seededRuleName(0)))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    @Order(7)
    void conformancePackLimitAbove20IsRejected() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConformancePacks")
            .contentType(CONTENT_TYPE)
            .body("""
                {"Limit": 21}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidLimitException"));
    }

    @Test
    @Order(8)
    void cleanupSeededRules() {
        for (int i = 0; i < SEEDED_RULES; i++) {
            given()
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteConfigRule")
                .contentType(CONTENT_TYPE)
                .body("""
                    {"ConfigRuleName": "%s"}
                    """.formatted(seededRuleName(i)))
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }
}
