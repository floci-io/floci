package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
class CloudFormationStepFunctionsCleanupIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";

    @InjectSpy
    StepFunctionsService stepFunctionsService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void permanentReplacementCleanupFailureKeepsCommittedUpdateAndWarns() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "sfn-cleanup-warning-" + suffix;
        String oldName = "sfn-cleanup-old-" + suffix;
        String newName = "sfn-cleanup-new-" + suffix;
        String exportName = "sfn-cleanup-export-" + suffix;
        String oldArn = stateMachineArn(oldName);
        String newArn = stateMachineArn(newName);

        createStack(stackName, template(oldName, "old-definition", "old-output", exportName));

        Mockito.doThrow(new IllegalStateException("simulated cleanup failure"))
                .when(stepFunctionsService)
                .deleteStateMachineIfRevisionMatches(eq(oldArn), anyString());

        updateStack(stackName, template(newName, "new-definition", "new-output", exportName));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(containsString("<StackStatusReason>"))
            .body(containsString(oldArn))
            .body(containsString("<OutputValue>new-output</OutputValue>"))
            .body(not(containsString("<OutputValue>old-output</OutputValue>")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListExports")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Name>" + exportName + "</Name>"))
            .body(containsString("<Value>new-output</Value>"))
            .body(not(containsString("<Value>old-output</Value>")));

        assertStateMachine(oldArn, "old-definition");
        assertStateMachine(newArn, "new-definition");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplate")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(newName))
            .body(not(containsString(oldName)));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
            .then()
            .statusCode(200)
            .body(containsString("<PhysicalResourceId>" + oldArn + "</PhysicalResourceId>"))
            .body(containsString("<ResourceStatus>DELETE_IN_PROGRESS</ResourceStatus>"))
            .body(containsString("<ResourceStatus>DELETE_FAILED</ResourceStatus>"));

        verify(stepFunctionsService, times(3))
                .deleteStateMachineIfRevisionMatches(eq(oldArn), anyString());

        Mockito.doCallRealMethod()
                .when(stepFunctionsService)
                .deleteStateMachineIfRevisionMatches(eq(oldArn), anyString());
        deleteStack(stackName);
        stepFunctionsService.deleteStateMachine(oldArn);
    }

    @Test
    void transientReplacementCleanupFailureSucceedsOnThirdAttempt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "sfn-cleanup-retry-" + suffix;
        String oldName = "sfn-cleanup-retry-old-" + suffix;
        String newName = "sfn-cleanup-retry-new-" + suffix;
        String exportName = "sfn-cleanup-retry-export-" + suffix;
        String oldArn = stateMachineArn(oldName);
        String newArn = stateMachineArn(newName);

        createStack(stackName, template(oldName, "old-definition", "old-output", exportName));

        Mockito.doThrow(new IllegalStateException("first cleanup failure"))
                .doThrow(new IllegalStateException("second cleanup failure"))
                .doCallRealMethod()
                .when(stepFunctionsService)
                .deleteStateMachineIfRevisionMatches(eq(oldArn), anyString());

        updateStack(stackName, template(newName, "new-definition", "new-output", exportName));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(not(containsString("<StackStatusReason>")))
            .body(containsString("<OutputValue>new-output</OutputValue>"));

        assertStateMachineMissing(oldArn);
        assertStateMachine(newArn, "new-definition");
        verify(stepFunctionsService, times(3))
                .deleteStateMachineIfRevisionMatches(eq(oldArn), anyString());

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<PhysicalResourceId>" + oldArn + "</PhysicalResourceId>"))
            .body(containsString("<ResourceStatus>DELETE_IN_PROGRESS</ResourceStatus>"))
            .body(containsString("<ResourceStatus>DELETE_COMPLETE</ResourceStatus>"))
            .body(not(containsString("<ResourceStatus>DELETE_FAILED</ResourceStatus>")));

        deleteStack(stackName);
    }

    @Test
    void updateAfterStubUpgradeProvisionsTheRealStateMachine() {
        // A stack that was created by a floci build that did not yet expand
        // AWS::Serverless::StateMachine had this resource stubbed with a non-ARN physical id
        // (<logicalId>-<8 hex>, see CloudFormationResourceProvisioner's default provisioning arm).
        // Its next update must provision the real state machine instead of failing with InvalidArn
        // when findStateMachine tries to describe that non-ARN value.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "sfn-stub-upgrade-" + suffix;
        String logicalId = "MyMachine";
        String stateMachineName = "sfn-stub-upgrade-machine-" + suffix;

        String stubbedTemplate = """
                {
                  "Resources": {
                    "%s": {
                      "Type": "AWS::Foo::Bar",
                      "Properties": {}
                    }
                  }
                }
                """.formatted(logicalId);
        createStack(stackName, stubbedTemplate);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<PhysicalResourceId>" + logicalId + "-"));

        String upgradedTemplate = """
                {
                  "Resources": {
                    "%s": {
                      "Type": "AWS::StepFunctions::StateMachine",
                      "Properties": {
                        "StateMachineName": "%s",
                        "RoleArn": "arn:aws:iam::000000000000:role/sfn-stub-upgrade-role",
                        "DefinitionString": "{\\"StartAt\\":\\"Done\\",\\"States\\":{\\"Done\\":{\\"Type\\":\\"Pass\\",\\"End\\":true}}}"
                      }
                    }
                  }
                }
                """.formatted(logicalId, stateMachineName);
        updateStack(stackName, upgradedTemplate);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"));

        assertStateMachine(stateMachineArn(stateMachineName), "Done");

        deleteStack(stackName);
    }

    @Test
    void replacementCleanupWithMalformedPreviousArnReportsFailureInsteadOfSilentSuccess() {
        // findStateMachine's InvalidArn widening exists for the stub-upgrade path above (the
        // provisioning entry point, :4144, reading a pre-SAM-expansion stub physical id). The
        // cleanup snapshot read here (:4512) carries an ARN this floci build itself recorded, so
        // an InvalidArn from describeStateMachine on it is a real anomaly, not a legitimate
        // "already gone" case, and must not be read as cleanup success. The first
        // describeStateMachine(oldArn) call belongs to the provisioning entry point (real
        // behaviour, the old machine genuinely still exists there); only the second, from cleanup,
        // is forced to simulate the anomaly.
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "sfn-cleanup-invalidarn-" + suffix;
        String oldName = "sfn-cleanup-invalidarn-old-" + suffix;
        String newName = "sfn-cleanup-invalidarn-new-" + suffix;
        String exportName = "sfn-cleanup-invalidarn-export-" + suffix;
        String oldArn = stateMachineArn(oldName);
        String newArn = stateMachineArn(newName);

        createStack(stackName, template(oldName, "old-definition", "old-output", exportName));

        Mockito.doCallRealMethod()
                .doThrow(new AwsException("InvalidArn", "simulated malformed ARN", 400))
                .when(stepFunctionsService)
                .describeStateMachine(eq(oldArn));

        updateStack(stackName, template(newName, "new-definition", "new-output", exportName));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(containsString("<StackStatusReason>"))
            .body(containsString(oldArn));

        verify(stepFunctionsService, never())
                .deleteStateMachineIfRevisionMatches(eq(oldArn), anyString());
        assertStateMachine(newArn, "new-definition");

        Mockito.doCallRealMethod()
                .when(stepFunctionsService)
                .describeStateMachine(eq(oldArn));
        deleteStack(stackName);
        stepFunctionsService.deleteStateMachine(oldArn);
    }

    private static void createStack(String stackName, String templateBody) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", templateBody)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void updateStack(String stackName, String templateBody) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", templateBody)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void assertStateMachine(String arn, String definitionMarker) {
        given()
            .header("X-Amz-Target", "AWSStepFunctions.DescribeStateMachine")
            .contentType(SFN_CONTENT_TYPE)
            .body("{\"stateMachineArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("definition", containsString(definitionMarker));
    }

    private static void assertStateMachineMissing(String arn) {
        given()
            .header("X-Amz-Target", "AWSStepFunctions.DescribeStateMachine")
            .contentType(SFN_CONTENT_TYPE)
            .body("{\"stateMachineArn\":\"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("StateMachineDoesNotExist"));
    }

    private static String stateMachineArn(String name) {
        return "arn:aws:states:us-east-1:000000000000:stateMachine:" + name;
    }

    private static String template(
            String stateMachineName,
            String definitionMarker,
            String outputValue,
            String exportName) {
        return """
                {
                  "Resources": {
                    "MyStateMachine": {
                      "Type": "AWS::StepFunctions::StateMachine",
                      "Properties": {
                        "StateMachineName": "%s",
                        "RoleArn": "arn:aws:iam::000000000000:role/cfn-sfn-cleanup-role",
                        "DefinitionString": "{\\"StartAt\\":\\"Done\\",\\"States\\":{\\"Done\\":{\\"Type\\":\\"Pass\\",\\"Result\\":\\"%s\\",\\"End\\":true}}}"
                      }
                    }
                  },
                  "Outputs": {
                    "Marker": {
                      "Value": "%s",
                      "Export": {"Name": "%s"}
                    }
                  }
                }
                """.formatted(
                        stateMachineName, definitionMarker, outputValue, exportName);
    }
}
