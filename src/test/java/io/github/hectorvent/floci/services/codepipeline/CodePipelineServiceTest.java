package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codebuild.CodeBuildService;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelinePipeline;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodePipelineServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper mapper = new ObjectMapper();
    private final CodePipelineService service = new CodePipelineService(
            new InMemoryStorageFactory(),
            mapper,
            mock(CodeBuildService.class),
            mock(CodeDeployService.class),
            mock(LambdaService.class),
            mock(S3Service.class));

    @Test
    void getPipelineTreatsMissingStoredVersionAsVersionOne() throws Exception {
        CapturingStorageFactory storageFactory = new CapturingStorageFactory();
        CodePipelineService legacyService = new CodePipelineService(
                storageFactory,
                mapper,
                mock(CodeBuildService.class),
                mock(CodeDeployService.class),
                mock(LambdaService.class),
                mock(S3Service.class));
        CodePipelinePipeline pipeline = mapper.readValue("""
                {
                    "accountId": "000000000000",
                    "region": "us-east-1",
                    "name": "legacy-pipeline",
                    "arn": "arn:aws:codepipeline:us-east-1:000000000000:legacy-pipeline",
                    "created": 1.0,
                    "updated": 1.0,
                    "declaration": {
                        "name": "legacy-pipeline",
                        "version": 1
                    }
                }
                """, CodePipelinePipeline.class);
        storageFactory.pipelineStore().putForAccount(ACCOUNT, REGION + ":legacy-pipeline", pipeline);

        try {
            JsonNode current = legacyService.handle(
                    "GetPipeline", mapper.readTree("{\"name\":\"legacy-pipeline\"}"), REGION, ACCOUNT);
            JsonNode explicitVersion = legacyService.handle(
                    "GetPipeline", mapper.readTree("{\"name\":\"legacy-pipeline\",\"version\":1}"), REGION, ACCOUNT);

            assertEquals(1, current.path("pipeline").path("version").asInt());
            assertEquals(1, explicitVersion.path("pipeline").path("version").asInt());
            AwsException missing = assertThrows(AwsException.class,
                    () -> legacyService.handle(
                            "GetPipeline", mapper.readTree("{\"name\":\"legacy-pipeline\",\"version\":2}"),
                            REGION, ACCOUNT));
            assertEquals("PipelineVersionNotFoundException", missing.getErrorCode());
        } finally {
            legacyService.shutdown();
        }
    }

    @Test
    void sourcePollingAndPipelineUpdateSerializeBaselineChanges() throws Exception {
        S3Service s3Service = mock(S3Service.class);
        S3Object source = new S3Object();
        source.setETag("\"baseline\"");
        AtomicInteger headCalls = new AtomicInteger();
        CountDownLatch pollEntered = new CountDownLatch(1);
        CountDownLatch releasePoll = new CountDownLatch(1);
        when(s3Service.headObject("source-bucket", "source.zip")).thenAnswer(invocation -> {
            int call = headCalls.incrementAndGet();
            if (call == 2) {
                pollEntered.countDown();
                if (!releasePoll.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release the source poll");
                }
            }
            return source;
        });

        CodePipelineService pollingService = new CodePipelineService(
                new InMemoryStorageFactory(),
                mapper,
                mock(CodeBuildService.class),
                mock(CodeDeployService.class),
                mock(LambdaService.class),
                s3Service);
        String pipelineRequest = """
                {
                    "pipeline": {
                        "name": "polling-pipeline",
                        "roleArn": "arn:aws:iam::000000000000:role/codepipeline-role",
                        "artifactStore": {"type": "S3", "location": "codepipeline-artifacts"},
                        "stages": [{
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
                                    "S3Bucket": "source-bucket",
                                    "S3ObjectKey": "source.zip"
                                },
                                "outputArtifacts": [{"name": "SourceOutput"}]
                            }]
                        }, {
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
                        }]
                    }
                }
                """;
        JsonNode request = mapper.readTree(pipelineRequest);

        try {
            pollingService.handle("CreatePipeline", request, REGION, ACCOUNT);
            pollingService.resumePersistedExecutions();
            assertTrue(pollEntered.await(2, TimeUnit.SECONDS));

            JsonNode updateRequest = mapper.readTree(pipelineRequest);
            CompletableFuture<JsonNode> update = CompletableFuture.supplyAsync(
                    () -> pollingService.handle("UpdatePipeline", updateRequest, REGION, ACCOUNT));

            Thread.sleep(150);
            assertFalse(update.isDone(), "UpdatePipeline must wait for the in-flight source poll");

            releasePoll.countDown();
            update.get(2, TimeUnit.SECONDS);
        } finally {
            releasePoll.countDown();
            pollingService.shutdown();
        }
    }

    @Test
    void stoppingCodeBuildWaitsForSuccessWithoutStoppingTheBuild() throws Exception {
        CodeBuildService codeBuildService = mock(CodeBuildService.class);
        AtomicReference<Build> currentBuild = new AtomicReference<>(build("IN_PROGRESS", false));
        CountDownLatch buildStarted = new CountDownLatch(1);
        when(codeBuildService.startBuild(anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                    buildStarted.countDown();
                    return currentBuild.get();
                });
        when(codeBuildService.getBuild(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> currentBuild.get());
        CodePipelineService pipelineService = serviceWith(codeBuildService, mock(CodeDeployService.class));
        String pipelineName = "codebuild-stop-wait";

        try {
            createExternalActionPipeline(pipelineService, pipelineName, """
                    {
                        "name": "BuildAction",
                        "actionTypeId": {
                            "category": "Build",
                            "owner": "AWS",
                            "provider": "CodeBuild",
                            "version": "1"
                        },
                        "configuration": {"ProjectName": "build-project"}
                    }
                    """);
            String executionId = startExecution(pipelineService, pipelineName);
            assertTrue(buildStarted.await(2, TimeUnit.SECONDS));

            stopExecution(pipelineService, pipelineName, executionId, false);
            waitForExecutionStatus(pipelineService, pipelineName, executionId, "Stopping");
            currentBuild.set(build("SUCCEEDED", true));

            waitForExecutionStatus(pipelineService, pipelineName, executionId, "Stopped");
            JsonNode state = pipelineState(pipelineService, pipelineName);
            assertEquals("Stopped", state.path("stageStates").path(0)
                    .path("latestExecution").path("status").asText());
            assertEquals("Succeeded", state.path("stageStates").path(0).path("actionStates").path(0)
                    .path("latestExecution").path("status").asText());
            verify(codeBuildService, never()).stopBuild(anyString(), anyString(), anyString());
        } finally {
            pipelineService.shutdown();
        }
    }

    @Test
    void stoppingCodeDeployWaitsForFailureWithoutFailingThePipeline() throws Exception {
        CodeDeployService codeDeployService = mock(CodeDeployService.class);
        AtomicReference<Deployment> currentDeployment = new AtomicReference<>(deployment("InProgress"));
        CountDownLatch deploymentStarted = new CountDownLatch(1);
        when(codeDeployService.createDeployment(anyString(), anyString(), anyString(),
                any(), any(), anyString())).thenAnswer(invocation -> {
                    deploymentStarted.countDown();
                    return "deployment-1";
                });
        when(codeDeployService.getDeployment(anyString(), anyString()))
                .thenAnswer(invocation -> currentDeployment.get());
        CodePipelineService pipelineService = serviceWith(mock(CodeBuildService.class), codeDeployService);
        String pipelineName = "codedeploy-stop-wait";

        try {
            createExternalActionPipeline(pipelineService, pipelineName, """
                    {
                        "name": "DeployAction",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "CodeDeploy",
                            "version": "1"
                        },
                        "configuration": {
                            "ApplicationName": "application",
                            "DeploymentGroupName": "deployment-group"
                        }
                    }
                    """);
            String executionId = startExecution(pipelineService, pipelineName);
            assertTrue(deploymentStarted.await(2, TimeUnit.SECONDS));

            stopExecution(pipelineService, pipelineName, executionId, false);
            waitForExecutionStatus(pipelineService, pipelineName, executionId, "Stopping");
            currentDeployment.set(deployment("Failed"));

            waitForExecutionStatus(pipelineService, pipelineName, executionId, "Stopped");
            JsonNode state = pipelineState(pipelineService, pipelineName);
            assertEquals("Failed", state.path("stageStates").path(0)
                    .path("latestExecution").path("status").asText());
            assertEquals("Failed", state.path("stageStates").path(0).path("actionStates").path(0)
                    .path("latestExecution").path("status").asText());
            verify(codeDeployService, never()).stopDeployment(anyString(), anyString());
        } finally {
            pipelineService.shutdown();
        }
    }

    @Test
    void abandoningExternalActionsStopsWaitingWithoutStoppingThem() throws Exception {
        CodeBuildService codeBuildService = mock(CodeBuildService.class);
        CodeDeployService codeDeployService = mock(CodeDeployService.class);
        CountDownLatch actionsStarted = new CountDownLatch(2);
        Build runningBuild = build("IN_PROGRESS", false);
        Deployment runningDeployment = deployment("InProgress");
        when(codeBuildService.startBuild(anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                    actionsStarted.countDown();
                    return runningBuild;
                });
        when(codeBuildService.getBuild(anyString(), anyString(), anyString())).thenReturn(runningBuild);
        when(codeDeployService.createDeployment(anyString(), anyString(), anyString(),
                any(), any(), anyString())).thenAnswer(invocation -> {
                    actionsStarted.countDown();
                    return "deployment-1";
                });
        when(codeDeployService.getDeployment(anyString(), anyString())).thenReturn(runningDeployment);
        CodePipelineService pipelineService = serviceWith(codeBuildService, codeDeployService);
        String pipelineName = "external-actions-abandon";

        try {
            createExternalActionPipeline(pipelineService, pipelineName, """
                    {
                        "name": "BuildAction",
                        "actionTypeId": {
                            "category": "Build",
                            "owner": "AWS",
                            "provider": "CodeBuild",
                            "version": "1"
                        },
                        "configuration": {"ProjectName": "build-project"}
                    },
                    {
                        "name": "DeployAction",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "CodeDeploy",
                            "version": "1"
                        },
                        "configuration": {
                            "ApplicationName": "application",
                            "DeploymentGroupName": "deployment-group"
                        }
                    }
                    """);
            String executionId = startExecution(pipelineService, pipelineName);
            assertTrue(actionsStarted.await(2, TimeUnit.SECONDS));

            stopExecution(pipelineService, pipelineName, executionId, true);

            waitForExecutionStatus(pipelineService, pipelineName, executionId, "Stopped");
            JsonNode state = pipelineState(pipelineService, pipelineName);
            assertEquals("Abandoned", state.path("stageStates").path(0).path("actionStates").path(0)
                    .path("latestExecution").path("status").asText());
            assertEquals("Abandoned", state.path("stageStates").path(0).path("actionStates").path(1)
                    .path("latestExecution").path("status").asText());
            verify(codeBuildService, never()).stopBuild(anyString(), anyString(), anyString());
            verify(codeDeployService, never()).stopDeployment(anyString(), anyString());
        } finally {
            pipelineService.shutdown();
        }
    }

    @Test
    void aStartAfterShutdownIsRefusedAndTheExecutionIsPersistedAsFailed() throws Exception {
        handle("CreatePipeline", """
                {
                    "pipeline": {
                        "name": "pipeline",
                        "roleArn": "arn:aws:iam::000000000000:role/codepipeline-role",
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
                """);
        service.shutdown();

        AwsException refused = assertThrows(AwsException.class,
                () -> handle("StartPipelineExecution", "{\"name\": \"pipeline\"}"));

        assertEquals("ConflictException", refused.getErrorCode());
        assertEquals(400, refused.getHttpStatus());
        JsonNode summaries = handle("ListPipelineExecutions", "{\"pipelineName\": \"pipeline\"}")
                .path("pipelineExecutionSummaries");
        assertEquals(1, summaries.size());
        assertEquals("Failed", summaries.get(0).path("status").asText());
    }

    private JsonNode handle(String action, String body) throws Exception {
        return service.handle(action, mapper.readTree(body), REGION, ACCOUNT);
    }

    private CodePipelineService serviceWith(CodeBuildService codeBuildService,
                                            CodeDeployService codeDeployService) {
        return new CodePipelineService(
                new InMemoryStorageFactory(), mapper, codeBuildService, codeDeployService,
                mock(LambdaService.class), mock(S3Service.class));
    }

    private void createExternalActionPipeline(CodePipelineService pipelineService,
                                              String pipelineName, String actions) throws Exception {
        handle(pipelineService, "CreatePipeline", """
                {
                    "pipeline": {
                        "name": "%s",
                        "roleArn": "arn:aws:iam::000000000000:role/codepipeline-role",
                        "artifactStore": {"type": "S3", "location": "codepipeline-artifacts"},
                        "stages": [{
                            "name": "External",
                            "actions": [%s]
                        }, {
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
                        }]
                    }
                }
                """.formatted(pipelineName, actions));
    }

    private String startExecution(CodePipelineService pipelineService, String pipelineName) throws Exception {
        return handle(pipelineService, "StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName)).path("pipelineExecutionId").asText();
    }

    private void stopExecution(CodePipelineService pipelineService, String pipelineName,
                               String executionId, boolean abandon) throws Exception {
        handle(pipelineService, "StopPipelineExecution", """
                {
                    "pipelineName": "%s",
                    "pipelineExecutionId": "%s",
                    "abandon": %s
                }
                """.formatted(pipelineName, executionId, abandon));
    }

    private JsonNode waitForExecutionStatus(CodePipelineService pipelineService, String pipelineName,
                                            String executionId, String expectedStatus) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        JsonNode execution;
        do {
            execution = handle(pipelineService, "GetPipelineExecution", """
                    {"pipelineName": "%s", "pipelineExecutionId": "%s"}
                    """.formatted(pipelineName, executionId)).path("pipelineExecution");
            if (expectedStatus.equals(execution.path("status").asText())) {
                return execution;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Pipeline did not reach " + expectedStatus
                + "; last status was " + execution.path("status").asText());
    }

    private JsonNode pipelineState(CodePipelineService pipelineService, String pipelineName) throws Exception {
        return handle(pipelineService, "GetPipelineState", """
                {"name": "%s"}
                """.formatted(pipelineName));
    }

    private JsonNode handle(CodePipelineService pipelineService, String action, String body) throws Exception {
        return pipelineService.handle(action, mapper.readTree(body), REGION, ACCOUNT);
    }

    private Build build(String status, boolean complete) {
        Build build = new Build();
        build.setId("build-1");
        build.setBuildStatus(status);
        build.setBuildComplete(complete);
        return build;
    }

    private Deployment deployment(String status) {
        Deployment deployment = new Deployment();
        deployment.setDeploymentId("deployment-1");
        deployment.setStatus(status);
        return deployment;
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory(ACCOUNT);
        }
    }

    private static final class CapturingStorageFactory extends StorageFactory {
        private AccountAwareStorageBackend<CodePipelinePipeline> pipelineStore;

        private CapturingStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                         TypeReference<Map<String, V>> typeReference) {
            AccountAwareStorageBackend<V> store = AccountAwareStorageBackend.inMemory(ACCOUNT);
            if ("codepipeline-pipelines.json".equals(fileName)) {
                pipelineStore = (AccountAwareStorageBackend<CodePipelinePipeline>) (AccountAwareStorageBackend<?>) store;
            }
            return store;
        }

        private AccountAwareStorageBackend<CodePipelinePipeline> pipelineStore() {
            return pipelineStore;
        }
    }
}
