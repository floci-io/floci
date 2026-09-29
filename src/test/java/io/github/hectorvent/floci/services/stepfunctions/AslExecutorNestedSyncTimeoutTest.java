package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.scheduler.SchedulerController;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.sns.SnsJsonHandler;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A {@code states:startExecution.sync} wait is bounded by the Task's {@code TimeoutSeconds}, the
 * way AWS bounds it, and no longer by a fixed poll count of its own. The timeout aborts the child,
 * a {@code Catch} on {@code States.Timeout} takes it, and the parent's own budget and a
 * StopExecution on the parent each end the wait and abort the child alike.
 */
class AslExecutorNestedSyncTimeoutTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";
    private static final String CHILD_SM_ARN =
            "arn:aws:states:%s:%s:stateMachine:child".formatted(REGION, ACCOUNT);
    private static final String CHILD_ARN =
            "arn:aws:states:%s:%s:execution:child:run-1".formatted(REGION, ACCOUNT);
    private static final String PARENT_ARN =
            "arn:aws:states:%s:%s:execution:parent:parent-run".formatted(REGION, ACCOUNT);
    private static final String ABORT_CAUSE =
            "The Task state in AWS Step Functions execution [" + PARENT_ARN
                    + "] which was managing this resource was aborted";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StepFunctionsService sfnService;
    private List<HistoryEvent> history;

    @BeforeEach
    void setUp() {
        sfnService = mock(StepFunctionsService.class);
        when(sfnService.startExecution(any(), any(), any(), any())).thenReturn(child("RUNNING"));
        when(sfnService.describeExecution(PARENT_ARN)).thenReturn(parent("RUNNING"));
    }

    @Test
    void syncFailsWithStatesTimeoutWhenTheChildOutlivesTimeoutSeconds() {
        when(sfnService.describeExecution(CHILD_ARN)).thenReturn(child("RUNNING"));

        Execution execution = run(newExecutor(TimeUnit.NANOSECONDS::sleep), ".sync", 1, false);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Timeout", execution.getError());
        assertNull(execution.getCause());
        HistoryEvent timedOut = history.stream()
                .filter(event -> "TaskTimedOut".equals(event.getType()))
                .findFirst().orElseThrow(() -> new AssertionError("no TaskTimedOut event in " + history));
        assertEquals("states", timedOut.getDetails().get("resourceType"));
        // AWS aborts the child it was waiting on: no error, this exact cause (measured).
        verify(sfnService).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
    }

    @Test
    void sync2TimeoutIsCaughtUnderStatesTimeout() throws Exception {
        when(sfnService.describeExecution(CHILD_ARN)).thenReturn(child("RUNNING"));

        Execution execution = run(newExecutor(TimeUnit.NANOSECONDS::sleep), ".sync:2", 1, true);

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertEquals("States.Timeout", output.path("Error").asText());
        assertTrue(history.stream().anyMatch(event -> "TaskTimedOut".equals(event.getType())));
        verify(sfnService).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
    }

    @Test
    void syncIsNotCappedByAPollCountWhenNoTimeoutSecondsIsDeclared() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        when(sfnService.describeExecution(CHILD_ARN)).thenAnswer(invocation -> {
            if (polls.incrementAndGet() < 700) {
                return child("RUNNING");
            }
            Execution done = child("SUCCEEDED");
            done.setOutput("{\"answer\":42}");
            return done;
        });

        Execution execution = run(newExecutor(nanos -> { }), ".sync", 0, false);

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertEquals(700, polls.get());
        assertTrue(objectMapper.readTree(execution.getOutput()).path("output").asText().contains("42"));
        verify(sfnService, never()).stopExecution(any(), any(), any());
    }

    @Test
    void executionBudgetEndsTheParentTimedOutAndAbortsTheChild() {
        when(sfnService.describeExecution(CHILD_ARN)).thenReturn(child("RUNNING"));

        Execution execution = run(newExecutor(TimeUnit.NANOSECONDS::sleep), ".sync:2", 60, false, 1);

        assertEquals("TIMED_OUT", execution.getStatus());
        assertTrue(history.stream().noneMatch(event -> "TaskTimedOut".equals(event.getType())));
        // Measured on AWS: the child is aborted on the parent's budget too, with the same cause.
        verify(sfnService).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
    }

    @Test
    void stopExecutionOnTheParentEndsTheWaitAndAbortsTheChild() {
        AtomicInteger parentReads = new AtomicInteger();
        when(sfnService.describeExecution(PARENT_ARN))
                .thenAnswer(invocation -> parent(parentReads.incrementAndGet() < 3 ? "RUNNING" : "ABORTED"));
        AtomicInteger childPolls = new AtomicInteger();
        when(sfnService.describeExecution(CHILD_ARN)).thenAnswer(invocation -> {
            childPolls.incrementAndGet();
            return child("RUNNING");
        });

        run(newExecutor(nanos -> { }), ".sync", 0, false);

        assertEquals(2, childPolls.get(), "polling stops as soon as the parent reads ABORTED");
        verify(sfnService).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
        List<String> types = history.stream().map(HistoryEvent::getType).toList();
        assertTrue(types.stream().noneMatch(type -> type.equals("TaskTimedOut")
                || type.equals("TaskFailed") || type.startsWith("Execution")
                && !type.equals("ExecutionStarted")), "an aborted execution gets no terminal event from the worker: " + types);
    }

    private static Execution child(String status) {
        Execution current = new Execution();
        current.setExecutionArn(CHILD_ARN);
        current.setStateMachineArn(CHILD_SM_ARN);
        current.setName("run-1");
        current.setStatus(status);
        current.setStartDate(1.0);
        return current;
    }

    private static Execution parent(String status) {
        Execution current = new Execution();
        current.setExecutionArn(PARENT_ARN);
        current.setStatus(status);
        return current;
    }

    @SuppressWarnings("unchecked")
    private AslExecutor newExecutor(AslExecutor.Sleeper sleeper) {
        Instance<StepFunctionsService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(sfnService);
        return new AslExecutor(
                mock(LambdaExecutorService.class),
                mock(LambdaFunctionStore.class),
                mock(DynamoDbFacade.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class), mock(SnsJsonHandler.class),
                mock(CloudFormationQueryHandler.class),
                mock(Ec2Service.class),
                mock(S3Service.class),
                mock(EcsService.class),
                mock(EcsJsonHandler.class),
                mock(EventBridgeHandler.class),
                mock(SchedulerService.class),
                mock(SchedulerController.class),
                objectMapper,
                new JsonataEvaluator(objectMapper),
                instance,
                mock(EmulatorConfig.class),
                null,
                null,
                Clock.systemUTC(),
                sleeper,
                null);
    }

    private Execution run(AslExecutor executor, String mode, int timeoutSeconds, boolean catchTimeout) {
        return run(executor, mode, timeoutSeconds, catchTimeout, 0);
    }

    private Execution run(AslExecutor executor, String mode, int timeoutSeconds, boolean catchTimeout,
                          int executionTimeoutSeconds) {
        String timeout = timeoutSeconds > 0 ? "\"TimeoutSeconds\": %d,".formatted(timeoutSeconds) : "";
        String catcher = catchTimeout
                ? "\"Catch\": [{\"ErrorEquals\": [\"States.Timeout\"], \"Next\": \"Recover\"}],"
                : "";
        String budget = executionTimeoutSeconds > 0
                ? "\"TimeoutSeconds\": %d,".formatted(executionTimeoutSeconds)
                : "";
        String definition = """
                {
                  %s
                  "StartAt": "Child",
                  "States": {
                    "Child": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::states:startExecution%s",
                      %s
                      %s
                      "Parameters": { "StateMachineArn": "%s" },
                      "End": true
                    },
                    "Recover": { "Type": "Pass", "End": true }
                  }
                }
                """.formatted(budget, mode, timeout, catcher, CHILD_SM_ARN);

        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("parent");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:parent".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("parent-run");
        execution.setExecutionArn(PARENT_ARN);
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{}");

        history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> { });
        return execution;
    }
}
