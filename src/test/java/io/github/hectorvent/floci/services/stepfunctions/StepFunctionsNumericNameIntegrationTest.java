package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * A state machine ARN and a state machine <em>version</em> ARN differ by one trailing segment:
 * {@code stateMachine:<name>} against {@code stateMachine:<name>:<version>}. Deciding between them
 * by asking whether the last segment is all digits misreads a numeric name, and digits are legal
 * in a state machine name. Describing a machine called {@code 2024} used to answer InvalidArn,
 * because the numeric tail was stripped as a version and the nameless remainder failed validation.
 */
@QuarkusTest
class StepFunctionsNumericNameIntegrationTest {

    private static final String CT = "application/x-amz-json-1.0";
    private static final String DEF =
            "{\\\"StartAt\\\":\\\"D\\\",\\\"States\\\":{\\\"D\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"End\\\":true}}}";

    /** @QuarkusTest classes share one emulator, so anything created here is removed again. */
    private final List<String> created = new ArrayList<>();

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void deleteCreated() {
        for (String arn : created) {
            call("DeleteStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}");
        }
        created.clear();
    }

    private static Response call(String target, String body) {
        return given().header("X-Amz-Target", "AWSStepFunctions." + target)
                .contentType(CT).body(body).when().post("/");
    }

    private String create(String name) {
        String arn = call("CreateStateMachine",
                "{\"name\":\"" + name + "\",\"definition\":\"" + DEF
                        + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/r\"}")
                .then().statusCode(200).extract().jsonPath().getString("stateMachineArn");
        created.add(arn);
        return arn;
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024", "1", "00", "42"})
    void describesAStateMachineWhoseNameIsAllDigits(String name) {
        String arn = create(name);

        call("DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}")
            .then()
                .statusCode(200)
                .body("name", equalTo(name));
    }

    /** The version ARN it was being confused with must still resolve as a version. */
    @Test
    void stillResolvesARealVersionArn() {
        String name = "numeric-versioned-" + System.nanoTime();
        String arn = create(name);

        String versionArn = call("PublishStateMachineVersion",
                "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200).extract().jsonPath().getString("stateMachineVersionArn");

        call("DescribeStateMachine", "{\"stateMachineArn\":\"" + versionArn + "\"}")
            .then()
                .statusCode(200)
                .body("name", equalTo(name));
    }

    /** A version suffix on a machine that does not exist is still not found, not InvalidArn. */
    @Test
    void reportsAMissingVersionAsNotFound() {
        String arn = "arn:aws:states:us-east-1:000000000000:stateMachine:no-such-machine:3";

        call("DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}")
            .then()
                .statusCode(400)
                .body(containsString("StateMachineDoesNotExist"));
    }
}
