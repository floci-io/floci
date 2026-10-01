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
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * cuts the Parallel instead, the child is aborted as well and no such event is recorded. The
 * failure is seen the moment it happens, whichever branch is listed first: the branches are
 * joined as they complete, and the outputs still come back in declaration order. An
 * iteration of an inline Map that fails cuts the other iterations the same way, and the job one of
 * them waits on is aborted too (measured: within about 0.15 s, with the same cause, whether or not
 * a Catch on the Map keeps the execution going).
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
    /**
     * An inline Map whose "fail" item runs the failing branch, whose "wait" item runs the long Wait,
     * whose "done" item is a Pass, and whose other items run the nested Task.
     */
    private static final String MAP_WITH_A_FAILING_ITERATION = """
            {"Type":"Map","End":true,"ItemProcessor":{"ProcessorConfig":{"Mode":"INLINE"},
              "StartAt":"Route","States":{
              "Route":{"Type":"Choice","Default":"Nest",
                "Choices":[{"Variable":"$.kind","StringEquals":"fail","Next":"Pause"},
                           {"Variable":"$.kind","StringEquals":"wait","Next":"Long"},
                           {"Variable":"$.kind","StringEquals":"done","Next":"Done"}]},
              "Pause":{"Type":"Wait","Seconds":1,"Next":"Boom"},
              "Boom":{"Type":"Fail","Error":"Boom","Cause":"sibling failed"},
              "Long":{"Type":"Wait","Seconds":20,"End":true},
              "Done":{"Type":"Pass","End":true},
              "Nest":{"Type":"Task","Resource":"arn:aws:states:::states:startExecution.sync:2",
                "Parameters":{"StateMachineArn":"%s"},"End":true}}}}""".formatted(CHILD_SM_ARN);
    /**
     * The same Map with the "fail" item's Fail swapped for a Choice nothing matches, so that item
     * ends in States.Runtime, and only after its Pause, where the sleeper holds it.
     */
    private static final String MAP_WITH_A_RUNTIME_FAILING_ITERATION = MAP_WITH_A_FAILING_ITERATION.replace(
            "\"Boom\":{\"Type\":\"Fail\",\"Error\":\"Boom\",\"Cause\":\"sibling failed\"}",
            "\"Boom\":{\"Type\":\"Choice\",\"Choices\":[{\"Variable\":\"$.kind\",\"StringEquals\":\"never\",\"Next\":\"Done\"}]}");
    private static final String SLOW_RESULT_BRANCH = """
            {"StartAt":"Slow","States":{"Slow":{"Type":"Wait","Seconds":1,"Next":"Out"},
              "Out":{"Type":"Pass","Result":"slow","End":true}}}""";
    private static final String FAST_RESULT_BRANCH = """
            {"StartAt":"Out","States":{"Out":{"Type":"Pass","Result":"fast","End":true}}}""";

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

    /** Fails against a declaration-order join after the default 300 s Task timeout; the limit ends it sooner. */
    @Test
    @Timeout(20)
    void aFailureInALaterBranchCutsAnEarlierBranchStillWaitingOnItsJob() {
        Execution execution = run(parallel(NESTED_SYNC_BRANCH, FAILING_BRANCH), 1, 0);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Boom", execution.getError());
        long failure = eventOfType("FailStateEntered").getId();
        HistoryEvent aborted = eventOfType("TaskStateAborted");
        assertEquals(failure, aborted.getPreviousEventId().longValue());
        assertEquals(failure, eventOfType("ParallelStateFailed").getPreviousEventId().longValue());
        assertTrue(aborted.getId() < eventOfType("ParallelStateFailed").getId());
        verify(sfnService, timeout(5_000)).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
    }

    /** The earlier branch's Wait really sleeps 20 s; a declaration-order join sees the failure only then. */
    @Test
    @Timeout(10)
    void aFailureInALaterBranchIsSeenWhileAnEarlierWaitStillRuns() {
        Execution execution = run(parallel(LONG_WAIT_BRANCH, FAILING_BRANCH), 1, 0);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Boom", execution.getError());
        long failure = eventOfType("FailStateEntered").getId();
        assertEquals(failure, eventOfType("WaitStateAborted").getPreviousEventId().longValue());
        // The failing branch's own Pause exits; the cut Long never does.
        List<String> exitedWaits = eventsOfType("WaitStateExited").stream()
                .map(event -> String.valueOf(event.getDetails().get("name"))).toList();
        assertEquals(List.of("Pause"), exitedWaits, types().toString());
    }

    @Test
    void outputsStayInDeclarationOrderWhenAnEarlierBranchFinishesLast() throws Exception {
        AslExecutor.Sleeper sleeper = nanos -> TimeUnit.MILLISECONDS.sleep(200);
        Execution execution = run(parallel(SLOW_RESULT_BRANCH, FAST_RESULT_BRANCH), sleeper, 0);

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertEquals(objectMapper.readTree("[\"slow\",\"fast\"]"), objectMapper.readTree(execution.getOutput()));
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
    void aMapIterationFailureAbortsTheChildAnotherIterationWaitsOn() {
        Execution execution = run(MAP_WITH_A_FAILING_ITERATION, sleeper(1), 0,
                "[{\"kind\":\"nest\"},{\"kind\":\"fail\"}]");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("Boom", execution.getError());
        assertEquals("sibling failed", execution.getCause());
        assertEquals(1, eventsOfType("MapIterationFailed").size(), types().toString());
        verify(sfnService, timeout(5_000)).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
    }

    /**
     * Measured on AWS: a failing iteration records, for each iteration it cuts, MapIterationAborted
     * and then the *StateAborted of the state it was in, all chained to the failing iteration's last
     * event and ahead of MapIterationFailed and MapStateFailed, which point at that same event.
     */
    @Test
    void aMapIterationCutInsideItsTaskRecordsMapIterationAbortedAndTaskStateAborted() {
        Execution execution = run(MAP_WITH_A_FAILING_ITERATION, sleeper(1), 0,
                "[{\"kind\":\"nest\"},{\"kind\":\"fail\"}]");

        assertEquals("FAILED", execution.getStatus());
        HistoryEvent failure = eventOfType("FailStateEntered");
        List<HistoryEvent> tail = history.subList(history.indexOf(failure) + 1, history.size());
        assertEquals(List.of("MapIterationAborted", "TaskStateAborted", "MapIterationFailed", "MapStateFailed",
                "ExecutionFailed"), tail.stream().map(HistoryEvent::getType).toList(), types().toString());
        for (HistoryEvent event : tail.subList(0, 4)) {
            assertEquals(failure.getId(), event.getPreviousEventId().longValue(), event.getType());
        }
        assertEquals(Map.of("name", "P", "index", 0), tail.get(0).getDetails());
        assertNull(tail.get(1).getDetails());
        assertEquals(Map.of("name", "P", "index", 1), tail.get(2).getDetails());
        assertEquals(tail.get(3).getId(), tail.get(4).getPreviousEventId().longValue());
        verify(sfnService, timeout(5_000)).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
    }

    @Test
    void aMapIterationCutInsideItsWaitRecordsMapIterationAbortedAndWaitStateAborted() {
        Execution execution = run(MAP_WITH_A_FAILING_ITERATION, sleeper(1), 0,
                "[{\"kind\":\"fail\"},{\"kind\":\"wait\"}]");

        assertEquals("FAILED", execution.getStatus());
        long failure = eventOfType("FailStateEntered").getId();
        HistoryEvent aborted = eventOfType("MapIterationAborted");
        assertEquals(Map.of("name", "P", "index", 1), aborted.getDetails());
        assertEquals(failure, aborted.getPreviousEventId().longValue());
        HistoryEvent waitAborted = eventOfType("WaitStateAborted");
        assertEquals(failure, waitAborted.getPreviousEventId().longValue());
        assertTrue(aborted.getId() < waitAborted.getId());
        assertTrue(waitAborted.getId() < eventOfType("MapIterationFailed").getId());
        assertEquals(List.of("Pause"), eventsOfType("WaitStateExited").stream()
                .map(event -> String.valueOf(event.getDetails().get("name"))).toList(), types().toString());
    }

    /**
     * Each event follows the rule of the one it accompanies. A States.Runtime failure in an
     * iteration records no MapIterationFailed; when what ends the Map is the tolerance it exceeded,
     * MapStateFailed is recorded, and so are the iterations that failure cut, right before it.
     */
    @Test
    void aRuntimeFailureThatExceedsTheToleranceRecordsTheOnesItCutButNotItself() {
        String map = MAP_WITH_A_RUNTIME_FAILING_ITERATION.replace("\"Type\":\"Map\",",
                "\"Type\":\"Map\",\"ToleratedFailureCount\":0,");
        Execution execution = run(map, sleeper(1), 0, "[{\"kind\":\"wait\"},{\"kind\":\"fail\"}]");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.ExceedToleratedFailureThreshold", execution.getError());
        assertTrue(types().stream().noneMatch("MapIterationFailed"::equals), types().toString());
        int stateFailed = types().indexOf("MapStateFailed");
        assertEquals(List.of("MapIterationAborted", "WaitStateAborted"), types().subList(stateFailed - 2, stateFailed),
                types().toString());
    }

    /**
     * A States.Runtime failure that ends the Map records no MapStateFailed, and nothing for the
     * iterations it cut either.
     */
    @Test
    void aRuntimeFailureThatEndsTheMapRecordsNothingForTheOnesItCut() {
        Execution execution = run(MAP_WITH_A_RUNTIME_FAILING_ITERATION, sleeper(1), 0,
                "[{\"kind\":\"wait\"},{\"kind\":\"fail\"}]");

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.Runtime", execution.getError());
        assertTrue(types().stream().noneMatch(type -> type.contains("Aborted") || type.endsWith("Failed")
                && !type.equals("ExecutionFailed")), types().toString());
        // The other iteration was cut inside Long: only the failing one's Pause ever exits.
        assertEquals(List.of("Pause"), eventsOfType("WaitStateExited").stream()
                .map(event -> String.valueOf(event.getDetails().get("name"))).toList(), types().toString());
    }

    /**
     * An ItemSelector that fails does so before its iteration starts, so there is no
     * MapIterationFailed to record, but the Map fails with the expression's error and the
     * iterations it cut are recorded before MapStateFailed. Which state the other iteration had
     * reached by then is a race the test does not fix, so it asserts the invariant over many runs:
     * an iteration is recorded cut exactly when its start is recorded and its end is not.
     */
    @Test
    void anItemSelectorFailureRecordsTheIterationsItCutOnlyOnceTheyStarted() {
        String map = """
                {"Type":"Map","End":true,"QueryLanguage":"JSONata","Items":"{% $states.input %}",
                  "ItemSelector":{"kind":"{% $states.context.Map.Item.Value.kind %}",
                                  "v":"{% $states.context.Map.Item.Value.v %}"},
                  "ItemProcessor":{"ProcessorConfig":{"Mode":"INLINE"},"StartAt":"Long","States":{
                    "Long":{"Type":"Wait","Seconds":20,"End":true}}}}""";
        for (int round = 0; round < 40; round++) {
            Execution execution = run(map, nanos -> TimeUnit.NANOSECONDS.sleep(nanos), 0,
                    "[{\"kind\":\"wait\",\"v\":1},{\"kind\":\"selector\"}]");

            assertEquals("FAILED", execution.getStatus(), types().toString());
            assertEquals("States.QueryEvaluationError", execution.getError(), types().toString());
            assertTrue(types().contains("MapStateFailed"), types().toString());
            assertTrue(types().stream().noneMatch("MapIterationFailed"::equals), types().toString());
            boolean started = types().contains("MapIterationStarted");
            boolean ended = types().contains("MapIterationSucceeded");
            assertEquals(started && !ended, types().contains("MapIterationAborted"), "round " + round + ": " + types());
            boolean inWait = types().contains("WaitStateEntered") && !types().contains("WaitStateExited");
            assertEquals(started && !ended && inWait, types().contains("WaitStateAborted"), "round " + round + ": " + types());
            if (types().contains("MapIterationAborted")) {
                assertTrue(types().indexOf("MapIterationAborted") < types().indexOf("MapStateFailed"), types().toString());
            }
        }
    }

    @Test
    void aMapIterationThatFinishedBeforeTheFailureRecordsNoAbortedEvent() {
        AslExecutor.Sleeper sleeper = nanos -> TimeUnit.MILLISECONDS.sleep(200);
        Execution execution = run(MAP_WITH_A_FAILING_ITERATION, sleeper, 0,
                "[{\"kind\":\"done\"},{\"kind\":\"fail\"}]");

        assertEquals("FAILED", execution.getStatus());
        assertEquals(1, eventsOfType("MapIterationSucceeded").size(), types().toString());
        assertTrue(types().stream().noneMatch(type -> type.contains("Aborted")), types().toString());
        assertEquals(eventOfType("FailStateEntered").getId(),
                eventOfType("MapIterationFailed").getPreviousEventId().longValue());
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
        return run(parallelState, sleeper, executionTimeoutSeconds, "{}");
    }

    private Execution run(String state, AslExecutor.Sleeper sleeper, int executionTimeoutSeconds,
                          String input) {
        String budget = executionTimeoutSeconds > 0
                ? "\"TimeoutSeconds\":%d,".formatted(executionTimeoutSeconds)
                : "";
        String definition = "{" + budget + "\"StartAt\":\"P\",\"States\":{\"P\":" + state + "}}";

        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("parent");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:parent".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("parent-run");
        execution.setExecutionArn(PARENT_ARN);
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput(input);

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
