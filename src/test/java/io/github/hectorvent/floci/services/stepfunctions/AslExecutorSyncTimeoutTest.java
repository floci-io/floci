package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An Express execution, synchronous or not, is one AWS ends with States.Timeout once it has run for
 * its maximum execution time. Once it has timed out it runs no further state, and the first
 * outcome published, the timeout or one the execution reached in time, is the one that stands.
 *
 * <p>Most cases run on a test clock that Wait states and slow integrations move forward, so they
 * take no real time. The two that need the synchronous caller's own wait to expire run on the real
 * clock with a 100 ms limit.
 */
class AslExecutorSyncTimeoutTest {

    private static final Duration MAX_EXECUTION_TIME = Duration.ofSeconds(1);
    private static final Duration SHORT_REAL_MAX_EXECUTION_TIME = Duration.ofMillis(100);
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:slow";
    private static final Set<String> TERMINAL_EVENTS =
            Set.of("ExecutionSucceeded", "ExecutionFailed", "ExecutionTimedOut", "ExecutionAborted");
    private static final String WAIT_THEN_PASS = """
            {"StartAt":"Pause","States":{
              "Pause":{"Type":"Wait","Seconds":2,"Next":"After"},
              "After":{"Type":"Pass","End":true}}}
            """;

    private final LambdaExecutorService lambdaExecutor = mock(LambdaExecutorService.class);
    private final LambdaFunctionStore functionStore = mock(LambdaFunctionStore.class);
    private final AtomicLong testNanos = new AtomicLong();
    private final AslExecutor executor = AslExecutorFixture.builder()
            .lambdaExecutor(lambdaExecutor)
            .functionStore(functionStore)
            .maxWaitSeconds(30)
            .expressMaxExecutionTime(MAX_EXECUTION_TIME)
            .nanoTime(testNanos::get)
            .sleeper(testNanos::addAndGet)
            .build();
    private final AslExecutor realTimeExecutor = AslExecutorFixture.builder()
            .lambdaExecutor(lambdaExecutor)
            .functionStore(functionStore)
            .expressMaxExecutionTime(SHORT_REAL_MAX_EXECUTION_TIME)
            .build();
    private final List<HistoryEvent> history = new CopyOnWriteArrayList<>();

