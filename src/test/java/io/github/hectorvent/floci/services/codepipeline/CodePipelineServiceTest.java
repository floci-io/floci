package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.codebuild.CodeBuildService;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineExecution;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelinePipeline;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineStoredItem;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Engine-level tests for the V2 features: stage conditions with rule evaluation,
 * OverrideStageCondition resume, in-place RetryStageExecution, and single-stage
 * RollbackStage executions.
 */
class CodePipelineServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private AccountAwareStorageBackend<CodePipelineExecution> executionStore;
    private CodeBuildService codeBuildService;
    private LambdaService lambdaService;
    private S3Service s3Service;
    private EventBridgeService eventBridgeService;
    private SnsService snsService;
    private CodePipelineService service;

    @BeforeEach
    void setUp() {
        executionStore = new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT);
        codeBuildService = mock(CodeBuildService.class);
        lambdaService = mock(LambdaService.class);
        s3Service = mock(S3Service.class);
        eventBridgeService = mock(EventBridgeService.class);
        snsService = mock(SnsService.class);

        S3Object object = mock(S3Object.class);
        when(object.getData()).thenReturn("artifact".getBytes());
        when(object.getETag()).thenReturn("etag-1");
        when(object.getVersionId()).thenReturn("v1");
        when(object.getLastModified()).thenReturn(Instant.now());
        when(s3Service.getObject(anyString(), anyString())).thenReturn(object);
        lambdaSucceeds();

        service = new CodePipelineService(
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT),
                executionStore,
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT),
                mapper, codeBuildService, mock(CodeDeployService.class),
                lambdaService, s3Service,
                new CodePipelineEventPublisher(eventBridgeService, snsService, mapper));
    }

    private void lambdaSucceeds() {
        InvokeResult ok = mock(InvokeResult.class);
        when(ok.getStatusCode()).thenReturn(200);
        when(ok.getFunctionError()).thenReturn(null);
        when(ok.getRequestId()).thenReturn("req-1");
        when(lambdaService.invoke(anyString(), anyString(), any(byte[].class), any(InvocationType.class)))
                .thenReturn(ok);
    }

    private void lambdaFails() {
        InvokeResult failed = mock(InvokeResult.class);
        when(failed.getStatusCode()).thenReturn(200);
        when(failed.getFunctionError()).thenReturn("Unhandled");
        when(failed.getRequestId()).thenReturn("req-err");
        when(lambdaService.invoke(anyString(), anyString(), any(byte[].class), any(InvocationType.class)))
                .thenReturn(failed);
    }

    // ---------------------------------------------------------------- helpers

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

    private void createPipeline(String name, ObjectNode... stages) {
        ObjectNode declaration = mapper.createObjectNode();
        declaration.put("name", name);
        declaration.put("roleArn", "arn:aws:iam::000000000000:role/cp");
        declaration.putObject("artifactStore").put("type", "S3").put("location", "bucket");
        var stageArray = declaration.putArray("stages");
        for (ObjectNode stage : stages) {
            stageArray.add(stage);
        }
        service.handle("CreatePipeline", mapper.createObjectNode().set("pipeline", declaration),
                REGION, ACCOUNT);
    }

    private String startExecution(String pipelineName) {
        JsonNode result = service.handle("StartPipelineExecution",
                mapper.createObjectNode().put("name", pipelineName), REGION, ACCOUNT);
        return result.path("pipelineExecutionId").asText();
    }

    private CodePipelineExecution awaitTerminal(String executionId) {
        return awaitStatus(executionId, null);
    }

    private CodePipelineExecution awaitStatus(String executionId, String expected) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            CodePipelineExecution execution = executionStore.scanAllAccounts().stream()
                    .filter(e -> executionId.equals(e.getPipelineExecutionId()))
                    .findFirst().orElse(null);
            if (execution != null) {
                String status = execution.getStatus();
                boolean terminal = List.of("Succeeded", "Failed", "Stopped").contains(status);
                if (expected != null ? expected.equals(status) : terminal) {
                    return execution;
                }
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return fail("Execution " + executionId + " did not reach "
                + (expected == null ? "a terminal state" : expected) + " in time");
    }

    // ---------------------------------------------------------------- tests

    @Test
    void pipelineWithSourceAndLambdaSucceeds() {
        createPipeline("basic", sourceStage(), lambdaStage("Deploy"));
        CodePipelineExecution execution = awaitTerminal(startExecution("basic"));
        assertEquals("Succeeded", execution.getStatus());
        assertEquals(2, execution.getActionExecutions().size());
    }

    @Test
    void codeBuildActionUploadsInputArtifactAndPassesSourceOverride() {
        Build build = new Build();
        build.setId("proj:1");
        build.setBuildComplete(true);
        build.setBuildStatus("SUCCEEDED");
        when(codeBuildService.startBuild(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(build);

        ObjectNode buildStage = mapper.createObjectNode();
        buildStage.put("name", "Build");
        ObjectNode action = buildStage.putArray("actions").addObject();
        action.put("name", "BuildApp");
        action.putObject("actionTypeId")
                .put("category", "Build").put("owner", "AWS").put("provider", "CodeBuild").put("version", "1");
        action.putObject("configuration").put("ProjectName", "proj");
        action.putArray("inputArtifacts").addObject().put("name", "SourceOut");
        action.put("runOrder", 1);
        createPipeline("building", sourceStage(), buildStage);

        String executionId = startExecution("building");
        awaitStatus(executionId, "Succeeded");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).putObject(eq("bucket"), keyCaptor.capture(), dataCaptor.capture(),
                eq("application/zip"), eq(Map.of()));
        assertEquals("codepipeline/" + executionId + "/SourceOut.zip", keyCaptor.getValue());
        assertArrayEquals("artifact".getBytes(), dataCaptor.getValue());
        verify(codeBuildService).startBuild(eq(REGION), eq(ACCOUNT), eq("proj"),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq("S3"),
                eq("bucket/codepipeline/" + executionId + "/SourceOut.zip"),
                isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void failedCodeBuildRetryRestoresInputArtifactFromArtifactStore() {
        Build failed = new Build();
        failed.setId("proj:1");
        failed.setBuildComplete(true);
        failed.setBuildStatus("FAILED");
        Build succeeded = new Build();
        succeeded.setId("proj:2");
        succeeded.setBuildComplete(true);
        succeeded.setBuildStatus("SUCCEEDED");
        when(codeBuildService.startBuild(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(failed, succeeded);

        ObjectNode buildStage = mapper.createObjectNode();
        buildStage.put("name", "Build");
        ObjectNode action = buildStage.putArray("actions").addObject();
        action.put("name", "BuildApp");
        action.putObject("actionTypeId")
                .put("category", "Build").put("owner", "AWS")
                .put("provider", "CodeBuild").put("version", "1");
        action.putObject("configuration").put("ProjectName", "proj");
        action.putArray("inputArtifacts").addObject().put("name", "SourceOut");
        ((ArrayNode) action.path("inputArtifacts")).addObject().put("name", "OptionalOutput");
        action.put("runOrder", 1);
        createPipeline("retry-build", sourceStage(), buildStage);

        when(s3Service.getObject(eq("bucket"), contains("/OptionalOutput.zip")))
                .thenThrow(new AwsException("NoSuchKey", "The specified key does not exist.", 404));
        String executionId = startExecution("retry-build");
        awaitStatus(executionId, "Failed");

        service.handle("RetryStageExecution", mapper.createObjectNode()
                        .put("pipelineName", "retry-build")
                        .put("pipelineExecutionId", executionId)
                        .put("stageName", "Build")
                        .put("retryMode", "FAILED_ACTIONS"),
                REGION, ACCOUNT);

        assertEquals("Succeeded", awaitStatus(executionId, "Succeeded").getStatus());
        verify(s3Service).getObject("bucket",
                "codepipeline/" + executionId + "/SourceOut.zip");
        verify(s3Service, times(2)).getObject("bucket",
                "codepipeline/" + executionId + "/OptionalOutput.zip");
        verify(codeBuildService, times(2)).startBuild(eq(REGION), eq(ACCOUNT), eq("proj"),
                isNull(), isNull(), isNull(), isNull(), isNull(), eq("S3"),
                eq("bucket/codepipeline/" + executionId + "/SourceOut.zip"),
                isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void codeBuildActionPassesEnvironmentVariablesAndSecondarySources() {
        Build build = new Build();
        build.setId("proj:1");
        build.setBuildComplete(true);
        build.setBuildStatus("SUCCEEDED");
        when(codeBuildService.startBuild(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(build);

        ObjectNode source = sourceStage();
        ((ArrayNode) source.path("actions").path(0).path("outputArtifacts"))
                .addObject().put("name", "Config");

        ObjectNode buildStage = mapper.createObjectNode();
        buildStage.put("name", "Build");
        ObjectNode action = buildStage.putArray("actions").addObject();
        action.put("name", "BuildApp");
        action.putObject("actionTypeId")
                .put("category", "Build").put("owner", "AWS").put("provider", "CodeBuild").put("version", "1");
        action.putObject("configuration")
                .put("ProjectName", "proj")
                .put("EnvironmentVariables",
                        "[{\"name\":\"ACCELERATOR_STAGE\",\"value\":\"prepare\"},"
                                + "{\"name\":\"CODEPIPELINE_EXECUTION_ID\","
                                + "\"value\":\"#{codepipeline.PipelineExecutionId}\",\"type\":\"PLAINTEXT\"},"
                                + "{\"name\":\"ACCELERATOR_PIPELINE_VERSION\","
                                + "\"value\":\"/accelerator/version\",\"type\":\"PARAMETER_STORE\"}]");
        ArrayNode inputArtifacts = action.putArray("inputArtifacts");
        inputArtifacts.addObject().put("name", "SourceOut");
        inputArtifacts.addObject().put("name", "Config");
        action.put("runOrder", 1);
        createPipeline("multi-input", source, buildStage);

        String executionId = startExecution("multi-input");
        awaitStatus(executionId, "Succeeded");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(s3Service, times(2)).putObject(eq("bucket"), keyCaptor.capture(),
                any(byte[].class), eq("application/zip"), eq(Map.of()));
        assertEquals(List.of(
                "codepipeline/" + executionId + "/SourceOut.zip",
                "codepipeline/" + executionId + "/Config.zip"), keyCaptor.getAllValues());

        ArgumentCaptor<List<Map<String, String>>> envCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<List<ProjectSource>> secondaryCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(codeBuildService).startBuild(eq(REGION), eq(ACCOUNT), eq("proj"),
                isNull(), isNull(), envCaptor.capture(), isNull(), isNull(), eq("S3"),
                eq("bucket/codepipeline/" + executionId + "/SourceOut.zip"),
                secondaryCaptor.capture(), isNull(), isNull(), isNull());

        assertEquals(List.of(
                Map.of("name", "ACCELERATOR_STAGE", "value", "prepare", "type", "PLAINTEXT"),
                Map.of("name", "CODEPIPELINE_EXECUTION_ID", "value", executionId, "type", "PLAINTEXT"),
                Map.of("name", "ACCELERATOR_PIPELINE_VERSION", "value", "/accelerator/version",
                        "type", "PARAMETER_STORE")), envCaptor.getValue());

        assertEquals(1, secondaryCaptor.getValue().size());
        var secondary = secondaryCaptor.getValue().get(0);
        assertEquals("S3", secondary.getType());
        assertEquals("bucket/codepipeline/" + executionId + "/Config.zip", secondary.getLocation());
        assertEquals("Config", secondary.getSourceIdentifier());
    }

    @Test
    void onSuccessVariableCheckFailureFailsExecutionAndOverrideResumes() {
        ObjectNode deploy = lambdaStage("Deploy");
        ObjectNode condition = deploy.putObject("onSuccess").putArray("conditions").addObject();
        condition.put("result", "FAIL");
        ObjectNode rule = condition.putArray("rules").addObject();
        rule.put("name", "check-env");
        rule.putObject("ruleTypeId")
                .put("category", "Rule").put("owner", "AWS").put("provider", "VariableCheck").put("version", "1");
        rule.putObject("configuration")
                .put("Variable", "#{variables.env}").put("Value", "prod").put("Operator", "EQ");
        createPipeline("conditional", sourceStage(), deploy);

        // Start without the expected variable: the ON_SUCCESS condition fails the run.
        String executionId = startExecution("conditional");
        CodePipelineExecution failed = awaitStatus(executionId, "Failed");
        assertTrue(failed.getStatusSummary().contains("ON_SUCCESS"));
        assertEquals(1, failed.getRuleExecutions().size());
        assertEquals("Failed", failed.getRuleExecutions().get(0).get("status"));

        // Overriding the failed condition resumes the execution to success.
        ObjectNode override = mapper.createObjectNode()
                .put("pipelineName", "conditional")
                .put("pipelineExecutionId", executionId)
                .put("stageName", "Deploy")
                .put("conditionType", "ON_SUCCESS");
        service.handle("OverrideStageCondition", override, REGION, ACCOUNT);
        assertEquals("Succeeded", awaitStatus(executionId, "Succeeded").getStatus());
    }

    @Test
    void beforeEntrySkipConditionSkipsStage() {
        ObjectNode deploy = lambdaStage("Deploy");
        ObjectNode condition = deploy.putObject("beforeEntry").putArray("conditions").addObject();
        condition.put("result", "SKIP");
        ObjectNode rule = condition.putArray("rules").addObject();
        rule.put("name", "gate");
        rule.putObject("ruleTypeId")
                .put("category", "Rule").put("owner", "AWS").put("provider", "VariableCheck").put("version", "1");
        rule.putObject("configuration")
                .put("Variable", "#{variables.go}").put("Value", "yes").put("Operator", "EQ");
        createPipeline("skipper", sourceStage(), deploy);

        CodePipelineExecution execution = awaitTerminal(startExecution("skipper"));
        assertEquals("Succeeded", execution.getStatus());
        assertTrue(execution.getActionExecutions().stream()
                .noneMatch(a -> "Deploy".equals(a.getStageName())));
    }

    @Test
    void retryStageExecutionResumesFailedStageInPlace() {
        createPipeline("retryable", sourceStage(), lambdaStage("Deploy"));
        lambdaFails();
        String executionId = startExecution("retryable");
        awaitStatus(executionId, "Failed");

        // Retry before the failure is fixed is allowed; retry of a running/succeeded one is not.
        lambdaSucceeds();
        JsonNode result = service.handle("RetryStageExecution", mapper.createObjectNode()
                        .put("pipelineName", "retryable")
                        .put("pipelineExecutionId", executionId)
                        .put("stageName", "Deploy")
                        .put("retryMode", "FAILED_ACTIONS"),
                REGION, ACCOUNT);
        assertEquals(executionId, result.path("pipelineExecutionId").asText());

        CodePipelineExecution retried = awaitStatus(executionId, "Succeeded");
        // The Fetch stage's action from the first attempt is still there, the Deploy stage
        // has exactly one (new) execution record.
        assertEquals(1, retried.getActionExecutions().stream()
                .filter(a -> "Deploy".equals(a.getStageName())).count());

        AwsException notRetryable = assertThrows(AwsException.class, () ->
                service.handle("RetryStageExecution", mapper.createObjectNode()
                                .put("pipelineName", "retryable")
                                .put("pipelineExecutionId", executionId)
                                .put("stageName", "Deploy"),
                        REGION, ACCOUNT));
        assertEquals("StageNotRetryableException", notRetryable.getErrorCode());
    }

    @Test
    void rollbackStageRunsOnlySourceAndTargetStage() {
        createPipeline("rollable", sourceStage(), lambdaStage("Build"), lambdaStage("Deploy"));
        String firstId = startExecution("rollable");
        awaitStatus(firstId, "Succeeded");

        JsonNode result = service.handle("RollbackStage", mapper.createObjectNode()
                        .put("pipelineName", "rollable")
                        .put("stageName", "Deploy")
                        .put("targetPipelineExecutionId", firstId),
                REGION, ACCOUNT);
        String rollbackId = result.path("pipelineExecutionId").asText();
        assertNotEquals(firstId, rollbackId);

        CodePipelineExecution rollback = awaitStatus(rollbackId, "Succeeded");
        assertEquals("ROLLBACK", rollback.getExecutionType());
        assertEquals(firstId, rollback.getRollbackTargetPipelineExecutionId());
        // Only the source stage (artifact seeding) and the target stage ran — Build did not.
        assertTrue(rollback.getActionExecutions().stream()
                .noneMatch(a -> "Build".equals(a.getStageName())));
        assertTrue(rollback.getActionExecutions().stream()
                .anyMatch(a -> "Deploy".equals(a.getStageName())));
    }

    @Test
    void rollbackRequiresTheStageToHaveSucceededInTarget() {
        createPipeline("guarded", sourceStage(), lambdaStage("Deploy"));
        lambdaFails();
        String failedId = startExecution("guarded");
        awaitStatus(failedId, "Failed");

        AwsException error = assertThrows(AwsException.class, () ->
                service.handle("RollbackStage", mapper.createObjectNode()
                                .put("pipelineName", "guarded")
                                .put("stageName", "Deploy")
                                .put("targetPipelineExecutionId", failedId),
                        REGION, ACCOUNT));
        assertEquals("UnableToRollbackStageException", error.getErrorCode());
    }

    @Test
    void onFailureRollbackTriggersAutomaticRollbackExecution() {
        ObjectNode deploy = lambdaStage("Deploy");
        deploy.putObject("onFailure").put("result", "ROLLBACK");
        createPipeline("autoroll", sourceStage(), deploy);

        String goodId = startExecution("autoroll");
        awaitStatus(goodId, "Succeeded");

        lambdaFails();
        String badId = startExecution("autoroll");
        awaitStatus(badId, "Failed");

        lambdaSucceeds();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        CodePipelineExecution rollback = null;
        while (System.currentTimeMillis() < deadline && rollback == null) {
            rollback = executionStore.scanAllAccounts().stream()
                    .filter(e -> "ROLLBACK".equals(e.getExecutionType()))
                    .findFirst().orElse(null);
            if (rollback == null) {
                try {
                    TimeUnit.MILLISECONDS.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        assertTrue(rollback != null, "expected an automatic ROLLBACK execution");
        assertEquals(goodId, rollback.getRollbackTargetPipelineExecutionId());
    }

    @Test
    void executionEmitsEventBridgeStateChangeEvents() {
        createPipeline("evented", sourceStage(), lambdaStage("Deploy"));
        awaitStatus(startExecution("evented"), "Succeeded");

        // The terminal pipeline event publishes just after the status flips, so poll for it.
        List<Map<String, Object>> events = awaitPublishedEvents(
                e -> String.valueOf(e.get("Detail")).contains("\"state\":\"SUCCEEDED\"")
                        && "CodePipeline Pipeline Execution State Change".equals(e.get("DetailType")));

        assertTrue(events.stream().allMatch(e -> "aws.codepipeline".equals(e.get("Source"))));
        List<String> detailTypes = events.stream()
                .map(e -> String.valueOf(e.get("DetailType"))).distinct().toList();
        assertTrue(detailTypes.contains("CodePipeline Pipeline Execution State Change"));
        assertTrue(detailTypes.contains("CodePipeline Stage Execution State Change"));
        assertTrue(detailTypes.contains("CodePipeline Action Execution State Change"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> awaitPublishedEvents(
            java.util.function.Predicate<Map<String, Object>> awaited) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        List<Map<String, Object>> events = List.of();
        while (System.currentTimeMillis() < deadline) {
            ArgumentCaptor<List<Map<String, Object>>> entries = ArgumentCaptor.forClass(List.class);
            verify(eventBridgeService, atLeast(0)).putEvents(entries.capture(), eq(REGION));
            events = entries.getAllValues().stream().flatMap(List::stream).toList();
            if (events.stream().anyMatch(awaited)) {
                return events;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return fail("Awaited event was never published; saw " + events.size() + " events");
    }

    @Test
    void approvalActionPublishesSnsNotification() {
        ObjectNode approvalStage = mapper.createObjectNode();
        approvalStage.put("name", "Gate");
        ObjectNode action = approvalStage.putArray("actions").addObject();
        action.put("name", "HumanGate");
        action.putObject("actionTypeId")
                .put("category", "Approval").put("owner", "AWS").put("provider", "Manual").put("version", "1");
        action.putObject("configuration")
                .put("NotificationArn", "arn:aws:sns:us-east-1:000000000000:approvals")
                .put("CustomData", "please review");
        createPipeline("approved", approvalStage, lambdaStage("Deploy"));

        String executionId = startExecution("approved");
        verify(snsService, timeout(5000)).publish(
                eq("arn:aws:sns:us-east-1:000000000000:approvals"), isNull(),
                contains("please review"),
                contains("APPROVAL NEEDED"), eq(REGION));

        CodePipelineExecution execution = awaitToken(executionId);
        String token = execution.getActionExecutions().get(0).getToken();
        service.handle("PutApprovalResult", mapper.createObjectNode()
                        .put("pipelineName", "approved")
                        .put("stageName", "Gate")
                        .put("actionName", "HumanGate")
                        .put("token", token)
                        .<ObjectNode>set("result", mapper.createObjectNode()
                                .put("status", "Approved").put("summary", "ok")),
                REGION, ACCOUNT);
        awaitStatus(executionId, "Succeeded");
    }

    private CodePipelineExecution awaitToken(String executionId) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            CodePipelineExecution execution = executionStore.scanAllAccounts().stream()
                    .filter(e -> executionId.equals(e.getPipelineExecutionId()))
                    .findFirst().orElse(null);
            if (execution != null && !execution.getActionExecutions().isEmpty()
                    && execution.getActionExecutions().get(0).getToken() != null) {
                return execution;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return fail("Approval token never appeared for " + executionId);
    }

    @Test
    void gitHubSourceActionDownloadsAndRepackagesTheArchive() throws Exception {
        byte[] codeloadArchive;
        try (var baos = new java.io.ByteArrayOutputStream()) {
            try (var zos = new java.util.zip.ZipOutputStream(baos)) {
                zos.putNextEntry(new java.util.zip.ZipEntry("repo-main/"));
                zos.closeEntry();
                zos.putNextEntry(new java.util.zip.ZipEntry("repo-main/README.md"));
                zos.write("hello".getBytes());
                zos.closeEntry();
                zos.putNextEntry(new java.util.zip.ZipEntry("repo-main/src/app.ts"));
                zos.write("code".getBytes());
                zos.closeEntry();
            }
            codeloadArchive = baos.toByteArray();
        }
        java.util.List<String> fetched = new java.util.ArrayList<>();
        CodePipelineService gitHubService = new CodePipelineService(
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT),
                executionStore,
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT),
                mapper, mock(CodeBuildService.class), mock(CodeDeployService.class),
                lambdaService, s3Service,
                new CodePipelineEventPublisher(eventBridgeService, snsService, mapper)) {
            @Override
            byte[] fetchGitHubArchive(java.net.URI uri) {
                fetched.add(uri.toString());
                return codeloadArchive;
            }
        };

        ObjectNode source = mapper.createObjectNode();
        source.put("name", "Fetch");
        ObjectNode action = source.putArray("actions").addObject();
        action.put("name", "GitHubSource");
        action.putObject("actionTypeId")
                .put("category", "Source").put("owner", "ThirdParty")
                .put("provider", "GitHub").put("version", "1");
        action.putObject("configuration")
                .put("Owner", "awslabs").put("Repo", "landing-zone-accelerator-on-aws")
                .put("Branch", "release/v1.16.0");
        action.putArray("outputArtifacts").addObject().put("name", "Source");
        action.put("runOrder", 1);

        ObjectNode declaration = mapper.createObjectNode();
        declaration.put("name", "github-sourced");
        declaration.put("roleArn", "arn:aws:iam::000000000000:role/cp");
        declaration.putObject("artifactStore").put("type", "S3").put("location", "bucket");
        declaration.putArray("stages").add(source).add(lambdaStage("Deploy"));
        gitHubService.handle("CreatePipeline",
                mapper.createObjectNode().set("pipeline", declaration), REGION, ACCOUNT);

        String executionId = gitHubService.handle("StartPipelineExecution",
                mapper.createObjectNode().put("name", "github-sourced"), REGION, ACCOUNT)
                .path("pipelineExecutionId").asText();
        CodePipelineExecution execution = awaitStatus(executionId, "Succeeded");

        assertEquals(List.of("https://codeload.github.com/awslabs/landing-zone-accelerator-on-aws"
                + "/zip/refs/heads/release/v1.16.0"), fetched);
        assertEquals("release/v1.16.0", execution.getActionExecutions().get(0).getExternalExecutionId());
        assertTrue(execution.getArtifactRevisions().stream().anyMatch(r ->
                "github.com/awslabs/landing-zone-accelerator-on-aws@release/v1.16.0"
                        .equals(r.get("revisionSummary"))));
    }

    @Test
    void gitHubSourceRepackagingPreservesUnixModes() throws Exception {
        byte[] codeloadArchive;
        try (var baos = new java.io.ByteArrayOutputStream()) {
            try (var zos = new ZipArchiveOutputStream(baos)) {
                zos.putArchiveEntry(new ZipArchiveEntry("repo-main/"));
                zos.closeArchiveEntry();
                ZipArchiveEntry script = new ZipArchiveEntry("repo-main/lib/bash/bootstrap.sh");
                script.setUnixMode(0755);
                zos.putArchiveEntry(script);
                zos.write("#!/bin/bash\n".getBytes());
                zos.closeArchiveEntry();
                zos.putArchiveEntry(new ZipArchiveEntry("repo-main/README.md"));
                zos.write("hello".getBytes());
                zos.closeArchiveEntry();
            }
            codeloadArchive = baos.toByteArray();
        }
        Build build = new Build();
        build.setId("proj:1");
        build.setBuildComplete(true);
        build.setBuildStatus("SUCCEEDED");
        when(codeBuildService.startBuild(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(build);
        CodePipelineService gitHubService = new CodePipelineService(
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT),
                executionStore,
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT),
                mapper, codeBuildService, mock(CodeDeployService.class),
                lambdaService, s3Service,
                new CodePipelineEventPublisher(eventBridgeService, snsService, mapper)) {
            @Override
            byte[] fetchGitHubArchive(java.net.URI uri) {
                return codeloadArchive;
            }
        };

        ObjectNode source = mapper.createObjectNode();
        source.put("name", "Fetch");
        ObjectNode sourceAction = source.putArray("actions").addObject();
        sourceAction.put("name", "GitHubSource");
        sourceAction.putObject("actionTypeId")
                .put("category", "Source").put("owner", "ThirdParty")
                .put("provider", "GitHub").put("version", "1");
        sourceAction.putObject("configuration")
                .put("Owner", "awslabs").put("Repo", "landing-zone-accelerator-on-aws")
                .put("Branch", "main");
        sourceAction.putArray("outputArtifacts").addObject().put("name", "Source");
        sourceAction.put("runOrder", 1);

        ObjectNode buildStage = mapper.createObjectNode();
        buildStage.put("name", "Build");
        ObjectNode buildAction = buildStage.putArray("actions").addObject();
        buildAction.put("name", "BuildApp");
        buildAction.putObject("actionTypeId")
                .put("category", "Build").put("owner", "AWS").put("provider", "CodeBuild").put("version", "1");
        buildAction.putObject("configuration").put("ProjectName", "proj");
        buildAction.putArray("inputArtifacts").addObject().put("name", "Source");
        buildAction.put("runOrder", 1);

        ObjectNode declaration = mapper.createObjectNode();
        declaration.put("name", "github-modes");
        declaration.put("roleArn", "arn:aws:iam::000000000000:role/cp");
        declaration.putObject("artifactStore").put("type", "S3").put("location", "bucket");
        declaration.putArray("stages").add(source).add(buildStage);
        gitHubService.handle("CreatePipeline",
                mapper.createObjectNode().set("pipeline", declaration), REGION, ACCOUNT);

        String executionId = gitHubService.handle("StartPipelineExecution",
                mapper.createObjectNode().put("name", "github-modes"), REGION, ACCOUNT)
                .path("pipelineExecutionId").asText();
        awaitStatus(executionId, "Succeeded");

        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).putObject(eq("bucket"),
                eq("codepipeline/" + executionId + "/Source.zip"), dataCaptor.capture(),
                eq("application/zip"), eq(Map.of()));
        try (var artifact = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(dataCaptor.getValue())).get()) {
            assertEquals(0755, artifact.getEntry("lib/bash/bootstrap.sh").getUnixMode());
            assertEquals(0, artifact.getEntry("README.md").getUnixMode());
        }
    }

    @Test
    void gitHubSourceRepackagingPreservesSymlinks() throws Exception {
        byte[] codeloadArchive;
        try (var baos = new java.io.ByteArrayOutputStream()) {
            try (var zos = new ZipArchiveOutputStream(baos)) {
                zos.putArchiveEntry(new ZipArchiveEntry("repo-main/"));
                zos.closeArchiveEntry();
                ZipArchiveEntry realBin = new ZipArchiveEntry("repo-main/node_modules/ts-node/dist/bin.js");
                realBin.setUnixMode(0755);
                zos.putArchiveEntry(realBin);
                zos.write("#!/usr/bin/env node\n".getBytes());
                zos.closeArchiveEntry();
                ZipArchiveEntry link = new ZipArchiveEntry("repo-main/node_modules/.bin/ts-node");
                link.setUnixMode(0120777);
                zos.putArchiveEntry(link);
                zos.write("../ts-node/dist/bin.js".getBytes());
                zos.closeArchiveEntry();
            }
            codeloadArchive = baos.toByteArray();
        }
        Build build = new Build();
        build.setId("proj:1");
        build.setBuildComplete(true);
        build.setBuildStatus("SUCCEEDED");
        when(codeBuildService.startBuild(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(build);
        CodePipelineService gitHubService = new CodePipelineService(
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT),
                executionStore,
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT),
                mapper, codeBuildService, mock(CodeDeployService.class),
                lambdaService, s3Service,
                new CodePipelineEventPublisher(eventBridgeService, snsService, mapper)) {
            @Override
            byte[] fetchGitHubArchive(java.net.URI uri) {
                return codeloadArchive;
            }
        };

        ObjectNode source = mapper.createObjectNode();
        source.put("name", "Fetch");
        ObjectNode sourceAction = source.putArray("actions").addObject();
        sourceAction.put("name", "GitHubSource");
        sourceAction.putObject("actionTypeId")
                .put("category", "Source").put("owner", "ThirdParty")
                .put("provider", "GitHub").put("version", "1");
        sourceAction.putObject("configuration")
                .put("Owner", "awslabs").put("Repo", "landing-zone-accelerator-on-aws")
                .put("Branch", "main");
        sourceAction.putArray("outputArtifacts").addObject().put("name", "Source");
        sourceAction.put("runOrder", 1);

        ObjectNode buildStage = mapper.createObjectNode();
        buildStage.put("name", "Build");
        ObjectNode buildAction = buildStage.putArray("actions").addObject();
        buildAction.put("name", "BuildApp");
        buildAction.putObject("actionTypeId")
                .put("category", "Build").put("owner", "AWS").put("provider", "CodeBuild").put("version", "1");
        buildAction.putObject("configuration").put("ProjectName", "proj");
        buildAction.putArray("inputArtifacts").addObject().put("name", "Source");
        buildAction.put("runOrder", 1);

        ObjectNode declaration = mapper.createObjectNode();
        declaration.put("name", "github-symlinks");
        declaration.put("roleArn", "arn:aws:iam::000000000000:role/cp");
        declaration.putObject("artifactStore").put("type", "S3").put("location", "bucket");
        declaration.putArray("stages").add(source).add(buildStage);
        gitHubService.handle("CreatePipeline",
                mapper.createObjectNode().set("pipeline", declaration), REGION, ACCOUNT);

        String executionId = gitHubService.handle("StartPipelineExecution",
                mapper.createObjectNode().put("name", "github-symlinks"), REGION, ACCOUNT)
                .path("pipelineExecutionId").asText();
        awaitStatus(executionId, "Succeeded");

        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(s3Service).putObject(eq("bucket"),
                eq("codepipeline/" + executionId + "/Source.zip"), dataCaptor.capture(),
                eq("application/zip"), eq(Map.of()));
        try (var artifact = ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(dataCaptor.getValue())).get()) {
            ZipArchiveEntry link = artifact.getEntry("node_modules/.bin/ts-node");
            assertEquals(0xA000, link.getUnixMode() & 0xF000, "Symlink S_IFLNK bits must survive repackaging");
            try (var in = artifact.getInputStream(link)) {
                assertEquals("../ts-node/dist/bin.js", new String(in.readAllBytes()));
            }
            assertEquals(0755, artifact.getEntry("node_modules/ts-node/dist/bin.js").getUnixMode());
        }
    }

    @Test
    void listRuleTypesReturnsTheAwsRuleCatalog() {
        JsonNode result = service.handle("ListRuleTypes", mapper.createObjectNode(), REGION, ACCOUNT);
        List<String> providers = result.path("ruleTypes").findValuesAsText("provider");
        assertTrue(providers.containsAll(
                List.of("LambdaInvoke", "VariableCheck", "Commands", "DeployWindow")));
    }

    @Test
    void listRuleExecutionsSurfacesRecordedRules() {
        ObjectNode deploy = lambdaStage("Deploy");
        ObjectNode condition = deploy.putObject("onSuccess").putArray("conditions").addObject();
        condition.put("result", "FAIL");
        ObjectNode rule = condition.putArray("rules").addObject();
        rule.put("name", "lambda-gate");
        rule.putObject("ruleTypeId")
                .put("category", "Rule").put("owner", "AWS").put("provider", "LambdaInvoke").put("version", "1");
        rule.putObject("configuration").put("FunctionName", "gate-fn");
        createPipeline("ruled", sourceStage(), deploy);

        String executionId = startExecution("ruled");
        awaitStatus(executionId, "Succeeded");

        JsonNode result = service.handle("ListRuleExecutions",
                mapper.createObjectNode().put("pipelineName", "ruled"), REGION, ACCOUNT);
        assertEquals(1, result.path("ruleExecutionDetails").size());
        JsonNode detail = result.path("ruleExecutionDetails").get(0);
        assertEquals("lambda-gate", detail.path("ruleName").asText());
        assertEquals("Succeeded", detail.path("status").asText());
        assertEquals("LambdaInvoke", detail.path("input").path("ruleTypeId").path("provider").asText());
    }
}
