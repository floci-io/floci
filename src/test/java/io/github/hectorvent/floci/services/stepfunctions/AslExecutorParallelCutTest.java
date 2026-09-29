package io.github.hectorvent.floci.services.stepfunctions;

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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A Parallel branch that is cut while its {@code .sync} Task waits on a child execution aborts that
 * child, the way AWS does, and the history records the state each cut branch was in. Measured on
 * AWS: a failure in one branch aborts the child of every other branch within about 0.1 s with the
 * cause below, and records one {@code TaskStateAborted} (or {@code WaitStateAborted}) per cut
 * branch, each chained to the failing branch's last event and recorded before
 * {@code ParallelStateFailed}, which is chained to that same event. When the execution's budget
 * cuts the Parallel instead, the child is aborted as well and no such event is recorded.
 *
 * <p>The failing branch pauses in a Wait that the sleeper holds until every other branch is
 * parked on its own wait, so the cut always lands while the others are mid-state.
 */
class AslExecutorParallelCutTest {

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
    private static final long PAUSE_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final String FAILING_BRANCH = """
            {"StartAt":"Pause","States":{
              "Pause":{"Type":"Wait","Seconds":1,"Next":"Boom"},
              "Boom":{"Type":"Fail","Error":"Boom","Cause":"sibling failed"}}}""";
    private static final String NESTED_SYNC_BRANCH = """
            {"StartAt":"Nest","States":{"Nest":{"Type":"Task",
              "Resource":"arn:aws:states:::states:startExecution.sync:2",
              "Parameters":{"StateMachineArn":"%s"},"End":true}}}""".formatted(CHILD_SM_ARN);
    private static final String PASS_BRANCH = """
            {"StartAt":"Done","States":{"Done":{"Type":"Pass","End":true}}}""";
    private static final String LONG_WAIT_BRANCH = """
            {"StartAt":"Long","States":{"Long":{"Type":"Wait","Seconds":20,"End":true}}}""";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StepFunctionsService sfnService;
    private List<HistoryEvent> history;

    @BeforeEach
    void setUp() {
        sfnService = mock(StepFunctionsService.class);
        when(sfnService.startExecution(any(), any(), any(), any())).thenReturn(child());
        when(sfnService.describeExecution(CHILD_ARN)).thenReturn(child());
        Execution parent = new Execution();
        parent.setExecutionArn(PARENT_ARN);
        parent.setStatus("RUNNING");
        when(sfnService.describeExecution(PARENT_ARN)).thenReturn(parent);
    }

    @Test
    void siblingFailureAbortsTheChildAndRecordsTaskStateAbortedBesideTheFailure() {
        Execution execution = run(parallel(FAILING_BRANCH, NESTED_SYNC_BRANCH), 1, 0);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Boom", execution.getError());
        assertEquals("sibling failed", execution.getCause());
        long failure = eventOfType("FailStateEntered").getId();
        List<HistoryEvent> aborted = eventsOfType("TaskStateAborted");
        assertEquals(1, aborted.size(), "one TaskStateAborted for the one cut branch: " + types());
        assertEquals(failure, aborted.get(0).getPreviousEventId().longValue());
        assertNull(aborted.get(0).getDetails());
        HistoryEvent parallelFailed = eventOfType("ParallelStateFailed");
        assertEquals(failure, parallelFailed.getPreviousEventId().longValue());
        assertTrue(aborted.get(0).getId() < parallelFailed.getId());
        verify(sfnService, timeout(5_000)).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
    }

    @Test
    void everyCutBranchRecordsItsOwnTaskStateAbortedAgainstTheSameEvent() {
        Execution execution = run(parallel(FAILING_BRANCH, NESTED_SYNC_BRANCH, NESTED_SYNC_BRANCH), 2, 0);

        assertEquals("FAILED", execution.getStatus());
        long failure = eventOfType("FailStateEntered").getId();
        List<HistoryEvent> aborted = eventsOfType("TaskStateAborted");
        assertEquals(2, aborted.size(), types().toString());
        assertTrue(aborted.stream().allMatch(event -> event.getPreviousEventId() == failure), types().toString());
        assertEquals(failure, eventOfType("ParallelStateFailed").getPreviousEventId().longValue());
        verify(sfnService, timeout(5_000).times(2)).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
    }

