package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * UpdateStateMachine — the AWS API action and the behavior CloudFormation relies on when a stack
 * update re-provisions an AWS::StepFunctions::StateMachine (previously Floci always called
 * CreateStateMachine, which failed with StateMachineAlreadyExists and rolled the stack update back).
 */
@QuarkusTest
class StepFunctionsUpdateStateMachineIntegrationTest {

    private static final String CT = "application/x-amz-json-1.0";
    private static final String DEF = "{\\\"StartAt\\\":\\\"D\\\",\\\"States\\\":{\\\"D\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"End\\\":true}}}";
    private static final String DEF2 = "{\\\"StartAt\\\":\\\"W\\\",\\\"States\\\":{\\\"W\\\":{\\\"Type\\\":\\\"Wait\\\",\\\"Seconds\\\":1,\\\"End\\\":true}}}";

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String target, String body) {
        return given().header("X-Amz-Target", "AWSStepFunctions." + target).contentType(CT).body(body).when().post("/");
    }

    private static String createStateMachine(String name) {
        return call("CreateStateMachine",
                "{\"name\":\"" + name + "\",\"definition\":\"" + DEF + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/r1\"}")
                .then().statusCode(200).extract().jsonPath().getString("stateMachineArn");
    }

    @Test
    void updateChangesDefinitionAndRoleArn() {
        String arn = createStateMachine("upd-def-" + System.currentTimeMillis());

        call("UpdateStateMachine",
                "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\"" + DEF2 + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/r2\"}")
                .then().statusCode(200).body("updateDate", notNullValue());

        // The update is reflected in DescribeStateMachine.
        call("DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                .body("roleArn", is("arn:aws:iam::000000000000:role/r2"))
                .body("definition", containsString("\"Wait\""));
    }

    @Test
    void directUpdatePreservesExistingTags() {
        String name = "upd-tags-" + System.currentTimeMillis();
        String arn = call("CreateStateMachine",
                "{\"name\":\"" + name + "\",\"definition\":\"" + DEF
                        + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/r1\","
                        + "\"tags\":[{\"key\":\"owner\",\"value\":\"platform\"},"
                        + "{\"key\":\"stage\",\"value\":\"test\"}]}")
                .then().statusCode(200).extract().jsonPath().getString("stateMachineArn");

        call("UpdateStateMachine",
                "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\"" + DEF2 + "\"}")
                .then().statusCode(200);

        call("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                .body("tags.size()", is(2))
                .body("tags.find { it.key == 'owner' }.value", is("platform"))
                .body("tags.find { it.key == 'stage' }.value", is("test"));
    }

    @Test
    void updateMissingStateMachineReturnsStateMachineDoesNotExist() {
        String missing = "arn:aws:states:us-east-1:000000000000:stateMachine:missing-" + System.currentTimeMillis();
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + missing + "\",\"definition\":\"" + DEF + "\"}")
                .then().statusCode(400).body(containsString("StateMachineDoesNotExist"));
    }

    @Test
    void updateWithoutDefinitionOrRoleArnReturnsMissingRequiredParameter() {
        String arn = createStateMachine("upd-empty-" + System.currentTimeMillis());
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(400).body(containsString("MissingRequiredParameter"));
    }

    @Test
    void updateWithPublishReturnsVersionArn() {
        String arn = createStateMachine("upd-pub-" + System.currentTimeMillis());
        call("UpdateStateMachine",
                "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\"" + DEF2 + "\",\"publish\":true}")
                .then().statusCode(200)
                .body("updateDate", notNullValue())
                .body("stateMachineVersionArn", containsString(arn + ":1"));
    }
}
