package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codebuild.CodeBuildService;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V2 stage condition evaluation: beforeEntry/onSuccess rules (VariableCheck, LambdaInvoke),
 * the FAIL and SKIP results, rule-execution records, and OverrideStageCondition resume.
 */
class CodePipelineV2ConditionsTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private LambdaService lambdaService;
    private CodePipelineService service;

    @BeforeEach
    void setUp() {
        lambdaService = mock(LambdaService.class);
        S3Service s3Service = mock(S3Service.class);
        S3Object object = mock(S3Object.class);
        when(object.getData()).thenReturn("artifact".getBytes());
        when(object.getETag()).thenReturn("etag-1");
        when(object.getVersionId()).thenReturn("v1");
        when(object.getLastModified()).thenReturn(Instant.now());
        when(s3Service.getObject(anyString(), anyString())).thenReturn(object);
        // The S3 source action reads a pinned version, and CreatePipeline baselines source polling.
        when(s3Service.getObject(anyString(), anyString(), any())).thenReturn(object);
        when(s3Service.headObject(anyString(), anyString())).thenReturn(object);
        lambdaReturns(null);

        service = new CodePipelineService(new InMemoryStorageFactory(), mapper, mock(CodeBuildService.class),
                mock(CodeDeployService.class), lambdaService, s3Service);
    }

    private void lambdaReturns(String functionError) {
        InvokeResult result = mock(InvokeResult.class);
        when(result.getStatusCode()).thenReturn(200);
        when(result.getFunctionError()).thenReturn(functionError);
        when(result.getRequestId()).thenReturn("req-1");
        when(lambdaService.invoke(anyString(), anyString(), any(byte[].class), any(InvocationType.class)))
                .thenReturn(result);
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

    /** Adds a one-rule condition to the stage's {@code block} (beforeEntry or onSuccess). */
    private ObjectNode addRule(ObjectNode stage, String block, String result, String provider) {
        ObjectNode condition = stage.putObject(block).putArray("conditions").addObject();
        condition.put("result", result);
        ObjectNode rule = condition.putArray("rules").addObject();
        rule.put("name", "rule-" + provider);
        rule.putObject("ruleTypeId")
                .put("category", "Rule").put("owner", "AWS").put("provider", provider).put("version", "1");
        return rule.putObject("configuration");
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

    private JsonNode awaitStatus(String pipelineName, String executionId, String expected) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while (System.currentTimeMillis() < deadline) {
            JsonNode execution = service.handle("GetPipelineExecution", mapper.createObjectNode()
                            .put("pipelineName", pipelineName).put("pipelineExecutionId", executionId),
                    REGION, ACCOUNT).path("pipelineExecution");
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

    private JsonNode ruleExecutions(String pipelineName) {
        return service.handle("ListRuleExecutions",
                mapper.createObjectNode().put("pipelineName", pipelineName), REGION, ACCOUNT)
                .path("ruleExecutionDetails");
    }

    // ---------------------------------------------------------------- tests

    @Test
    void onSuccessVariableCheckFailureFailsExecutionAndOverrideResumes() {
        // Catches: a failed onSuccess VariableCheck not failing the run, or OverrideStageCondition
        // not resuming an execution that failed on exactly that condition.
        ObjectNode deploy = lambdaStage("Deploy");
        addRule(deploy, "onSuccess", "FAIL", "VariableCheck")
                .put("Variable", "#{variables.env}").put("Value", "prod").put("Operator", "EQ");
        createPipeline("conditional", sourceStage(), deploy);

        // Start without the expected variable: the ON_SUCCESS condition fails the run.
        String executionId = startExecution("conditional");
        JsonNode failed = awaitStatus("conditional", executionId, "Failed");
        assertEquals("Condition ON_SUCCESS failed in stage Deploy.", failed.path("statusSummary").asText());
        assertEquals(1, ruleExecutions("conditional").size());
        assertEquals("Failed", ruleExecutions("conditional").get(0).path("status").asText());

        // Overriding the failed condition resumes the execution to success.
        service.handle("OverrideStageCondition", mapper.createObjectNode()
                .put("pipelineName", "conditional")
                .put("pipelineExecutionId", executionId)
                .put("stageName", "Deploy")
                .put("conditionType", "ON_SUCCESS"), REGION, ACCOUNT);
        awaitStatus("conditional", executionId, "Succeeded");
    }

    @Test
    void variableCheckMatchesRejectsAnOverlongPatternInsteadOfRunningIt() {
        // Catches: an unbounded MATCHES regex being evaluated against pipeline-declared input.
        ObjectNode deploy = lambdaStage("Deploy");
        addRule(deploy, "onSuccess", "FAIL", "VariableCheck")
                .put("Variable", "#{variables.env}").put("Value", "a".repeat(257)).put("Operator", "MATCHES");
        createPipeline("matches-overlong", sourceStage(), deploy);

        String executionId = startExecution("matches-overlong");
        awaitStatus("matches-overlong", executionId, "Failed");
        JsonNode rules = ruleExecutions("matches-overlong");
        assertEquals(1, rules.size());
        assertEquals("Failed", rules.get(0).path("status").asText());
        assertEquals("VariableCheck MATCHES pattern or value exceeds 256 characters",
                rules.get(0).path("output").path("executionResult").path("externalExecutionSummary").asText());
    }

    @Test
    void variableCheckMatchesPassesWhenTheVariableMatchesThePattern() {
        // Catches: MATCHES comparing literally instead of as a full-string regex.
        ObjectNode deploy = lambdaStage("Deploy");
        addRule(deploy, "beforeEntry", "FAIL", "VariableCheck")
                .put("Variable", "#{variables.env}").put("Value", "pr.*").put("Operator", "MATCHES");
        createPipeline("matches-ok", sourceStage(), deploy);

        ObjectNode start = mapper.createObjectNode().put("name", "matches-ok");
        start.putArray("variables").addObject().put("name", "env").put("value", "prod");
        String executionId = service.handle("StartPipelineExecution", start, REGION, ACCOUNT)
                .path("pipelineExecutionId").asText();
        awaitStatus("matches-ok", executionId, "Succeeded");
        assertEquals("Succeeded", ruleExecutions("matches-ok").get(0).path("status").asText());
    }

    @Test
    void beforeEntrySkipConditionSkipsStage() {
        // Catches: a failed beforeEntry condition with result SKIP running or failing the stage.
        ObjectNode deploy = lambdaStage("Deploy");
        addRule(deploy, "beforeEntry", "SKIP", "VariableCheck")
                .put("Variable", "#{variables.go}").put("Value", "yes").put("Operator", "EQ");
        createPipeline("skipper", sourceStage(), deploy);

        String executionId = startExecution("skipper");
        awaitStatus("skipper", executionId, "Succeeded");
        JsonNode actions = service.handle("ListActionExecutions",
                mapper.createObjectNode().put("pipelineName", "skipper"), REGION, ACCOUNT)
                .path("actionExecutionDetails");
        assertFalse(actions.findValuesAsText("stageName").contains("Deploy"));
    }

    @Test
    void failedLambdaInvokeRuleFailsTheCondition() {
        // Catches: a LambdaInvoke rule passing although the function returned a function error.
        lambdaReturns("Unhandled");
        ObjectNode deploy = lambdaStage("Deploy");
        addRule(deploy, "beforeEntry", "FAIL", "LambdaInvoke").put("FunctionName", "gate-fn");
        createPipeline("lambda-gated", sourceStage(), deploy);

        String executionId = startExecution("lambda-gated");
        JsonNode failed = awaitStatus("lambda-gated", executionId, "Failed");
        assertEquals("Condition BEFORE_ENTRY failed in stage Deploy.", failed.path("statusSummary").asText());
    }

    @Test
    void listRuleTypesReturnsTheAwsRuleCatalog() {
        // Catches: ListRuleTypes returning an empty page instead of the AWS rule providers.
        JsonNode result = service.handle("ListRuleTypes", mapper.createObjectNode(), REGION, ACCOUNT);
        List<String> providers = result.path("ruleTypes").findValuesAsText("provider");
        assertEquals(List.of("LambdaInvoke", "VariableCheck", "Commands", "DeployWindow"), providers);
    }

    @Test
    void listRuleExecutionsSurfacesRecordedRules() {
        // Catches: ListRuleExecutions dropping the recorded rule runs or their provider.
        ObjectNode deploy = lambdaStage("Deploy");
        addRule(deploy, "onSuccess", "FAIL", "LambdaInvoke").put("FunctionName", "gate-fn");
        createPipeline("ruled", sourceStage(), deploy);

        String executionId = startExecution("ruled");
        awaitStatus("ruled", executionId, "Succeeded");

        JsonNode details = ruleExecutions("ruled");
        assertEquals(1, details.size());
        JsonNode detail = details.get(0);
        assertEquals("rule-LambdaInvoke", detail.path("ruleName").asText());
        assertEquals("Deploy", detail.path("stageName").asText());
        assertEquals("Succeeded", detail.path("status").asText());
        assertEquals("LambdaInvoke", detail.path("input").path("ruleTypeId").path("provider").asText());
        assertEquals(executionId, detail.path("pipelineExecutionId").asText());
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
