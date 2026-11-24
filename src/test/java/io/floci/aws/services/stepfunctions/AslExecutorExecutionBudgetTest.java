package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.lambda.LambdaExecutorService;
import io.floci.aws.services.lambda.LambdaFunctionStore;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.services.stepfunctions.model.Execution;
import io.floci.aws.services.stepfunctions.model.HistoryEvent;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * The state machine's {@code TimeoutSeconds} budget cutting each kind of wait, and a heartbeat
 * expiry caught under either of its names, on a clock the test moves forward so no case waits the
 * budget out. One end-to-end case per clock stays in {@code StepFunctionsTimeoutSecondsIntegrationTest}.
 *
 * <p>The executor reads {@code System.nanoTime()} plus an offset the test controls, which keeps it
 * on the time base the task-token store stamps heartbeats with. A Wait or a Retry backoff moves the
 * offset through the sleeper instead of sleeping. A Parallel branch's Wait moves it the same way
 * and then stays parked, since a branch is never cut mid-state; the thread joining the branches
 * reads the clock only once the branch is parked, so the join finds the budget already spent.
 */
@Timeout(10)
class AslExecutorExecutionBudgetTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String ACTIVITY_ARN = "arn:aws:states:%s:%s:activity:worker".formatted(REGION, ACCOUNT);
    private static final String FUNCTION_NAME = "flaky";
    private static final String FUNCTION_ARN =
            "arn:aws:lambda:%s:%s:function:%s".formatted(REGION, ACCOUNT, FUNCTION_NAME);
    private static final long PAST_ONE_SECOND = TimeUnit.SECONDS.toNanos(2);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong offset = new AtomicLong();
    private final List<Long> sleeps = new CopyOnWriteArrayList<>();
    private final CountDownLatch branchParked = new CountDownLatch(1);
    private final CountDownLatch releaseBranches = new CountDownLatch(1);
    private final RecordingHistory history = new RecordingHistory();
    private final LambdaExecutorService lambdaExecutor = mock(LambdaExecutorService.class);
    private final LambdaFunctionStore functionStore = mock(LambdaFunctionStore.class);
    private StepFunctionsService tokens;
    private AslExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getRegion()).thenReturn(REGION);
        when(resolver.getAccountId()).thenReturn(ACCOUNT);
        tokens = spy(new StepFunctionsService(mock(StorageFactory.class), resolver, mock(AslExecutor.class),
                mapper, mock(SfnMockLoader.class), mock(StepFunctionsEventPublisher.class)));
        Instance<StepFunctionsService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(tokens);
        executor = AslExecutorFixture.builder()
                .objectMapper(mapper)
                .lambdaExecutor(lambdaExecutor)
                .functionStore(functionStore)
                .sfnService(instance)
                .customResourceLiveness(null)
                .maxWaitSeconds(30)
                .nanoTime(this::now)
                .sleeper(this::sleep)
                .build();
    }

    @AfterEach
    void releaseParkedBranches() {
        releaseBranches.countDown();
        executor.stop();
    }

    @Test
    void stateMachineTimeoutSecondsCutsTheWaitForATaskToken() {
        // Measured against us-east-1: the execution ends TIMED_OUT the instant its budget runs out,
        // and writes nothing about the task it cut. No ActivityTimedOut, no TaskFailed.
        advanceOnceTheTokenIsRegistered();

        Execution execution = run("""
                {"StartAt":"Await","TimeoutSeconds":1,"States":{
                  "Await":{"Type":"Task","Resource":"%s","TimeoutSeconds":60,"End":true}}}
                """.formatted(ACTIVITY_ARN));

        assertTimedOut(execution);
        assertEquals(List.of("TaskStateEntered", "ActivityScheduled", "ActivityStarted", "ExecutionTimedOut"), types());
    }

    @Test
    void stateMachineTimeoutSecondsCutsTheWaitForAParallelBranch() {
        // The branch's Wait is entered and never exited: the budget cuts the join, so the Parallel
        // gets no Exited event and the state after it is never entered.
        Execution execution = run("""
                {"StartAt":"Fork","TimeoutSeconds":1,"States":{
                  "Fork":{"Type":"Parallel","Next":"Unreached","Branches":[{"StartAt":"Linger",
                    "States":{"Linger":{"Type":"Wait","Seconds":3,"End":true}}}]},
                  "Unreached":{"Type":"Pass","End":true}}}
                """);

        assertTimedOut(execution);
        assertEquals(List.of("ParallelStateEntered", "ParallelStateStarted", "WaitStateEntered",
                "ExecutionTimedOut"), types());
    }

    @Test
    void stateMachineTimeoutSecondsCutsARetryBackoffLongerThanTheRemainingBudget() {
        // Every attempt fails at once, so the only time the state spends is the 10-second backoff.
        // AWS ends the execution where the budget runs out, mid-backoff, rather than failing the
        // state on its own error once the backoff is over.
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName(FUNCTION_NAME);
        function.setFunctionArn(FUNCTION_ARN);
        when(functionStore.get(REGION, FUNCTION_NAME)).thenReturn(Optional.of(function));
        when(lambdaExecutor.invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(new InvokeResult(200, "Handled",
                        "{\"errorType\":\"Boom\"}".getBytes(StandardCharsets.UTF_8), null, "req-1"));

        Execution execution = run("""
                {"StartAt":"Flaky","TimeoutSeconds":1,"States":{
                  "Flaky":{"Type":"Task","Resource":"%s","End":true,
                    "Retry":[{"ErrorEquals":["States.ALL"],"IntervalSeconds":10,"MaxAttempts":1}]}}}
                """.formatted(FUNCTION_ARN));

        assertTimedOut(execution);
        assertEquals("ExecutionTimedOut", history.getLast().getType());
        assertEquals(1, types().stream().filter("LambdaFunctionFailed"::equals).count(), types().toString());
        // The test clock takes any sleep, so the sleeps themselves show the backoff stopped at the budget.
        long budget = TimeUnit.SECONDS.toNanos(1);
        assertTrue(!sleeps.isEmpty() && sleeps.stream().allMatch(nanos -> nanos <= budget),
                "the backoff must sleep no longer than the one-second budget: " + sleeps);
    }

    @ParameterizedTest
    @ValueSource(strings = {"States.HeartbeatTimeout", "States.Timeout"})
    void aCatchMatchesAHeartbeatExpiryUnderEitherErrorName(String errorEquals) throws Exception {
        advanceOnceTheTokenIsRegistered();

        Execution execution = run("""
                {"StartAt":"Await","States":{
                  "Await":{"Type":"Task","Resource":"%s","TimeoutSeconds":10,"HeartbeatSeconds":1,
                    "Catch":[{"ErrorEquals":["%s"],"Next":"Recovered"}],"End":true},
                  "Recovered":{"Type":"Pass","End":true}}}
                """.formatted(ACTIVITY_ARN, errorEquals));

        assertEquals("SUCCEEDED", execution.getStatus(), "Catch on " + errorEquals + " left " + types());
        assertEquals("States.Timeout", mapper.readTree(execution.getOutput()).path("Error").asText(),
                "Catch on " + errorEquals + " reported the wrong error");
        assertTrue(types().contains("ActivityTimedOut"), types().toString());
    }

    /**
     * Moves the clock past a one-second bound as soon as the task's token is registered, which is
     * when both of its clocks start, the same instant a worker that never answers would leave it.
     */
    private void advanceOnceTheTokenIsRegistered() {
        doAnswer(invocation -> {
            Object future = invocation.callRealMethod();
            offset.addAndGet(PAST_ONE_SECOND);
            return future;
        }).when(tokens).registerPendingToken(anyString(), anyString(), anyString());
    }

    private Execution run(String definition) {
        return AslExecutorFixture.runSync(executor, REGION, "budget", definition, "{}", history);
    }

    private long now() {
        if (Thread.currentThread() == history.joiningThread) {
            awaitUninterruptibly(branchParked);
        }
        return System.nanoTime() + offset.get();
    }

    private void sleep(long nanos) throws InterruptedException {
        sleeps.add(nanos);
        offset.addAndGet(nanos);
        if (Thread.currentThread() != history.runningThread) {
            branchParked.countDown();
            releaseBranches.await();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "the branch never reached its Wait");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void assertTimedOut(Execution execution) {
        assertEquals("TIMED_OUT", execution.getStatus(), types().toString());
        assertNull(execution.getError(), "TIMED_OUT carries no error");
        assertNull(execution.getCause(), "TIMED_OUT carries no cause");
    }

    private List<String> types() {
        return history.stream().map(HistoryEvent::getType).toList();
    }

    /**
     * The history, which also names the thread that runs the execution (the one that records its
     * first event; ExecutionStarted is the service's to record) and, once a Parallel starts, the
     * thread that joins its branches.
     */
    private static final class RecordingHistory extends CopyOnWriteArrayList<HistoryEvent> {

        private volatile Thread runningThread;
        private volatile Thread joiningThread;

        @Override
        public boolean add(HistoryEvent event) {
            if (runningThread == null) {
                runningThread = Thread.currentThread();
            }
            if ("ParallelStateStarted".equals(event.getType())) {
                joiningThread = Thread.currentThread();
            }
            return super.add(event);
        }
    }
}
