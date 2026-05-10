package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SFN tag operations covering both wire paths:
 *  - REST /tags/{arn}  — used by Terraform AWS provider v5+
 *  - JSON 1.0 actions  — used by the AWS SDK (SfnClient)
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsTagsIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String SIMPLE_DEFINITION =
            "{\"Comment\":\"tag test\",\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"End\":true}}}";

    private static String stateMachineArn;
    private static String activityArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createStateMachine() {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"name\":\"tag-test-sm\",\"definition\":\"" + SIMPLE_DEFINITION.replace("\"", "\\\"") + "\",\"roleArn\":\"" + ROLE_ARN + "\"}")
                .when().post("/");
        resp.then().statusCode(200);
        stateMachineArn = resp.jsonPath().getString("stateMachineArn");
        assertNotNull(stateMachineArn);
    }

    @Test
    @Order(2)
    void createActivity() {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateActivity")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"name\":\"tag-test-activity\"}")
                .when().post("/");
        resp.then().statusCode(200);
        activityArn = resp.jsonPath().getString("activityArn");
        assertNotNull(activityArn);
    }

    // ──────────────── REST path (/tags/{arn}) — Terraform wire ────────────────

    @Test
    @Order(3)
    void rest_listTagsReturnEmptyForNewStateMachine() {
        given()
                .get("/tags/" + stateMachineArn)
                .then()
                .statusCode(200)
                .body("tags", anEmptyMap());
    }

    @Test
    @Order(4)
    void rest_tagStateMachine() {
        given()
                .contentType("application/json")
                .body("{\"tags\":{\"env\":\"test\",\"team\":\"platform\"}}")
                .post("/tags/" + stateMachineArn)
                .then()
                .statusCode(204);
    }

    @Test
    @Order(5)
    void rest_listTagsForStateMachineReturnsAddedTags() {
        given()
                .get("/tags/" + stateMachineArn)
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("test"))
                .body("tags.team", equalTo("platform"));
    }

    @Test
    @Order(6)
    void rest_untagStateMachine() {
        given()
                .delete("/tags/" + stateMachineArn + "?tagKeys=team")
                .then()
                .statusCode(204);
    }

    @Test
    @Order(7)
    void rest_listTagsAfterUntagHasOnlyRemainingKeys() {
        given()
                .get("/tags/" + stateMachineArn)
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("test"))
                .body("tags.team", nullValue());
    }

    // ──────────────── JSON 1.0 path (X-Amz-Target) — SDK wire ────────────────

    @Test
    @Order(8)
    void json_listTagsForResourceReturnsCurrentTags() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.ListTagsForResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + stateMachineArn + "\"}")
                .post("/")
                .then()
                .statusCode(200)
                .body("tags", hasSize(1))
                .body("tags[0].key", equalTo("env"))
                .body("tags[0].value", equalTo("test"));
    }

    @Test
    @Order(9)
    void json_tagResourceAddsTag() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.TagResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + stateMachineArn + "\",\"tags\":[{\"key\":\"owner\",\"value\":\"infra\"}]}")
                .post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "AWSStepFunctions.ListTagsForResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + stateMachineArn + "\"}")
                .post("/")
                .then()
                .statusCode(200)
                .body("tags", hasSize(2));
    }

    @Test
    @Order(10)
    void json_untagResourceRemovesTag() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.UntagResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + stateMachineArn + "\",\"tagKeys\":[\"owner\"]}")
                .post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "AWSStepFunctions.ListTagsForResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + stateMachineArn + "\"}")
                .post("/")
                .then()
                .statusCode(200)
                .body("tags", hasSize(1));
    }

    // ──────────────── Activity tags ────────────────

    @Test
    @Order(11)
    void rest_listTagsReturnEmptyForNewActivity() {
        given()
                .get("/tags/" + activityArn)
                .then()
                .statusCode(200)
                .body("tags", anEmptyMap());
    }

    @Test
    @Order(12)
    void rest_tagActivity() {
        given()
                .contentType("application/json")
                .body("{\"tags\":{\"owner\":\"infra\"}}")
                .post("/tags/" + activityArn)
                .then()
                .statusCode(204);
    }

    @Test
    @Order(13)
    void rest_listTagsForActivityReturnsAddedTags() {
        given()
                .get("/tags/" + activityArn)
                .then()
                .statusCode(200)
                .body("tags.owner", equalTo("infra"));
    }

    @Test
    @Order(14)
    void cleanup() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.DeleteStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"stateMachineArn\":\"" + stateMachineArn + "\"}")
                .post("/")
                .then().statusCode(200);

        given()
                .header("X-Amz-Target", "AWSStepFunctions.DeleteActivity")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"activityArn\":\"" + activityArn + "\"}")
                .post("/")
                .then().statusCode(200);
    }
}
