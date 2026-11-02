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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An Express execution, synchronous or not, is one AWS ends with States.Timeout once it has run for
 * its maximum execution time. Once it has timed out it runs no further state, and the first
 * outcome published, the timeout or one the execution reached in time, is the one that stands.
 *
 * <p>Every case runs on a test clock that Wait states and slow integrations move forward, so none
 * takes real time. What the synchronous caller does once its own wait runs out is exercised by
 * calling that step directly.
 */
class AslExecutorSyncTimeoutTest {

    private static final Duration MAX_EXECUTION_TIME = Duration.ofSeconds(1);
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
    void callerTimeoutLeavesAnExecutionThatAlreadyEndedAsItWas() {
        Execution execution = execution(stateMachine(WAIT_THEN_PASS));
        execution.setStatus("SUCCEEDED");
        AtomicInteger updates = new AtomicInteger();

        executor.answerTimedOutUnlessEnded(execution, history, (updated, events) -> updates.incrementAndGet());

        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals(0, updates.get());
    }

    @Test
    void callerTimeoutEndsAStillRunningExecutionTimedOut() {
        Execution execution = execution(stateMachine(WAIT_THEN_PASS));
        AtomicInteger updates = new AtomicInteger();

        executor.answerTimedOutUnlessEnded(execution, history, (updated, events) -> updates.incrementAndGet());

        assertEquals("TIMED_OUT", execution.getStatus());
        assertNotNull(execution.getStopDate());
        assertEquals(1, updates.get());
    }

    @Test
    void lateOutcomeAfterTheCallerWasAnsweredKeepsTheTimeout() {
        lambdaAnswers(invocation -> {
            advance(Duration.ofSeconds(2));
            return new InvokeResult(200, null, bytes("{}"), null, "late-success");
        });
        StateMachine stateMachine = stateMachine(lambdaTask());
        Execution execution = execution(stateMachine);

        // The caller's wait ran out first; the worker's late outcome arrives after it.
        executor.answerTimedOutUnlessEnded(execution, history, (updated, events) -> {
        });
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });

        assertEndedTimedOut(execution);
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
        awaitTerminalEvent();
        assertEquals("TIMED_OUT", execution.getStatus());
        assertEquals("ExecutionTimedOut", history.getLast().getType());
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
