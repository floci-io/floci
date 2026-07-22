package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    private static String quote(String value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
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

    @Test
    void updateValidatesRequiredAndMalformedArns() {
        call("UpdateStateMachine", "{\"definition\":\"" + DEF2 + "\"}")
                .then().statusCode(400).body(containsString("MissingRequiredParameter"));

        call("UpdateStateMachine", "{\"stateMachineArn\":\"\",\"definition\":\"" + DEF2 + "\"}")
                .then().statusCode(400).body(containsString("InvalidArn"));

        call("UpdateStateMachine", "{\"stateMachineArn\":\"not-an-arn\",\"definition\":\""
                + DEF2 + "\"}")
                .then().statusCode(400).body(containsString("InvalidArn"));

        call("UpdateStateMachine", "{\"stateMachineArn\":42,\"definition\":\"" + DEF2 + "\"}")
                .then().statusCode(400).body(containsString("ValidationException"));

        String distributedMapArn = "arn:aws:states:us-east-1:000000000000:stateMachine:machine/map-label";
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + distributedMapArn
                + "\",\"definition\":\"" + DEF2 + "\"}")
                .then().statusCode(400).body(containsString("ValidationException"));

        String longNameMapArn = "arn:aws:states:us-east-1:000000000000:stateMachine:"
                + "m".repeat(80) + "/MapState";
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + longNameMapArn
                + "\",\"definition\":\"" + DEF2 + "\"}")
                .then().statusCode(400).body(containsString("ValidationException"));
    }

    @Test
    void rejectedInputsDoNotMutateTheStateMachine() {
        String arn = createStateMachine("upd-invalid-" + System.currentTimeMillis());
        Response successful = call("UpdateStateMachine",
                "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\"" + DEF2 + "\"}");
        successful.then().statusCode(200);
        String revision = successful.jsonPath().getString("revisionId");

        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"roleArn\":\"\"}")
                .then().statusCode(400).body(containsString("InvalidArn"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn
                + "\",\"roleArn\":\"arn:aws:s3:::not-a-role\"}")
                .then().statusCode(400).body(containsString("InvalidArn"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\"\"}")
                .then().statusCode(400).body(containsString("InvalidDefinition"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\"{}\"}")
                .then().statusCode(400).body(containsString("InvalidDefinition"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn
                + "\",\"definition\":\"{\\\"StartAt\\\":\\\"A\\\",\\\"States\\\":{\\\"A\\\":{}}}\"}")
                .then().statusCode(400).body(containsString("InvalidDefinition"));

        String oversized = "x".repeat(1_048_577);
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"definition\":"
                + quote(oversized) + "}")
                .then().statusCode(400).body(containsString("ValidationException"));

        Response after = call("DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}");
        after.then().statusCode(200)
                .body("definition", containsString("\"Wait\""))
                .body("roleArn", is("arn:aws:iam::000000000000:role/r1"));
        assertEquals(revision, after.jsonPath().getString("revisionId"));
    }

    @Test
    void updateRotatesRevisionAndRoundTripsConfigurations() {
        String arn = createStateMachine("upd-config-" + System.currentTimeMillis());
        String initialRevision = call("DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200).extract().jsonPath().getString("revisionId");

        Response update = call("UpdateStateMachine", """
                {
                  "stateMachineArn":"%s",
                  "definition":"%s",
                  "loggingConfiguration":{
                    "level":"ALL",
                    "includeExecutionData":true,
                    "destinations":[{
                      "cloudWatchLogsLogGroup":{
                        "logGroupArn":"arn:aws:logs:us-east-1:000000000000:log-group:sfn:*"
                      }
                    }]
                  },
                  "tracingConfiguration":{"enabled":true},
                  "encryptionConfiguration":{
                    "type":"CUSTOMER_MANAGED_KMS_KEY",
                    "kmsKeyId":"alias/sfn-key",
                    "kmsDataKeyReusePeriodSeconds":120
                  }
                }
                """.formatted(arn, DEF2));
        update.then().statusCode(200)
                .body("revisionId", notNullValue());
        String updatedRevision = update.jsonPath().getString("revisionId");
        assertNotEquals(initialRevision, updatedRevision);

        call("DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                .body("revisionId", is(updatedRevision))
                .body("loggingConfiguration.level", is("ALL"))
                .body("loggingConfiguration.includeExecutionData", is(true))
                .body("tracingConfiguration.enabled", is(true))
                .body("encryptionConfiguration.type", is("CUSTOMER_MANAGED_KMS_KEY"))
                .body("encryptionConfiguration.kmsKeyId", is("alias/sfn-key"))
                .body("encryptionConfiguration.kmsDataKeyReusePeriodSeconds", is(120));
    }

    @Test
    void invalidConfigurationsAreTypedErrorsAndDoNotMutate() {
        String arn = createStateMachine("upd-invalid-config-" + System.currentTimeMillis());
        String initialRevision = call("DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200).extract().jsonPath().getString("revisionId");

        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\""
                + DEF2 + "\",\"loggingConfiguration\":{\"level\":\"TRACE\"}}")
                .then().statusCode(400).body(containsString("InvalidLoggingConfiguration"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\""
                + DEF2 + "\",\"loggingConfiguration\":{\"level\":null}}")
                .then().statusCode(400).body(containsString("InvalidLoggingConfiguration"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\""
                + DEF2 + "\",\"tracingConfiguration\":{\"enabled\":\"yes\"}}")
                .then().statusCode(400).body(containsString("InvalidTracingConfiguration"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\""
                + DEF2 + "\",\"encryptionConfiguration\":{\"type\":\"CUSTOMER_MANAGED_KMS_KEY\"}}")
                .then().statusCode(400).body(containsString("InvalidEncryptionConfiguration"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\""
                + DEF2 + "\",\"encryptionConfiguration\":{}}")
                .then().statusCode(400).body(containsString("InvalidEncryptionConfiguration"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn
                + "\",\"tracingConfiguration\":{\"enabled\":true}}")
                .then().statusCode(400).body(containsString("MissingRequiredParameter"));

        call("DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                .body("definition", containsString("\"Pass\""))
                .body("revisionId", is(initialRevision));
    }

    @Test
    void updatePublishStoresTheDescriptionAndRevisionSnapshot() {
        String arn = createStateMachine("upd-version-" + System.currentTimeMillis());
        Response update = call("UpdateStateMachine",
                "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\"" + DEF2
                        + "\",\"publish\":true,\"versionDescription\":\"release one\"}");
        update.then().statusCode(200)
                .body("revisionId", notNullValue())
                .body("stateMachineVersionArn", is(arn + ":1"));
        String revision = update.jsonPath().getString("revisionId");

        call("UpdateStateMachine",
                "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\"" + DEF + "\"}")
                .then().statusCode(200);

        call("DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + ":1\"}")
                .then().statusCode(200)
                .body("stateMachineArn", is(arn + ":1"))
                .body("definition", containsString("\"Wait\""))
                .body("description", is("release one"))
                .body("revisionId", is(revision));
    }

    @Test
    void versionDescriptionRequiresPublishAndValidationPrecedesMutation() {
        String arn = createStateMachine("upd-description-" + System.currentTimeMillis());
        String initialRevision = call("DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200).extract().jsonPath().getString("revisionId");

        call("UpdateStateMachine",
                "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\"" + DEF2
                        + "\",\"versionDescription\":\"not published\"}")
                .then().statusCode(400).body(containsString("ValidationException"));

        call("DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                .body("definition", containsString("\"Pass\""))
                .body("revisionId", is(initialRevision));
    }
}