    @Test
    void aWaitCutByASiblingFailureIsRecordedAsWaitStateAborted() {
        Execution execution = run(parallel(FAILING_BRANCH, LONG_WAIT_BRANCH), 1, 0);

        assertEquals("FAILED", execution.getStatus());
        long failure = eventOfType("FailStateEntered").getId();
        HistoryEvent aborted = eventOfType("WaitStateAborted");
        assertEquals(failure, aborted.getPreviousEventId().longValue());
        assertTrue(eventsOfType("TaskStateAborted").isEmpty(), types().toString());
        assertTrue(aborted.getId() < eventOfType("ParallelStateFailed").getId());
    }

    @Test
    void aBranchThatFinishedBeforeTheFailureRecordsNoAbortedState() {
        // The failing branch's Pause really sleeps, so the Pass branch has long finished by then.
        AslExecutor.Sleeper sleeper = nanos -> TimeUnit.MILLISECONDS.sleep(200);
        Execution execution = run(parallel(FAILING_BRANCH, PASS_BRANCH), sleeper, 0);

        assertEquals("FAILED", execution.getStatus());
        assertTrue(types().contains("PassStateExited"), types().toString());
        assertTrue(types().stream().noneMatch(type -> type.endsWith("StateAborted")), types().toString());
    }

    @Test
    void executionBudgetCuttingTheParallelAbortsTheChildAndRecordsNoAbortedState() {
        Execution execution = run(parallel(NESTED_SYNC_BRANCH), 0, 1);

        assertEquals("TIMED_OUT", execution.getStatus());
        assertTrue(types().stream().noneMatch(type -> type.endsWith("StateAborted")), types().toString());
        verify(sfnService, timeout(5_000)).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
    }

    private static Execution child() {
        Execution current = new Execution();
        current.setExecutionArn(CHILD_ARN);
        current.setStateMachineArn(CHILD_SM_ARN);
        current.setName("run-1");
        current.setStatus("RUNNING");
        current.setStartDate(1.0);
        return current;
    }

    private static String parallel(String... branches) {
        return "{\"Type\":\"Parallel\",\"End\":true,\"Branches\":[" + String.join(",", branches) + "]}";
    }

    /**
     * The failing branch's one-second Pause is held until {@code waitingBranches} other branch
     * threads have started a sleep of their own (a {@code .sync} poll, or a longer Wait); every
     * other sleep is real, so the cut interrupts it.
     */
    private static AslExecutor.Sleeper sleeper(int waitingBranches) {
        CountDownLatch othersWaiting = new CountDownLatch(waitingBranches);
        Set<Thread> seen = ConcurrentHashMap.newKeySet();
        return nanos -> {
            if (nanos == PAUSE_NANOS) {
                othersWaiting.await();
                return;
            }
            if (seen.add(Thread.currentThread())) {
                othersWaiting.countDown();
            }
            TimeUnit.NANOSECONDS.sleep(nanos);
        };
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
                30);
    }

    private Execution run(String parallelState, int waitingBranches, int executionTimeoutSeconds) {
        return run(parallelState, sleeper(waitingBranches), executionTimeoutSeconds);
    }

    private Execution run(String parallelState, AslExecutor.Sleeper sleeper, int executionTimeoutSeconds) {
        String budget = executionTimeoutSeconds > 0
                ? "\"TimeoutSeconds\":%d,".formatted(executionTimeoutSeconds)
                : "";
        String definition = "{" + budget + "\"StartAt\":\"P\",\"States\":{\"P\":" + parallelState + "}}";

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
        newExecutor(sleeper).executeSync(stateMachine, execution, history, (updated, events) -> { });
        return execution;
    }

    private List<String> types() {
        return history.stream().map(HistoryEvent::getType).toList();
    }

    private List<HistoryEvent> eventsOfType(String type) {
        return history.stream().filter(event -> type.equals(event.getType())).toList();
    }

    private HistoryEvent eventOfType(String type) {
        return history.stream().filter(event -> type.equals(event.getType())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " in " + types()));
    }
}
