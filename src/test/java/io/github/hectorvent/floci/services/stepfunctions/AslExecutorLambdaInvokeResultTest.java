package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the shape of a Lambda Task result.
 *
 * AWS returns the Invoke response envelope (ExecutedVersion / Payload / StatusCode) for the
 * optimized "arn:aws:states:::lambda:invoke" integration, but only the function output for a
 * directly specified function ARN. Reading a Lambda result through $.Payload or
 * $states.result.Payload — the documented way — depends on that distinction.
 */
@QuarkusTest
class AslExecutorLambdaInvokeResultTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";
    private static final String FUNCTION_NAME = "echo-lambda";
    private static final String FUNCTION_ARN =
            "arn:aws:lambda:%s:%s:function:%s".formatted(REGION, ACCOUNT, FUNCTION_NAME);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AslExecutor executor;

    @Inject
    Vertx vertx;

    @BeforeEach
    void setUp() throws Exception {
        LambdaExecutorService lambdaExecutor = mock(LambdaExecutorService.class);
        LambdaFunctionStore functionStore = mock(LambdaFunctionStore.class);
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName(FUNCTION_NAME);
        function.setFunctionArn(FUNCTION_ARN);

        when(functionStore.get(REGION, FUNCTION_NAME)).thenReturn(Optional.of(function));
        when(lambdaExecutor.invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    JsonNode event = objectMapper.readTree((byte[]) invocation.getArgument(1));
                    byte[] output = objectMapper.writeValueAsBytes(objectMapper.createObjectNode()
                            .put("marker", "RET")
                            .set("echo", event));
                    return new InvokeResult(200, null, output, null, "echo-request");
                });

        executor = new AslExecutor(
                lambdaExecutor,
                functionStore,
                mock(DynamoDbService.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler.class),
                mock(io.github.hectorvent.floci.services.ec2.Ec2Service.class),
                mock(S3Service.class),
                mock(io.github.hectorvent.floci.services.ecs.EcsService.class),
                mock(io.github.hectorvent.floci.services.ecs.EcsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerService.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerController.class),
                objectMapper,
                new JsonataEvaluator(objectMapper),
                mock(Instance.class), mock(EmulatorConfig.class), vertx);
    }

    @Test
    void optimizedInvokeNestsFunctionOutputInTheInvokeResponseEnvelope() throws Exception {
        Execution execution = run("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::lambda:invoke",
                      "Parameters": {"FunctionName": "%s", "Payload": {"in": 1}},
                      "End": true
                    }
                  }
                }
                """.formatted(FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertEquals("$LATEST", output.path("ExecutedVersion").asText());
        assertTrue(output.path("StatusCode").isInt());
        assertEquals(200, output.path("StatusCode").asInt());
        assertEquals("RET", output.path("Payload").path("marker").asText());
        assertEquals(1, output.path("Payload").path("echo").path("in").asInt());
        assertFalse(output.has("marker"));
        assertEquals(3, output.size());
    }

    @Test
    void directFunctionArnReturnsOnlyTheFunctionOutput() throws Exception {
        Execution execution = run("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true
                    }
                  }
                }
                """.formatted(FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertEquals("RET", output.path("marker").asText());
        assertEquals(1, output.path("echo").path("in").asInt());
        assertFalse(output.has("Payload"));
        assertFalse(output.has("StatusCode"));
        assertFalse(output.has("ExecutedVersion"));
    }

    @Test
    void resultSelectorReadsPayloadFromOptimizedInvokeResult() throws Exception {
        Execution execution = run("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::lambda:invoke",
                      "Parameters": {"FunctionName": "%s", "Payload": {"in": 1}},
                      "ResultSelector": {"marker.$": "$.Payload.marker", "status.$": "$.StatusCode"},
                      "ResultPath": "$.lambda",
                      "End": true
                    }
                  }
                }
                """.formatted(FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertEquals("RET", output.path("lambda").path("marker").asText());
        assertEquals(200, output.path("lambda").path("status").asInt());
        assertEquals(1, output.path("in").asInt());
    }

    @Test
    void outputPathPayloadUnwrapsTheOptimizedInvokeResult() throws Exception {
        Execution execution = run("""
                {
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::lambda:invoke",
                      "OutputPath": "$.Payload",
                      "Parameters": {"Payload.$": "$", "FunctionName": "%s"},
                      "End": true
                    }
                  }
                }
                """.formatted(FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertEquals("RET", output.path("marker").asText());
        assertEquals(1, output.path("echo").path("in").asInt());
    }

    @Test
    void jsonataOutputResolvesStatesResultPayload() throws Exception {
        Execution execution = run("""
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Call",
                  "States": {
                    "Call": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::lambda:invoke",
                      "Arguments": {"FunctionName": "%s", "Payload": {"in": 1}},
                      "Output": "{%% $states.result.Payload %%}",
                      "End": true
                    }
                  }
                }
                """.formatted(FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertEquals("RET", output.path("marker").asText());
        assertEquals(1, output.path("echo").path("in").asInt());
    }

    private Execution run(String definition) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("lambda-invoke-result");
        stateMachine.setStateMachineArn(
                "arn:aws:states:%s:%s:stateMachine:lambda-invoke-result".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("lambda-invoke-result-execution");
        execution.setExecutionArn("arn:aws:states:%s:%s:execution:lambda-invoke-result:run-1"
                .formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{\"in\":1}");

        List<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }
}
