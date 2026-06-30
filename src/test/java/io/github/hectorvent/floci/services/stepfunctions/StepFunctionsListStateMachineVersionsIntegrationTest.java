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
class StepFunctionsListStateMachineVersionsIntegrationTest {

    private static final String CT = "application/x-amz-json-1.0";
    private static final String TARGET = "AWSStepFunctions.ListStateMachineVersions";
    private static final String CREATE_TARGET = "AWSStepFunctions.CreateStateMachine";
    private static final String DELETE_TARGET = "AWSStepFunctions.DeleteStateMachine";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String SIMPLE_DEFINITION =
            "{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"End\":true}}}";

    private static String stateMachineArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createStateMachine() {
        Response resp = given()
                .header("X-Amz-Target", CREATE_TARGET)
                .contentType(CT)
                .body("{\"name\":\"versions-test-sm\",\"definition\":\"" + SIMPLE_DEFINITION.replace("\"", "\\\"") + "\",\"roleArn\":\"" + ROLE_ARN + "\"}")
                .when().post("/");
        resp.then().statusCode(200);
        stateMachineArn = resp.jsonPath().getString("stateMachineArn");
        assertNotNull(stateMachineArn);
    }

    @Test
    @Order(2)
    void listVersions_existingMachine_returns200() {
        given()
                .header("X-Amz-Target", TARGET)
                .contentType(CT)
                .body("{\"stateMachineArn\":\"" + stateMachineArn + "\"}")
                .when().post("/")
                .then()
                .statusCode(200)
                .body("stateMachineVersions", notNullValue())
                .body("stateMachineVersions[0].stateMachineVersionArn", equalTo(stateMachineArn + ":1"))
                .body("stateMachineVersions[0].creationDate", notNullValue());
    }

    @Test
    @Order(3)
    void listVersions_nonExistentArn_returnsStateMachineDoesNotExist() {
        given()
                .header("X-Amz-Target", TARGET)
                .contentType(CT)
                .body("{\"stateMachineArn\":\"arn:aws:states:us-east-1:000000000000:stateMachine:does-not-exist\"}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", containsString("StateMachineDoesNotExist"));
    }

    @Test
    @Order(4)
    void listVersions_missingArn_returnsStateMachineDoesNotExist() {
        given()
                .header("X-Amz-Target", TARGET)
                .contentType(CT)
                .body("{}")
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", containsString("StateMachineDoesNotExist"));
    }

    @Test
    @Order(5)
    void cleanup() {
        given()
                .header("X-Amz-Target", DELETE_TARGET)
                .contentType(CT)
                .body("{\"stateMachineArn\":\"" + stateMachineArn + "\"}")
                .when().post("/")
                .then().statusCode(200);
    }
}
