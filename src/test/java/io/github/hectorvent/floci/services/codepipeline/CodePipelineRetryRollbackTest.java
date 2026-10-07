package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codebuild.CodeBuildService;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineExecution;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RollbackStage runs a single-stage ROLLBACK execution seeded from the target execution, and
 * RetryStageExecution rejects a retryMode outside the StageRetryMode enum.
 */
class CodePipelineRetryRollbackTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private LambdaService lambdaService;
    private CodePipelineService service;
    private S3Service s3;

    @BeforeEach
    void setUp() {
        S3Service s3Service = mock(S3Service.class);
        S3Object object = mock(S3Object.class);
        when(object.getData()).thenReturn("artifact".getBytes());
        when(object.getETag()).thenReturn("etag-1");
        when(object.getVersionId()).thenReturn("v1");
        when(object.getLastModified()).thenReturn(Instant.now());
        when(s3Service.getObject(anyString(), anyString())).thenReturn(object);
        when(s3Service.getObject(anyString(), anyString(), any())).thenReturn(object);
        when(s3Service.headObject(anyString(), anyString())).thenReturn(object);

        lambdaService = mock(LambdaService.class);
        lambdaReturns(null);

        s3 = s3Service;
        service = new CodePipelineService(new InMemoryStorageFactory(), mapper, mock(CodeBuildService.class),
                mock(CodeDeployService.class), lambdaService, s3Service);
    }

    private CodePipelineService newService(StorageFactory storage, LambdaService lambda) {
        return new CodePipelineService(storage, mapper, mock(CodeBuildService.class),
                mock(CodeDeployService.class), lambda, s3);
    }

    private CodePipelineService newService(StorageFactory storage, LambdaService lambda,
                                           EventBridgeService eventBridge) {
        return new CodePipelineService(storage, mapper, mock(CodeBuildService.class),
                mock(CodeDeployService.class), lambda, s3,
                new CodePipelineEventPublisher(eventBridge, null, mapper), 500L,
                TimeUnit.SECONDS.toNanos(5), () -> { });
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // Catches: RollbackStage starting a full-pipeline execution, re-running the intermediate Build
    // stage instead of only the source stage (artifact seeding) and the target stage.
    @Test
    void rollbackStageRunsOnlySourceAndTargetStage() {
        createPipeline("rollable", sourceStage(), lambdaStage("Build"), lambdaStage("Deploy"));
        String firstId = startExecution("rollable");
        awaitStatus("rollable", firstId, "Succeeded");

        JsonNode result = service.handle("RollbackStage", mapper.createObjectNode()
                        .put("pipelineName", "rollable")
                        .put("stageName", "Deploy")
                        .put("targetPipelineExecutionId", firstId),
                REGION, ACCOUNT);
        String rollbackId = result.path("pipelineExecutionId").asText();
        assertNotEquals(firstId, rollbackId);

        JsonNode rollback = awaitStatus("rollable", rollbackId, "Succeeded");
        assertEquals("ROLLBACK", rollback.path("executionType").asText());
        assertEquals(firstId, rollback.path("rollbackMetadata").path("rollbackTargetPipelineExecutionId").asText());
        // ListActionExecutions orders by start time, and Fetch and Deploy can share a millisecond,
        // so compare which stages ran, not their order.
        assertEquals(Set.of("Fetch", "Deploy"), Set.copyOf(ranStages("rollable", rollbackId)));
    }

    // Catches: RollbackStage accepting a target execution in which the stage never succeeded.
    @Test
    void rollbackStageRejectsTargetWhereStageDidNotSucceed() {
        createPipeline("guarded", sourceStage(), lambdaStage("Deploy"));
        lambdaReturns("Unhandled");
        String failedId = startExecution("guarded");
        awaitStatus("guarded", failedId, "Failed");

        AwsException error = assertThrows(AwsException.class, () ->
                service.handle("RollbackStage", mapper.createObjectNode()
                                .put("pipelineName", "guarded")
                                .put("stageName", "Deploy")
                                .put("targetPipelineExecutionId", failedId),
                        REGION, ACCOUNT));
        assertEquals("UnableToRollbackStageException", error.getErrorCode());
    }

    // Catches: RollbackStage skipping stage-name validation and starting an execution for a stage
    // the pipeline does not have.
    @Test
    void rollbackStageRejectsUnknownStage() {
        createPipeline("known", sourceStage(), lambdaStage("Deploy"));
        String firstId = startExecution("known");
        awaitStatus("known", firstId, "Succeeded");

        AwsException error = assertThrows(AwsException.class, () ->
                service.handle("RollbackStage", mapper.createObjectNode()
                                .put("pipelineName", "known")
                                .put("stageName", "Nope")
                                .put("targetPipelineExecutionId", firstId),
                        REGION, ACCOUNT));
        assertEquals("StageNotFoundException", error.getErrorCode());
    }

    // Catches: a retryMode outside the StageRetryMode enum being treated as FAILED_ACTIONS and
    // reported as a success instead of ValidationException.
    @Test
    void retryStageExecutionRejectsRetryModeOutsideTheEnum() {
        createPipeline("retry-mode-enum", sourceStage(), lambdaStage("Deploy"));
        lambdaReturns("Unhandled");
        String executionId = startExecution("retry-mode-enum");
        awaitStatus("retry-mode-enum", executionId, "Failed");

        AwsException error = assertThrows(AwsException.class, () ->
                service.handle("RetryStageExecution", mapper.createObjectNode()
                                .put("pipelineName", "retry-mode-enum")
                                .put("pipelineExecutionId", executionId)
                                .put("stageName", "Deploy")
                                .put("retryMode", "ALL_STAGES"),
                        REGION, ACCOUNT));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals("Failed", getExecution("retry-mode-enum", executionId).path("status").asText());
    }

    // Catches: a ROLLBACK execution resumed after a restart re-running every stage, including the
    // Build stage the rollback was meant to skip, because the stage filter lived only in memory.
    @Test
    void rollbackResumedAfterRestartRunsOnlySourceAndTargetStage() {
        SharedStorageFactory storage = new SharedStorageFactory();
        service.shutdown();
        service = newService(storage, lambdaService);
        createPipeline("restartable", sourceStage(), lambdaStage("Build"), lambdaStage("Deploy"));
        String firstId = startExecution("restartable");
        awaitStatus("restartable", firstId, "Succeeded");
        String rollbackId = service.handle("RollbackStage", mapper.createObjectNode()
                        .put("pipelineName", "restartable")
                        .put("stageName", "Deploy")
                        .put("targetPipelineExecutionId", firstId),
                REGION, ACCOUNT).path("pipelineExecutionId").asText();
        awaitStatus("restartable", rollbackId, "Succeeded");

        // Crash mid-run: the persisted record says InProgress, and the process restarts.
        for (CodePipelineExecution persisted : storage.executions().scanAllAccounts()) {
            if (rollbackId.equals(persisted.getPipelineExecutionId())) {
                persisted.setStatus("InProgress");
            }
        }
        service.shutdown();
        service = newService(storage, lambdaService);
        service.resumePersistedExecutions();

        awaitStatus("restartable", rollbackId, "Succeeded");
        assertEquals(Set.of("Fetch", "Deploy"),
                Set.copyOf(ranStages("restartable", rollbackId)));
    }

    // Catches: retrying a failed target stage of a ROLLBACK execution carrying on into the stages
    // after it, which the rollback was meant to skip.
    @Test
    void retryingRollbackTargetStageDoesNotRunLaterStages() {
        createPipeline("retry-rollback", sourceStage(), lambdaStage("Deploy"), lambdaStage("Notify"));
        String firstId = startExecution("retry-rollback");
        awaitStatus("retry-rollback", firstId, "Succeeded");
        lambdaReturns("Unhandled");
        String rollbackId = service.handle("RollbackStage", mapper.createObjectNode()
                        .put("pipelineName", "retry-rollback")
                        .put("stageName", "Deploy")
                        .put("targetPipelineExecutionId", firstId),
                REGION, ACCOUNT).path("pipelineExecutionId").asText();
        awaitStatus("retry-rollback", rollbackId, "Failed");

        lambdaReturns(null);
        service.handle("RetryStageExecution", mapper.createObjectNode()
                        .put("pipelineName", "retry-rollback")
                        .put("pipelineExecutionId", rollbackId)
                        .put("stageName", "Deploy")
                        .put("retryMode", "FAILED_ACTIONS"),
                REGION, ACCOUNT);

        awaitStatus("retry-rollback", rollbackId, "Succeeded");
        assertEquals(Set.of("Fetch", "Deploy"),
                Set.copyOf(ranStages("retry-rollback", rollbackId)));
    }

    // Catches: rollback eligibility read from action statuses, so a stage whose onSuccess condition
    // failed (every action Succeeded, stage Failed) is accepted as a rollback target.
    @Test
    void rollbackStageRejectsStageWhoseOnSuccessConditionFailed() {
        ObjectNode deploy = lambdaStage("Deploy");
        ObjectNode condition = deploy.putObject("onSuccess").putArray("conditions").addObject();
        condition.put("result", "FAIL");
        ObjectNode rule = condition.putArray("rules").addObject();
        rule.put("name", "gate");
        rule.putObject("ruleTypeId")
                .put("category", "Rule").put("owner", "AWS").put("provider", "VariableCheck").put("version", "1");
        rule.putObject("configuration")
                .put("Variable", "#{variables.env}").put("Value", "prod").put("Operator", "EQ");
        createPipeline("gated", sourceStage(), deploy);
        String failedId = startExecution("gated");
        awaitStatus("gated", failedId, "Failed");

        AwsException error = assertThrows(AwsException.class, () ->
                service.handle("RollbackStage", mapper.createObjectNode()
                                .put("pipelineName", "gated")
                                .put("stageName", "Deploy")
                                .put("targetPipelineExecutionId", failedId),
                        REGION, ACCOUNT));
        assertEquals("UnableToRollbackStageException", error.getErrorCode());
    }

    // Catches: RollbackStage accepting a target execution that ran an earlier pipeline version,
    // whose stage layout the current pipeline no longer has.
    @Test
    void rollbackStageRejectsTargetFromEarlierPipelineVersion() {
        createPipeline("versioned", sourceStage(), lambdaStage("Deploy"));
        String firstId = startExecution("versioned");
        awaitStatus("versioned", firstId, "Succeeded");
        service.handle("UpdatePipeline", mapper.createObjectNode()
                .set("pipeline", declaration("versioned", sourceStage(), lambdaStage("Deploy"))), REGION, ACCOUNT);

        AwsException error = assertThrows(AwsException.class, () ->
                service.handle("RollbackStage", mapper.createObjectNode()
                                .put("pipelineName", "versioned")
                                .put("stageName", "Deploy")
                                .put("targetPipelineExecutionId", firstId),
                        REGION, ACCOUNT));
        assertEquals("UnableToRollbackStageException", error.getErrorCode());
    }

    // Catches: a manual rollback reporting triggerType RollbackStage, which is not a value of the
    // AWS TriggerType enum (ManualRollback is), or putting the target execution id in triggerDetail
    // instead of what a manual start records ("manual"); rollbackMetadata carries the target id.
    @Test
    void rollbackStageExecutionTriggerTypeIsManualRollback() {
        createPipeline("trigger-type", sourceStage(), lambdaStage("Deploy"));
        String firstId = startExecution("trigger-type");
        awaitStatus("trigger-type", firstId, "Succeeded");

        String rollbackId = service.handle("RollbackStage", mapper.createObjectNode()
                        .put("pipelineName", "trigger-type")
                        .put("stageName", "Deploy")
                        .put("targetPipelineExecutionId", firstId),
                REGION, ACCOUNT).path("pipelineExecutionId").asText();

        JsonNode rollback = awaitStatus("trigger-type", rollbackId, "Succeeded");
        assertEquals("ManualRollback", rollback.path("trigger").path("triggerType").asText());
        assertEquals("manual", rollback.path("trigger").path("triggerDetail").asText());
    }

    // Catches: a rollback execution that never publishes the pipeline STARTED state-change event.
    @Test
    void rollbackStagePublishesPipelineStartedEvent() {
        EventBridgeService eventBridge = mock(EventBridgeService.class);
        service.shutdown();
        service = newService(new InMemoryStorageFactory(), lambdaService, eventBridge);
        createPipeline("started-event", sourceStage(), lambdaStage("Deploy"));
        String firstId = startExecution("started-event");
        awaitStatus("started-event", firstId, "Succeeded");

        String rollbackId = service.handle("RollbackStage", mapper.createObjectNode()
                        .put("pipelineName", "started-event")
                        .put("stageName", "Deploy")
                        .put("targetPipelineExecutionId", firstId),
                REGION, ACCOUNT).path("pipelineExecutionId").asText();
        awaitStatus("started-event", rollbackId, "Succeeded");

        assertEquals(List.of("STARTED", "SUCCEEDED"), pipelineStates(eventBridge, rollbackId));
    }

    // Catches: a rollback whose scheduling is rejected leaving the STARTED event without a FAILED one.
    @Test
    @SuppressWarnings("unchecked")
    void rollbackStageRejectedSchedulePublishesPipelineFailedEvent() {
        EventBridgeService eventBridge = mock(EventBridgeService.class);
        service.shutdown();
        service = newService(new InMemoryStorageFactory(), lambdaService, eventBridge);
        createPipeline("rejected-event", sourceStage(), lambdaStage("Deploy"));
        String firstId = startExecution("rejected-event");
        awaitStatus("rejected-event", firstId, "Succeeded");
        service.shutdown();

        assertThrows(AwsException.class, () ->
                service.handle("RollbackStage", mapper.createObjectNode()
                                .put("pipelineName", "rejected-event")
                                .put("stageName", "Deploy")
                                .put("targetPipelineExecutionId", firstId),
                        REGION, ACCOUNT));

        ArgumentCaptor<List<Map<String, Object>>> events = ArgumentCaptor.forClass(List.class);
        verify(eventBridge, atLeastOnce()).putEvents(events.capture(), anyString(), anyString());
        List<String> states = new ArrayList<>();
        for (List<Map<String, Object>> batch : events.getAllValues()) {
            for (Map<String, Object> entry : batch) {
                JsonNode detail = parseDetail((String) entry.get("Detail"));
                if ("CodePipeline Pipeline Execution State Change".equals(entry.get("DetailType"))
                        && !firstId.equals(detail.path("execution-id").asText())) {
                    states.add(detail.path("state").asText());
                }
            }
        }
        assertEquals(List.of("STARTED", "FAILED"), states);
    }

    // Catches: a ROLLBACK execution whose target stage is no longer found (older record with no
    // rollbackStageName, or a renamed stage) running only the source stage and ending Succeeded.
    @Test
    void rollbackWhoseTargetStageIsMissingEndsFailed() {
        SharedStorageFactory storage = new SharedStorageFactory();
        service.shutdown();
        service = newService(storage, lambdaService);
        createPipeline("orphaned", sourceStage(), lambdaStage("Deploy"));
        String firstId = startExecution("orphaned");
        awaitStatus("orphaned", firstId, "Succeeded");
        String rollbackId = service.handle("RollbackStage", mapper.createObjectNode()
                        .put("pipelineName", "orphaned")
                        .put("stageName", "Deploy")
                        .put("targetPipelineExecutionId", firstId),
                REGION, ACCOUNT).path("pipelineExecutionId").asText();
        awaitStatus("orphaned", rollbackId, "Succeeded");

        for (CodePipelineExecution persisted : storage.executions().scanAllAccounts()) {
            if (rollbackId.equals(persisted.getPipelineExecutionId())) {
                persisted.setStatus("InProgress");
                persisted.setRollbackStageName(null);
            }
        }
        service.shutdown();
        service = newService(storage, lambdaService);
        service.resumePersistedExecutions();

        awaitStatus("orphaned", rollbackId, "Failed");
    }

    // Catches: RollbackStage answering a full pipeline with ConcurrentPipelineExecutionsLimitExceededException,
    // which the RollbackStage API does not model; AWS reports a busy pipeline as ConflictException.
    @Test
    void rollbackStageReportsConflictWhenNoExecutionSlotIsFree() {
        SharedStorageFactory storage = new SharedStorageFactory();
        service.shutdown();
        service = newService(storage, lambdaService);
        ObjectNode parallel = declaration("full", sourceStage(), lambdaStage("Deploy"));
        parallel.put("pipelineType", "V2").put("executionMode", "QUEUED");
        service.handle("CreatePipeline", mapper.createObjectNode().set("pipeline", parallel), REGION, ACCOUNT);
        String firstId = startExecution("full");
        awaitStatus("full", firstId, "Succeeded");

        for (int i = 0; i < 50; i++) {
            CodePipelineExecution active = new CodePipelineExecution();
            active.setAccountId(ACCOUNT);
            active.setRegion(REGION);
            active.setPipelineName("full");
            active.setPipelineExecutionId("active-" + i);
            active.setStatus("InProgress");
            storage.executions().putForAccount(ACCOUNT, REGION + ":full:active-" + i, active);
        }

        AwsException error = assertThrows(AwsException.class, () ->
                service.handle("RollbackStage", mapper.createObjectNode()
                                .put("pipelineName", "full")
                                .put("stageName", "Deploy")
                                .put("targetPipelineExecutionId", firstId),
                        REGION, ACCOUNT));
        assertEquals("ConflictException", error.getErrorCode());
    }

    // Catches: RollbackStage accepting a source stage, which cannot be rolled back (the Fetch stage
    // would run and end Succeeded).
    @Test
    void rollbackStageRejectsSourceStage() {
        createPipeline("sourced", sourceStage(), lambdaStage("Deploy"));
        String firstId = startExecution("sourced");
        awaitStatus("sourced", firstId, "Succeeded");

        AwsException error = assertThrows(AwsException.class, () ->
                service.handle("RollbackStage", mapper.createObjectNode()
                                .put("pipelineName", "sourced")
                                .put("stageName", "Fetch")
                                .put("targetPipelineExecutionId", firstId),
                        REGION, ACCOUNT));
        assertEquals("UnableToRollbackStageException", error.getErrorCode());
        assertEquals("A source stage cannot be rolled back.", error.getMessage());
    }

    // Catches: RollbackStage accepting a ROLLBACK execution as the target, starting a rollback of a
    // rollback.
    @Test
    void rollbackStageRejectsRollbackExecutionAsTarget() {
        createPipeline("chained", sourceStage(), lambdaStage("Deploy"));
        String firstId = startExecution("chained");
        awaitStatus("chained", firstId, "Succeeded");
        String rollbackId = service.handle("RollbackStage", mapper.createObjectNode()
                        .put("pipelineName", "chained")
                        .put("stageName", "Deploy")
                        .put("targetPipelineExecutionId", firstId),
                REGION, ACCOUNT).path("pipelineExecutionId").asText();
        awaitStatus("chained", rollbackId, "Succeeded");

        AwsException error = assertThrows(AwsException.class, () ->
                service.handle("RollbackStage", mapper.createObjectNode()
                                .put("pipelineName", "chained")
                                .put("stageName", "Deploy")
                                .put("targetPipelineExecutionId", rollbackId),
                        REGION, ACCOUNT));
        assertEquals("UnableToRollbackStageException", error.getErrorCode());
        assertEquals("The target execution is a rollback execution.", error.getMessage());
    }

    // Catches: RollbackStage starting while another execution is still running the stage.
    @Test
    void rollbackStageRejectsStageThatIsCurrentlyRunning() {
        SharedStorageFactory storage = new SharedStorageFactory();
        service.shutdown();
        service = newService(storage, lambdaService);
        ObjectNode parallel = declaration("running", sourceStage(), lambdaStage("Deploy"));
        parallel.put("pipelineType", "V2").put("executionMode", "QUEUED");
        service.handle("CreatePipeline", mapper.createObjectNode().set("pipeline", parallel), REGION, ACCOUNT);
        String firstId = startExecution("running");
        awaitStatus("running", firstId, "Succeeded");

        CodePipelineExecution active = new CodePipelineExecution();
        active.setAccountId(ACCOUNT);
        active.setRegion(REGION);
        active.setPipelineName("running");
        active.setPipelineExecutionId("active-deploy");
        active.setStatus("InProgress");
        active.getStageExecutionStatuses().put("Deploy", "InProgress");
        storage.executions().putForAccount(ACCOUNT, REGION + ":running:active-deploy", active);

        AwsException error = assertThrows(AwsException.class, () ->
                service.handle("RollbackStage", mapper.createObjectNode()
                                .put("pipelineName", "running")
                                .put("stageName", "Deploy")
                                .put("targetPipelineExecutionId", firstId),
                        REGION, ACCOUNT));
        assertEquals("UnableToRollbackStageException", error.getErrorCode());
        assertEquals("The stage is currently running.", error.getMessage());
    }

    // Catches: RollbackStage accepting a PARALLEL pipeline, which AWS does not support for stage
    // rollback (CodePipeline User Guide, execution modes).
    @Test
    void rollbackStageRejectsParallelPipeline() {
        ObjectNode parallel = declaration("par", sourceStage(), lambdaStage("Deploy"));
        parallel.put("pipelineType", "V2").put("executionMode", "PARALLEL");
        service.handle("CreatePipeline", mapper.createObjectNode().set("pipeline", parallel), REGION, ACCOUNT);
        String firstId = startExecution("par");
        awaitStatus("par", firstId, "Succeeded");

        AwsException error = assertThrows(AwsException.class, () ->
                service.handle("RollbackStage", mapper.createObjectNode()
                                .put("pipelineName", "par")
                                .put("stageName", "Deploy")
                                .put("targetPipelineExecutionId", firstId),
                        REGION, ACCOUNT));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals("Stage rollback is not supported on PARALLEL pipelines.", error.getMessage());
    }

    // Catches: the running check only looking at stage statuses, so a second rollback is admitted
    // while the first is still fetching its sources and has not reached the stage yet.
    @Test
    void rollbackStageRejectsSecondRollbackOfSameStageStillFetchingSources() {
        SharedStorageFactory storage = new SharedStorageFactory();
        service.shutdown();
        service = newService(storage, lambdaService);
        ObjectNode queued = declaration("inflight", sourceStage(), lambdaStage("Deploy"));
        queued.put("pipelineType", "V2").put("executionMode", "QUEUED");
        service.handle("CreatePipeline", mapper.createObjectNode().set("pipeline", queued), REGION, ACCOUNT);
        String firstId = startExecution("inflight");
        awaitStatus("inflight", firstId, "Succeeded");

        CodePipelineExecution fetching = new CodePipelineExecution();
        fetching.setAccountId(ACCOUNT);
        fetching.setRegion(REGION);
        fetching.setPipelineName("inflight");
        fetching.setPipelineExecutionId("rollback-fetching");
        fetching.setStatus("InProgress");
        fetching.setExecutionType("ROLLBACK");
        fetching.setRollbackStageName("Deploy");
        storage.executions().putForAccount(ACCOUNT, REGION + ":inflight:rollback-fetching", fetching);

        AwsException error = assertThrows(AwsException.class, () ->
                service.handle("RollbackStage", mapper.createObjectNode()
                                .put("pipelineName", "inflight")
                                .put("stageName", "Deploy")
                                .put("targetPipelineExecutionId", firstId),
                        REGION, ACCOUNT));
        assertEquals("UnableToRollbackStageException", error.getErrorCode());
        assertEquals("The stage is currently running.", error.getMessage());
    }

    // ---------------------------------------------------------------- helpers

    private void lambdaReturns(String functionError) {
        InvokeResult result = mock(InvokeResult.class);
        when(result.getStatusCode()).thenReturn(200);
        when(result.getFunctionError()).thenReturn(functionError);
        when(result.getRequestId()).thenReturn("req-1");
        when(lambdaService.invoke(anyString(), anyString(), any(byte[].class), any(InvocationType.class)))
                .thenReturn(result);
    }

    private ObjectNode sourceStage() {
        ObjectNode stage = mapper.createObjectNode();
        stage.put("name", "Fetch");
        ObjectNode action = stage.putArray("actions").addObject();
        action.put("name", "S3Source");
        action.putObject("actionTypeId")
                .put("category", "Source").put("owner", "AWS").put("provider", "S3").put("version", "1");
        action.putObject("configuration").put("S3Bucket", "bucket").put("S3ObjectKey", "app.zip");
        action.putArray("outputArtifacts").addObject().put("name", "SourceOut");
        action.put("runOrder", 1);
        return stage;
    }

    private ObjectNode lambdaStage(String stageName) {
        ObjectNode stage = mapper.createObjectNode();
        stage.put("name", stageName);
        ObjectNode action = stage.putArray("actions").addObject();
        action.put("name", stageName + "Fn");
        action.putObject("actionTypeId")
                .put("category", "Invoke").put("owner", "AWS").put("provider", "Lambda").put("version", "1");
        action.putObject("configuration").put("FunctionName", "fn-" + stageName);
        action.put("runOrder", 1);
        return stage;
    }

    private ObjectNode declaration(String name, ObjectNode... stages) {
        ObjectNode declaration = mapper.createObjectNode();
        declaration.put("name", name);
        declaration.put("roleArn", "arn:aws:iam::000000000000:role/cp");
        declaration.putObject("artifactStore").put("type", "S3").put("location", "bucket");
        ArrayNode stageArray = declaration.putArray("stages");
        for (ObjectNode stage : stages) {
            stageArray.add(stage);
        }
        return declaration;
    }

    private void createPipeline(String name, ObjectNode... stages) {
        service.handle("CreatePipeline", mapper.createObjectNode().set("pipeline", declaration(name, stages)),
                REGION, ACCOUNT);
    }

    private JsonNode parseDetail(String detail) {
        try {
            return mapper.readTree(detail);
        } catch (IOException e) {
            return fail("Unparseable event detail: " + detail);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> pipelineStates(EventBridgeService eventBridge, String executionId) {
        ArgumentCaptor<List<Map<String, Object>>> events = ArgumentCaptor.forClass(List.class);
        verify(eventBridge, atLeastOnce()).putEvents(events.capture(), anyString(), anyString());
        List<String> states = new ArrayList<>();
        for (List<Map<String, Object>> batch : events.getAllValues()) {
            for (Map<String, Object> entry : batch) {
                JsonNode detail = parseDetail((String) entry.get("Detail"));
                if ("CodePipeline Pipeline Execution State Change".equals(entry.get("DetailType"))
                        && executionId.equals(detail.path("execution-id").asText())) {
                    states.add(detail.path("state").asText());
                }
            }
        }
        return states;
    }

    private String startExecution(String pipelineName) {
        return service.handle("StartPipelineExecution",
                mapper.createObjectNode().put("name", pipelineName), REGION, ACCOUNT)
                .path("pipelineExecutionId").asText();
    }

    private JsonNode getExecution(String pipelineName, String executionId) {
        return service.handle("GetPipelineExecution", mapper.createObjectNode()
                        .put("pipelineName", pipelineName)
                        .put("pipelineExecutionId", executionId),
                REGION, ACCOUNT).path("pipelineExecution");
    }

    private JsonNode awaitStatus(String pipelineName, String executionId, String expected) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            JsonNode execution = getExecution(pipelineName, executionId);
            if (expected.equals(execution.path("status").asText())) {
                return execution;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return fail("Execution " + executionId + " did not reach " + expected + " in time");
    }

    private List<JsonNode> actionDetails(String pipelineName, String executionId) {
        JsonNode response = service.handle("ListActionExecutions", mapper.createObjectNode()
                        .put("pipelineName", pipelineName)
                        .set("filter", mapper.createObjectNode().put("pipelineExecutionId", executionId)),
                REGION, ACCOUNT);
        List<JsonNode> details = new ArrayList<>();
        response.path("actionExecutionDetails").forEach(details::add);
        // ListActionExecutions returns newest first; tests read oldest first.
        Collections.reverse(details);
        return details;
    }

    private List<String> ranStages(String pipelineName, String executionId) {
        return actionDetails(pipelineName, executionId).stream()
                .map(detail -> detail.path("stageName").asText())
                .distinct()
                .toList();
    }

    private List<String> actionStatuses(String pipelineName, String executionId, String stageName) {
        return actionDetails(pipelineName, executionId).stream()
                .filter(detail -> stageName.equals(detail.path("stageName").asText()))
                .map(detail -> detail.path("status").asText())
                .toList();
    }

    /** Hands every service the same backends, so a second service sees the first one's state. */
    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, AccountAwareStorageBackend<?>> backends = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                        TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) backends.computeIfAbsent(fileName,
                    name -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        }

        private AccountAwareStorageBackend<CodePipelineExecution> executions() {
            return create("codepipeline", "codepipeline-executions.json", null);
        }
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
}
