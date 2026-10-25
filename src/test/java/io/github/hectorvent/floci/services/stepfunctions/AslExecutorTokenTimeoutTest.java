package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService.TaskResult;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@Timeout(10)
class AslExecutorTokenTimeoutTest {

    private static final String OWNER = "111111111111";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RegionResolver resolver = mock(RegionResolver.class);
    private final StepFunctionsService service = new StepFunctionsService(mock(StorageFactory.class),
            resolver, mock(AslExecutor.class), mapper, mock(SfnMockLoader.class),
            mock(StepFunctionsEventPublisher.class));

    @ParameterizedTest
    @ValueSource(strings = {"execution", "task", "heartbeat"})
    void timeoutClosesTokenBeforeCancellingFuture(String clock) throws Exception {
        when(resolver.getAccountId()).thenReturn(OWNER);
        CompletableFuture<JsonNode> future = service.registerPendingToken("expired", OWNER);
        long now = service.lastTaskHeartbeatNanos("expired") + TimeUnit.SECONDS.toNanos(2);
        List<String> callbackResults = new ArrayList<>();
        future.whenComplete((result, error) -> {
            callbackResults.add(Thread.holdsLock(service) ? "locked" : "unlocked");
            callbackResults.add(callbackResult(() -> service.sendTaskSuccess("expired", "{}")));
            callbackResults.add(callbackResult(() -> service.sendTaskFailure("expired", "cause", "error")));
            callbackResults.add(callbackResult(() -> service.sendTaskHeartbeat("expired")));
        });
        long executionDeadline = "execution".equals(clock) ? now : Long.MAX_VALUE;
        long taskDeadline = "task".equals(clock) ? now : Long.MAX_VALUE;
        JsonNode definition = mapper.readTree("heartbeat".equals(clock) ? "{\"HeartbeatSeconds\":1}" : "{}");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> executor(() -> now).awaitToken(future, definition, "expired",
                        executionDeadline, taskDeadline));

