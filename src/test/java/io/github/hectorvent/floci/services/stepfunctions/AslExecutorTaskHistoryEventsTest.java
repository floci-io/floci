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
import io.github.hectorvent.floci.services.stepfunctions.model.MockedResponseStep;
import io.github.hectorvent.floci.services.stepfunctions.model.MockedTestCase;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for issue #2520: a Task state must emit the AWS event family
 * (Task/LambdaFunction/Activity Scheduled/Started/Succeeded/Failed) around
 * TaskStateEntered/Exited, and every event's previousEventId must chain to the id of the
 * event immediately before it, with the first state's Entered event pointing to 0.
 */
@QuarkusTest
class AslExecutorTaskHistoryEventsTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LambdaExecutorService lambdaExecutor;
    private LambdaFunctionStore functionStore;
    private AslExecutor executor;

    @Inject
    Vertx vertx;

    @BeforeEach
    void setUp() {
        lambdaExecutor = mock(LambdaExecutorService.class);
        functionStore = mock(LambdaFunctionStore.class);

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
                mock(Instance.class), mock(EmulatorConfig.class), vertx,
                mock(io.github.hectorvent.floci.core.common.CustomResourceLiveness.class));
    }

    @Test
    void mockedTaskEmitsScheduledStartedSucceededInOrder() throws Exception {
        var mocks = testCase(Map.of("Send", List.of(
                returnStep(0, 0, "{\"MessageId\":\"abc\"}"))));

        var history = new ArrayList<HistoryEvent>();
        var execution = run("""
                {
                  "StartAt": "Send",
                  "States": {
                    "Send": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sqs:sendMessage",
                      "Parameters": {
                        "QueueUrl": "https://sqs.us-east-2.amazonaws.com/000000000000/q",
                        "MessageBody.$": "$.msg"
                      },
                      "End": true
                    }
                  }
                }
                """, "{\"msg\":\"hello\"}", mocks, history);

        assertEquals("SUCCEEDED", execution.getStatus(), "history: " + typesOf(history));
        assertEquals(
                List.of("TaskStateEntered", "TaskScheduled", "TaskStarted", "TaskSucceeded",
                        "TaskStateExited", "ExecutionSucceeded"),
                typesOf(history));
        assertChain(history);

        var scheduled = eventOfType(history, "TaskScheduled");
        assertEquals("aws-sdk:sqs", scheduled.getDetails().get("resourceType"));
        assertEquals("sendMessage", scheduled.getDetails().get("resource"));
        assertEquals(REGION, scheduled.getDetails().get("region"));
        var parameters = objectMapper.readTree((String) scheduled.getDetails().get("parameters"));
        assertEquals("https://sqs.us-east-2.amazonaws.com/000000000000/q", parameters.path("QueueUrl").asText());
        assertEquals("hello", parameters.path("MessageBody").asText());
    }

    @Test
    void retriedMockEmitsTaskEventsPerAttempt() throws Exception {
        var mocks = testCase(Map.of("Send", List.of(
                throwStep(0, 1, "ApiGateway.500", "boom"),
                returnStep(2, 2, "{\"ok\":true}"))));

        var history = new ArrayList<HistoryEvent>();
        var execution = run("""
                {
                  "StartAt": "Send",
                  "States": {
                    "Send": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sqs:sendMessage",
                      "Parameters": {"QueueUrl": "https://sqs.us-east-2.amazonaws.com/000000000000/q", "MessageBody": "x"},
                      "End": true,
                      "Retry": [{
                        "ErrorEquals": ["ApiGateway.500"],
                        "IntervalSeconds": 0,
                        "BackoffRate": 1.0,
                        "MaxAttempts": 2
                      }]
                    }
                  }
                }
                """, "{}", mocks, history);

        assertEquals("SUCCEEDED", execution.getStatus(), "history: " + typesOf(history));
        assertEquals(
                List.of("TaskStateEntered",
                        "TaskScheduled", "TaskStarted", "TaskFailed",
                        "TaskScheduled", "TaskStarted", "TaskFailed",
                        "TaskScheduled", "TaskStarted", "TaskSucceeded",
                        "TaskStateExited", "ExecutionSucceeded"),
                typesOf(history));
        assertChain(history);
    }

    @Test
    void taskFailedToleratesNullCause() throws Exception {
        var mocks = testCase(Map.of("Send", List.of(
                throwStep(0, 0, "My.Error", null))));

        var history = new ArrayList<HistoryEvent>();
        var execution = run("""
                {
                  "StartAt": "Send",
                  "States": {
                    "Send": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sqs:sendMessage",
                      "Parameters": {"QueueUrl": "https://sqs.us-east-2.amazonaws.com/000000000000/q", "MessageBody": "x"},
                      "End": true
                    }
                  }
                }
                """, "{}", mocks, history);

        assertEquals("FAILED", execution.getStatus(), "history: " + typesOf(history));
        var failed = eventOfType(history, "TaskFailed");
        assertEquals("My.Error", failed.getDetails().get("error"));
        assertFalse(failed.getDetails().containsKey("cause"));
    }

    @Test
    void jsonataArgumentsAppearResolvedInTaskScheduledParameters() throws Exception {
        var mocks = testCase(Map.of("Send", List.of(
                returnStep(0, 0, "{\"ok\":true}"))));

        var history = new ArrayList<HistoryEvent>();
        run("""
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Send",
                  "States": {
                    "Send": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:sqs:sendMessage",
                      "Arguments": "{% $states.input %}",
                      "End": true
                    }
                  }
                }
                """, "{\"msg\":\"hi\"}", mocks, history);

        var scheduled = eventOfType(history, "TaskScheduled");
        var parameters = objectMapper.readTree((String) scheduled.getDetails().get("parameters"));
        assertEquals("hi", parameters.path("msg").asText());
    }

    @Test
    void directLambdaArnEmitsLambdaFunctionEvents() throws Exception {
        var functionName = "echo-lambda";
        var functionArn = "arn:aws:lambda:%s:%s:function:%s".formatted(REGION, ACCOUNT, functionName);
        var function = new LambdaFunction();
        function.setFunctionName(functionName);
        function.setFunctionArn(functionArn);

        when(functionStore.get(REGION, functionName)).thenReturn(Optional.of(function));
        when(lambdaExecutor.invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    var event = objectMapper.readTree((byte[]) invocation.getArgument(1));
                    var output = objectMapper.writeValueAsBytes(event);
                    return new InvokeResult(200, null, output, null, "echo-request");
                });

        var history = new ArrayList<HistoryEvent>();
        var execution = run("""
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
                """.formatted(functionArn), "{\"msg\":\"hello\"}", null, history);

        assertEquals("SUCCEEDED", execution.getStatus(), "history: " + typesOf(history));
        assertEquals(
                List.of("TaskStateEntered", "LambdaFunctionScheduled", "LambdaFunctionStarted",
                        "LambdaFunctionSucceeded", "TaskStateExited", "ExecutionSucceeded"),
                typesOf(history));
        assertChain(history);

        var scheduled = eventOfType(history, "LambdaFunctionScheduled");
        assertEquals(functionArn, scheduled.getDetails().get("resource"));
        assertFalse(scheduled.getDetails().containsKey("resourceType"));
        var input = objectMapper.readTree((String) scheduled.getDetails().get("input"));
        assertEquals("hello", input.path("msg").asText());

        var started = eventOfType(history, "LambdaFunctionStarted");
        assertNull(started.getDetails());
    }

    /**
     * A Parallel branch and a Map iteration run states of their own whose events floci does not
     * publish. The published history is still one unbroken chain: the ids of the events around them
     * do not skip the numbers those states used.
     */
    @Test
    void parallelAndMapPublishAnUnbrokenEventChain() throws Exception {
        var history = new ArrayList<HistoryEvent>();
        var execution = run("""
                {
                  "StartAt": "Fan",
                  "States": {
                    "Fan": {
                      "Type": "Parallel",
                      "Branches": [{
                        "StartAt": "BranchStep",
                        "States": {"BranchStep": {"Type": "Pass", "Next": "BranchTail"},
                                   "BranchTail": {"Type": "Pass", "End": true}}
                      }],
                      "Next": "Iterate"
                    },
                    "Iterate": {
                      "Type": "Map",
                      "ItemsPath": "$[0].items",
                      "ItemProcessor": {
                        "StartAt": "ItemStep",
                        "States": {"ItemStep": {"Type": "Pass", "End": true}}
                      },
                      "End": true
                    }
                  }
                }
                """, "{\"items\": [1, 2]}", null, history);

        assertEquals("SUCCEEDED", execution.getStatus(), "history: " + typesOf(history));
        assertEquals(
                List.of("ParallelStateEntered", "ParallelStateExited",
                        "MapStateEntered", "MapStateExited", "ExecutionSucceeded"),
                typesOf(history));
        assertChain(history);
    }

    private static List<String> typesOf(List<HistoryEvent> history) {
        return history.stream().map(HistoryEvent::getType).toList();
    }

    private static HistoryEvent eventOfType(List<HistoryEvent> history, String type) {
        return history.stream()
                .filter(event -> type.equals(event.getType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "history has no " + type + " event: " + typesOf(history)));
    }

    private static void assertChain(List<HistoryEvent> history) {
        for (var i = 0; i < history.size(); i++) {
            var event = history.get(i);
            assertEquals(i + 1L, event.getId(), "unexpected id at index " + i);
            assertEquals((long) i, event.getPreviousEventId(), "unexpected previousEventId at index " + i);
        }
    }

    private MockedTestCase testCase(Map<String, List<MockedResponseStep>> stateResponses) {
        return new MockedTestCase("history-events-test", "Case", stateResponses);
    }

    private MockedResponseStep returnStep(int from, int to, String json) throws Exception {
        return new MockedResponseStep(from, to, objectMapper.readTree(json), null, null);
    }

    private MockedResponseStep throwStep(int from, int to, String error, String cause) {
        return new MockedResponseStep(from, to, null, error, cause);
    }

    private Execution run(String definition, String input, MockedTestCase mocks, List<HistoryEvent> history) {
        var stateMachine = new StateMachine();
        stateMachine.setName("history-events-test");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:history-events-test".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        var execution = new Execution();
        execution.setName("history-events-execution");
        execution.setExecutionArn(
                "arn:aws:states:%s:%s:execution:history-events-test:history-events-execution".formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

        executor.executeSync(stateMachine, execution, history, mocks, (updated, events) -> {
        });
        return execution;
    }
}
