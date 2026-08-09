package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.codebuild.CodeBuildService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    private LambdaService lambdaService;
    private S3Service s3Service;
    private EventBridgeService eventBridgeService;
    private SnsService snsService;
    private CodePipelineService service;

    @BeforeEach
    void setUp() {
        executionStore = new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT);
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
                mapper, mock(CodeBuildService.class), mock(CodeDeployService.class),
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
