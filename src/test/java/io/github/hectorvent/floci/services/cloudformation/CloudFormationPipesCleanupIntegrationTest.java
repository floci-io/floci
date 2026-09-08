package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.pipes.PipesService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Renaming an {@code AWS::Pipes::Pipe} replaces it, and the pipe left under the prior name is
 * deleted after the stack update commits, on the same update cleanup path every replaced resource
 * takes: three attempts, DELETE_IN_PROGRESS then DELETE_COMPLETE or DELETE_FAILED, and an orphan
 * named in the stack's UPDATE_COMPLETE status reason.
 */
@QuarkusTest
class CloudFormationPipesCleanupIntegrationTest {

    @InjectSpy
    PipesService pipesService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void permanentRenameCleanupFailureKeepsCommittedUpdateAndNamesTheOrphanedPipe() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "pipe-cleanup-warning-" + suffix;
        String oldName = "pipe-cleanup-old-" + suffix;
        String newName = "pipe-cleanup-new-" + suffix;

        createStack(stackName, template(oldName, suffix));

        Mockito.doThrow(new IllegalStateException("simulated cleanup failure"))
                .when(pipesService)
                .deletePipe(eq(oldName), anyString());

        updateStack(stackName, template(newName, suffix));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(containsString(
                    "The following resource(s) could not be deleted during update cleanup: "
                            + "[MyPipe (" + oldName + ")]."));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<PhysicalResourceId>" + oldName + "</PhysicalResourceId>"))
            .body(containsString("<ResourceStatus>DELETE_IN_PROGRESS</ResourceStatus>"))
            .body(containsString("<ResourceStatus>DELETE_FAILED</ResourceStatus>"));

        verify(pipesService, times(3)).deletePipe(eq(oldName), anyString());
        assertPipe(oldName);
        assertPipe(newName);