        if ("execution".equals(clock)) {
            assertTrue(failure instanceof AslExecutor.ExecutionTimedOutException);
        } else {
            assertTrue(failure instanceof AslExecutor.TaskTimedOutException);
        }
        assertTrue(future.isCancelled());
        assertEquals(List.of("unlocked", "TaskTimedOut", "TaskTimedOut", "TaskTimedOut"), callbackResults);
    }

    @Test
    void callbackClaimedBeforeTimeoutDecisionKeepsItsResult() throws Exception {
        when(resolver.getAccountId()).thenReturn(OWNER);
        CompletableFuture<JsonNode> future = service.registerPendingToken("winner", OWNER);
        AtomicInteger clockReads = new AtomicInteger();
        AslExecutor executor = executor(() -> {
            if (clockReads.incrementAndGet() == 2) {
                service.sendTaskSuccess("winner", "{\"accepted\":true}");
            }
            return 1;
        });

        JsonNode result = executor.awaitToken(future, mapper.readTree("{}"), "winner", Long.MAX_VALUE, 1);

        assertTrue(result.path("accepted").asBoolean());
        assertFalse(future.isCancelled());
    }

    @Test
    void heartbeatBeforeTimeoutDecisionExtendsTheWait() throws Exception {
        when(resolver.getAccountId()).thenReturn(OWNER);
        CompletableFuture<JsonNode> future = service.registerPendingToken("heartbeat", OWNER);
        long now = service.lastTaskHeartbeatNanos("heartbeat") + TimeUnit.SECONDS.toNanos(1);
        AtomicInteger clockReads = new AtomicInteger();
        AslExecutor executor = executor(() -> {
            int read = clockReads.incrementAndGet();
            if (read == 2) {
                service.sendTaskHeartbeat("heartbeat");
            } else if (read == 3) {
                service.sendTaskSuccess("heartbeat", "{\"accepted\":true}");
            }
            return now;
        });

        JsonNode result = executor.awaitToken(future, mapper.readTree("{\"HeartbeatSeconds\":1}"),
                "heartbeat", Long.MAX_VALUE, Long.MAX_VALUE);

        assertTrue(result.path("accepted").asBoolean());
        assertFalse(future.isCancelled());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void claimedResultDoesNotWaitForCallbackCompletion(boolean failure) throws Exception {
        when(resolver.getAccountId()).thenReturn(OWNER);
        StepFunctionsService callbackService = spy(service);
        CompletableFuture<JsonNode> future = callbackService.registerPendingToken("stalled", OWNER);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            CompletableFuture<JsonNode> result = (CompletableFuture<JsonNode>) invocation.callRealMethod();
            claimed.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return result;
        }).when(callbackService).claimPendingTask(eq("stalled"), any(TaskResult.class));
        List<String> completions = new ArrayList<>();
        future.whenComplete((value, exception) ->
                completions.add(Thread.holdsLock(callbackService) ? "locked" : "unlocked"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> callback = worker.submit(() -> failure
                    ? callbackService.sendTaskFailure("stalled", "cause", "CallbackError")
                    : callbackService.sendTaskSuccess("stalled", "{\"accepted\":true}"));
            assertTrue(claimed.await(5, TimeUnit.SECONDS));
            AslExecutor executor = executor(callbackService, () -> 1);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                if (failure) {
                    AslExecutor.FailStateException error = assertThrows(AslExecutor.FailStateException.class,
                            () -> executor.awaitToken(future, mapper.readTree("{}"), "stalled", 1, 1));
                    assertEquals("CallbackError", error.error);
                    assertEquals("cause", error.cause);
                } else {
                    assertTrue(executor.awaitToken(future, mapper.readTree("{}"), "stalled", 1, 1)
                            .path("accepted").asBoolean());
                }
            });
            assertFalse(future.isDone(), "the waiter must not depend on callback-thread completion");
            assertEquals("TaskTimedOut", callbackResult(() -> callbackService.sendTaskHeartbeat("stalled")));
            release.countDown();
            assertTrue(callback.get(5, TimeUnit.SECONDS));
            assertEquals(List.of("unlocked"), completions);
            assertFalse(future.isCancelled());
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void discardedTokenDoesNotLookLikeAnAcceptedCallback() throws Exception {
        CompletableFuture<JsonNode> future = service.registerPendingToken("discarded", OWNER);
        service.discardPendingToken("discarded");

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(AslExecutor.TaskTimedOutException.class,
                () -> executor(() -> 1).awaitToken(future, mapper.readTree("{}"), "discarded", 1, 1)));
        assertTrue(future.isCancelled());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clearPreservesClaimedOutcomeAndCompletesOutsideLock(boolean failure) throws Exception {
        when(resolver.getAccountId()).thenReturn(OWNER);
        CompletableFuture<JsonNode> future = service.registerPendingToken("cleared", OWNER);
        JsonNode output = mapper.readTree("{\"accepted\":true}");
        AslExecutor.FailStateException error = new AslExecutor.FailStateException("CallbackError", "cause");
        service.claimPendingTask("cleared", failure ? new TaskResult(null, error) : new TaskResult(output, null));
        List<String> completions = new ArrayList<>();
        future.whenComplete((value, exception) -> completions.add(Thread.holdsLock(service) ? "locked" : "unlocked"));

        service.clear();

        if (failure) {
            assertEquals(error, assertThrows(AslExecutor.FailStateException.class,
                    () -> executor(() -> 1).awaitToken(future, mapper.readTree("{}"), "cleared", 1, 1)));
        } else {
            assertEquals(output, executor(() -> 1).awaitToken(future, mapper.readTree("{}"), "cleared", 1, 1));
        }
        assertEquals(List.of("unlocked"), completions);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void registrationDuringResetIsRetiredUntilAllResetsFinish(boolean overlappingReset) throws Exception {
        when(resolver.getAccountId()).thenReturn(OWNER);
        CompletableFuture<JsonNode> first = service.registerPendingToken("first", OWNER);
        CountDownLatch completing = new CountDownLatch(1);
        CompletableFuture<Void> release = new CompletableFuture<>();
        List<Boolean> completionLocks = new ArrayList<>();
        first.whenComplete((value, error) -> {
            completionLocks.add(Thread.holdsLock(service));
            completing.countDown();
            release.join();
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> reset = worker.submit(service::clear);
            assertTrue(completing.await(5, TimeUnit.SECONDS));
            if (overlappingReset) {
                service.clear();
            }
            CompletableFuture<JsonNode> duringReset = service.registerPendingToken("during-reset", OWNER);
            assertTrue(duringReset.isCompletedExceptionally());
            assertEquals("InvalidToken", callbackResult(() -> service.sendTaskSuccess("during-reset", "{}")));
            release.complete(null);
            reset.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(false), completionLocks);

            CompletableFuture<JsonNode> afterReset = service.registerPendingToken("after-reset", OWNER);
            service.sendTaskSuccess("after-reset", "{}");
            assertEquals(mapper.readTree("{}"), afterReset.get(5, TimeUnit.SECONDS));
        } finally {
            release.complete(null);
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private String callbackResult(Runnable callback) {
        try {
            callback.run();
            return "accepted";
        } catch (AwsException error) {
            return error.getErrorCode();
        }
    }

    @SuppressWarnings("unchecked")
    private AslExecutor executor(LongSupplier clock) {
        return executor(service, clock);
    }

    @SuppressWarnings("unchecked")
    private AslExecutor executor(StepFunctionsService target, LongSupplier clock) {
        Instance<StepFunctionsService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(target);
        return AslExecutorFixture.builder().objectMapper(mapper).sfnService(instance).nanoTime(clock).build();
    }
}
