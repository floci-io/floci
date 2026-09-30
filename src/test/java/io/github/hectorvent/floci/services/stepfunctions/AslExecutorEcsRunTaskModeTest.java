package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.container.HostVolumePolicy;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
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
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the ecs:runTask integration's mode-specific failure semantics and the threading of
 * NetworkConfiguration through to {@link EcsService#runTask}:
 * <ul>
 *   <li>request-response never fails the state on a placement failure — it returns the
 *       {@code {Tasks,Failures}} envelope, even with no launched tasks;</li>
 *   <li>{@code .sync} (and {@code .waitForTaskToken}) fail the state on a placement failure,
 *       using the AWS error name {@code AmazonECS.Unknown};</li>
 *   <li>an awsvpc {@code NetworkConfiguration} in the parameters is parsed and passed to runTask
 *       rather than dropped;</li>
 *   <li>a {@code .sync} wait is bounded by the Task's {@code TimeoutSeconds} and by the execution's
 *       budget, and by nothing else: no poll count of its own cuts a long-running task short.</li>
 * </ul>
 */
class AslExecutorEcsRunTaskModeTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private EcsService ecsService;
    private StepFunctionsService sfnService;
    private AslExecutor executor;
    private List<HistoryEvent> history;

    @BeforeEach
    void setUp() {
        ecsService = mock(EcsService.class);
        // The .sync wait reads the execution back to notice a StopExecution; RUNNING unless a test says otherwise.
        sfnService = mock(StepFunctionsService.class);
        when(sfnService.describeExecution(any())).thenAnswer(invocation -> parent(invocation.getArgument(0), "RUNNING"));
        executor = newExecutor(TimeUnit.NANOSECONDS::sleep);
    }

    @SuppressWarnings("unchecked")
    private AslExecutor newExecutor(AslExecutor.Sleeper sleeper) {
        // A real handler so parseNetworkConfiguration / parseContainerOverrides actually run.
        EcsJsonHandler ecsJsonHandler = new EcsJsonHandler(ecsService, objectMapper,
                new HostVolumePolicy(mock(EmulatorConfig.class, RETURNS_DEEP_STUBS)));
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
                ecsService,
                ecsJsonHandler,
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
                30);
    }

    @Test
    void requestResponseDoesNotFailWhenNoTasksLaunched() throws Exception {
        // No tasks could be placed (AWS: 200 with a non-empty Failures list). Request-response
        // must return the envelope and let execution continue, not fail the state.
        when(ecsService.runTask(any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());

        Execution execution = run("arn:aws:states:::ecs:runTask",
                "{\"TaskDefinition\":\"my-task-def\"}");

        assertEquals("SUCCEEDED", execution.getStatus());
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertTrue(output.path("Tasks").isArray(), "Tasks should be an array");
        assertEquals(0, output.path("Tasks").size());
        assertTrue(output.path("Failures").isArray(), "Failures should be an array");
    }

    @Test
    void syncFailsWithAmazonEcsUnknownWhenNoTasksLaunched() throws Exception {
        when(ecsService.runTask(any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());

        Execution execution = run("arn:aws:states:::ecs:runTask.sync",
                "{\"TaskDefinition\":\"my-task-def\"}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("AmazonECS.Unknown", execution.getError());
    }

    @Test
    void networkConfigurationIsThreadedThroughToRunTask() throws Exception {
        when(ecsService.runTask(any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());

        run("arn:aws:states:::ecs:runTask", """
                {
                  "TaskDefinition": "my-task-def",
                  "LaunchType": "FARGATE",
                  "NetworkConfiguration": {
                    "AwsvpcConfiguration": {
                      "Subnets": ["subnet-123"],
                      "SecurityGroups": ["sg-abc"],
                      "AssignPublicIp": "ENABLED"
                    }
                  }
                }
                """);

        ArgumentCaptor<NetworkConfiguration> captor = ArgumentCaptor.forClass(NetworkConfiguration.class);
        verify(ecsService).runTask(
                any(), any(), anyInt(), any(), any(), any(), any(), captor.capture(), any());

        NetworkConfiguration passed = captor.getValue();
        assertNotNull(passed, "NetworkConfiguration should be parsed and passed, not dropped");
        assertNotNull(passed.getAwsvpcConfiguration());
        assertEquals(List.of("subnet-123"), passed.getAwsvpcConfiguration().getSubnets());
        assertEquals(List.of("sg-abc"), passed.getAwsvpcConfiguration().getSecurityGroups());
        assertEquals("ENABLED", passed.getAwsvpcConfiguration().getAssignPublicIp());
    }

    @Test
    void syncFailsWithStatesTimeoutWhenTheTaskOutlivesTimeoutSeconds() throws Exception {
        launchOneTask();
        when(ecsService.describeTasks(any(), any(), any())).thenReturn(List.of(task("RUNNING")));

        Execution execution = runDefinition("""
                {
                  "StartAt": "RunTask",
                  "States": {
                    "RunTask": { "Type": "Task", "Resource": "arn:aws:states:::ecs:runTask.sync",
                                 "TimeoutSeconds": 1, "End": true }
                  }
                }
                """, "{\"TaskDefinition\":\"my-task-def\"}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Timeout", execution.getError());
        assertNull(execution.getCause(), "a timeout carries no cause");
        HistoryEvent timedOut = history.stream()
                .filter(event -> "TaskTimedOut".equals(event.getType()))
                .findFirst().orElseThrow(() -> new AssertionError("no TaskTimedOut event in " + history));
        assertEquals("ecs", timedOut.getDetails().get("resourceType"));
        assertEquals("States.Timeout", timedOut.getDetails().get("error"));
        // AWS stops the task it was waiting on, with this exact stoppedReason (measured).
        verify(ecsService).stopTask(any(), eq(task("RUNNING").getTaskArn()),
                eq("The Task state in AWS Step Functions execution [" + execution.getExecutionArn()
                        + "] which was managing this resource was aborted"), eq(REGION));
    }

    @Test
    void syncIsNotCappedByAPollCountWhenNoTimeoutSecondsIsDeclared() throws Exception {
        // The former loop gave up after 600 polls. A task that stops on the 700th read must still
        // succeed: without a TimeoutSeconds only the emulator's default bound applies, and the
        // no-op sleeper keeps that from being real time.
        executor = newExecutor(nanos -> { });
        launchOneTask();
        AtomicInteger polls = new AtomicInteger();
        when(ecsService.describeTasks(any(), any(), any()))
                .thenAnswer(invocation -> List.of(task(polls.incrementAndGet() < 700 ? "RUNNING" : "STOPPED")));

        Execution execution = run("arn:aws:states:::ecs:runTask.sync",
                "{\"TaskDefinition\":\"my-task-def\"}");

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertEquals(700, polls.get());
        assertEquals("STOPPED", objectMapper.readTree(execution.getOutput()).path("LastStatus").asText());
    }

    @Test
    void executionBudgetCutsASyncWaitBeforeTheTasksOwnTimeoutAndStopsTheTask() throws Exception {
        launchOneTask();
        when(ecsService.describeTasks(any(), any(), any())).thenReturn(List.of(task("RUNNING")));

        Execution execution = runDefinition("""
                {
                  "StartAt": "RunTask",
                  "TimeoutSeconds": 1,
                  "States": {
                    "RunTask": { "Type": "Task", "Resource": "arn:aws:states:::ecs:runTask.sync",
                                 "TimeoutSeconds": 60, "End": true }
                  }
                }
                """, "{\"TaskDefinition\":\"my-task-def\"}");

        assertEquals("TIMED_OUT", execution.getStatus());
        assertNull(execution.getError());
        // AWS stops the task on the execution's budget too (measured: UserInitiated, same reason).
        verify(ecsService).stopTask(any(), eq(task("RUNNING").getTaskArn()),
                eq("The Task state in AWS Step Functions execution [" + execution.getExecutionArn()
                        + "] which was managing this resource was aborted"), eq(REGION));
        assertTrue(history.stream().noneMatch(event -> "TaskTimedOut".equals(event.getType())),
                "the execution's budget writes nothing about the state it cut");
        assertTrue(history.stream().anyMatch(event -> "ExecutionTimedOut".equals(event.getType())));
    }

    @Test
    void failureInASiblingParallelBranchStopsTheTask() throws Exception {
        // The sibling's Pause is held until this branch is polling, so the cut lands mid-wait.
        CountDownLatch polling = new CountDownLatch(1);
        executor = newExecutor(nanos -> {
            if (nanos == TimeUnit.SECONDS.toNanos(1)) {
                polling.await();
                return;
            }
            polling.countDown();
            TimeUnit.NANOSECONDS.sleep(nanos);
        });
        launchOneTask();
        when(ecsService.describeTasks(any(), any(), any())).thenReturn(List.of(task("RUNNING")));

        Execution execution = runDefinition("""
                {
                  "StartAt": "P",
                  "States": {
                    "P": { "Type": "Parallel", "End": true, "Branches": [
                      { "StartAt": "Pause", "States": {
                          "Pause": { "Type": "Wait", "Seconds": 1, "Next": "Boom" },
                          "Boom": { "Type": "Fail", "Error": "Boom" } } },
                      { "StartAt": "RunTask", "States": {
                          "RunTask": { "Type": "Task", "Resource": "arn:aws:states:::ecs:runTask.sync",
                                       "Parameters": { "TaskDefinition": "my-task-def" }, "End": true } } }
                    ] }
                  }
                }
                """, "{}");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Boom", execution.getError());
        // The cut branch stops its task on its own thread, after the Parallel has already failed.
        verify(ecsService, timeout(5_000)).stopTask(any(), eq(task("RUNNING").getTaskArn()),
                eq("The Task state in AWS Step Functions execution [" + execution.getExecutionArn()
                        + "] which was managing this resource was aborted"), eq(REGION));
    }

    @Test
    void stopExecutionEndsTheSyncWaitAndStopsTheTask() throws Exception {
        executor = newExecutor(nanos -> { });
        launchOneTask();
        AtomicInteger parentReads = new AtomicInteger();
        when(sfnService.describeExecution(any()))
                .thenAnswer(invocation -> parent(invocation.getArgument(0), parentReads.incrementAndGet() < 3 ? "RUNNING" : "ABORTED"));
        AtomicInteger polls = new AtomicInteger();
        when(ecsService.describeTasks(any(), any(), any())).thenAnswer(invocation -> {
            polls.incrementAndGet();
            return List.of(task("RUNNING"));
        });

        Execution execution = run("arn:aws:states:::ecs:runTask.sync",
                "{\"TaskDefinition\":\"my-task-def\"}");

        assertEquals(2, polls.get(), "polling stops as soon as the execution reads ABORTED");
        verify(ecsService).stopTask(any(), eq(task("RUNNING").getTaskArn()),
                eq("The Task state in AWS Step Functions execution [" + execution.getExecutionArn()
                        + "] which was managing this resource was aborted"), eq(REGION));
        List<String> types = history.stream().map(HistoryEvent::getType).toList();
        assertTrue(types.stream().noneMatch(type -> type.equals("TaskTimedOut") || type.equals("TaskFailed")
                || type.equals("ExecutionFailed") || type.equals("ExecutionSucceeded")),
                "an aborted execution gets no terminal event from the worker: " + types);
    }

    private static Execution parent(String executionArn, String status) {
        Execution parent = new Execution();
        parent.setExecutionArn(executionArn);
        parent.setStatus(status);
        return parent;
    }

    private void launchOneTask() {
        when(ecsService.runTask(any(), any(), anyInt(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(task("PENDING")));
    }

    private static EcsTask task(String lastStatus) {
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:%s:%s:task/default/0123456789abcdef".formatted(REGION, ACCOUNT));
        task.setClusterArn("arn:aws:ecs:%s:%s:cluster/default".formatted(REGION, ACCOUNT));
        task.setLastStatus(lastStatus);
        Container container = new Container();
        container.setName("runner");
        if ("STOPPED".equals(lastStatus)) {
            container.setExitCode(0);
        }
        task.setContainers(List.of(container));
        return task;
    }

    private Execution run(String resource, String input) {
        return runDefinition("""
                {
                  "StartAt": "RunTask",
                  "States": {
                    "RunTask": { "Type": "Task", "Resource": "%s", "End": true }
                  }
                }
                """.formatted(resource), input);
    }

    private Execution runDefinition(String definition, String input) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("ecs-runtask-test");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:ecs-runtask-test".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("ecs-runtask-execution");
        execution.setExecutionArn("arn:aws:states:%s:%s:execution:ecs-runtask-test:ecs-runtask-execution".formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

        history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> { });
        return execution;
    }
}