    @Test
    void executionPastItsMaximumTimeRunsNoFurtherStateAndStaysTimedOut() {
        StateMachine stateMachine = stateMachine(WAIT_THEN_PASS);
        Execution execution = execution(stateMachine);

        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });

        assertEndedTimedOut(execution);
        assertFalse(history.stream().anyMatch(event -> "PassStateEntered".equals(event.getType())),
                "the state after the timeout ran");
    }

    @Test
    void lastStateFinishingPastTheMaximumTimeStillEndsTimedOut() {
        lambdaAnswers(invocation -> {
            advance(Duration.ofSeconds(2));
            return new InvokeResult(200, null, bytes("{}"), null, "late-success");
        });
        StateMachine stateMachine = stateMachine(lambdaTask());
        Execution execution = execution(stateMachine);

        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });

        assertEndedTimedOut(execution);
    }

    @Test
    void lastStateFailingPastTheMaximumTimeStillEndsTimedOut() {
        lambdaAnswers(invocation -> {
            advance(Duration.ofSeconds(2));
            return new InvokeResult(200, "Unhandled",
                    bytes("{\"errorType\":\"Boom\",\"errorMessage\":\"late\"}"), null, "late-failure");
        });
        StateMachine stateMachine = stateMachine(lambdaTask());
        Execution execution = execution(stateMachine);

        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });

        assertEndedTimedOut(execution);
    }

    @Test
    void lastStateThrowingPastTheMaximumTimeStillEndsTimedOut() {
        lambdaAnswers(invocation -> {
            advance(Duration.ofSeconds(2));
            throw new IllegalStateException("runtime broke after the deadline");
        });
        StateMachine stateMachine = stateMachine(lambdaTask());
        Execution execution = execution(stateMachine);

        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });

        assertEndedTimedOut(execution);
    }

    @Test
    void expressExecutionStartedAsynchronouslyEndsAtItsMaximumTime() {
        StateMachine stateMachine = stateMachine(WAIT_THEN_PASS);
        stateMachine.setType("EXPRESS");
        Execution execution = execution(stateMachine);

        executor.executeAsync(stateMachine, execution, history, (updated, events) -> {
        });

        awaitTerminalEvent();
        assertEndedTimedOut(execution);
        assertFalse(history.stream().anyMatch(event -> "PassStateEntered".equals(event.getType())),
                "the state after the timeout ran");
    }

    @Test
    void standardExecutionStartedAsynchronouslyHasNoSuchLimit() {
        StateMachine stateMachine = stateMachine(WAIT_THEN_PASS);
        stateMachine.setType("STANDARD");
        Execution execution = execution(stateMachine);

        executor.executeAsync(stateMachine, execution, history, (updated, events) -> {
        });

        awaitTerminalEvent();
        assertEquals("SUCCEEDED", execution.getStatus());
    }

    @Test
    void lateOutcomeAfterTheCallerWasAnsweredKeepsTheTimeout() {
        CountDownLatch release = new CountDownLatch(1);
        lambdaAnswers(invocation -> {
            awaitQuietly(release);
            return new InvokeResult(200, null, bytes("{}"), null, "late-success");
        });
        StateMachine stateMachine = stateMachine(lambdaTask());
        Execution execution = execution(stateMachine);

        realTimeExecutor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        assertEquals("TIMED_OUT", execution.getStatus());
        release.countDown();

        awaitTerminalEvent();
        assertEndedTimedOut(execution);
    }

    @Test
    void executionThatSucceededInTimeIsNotReportedTimedOut() {
        StateMachine stateMachine = stateMachine("""
                {"StartAt":"Done","States":{"Done":{"Type":"Pass","End":true}}}
                """);
        Execution execution = execution(stateMachine);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);

        // The worker publishes SUCCEEDED at once, then is held in its update until past the limit.
        realTimeExecutor.executeSync(stateMachine, execution, history, (updated, events) -> {
            if (first.getAndSet(false)) {
                awaitQuietly(release);
            }
        });
        String reported = execution.getStatus();
        release.countDown();

        assertEquals("SUCCEEDED", reported);
        assertEquals("ExecutionSucceeded", history.getLast().getType());
    }

    private void advance(Duration duration) {
        testNanos.addAndGet(duration.toNanos());
    }

    private void lambdaAnswers(Answer<InvokeResult> answer) {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName("slow");
        function.setFunctionArn(FUNCTION_ARN);
        when(functionStore.get("us-east-1", "slow")).thenReturn(Optional.of(function));
        when(lambdaExecutor.invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(answer);
    }

    private void awaitTerminalEvent() {
        await().atMost(Duration.ofSeconds(10))
                .until(() -> history.stream().anyMatch(event -> TERMINAL_EVENTS.contains(event.getType())));
    }

    private void assertEndedTimedOut(Execution execution) {
        assertEquals("TIMED_OUT", execution.getStatus());
        assertEquals("ExecutionTimedOut", history.getLast().getType());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String lambdaTask() {
        return """
                {"StartAt":"Slow","States":{"Slow":{"Type":"Task","Resource":"%s","End":true}}}
                """.formatted(FUNCTION_ARN);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static StateMachine stateMachine(String definition) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("sync-timeout-test");
        stateMachine.setStateMachineArn(
                "arn:aws:states:us-east-1:000000000000:stateMachine:sync-timeout-test");
        stateMachine.setDefinition(definition);
        return stateMachine;
    }

    private static Execution execution(StateMachine stateMachine) {
        Execution execution = new Execution();
        execution.setName("sync-timeout-exec");
        execution.setExecutionArn(
                "arn:aws:states:us-east-1:000000000000:execution:sync-timeout-test:sync-timeout-exec");
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{}");
        execution.setStatus("RUNNING");
        return execution;
    }
}
