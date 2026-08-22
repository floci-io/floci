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
import io.github.hectorvent.floci.services.stepfunctions.model.MockedResponseStep;
import io.github.hectorvent.floci.services.stepfunctions.model.MockedTestCase;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
class AslExecutorMockedResponsesTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";
    private static final String UNSUPPORTED_RESOURCE = "arn:aws:states:::apigateway:invoke";
    private static final String FLAKY_FUNCTION_NAME = "flaky-lambda";
    private static final String FLAKY_FUNCTION_ARN =
            "arn:aws:lambda:%s:%s:function:%s".formatted(REGION, ACCOUNT, FLAKY_FUNCTION_NAME);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LambdaExecutorService lambdaExecutor;
    private LambdaFunctionStore functionStore;
    private LambdaFunction flakyFunction;
    private AslExecutor executor;

    @Inject
    Vertx vertx;

    @BeforeEach
    void setUp() {
        lambdaExecutor = mock(LambdaExecutorService.class);
        functionStore = mock(LambdaFunctionStore.class);
        flakyFunction = new LambdaFunction();
        flakyFunction.setFunctionName(FLAKY_FUNCTION_NAME);
        flakyFunction.setFunctionArn(FLAKY_FUNCTION_ARN);
        when(functionStore.get(REGION, FLAKY_FUNCTION_NAME)).thenReturn(Optional.of(flakyFunction));

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
                objectMapper,
                new JsonataEvaluator(objectMapper),
                mock(Instance.class), mock(EmulatorConfig.class), vertx);
    }

    @Test
    void mockedReturnReplacesUnsupportedResource() throws Exception {
        var mocks = testCase(Map.of("Call API", List.of(
                returnStep(0, 0, "{\"StatusCode\": 200, \"ResponseBody\": {\"id\": 1}}"))));

        var execution = run("""
                {
                  "StartAt": "Call API",
                  "States": {
                    "Call API": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true
                    }
                  }
                }
                """.formatted(UNSUPPORTED_RESOURCE), mocks);

        assertEquals("SUCCEEDED", execution.getStatus());
        var output = objectMapper.readTree(execution.getOutput());
        assertEquals(200, output.path("StatusCode").asInt());
        assertEquals(1, output.path("ResponseBody").path("id").asInt());
        verifyNoInteractions(lambdaExecutor);
    }

    @Test
    void mockedThrowKeepsErrorAndCauseThroughCatch() throws Exception {
        var mocks = testCase(Map.of("Call API", List.of(
                throwStep(0, 0, "ApiGateway.422", "Unprocessable"))));

        var execution = run("""
                {
                  "StartAt": "Call API",
                  "States": {
                    "Call API": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Catch": [{
                        "ErrorEquals": ["ApiGateway.422"],
                        "Next": "HandleError"
                      }]
                    },
                    "HandleError": {
                      "Type": "Pass",
                      "End": true
                    }
                  }
                }
                """.formatted(UNSUPPORTED_RESOURCE), mocks);

        assertEquals("SUCCEEDED", execution.getStatus());
        var output = objectMapper.readTree(execution.getOutput());
        assertEquals("ApiGateway.422", output.path("Error").asText());
        assertEquals("Unprocessable", output.path("Cause").asText());
    }

    @Test
    void attemptKeyedMocksDriveRetry() throws Exception {
        var mocks = testCase(Map.of("Call API", List.of(
                throwStep(0, 0, "ApiGateway.429", "Too many requests"),
                returnStep(1, 2, "{\"retried\": true}"))));

        var execution = run("""
                {
                  "StartAt": "Call API",
                  "States": {
                    "Call API": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Retry": [{
                        "ErrorEquals": ["ApiGateway.429"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 2
                      }]
                    }
                  }
                }
                """.formatted(UNSUPPORTED_RESOURCE), mocks);

        assertEquals("SUCCEEDED", execution.getStatus());
        assertTrue(objectMapper.readTree(execution.getOutput()).path("retried").asBoolean());
    }

    @Test
    void missingAttemptEntryFailsExecution() throws Exception {
        var mocks = testCase(Map.of("Call API", List.of(
                throwStep(0, 0, "ApiGateway.429", "Too many requests"))));

        var execution = run("""
                {
                  "StartAt": "Call API",
                  "States": {
                    "Call API": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Retry": [{
                        "ErrorEquals": ["ApiGateway.429"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 2
                      }]
                    }
                  }
                }
                """.formatted(UNSUPPORTED_RESOURCE), mocks);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertTrue(execution.getCause().contains("attempt 1"));
    }

    @Test
    void unmockedStateRunsRealIntegrationAndRetries() throws Exception {
        when(lambdaExecutor.invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Handled",
                        "{\"errorType\":\"Boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-1"))
                .thenReturn(new InvokeResult(200, null,
                        "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), null, "req-2"));

        var execution = run("""
                {
                  "StartAt": "Flaky",
                  "States": {
                    "Flaky": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Retry": [{
                        "ErrorEquals": ["Lambda.AWSLambdaException"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 2
                      }]
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN), null);

        assertEquals("SUCCEEDED", execution.getStatus());
        assertTrue(objectMapper.readTree(execution.getOutput()).path("ok").asBoolean());
        verify(lambdaExecutor, times(2))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void exhaustedRetriesFailWithOriginalError() throws Exception {
        when(lambdaExecutor.invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Handled",
                        "{\"errorType\":\"Boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-1"));

        var execution = run("""
                {
                  "StartAt": "Flaky",
                  "States": {
                    "Flaky": {
                      "Type": "Task",
                      "Resource": "%s",
                      "End": true,
                      "Retry": [{
                        "ErrorEquals": ["Lambda.AWSLambdaException"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 1
                      }]
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN), null);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Lambda.AWSLambdaException", execution.getError());
        verify(lambdaExecutor, times(2))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    private MockedTestCase testCase(Map<String, List<MockedResponseStep>> stateResponses) {
        return new MockedTestCase("mock-test", "Case", stateResponses);
    }

    private MockedResponseStep returnStep(int from, int to, String json) throws Exception {
        return new MockedResponseStep(from, to, objectMapper.readTree(json), null, null);
    }

    private MockedResponseStep throwStep(int from, int to, String error, String cause) {
        return new MockedResponseStep(from, to, null, error, cause);
    }

    private Execution run(String definition, MockedTestCase mocks) {
        var stateMachine = new StateMachine();
        stateMachine.setName("mock-test");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:mock-test".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        var execution = new Execution();
        execution.setName("mock-test-execution");
        execution.setExecutionArn(
                "arn:aws:states:%s:%s:execution:mock-test:mock-test-execution".formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{}");

        var history = new ArrayList<HistoryEvent>();
        executor.executeSync(stateMachine, execution, history, mocks, (updated, events) -> {
        });
        return execution;
    }
}
