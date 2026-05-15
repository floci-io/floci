package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SFN tag operations via the JSON 1.0 wire path
 * (X-Amz-Target: AWSStepFunctions.*), used by the AWS SDK and Terraform.
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

    // ──────────────── JSON 1.0 path (X-Amz-Target) — SDK / Terraform wire ────────────────

    @Test
    @Order(3)
    void json_listTagsForResourceReturnsEmptyForNewStateMachine() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.ListTagsForResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + stateMachineArn + "\"}")
                .post("/")
                .then()
                .statusCode(200)
                .body("tags", hasSize(0));
    }

    @Test
    @Order(4)
    void json_tagResourceAddsTag() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.TagResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + stateMachineArn + "\",\"tags\":[{\"key\":\"env\",\"value\":\"test\"},{\"key\":\"owner\",\"value\":\"infra\"}]}")
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
                .body("tags", hasSize(2))
                .body("tags.key", hasItems("env", "owner"))
                .body("tags.value", hasItems("test", "infra"));
    }

    @Test
    @Order(5)
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
                .body("tags", hasSize(1))
                .body("tags[0].key", equalTo("env"))
                .body("tags[0].value", equalTo("test"));
    }

    @Test
    @Order(6)
    void json_createStateMachine_withTags_tagsPersistedImmediately() {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"name\":\"tag-test-sm-with-tags\",\"definition\":\"" + SIMPLE_DEFINITION.replace("\"", "\\\"") + "\",\"roleArn\":\"" + ROLE_ARN + "\",\"tags\":[{\"key\":\"env\",\"value\":\"prod\"},{\"key\":\"team\",\"value\":\"platform\"}]}")
                .when().post("/");
        resp.then().statusCode(200);
        String arn = resp.jsonPath().getString("stateMachineArn");

        given()
                .header("X-Amz-Target", "AWSStepFunctions.ListTagsForResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + arn + "\"}")
                .post("/")
                .then()
                .statusCode(200)
                .body("tags", hasSize(2))
                .body("tags.key", hasItems("env", "team"))
                .body("tags.value", hasItems("prod", "platform"));

        given()
                .header("X-Amz-Target", "AWSStepFunctions.DeleteStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"stateMachineArn\":\"" + arn + "\"}")
                .post("/")
                .then().statusCode(200);
    }

    @Test
    @Order(7)
    void json_createActivity_withTags_tagsPersistedImmediately() {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateActivity")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"name\":\"tag-test-activity-with-tags\",\"tags\":[{\"key\":\"owner\",\"value\":\"infra\"}]}")
                .when().post("/");
        resp.then().statusCode(200);
        String arn = resp.jsonPath().getString("activityArn");

        given()
                .header("X-Amz-Target", "AWSStepFunctions.ListTagsForResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + arn + "\"}")
                .post("/")
                .then()
                .statusCode(200)
                .body("tags", hasSize(1))
                .body("tags[0].key", equalTo("owner"))
                .body("tags[0].value", equalTo("infra"));

        given()
                .header("X-Amz-Target", "AWSStepFunctions.DeleteActivity")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"activityArn\":\"" + arn + "\"}")
                .post("/")
                .then().statusCode(200);
    }

    @Test
    @Order(8)
    void json_tagResource_tagsNotArray_returns400() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.TagResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + stateMachineArn + "\",\"tags\":\"not-an-array\"}")
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(9)
    void json_untagResource_tagKeysNotArray_returns400() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.UntagResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + stateMachineArn + "\",\"tagKeys\":\"not-an-array\"}")
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(10)
    void json_listTags_unknownArn_returns400() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.ListTagsForResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"arn:aws:states:us-east-1:000000000000:stateMachine:does-not-exist\"}")
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFound"));
    }

    @Test
    @Order(11)
    void json_tagResource_unknownArn_returns400() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.TagResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"arn:aws:states:us-east-1:000000000000:stateMachine:does-not-exist\",\"tags\":[{\"key\":\"k\",\"value\":\"v\"}]}")
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFound"));
    }

    @Test
    @Order(12)
    void json_untagResource_unknownArn_returns400() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.UntagResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"arn:aws:states:us-east-1:000000000000:stateMachine:does-not-exist\",\"tagKeys\":[\"k\"]}")
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFound"));
    }

    @Test
    @Order(13)
    void json_tagResource_activity_addsTag() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.TagResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + activityArn + "\",\"tags\":[{\"key\":\"env\",\"value\":\"test\"}]}")
                .post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "AWSStepFunctions.ListTagsForResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + activityArn + "\"}")
                .post("/")
                .then()
                .statusCode(200)
                .body("tags", hasSize(1))
                .body("tags[0].key", equalTo("env"))
                .body("tags[0].value", equalTo("test"));
    }

    @Test
    @Order(14)
    void json_untagResource_activity_removesTag() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.UntagResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + activityArn + "\",\"tagKeys\":[\"env\"]}")
                .post("/")
                .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", "AWSStepFunctions.ListTagsForResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + activityArn + "\"}")
                .post("/")
                .then()
                .statusCode(200)
                .body("tags", hasSize(0));
    }

    @Test
    @Order(15)
    void json_tagResource_overwritesExistingKey() {
        given()
                .header("X-Amz-Target", "AWSStepFunctions.TagResource")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"resourceArn\":\"" + stateMachineArn + "\",\"tags\":[{\"key\":\"env\",\"value\":\"updated\"}]}")
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
                .body("tags", hasSize(1))
                .body("tags[0].key", equalTo("env"))
                .body("tags[0].value", equalTo("updated"));
    }

    @Test
    @Order(16)
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
