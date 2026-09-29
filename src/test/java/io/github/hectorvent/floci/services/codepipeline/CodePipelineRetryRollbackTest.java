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
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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

        service = new CodePipelineService(new InMemoryStorageFactory(), mapper, mock(CodeBuildService.class),
                mock(CodeDeployService.class), lambdaService, s3Service);
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
        assertEquals(java.util.Set.of("Fetch", "Deploy"), java.util.Set.copyOf(ranStages("rollable", rollbackId)));
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

    private void createPipeline(String name, ObjectNode... stages) {
        ObjectNode declaration = mapper.createObjectNode();
        declaration.put("name", name);
        declaration.put("roleArn", "arn:aws:iam::000000000000:role/cp");
        declaration.putObject("artifactStore").put("type", "S3").put("location", "bucket");
        ArrayNode stageArray = declaration.putArray("stages");
        for (ObjectNode stage : stages) {
            stageArray.add(stage);
        }
        service.handle("CreatePipeline", mapper.createObjectNode().set("pipeline", declaration), REGION, ACCOUNT);
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
        java.util.Collections.reverse(details);
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
