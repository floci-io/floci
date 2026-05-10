package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsTagsIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String SIMPLE_DEFINITION = """
            {"Comment":"tag test","StartAt":"Done","States":{"Done":{"Type":"Pass","End":true}}}
            """.strip();

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
                .body("""
                        {"name":"tag-test-sm","definition":"%s","roleArn":"%s"}
                        """.formatted(SIMPLE_DEFINITION.replace("\"", "\\\""), ROLE_ARN))
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

    @Test
    @Order(3)
    void listTagsReturnEmptyForNewStateMachine() {
        given()
                .get("/tags/" + stateMachineArn)
                .then()
                .statusCode(200)
                .body("tags", anEmptyMap());
    }

    @Test
    @Order(4)
    void tagStateMachine() {
        given()
                .contentType("application/json")
                .body("{\"tags\":{\"env\":\"test\",\"team\":\"platform\"}}")
                .post("/tags/" + stateMachineArn)
                .then()
                .statusCode(204);
    }

    @Test
    @Order(5)
    void listTagsForStateMachineReturnsAddedTags() {
        given()
                .get("/tags/" + stateMachineArn)
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("test"))
                .body("tags.team", equalTo("platform"));
    }

    @Test
    @Order(6)
    void untagStateMachine() {
        given()
                .delete("/tags/" + stateMachineArn + "?tagKeys=team")
                .then()
                .statusCode(204);
    }

    @Test
    @Order(7)
    void listTagsAfterUntagHasOnlyRemainingKeys() {
        given()
                .get("/tags/" + stateMachineArn)
                .then()
                .statusCode(200)
                .body("tags.env", equalTo("test"))
                .body("tags.team", nullValue());
    }

    @Test
    @Order(8)
    void listTagsReturnEmptyForNewActivity() {
        given()
                .get("/tags/" + activityArn)
                .then()
                .statusCode(200)
                .body("tags", anEmptyMap());
    }

    @Test
    @Order(9)
    void tagActivity() {
        given()
                .contentType("application/json")
                .body("{\"tags\":{\"owner\":\"infra\"}}")
                .post("/tags/" + activityArn)
                .then()
                .statusCode(204);
    }

    @Test
    @Order(10)
    void listTagsForActivityReturnsAddedTags() {
        given()
                .get("/tags/" + activityArn)
                .then()
                .statusCode(200)
                .body("tags.owner", equalTo("infra"));
    }

    @Test
    @Order(11)
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
