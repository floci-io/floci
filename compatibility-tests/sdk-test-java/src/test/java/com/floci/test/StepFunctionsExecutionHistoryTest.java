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