        Mockito.doCallRealMethod().when(pipesService).deletePipe(eq(oldName), anyString());
        deleteStack(stackName);
        pipesService.deletePipe(oldName, "us-east-1");
    }

    @Test
    void transientRenameCleanupFailureSucceedsOnThirdAttempt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "pipe-cleanup-retry-" + suffix;
        String oldName = "pipe-cleanup-retry-old-" + suffix;
        String newName = "pipe-cleanup-retry-new-" + suffix;

        createStack(stackName, template(oldName, suffix));

        Mockito.doThrow(new IllegalStateException("first cleanup failure"))
                .doThrow(new IllegalStateException("second cleanup failure"))
                .doCallRealMethod()
                .when(pipesService)
                .deletePipe(eq(oldName), anyString());

        updateStack(stackName, template(newName, suffix));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(not(containsString("<StackStatusReason>")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<PhysicalResourceId>" + oldName + "</PhysicalResourceId>"))
            .body(containsString(
                    "<ResourceStatus>UPDATE_COMPLETE_CLEANUP_IN_PROGRESS</ResourceStatus>"))
            .body(containsString("<ResourceStatus>DELETE_IN_PROGRESS</ResourceStatus>"))
            .body(containsString("<ResourceStatus>DELETE_COMPLETE</ResourceStatus>"))
            .body(not(containsString("<ResourceStatus>DELETE_FAILED</ResourceStatus>")));

        verify(pipesService, times(3)).deletePipe(eq(oldName), anyString());
        assertPipeMissing(oldName);
        assertPipe(newName);

        deleteStack(stackName);
    }

    /**
     * The cleanup phase runs after the update has committed, so a stack that never reaches the end
     * of it stays in UPDATE_COMPLETE_CLEANUP_IN_PROGRESS with the displaced pipe alive. That status
     * means an operation still owns the stack, so the next update is refused rather than made to
     * carry a phase it did not start. DeleteStack is the way out, and it takes the displaced pipe
     * with it.
     */
    @Test
    void anUpdateIsRefusedWhileAnInterruptedCleanupOwnsTheStack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "pipe-cleanup-refused-" + suffix;
        String oldName = "pipe-cleanup-refused-old-" + suffix;
        String newName = "pipe-cleanup-refused-new-" + suffix;

        createStack(stackName, template(oldName, suffix));

        // An Error is not a deletion failure the cleanup retries: it leaves the phase where it
        // stands, which is what the stack looks like when the process dies mid cleanup.
        Mockito.doThrow(new Error("cleanup interrupted"))
                .when(pipesService)
                .deletePipe(eq(oldName), anyString());

        updateStack(stackName, template(newName, suffix));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(
                    "<StackStatus>UPDATE_COMPLETE_CLEANUP_IN_PROGRESS</StackStatus>"));
        assertPipe(oldName);
        assertPipe(newName);

        Mockito.doCallRealMethod().when(pipesService).deletePipe(eq(oldName), anyString());

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template(newName, suffix))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString(
                    "Stack:arn:aws:cloudformation:us-east-1:000000000000:stack/" + stackName + "/"))
            .body(containsString(
                    " is in UPDATE_COMPLETE_CLEANUP_IN_PROGRESS state and can not be updated."));

        // The refusal changes nothing: the stack keeps the status and both pipes it had.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(
                    "<StackStatus>UPDATE_COMPLETE_CLEANUP_IN_PROGRESS</StackStatus>"));
        assertPipe(oldName);
        assertPipe(newName);

        deleteStack(stackName);
        assertPipeMissing(oldName);
        assertPipeMissing(newName);
    }

    /**
     * CreateChangeSet is refused on the same terms as UpdateStack: a status ending in
     * _IN_PROGRESS says an operation owns the stack, and no change set attaches to it. Nothing on
     * the stack is a way to finish the phase the interruption left open, so DeleteStack is again
     * what removes both pipes.
     */
    @Test
    void aChangeSetIsRefusedWhileAnInterruptedCleanupOwnsTheStack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "pipe-cleanup-changeset-refused-" + suffix;
        String oldName = "pipe-cleanup-changeset-refused-old-" + suffix;
        String newName = "pipe-cleanup-changeset-refused-new-" + suffix;

        createStack(stackName, template(oldName, suffix));

        Mockito.doThrow(new Error("cleanup interrupted"))
                .when(pipesService)
                .deletePipe(eq(oldName), anyString());

        updateStack(stackName, template(newName, suffix));

        Mockito.doCallRealMethod().when(pipesService).deletePipe(eq(oldName), anyString());

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", "pipe-cleanup-changeset-" + suffix)
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", template(newName, suffix))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString(
                    "Stack:arn:aws:cloudformation:us-east-1:000000000000:stack/" + stackName + "/"))
            .body(containsString(
                    " is in UPDATE_COMPLETE_CLEANUP_IN_PROGRESS state and can not be updated."));

        assertPipe(oldName);
        assertPipe(newName);

        deleteStack(stackName);
        assertPipeMissing(oldName);
        assertPipeMissing(newName);
    }

    /**
     * A rename is a replacement, and an update that fails at a later resource never commits it.
     * CloudFormation puts a replaced resource back to the configuration it had before the update,
     * so the stack resource points at the prior pipe again, the replacement the failed update
     * created is gone, and the rename cleanup recorded for the committed path never fires.
     */
    @Test
    void aRenameRolledBackPointsTheStackResourceAtThePriorPipe() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "pipe-rename-rollback-" + suffix;
        String priorName = "pipe-rename-rollback-prior-" + suffix;
        String replacementName = "pipe-rename-rollback-replacement-" + suffix;

        createStack(stackName, renameRollbackTemplate(priorName, ""));

        updateStack(stackName, renameRollbackTemplate(replacementName, """
                ,
                    "BadSecret": {
                      "Type": "AWS::SecretsManager::Secret",
                      "DependsOn": "MyPipe",
                      "Properties": {
                        "Name": "pipe-rename-rollback-secret-%s",
                        "SecretString": "explicit",
                        "GenerateSecretString": {"PasswordLength": 32}
                      }
                    }""".formatted(suffix)));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_ROLLBACK_COMPLETE</StackStatus>"))
            .body(not(containsString("Rollback is not implemented")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<PhysicalResourceId>" + priorName + "</PhysicalResourceId>"))
            .body(not(containsString(replacementName)));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("Rollback is not implemented")))
            .body(containsString(
                    "<ResourceStatusReason>Resource update rolled back</ResourceStatusReason>"));

        given()
            .contentType("application/json")
        .when()
            .get("/v1/pipes/" + priorName)
        .then()
            .statusCode(200)
            .body("Arn", equalTo("arn:aws:pipes:us-east-1:000000000000:pipe/" + priorName))
            .body("Target", equalTo("arn:aws:sqs:us-east-1:000000000000:pipe-rename-rollback-target"));
        assertPipeMissing(replacementName);
        verify(pipesService, never()).deletePipe(eq(priorName), anyString());

        deleteStack(stackName);
        assertPipeMissing(priorName);
    }

    /**
     * The rollback of a rename deletes the replacement the failed update created, and that delete
     * can fail. The pipe the rollback exists to preserve is the prior one, so the resource names it
     * and the rename cleanup is spent before the delete is attempted. The stack still reports
     * UPDATE_ROLLBACK_FAILED naming the resource, the replacement is what stays orphaned, and the
     * next update finds nothing on the resource that would delete the prior pipe.
     */
    @Test
    void aRollbackThatCannotDeleteTheReplacementStillKeepsThePriorPipe() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "pipe-rename-rollback-keep-" + suffix;
        String priorName = "pipe-rename-rollback-keep-prior-" + suffix;
        String replacementName = "pipe-rename-rollback-keep-replacement-" + suffix;

        createStack(stackName, renameRollbackTemplate(priorName, ""));

        // Only the replacement's delete fails, which is the delete the rollback performs itself.
        Mockito.doThrow(new IllegalStateException("simulated replacement delete failure"))
                .when(pipesService)
                .deletePipe(eq(replacementName), anyString());

        updateStack(stackName, renameRollbackTemplate(replacementName, """
                ,
                    "BadSecret": {
                      "Type": "AWS::SecretsManager::Secret",
                      "DependsOn": "MyPipe",
                      "Properties": {
                        "Name": "pipe-rename-rollback-keep-secret-%s",
                        "SecretString": "explicit",
                        "GenerateSecretString": {"PasswordLength": 32}
                      }
                    }""".formatted(suffix)));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_ROLLBACK_FAILED</StackStatus>"))
            .body(containsString("The following resource(s) failed to roll back: [MyPipe]."));

        // The physical id is what a later cleanup and a later DeleteStack address, so it names the
        // pipe that survived the rollback rather than the replacement left behind.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<PhysicalResourceId>" + priorName + "</PhysicalResourceId>"))
            .body(not(containsString(replacementName)));

        assertPipe(priorName);
        assertPipe(replacementName);

        Mockito.doCallRealMethod().when(pipesService).deletePipe(eq(replacementName), anyString());
        updateStack(stackName, renameRollbackTemplate(replacementName, ""));

        given()
            .contentType("application/json")
        .when()
            .get("/v1/pipes/" + priorName)
        .then()
            .statusCode(200)
            .body("Name", equalTo(priorName))
            .body("Target", equalTo("arn:aws:sqs:us-east-1:000000000000:pipe-rename-rollback-target"));
        verify(pipesService, never()).deletePipe(eq(priorName), anyString());

        deleteStack(stackName);
        // The rollback left the replacement orphaned and the resource UPDATE_FAILED, so neither pipe
        // is one DeleteStack removes and this test removes them itself.
        given().when().delete("/v1/pipes/" + priorName);
        given().when().delete("/v1/pipes/" + replacementName);
    }

    /**
     * The tag reconciliation runs after updatePipe has already written the pipe, so a failure there
     * leaves the pipe carrying the update the stack is about to disown. The provisioner puts it back
     * from its own snapshot before the failure leaves it, description and tags alike, and the stack
     * reports a clean rollback.
     */
    @Test
    void aFailedTagReconcileRestoresThePipeAndTheStackRollsBackCleanly() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "pipe-tag-restore-" + suffix;
        String pipeName = "pipe-tag-restore-pipe-" + suffix;

        createStack(stackName, tagReconcileTemplate(pipeName, "before", "owner", "before"));

        // The update's tag call fails; the restore's own tag call is left working, which is what
        // makes this the clean-rollback case rather than the failed-restore one below.
        Mockito.doThrow(new IllegalStateException("simulated tagResource failure"))
                .doCallRealMethod()
                .when(pipesService)
                .tagResource(anyString(), anyString(), anyMap());

        updateStack(stackName, tagReconcileTemplate(pipeName, "after", "team", "after"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_ROLLBACK_COMPLETE</StackStatus>"));

        given()
            .contentType("application/json")
        .when()
            .get("/v1/pipes/" + pipeName)
        .then()
            .statusCode(200)
            .body("Description", equalTo("before"))
            .body("Tags.owner", equalTo("before"))
            .body("Tags.team", nullValue());

        Mockito.doCallRealMethod().when(pipesService).tagResource(anyString(), anyString(), anyMap());
        deleteStack(stackName);
        assertPipeMissing(pipeName);
    }

    /**
     * The restore repeats the tag calls the update just made, so a tag failure that persists takes
     * the restore down with it and nobody knows what the pipe carries. The stack says so:
     * UPDATE_ROLLBACK_FAILED naming the pipe resource, rather than a clean rollback claiming a
     * configuration the pipe does not hold.
     */
    @Test
    void aFailedRestoreLeavesTheStackInUpdateRollbackFailedNamingThePipe() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "pipe-restore-failure-" + suffix;
        String pipeName = "pipe-restore-failure-pipe-" + suffix;

        createStack(stackName, tagReconcileTemplate(pipeName, "before", "owner", "before"));

        Mockito.doThrow(new IllegalStateException("simulated persistent tagResource failure"))
                .when(pipesService)
                .tagResource(anyString(), anyString(), anyMap());

        updateStack(stackName, tagReconcileTemplate(pipeName, "after", "team", "after"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>UPDATE_ROLLBACK_FAILED</StackStatus>"))
            .body(containsString(
                    "The following resource(s) failed to roll back: [MyPipe]."));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("simulated persistent tagResource failure"))
            .body(not(containsString(
                    "<ResourceStatusReason>Resource update rolled back</ResourceStatusReason>")));

        Mockito.doCallRealMethod().when(pipesService).tagResource(anyString(), anyString(), anyMap());
        deleteStack(stackName);
        // A resource left UPDATE_FAILED by a failed rollback is not one DeleteStack removes, so the
        // pipe outlives the stack and this test deletes it itself.
        pipesService.deletePipe(pipeName, "us-east-1");
    }

    /** The pipe alone, under a fixed name, so every update reconciles it in place. */
    private static String tagReconcileTemplate(String pipeName, String description,
                                               String tagKey, String tagValue) {
        return """
                {
                  "Resources": {
                    "MyPipe": {
                      "Type": "AWS::Pipes::Pipe",
                      "Properties": {
                        "Name": "%1$s",
                        "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-tag-restore-source",
                        "Target": "arn:aws:sqs:us-east-1:000000000000:pipe-tag-restore-target",
                        "RoleArn": "arn:aws:iam::000000000000:role/pipe-tag-restore-role",
                        "Description": "%2$s",
                        "DesiredState": "STOPPED",
                        "Tags": [{"Key": "%3$s", "Value": "%4$s"}]
                      }
                    }
                  }
                }
                """.formatted(pipeName, description, tagKey, tagValue);
    }

    /**
     * The pipe alone, addressing its queues by ARN. A queue of this stack's own would report
     * UPDATE_FAILED for want of its own rollback and hide the pipe's outcome behind
     * UPDATE_ROLLBACK_FAILED.
     */
    private static String renameRollbackTemplate(String pipeName, String failingResource) {
        return """
                {
                  "Resources": {
                    "MyPipe": {
                      "Type": "AWS::Pipes::Pipe",
                      "Properties": {
                        "Name": "%1$s",
                        "Source": "arn:aws:sqs:us-east-1:000000000000:pipe-rename-rollback-source",
                        "Target": "arn:aws:sqs:us-east-1:000000000000:pipe-rename-rollback-target",
                        "RoleArn": "arn:aws:iam::000000000000:role/pipe-rename-rollback-role",
                        "DesiredState": "STOPPED"
                      }
                    }%2$s
                  }
                }
                """.formatted(pipeName, failingResource);
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

    private static void assertPipe(String pipeName) {
        given()
            .contentType("application/json")
        .when()
            .get("/v1/pipes/" + pipeName)
        .then()
            .statusCode(200)
            .body("Name", equalTo(pipeName));
    }

    private static void assertPipeMissing(String pipeName) {
        given()
            .contentType("application/json")
        .when()
            .get("/v1/pipes/" + pipeName)
        .then()
            .statusCode(404);
    }

    private static String template(String pipeName, String suffix) {
        return """
                {
                  "Resources": {
                    "SourceQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "pipe-cleanup-source-%s"}
                    },
                    "TargetQueue": {
                      "Type": "AWS::SQS::Queue",
                      "Properties": {"QueueName": "pipe-cleanup-target-%s"}
                    },
                    "MyPipe": {
                      "Type": "AWS::Pipes::Pipe",
                      "Properties": {
                        "Name": "%s",
                        "Source": {"Fn::GetAtt": ["SourceQueue", "Arn"]},
                        "Target": {"Fn::GetAtt": ["TargetQueue", "Arn"]},
                        "RoleArn": "arn:aws:iam::000000000000:role/pipe-cleanup-role",
                        "DesiredState": "STOPPED"
                      }
                    }
                  }
                }
                """.formatted(suffix, suffix, pipeName);
    }
}
