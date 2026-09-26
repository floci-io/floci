package io.github.hectorvent.floci.services.codepipeline;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class CodePipelineIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET = "CodePipeline_20150709.";

    @Inject
    EmulatorConfig config;

    /**
     * Sleeps for {@code cycles} times the configured source poll interval. The assertions that
     * follow these waits check that no execution, or no additional execution, was started, and they
     * are only meaningful if the poller actually ran and declined, so the wait scales with the
     * interval instead of hard-coding milliseconds that stop covering enough cycles when the
     * interval changes.
     *
     * <p>This is wall clock, not a handshake with the scheduler. The poller runs on a fixed delay,
     * so its period is the interval plus however long a pass takes, and fewer than {@code cycles}
     * passes may complete within the sleep. Several cycles are requested to leave margin for that,
     * not to guarantee a number of completed passes.
     */
    private void awaitSourcePollCycles(int cycles) throws InterruptedException {
        Thread.sleep(config.services().codepipeline().sourcePollIntervalMs() * cycles);
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getPipelineReturnsCurrentStructureAndMetadata() {
        String pipelineName = "get-pipeline-it";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceAction",
                        "actionTypeId": {
                            "category": "Source",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "runOrder": 1
                    }]
                },
                {
                    "name": "Build",
                    "actions": [{
                        "name": "BuildAction",
                        "actionTypeId": {
                            "category": "Build",
                            "owner": "AWS",
                            "provider": "CodeBuild",
                            "version": "1"
                        },
                        "runOrder": 1
                    }]
                }
                """)).then().statusCode(200);

        post("GetPipeline", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("pipeline.name", equalTo(pipelineName))
                .body("pipeline.version", equalTo(1))
                .body("pipeline.pipelineType", equalTo("V1"))
                .body("pipeline.executionMode", equalTo("SUPERSEDED"))
                .body("metadata.pipelineArn", equalTo("arn:aws:codepipeline:us-east-1:000000000000:" + pipelineName))
                .body("metadata.created", notNullValue())
                .body("metadata.updated", notNullValue());

        post("GetPipeline", """
                {"name": "%s", "version": 2}
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("PipelineVersionNotFoundException"));

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void updatePipelineReplacesStructureAndIncrementsVersion() {
        String pipelineName = "update-pipeline-it";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceAction",
                        "actionTypeId": {
                            "category": "Source",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        }
                    }]
                },
                {
                    "name": "Build",
                    "actions": [{
                        "name": "BuildAction",
                        "actionTypeId": {
                            "category": "Build",
                            "owner": "AWS",
                            "provider": "CodeBuild",
                            "version": "1"
                        }
                    }]
                }
                """)).then().statusCode(200);

        post("UpdatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceActionV2",
                        "actionTypeId": {
                            "category": "Source",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        }
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "DeployAction",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "CodeDeploy",
                            "version": "1"
                        }
                    }]
                }
                """))
                .then()
                .statusCode(200)
                .body("pipeline.name", equalTo(pipelineName))
                .body("pipeline.version", equalTo(2))
                .body("pipeline.stages[1].name", equalTo("Deploy"));

        post("GetPipeline", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("pipeline.version", equalTo(2))
                .body("pipeline.stages[0].actions[0].name", equalTo("SourceActionV2"))
                .body("pipeline.stages[1].name", equalTo("Deploy"));

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void pipelineLifecycleExecutesS3SourceAndDeployActions() throws Exception {
        createBucket("codepipeline-source");
        createBucket("codepipeline-destination");
        String sourceETag = putObject(
                "codepipeline-source", "source.zip", "pipeline artifact", "release candidate");

        String pipelineName = "s3-copy-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceObject",
                        "actionTypeId": {
                            "category": "Source",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "S3Bucket": "codepipeline-source",
                            "S3ObjectKey": "source.zip"
                        },
                        "outputArtifacts": [{"name": "SourceOutput"}],
                        "runOrder": 1
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "DeployObject",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "codepipeline-destination",
                            "ObjectKey": "deployed.zip"
                        },
                        "inputArtifacts": [{"name": "SourceOutput"}],
                        "runOrder": 1
                    }]
                }
                """))
                .then()
                .statusCode(200)
                .body("pipeline.name", equalTo(pipelineName))
                .body("pipeline.version", equalTo(1))
                .body("pipeline.executionMode", equalTo("SUPERSEDED"));

        String executionId = post("StartPipelineExecution", """
                {"name": "%s", "clientRequestToken": "s3-copy-token"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("pipelineExecutionId", notNullValue())
                .extract().path("pipelineExecutionId");

        waitForExecution(pipelineName, executionId, "Succeeded");

        given()
                .get("/codepipeline-destination/deployed.zip")
        .then()
                .statusCode(200)
                .body(equalTo("pipeline artifact"));

        post("GetPipelineExecution", """
                {"pipelineName": "%s", "pipelineExecutionId": "%s"}
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200)
                .body("pipelineExecution.pipelineName", equalTo(pipelineName))
                .body("pipelineExecution.status", equalTo("Succeeded"))
                .body("pipelineExecution.artifactRevisions", hasSize(1))
                .body("pipelineExecution.artifactRevisions[0].name", equalTo("SourceObject"))
                .body("pipelineExecution.sourceRevisions", nullValue())
                .body("pipelineExecution.sourceRevisionOverrides", nullValue());

        post("ListPipelineExecutions", """
                {"pipelineName": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("pipelineExecutionSummaries", hasSize(1))
                .body("pipelineExecutionSummaries[0].pipelineExecutionId", equalTo(executionId))
                .body("pipelineExecutionSummaries[0].status", equalTo("Succeeded"))
                .body("pipelineExecutionSummaries[0].sourceRevisions", hasSize(1))
                .body("pipelineExecutionSummaries[0].sourceRevisions[0].actionName", equalTo("SourceObject"))
                .body("pipelineExecutionSummaries[0].sourceRevisions[0].revisionId",
                        equalTo(unquote(sourceETag)))
                .body("pipelineExecutionSummaries[0].sourceRevisions[0].revisionSummary",
                        equalTo("release candidate"))
                .body("pipelineExecutionSummaries[0].sourceRevisions[0].revisionUrl",
                        equalTo("s3://codepipeline-source/source.zip"))
                .body("pipelineExecutionSummaries[0].sourceRevisionOverrides", nullValue());

        post("ListPipelineExecutions", """
                {
                    "pipelineName": "%s",
                    "filter": {"succeededInStage": {"stageName": "Deploy"}}
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("pipelineExecutionSummaries", hasSize(1));

        post("GetPipelineState", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("stageStates", hasSize(2))
                .body("stageStates[0].actionStates[0].latestExecution.status", equalTo("Succeeded"))
                .body("stageStates[0].latestExecution.pipelineExecutionId", equalTo(executionId))
                .body("stageStates[0].latestExecution.status", equalTo("Succeeded"))
                .body("stageStates[1].latestExecution.pipelineExecutionId", equalTo(executionId))
                .body("stageStates[1].latestExecution.status", equalTo("Succeeded"));

        post("ListActionExecutions", """
                {
                    "pipelineName": "%s",
                    "filter": {"pipelineExecutionId": "%s"}
                }
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200)
                .body("actionExecutionDetails", hasSize(2));

        String rollbackExecutionId = post("RollbackStage", """
                {
                    "pipelineName": "%s",
                    "stageName": "Deploy",
                    "targetPipelineExecutionId": "%s"
                }
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200)
                .body("pipelineExecutionId", notNullValue())
                .extract().path("pipelineExecutionId");

        waitForExecution(pipelineName, rollbackExecutionId, "Succeeded");

        post("GetPipelineExecution", """
                {"pipelineName": "%s", "pipelineExecutionId": "%s"}
                """.formatted(pipelineName, rollbackExecutionId))
                .then()
                .statusCode(200)
                .body("pipelineExecution.executionType", equalTo("ROLLBACK"))
                .body("pipelineExecution.rollbackMetadata.rollbackTargetPipelineExecutionId", equalTo(executionId))
                .body("pipelineExecution.rollbackTargetPipelineExecutionId", nullValue());

        post("TagResource", """
                {
                    "resourceArn": "arn:aws:codepipeline:us-east-1:000000000000:%s",
                    "tags": [{"key": "environment", "value": "test"}]
                }
                """.formatted(pipelineName)).then().statusCode(200);

        post("ListTagsForResource", """
                {"resourceArn": "arn:aws:codepipeline:us-east-1:000000000000:%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("tags[0].key", equalTo("environment"));

        post("ListPipelines", "{}")
                .then()
                .statusCode(200)
                .body("pipelines.name", hasItem(pipelineName));

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void s3SourcePollingStartsExactlyOneExecutionForARevisionChange() throws Exception {
        createBucket("codepipeline-poll-source");
        createBucket("codepipeline-poll-destination");
        putObject("codepipeline-poll-source", "source.zip", "baseline artifact");

        String pipelineName = "s3-source-polling-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceObject",
                        "actionTypeId": {
                            "category": "Source",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "S3Bucket": "codepipeline-poll-source",
                            "S3ObjectKey": "source.zip"
                        },
                        "outputArtifacts": [{"name": "SourceOutput"}]
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "DeployObject",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "codepipeline-poll-destination",
                            "ObjectKey": "deployed.zip"
                        },
                        "inputArtifacts": [{"name": "SourceOutput"}]
                    }]
                }
                """))
                .then().statusCode(200);

        awaitSourcePollCycles(5);
        post("ListPipelineExecutions", """
                {"pipelineName": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("pipelineExecutionSummaries", hasSize(0));

        putObject("codepipeline-poll-source", "source.zip", "changed artifact");

        Response executions = waitForPipelineExecutionCount(pipelineName, 1);
        String executionId = executions.jsonPath().getString("pipelineExecutionSummaries[0].pipelineExecutionId");
        waitForExecution(pipelineName, executionId, "Succeeded");

        post("GetPipelineExecution", """
                {"pipelineName": "%s", "pipelineExecutionId": "%s"}
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200)
                .body("pipelineExecution.trigger.triggerType", equalTo("PollForSourceChanges"))
                .body("pipelineExecution.trigger.triggerDetail",
                        equalTo("s3://codepipeline-poll-source/source.zip"));

        given()
                .get("/codepipeline-poll-destination/deployed.zip")
        .then()
                .statusCode(200)
                .body(equalTo("changed artifact"));

        awaitSourcePollCycles(5);
        post("ListPipelineExecutions", """
                {"pipelineName": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("pipelineExecutionSummaries", hasSize(1));

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void s3SourcePollingStartsWhenPreviouslyMissingObjectAppears() throws Exception {
        createBucket("codepipeline-poll-missing-source");
        createBucket("codepipeline-poll-missing-destination");

        String pipelineName = "s3-source-polling-missing-object";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceObject",
                        "actionTypeId": {
                            "category": "Source",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "S3Bucket": "codepipeline-poll-missing-source",
                            "S3ObjectKey": "source.zip"
                        },
                        "outputArtifacts": [{"name": "SourceOutput"}]
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "DeployObject",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "codepipeline-poll-missing-destination",
                            "ObjectKey": "deployed.zip"
                        },
                        "inputArtifacts": [{"name": "SourceOutput"}]
                    }]
                }
                """))
                .then().statusCode(200);

        awaitSourcePollCycles(3);
        post("ListPipelineExecutions", """
                {"pipelineName": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("pipelineExecutionSummaries", hasSize(0));

        putObject("codepipeline-poll-missing-source", "source.zip", "first available artifact");

        Response executions = waitForPipelineExecutionCount(pipelineName, 1);
        String executionId = executions.jsonPath().getString("pipelineExecutionSummaries[0].pipelineExecutionId");
        waitForExecution(pipelineName, executionId, "Succeeded");
        post("GetPipelineExecution", """
                {"pipelineName": "%s", "pipelineExecutionId": "%s"}
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200)
                .body("pipelineExecution.trigger.triggerType", equalTo("PollForSourceChanges"));

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void s3SourcePollingDisabledDoesNotStartExecution() throws Exception {
        createBucket("codepipeline-poll-disabled-source");
        putObject("codepipeline-poll-disabled-source", "source.zip", "baseline artifact");

        String pipelineName = "s3-source-polling-disabled";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceObject",
                        "actionTypeId": {
                            "category": "Source",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "S3Bucket": "codepipeline-poll-disabled-source",
                            "S3ObjectKey": "source.zip",
                            "PollForSourceChanges": "false"
                        },
                        "outputArtifacts": [{"name": "SourceOutput"}]
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "PlaceholderAction",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "unused-codepipeline-destination",
                            "ObjectKey": "deployed.zip"
                        },
                        "inputArtifacts": [{"name": "SourceOutput"}]
                    }]
                }
                """))
                .then().statusCode(200);

        putObject("codepipeline-poll-disabled-source", "source.zip", "changed artifact");
        awaitSourcePollCycles(5);

        post("ListPipelineExecutions", """
                {"pipelineName": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("pipelineExecutionSummaries", hasSize(0));

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void retryStageExecutionReusesExecutionAndRetainedArtifacts() throws Exception {
        createBucket("retry-stage-source");
        createBucket("retry-stage-success");
        putObject("retry-stage-source", "source.zip", "retry artifact");

        String pipelineName = "retry-stage-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceObject",
                        "actionTypeId": {
                            "category": "Source",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "S3Bucket": "retry-stage-source",
                            "S3ObjectKey": "source.zip"
                        },
                        "outputArtifacts": [{"name": "SourceOutput"}],
                        "runOrder": 1
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "SuccessfulDeploy",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "retry-stage-success",
                            "ObjectKey": "successful.zip"
                        },
                        "inputArtifacts": [{"name": "SourceOutput"}],
                        "runOrder": 1
                    }, {
                        "name": "FailedDeploy",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "retry-stage-destination",
                            "ObjectKey": "deployed.zip"
                        },
                        "inputArtifacts": [{"name": "SourceOutput"}],
                        "runOrder": 1
                    }]
                }
                """))
                .then()
                .statusCode(200);

        String executionId = post("StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .extract().path("pipelineExecutionId");

        waitForExecution(pipelineName, executionId, "Failed");

        post("ListActionExecutions", """
                {
                    "pipelineName": "%s",
                    "filter": {"pipelineExecutionId": "%s"}
                }
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200)
                .body("actionExecutionDetails", hasSize(3))
                .body("actionExecutionDetails.findAll { it.actionName == 'SourceObject' }", hasSize(1))
                .body("actionExecutionDetails.find { it.actionName == 'SuccessfulDeploy' }.status",
                        equalTo("Succeeded"))
                .body("actionExecutionDetails.find { it.actionName == 'FailedDeploy' }.status", equalTo("Failed"));

        createBucket("retry-stage-destination");

        retryStage(pipelineName, "Deploy", executionId, "FAILED_ACTIONS")
                .then()
                .statusCode(200)
                .body("pipelineExecutionId", equalTo(executionId));

        waitForExecution(pipelineName, executionId, "Succeeded");

        post("GetPipelineExecution", """
                {"pipelineName": "%s", "pipelineExecutionId": "%s"}
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200)
                .body("pipelineExecution.pipelineExecutionId", equalTo(executionId))
                .body("pipelineExecution.artifactsReleased", nullValue())
                .body("pipelineExecution.stageExecutionStatuses", nullValue());

        given()
                .get("/retry-stage-destination/deployed.zip")
        .then()
                .statusCode(200)
                .body(equalTo("retry artifact"));

        post("ListPipelineExecutions", """
                {"pipelineName": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("pipelineExecutionSummaries", hasSize(1))
                .body("pipelineExecutionSummaries[0].pipelineExecutionId", equalTo(executionId))
                .body("pipelineExecutionSummaries[0].status", equalTo("Succeeded"));

        post("ListActionExecutions", """
                {
                    "pipelineName": "%s",
                    "filter": {"pipelineExecutionId": "%s"}
                }
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200)
                .body("actionExecutionDetails", hasSize(4))
                .body("actionExecutionDetails.findAll { it.actionName == 'SourceObject' }", hasSize(1))
                .body("actionExecutionDetails.findAll { it.actionName == 'SuccessfulDeploy' }", hasSize(1))
                .body("actionExecutionDetails.findAll { it.actionName == 'FailedDeploy' }", hasSize(2))
                .body("actionExecutionDetails.find { it.actionName == 'FailedDeploy' }.status", equalTo("Succeeded"));

        post("GetPipelineState", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("stageStates.find { it.stageName == 'Deploy' }.actionStates"
                        + ".find { it.actionName == 'SuccessfulDeploy' }.latestExecution.status", equalTo("Succeeded"))
                .body("stageStates.find { it.stageName == 'Deploy' }.actionStates"
                        + ".find { it.actionName == 'FailedDeploy' }.latestExecution.status", equalTo("Succeeded"));

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void retryStageExecutionAllActionsRerunsSuccessfulActions() throws Exception {
        createBucket("retry-all-source");
        createBucket("retry-all-success");
        putObject("retry-all-source", "source.zip", "retry all artifact");

        String pipelineName = "retry-all-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceObject",
                        "actionTypeId": {
                            "category": "Source", "owner": "AWS", "provider": "S3", "version": "1"
                        },
                        "configuration": {
                            "S3Bucket": "retry-all-source", "S3ObjectKey": "source.zip"
                        },
                        "outputArtifacts": [{"name": "SourceOutput"}]
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "SuccessfulDeploy",
                        "actionTypeId": {
                            "category": "Deploy", "owner": "AWS", "provider": "S3", "version": "1"
                        },
                        "configuration": {
                            "BucketName": "retry-all-success", "ObjectKey": "successful.zip"
                        },
                        "inputArtifacts": [{"name": "SourceOutput"}],
                        "runOrder": 1
                    }, {
                        "name": "FailedDeploy",
                        "actionTypeId": {
                            "category": "Deploy", "owner": "AWS", "provider": "S3", "version": "1"
                        },
                        "configuration": {
                            "BucketName": "retry-all-destination", "ObjectKey": "deployed.zip"
                        },
                        "inputArtifacts": [{"name": "SourceOutput"}],
                        "runOrder": 1
                    }]
                }
                """))
                .then().statusCode(200);

        String executionId = post("StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then().statusCode(200).extract().path("pipelineExecutionId");
        waitForExecution(pipelineName, executionId, "Failed");
        createBucket("retry-all-destination");

        retryStage(pipelineName, "Deploy", executionId, "ALL_ACTIONS")
                .then().statusCode(200).body("pipelineExecutionId", equalTo(executionId));
        waitForExecution(pipelineName, executionId, "Succeeded");

        post("ListActionExecutions", """
                {
                    "pipelineName": "%s",
                    "filter": {"pipelineExecutionId": "%s"}
                }
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200)
                .body("actionExecutionDetails.findAll { it.actionName == 'SourceObject' }", hasSize(1))
                .body("actionExecutionDetails.findAll { it.actionName == 'SuccessfulDeploy' }", hasSize(2))
                .body("actionExecutionDetails.findAll { it.actionName == 'FailedDeploy' }", hasSize(2));

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void retryStageExecutionValidatesRetryability() throws Exception {
        String pipelineName = "retry-validation-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceObject",
                        "actionTypeId": {
                            "category": "Source",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "S3Bucket": "missing-retry-source",
                            "S3ObjectKey": "source.zip"
                        },
                        "outputArtifacts": [{"name": "SourceOutput"}]
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "DeployObject",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "unused-retry-destination",
                            "ObjectKey": "deployed.zip"
                        },
                        "inputArtifacts": [{"name": "SourceOutput"}]
                    }]
                }
                """))
                .then()
                .statusCode(200);

        String executionId = post("StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .extract().path("pipelineExecutionId");

        waitForExecution(pipelineName, executionId, "Failed");

        post("RetryStageExecution", """
                {
                    "pipelineName": "%s",
                    "stageName": "Source",
                    "pipelineExecutionId": "%s",
                    "retryMode": "INVALID"
                }
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"));

        post("RetryStageExecution", """
                {
                    "pipelineName": "%s",
                    "stageName": "Missing",
                    "pipelineExecutionId": "%s",
                    "retryMode": "FAILED_ACTIONS"
                }
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(400)
                .body("__type", containsString("StageNotFoundException"));

        post("UpdatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceObjectV2",
                        "actionTypeId": {
                            "category": "Source", "owner": "AWS", "provider": "S3", "version": "1"
                        },
                        "configuration": {
                            "S3Bucket": "missing-retry-source", "S3ObjectKey": "source.zip"
                        },
                        "outputArtifacts": [{"name": "SourceOutput"}]
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "DeployObject",
                        "actionTypeId": {
                            "category": "Deploy", "owner": "AWS", "provider": "S3", "version": "1"
                        },
                        "configuration": {
                            "BucketName": "unused-retry-destination", "ObjectKey": "deployed.zip"
                        },
                        "inputArtifacts": [{"name": "SourceOutput"}]
                    }]
                }
                """))
                .then().statusCode(200);

        post("RetryStageExecution", """
                {
                    "pipelineName": "%s",
                    "stageName": "Source",
                    "pipelineExecutionId": "%s",
                    "retryMode": "FAILED_ACTIONS"
                }
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(400)
                .body("__type", containsString("StageNotRetryableException"));

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void retryStageExecutionAdmitsOneOfConcurrentRetries() throws Exception {
        String pipelineName = "retry-concurrent-pipeline";
        post("CreatePipeline", approvalPipeline(pipelineName, "PARALLEL")).then().statusCode(200);
        String executionId = startExecution(pipelineName);
        waitForApprovalToken(pipelineName);
        stopExecutions(pipelineName, List.of(executionId));
        waitForExecution(pipelineName, executionId, "Stopped");

        int callersPerBurst = 8;
        ExecutorService callers = Executors.newFixedThreadPool(callersPerBurst);
        try {
            await("one retry to be admitted once the stopped run has finished").atMost(Duration.ofSeconds(5))
                    .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(50))
                    .until(() -> {
                        List<Response> completed = retryConcurrently(callers, callersPerBurst,
                                pipelineName, "Approve", executionId, "ALL_ACTIONS");
                        long admitted = completed.stream().filter(r -> r.statusCode() == 200).count();
                        assertTrue(admitted <= 1, "more than one concurrent retry was admitted");
                        assertTrue(completed.stream().filter(r -> r.statusCode() != 200)
                                .allMatch(r -> r.statusCode() == 400
                                        && r.jsonPath().getString("__type").contains("ConflictException")));
                        return admitted == 1;
                    });
        } finally {
            callers.shutdownNow();
            post("StopPipelineExecution", """
                    {"pipelineName": "%s", "pipelineExecutionId": "%s", "abandon": true}
                    """.formatted(pipelineName, executionId));
        }
    }

    @Test
    void retryStageExecutionRejectsStageCompletedBeforeInboundStop() throws Exception {
        String pipelineName = "retry-inbound-stop-pipeline";
        post("CreatePipeline", approvalPipeline(pipelineName, "PARALLEL")).then().statusCode(200);
        post("DisableStageTransition", """
                {
                    "pipelineName": "%s",
                    "stageName": "Complete",
                    "transitionType": "Inbound",
                    "reason": "hold"
                }
                """.formatted(pipelineName)).then().statusCode(200);
        String executionId = startExecution(pipelineName);
        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Approve",
                    "actionName": "ManualApproval",
                    "token": "%s",
                    "result": {"status": "Approved", "summary": "ok"}
                }
                """.formatted(pipelineName, waitForApprovalToken(pipelineName))).then().statusCode(200);
        waitForStageStatus(pipelineName, "Approve", "Succeeded");
        stopExecutions(pipelineName, List.of(executionId));
        waitForExecution(pipelineName, executionId, "Stopped");

        retryStage(pipelineName, "Approve", executionId, "ALL_ACTIONS")
                .then()
                .statusCode(400)
                .body("__type", containsString("StageNotRetryableException"));
    }

    @Test
    void retryStageExecutionCountsAgainstActiveExecutionLimit() throws Exception {
        String pipelineName = "retry-limit-pipeline";
        post("CreatePipeline", approvalPipeline(pipelineName, "PARALLEL")).then().statusCode(200);
        String stoppedId = startExecution(pipelineName);
        waitForApprovalToken(pipelineName);
        stopExecutions(pipelineName, List.of(stoppedId));
        waitForExecution(pipelineName, stoppedId, "Stopped");

        List<String> executionIds = new ArrayList<>();
        try {
            while (executionIds.size() < 50) {
                executionIds.add(startExecution(pipelineName));
            }
            waitForActiveExecutions(pipelineName, 50);

            retryStage(pipelineName, "Approve", stoppedId, "ALL_ACTIONS")
                    .then()
                    .statusCode(400)
                    .body("__type", containsString("ConcurrentPipelineExecutionsLimitExceededException"));
        } finally {
            stopExecutions(pipelineName, executionIds);
        }
    }

    @Test
    void retryStageExecutionReleasesArtifactsOfOutdatedExecution() throws Exception {
        createBucket("retry-outdated-source");
        putObject("retry-outdated-source", "source.zip", "outdated artifact");
        String pipelineName = "retry-outdated-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceObject",
                        "actionTypeId": {"category": "Source", "owner": "AWS", "provider": "S3", "version": "1"},
                        "configuration": {"S3Bucket": "retry-outdated-source", "S3ObjectKey": "source.zip"},
                        "outputArtifacts": [{"name": "SourceOutput"}]
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "DeployObject",
                        "actionTypeId": {"category": "Deploy", "owner": "AWS", "provider": "S3", "version": "1"},
                        "configuration": {"BucketName": "retry-outdated-destination", "ObjectKey": "deployed.zip"},
                        "inputArtifacts": [{"name": "SourceOutput"}]
                    }]
                }
                """)).then().statusCode(200);

        String olderId = startExecution(pipelineName);
        waitForExecution(pipelineName, olderId, "Failed");
        String newerId = startExecution(pipelineName);
        waitForExecution(pipelineName, newerId, "Failed");

        retryStage(pipelineName, "Deploy", olderId, "FAILED_ACTIONS")
                .then()
                .statusCode(400)
                .body("__type", containsString("NotLatestPipelineExecutionException"));

        createBucket("retry-outdated-destination");
        retryStage(pipelineName, "Deploy", newerId, "FAILED_ACTIONS").then().statusCode(200);
        waitForExecution(pipelineName, newerId, "Succeeded");

        retryStage(pipelineName, "Deploy", olderId, "FAILED_ACTIONS")
                .then()
                .statusCode(400)
                .body("__type", containsString("StageNotRetryableException"));

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void retryStageExecutionRejectsRetryWhileSiblingActionStillRuns() throws Exception {
        String pipelineName = "retry-sibling-running-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "ManualApproval",
                        "actionTypeId": {"category": "Approval", "owner": "AWS", "provider": "Manual", "version": "1"},
                        "runOrder": 1
                    }, {
                        "name": "UnsupportedDeploy",
                        "actionTypeId": {"category": "Deploy", "owner": "AWS", "provider": "ECS", "version": "1"},
                        "configuration": {"ClusterName": "none", "ServiceName": "none"},
                        "runOrder": 1
                    }]
                },
                {
                    "name": "Release",
                    "actions": [{
                        "name": "ReleaseApproval",
                        "actionTypeId": {"category": "Approval", "owner": "AWS", "provider": "Manual", "version": "1"}
                    }]
                }
                """)).then().statusCode(200);

        String executionId = startExecution(pipelineName);
        waitForExecution(pipelineName, executionId, "Failed");
        String approvalToken = waitForApprovalToken(pipelineName);

        post("RetryStageExecution", """
                {
                    "pipelineName": "%s",
                    "stageName": "Deploy",
                    "pipelineExecutionId": "%s",
                    "retryMode": "FAILED_ACTIONS"
                }
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(400)
                .body("__type", containsString("ConflictException"));

        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Deploy",
                    "actionName": "ManualApproval",
                    "token": "%s",
                    "result": {"status": "Approved", "summary": "done"}
                }
                """.formatted(pipelineName, approvalToken)).then().statusCode(200);
        post("ListActionExecutions", """
                {"pipelineName": "%s", "filter": {"pipelineExecutionId": "%s"}}
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200)
                .body("actionExecutionDetails.findAll { it.actionName == 'ManualApproval' }", hasSize(1));
    }

    @Test
    void retryStageExecutionRetriesCodeBuildStageFedByUnstoredArtifact() throws Exception {
        createBucket("retry-codebuild-source");
        putObject("retry-codebuild-source", "source.zip", "codebuild artifact");
        String pipelineName = "retry-codebuild-chain-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceObject",
                        "actionTypeId": {"category": "Source", "owner": "AWS", "provider": "S3", "version": "1"},
                        "configuration": {"S3Bucket": "retry-codebuild-source", "S3ObjectKey": "source.zip"},
                        "outputArtifacts": [{"name": "SourceOutput"}]
                    }]
                },
                {
                    "name": "Test",
                    "actions": [{
                        "name": "RunTests",
                        "actionTypeId": {"category": "Test", "owner": "AWS", "provider": "CodeBuild", "version": "1"},
                        "configuration": {"ProjectName": "retry-codebuild-missing-project"},
                        "inputArtifacts": [{"name": "BuildOutput"}]
                    }]
                }
                """)).then().statusCode(200);

        String executionId = startExecution(pipelineName);
        waitForExecution(pipelineName, executionId, "Failed");
        post("ListActionExecutions", """
                {"pipelineName": "%s", "filter": {"pipelineExecutionId": "%s"}}
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200)
                .body("actionExecutionDetails.find { it.actionName == 'RunTests' }"
                        + ".output.executionResult.errorDetails.message", containsString("Project not found"));

        retryStage(pipelineName, "Test", executionId, "FAILED_ACTIONS")
                .then()
                .statusCode(200)
                .body("pipelineExecutionId", equalTo(executionId));
        waitForExecution(pipelineName, executionId, "Failed");
        waitForActionExecutionCount(pipelineName, executionId, "RunTests", 2);

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void startPipelineExecutionS3OverridesSelectEffectiveKeyAndVersion() throws Exception {
        createBucket("codepipeline-source-override");
        createBucket("codepipeline-override-destination");
        given()
                .body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
        .when()
                .put("/codepipeline-source-override?versioning")
        .then()
                .statusCode(200);

        putObject("codepipeline-source-override", "source.zip", "default artifact");
        String oldVersion = given()
                .contentType("application/octet-stream")
                .body("selected old version".getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .when()
                .put("/codepipeline-source-override/alternate.zip")
        .then()
                .statusCode(200)
                .header("x-amz-version-id", notNullValue())
                .extract().header("x-amz-version-id");
        putObject("codepipeline-source-override", "alternate.zip", "newer version");

        String pipelineName = "source-revision-override-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceObject",
                        "actionTypeId": {
                            "category": "Source",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "S3Bucket": "codepipeline-source-override",
                            "S3ObjectKey": "source.zip",
                            "AllowOverrideForS3ObjectKey": "true"
                        },
                        "outputArtifacts": [{"name": "SourceOutput"}]
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "DeployObject",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "codepipeline-override-destination",
                            "ObjectKey": "deployed.zip"
                        },
                        "inputArtifacts": [{"name": "SourceOutput"}]
                    }]
                }
                """))
                .then().statusCode(200);

        String executionId = post("StartPipelineExecution", """
                {
                    "name": "%s",
                    "sourceRevisions": [
                        {
                            "actionName": "SourceObject",
                            "revisionType": "S3_OBJECT_KEY",
                            "revisionValue": "alternate.zip"
                        },
                        {
                            "actionName": "SourceObject",
                            "revisionType": "S3_OBJECT_VERSION_ID",
                            "revisionValue": "%s"
                        }
                    ]
                }
                """.formatted(pipelineName, oldVersion))
                .then()
                .statusCode(200)
                .extract().path("pipelineExecutionId");

        waitForExecution(pipelineName, executionId, "Succeeded");

        given()
                .get("/codepipeline-override-destination/deployed.zip")
        .then()
                .statusCode(200)
                .body(equalTo("selected old version"));

        post("ListPipelineExecutions", """
                {"pipelineName": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("pipelineExecutionSummaries[0].pipelineExecutionId", equalTo(executionId))
                .body("pipelineExecutionSummaries[0].sourceRevisions", hasSize(1))
                .body("pipelineExecutionSummaries[0].sourceRevisions[0].actionName", equalTo("SourceObject"))
                .body("pipelineExecutionSummaries[0].sourceRevisions[0].revisionId", equalTo(oldVersion))
                .body("pipelineExecutionSummaries[0].sourceRevisions[0].revisionUrl",
                        equalTo("s3://codepipeline-source-override/alternate.zip"));

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void rollbackStageRedeploysTargetExecutionSourceVersion() throws Exception {
        createBucket("codepipeline-rollback-source");
        createBucket("codepipeline-rollback-destination");
        given()
                .body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
        .when()
                .put("/codepipeline-rollback-source?versioning")
        .then()
                .statusCode(200);
        putObject("codepipeline-rollback-source", "source.zip", "first release");

        String pipelineName = "rollback-source-version-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceObject",
                        "actionTypeId": {
                            "category": "Source",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "S3Bucket": "codepipeline-rollback-source",
                            "S3ObjectKey": "source.zip",
                            "PollForSourceChanges": "false"
                        },
                        "outputArtifacts": [{"name": "SourceOutput"}]
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "DeployObject",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "codepipeline-rollback-destination",
                            "ObjectKey": "deployed.zip"
                        },
                        "inputArtifacts": [{"name": "SourceOutput"}]
                    }]
                }
                """))
                .then().statusCode(200);

        String firstExecutionId = post("StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .extract().path("pipelineExecutionId");
        waitForExecution(pipelineName, firstExecutionId, "Succeeded");

        putObject("codepipeline-rollback-source", "source.zip", "second release");
        String secondExecutionId = post("StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .extract().path("pipelineExecutionId");
        waitForExecution(pipelineName, secondExecutionId, "Succeeded");

        String rollbackExecutionId = post("RollbackStage", """
                {
                    "pipelineName": "%s",
                    "stageName": "Deploy",
                    "targetPipelineExecutionId": "%s"
                }
                """.formatted(pipelineName, firstExecutionId))
                .then()
                .statusCode(200)
                .extract().path("pipelineExecutionId");
        waitForExecution(pipelineName, rollbackExecutionId, "Succeeded");

        given()
                .get("/codepipeline-rollback-destination/deployed.zip")
        .then()
                .statusCode(200)
                .body(equalTo("first release"));

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void startPipelineExecutionRejectsS3KeyOverrideUnlessActionAllowsIt() {
        String pipelineName = "source-revision-key-override-validation";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceObject",
                        "actionTypeId": {
                            "category": "Source",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "S3Bucket": "codepipeline-source",
                            "S3ObjectKey": "source.zip"
                        },
                        "outputArtifacts": [{"name": "SourceOutput"}]
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "DeployObject",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "codepipeline-destination",
                            "ObjectKey": "deployed.zip"
                        },
                        "inputArtifacts": [{"name": "SourceOutput"}]
                    }]
                }
                """))
                .then().statusCode(200);

        post("StartPipelineExecution", """
                {
                    "name": "%s",
                    "sourceRevisions": [{
                        "actionName": "SourceObject",
                        "revisionType": "S3_OBJECT_KEY",
                        "revisionValue": "alternate.zip"
                    }]
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("AllowOverrideForS3ObjectKey"));

        post("StartPipelineExecution", """
                {
                    "name": "%s",
                    "sourceRevisions": [
                        {
                            "actionName": "SourceObject",
                            "revisionType": "S3_OBJECT_VERSION_ID",
                            "revisionValue": "v1"
                        },
                        {
                            "actionName": "SourceObject",
                            "revisionType": "S3_OBJECT_VERSION_ID",
                            "revisionValue": "v2"
                        }
                    ]
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("Duplicate S3_OBJECT_VERSION_ID"));

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void listPipelineExecutionsValidatesFilterAndMaxResults() {
        String pipelineName = "list-executions-validation-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceAction",
                        "actionTypeId": {
                            "category": "Source",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "runOrder": 1
                    }]
                },
                {
                    "name": "Build",
                    "actions": [{
                        "name": "BuildAction",
                        "actionTypeId": {
                            "category": "Build",
                            "owner": "AWS",
                            "provider": "CodeBuild",
                            "version": "1"
                        },
                        "runOrder": 1
                    }]
                }
                """))
                .then()
                .statusCode(200);

        post("ListPipelineExecutions", """
                {
                    "pipelineName": "%s",
                    "filter": {"succeededInStage": {"stageName": 42}}
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("succeededInStage requires stageName"));

        post("ListPipelineExecutions", """
                {
                    "pipelineName": "%s",
                    "filter": {"succeededInStage": {"stageName": "Source", "extra": 1}}
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("Unknown"));

        post("ListPipelineExecutions", """
                {
                    "pipelineName": "%s",
                    "filter": {"succeededInStage": {"stageName": "Source"}, "extra": 1}
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("Unknown"));

        post("ListPipelineExecutions", """
                {
                    "pipelineName": "%s",
                    "filter": {"succeededInStage": null}
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(200);

        post("ListPipelineExecutions", """
                {
                    "pipelineName": "%s",
                    "maxResults": "5"
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("maxResults must be"));

        post("ListPipelineExecutions", """
                {
                    "pipelineName": "%s",
                    "maxResults": 5.5
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("maxResults must be"));

        post("ListPipelineExecutions", """
                {
                    "pipelineName": "%s",
                    "maxResults": 0
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("between 1 and 100"));

        post("ListPipelineExecutions", """
                {
                    "pipelineName": "%s",
                    "maxResults": 101
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("between 1 and 100"));
    }

    @Test
    void putApprovalResultSurvivesStageRenameForInFlightApproval() throws Exception {
        String pipelineName = "approval-rename-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Approve",
                    "actions": [{
                        "name": "ManualApproval",
                        "actionTypeId": {
                            "category": "Approval",
                            "owner": "AWS",
                            "provider": "Manual",
                            "version": "1"
                        },
                        "configuration": {},
                        "runOrder": 1
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "PlaceholderAction",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "codepipeline-artifacts",
                            "ObjectKey": "placeholder"
                        },
                        "runOrder": 1
                    }]
                }
                """))
                .then()
                .statusCode(200);

        post("StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200);

        String approvalToken = waitForApprovalToken(pipelineName);

        post("UpdatePipeline", pipeline(pipelineName, """
                {
                    "name": "ApproveRenamed",
                    "actions": [{
                        "name": "ManualApprovalRenamed",
                        "actionTypeId": {
                            "category": "Approval",
                            "owner": "AWS",
                            "provider": "Manual",
                            "version": "1"
                        },
                        "configuration": {},
                        "runOrder": 1
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "PlaceholderAction",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "codepipeline-artifacts",
                            "ObjectKey": "placeholder"
                        },
                        "runOrder": 1
                    }]
                }
                """))
                .then()
                .statusCode(200);

        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Approve",
                    "actionName": "ManualApproval",
                    "token": "%s",
                    "result": {
                        "status": "Approved",
                        "summary": "Approved despite rename"
                    }
                }
                """.formatted(pipelineName, approvalToken))
                .then()
                .statusCode(200)
                .body("approvedAt", notNullValue());
    }

    @Test
    void customActionUsesAwsWorkerJobProtocol() throws Exception {
        createBucket("codepipeline-custom-source");
        putObject("codepipeline-custom-source", "source.zip", "custom artifact");

        post("CreateCustomActionType", """
                {
                    "category": "Build",
                    "provider": "FlociWorker",
                    "version": "1",
                    "inputArtifactDetails": {"minimumCount": 0, "maximumCount": 1},
                    "outputArtifactDetails": {"minimumCount": 0, "maximumCount": 1}
                }
                """)
                .then()
                .statusCode(200)
                .body("actionType.id.owner", equalTo("Custom"));

        String pipelineName = "custom-worker-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Source",
                    "actions": [{
                        "name": "SourceObject",
                        "actionTypeId": {
                            "category": "Source",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "S3Bucket": "codepipeline-custom-source",
                            "S3ObjectKey": "source.zip"
                        },
                        "outputArtifacts": [{"name": "SourceOutput"}]
                    }]
                },
                {
                    "name": "Build",
                    "actions": [{
                        "name": "WorkerBuild",
                        "actionTypeId": {
                            "category": "Build",
                            "owner": "Custom",
                            "provider": "FlociWorker",
                            "version": "1"
                        },
                        "inputArtifacts": [{"name": "SourceOutput"}]
                    }]
                }
                """)).then().statusCode(200);

        String executionId = post("StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().extract().path("pipelineExecutionId");

        Response poll = waitForJob();
        String jobId = poll.path("jobs[0].id");
        String nonce = poll.path("jobs[0].nonce");

        post("AcknowledgeJob", """
                {"jobId": "%s", "nonce": "%s"}
                """.formatted(jobId, nonce))
                .then()
                .statusCode(200)
                .body("status", equalTo("InProgress"));

        post("PutJobSuccessResult", """
                {
                    "jobId": "%s",
                    "executionDetails": {
                        "summary": "worker completed",
                        "externalExecutionId": "worker-1",
                        "percentComplete": 100
                    },
                    "outputVariables": {"result": "ok"}
                }
                """.formatted(jobId)).then().statusCode(200);

        waitForExecution(pipelineName, executionId, "Succeeded");

        post("DeleteCustomActionType", """
                {"category": "Build", "provider": "FlociWorker", "version": "1"}
                """).then().statusCode(200);
    }

    @Test
    void stoppingCustomActionWaitsForWorkerSuccess() throws Exception {
        String provider = "StopWaitSuccessWorker";
        String pipelineName = "custom-worker-stop-wait-success";
        createCustomWorkerPipeline(provider, pipelineName);
        String executionId = post("StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().extract().path("pipelineExecutionId");
        String jobId = waitForJob(provider).path("jobs[0].id");

        post("StopPipelineExecution", """
                {
                    "pipelineName": "%s",
                    "pipelineExecutionId": "%s",
                    "reason": "Wait for the custom worker"
                }
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200);

        assertCustomWorkerIsStopping(pipelineName);
        post("PutJobSuccessResult", """
                {
                    "jobId": "%s",
                    "executionDetails": {
                        "summary": "worker completed after stop request",
                        "externalExecutionId": "stop-wait-success",
                        "percentComplete": 100
                    }
                }
                """.formatted(jobId)).then().statusCode(200);

        waitForExecution(pipelineName, executionId, "Stopped");
        post("GetPipelineState", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("stageStates[0].latestExecution.status", equalTo("Stopped"))
                .body("stageStates[0].actionStates[0].latestExecution.status", equalTo("Succeeded"))
                .body("stageStates[1].latestExecution", nullValue());

        deleteCustomWorkerPipeline(provider, pipelineName);
    }

    @Test
    void stoppingCustomActionWaitsForWorkerFailure() throws Exception {
        String provider = "StopWaitFailureWorker";
        String pipelineName = "custom-worker-stop-wait-failure";
        createCustomWorkerPipeline(provider, pipelineName);
        String executionId = post("StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().extract().path("pipelineExecutionId");
        String jobId = waitForJob(provider).path("jobs[0].id");

        post("StopPipelineExecution", """
                {
                    "pipelineName": "%s",
                    "pipelineExecutionId": "%s",
                    "reason": "Wait for the failing custom worker"
                }
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200);

        assertCustomWorkerIsStopping(pipelineName);
        post("PutJobFailureResult", """
                {
                    "jobId": "%s",
                    "failureDetails": {
                        "type": "JobFailed",
                        "message": "worker failed after stop request",
                        "externalExecutionId": "stop-wait-failure"
                    }
                }
                """.formatted(jobId)).then().statusCode(200);

        waitForExecution(pipelineName, executionId, "Stopped");
        post("GetPipelineState", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("stageStates[0].latestExecution.status", equalTo("Failed"))
                .body("stageStates[0].actionStates[0].latestExecution.status", equalTo("Failed"))
                .body("stageStates[1].latestExecution", nullValue());

        deleteCustomWorkerPipeline(provider, pipelineName);
    }

    @Test
    void abandoningCustomActionStopsWaitingImmediately() throws Exception {
        String provider = "AbandonWorker";
        String pipelineName = "custom-worker-stop-abandon";
        createCustomWorkerPipeline(provider, pipelineName);
        String executionId = post("StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().extract().path("pipelineExecutionId");
        waitForJob(provider);

        post("StopPipelineExecution", """
                {
                    "pipelineName": "%s",
                    "pipelineExecutionId": "%s",
                    "abandon": true,
                    "reason": "Abandon the custom worker"
                }
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200);

        waitForExecution(pipelineName, executionId, "Stopped");
        post("GetPipelineState", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("stageStates[0].latestExecution.status", equalTo("Stopped"))
                .body("stageStates[0].actionStates[0].latestExecution.status", equalTo("Abandoned"))
                .body("stageStates[1].latestExecution", nullValue());

        deleteCustomWorkerPipeline(provider, pipelineName);
    }

    private void createCustomWorkerPipeline(String provider, String pipelineName) {
        post("CreateCustomActionType", """
                {
                    "category": "Build",
                    "provider": "%s",
                    "version": "1",
                    "inputArtifactDetails": {"minimumCount": 0, "maximumCount": 0},
                    "outputArtifactDetails": {"minimumCount": 0, "maximumCount": 0}
                }
                """.formatted(provider))
                .then()
                .statusCode(200);

        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Build",
                    "actions": [{
                        "name": "WorkerBuild",
                        "actionTypeId": {
                            "category": "Build",
                            "owner": "Custom",
                            "provider": "%s",
                            "version": "1"
                        }
                    }]
                },
                {
                    "name": "Complete",
                    "actions": [{
                        "name": "ManualApproval",
                        "actionTypeId": {
                            "category": "Approval",
                            "owner": "AWS",
                            "provider": "Manual",
                            "version": "1"
                        }
                    }]
                }
                """.formatted(provider))).then().statusCode(200);
    }

    private void assertCustomWorkerIsStopping(String pipelineName) {
        post("GetPipelineState", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("stageStates[0].latestExecution.status", equalTo("Stopping"))
                .body("stageStates[0].actionStates[0].latestExecution.status", equalTo("InProgress"))
                .body("stageStates[1].latestExecution", nullValue());
    }

    private void deleteCustomWorkerPipeline(String provider, String pipelineName) {
        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
        post("DeleteCustomActionType", """
                {"category": "Build", "provider": "%s", "version": "1"}
                """.formatted(provider)).then().statusCode(200);
    }

    @Test
    void putApprovalResultApprovesAndRejectsManualApprovalActions() throws Exception {
        String pipelineName = "approval-test-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Approve",
                    "actions": [{
                        "name": "ManualApproval",
                        "actionTypeId": {
                            "category": "Approval",
                            "owner": "AWS",
                            "provider": "Manual",
                            "version": "1"
                        },
                        "configuration": {},
                        "runOrder": 1
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "PlaceholderAction",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "codepipeline-artifacts",
                            "ObjectKey": "placeholder"
                        },
                        "runOrder": 1
                    }]
                }
                """))
                .then()
                .statusCode(200);

        String executionId = post("StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .extract().path("pipelineExecutionId");

        String approvalToken = waitForApprovalToken(pipelineName);

        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Approve",
                    "actionName": "ManualApproval",
                    "token": "%s",
                    "result": {
                        "status": "Approved",
                        "summary": "Approved by test"
                    }
                }
                """.formatted(pipelineName, approvalToken))
                .then()
                .statusCode(200)
                .body("approvedAt", notNullValue());

        waitForApprovalStatus(pipelineName, "Succeeded")
                .then()
                .statusCode(200)
                .body("stageStates[0].actionStates[0].latestExecution.status", equalTo("Succeeded"))
                .body("stageStates[0].actionStates[0].latestExecution.summary", equalTo("Approved by test"));

        String pipelineName2 = "rejection-test-pipeline";
        post("CreatePipeline", pipeline(pipelineName2, """
                {
                    "name": "Approve",
                    "actions": [{
                        "name": "ManualApproval",
                        "actionTypeId": {
                            "category": "Approval",
                            "owner": "AWS",
                            "provider": "Manual",
                            "version": "1"
                        },
                        "configuration": {},
                        "runOrder": 1
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "PlaceholderAction",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "codepipeline-artifacts",
                            "ObjectKey": "placeholder"
                        },
                        "runOrder": 1
                    }]
                }
                """))
                .then()
                .statusCode(200);

        String executionId2 = post("StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName2))
                .then()
                .statusCode(200)
                .extract().path("pipelineExecutionId");

        String approvalToken2 = waitForApprovalToken(pipelineName2);

        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Approve",
                    "actionName": "ManualApproval",
                    "token": "%s",
                    "result": {
                        "status": "Rejected",
                        "summary": "Rejected by test"
                    }
                }
                """.formatted(pipelineName2, approvalToken2))
                .then()
                .statusCode(200)
                .body("approvedAt", notNullValue());

        waitForApprovalStatus(pipelineName2, "Failed")
                .then()
                .statusCode(200)
                .body("stageStates[0].actionStates[0].latestExecution.status", equalTo("Failed"))
                .body("stageStates[0].actionStates[0].latestExecution.summary", equalTo("Rejected by test"))
                .body("stageStates[0].latestExecution.pipelineExecutionId", equalTo(executionId2))
                .body("stageStates[0].latestExecution.status", equalTo("Failed"));
    }

    @Test
    void stageStatusTracksMultipleRunOrdersAndStopBeforeLaterGroup() throws Exception {
        String pipelineName = "multi-run-order-stage-status";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Approve",
                    "actions": [
                        {
                            "name": "FirstApproval",
                            "actionTypeId": {
                                "category": "Approval",
                                "owner": "AWS",
                                "provider": "Manual",
                                "version": "1"
                            },
                            "configuration": {},
                            "runOrder": 1
                        },
                        {
                            "name": "SecondApproval",
                            "actionTypeId": {
                                "category": "Approval",
                                "owner": "AWS",
                                "provider": "Manual",
                                "version": "1"
                            },
                            "configuration": {},
                            "runOrder": 2
                        },
                        {
                            "name": "NeverStartedApproval",
                            "actionTypeId": {
                                "category": "Approval",
                                "owner": "AWS",
                                "provider": "Manual",
                                "version": "1"
                            },
                            "configuration": {},
                            "runOrder": 3
                        }
                    ]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "PlaceholderAction",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "codepipeline-artifacts",
                            "ObjectKey": "placeholder"
                        },
                        "runOrder": 1
                    }]
                }
                """))
                .then()
                .statusCode(200);

        String executionId = post("StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .extract().path("pipelineExecutionId");

        String firstToken = waitForApprovalToken(pipelineName, 0);
        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Approve",
                    "actionName": "FirstApproval",
                    "token": "%s",
                    "result": {"status": "Approved", "summary": "First group complete"}
                }
                """.formatted(pipelineName, firstToken))
                .then()
                .statusCode(200);

        waitForApprovalToken(pipelineName, 1);
        post("GetPipelineState", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("stageStates[0].latestExecution.pipelineExecutionId", equalTo(executionId))
                .body("stageStates[0].latestExecution.status", equalTo("InProgress"))
                .body("stageStates[0].actionStates[0].latestExecution.status", equalTo("Succeeded"))
                .body("stageStates[0].actionStates[1].latestExecution.status", equalTo("InProgress"))
                .body("stageStates[0].actionStates[2].latestExecution", nullValue());

        post("StopPipelineExecution", """
                {
                    "pipelineName": "%s",
                    "pipelineExecutionId": "%s",
                    "abandon": true,
                    "reason": "Verify stage status"
                }
                """.formatted(pipelineName, executionId))
                .then()
                .statusCode(200)
                .body("pipelineExecutionId", equalTo(executionId));

        waitForExecution(pipelineName, executionId, "Stopped");
        post("GetPipelineState", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("stageStates[0].latestExecution.pipelineExecutionId", equalTo(executionId))
                .body("stageStates[0].latestExecution.status", equalTo("Stopped"))
                .body("stageStates[0].actionStates[0].latestExecution.status", equalTo("Succeeded"))
                .body("stageStates[0].actionStates[1].latestExecution.status", equalTo("Abandoned"))
                .body("stageStates[0].actionStates[2].latestExecution", nullValue());

        post("DeletePipeline", """
                {"name": "%s"}
                """.formatted(pipelineName)).then().statusCode(200);
    }

    @Test
    void putApprovalResultValidatesErrors() throws Exception {
        String pipelineName = "error-test-pipeline";
        post("CreatePipeline", pipeline(pipelineName, """
                {
                    "name": "Approve",
                    "actions": [{
                        "name": "ManualApproval",
                        "actionTypeId": {
                            "category": "Approval",
                            "owner": "AWS",
                            "provider": "Manual",
                            "version": "1"
                        },
                        "configuration": {},
                        "runOrder": 1
                    }]
                },
                {
                    "name": "Deploy",
                    "actions": [{
                        "name": "PlaceholderAction",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "S3",
                            "version": "1"
                        },
                        "configuration": {
                            "BucketName": "codepipeline-artifacts",
                            "ObjectKey": "placeholder"
                        },
                        "runOrder": 1
                    }]
                }
                """))
                .then()
                .statusCode(200);

        post("PutApprovalResult", """
                {
                    "pipelineName": "nonexistent",
                    "stageName": "Approve",
                    "actionName": "ManualApproval",
                    "token": "00000000-0000-0000-0000-000000000000",
                    "result": {
                        "status": "Approved",
                        "summary": ""
                    }
                }
                """)
                .then()
                .statusCode(400)
                .body("__type", containsString("PipelineNotFoundException"));

        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "NonexistentStage",
                    "actionName": "ManualApproval",
                    "token": "00000000-0000-0000-0000-000000000000",
                    "result": {
                        "status": "Approved",
                        "summary": ""
                    }
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("StageNotFoundException"));

        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Approve",
                    "actionName": "NonexistentAction",
                    "token": "00000000-0000-0000-0000-000000000000",
                    "result": {
                        "status": "Approved",
                        "summary": ""
                    }
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("ActionNotFoundException"));

        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Approve",
                    "actionName": "ManualApproval",
                    "token": "00000000-0000-0000-0000-000000000000"
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("result is required"));

        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Approve",
                    "actionName": "ManualApproval",
                    "token": "00000000-0000-0000-0000-000000000000",
                    "result": "Approved"
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("result must be an object"));

        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Approve",
                    "actionName": "ManualApproval",
                    "token": "00000000-0000-0000-0000-000000000000",
                    "result": {
                        "status": "Approved",
                        "summary": 12345
                    }
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("result.summary must be a string"));

        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Approve",
                    "actionName": "ManualApproval",
                    "token": "00000000-0000-0000-0000-000000000000",
                    "result": {
                        "status": "Invalid",
                        "summary": ""
                    }
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("result.status must be one of"));

        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Approve",
                    "actionName": "ManualApproval",
                    "token": "00000000-0000-0000-0000-000000000000",
                    "result": {
                        "status": "Approved",
                        "summary": "%s"
                    }
                }
                """.formatted(pipelineName, "a".repeat(513)))
                .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("must not exceed 512 characters"));

        String executionId = post("StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .extract().path("pipelineExecutionId");

        String approvalToken = waitForApprovalToken(pipelineName);

        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Approve",
                    "actionName": "ManualApproval",
                    "token": "00000000-0000-0000-0000-000000000000",
                    "result": {
                        "status": "Approved",
                        "summary": ""
                    }
                }
                """.formatted(pipelineName))
                .then()
                .statusCode(400)
                .body("__type", containsString("InvalidApprovalTokenException"));

        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Approve",
                    "actionName": "ManualApproval",
                    "token": "%s",
                    "result": {
                        "status": "Approved",
                        "summary": "First approval"
                    }
                }
                """.formatted(pipelineName, approvalToken))
                .then()
                .statusCode(200);

        post("PutApprovalResult", """
                {
                    "pipelineName": "%s",
                    "stageName": "Approve",
                    "actionName": "ManualApproval",
                    "token": "%s",
                    "result": {
                        "status": "Approved",
                        "summary": "Second approval attempt"
                    }
                }
                """.formatted(pipelineName, approvalToken))
                .then()
                .statusCode(400)
                .body("__type", containsString("ApprovalAlreadyCompletedException"));
    }

    @Test
    void webhookStageTransitionAndValidationErrorsUseAwsShapes() {
        post("PutWebhook", """
                {
                    "webhook": {
                        "name": "source-hook",
                        "targetPipeline": "pipeline",
                        "targetAction": "source",
                        "filters": [{"jsonPath": "$.ref", "matchEquals": "refs/heads/main"}],
                        "authentication": "UNAUTHENTICATED"
                    }
                }
                """)
                .then()
                .statusCode(200)
                .body("webhook.definition.name", equalTo("source-hook"))
                .body("webhook.definition.targetPipeline", equalTo("pipeline"))
                .body("webhook.definition.targetAction", equalTo("source"))
                .body("webhook.registrationStatus", nullValue());

        post("RegisterWebhookWithThirdParty", """
                {"webhookName": "source-hook"}
                """).then().statusCode(200);

        post("ListWebhooks", "{}")
                .then()
                .statusCode(200)
                .body("webhooks.definition.name", hasItem("source-hook"))
                .body("webhooks[0].definition.filters[0].jsonPath", equalTo("$.ref"))
                .body("webhooks[0].registrationStatus", nullValue());

        post("DeleteWebhook", """
                {"name": "source-hook"}
                """)
                .then()
                .statusCode(200)
                .body(equalTo("{}"));

        post("CreatePipeline", """
                {"pipeline": {"name": "invalid", "roleArn": "role", "stages": []}}
                """)
                .then()
                .statusCode(400)
                .body("__type", containsString("InvalidStructureException"));
    }

    @Test
    void parallelPipelineRejectsExecutionAfterAwsActiveLimit() throws Exception {
        String pipelineName = "parallel-limit-pipeline";
        post("CreatePipeline", approvalPipeline(pipelineName, "PARALLEL")).then().statusCode(200);
        List<String> executionIds = new ArrayList<>();
        try {
            while (executionIds.size() < 50) {
                executionIds.add(startExecution(pipelineName));
            }
            waitForActiveExecutions(pipelineName, 50);

            post("StartPipelineExecution", "{\"name\": \"%s\"}".formatted(pipelineName))
                    .then()
                    .statusCode(400)
                    .body("__type", containsString("ConcurrentPipelineExecutionsLimitExceededException"));
        } finally {
            stopExecutions(pipelineName, executionIds);
        }
    }

    @Test
    void queuedPipelineAcceptsStartsWhileAnExecutionRunsAndRejectsTheFiftyFirst() throws Exception {
        String pipelineName = "queued-limit-pipeline";
        post("CreatePipeline", approvalPipeline(pipelineName, "QUEUED")).then().statusCode(200);
        List<String> executionIds = new ArrayList<>();
        try {
            executionIds.add(startExecution(pipelineName));
            waitForApprovalToken(pipelineName);

            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (executionIds.size() < 50) {
                    executionIds.add(startExecution(pipelineName));
                }
            }, "StartPipelineExecution waited for the running execution to finish");

            post("StartPipelineExecution", "{\"name\": \"%s\"}".formatted(pipelineName))
                    .then()
                    .statusCode(400)
                    .body("__type", containsString("ConcurrentPipelineExecutionsLimitExceededException"));
        } finally {
            stopExecutions(pipelineName, executionIds);
        }
    }

    private Response waitForJob() throws Exception {
        return waitForJob("FlociWorker");
    }

    private Response waitForJob(String provider) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        Response response;
        do {
            response = post("PollForJobs", """
                    {
                        "actionTypeId": {
                            "category": "Build",
                            "owner": "Custom",
                            "provider": "%s",
                            "version": "1"
                        }
                    }
                    """.formatted(provider));
            if (response.jsonPath().getList("jobs").size() == 1) {
                return response;
            }
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Custom action job was not created");
    }

    private Response waitForPipelineExecutionCount(String pipelineName, int expectedCount) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        Response response;
        do {
            response = post("ListPipelineExecutions", """
                    {"pipelineName": "%s"}
                    """.formatted(pipelineName));
            if (response.jsonPath().getList("pipelineExecutionSummaries").size() == expectedCount) {
                return response;
            }
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Pipeline execution count did not reach " + expectedCount);
    }

    private void waitForExecution(String pipelineName, String executionId, String expected) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        String status;
        do {
            status = post("GetPipelineExecution", """
                    {"pipelineName": "%s", "pipelineExecutionId": "%s"}
                    """.formatted(pipelineName, executionId))
                    .jsonPath().getString("pipelineExecution.status");
            if (expected.equals(status)) {
                return;
            }
            if ("Failed".equals(status)) {
                throw new AssertionError("Pipeline failed");
            }
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Pipeline did not reach " + expected + ", last status: " + status);
    }

    private Response waitForApprovalStatus(String pipelineName, String expectedStatus) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        Response response;
        String status;
        do {
            response = post("GetPipelineState", """
                    {"name": "%s"}
                    """.formatted(pipelineName));
            status = response.jsonPath().getString("stageStates[0].actionStates[0].latestExecution.status");
            if (expectedStatus.equals(status)) {
                return response;
            }
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Approval action did not reach " + expectedStatus + ", last status: " + status);
    }

    private void waitForStageStatus(String pipelineName, String stageName, String expected) {
        await("stage " + stageName + " to reach " + expected).atMost(Duration.ofSeconds(5))
                .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(50))
                .until(() -> post("GetPipelineState", """
                        {"name": "%s"}
                        """.formatted(pipelineName))
                        .jsonPath().getString("stageStates.find { it.stageName == '%s' }.latestExecution.status"
                                .formatted(stageName)), equalTo(expected));
    }

    private void waitForActionExecutionCount(String pipelineName, String executionId, String actionName,
                                             int expected) {
        await(actionName + " to run " + expected + " times").atMost(Duration.ofSeconds(5))
                .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(50))
                .until(() -> post("ListActionExecutions", """
                        {"pipelineName": "%s", "filter": {"pipelineExecutionId": "%s"}}
                        """.formatted(pipelineName, executionId))
                        .jsonPath().getList("actionExecutionDetails.findAll { it.actionName == '%s' }"
                                .formatted(actionName)).size(), equalTo(expected));
    }

    private String waitForApprovalToken(String pipelineName) throws Exception {
        return waitForApprovalToken(pipelineName, 0);
    }

    private String waitForApprovalToken(String pipelineName, int actionIndex) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        String token;
        do {
            token = post("GetPipelineState", """
                    {"name": "%s"}
                    """.formatted(pipelineName))
                    .jsonPath().getString(
                            "stageStates[0].actionStates[%d].latestExecution.token".formatted(actionIndex));
            if (token != null) {
                return token;
            }
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Approval token was not issued for pipeline " + pipelineName);
    }

    private void waitForActiveExecutions(String pipelineName, int expected) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        do {
            int active = post("ListPipelineExecutions", "{\"pipelineName\": \"%s\"}".formatted(pipelineName))
                    .jsonPath().getList("pipelineExecutionSummaries.status", String.class).stream()
                    .filter("InProgress"::equals)
                    .toList()
                    .size();
            if (active >= expected) {
                return;
            }
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Pipeline did not reach " + expected + " active executions");
    }

    private static String approvalPipeline(String name, String executionMode) {
        return """
                {
                    "pipeline": {
                        "name": "%s",
                        "roleArn": "arn:aws:iam::000000000000:role/codepipeline-role",
                        "pipelineType": "V2",
                        "executionMode": "%s",
                        "artifactStore": {"type": "S3", "location": "codepipeline-artifacts"},
                        "stages": [{
                            "name": "Approve",
                            "actions": [{
                                "name": "ManualApproval",
                                "actionTypeId": {
                                    "category": "Approval",
                                    "owner": "AWS",
                                    "provider": "Manual",
                                    "version": "1"
                                }
                            }]
                        }, {
                            "name": "Complete",
                            "actions": [{
                                "name": "ManualApprovalComplete",
                                "actionTypeId": {
                                    "category": "Approval",
                                    "owner": "AWS",
                                    "provider": "Manual",
                                    "version": "1"
                                }
                            }]
                        }]
                    }
                }
                """.formatted(name, executionMode);
    }

    private static String startExecution(String pipelineName) {
        return post("StartPipelineExecution", "{\"name\": \"%s\"}".formatted(pipelineName))
                .then()
                .statusCode(200)
                .extract().jsonPath().getString("pipelineExecutionId");
    }

    private static void stopExecutions(String pipelineName, List<String> executionIds) {
        for (String executionId : executionIds) {
            post("StopPipelineExecution", """
                    {"pipelineName": "%s", "pipelineExecutionId": "%s", "abandon": true}
                    """.formatted(pipelineName, executionId)).then().statusCode(200);
        }
    }

    // A retry sent while the previous run is still finishing is rejected with ConflictException,
    // which AWS documents as "try again later".
    private static Response retryStage(String pipelineName, String stageName, String executionId,
                                       String retryMode) {
        AtomicReference<Response> response = new AtomicReference<>();
        await("the previous run of " + executionId + " to finish").atMost(Duration.ofSeconds(5))
                .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    response.set(post("RetryStageExecution",
                            retryBody(pipelineName, stageName, executionId, retryMode)));
                    return !isConflict(response.get());
                });
        return response.get();
    }

    private static List<Response> retryConcurrently(ExecutorService callers, int count, String pipelineName,
                                                    String stageName, String executionId, String retryMode)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            futures.add(callers.submit(() -> {
                start.await();
                return post("RetryStageExecution", retryBody(pipelineName, stageName, executionId, retryMode));
            }));
        }
        start.countDown();
        List<Response> responses = new ArrayList<>();
        for (Future<Response> future : futures) {
            responses.add(future.get(10, TimeUnit.SECONDS));
        }
        return responses;
    }

    private static String retryBody(String pipelineName, String stageName, String executionId, String retryMode) {
        return """
                {
                    "pipelineName": "%s",
                    "stageName": "%s",
                    "pipelineExecutionId": "%s",
                    "retryMode": "%s"
                }
                """.formatted(pipelineName, stageName, executionId, retryMode);
    }

    private static boolean isConflict(Response response) {
        return response.statusCode() == 400
                && String.valueOf(response.jsonPath().getString("__type")).contains("ConflictException");
    }

    private static Response post(String action, String body) {
        return given()
                .header("X-Amz-Target", TARGET + action)
                .contentType(CONTENT_TYPE)
                .body(body)
        .when()
                .post("/");
    }

    private static String pipeline(String name, String stages) {
        return """
                {
                    "pipeline": {
                        "name": "%s",
                        "roleArn": "arn:aws:iam::000000000000:role/codepipeline-role",
                        "artifactStore": {
                            "type": "S3",
                            "location": "codepipeline-artifacts"
                        },
                        "stages": [%s]
                    }
                }
                """.formatted(name, stages);
    }

    private static void createBucket(String bucket) {
        given().when().put("/" + bucket).then().statusCode(200);
    }

    private static String putObject(String bucket, String key, String body) {
        return putObject(bucket, key, body, null);
    }

    private static String putObject(String bucket, String key, String body, String revisionSummary) {
        RequestSpecification request = given()
                .contentType("application/octet-stream")
                .body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (revisionSummary != null) {
            request.header("x-amz-meta-codepipeline-artifact-revision-summary", revisionSummary);
        }
        return request
        .when()
                .put("/" + bucket + "/" + key)
        .then()
                .statusCode(200)
                .header("ETag", notNullValue())
                .extract().header("ETag");
    }

    private static String unquote(String value) {
        return value == null ? null : value.replace("\"", "");
    }
}
