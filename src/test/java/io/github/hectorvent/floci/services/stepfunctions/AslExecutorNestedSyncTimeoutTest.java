package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@code states:startExecution.sync} wait is bounded by the Task's {@code TimeoutSeconds}, the
 * way AWS bounds it, and no longer by a fixed poll count of its own.
 */
class AslExecutorNestedSyncTimeoutTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";
    private static final String CHILD_ARN =
            "arn:aws:states:%s:%s:execution:child:run-1".formatted(REGION, ACCOUNT);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StepFunctionsService sfnService;
    private List<HistoryEvent> history;

    @BeforeEach
    void setUp() {
        sfnService = mock(StepFunctionsService.class);
        Execution child = new Execution();
        child.setExecutionArn(CHILD_ARN);
        child.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:child".formatted(REGION, ACCOUNT));
        child.setName("run-1");
        child.setStatus("RUNNING");
        child.setStartDate(1.0);
        when(sfnService.startExecution(any(), any(), any(), any())).thenReturn(child);
    }

    @Test
    void syncFailsWithStatesTimeoutWhenTheChildOutlivesTimeoutSeconds() {
        when(sfnService.describeExecution(CHILD_ARN)).thenReturn(running());

        Execution execution = run(newExecutor(TimeUnit.NANOSECONDS::sleep), 1);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Timeout", execution.getError());
        assertNull(execution.getCause());
        HistoryEvent timedOut = history.stream()
                .filter(event -> "TaskTimedOut".equals(event.getType()))
                .findFirst().orElseThrow(() -> new AssertionError("no TaskTimedOut event in " + history));
        assertEquals("states", timedOut.getDetails().get("resourceType"));
    }

    @Test
    void syncIsNotCappedByAPollCountWhenNoTimeoutSecondsIsDeclared() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        when(sfnService.describeExecution(CHILD_ARN)).thenAnswer(invocation -> {
            if (polls.incrementAndGet() < 700) {
                return running();
            }
            Execution done = running();
            done.setStatus("SUCCEEDED");
            done.setOutput("{\"answer\":42}");
            return done;
        });

        Execution execution = run(newExecutor(nanos -> { }), 0);

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertEquals(700, polls.get());
        assertTrue(objectMapper.readTree(execution.getOutput()).path("output").asText().contains("42"));
    }

    private Execution running() {
        Execution current = new Execution();
        current.setExecutionArn(CHILD_ARN);
        current.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:child".formatted(REGION, ACCOUNT));
        current.setName("run-1");
        current.setStatus("RUNNING");
        current.setStartDate(1.0);
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
                mock(io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler.class),
                mock(io.github.hectorvent.floci.services.ec2.Ec2Service.class),
                mock(io.github.hectorvent.floci.services.s3.S3Service.class),
                mock(EcsService.class),
                mock(EcsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerService.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerController.class),
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

    private Execution run(AslExecutor executor, int timeoutSeconds) {
        String timeout = timeoutSeconds > 0 ? "\"TimeoutSeconds\": %d,".formatted(timeoutSeconds) : "";
        String definition = """
                {
                  "StartAt": "Child",
                  "States": {
                    "Child": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::states:startExecution.sync",
                      %s
                      "Parameters": { "StateMachineArn": "arn:aws:states:%s:%s:stateMachine:child" },
                      "End": true
                    }
                  }
                }
                """.formatted(timeout, REGION, ACCOUNT);

        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("parent");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:parent".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("parent-run");
        execution.setExecutionArn("arn:aws:states:%s:%s:execution:parent:parent-run".formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{}");

        history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> { });
        return execution;
    }
}
