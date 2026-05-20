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
class ConfigTaggingIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "StarlingDoveService.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void putConfigRuleForTagging() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutConfigRule")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ConfigRule": {
                        "ConfigRuleName": "tag-test-rule",
                        "Source": {
                            "Owner": "AWS",
                            "SourceIdentifier": "S3_BUCKET_VERSIONING_ENABLED"
                        }
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
    void tagResource() {
        String arn = getConfigRuleArn("tag-test-rule");
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "TagResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResourceArn": "%s",
                    "Tags": [
                        {"Key": "Environment", "Value": "test"},
                        {"Key": "Team", "Value": "platform"}
                    ]
                }
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(3)
    void tagResourceAddTag() {
        String arn = getConfigRuleArn("tag-test-rule");
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "TagResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResourceArn": "%s",
                    "Tags": [{"Key": "CostCenter", "Value": "12345"}]
                }
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceArn": "%s"}
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(3));
    }

    @Test
    @Order(4)
    void untagResource() {
        String arn = getConfigRuleArn("tag-test-rule");
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "UntagResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ResourceArn": "%s",
                    "TagKeys": ["CostCenter"]
                }
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceArn": "%s"}
                """.formatted(arn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(2));
    }

    @Test
    @Order(5)
    void listTagsForResourceNoTags() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceArn": "arn:aws:config:us-east-1:123456789012:config-rule/nonexistent"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(0));
    }

    private String getConfigRuleArn(String ruleName) {
        return given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConfigRules")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConfigRuleNames": ["%s"]}
                """.formatted(ruleName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract()
            .path("ConfigRules[0].ConfigRuleArn");
    }
}
