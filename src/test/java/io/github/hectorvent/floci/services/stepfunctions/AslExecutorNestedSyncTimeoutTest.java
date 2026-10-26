package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.ExecutionStatusSubscription;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
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
    private AslExecutor.NestedWaiter nestedWaiter;

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

    @ParameterizedTest
    @ValueSource(strings = {".sync", ".sync:2"})
    void syncCompletionDoesNotDependOnPolling(String mode) throws Exception {
        AtomicLong nanos = new AtomicLong();
        when(sfnService.describeExecution(CHILD_ARN)).thenAnswer(invocation -> {
            nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(350));
            Execution done = child("SUCCEEDED");
            done.setOutput("{\"answer\":42}");
            return done;
        });

        Execution execution = run(newExecutor(pause -> {
            throw new AssertionError("Nested execution completion must not use a polling sleep");
        }, nanos::get), mode, 0, false, 0, true);

        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(350), nanos.get());
        assertTrue(objectMapper.readTree(execution.getOutput()).path("Output").toString().contains("42"));
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
        Execution parent = parent("RUNNING");
        when(sfnService.describeExecution(PARENT_ARN)).thenReturn(parent);
        AtomicInteger childPolls = new AtomicInteger();
        when(sfnService.describeExecution(CHILD_ARN)).thenAnswer(invocation -> {
            childPolls.incrementAndGet();
            parent.setStatus("ABORTED");
            return child("RUNNING");
        });

        run(newExecutor(nanos -> { throw new AssertionError("Parent cancellation must notify the wait"); }),
                ".sync", 0, false);

        assertEquals(1, childPolls.get(), "the parent cancellation wakes the wait without rereading its child");
        verify(sfnService).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
        List<String> types = history.stream().map(HistoryEvent::getType).toList();
        assertTrue(types.stream().noneMatch(type -> type.equals("TaskTimedOut")
                || type.equals("TaskFailed") || type.startsWith("Execution")
                && !type.equals("ExecutionStarted")), "an aborted execution gets no terminal event from the worker: " + types);
    }

    @ParameterizedTest
    @ValueSource(strings = {".sync", ".sync:2"})
    void childCompletionWakesAParkedParentWithoutPolling(String mode) throws Exception {
        Execution live = child("RUNNING");
        when(sfnService.startExecution(any(), any(), any(), any())).thenReturn(live);
        CountDownLatch firstRead = new CountDownLatch(1);
        AtomicReference<Thread> waitingThread = new AtomicReference<>();
        when(sfnService.describeExecution(CHILD_ARN)).thenAnswer(invocation -> {
            waitingThread.set(Thread.currentThread());
            firstRead.countDown();
            return live;
        });
        AslExecutor executor = newExecutor(nanos -> {
            throw new AssertionError("Nested completion must not use the polling sleeper");
        });
        CompletableFuture<Execution> result = CompletableFuture.supplyAsync(
                () -> run(executor, mode, 60, false));
        try {
            assertTrue(firstRead.await(2, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(2))
                    .until(() -> waitingThread.get().getState() == Thread.State.TIMED_WAITING);
            verify(sfnService, times(1)).describeExecution(CHILD_ARN);
            synchronized (live) {
                live.setOutput("{\"answer\":42}");
                live.setStopDate(2.0);
                live.setStatus("SUCCEEDED");
            }

            Execution execution = result.get(2, TimeUnit.SECONDS);
            assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
            assertTrue(objectMapper.readTree(execution.getOutput()).path("Output").toString().contains("42"));
            verify(sfnService, times(2)).describeExecution(CHILD_ARN);
            verify(sfnService, never()).stopExecution(any(), any(), any());
        } finally {
            if (!result.isDone() && waitingThread.get() != null) {
                waitingThread.get().interrupt();
            }
        }
    }

    @Test
    void clearCancelsAnActiveNestedWaitAndAbortsItsChild() {
        AslExecutor executor = newExecutor(nanos -> {
            throw new AssertionError("Reset must notify the wait");
        });
        when(sfnService.describeExecution(CHILD_ARN)).thenAnswer(invocation -> {
            executor.clearNestedExecutionWaits();
            return child("RUNNING");
        });

        Execution execution = run(executor, ".sync", 0, false);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.TaskFailed", execution.getError());
        verify(sfnService).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
    }

    @Test
    void clearDuringChildStartCannotLeaveAnUnregisteredWait() {
        AslExecutor executor = newExecutor(nanos -> {
            throw new AssertionError("Reset during submission must not start polling");
        });
        when(sfnService.startExecution(any(), any(), any(), any())).thenAnswer(invocation -> {
            executor.clearNestedExecutionWaits();
            return child("RUNNING");
        });

        Execution execution = run(executor, ".sync:2", 0, false);

        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.TaskFailed", execution.getError());
        verify(sfnService).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {".sync", ".sync:2"})
    void clearDuringInputEncodingCancelsTheNestedWait(String mode) throws Exception {
        ObjectMapper mapper = spy(objectMapper);
        AslExecutor executor = newExecutor(nanos -> {
            throw new AssertionError("Reset during input encoding must not start polling");
        }, System::nanoTime, mapper);
        AtomicInteger resets = new AtomicInteger();
        JsonNode childInput = objectMapper.readTree("{\"resetDuringEncoding\":true}");
        doAnswer(invocation -> {
            resets.incrementAndGet();
            executor.clearNestedExecutionWaits();
            return invocation.callRealMethod();
        }).when(mapper).writeValueAsString(eq(childInput));
        when(sfnService.describeExecution(CHILD_ARN)).thenReturn(child("SUCCEEDED"));

        Execution execution = run(executor, mode, 0, false, 0, false,
                ", \"Input\": {\"resetDuringEncoding\":true}");

        assertEquals(1, resets.get(), "reset occurs while encoding the child's input");
        assertEquals("FAILED", execution.getStatus());
        assertEquals("States.TaskFailed", execution.getError());
        verify(sfnService).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {".sync", ".sync:2"})
    @Timeout(5)
    void injectedClockReachesNestedTaskDeadline(String mode) {
        AtomicLong nanos = new AtomicLong();
        AtomicInteger waits = new AtomicInteger();
        nestedWaiter = (subscription, observed, remaining) -> {
            assertEquals(TimeUnit.SECONDS.toNanos(60), remaining);
            waits.incrementAndGet();
            nanos.addAndGet(remaining);
        };
        when(sfnService.describeExecution(CHILD_ARN)).thenReturn(child("RUNNING"));

        Execution execution = run(newExecutor(pause -> {
            throw new AssertionError("Nested waits must not poll through the sleeper");
        }, nanos::get), mode, 60, true);

        assertEquals(1, waits.get());
        assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
        assertTrue(history.stream().anyMatch(event -> "TaskTimedOut".equals(event.getType())));
        verify(sfnService).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {".sync", ".sync:2"})
    @Timeout(5)
    void injectedClockReachesParentDeadlineBeforeNestedTaskDeadline(String mode) {
        AtomicLong nanos = new AtomicLong();
        AtomicInteger waits = new AtomicInteger();
        nestedWaiter = (subscription, observed, remaining) -> {
            assertEquals(TimeUnit.SECONDS.toNanos(30), remaining);
            waits.incrementAndGet();
            nanos.addAndGet(remaining);
        };
        when(sfnService.describeExecution(CHILD_ARN)).thenReturn(child("RUNNING"));

        Execution execution = run(newExecutor(pause -> {
            throw new AssertionError("Nested waits must not poll through the sleeper");
        }, nanos::get), mode, 60, false, 30);

        assertEquals(1, waits.get());
        assertEquals("TIMED_OUT", execution.getStatus());
        assertTrue(history.stream().noneMatch(event -> "TaskTimedOut".equals(event.getType())));
        verify(sfnService).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {".sync", ".sync:2"})
    void clockAdvanceOnAParkedWaitIsCheckedAfterWake(String mode) throws Exception {
        AtomicLong nanos = new AtomicLong();
        AtomicReference<ExecutionStatusSubscription> parked = new AtomicReference<>();
        AtomicReference<Thread> waiter = new AtomicReference<>();
        nestedWaiter = (subscription, observed, remaining) -> {
            parked.set(subscription);
            waiter.set(Thread.currentThread());
            subscription.awaitChange(observed, remaining);
        };
        when(sfnService.describeExecution(CHILD_ARN)).thenReturn(child("RUNNING"));
        AslExecutor executor = newExecutor(pause -> {
            throw new AssertionError("Nested waits must not poll through the sleeper");
        }, nanos::get);
        CompletableFuture<Execution> result = CompletableFuture.supplyAsync(
                () -> run(executor, mode, 60, true));
        try {
            await().atMost(Duration.ofSeconds(2)).until(() -> waiter.get() != null
                    && waiter.get().getState() == Thread.State.TIMED_WAITING);
            ExecutionStatusSubscription subscription = parked.get();
            synchronized (subscription) {
                nanos.set(TimeUnit.SECONDS.toNanos(60));
                subscription.notifyAll();
            }
            Execution execution = result.get(2, TimeUnit.SECONDS);
            assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
            assertTrue(history.stream().anyMatch(event -> "TaskTimedOut".equals(event.getType())));
            verify(sfnService).stopExecution(eq(CHILD_ARN), eq(ABORT_CAUSE), isNull());
        } finally {
            executor.clearNestedExecutionWaits();
            result.get(2, TimeUnit.SECONDS);
        }
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

    private AslExecutor newExecutor(AslExecutor.Sleeper sleeper) {
        return newExecutor(sleeper, System::nanoTime);
    }

    private AslExecutor newExecutor(AslExecutor.Sleeper sleeper, LongSupplier nanoTime) {
        return newExecutor(sleeper, nanoTime, objectMapper);
    }

    @SuppressWarnings("unchecked")
    private AslExecutor newExecutor(AslExecutor.Sleeper sleeper, LongSupplier nanoTime, ObjectMapper mapper) {
        Instance<StepFunctionsService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(sfnService);
        return AslExecutorFixture.builder()
                .objectMapper(mapper)
                .sfnService(instance)
                .customResourceLiveness(null)
                .sleeper(sleeper)
                .nanoTime(nanoTime)
                .nestedWaiter(nestedWaiter)
                .build();
    }

    private Execution run(AslExecutor executor, String mode, int timeoutSeconds, boolean catchTimeout) {
        return run(executor, mode, timeoutSeconds, catchTimeout, 0);
    }

    private Execution run(AslExecutor executor, String mode, int timeoutSeconds, boolean catchTimeout,
                           int executionTimeoutSeconds) {
        return run(executor, mode, timeoutSeconds, catchTimeout, executionTimeoutSeconds, false);
    }

    private Execution run(AslExecutor executor, String mode, int timeoutSeconds, boolean catchTimeout,
                          int executionTimeoutSeconds, boolean asynchronous) {
        return run(executor, mode, timeoutSeconds, catchTimeout, executionTimeoutSeconds, asynchronous, "");
    }

    private Execution run(AslExecutor executor, String mode, int timeoutSeconds, boolean catchTimeout,
                          int executionTimeoutSeconds, boolean asynchronous, String additionalParameters) {
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
                      "Parameters": { "StateMachineArn": "%s"%s },
                      "End": true
                    },
                    "Recover": { "Type": "Pass", "End": true }
                  }
                }
                """.formatted(budget, mode, timeout, catcher, CHILD_SM_ARN, additionalParameters);

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

        history = new CopyOnWriteArrayList<>();
        if (asynchronous) {
            executor.executeAsync(stateMachine, execution, history, (updated, events) -> { });
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> history.stream().anyMatch(event -> event.getType().startsWith("Execution")
                            && !event.getType().equals("ExecutionStarted")));
        } else {
            executor.executeSync(stateMachine, execution, history, (updated, events) -> { });
        }
        return execution;
    }
}
