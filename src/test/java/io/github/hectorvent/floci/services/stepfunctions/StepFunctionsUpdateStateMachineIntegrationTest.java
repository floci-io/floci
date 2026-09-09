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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        Response update = call("UpdateStateMachine",
                "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\"" + DEF2 + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/r2\"}")
                .then().statusCode(200)
                .body("updateDate", notNullValue())
                .body("revisionId", notNullValue())
                .extract().response();
        assertTrue(update.asString().contains("\"stateMachineVersionArn\":null"));

        // The update is reflected in DescribeStateMachine.
        Response describe = call("DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                .body("roleArn", is("arn:aws:iam::000000000000:role/r2"))
                .body("definition", containsString("\"Wait\""))
                .extract().response();
        assertFalse(describe.jsonPath().getMap("").containsKey("updateDate"));
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
        Response published = call("UpdateStateMachine", """
                {
                  "stateMachineArn":"%s",
                  "definition":"%s",
                  "roleArn":"arn:aws:iam::000000000000:role/r2",
                  "loggingConfiguration":{
                    "level":"ALL",
                    "includeExecutionData":true,
                    "destinations":[{
                      "cloudWatchLogsLogGroup":{
                        "logGroupArn":"arn:aws:logs:us-east-1:000000000000:log-group:version:*"
                      }
                    }]
                  },
                  "tracingConfiguration":{"enabled":true},
                  "encryptionConfiguration":{
                    "type":"CUSTOMER_MANAGED_KMS_KEY",
                    "kmsKeyId":"alias/version-key",
                    "kmsDataKeyReusePeriodSeconds":120
                  },
                  "publish":true,
                  "versionDescription":"first update"
                }
                """.formatted(arn, DEF2));
        published.then().statusCode(200)
                .body("updateDate", notNullValue())
                .body("revisionId", notNullValue())
                .body("stateMachineVersionArn", containsString(arn + ":1"));
        String versionArn = published.jsonPath().getString("stateMachineVersionArn");
        String versionRevision = published.jsonPath().getString("revisionId");

        call("UpdateStateMachine", """
                {
                  "stateMachineArn":"%s",
                  "definition":"%s",
                  "roleArn":"arn:aws:iam::000000000000:role/r3",
                  "loggingConfiguration":{
                    "level":"OFF",
                    "includeExecutionData":false,
                    "destinations":[]
                  },
                  "tracingConfiguration":{"enabled":false},
                  "encryptionConfiguration":{"type":"AWS_OWNED_KEY"}
                }
                """.formatted(arn, DEF))
                .then().statusCode(200);

        call("DescribeStateMachine", "{\"stateMachineArn\":\"" + versionArn + "\"}")
                .then().statusCode(200)
                .body("stateMachineArn", is(versionArn))
                .body("creationDate", notNullValue())
                .body("definition", containsString("\"Wait\""))
                .body("roleArn", is("arn:aws:iam::000000000000:role/r2"))
                .body("revisionId", is(versionRevision))
                .body("description", is("first update"))
                .body("loggingConfiguration.level", is("ALL"))
                .body("loggingConfiguration.includeExecutionData", is(true))
                .body("tracingConfiguration.enabled", is(true))
                .body("encryptionConfiguration.type", is("CUSTOMER_MANAGED_KMS_KEY"))
                .body("encryptionConfiguration.kmsKeyId", is("alias/version-key"))
                .body("encryptionConfiguration.kmsDataKeyReusePeriodSeconds", is(120));

        call("DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200)
                .body("definition", containsString("\"Pass\""))
                .body("roleArn", is("arn:aws:iam::000000000000:role/r3"))
                .body("loggingConfiguration.level", is("OFF"))
                .body("tracingConfiguration.enabled", is(false))
                .body("encryptionConfiguration.type", is("AWS_OWNED_KEY"));

        call("DeleteStateMachineVersion",
                "{\"stateMachineVersionArn\":\"" + versionArn + "\"}")
                .then().statusCode(200);
        call("DescribeStateMachine", "{\"stateMachineArn\":\"" + versionArn + "\"}")
                .then().statusCode(400)
                .body(containsString("StateMachineDoesNotExist"));
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

        String distributedMapArn =
                "arn:aws:states:us-east-1:000000000000:stateMachine:machine/map-label";
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + distributedMapArn
                + "\",\"definition\":\"" + DEF2 + "\"}")
                .then().statusCode(400).body(containsString("ValidationException"));
    }

    @Test
    void rejectedInputsDoNotMutateTheStateMachine() {
        String arn = createStateMachine("upd-invalid-" + System.currentTimeMillis());
        String initialRevision = call(
                "DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}")
                .then().statusCode(200).extract().jsonPath().getString("revisionId");

        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"roleArn\":\"\"}")
                .then().statusCode(400).body(containsString("InvalidArn"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn
                + "\",\"roleArn\":\"arn:aws:s3:::not-a-role\"}")
                .then().statusCode(400).body(containsString("InvalidArn"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\"\"}")
                .then().statusCode(400).body(containsString("InvalidDefinition"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn
                + "\",\"definition\":\"{}\"}")
                .then().statusCode(400).body(containsString("InvalidDefinition"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn
                + "\",\"definition\":\"{\\\"States\\\":{\\\"A\\\":{"
                + "\\\"Type\\\":\\\"Pass\\\",\\\"End\\\":true}}}\"}")
                .then().statusCode(400).body(containsString("InvalidDefinition"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn
                + "\",\"definition\":\"{\\\"StartAt\\\":\\\"A\\\","
                + "\\\"States\\\":{\\\"A\\\":{\\\"End\\\":true}}}\"}")
                .then().statusCode(400).body(containsString("InvalidDefinition"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\""
                + DEF2 + "\",\"loggingConfiguration\":{\"level\":\"TRACE\"}}")
                .then().statusCode(400).body(containsString("InvalidLoggingConfiguration"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\""
                + DEF2 + "\",\"tracingConfiguration\":{\"enabled\":\"yes\"}}")
                .then().statusCode(400).body(containsString("InvalidTracingConfiguration"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\""
                + DEF2
                + "\",\"encryptionConfiguration\":{\"type\":\"CUSTOMER_MANAGED_KMS_KEY\"}}")
                .then().statusCode(400).body(containsString("InvalidEncryptionConfiguration"));
        call("UpdateStateMachine", "{\"stateMachineArn\":\"" + arn + "\",\"definition\":\""
                + DEF2 + "\",\"versionDescription\":\"not published\"}")
                .then().statusCode(400).body(containsString("ValidationException"));

        Response after = call(
                "DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}");
        after.then().statusCode(200)
                .body("definition", containsString("\"Pass\""))
                .body("roleArn", is("arn:aws:iam::000000000000:role/r1"));
        assertEquals(initialRevision, after.jsonPath().getString("revisionId"));
    }

    @Test
    void updateRotatesRevisionAndRoundTripsConfigurations() {
        String arn = createStateMachine("upd-config-" + System.currentTimeMillis());
        Response initial = call(
                "DescribeStateMachine", "{\"stateMachineArn\":\"" + arn + "\"}");
        initial.then().statusCode(200)
                .body("loggingConfiguration.level", is("OFF"))
                .body("loggingConfiguration.includeExecutionData", is(false))
                .body("loggingConfiguration.destinations.size()", is(0))
                .body("tracingConfiguration.enabled", is(false))
                .body("encryptionConfiguration.type", is("AWS_OWNED_KEY"));
        String initialRevision = initial.jsonPath().getString("revisionId");

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
        update.then().statusCode(200).body("revisionId", notNullValue());
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

        call("DescribeStateMachine",
                "{\"stateMachineArn\":\"" + arn + "\",\"includedData\":\"METADATA_ONLY\"}")
                .then().statusCode(200)
                .body("definition", is("{}"))
                .body("encryptionConfiguration.type", is("CUSTOMER_MANAGED_KMS_KEY"));

        call("DescribeStateMachine",
                "{\"stateMachineArn\":\"" + arn + "\",\"includedData\":\"ALL_DATA\"}")
                .then().statusCode(200)
                .body("definition", containsString("\"Wait\""));

        call("DescribeStateMachine",
                "{\"stateMachineArn\":\"" + arn + "\",\"includedData\":\"INVALID\"}")
                .then().statusCode(400)
                .body(containsString("ValidationException"));

        call("DescribeStateMachine",
                "{\"stateMachineArn\":\"" + arn + "\",\"includedData\":42}")
                .then().statusCode(400)
                .body(containsString("ValidationException"));
    }

    @Test
    void createRejectsUnsupportedStateMachineTypeBeforePersistence() {
        String name = "invalid-type-" + System.currentTimeMillis();
        call("CreateStateMachine",
                "{\"name\":\"" + name + "\",\"definition\":\"" + DEF
                        + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/r1\","
                        + "\"type\":\"BASIC\"}")
                .then().statusCode(400)
                .body(containsString("StateMachineTypeNotSupported"));

        call("DescribeStateMachine", "{\"stateMachineArn\":"
                + "\"arn:aws:states:us-east-1:000000000000:stateMachine:" + name + "\"}")
                .then().statusCode(400)
                .body(containsString("StateMachineDoesNotExist"));
    }

    @Test
    void describeValidatesRequiredAndQualifiedArns() {
        call("DescribeStateMachine", "{}")
                .then().statusCode(400)
                .body(containsString("MissingRequiredParameter"));
        call("DescribeStateMachine", "{\"stateMachineArn\":42}")
                .then().statusCode(400)
                .body(containsString("ValidationException"));
        call("DescribeStateMachine", "{\"stateMachineArn\":\"not-an-arn\"}")
                .then().statusCode(400)
                .body(containsString("InvalidArn"));
        call("DescribeStateMachine", "{\"stateMachineArn\":"
                + "\"arn:aws:states:us-east-1:000000000000:"
                + "stateMachine:machine/map-label\"}")
                .then().statusCode(400)
                .body(containsString("ValidationException"));
    }
}
