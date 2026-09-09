package com.floci.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Compatibility test for issue #2520: a Task state backed by a service integration must emit
 * TaskScheduled/TaskStarted/TaskSucceeded around TaskStateEntered/TaskStateExited, and every
 * event's previousEventId must chain to the id of the event immediately before it, with the
 * first state's Entered event pointing to 0.
 *
 * Shapes pinned against real AWS: see taskScheduledEventDetails assertions below for the
 * exact fields AWS returns for the "arn:aws:states:::sqs:sendMessage" optimized integration.
 */
@DisplayName("SFN GetExecutionHistory task events and previousEventId chaining")
class StepFunctionsExecutionHistoryTest {

    private static final String ROLE_ARN = System.getenv("SFN_ROLE_ARN") != null
            ? System.getenv("SFN_ROLE_ARN")
            : "arn:aws:iam::000000000000:role/service-role/test-role";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SfnClient sfn;
    private static SqsClient sqs;

    @BeforeAll
    static void setup() {
        sfn = TestFixtures.sfnClient();
        sqs = TestFixtures.sqsClient();
    }

    @AfterAll
    static void cleanup() {
        if (sfn != null) {
            sfn.close();
        }
        if (sqs != null) {
            sqs.close();
        }
    }

    @Test
    void taskEventsAreEmittedInOrderWithChainedPreviousEventId() throws Exception {
        var queueUrl = sqs.createQueue(b -> b.queueName(TestFixtures.uniqueName("sfn-history-queue")))
                .queueUrl();

        var smDef = """
                {
                  "StartAt": "Send",
                  "States": {
                    "Send": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::sqs:sendMessage",
                      "Parameters": {
                        "QueueUrl": "%s",
                        "MessageBody": "hello"
                      },
                      "End": true
                    }
                  }
                }
                """.formatted(queueUrl);

        var smArn = sfn.createStateMachine(b -> b
                .name(TestFixtures.uniqueName("sfn-history-sm"))
                .definition(smDef)
                .roleArn(ROLE_ARN)).stateMachineArn();

        try {
            var execArn = sfn.startExecution(b -> b
                    .stateMachineArn(smArn)
                    .input("{}")).executionArn();

            var execution = pollUntilDone(execArn);
            assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);

            var history = sfn.getExecutionHistory(b -> b
                    .executionArn(execArn)
                    .includeExecutionData(true));
            var events = history.events();

            var types = events.stream().map(HistoryEvent::type).toList();
            assertThat(types).containsExactly(
                    HistoryEventType.EXECUTION_STARTED,
                    HistoryEventType.TASK_STATE_ENTERED,
                    HistoryEventType.TASK_SCHEDULED,
                    HistoryEventType.TASK_STARTED,
                    HistoryEventType.TASK_SUCCEEDED,
                    HistoryEventType.TASK_STATE_EXITED,
                    HistoryEventType.EXECUTION_SUCCEEDED);

            var expectedPreviousEventIds = List.of(0L, 0L, 2L, 3L, 4L, 5L, 6L);
            for (var i = 0; i < events.size(); i++) {
                var event = events.get(i);
                assertThat(event.id()).isEqualTo(i + 1L);
                assertThat(event.previousEventId()).isEqualTo(expectedPreviousEventIds.get(i));
            }

            var scheduled = events.get(2).taskScheduledEventDetails();
            assertThat(scheduled.resourceType()).isEqualTo("sqs");
            assertThat(scheduled.resource()).isEqualTo("sendMessage");
            assertThat(scheduled.region()).isNotBlank();
            var parameters = MAPPER.readTree(scheduled.parameters());
            assertThat(parameters.path("QueueUrl").asText()).isEqualTo(queueUrl);
            assertThat(parameters.path("MessageBody").asText()).isEqualTo("hello");

            var started = events.get(3).taskStartedEventDetails();
            assertThat(started.resourceType()).isEqualTo("sqs");
            assertThat(started.resource()).isEqualTo("sendMessage");

            var succeeded = events.get(4).taskSucceededEventDetails();
            assertThat(succeeded.resourceType()).isEqualTo("sqs");
            assertThat(succeeded.resource()).isEqualTo("sendMessage");
            assertThat(succeeded.output()).contains("MessageId");
        } finally {
            try { sfn.deleteStateMachine(b -> b.stateMachineArn(smArn)); } catch (Exception ignored) {}
            try { sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build()); } catch (Exception ignored) {}
        }
    }

    /**
     * Issue #2868: the states inside a Parallel branch and an inline Map iteration publish into the
     * execution's history, chained through previousEventId to the Started event that opened them.
     * The SDK reads each event's details under the field named for its type, which is what this
     * test pins. Shapes verified against real AWS in us-east-1.
     */
    @Test
    void parallelBranchesAndMapIterationsPublishTheirEvents() throws Exception {
        var smDef = """
                {
                  "StartAt": "P",
                  "States": {
                    "P": {
                      "Type": "Parallel",
                      "Branches": [
                        {"StartAt": "A1", "States": {"A1": {"Type": "Pass", "End": true}}}
                      ],
                      "Next": "M"
                    },
                    "M": {
                      "Type": "Map",
                      "ItemsPath": "$[0].items",
                      "MaxConcurrency": 1,
                      "ItemProcessor": {
                        "ProcessorConfig": {"Mode": "INLINE"},
                        "StartAt": "I1",
                        "States": {"I1": {"Type": "Pass", "End": true}}
                      },
                      "End": true
                    }
                  }
                }
                """;

        var smArn = sfn.createStateMachine(b -> b
                .name(TestFixtures.uniqueName("sfn-history-branches-sm"))
                .definition(smDef)
                .roleArn(ROLE_ARN)).stateMachineArn();

        try {
            var execArn = sfn.startExecution(b -> b
                    .stateMachineArn(smArn)
                    .input("{\"items\": [1, 2]}")).executionArn();

            var execution = pollUntilDone(execArn);
            assertThat(execution.status()).isEqualTo(ExecutionStatus.SUCCEEDED);

            var events = sfn.getExecutionHistory(b -> b
                    .executionArn(execArn)
                    .includeExecutionData(true)).events();

            var types = events.stream().map(HistoryEvent::type).toList();
            assertThat(types).containsExactly(
                    HistoryEventType.EXECUTION_STARTED,
                    HistoryEventType.PARALLEL_STATE_ENTERED,
                    HistoryEventType.PARALLEL_STATE_STARTED,
                    HistoryEventType.PASS_STATE_ENTERED,
                    HistoryEventType.PASS_STATE_EXITED,
                    HistoryEventType.PARALLEL_STATE_SUCCEEDED,
                    HistoryEventType.PARALLEL_STATE_EXITED,
                    HistoryEventType.MAP_STATE_ENTERED,
                    HistoryEventType.MAP_STATE_STARTED,
                    HistoryEventType.MAP_ITERATION_STARTED,
                    HistoryEventType.PASS_STATE_ENTERED,
                    HistoryEventType.PASS_STATE_EXITED,
                    HistoryEventType.MAP_ITERATION_SUCCEEDED,
                    HistoryEventType.MAP_ITERATION_STARTED,
                    HistoryEventType.PASS_STATE_ENTERED,
                    HistoryEventType.PASS_STATE_EXITED,
                    HistoryEventType.MAP_ITERATION_SUCCEEDED,
                    HistoryEventType.MAP_STATE_SUCCEEDED,
                    HistoryEventType.MAP_STATE_EXITED,
                    HistoryEventType.EXECUTION_SUCCEEDED);

            var expectedPreviousEventIds = List.of(0L, 0L, 2L, 3L, 4L, 5L, 5L, 7L, 8L, 9L, 10L, 11L, 12L,
                    9L, 14L, 15L, 16L, 17L, 17L, 19L);
            for (var i = 0; i < events.size(); i++) {
                var event = events.get(i);
                assertThat(event.id()).isEqualTo(i + 1L);
                assertThat(event.previousEventId()).as("previousEventId of event %d", i + 1)
                        .isEqualTo(expectedPreviousEventIds.get(i));
            }

            assertThat(events.get(8).mapStateStartedEventDetails().length()).isEqualTo(2);
            assertThat(events.get(9).mapIterationStartedEventDetails().name()).isEqualTo("M");
            assertThat(events.get(9).mapIterationStartedEventDetails().index()).isEqualTo(0);
            assertThat(events.get(16).mapIterationSucceededEventDetails().name()).isEqualTo("M");
            assertThat(events.get(16).mapIterationSucceededEventDetails().index()).isEqualTo(1);
            assertThat(events.get(18).stateExitedEventDetails().output()).isEqualTo("[1,2]");
        } finally {
            try { sfn.deleteStateMachine(b -> b.stateMachineArn(smArn)); } catch (Exception ignored) {}
        }
    }

    /** Issue #2868: the cause of a failure inside a branch names the branch state, as on AWS. */
    @Test
    void failureInsideABranchNamesTheBranchStateInItsCause() throws Exception {
        var smDef = """
                {
                  "StartAt": "P",
                  "States": {
                    "P": {
                      "Type": "Parallel",
                      "Branches": [
                        {"StartAt": "Fast", "States": {"Fast": {"Type": "Pass", "Parameters": {"m.$": "$.nope"}, "End": true}}}
                      ],
                      "End": true
                    }
                  }
                }
                """;

        var smArn = sfn.createStateMachine(b -> b
                .name(TestFixtures.uniqueName("sfn-history-branch-failure-sm"))
                .definition(smDef)
                .roleArn(ROLE_ARN)).stateMachineArn();

        try {
            var execArn = sfn.startExecution(b -> b
                    .stateMachineArn(smArn)
                    .input("{\"x\":\"a\"}")).executionArn();

            var execution = pollUntilDone(execArn);
            assertThat(execution.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(execution.error()).isEqualTo("States.Runtime");
            assertThat(execution.cause()).isEqualTo(
                    "An error occurred while executing the state 'Fast' (entered at the event id #4). "
                    + "The JSONPath '$.nope' specified for the field 'm.$' could not be found in the input '{\"x\":\"a\"}'");

            var events = sfn.getExecutionHistory(b -> b.executionArn(execArn)).events();
            assertThat(events.stream().map(HistoryEvent::type).toList()).containsExactly(
                    HistoryEventType.EXECUTION_STARTED,
                    HistoryEventType.PARALLEL_STATE_ENTERED,
                    HistoryEventType.PARALLEL_STATE_STARTED,
                    HistoryEventType.PASS_STATE_ENTERED,
                    HistoryEventType.EXECUTION_FAILED);
            assertThat(events.get(3).stateEnteredEventDetails().name()).isEqualTo("Fast");
            assertThat(events.get(4).executionFailedEventDetails().cause()).isEqualTo(execution.cause());
        } finally {
            try { sfn.deleteStateMachine(b -> b.stateMachineArn(smArn)); } catch (Exception ignored) {}
        }
    }

    private DescribeExecutionResponse pollUntilDone(String execArn) throws InterruptedException {
        for (var i = 0; i < 120; i++) {
            var resp = sfn.describeExecution(b -> b.executionArn(execArn));
            if (resp.status() != ExecutionStatus.RUNNING) {
                return resp;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Execution did not complete within 60s: " + execArn);
    }
}
