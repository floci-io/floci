package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * State machine version APIs — the Terraform AWS provider calls ListStateMachineVersions after
 * creating an aws_sfn_state_machine; previously Floci returned UnsupportedOperation.
 */
@QuarkusTest
class StepFunctionsVersionsIntegrationTest {

    private static final String CT = "application/x-amz-json-1.0";
    private static final String DEF = "{\\\"StartAt\\\":\\\"D\\\",\\\"States\\\":{\\\"D\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"End\\\":true}}}";

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String target, String body) {
        return given().header("X-Amz-Target", "AWSStepFunctions." + target).contentType(CT).body(body).when().post("/");
    }

    @Test
    void listVersionsEmptyByDefaultThenPublishAndDelete() {
        String name = "ver-test-" + System.currentTimeMillis();
        String arn = call("CreateStateMachine",
                "{\"name\":\"" + name + "\",\"definition\":\"" + DEF + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/r\"}")
                .then().statusCode(200).extract().jsonPath().getString("stateMachineArn");

        // No versions published yet -> empty list (the call that previously failed).
        call("ListStateMachineVersions", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200).body("stateMachineVersions", is(java.util.Collections.emptyList()));

        // Publish a version.
        String versionArn = call("PublishStateMachineVersion", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200).body("stateMachineVersionArn", containsString(arn + ":1"))
                .extract().jsonPath().getString("stateMachineVersionArn");

        call("ListStateMachineVersions", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200).body("stateMachineVersions[0].stateMachineVersionArn", is(versionArn));

        // Delete it.
        call("DeleteStateMachineVersion", "{\"stateMachineVersionArn\":\"" + versionArn + "\"}")
                .then().statusCode(200);
        call("ListStateMachineVersions", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200).body("stateMachineVersions", is(java.util.Collections.emptyList()));
    }

    @Test
    void createWithPublishReturnsVersionArn() {
        String name = "ver-pub-" + System.currentTimeMillis();
        call("CreateStateMachine",
                "{\"name\":\"" + name + "\",\"definition\":\"" + DEF + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/r\",\"publish\":true}")
                .then().statusCode(200)
                .body("stateMachineVersionArn", not(emptyOrNullString()))
                .body("stateMachineVersionArn", containsString(":1"));
    }
}
