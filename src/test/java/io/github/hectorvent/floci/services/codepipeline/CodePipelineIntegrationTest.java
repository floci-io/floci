package io.github.hectorvent.floci.services.codepipeline;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static io.restassured.RestAssured.given;
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

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void pipelineLifecycleExecutesS3SourceAndDeployActions() throws Exception {
        createBucket("codepipeline-source");
        createBucket("codepipeline-destination");
        putObject("codepipeline-source", "source.zip", "pipeline artifact");

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
                .body("pipelineExecution.artifactRevisions[0].name", equalTo("SourceObject"));

        post("GetPipelineState", """
                {"name": "%s"}
                """.formatted(pipelineName))
                .then()
                .statusCode(200)
                .body("stageStates", hasSize(2))
                .body("stageStates[0].actionStates[0].latestExecution.status", equalTo("Succeeded"));

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

    private Response waitForJob() throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        Response response;
        do {
            response = post("PollForJobs", """
                    {
                        "actionTypeId": {
                            "category": "Build",
                            "owner": "Custom",
                            "provider": "FlociWorker",
                            "version": "1"
                        }
                    }
                    """);
            if (response.jsonPath().getList("jobs").size() == 1) {
                return response;
            }
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Custom action job was not created");
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

    private static void putObject(String bucket, String key, String body) {
        given()
                .contentType("application/octet-stream")
                .body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .when()
                .put("/" + bucket + "/" + key)
        .then()
                .statusCode(200);
    }
}
