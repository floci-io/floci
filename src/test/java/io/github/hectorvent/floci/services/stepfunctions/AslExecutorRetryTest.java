package io.github.hectorvent.floci.services.stepfunctions;

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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retry policy semantics of the ASL executor. Attempt counts, defaults, and backoff
 * behavior were verified against real AWS Step Functions in us-east-1.
 */
@QuarkusTest
class AslExecutorRetryTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";
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
                mock(io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerService.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerController.class),
                objectMapper,
                new JsonataEvaluator(objectMapper),
                mock(Instance.class), mock(EmulatorConfig.class), vertx);
    }

    @Test
    void retriesUntilTheTaskSucceeds() throws Exception {
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
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        assertTrue(objectMapper.readTree(execution.getOutput()).path("ok").asBoolean());
        verify(lambdaExecutor, times(2))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void exhaustedRetriesFailWithOriginalError() {
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
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Lambda.AWSLambdaException", execution.getError());
        verify(lambdaExecutor, times(2))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void unmatchedErrorIsNotRetried() {
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
                        "ErrorEquals": ["SomeOther.Error"],
                        "IntervalSeconds": 1,
                        "MaxAttempts": 3
                      }]
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Lambda.AWSLambdaException", execution.getError());
        verify(lambdaExecutor, times(1))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void exhaustedRetrierFallsThroughToCatch() throws Exception {
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
                        "ErrorEquals": ["States.ALL"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 1
                      }],
                      "Catch": [{
                        "ErrorEquals": ["States.ALL"],
                        "Next": "HandleError"
                      }]
                    },
                    "HandleError": {
                      "Type": "Pass",
                      "End": true
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals("Lambda.AWSLambdaException",
                objectMapper.readTree(execution.getOutput()).path("Error").asText());
        verify(lambdaExecutor, times(2))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void parallelStateRetriesFailingBranch() throws Exception {
        failOnceThenSucceed();

        var execution = run("""
                {
                  "StartAt": "Par",
                  "States": {
                    "Par": {
                      "Type": "Parallel",
                      "Branches": [{
                        "StartAt": "Inner",
                        "States": {"Inner": {"Type": "Task", "Resource": "%s", "End": true}}
                      }],
                      "Retry": [{
                        "ErrorEquals": ["Lambda.AWSLambdaException"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 1
                      }],
                      "End": true
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        assertTrue(objectMapper.readTree(execution.getOutput()).path(0).path("ok").asBoolean());
        verify(lambdaExecutor, times(2))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void parallelBranchFailureReachesCatch() throws Exception {
        when(lambdaExecutor.invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Handled",
                        "{\"errorType\":\"Boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-1"));

        var execution = run("""
                {
                  "StartAt": "Par",
                  "States": {
                    "Par": {
                      "Type": "Parallel",
                      "Branches": [{
                        "StartAt": "Inner",
                        "States": {"Inner": {"Type": "Task", "Resource": "%s", "End": true}}
                      }],
                      "Catch": [{
                        "ErrorEquals": ["States.ALL"],
                        "Next": "HandleError"
                      }],
                      "End": true
                    },
                    "HandleError": {"Type": "Pass", "End": true}
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals("Lambda.AWSLambdaException",
                objectMapper.readTree(execution.getOutput()).path("Error").asText());
        verify(lambdaExecutor, times(1))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void taskInsideParallelBranchRetriesIndependently() throws Exception {
        failOnceThenSucceed();

        var execution = run("""
                {
                  "StartAt": "Par",
                  "States": {
                    "Par": {
                      "Type": "Parallel",
                      "Branches": [{
                        "StartAt": "Inner",
                        "States": {
                          "Inner": {
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
                      }],
                      "End": true
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("SUCCEEDED", execution.getStatus());
        assertTrue(objectMapper.readTree(execution.getOutput()).path(0).path("ok").asBoolean());
        verify(lambdaExecutor, times(2))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void mapStateRetriesFailingIteration() throws Exception {
        failOnceThenSucceed();

        var execution = run("""
                {
                  "StartAt": "Loop",
                  "States": {
                    "Loop": {
                      "Type": "Map",
                      "ItemsPath": "$.items",
                      "ItemProcessor": {
                        "ProcessorConfig": {"Mode": "INLINE"},
                        "StartAt": "Inner",
                        "States": {"Inner": {"Type": "Task", "Resource": "%s", "End": true}}
                      },
                      "Retry": [{
                        "ErrorEquals": ["Lambda.AWSLambdaException"],
                        "IntervalSeconds": 1,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 1
                      }],
                      "End": true
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN), "{\"items\": [1]}");

        assertEquals("SUCCEEDED", execution.getStatus());
        assertTrue(objectMapper.readTree(execution.getOutput()).path(0).path("ok").asBoolean());
        verify(lambdaExecutor, times(2))
                .invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void branchErrorStillFailsTheExecution() {
        when(lambdaExecutor.invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenThrow(new AssertionError("boom"));

        var execution = run("""
                {
                  "StartAt": "Par",
                  "States": {
                    "Par": {
                      "Type": "Parallel",
                      "Branches": [{
                        "StartAt": "Inner",
                        "States": {"Inner": {"Type": "Task", "Resource": "%s", "End": true}}
                      }],
                      "End": true
                    }
                  }
                }
                """.formatted(FLAKY_FUNCTION_ARN));

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
    }

    @Test
    void retryDelayFollowsBackoffAndCaps() throws Exception {
        assertEquals(10.0, delay("{\"IntervalSeconds\": 10, \"BackoffRate\": 2.0}", 1, 0.5));
        assertEquals(20.0, delay("{\"IntervalSeconds\": 10, \"BackoffRate\": 2.0}", 2, 0.5));
        assertEquals(15.0, delay("{\"IntervalSeconds\": 10, \"BackoffRate\": 2.0, \"MaxDelaySeconds\": 15}", 2, 0.5));
        assertEquals(30.0, delay("{\"IntervalSeconds\": 20, \"BackoffRate\": 2.0}", 2, 0.5));
    }

    @Test
    void fullJitterScalesTheDelayByTheRandomFactor() throws Exception {
        var retrier = "{\"IntervalSeconds\": 10, \"BackoffRate\": 1.0, \"JitterStrategy\": \"FULL\"}";
        assertEquals(0.0, delay(retrier, 1, 0.0));
        assertEquals(5.0, delay(retrier, 1, 0.5));
        assertEquals(10.0, delay("{\"IntervalSeconds\": 10, \"BackoffRate\": 1.0, \"JitterStrategy\": \"NONE\"}", 1, 0.5));
    }

    private double delay(String retrierJson, int attemptsUsed, double random) throws Exception {
        return AslExecutor.retryDelaySeconds(objectMapper.readTree(retrierJson), attemptsUsed, random);
    }

    private void failOnceThenSucceed() {
        when(lambdaExecutor.invoke(eq(flakyFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Handled",
                        "{\"errorType\":\"Boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-1"))
                .thenReturn(new InvokeResult(200, null,
                        "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), null, "req-2"));
    }

    private Execution run(String definition) {
        return run(definition, "{}");
    }

    private Execution run(String definition, String input) {
        var stateMachine = new StateMachine();
        stateMachine.setName("retry-test");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:retry-test".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        var execution = new Execution();
        execution.setName("retry-test-execution");
        execution.setExecutionArn(
                "arn:aws:states:%s:%s:execution:retry-test:retry-test-execution".formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

        var history = new ArrayList<HistoryEvent>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }
}
