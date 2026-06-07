package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AslExecutorCatchTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";
    private static final String FAILING_FUNCTION_NAME = "failing-lambda";
    private static final String CLEANUP_FUNCTION_NAME = "cleanup-lambda";
    private static final String FAILING_FUNCTION_ARN = lambdaArn(FAILING_FUNCTION_NAME);
    private static final String CLEANUP_FUNCTION_ARN = lambdaArn(CLEANUP_FUNCTION_NAME);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LambdaExecutorService lambdaExecutor;
    private LambdaFunctionStore functionStore;
    private LambdaFunction failingFunction;
    private LambdaFunction cleanupFunction;
    private AslExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        lambdaExecutor = mock(LambdaExecutorService.class);
        functionStore = mock(LambdaFunctionStore.class);
        failingFunction = lambdaFunction(FAILING_FUNCTION_NAME, FAILING_FUNCTION_ARN);
        cleanupFunction = lambdaFunction(CLEANUP_FUNCTION_NAME, CLEANUP_FUNCTION_ARN);

        when(functionStore.get(REGION, FAILING_FUNCTION_NAME)).thenReturn(Optional.of(failingFunction));
        when(functionStore.get(REGION, CLEANUP_FUNCTION_NAME)).thenReturn(Optional.of(cleanupFunction));
        when(lambdaExecutor.invoke(eq(failingFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Handled", errorPayload(), null, "fail-request"));
        when(lambdaExecutor.invoke(eq(cleanupFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    byte[] payload = invocation.getArgument(1);
                    JsonNode input = objectMapper.readTree(payload);
                    byte[] output = objectMapper.writeValueAsBytes(objectMapper.createObjectNode()
                            .put("cleanup", true)
                            .set("input", input));
                    return new InvokeResult(200, null, output, null, "cleanup-request");
                });

        executor = new AslExecutor(
                lambdaExecutor,
                functionStore,
                mock(DynamoDbService.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class),
                objectMapper,
                new JsonataEvaluator(objectMapper),
                mock(Instance.class));
    }

    @Test
    void lambdaTaskFailureWithStatesAllCatchTransitionsToCleanupWithOriginalInput() throws Exception {
        Execution execution = run("""
                {
                  "StartAt": "FailingLambdaTask",
                  "States": {
                    "FailingLambdaTask": {
                      "Type": "Task",
                      "Resource": "%s",
                      "Next": "UnexpectedSuccess",
                      "Catch": [{
                        "ErrorEquals": ["States.ALL"],
                        "Next": "CleanupLambdaTask",
                        "ResultPath": null
                      }]
                    },
                    "UnexpectedSuccess": {
                      "Type": "Fail",
                      "Cause": "failing Lambda unexpectedly succeeded"
                    },
                    "CleanupLambdaTask": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true
                    }
                  }
                }
                """.formatted(FAILING_FUNCTION_ARN, CLEANUP_FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertTrue(output.path("cleanup").asBoolean());
        assertEquals("token-1", output.path("input").path("cleanupToken").asText());
        assertTrue(output.path("input").path("fail").asBoolean());

        verify(lambdaExecutor).invoke(eq(failingFunction), any(byte[].class), eq(InvocationType.RequestResponse));
        verify(lambdaExecutor).invoke(eq(cleanupFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void lambdaTaskFailureWithoutCatchStillFailsExecution() throws Exception {
        Execution execution = run("""
                {
                  "StartAt": "FailingLambdaTask",
                  "States": {
                    "FailingLambdaTask": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true
                    }
                  }
                }
                """.formatted(FAILING_FUNCTION_ARN));

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Lambda.AWSLambdaException", execution.getError());
        assertEquals("Handled", execution.getCause());
        verify(lambdaExecutor).invoke(eq(failingFunction), any(byte[].class), eq(InvocationType.RequestResponse));
        verify(lambdaExecutor, never()).invoke(eq(cleanupFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    private Execution run(String definition) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("catch-test");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:catch-test".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("catch-test-execution");
        execution.setExecutionArn("arn:aws:states:%s:%s:execution:catch-test:catch-test-execution".formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{\"fail\":true,\"cleanupToken\":\"token-1\"}");

        List<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }

    private byte[] errorPayload() {
        return "{\"errorType\":\"ContractFailure\",\"errorMessage\":\"forced failure\"}".getBytes(StandardCharsets.UTF_8);
    }

    private static LambdaFunction lambdaFunction(String name, String arn) {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName(name);
        function.setFunctionArn(arn);
        return function;
    }

    private static String lambdaArn(String name) {
        return "arn:aws:lambda:%s:%s:function:%s".formatted(REGION, ACCOUNT, name);
    }
}
