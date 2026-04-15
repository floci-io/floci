package io.github.hectorvent.floci.services.resourcegroupstagging;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the Resource Groups Tagging API.
 * Uses JSON 1.1 protocol (X-Amz-Target: ResourceGroupsTaggingAPI_20170126.*).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResourceGroupsTaggingIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "ResourceGroupsTaggingAPI_20170126.";

    private static final String ARN_INSTANCE = "arn:aws:ec2:us-east-1:000000000000:instance/i-abc123";
    private static final String ARN_BUCKET   = "arn:aws:s3:::my-test-bucket";
    private static final String ARN_FUNCTION = "arn:aws:lambda:us-east-1:000000000000:function/my-func";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ─── TagResources ──────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void tagResources() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "TagResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "ResourceARNList": ["%s", "%s"],
                  "Tags": {"Environment": "prod", "Team": "platform"}
                }
                """.formatted(ARN_INSTANCE, ARN_BUCKET))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedResourcesMap", anEmptyMap());
    }

    @Test
    @Order(2)
    void tagResourcesSecond() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "TagResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "ResourceARNList": ["%s"],
                  "Tags": {"Environment": "staging", "Team": "data"}
                }
                """.formatted(ARN_FUNCTION))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedResourcesMap", anEmptyMap());
    }

    // ─── GetResources ──────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void getResourcesAll() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.size()", equalTo(3))
            .body("ResourceTagMappingList.ResourceARN", hasItems(ARN_INSTANCE, ARN_BUCKET, ARN_FUNCTION));
    }

    @Test
    @Order(4)
    void getResourcesByArnList() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceARNList": ["%s"]}
                """.formatted(ARN_INSTANCE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.size()", equalTo(1))
            .body("ResourceTagMappingList[0].ResourceARN", equalTo(ARN_INSTANCE))
            .body("ResourceTagMappingList[0].Tags.size()", equalTo(2));
    }

    @Test
    @Order(5)
    void getResourcesByTagFilter() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "TagFilters": [
                    {"Key": "Environment", "Values": ["prod"]}
                  ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.size()", equalTo(2))
            .body("ResourceTagMappingList.ResourceARN", hasItems(ARN_INSTANCE, ARN_BUCKET));
    }

    @Test
    @Order(6)
    void getResourcesByTagFilterKeyOnly() {
        // Values empty → match any resource that has the key
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "TagFilters": [
                    {"Key": "Team", "Values": []}
                  ]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.size()", equalTo(3));
    }

    @Test
    @Order(7)
    void getResourcesByResourceTypeFilter() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceTypeFilters": ["ec2:instance"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.size()", equalTo(1))
            .body("ResourceTagMappingList[0].ResourceARN", equalTo(ARN_INSTANCE));
    }

    @Test
    @Order(8)
    void getResourcesByServiceTypeFilter() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceTypeFilters": ["lambda"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.size()", equalTo(1))
            .body("ResourceTagMappingList[0].ResourceARN", equalTo(ARN_FUNCTION));
    }

    @Test
    @Order(9)
    void getResourcesPagination() {
        // ResourcesPerPage=1 → first page has 1 item and a pagination token
        String paginationToken = given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourcesPerPage": 1}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.size()", equalTo(1))
            .body("PaginationToken", not(emptyString()))
        .extract().path("PaginationToken");

        // Second page using the token
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourcesPerPage": 1, "PaginationToken": "%s"}
                """.formatted(paginationToken))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.size()", equalTo(1));
    }

    // ─── GetTagKeys ────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void getTagKeys() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetTagKeys")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TagKeys", hasItems("Environment", "Team"))
            .body("PaginationToken", notNullValue());
    }

    // ─── GetTagValues ──────────────────────────────────────────────────────────

    @Test
    @Order(11)
    void getTagValues() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetTagValues")
            .contentType(CONTENT_TYPE)
            .body("""
                {"Key": "Environment"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TagValues", hasItems("prod", "staging"));
    }

    // ─── UntagResources ────────────────────────────────────────────────────────

    @Test
    @Order(12)
    void untagResources() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "UntagResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "ResourceARNList": ["%s"],
                  "TagKeys": ["Team"]
                }
                """.formatted(ARN_INSTANCE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedResourcesMap", anEmptyMap());
    }

    @Test
    @Order(13)
    void getResourcesAfterUntag() {
        // ARN_INSTANCE should now only have "Environment" tag
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceARNList": ["%s"]}
                """.formatted(ARN_INSTANCE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList[0].Tags.size()", equalTo(1))
            .body("ResourceTagMappingList[0].Tags[0].Key", equalTo("Environment"))
            .body("ResourceTagMappingList[0].Tags[0].Value", equalTo("prod"));
    }

    // ─── Unsupported action ────────────────────────────────────────────────────

    @Test
    @Order(20)
    void unsupportedAction() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "UnknownAction")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnsupportedOperation"));
    }
}
