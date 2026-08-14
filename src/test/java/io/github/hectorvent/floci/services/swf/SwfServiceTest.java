package io.github.hectorvent.floci.services.swf;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.swf.SwfService.Decision;
import io.github.hectorvent.floci.services.swf.SwfService.ExecutionFilter;
import io.github.hectorvent.floci.services.swf.SwfService.StartWorkflowExecutionRequest;
import io.github.hectorvent.floci.services.swf.model.SwfActivityTask;
import io.github.hectorvent.floci.services.swf.model.SwfActivityType;
import io.github.hectorvent.floci.services.swf.model.SwfDecisionTask;
import io.github.hectorvent.floci.services.swf.model.SwfDomain;
import io.github.hectorvent.floci.services.swf.model.SwfHistoryEvent;
import io.github.hectorvent.floci.services.swf.model.SwfWorkflowExecution;
import io.github.hectorvent.floci.services.swf.model.SwfWorkflowType;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the parts of the SWF state machine that are awkward to reach over
 * HTTP: timeout expiry (driven here by a controllable clock rather than wall time) and
 * the decision-task-outstanding invariant.
 */
class SwfServiceTest {

    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "unit-domain";

    private MutableClock clock;
    private SwfService service;
    private List<String> lambdaInvocations;
    private LambdaInvocationResult lambdaResponse;
    private RuntimeException lambdaFailure;
    private LambdaGate lambdaGate;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        lambdaInvocations = new ArrayList<>();
        lambdaResponse = new LambdaInvocationResult("{\"ok\":true}", null);
        lambdaFailure = null;
        lambdaGate = null;
        service = new SwfService(new InMemoryStorageFactory(),
                new RegionResolver("us-east-1", "000000000000"), clock, recordingLambdaInvoker());

        service.registerDomain(DOMAIN, "unit test domain", "7", Map.of(), REGION);
        service.registerWorkflowType(REGION, DOMAIN, workflowType("W", "1"));
        service.registerActivityType(REGION, DOMAIN, activityType("A", "1"));
    }

    /**
     * Stands in for the real Lambda service so these tests need no Docker daemon: records the
     * region and function it was asked for, then returns {@link #lambdaResponse} or throws
     * {@link #lambdaFailure}.
     */
    private LambdaInvoker recordingLambdaInvoker() {
        return (region, functionName, payload) -> {
            lambdaInvocations.add(region + "|" + functionName + "|"
                    + new String(payload, StandardCharsets.UTF_8));
            LambdaGate gate = lambdaGate;
            if (gate != null) {
                // Let the test observe that the invocation is in flight, then block in it.
                gate.started().countDown();
                try {
                    gate.release().await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (lambdaFailure != null) {
                throw lambdaFailure;
            }
            return lambdaResponse;
        };
    }

    @Test
    void startWorkflowExecution_seedsStartedAndDecisionTaskScheduledEvents() {
        String runId = start("wf-1");

        List<SwfHistoryEvent> events = service.getWorkflowExecutionHistory(REGION, DOMAIN, "wf-1", runId, false);
        assertEquals(2, events.size());
        assertEquals(1, events.get(0).getEventId());
        assertEquals("WorkflowExecutionStarted", events.get(0).getEventType());
        assertEquals(2, events.get(1).getEventId());
        assertEquals("DecisionTaskScheduled", events.get(1).getEventType());
    }

    @Test
    void startWorkflowExecution_resolvesUnsetFieldsFromTheWorkflowTypeDefaults() {
        String runId = start("wf-defaults");
        SwfWorkflowExecution execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-defaults", runId);

        assertEquals("tl", execution.getTaskList());
        assertEquals("300", execution.getExecutionStartToCloseTimeout());
        assertEquals("10", execution.getTaskStartToCloseTimeout());
        assertEquals("TERMINATE", execution.getChildPolicy());
    }

    @Test
    void startWorkflowExecution_withoutAnyDefault_throwsDefaultUndefinedFault() {
        SwfWorkflowType bare = new SwfWorkflowType();
        bare.setName("Bare");
        bare.setVersion("1");
        service.registerWorkflowType(REGION, DOMAIN, bare);

        AwsException thrown = assertThrows(AwsException.class, () -> service.startWorkflowExecution(
                new StartWorkflowExecutionRequest(REGION, DOMAIN, "wf-bare", "Bare", "1",
                        null, null, null, null, null, null, null, null)));
        assertEquals("DefaultUndefinedFault", thrown.getErrorCode());
        assertEquals("com.amazonaws.swf.base.model#DefaultUndefinedFault", thrown.jsonType());
    }

    @Test
    void faults_reportBareErrorCodeAndNamespacedJsonType() {
        AwsException thrown = assertThrows(AwsException.class, () -> service.describeDomain(REGION, "no-such"));

        // botocore prefers the header (error code) over the body's __type, so the bare name
        // has to stay on the code for the CLI to print UnknownResourceFault.
        assertEquals("UnknownResourceFault", thrown.getErrorCode());
        assertEquals("com.amazonaws.swf.base.model#UnknownResourceFault", thrown.jsonType());
        assertEquals("Unknown domain: no-such", thrown.getMessage());
        assertEquals(400, thrown.getHttpStatus());
    }

    @Test
    void pollForDecisionTask_handsOutOnlyOneTaskPerExecutionAtATime() {
        start("wf-single");

        assertTrue(service.pollForDecisionTask(REGION, DOMAIN, "tl", "d1").isPresent());
        assertTrue(service.pollForDecisionTask(REGION, DOMAIN, "tl", "d2").isEmpty(),
                "a second decider must not receive the same execution's task");
    }

    @Test
    void signalWhileDecisionOutstanding_defersTheNextTaskUntilTheCurrentOneCompletes() {
        String runId = start("wf-defer");
        SwfDecisionTask first = service.pollForDecisionTask(REGION, DOMAIN, "tl", "d1").orElseThrow();

        service.signalWorkflowExecution(REGION, DOMAIN, "wf-defer", runId, "poke", null);
        assertTrue(service.pollForDecisionTask(REGION, DOMAIN, "tl", "d2").isEmpty(),
                "the signal must not create a concurrent decision task");

        service.respondDecisionTaskCompleted(first.getTaskToken(), List.of(), null);
        assertTrue(service.pollForDecisionTask(REGION, DOMAIN, "tl", "d3").isPresent(),
                "completing the outstanding task must release the deferred one");
    }

    @Test
    void respondDecisionTaskCompleted_withStaleToken_throwsUnknownResourceFault() {
        AwsException thrown = assertThrows(AwsException.class,
                () -> service.respondDecisionTaskCompleted("not-a-token", List.of(), null));
        assertEquals("UnknownResourceFault", thrown.getErrorCode());
    }

    @Test
    void respondDecisionTaskCompleted_rejectsABatchWhoseClosingDecisionIsNotLast() {
        String runId = start("wf-after-close");
        SwfDecisionTask task = service.pollForDecisionTask(REGION, DOMAIN, "tl", "d").orElseThrow();

        // The live service rejects the whole batch rather than applying the prefix.
        AwsException thrown = assertThrows(AwsException.class,
                () -> service.respondDecisionTaskCompleted(task.getTaskToken(), List.of(
                        new Decision("CompleteWorkflowExecution", Map.of("result", "done")),
                        new Decision("ScheduleActivityTask", Map.of(
                                "activityId", "too-late",
                                "activityType", Map.of("name", "A", "version", "1")))), null));
        assertEquals("ValidationException", thrown.getErrorCode());
        assertEquals("Close must be last decision in list", thrown.getMessage());
        assertEquals("com.amazon.coral.validate#ValidationException", thrown.jsonType());

        // Nothing was applied and the task is still outstanding, so the decider can retry.
        SwfWorkflowExecution execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-after-close", runId);
        assertEquals("OPEN", execution.getExecutionStatus());
        assertTrue(execution.getActivities().isEmpty());
        assertNull(lastAttribute(execution, "DecisionTaskCompleted", "scheduledEventId"),
                "a rejected batch must not append DecisionTaskCompleted");

        service.respondDecisionTaskCompleted(task.getTaskToken(), List.of(
                new Decision("CompleteWorkflowExecution", Map.of("result", "done"))), null);
        assertEquals("COMPLETED",
                service.describeWorkflowExecution(REGION, DOMAIN, "wf-after-close", runId).getCloseStatus());
    }

    @Test
    void respondDecisionTaskCompleted_acceptsAClosingDecisionInFinalPosition() {
        String runId = start("wf-close-last");
        SwfDecisionTask task = service.pollForDecisionTask(REGION, DOMAIN, "tl", "d").orElseThrow();

        service.respondDecisionTaskCompleted(task.getTaskToken(), List.of(
                new Decision("RecordMarker", Map.of("markerName", "m-1")),
                new Decision("CompleteWorkflowExecution", Map.of("result", "done"))), null);

        SwfWorkflowExecution execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-close-last", runId);
        assertEquals("m-1", lastAttribute(execution, "MarkerRecorded", "markerName"));
        assertEquals("COMPLETED", execution.getCloseStatus());
    }

    @Test
    void closingDecisionWithAnOpenActivity_recordsUnhandledDecisionAndKeepsTheExecutionOpen() {
        String runId = start("wf-unhandled");
        scheduleActivity("wf-unhandled", "act-1");

        SwfDecisionTask task = pokeForDecision("wf-unhandled");
        service.respondDecisionTaskCompleted(task.getTaskToken(),
                List.of(new Decision("CompleteWorkflowExecution", Map.of())), null);

        SwfWorkflowExecution execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-unhandled", runId);
        assertEquals("OPEN", execution.getExecutionStatus());
        assertEquals("UNHANDLED_DECISION",
                lastAttribute(execution, "CompleteWorkflowExecutionFailed", "cause"));
    }

    @Test
    void activityScheduleToStartTimeout_expiresTheTaskAndSchedulesANewDecision() {
        String runId = start("wf-s2s");
        scheduleActivity("wf-s2s", "act-s2s");

        clock.advance(Duration.ofSeconds(31));
        service.sweep();

        SwfWorkflowExecution execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-s2s", runId);
        assertEquals("SCHEDULE_TO_START", lastAttribute(execution, "ActivityTaskTimedOut", "timeoutType"));
        // Never started, so the live service reports startedEventId 0 rather than omitting it.
        assertEquals(0L, lastAttribute(execution, "ActivityTaskTimedOut", "startedEventId"));
        assertEquals(0, service.openActivityCount(execution));
        assertTrue(service.pollForDecisionTask(REGION, DOMAIN, "tl", "d").isPresent(),
                "the timeout must schedule a decision task");
    }

    @Test
    void activityStartToCloseTimeout_expiresAStartedTask() {
        start("wf-s2c");
        scheduleActivity("wf-s2c", "act-s2c");
        SwfActivityTask task = service.pollForActivityTask(REGION, DOMAIN, "act-tl", "w").orElseThrow();
        assertEquals("act-s2c", task.getActivityId());

        clock.advance(Duration.ofSeconds(61));
        service.sweep();

        SwfWorkflowExecution execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-s2c", null);
        assertEquals("START_TO_CLOSE", lastAttribute(execution, "ActivityTaskTimedOut", "timeoutType"));
        assertThrows(AwsException.class,
                () -> service.respondActivityTaskCompleted(task.getTaskToken(), "too late"),
                "the token must stop resolving once the task has timed out");
    }

    @Test
    void activityHeartbeatTimeout_expiresATaskThatStopsHeartbeating() {
        SwfActivityType heartbeating = activityType("HB", "1");
        heartbeating.setDefaultTaskHeartbeatTimeout("10");
        heartbeating.setDefaultTaskStartToCloseTimeout("600");
        service.registerActivityType(REGION, DOMAIN, heartbeating);

        start("wf-hb");
        SwfDecisionTask decision = service.pollForDecisionTask(REGION, DOMAIN, "tl", "d").orElseThrow();
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("ScheduleActivityTask", Map.of(
                        "activityId", "act-hb",
                        "activityType", Map.of("name", "HB", "version", "1")))), null);
        SwfActivityTask task = service.pollForActivityTask(REGION, DOMAIN, "act-tl", "w").orElseThrow();

        // A heartbeat resets the window, so the task survives the first advance.
        clock.advance(Duration.ofSeconds(8));
        assertFalse(service.recordActivityTaskHeartbeat(task.getTaskToken(), "alive"));
        clock.advance(Duration.ofSeconds(8));
        service.sweep();
        SwfWorkflowExecution execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-hb", null);
        assertNull(lastAttribute(execution, "ActivityTaskTimedOut", "timeoutType"),
                "a heartbeat within the window must keep the task alive");

        clock.advance(Duration.ofSeconds(11));
        service.sweep();
        execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-hb", null);
        assertEquals("HEARTBEAT", lastAttribute(execution, "ActivityTaskTimedOut", "timeoutType"));
    }

    @Test
    void decisionTaskStartToCloseTimeout_reschedulesTheTaskForAnotherDecider() {
        String runId = start("wf-dt-timeout");
        SwfDecisionTask abandoned = service.pollForDecisionTask(REGION, DOMAIN, "tl", "dead").orElseThrow();

        clock.advance(Duration.ofSeconds(11));
        service.sweep();

        SwfWorkflowExecution execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-dt-timeout", runId);
        assertEquals("START_TO_CLOSE", lastAttribute(execution, "DecisionTaskTimedOut", "timeoutType"));
        assertThrows(AwsException.class,
                () -> service.respondDecisionTaskCompleted(abandoned.getTaskToken(), List.of(), null));

        SwfDecisionTask replacement = service.pollForDecisionTask(REGION, DOMAIN, "tl", "fresh").orElseThrow();
        assertEquals(abandoned.getStartedEventId(), replacement.getPreviousStartedEventId());
    }

    @Test
    void workflowExecutionStartToCloseTimeout_closesTheExecutionAsTimedOut() {
        String runId = start("wf-exec-timeout");

        clock.advance(Duration.ofSeconds(301));
        service.sweep();

        SwfWorkflowExecution execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-exec-timeout", runId);
        assertEquals("CLOSED", execution.getExecutionStatus());
        assertEquals("TIMED_OUT", execution.getCloseStatus());
        assertEquals("START_TO_CLOSE", lastAttribute(execution, "WorkflowExecutionTimedOut", "timeoutType"));
    }

    @Test
    void timerFires_onceItsStartToFireTimeoutElapses() {
        String runId = start("wf-timer");
        SwfDecisionTask decision = service.pollForDecisionTask(REGION, DOMAIN, "tl", "d").orElseThrow();
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("StartTimer", Map.of("timerId", "t-1", "startToFireTimeout", "30"))), null);

        service.sweep();
        assertEquals(1, service.openTimerCount(
                service.describeWorkflowExecution(REGION, DOMAIN, "wf-timer", runId)));

        clock.advance(Duration.ofSeconds(31));
        service.sweep();

        SwfWorkflowExecution execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-timer", runId);
        assertEquals("t-1", lastAttribute(execution, "TimerFired", "timerId"));
        assertEquals(0, service.openTimerCount(execution));
    }

    @Test
    void timeoutSweep_neverTouchesClosedExecutions() {
        String runId = start("wf-closed");
        SwfDecisionTask decision = service.pollForDecisionTask(REGION, DOMAIN, "tl", "d").orElseThrow();
        service.respondDecisionTaskCompleted(decision.getTaskToken(),
                List.of(new Decision("CompleteWorkflowExecution", Map.of("result", "ok"))), null);
        int eventCount = service.getWorkflowExecutionHistory(REGION, DOMAIN, "wf-closed", runId, false).size();

        clock.advance(Duration.ofHours(2));
        service.sweep();

        assertEquals(eventCount,
                service.getWorkflowExecutionHistory(REGION, DOMAIN, "wf-closed", runId, false).size(),
                "a closed execution must not accrue timeout events");
    }

    @Test
    void concurrentStartsOfTheSameWorkflowId_produceExactlyOneOpenRun() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch releaseAll = new CountDownLatch(1);
        List<Future<String>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                attempts.add(pool.submit(() -> {
                    releaseAll.await();
                    return service.startWorkflowExecution(new StartWorkflowExecutionRequest(
                            REGION, DOMAIN, "wf-race", "W", "1", null, null, null, null, null, null, null, null));
                }));
            }
            releaseAll.countDown();

            int started = 0;
            int rejected = 0;
            for (Future<String> attempt : attempts) {
                try {
                    assertNotNull(attempt.get(10, TimeUnit.SECONDS));
                    started++;
                } catch (ExecutionException e) {
                    assertInstanceOf(AwsException.class, e.getCause());
                    assertEquals("WorkflowExecutionAlreadyStartedFault",
                            ((AwsException) e.getCause()).getErrorCode());
                    rejected++;
                }
            }

            // SWF admits one open run per workflowId; the losers must see the fault rather
            // than each persisting their own run key.
            assertEquals(1, started, "exactly one start may succeed");
            assertEquals(threads - 1, rejected);
            assertEquals(1, service.listExecutions(REGION, DOMAIN, ExecutionFilter.all(), false).stream()
                    .filter(e -> "wf-race".equals(e.getWorkflowId()))
                    .count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void closingAChildWhileTheParentIsMutated_keepsBothHistoriesIntact() throws Exception {
        String parentRunId = start("wf-cc-parent");
        SwfDecisionTask decision = pollFor("wf-cc-parent");
        List<Decision> children = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            children.add(new Decision("StartChildWorkflowExecution", Map.of(
                    "workflowId", "wf-cc-child-" + i,
                    "workflowType", Map.of("name", "W", "version", "1"))));
        }
        service.respondDecisionTaskCompleted(decision.getTaskToken(), children, null);

        // Children closing concurrently all append ChildWorkflowExecutionCompleted to the
        // same parent. Unsynchronized event-id allocation loses or duplicates events.
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch releaseAll = new CountDownLatch(1);
        List<Future<?>> closes = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                String childId = "wf-cc-child-" + i;
                closes.add(pool.submit(() -> {
                    releaseAll.await();
                    SwfDecisionTask childTask = pollFor(childId);
                    service.respondDecisionTaskCompleted(childTask.getTaskToken(),
                            List.of(new Decision("CompleteWorkflowExecution",
                                    Map.of("result", childId))), null);
                    return null;
                }));
            }
            releaseAll.countDown();
            for (Future<?> close : closes) {
                close.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        SwfWorkflowExecution parent = service.describeWorkflowExecution(REGION, DOMAIN, "wf-cc-parent", parentRunId);
        List<SwfHistoryEvent> events = parent.getEvents();

        // Event ids must stay a contiguous 1..n with no duplicates.
        Set<Long> ids = new LinkedHashSet<>();
        for (SwfHistoryEvent event : events) {
            assertTrue(ids.add(event.getEventId()), "duplicate eventId " + event.getEventId());
        }
        for (int i = 0; i < events.size(); i++) {
            assertEquals(i + 1L, events.get(i).getEventId(), "eventIds must be contiguous");
        }

        // Every child must be reported exactly once.
        Map<String, Long> reported = events.stream()
                .filter(e -> "ChildWorkflowExecutionCompleted".equals(e.getEventType()))
                .collect(Collectors.groupingBy(
                        e -> (String) ((Map<?, ?>) e.getAttributes().get("workflowExecution")).get("workflowId"),
                        Collectors.counting()));
        assertEquals(6, reported.size(), "each child reports to the parent");
        reported.forEach((childId, count) ->
                assertEquals(1L, count, childId + " reported " + count + " times"));
    }

    @Test
    void scheduleLambdaFunction_invokesTheFunctionAndRecordsCompletion() {
        // A lambdaRole is required for SWF to invoke at all.
        service.registerWorkflowType(REGION, DOMAIN, lambdaWorkflowType("LW", "1",
                "arn:aws:iam::000000000000:role/swf-lambda"));
        String runId = startLambdaWf("wf-lam", "LW");

        SwfDecisionTask task = service.pollForDecisionTask(REGION, DOMAIN, "tl", "d").orElseThrow();
        lambdaResponse = new LambdaInvocationResult("{\"echoed\":{\"hello\":\"swf\"}}", null);
        service.respondDecisionTaskCompleted(task.getTaskToken(), List.of(
                scheduleLambda("lam-1", "my-fn", "{\"hello\":\"swf\"}", "ctl", "30")), null);

        assertEquals(1, lambdaInvocations.size(), "the function must actually be invoked");
        assertEquals("us-east-1|my-fn|{\"hello\":\"swf\"}", lambdaInvocations.get(0));

        List<SwfHistoryEvent> events = service.getWorkflowExecutionHistory(REGION, DOMAIN, "wf-lam", runId, false);
        SwfHistoryEvent scheduled = eventOfType(events, "LambdaFunctionScheduled");
        SwfHistoryEvent started = eventOfType(events, "LambdaFunctionStarted");
        SwfHistoryEvent completed = eventOfType(events, "LambdaFunctionCompleted");

        assertEquals("lam-1", scheduled.getAttributes().get("id"));
        assertEquals("my-fn", scheduled.getAttributes().get("name"));
        assertEquals("ctl", scheduled.getAttributes().get("control"));
        assertEquals("30", scheduled.getAttributes().get("startToCloseTimeout"));
        assertEquals(scheduled.getEventId(), started.getAttributes().get("scheduledEventId"));
        assertEquals(scheduled.getEventId(), completed.getAttributes().get("scheduledEventId"));
        assertEquals(started.getEventId(), completed.getAttributes().get("startedEventId"));
        assertEquals("{\"echoed\":{\"hello\":\"swf\"}}", completed.getAttributes().get("result"));

        // The outcome gives the decider a fresh task to react to.
        assertEquals("DecisionTaskScheduled", events.get(events.size() - 1).getEventType());
    }

    @Test
    void scheduleLambdaFunction_withNoInput_passesAnEmptyJsonObject() {
        service.registerWorkflowType(REGION, DOMAIN, lambdaWorkflowType("LW", "1",
                "arn:aws:iam::000000000000:role/swf-lambda"));
        startLambdaWf("wf-lam-empty", "LW");
        SwfDecisionTask task = service.pollForDecisionTask(REGION, DOMAIN, "tl", "d").orElseThrow();

        service.respondDecisionTaskCompleted(task.getTaskToken(), List.of(
                scheduleLambda("lam-1", "my-fn", null, null, null)), null);

        assertEquals("us-east-1|my-fn|{}", lambdaInvocations.get(0));
    }

    @Test
    void scheduleLambdaFunction_whenTheFunctionIsMissing_recordsFailedWithTheAwsErrorCode() {
        service.registerWorkflowType(REGION, DOMAIN, lambdaWorkflowType("LW", "1",
                "arn:aws:iam::000000000000:role/swf-lambda"));
        String runId = startLambdaWf("wf-lam-missing", "LW");
        SwfDecisionTask task = service.pollForDecisionTask(REGION, DOMAIN, "tl", "d").orElseThrow();

        lambdaFailure = new AwsException("ResourceNotFoundException",
                "Function not found: arn:aws:lambda:us-east-1:000000000000:function:gone", 404);
        service.respondDecisionTaskCompleted(task.getTaskToken(), List.of(
                scheduleLambda("lam-1", "gone", null, null, null)), null);

        List<SwfHistoryEvent> events = service.getWorkflowExecutionHistory(REGION, DOMAIN, "wf-lam-missing", runId, false);
        // The live service still reports Started before Failed for an unresolvable function.
        SwfHistoryEvent started = eventOfType(events, "LambdaFunctionStarted");
        SwfHistoryEvent failed = eventOfType(events, "LambdaFunctionFailed");
        assertEquals("ResourceNotFoundException", failed.getAttributes().get("reason"));
        assertEquals(started.getEventId(), failed.getAttributes().get("startedEventId"));
        assertEquals("DecisionTaskScheduled", events.get(events.size() - 1).getEventType());
    }

    @Test
    void scheduleLambdaFunction_whenTheHandlerErrors_recordsFailedWithTheFunctionError() {
        service.registerWorkflowType(REGION, DOMAIN, lambdaWorkflowType("LW", "1",
                "arn:aws:iam::000000000000:role/swf-lambda"));
        String runId = startLambdaWf("wf-lam-err", "LW");
        SwfDecisionTask task = service.pollForDecisionTask(REGION, DOMAIN, "tl", "d").orElseThrow();

        lambdaResponse = new LambdaInvocationResult("{\"errorMessage\":\"boom\"}", "Unhandled");
        service.respondDecisionTaskCompleted(task.getTaskToken(), List.of(
                scheduleLambda("lam-1", "my-fn", null, null, null)), null);

        SwfHistoryEvent failed = eventOfType(
                service.getWorkflowExecutionHistory(REGION, DOMAIN, "wf-lam-err", runId, false), "LambdaFunctionFailed");
        assertEquals("Unhandled", failed.getAttributes().get("reason"));
        assertEquals("{\"errorMessage\":\"boom\"}", failed.getAttributes().get("details"));
    }

    @Test
    void scheduleLambdaFunction_withoutALambdaRole_neverStartsAndReportsAssumeRoleFailed() {
        // The workflow type registered in setUp() has no defaultLambdaRole.
        String runId = start("wf-lam-norole");
        SwfDecisionTask task = service.pollForDecisionTask(REGION, DOMAIN, "tl", "d").orElseThrow();

        service.respondDecisionTaskCompleted(task.getTaskToken(), List.of(
                scheduleLambda("lam-1", "my-fn", null, null, null)), null);

        assertTrue(lambdaInvocations.isEmpty(), "no role means the function is never invoked");

        List<SwfHistoryEvent> events = service.getWorkflowExecutionHistory(REGION, DOMAIN, "wf-lam-norole", runId, false);
        SwfHistoryEvent scheduled = eventOfType(events, "LambdaFunctionScheduled");
        SwfHistoryEvent failed = eventOfType(events, "StartLambdaFunctionFailed");
        assertEquals("ASSUME_ROLE_FAILED", failed.getAttributes().get("cause"));
        assertEquals("No IAM role is attached to the current workflow execution.",
                failed.getAttributes().get("message"));
        assertEquals(scheduled.getEventId(), failed.getAttributes().get("scheduledEventId"));
        // This is the one Lambda path that skips Started entirely.
        assertTrue(events.stream().noneMatch(e -> "LambdaFunctionStarted".equals(e.getEventType())));
    }

    @Test
    void startWorkflowExecution_resolvesLambdaRoleFromTheTypeDefaultAndAcceptsAnOverride() {
        service.registerWorkflowType(REGION, DOMAIN, lambdaWorkflowType("LW", "1",
                "arn:aws:iam::000000000000:role/type-default"));

        String inherited = startLambdaWf("wf-inherit", "LW");
        assertEquals("arn:aws:iam::000000000000:role/type-default",
                service.describeWorkflowExecution(REGION, DOMAIN, "wf-inherit", inherited).getLambdaRole());

        String overridden = service.startWorkflowExecution(new StartWorkflowExecutionRequest(
                REGION, DOMAIN, "wf-override", "LW", "1", null, null, null, null, null, null, null,
                "arn:aws:iam::000000000000:role/per-execution"));
        assertEquals("arn:aws:iam::000000000000:role/per-execution",
                service.describeWorkflowExecution(REGION, DOMAIN, "wf-override", overridden).getLambdaRole());
    }

    @Test
    void domainsAreScopedByRegion_soTheSameNameCanExistInTwoRegions() {
        // us-east-1 already has DOMAIN from setUp(); registering it in eu-west-1 must succeed
        // rather than reporting DomainAlreadyExistsFault, because SWF names are per-region.
        service.registerDomain(DOMAIN, "west copy", "3", Map.of(), "eu-west-1");

        assertEquals("unit test domain", service.describeDomain(REGION, DOMAIN).getDescription());
        assertEquals("west copy", service.describeDomain("eu-west-1", DOMAIN).getDescription());
        assertEquals("7", service.describeDomain(REGION, DOMAIN)
                .getWorkflowExecutionRetentionPeriodInDays());
        assertEquals("3", service.describeDomain("eu-west-1", DOMAIN)
                .getWorkflowExecutionRetentionPeriodInDays());

        // Each domain's ARN names its own region.
        assertTrue(service.describeDomain(REGION, DOMAIN).getArn().contains(":" + REGION + ":"),
                service.describeDomain(REGION, DOMAIN).getArn());
        assertTrue(service.describeDomain("eu-west-1", DOMAIN).getArn().contains(":eu-west-1:"),
                service.describeDomain("eu-west-1", DOMAIN).getArn());

        // Re-registering in a region that already has it is still a duplicate.
        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.registerDomain(DOMAIN, "again", "1", Map.of(), REGION));
        assertEquals("DomainAlreadyExistsFault", duplicate.getErrorCode());

        // ListDomains only reports the caller's region.
        assertEquals(List.of(DOMAIN), service.listDomains("eu-west-1", "REGISTERED").stream()
                .map(SwfDomain::getName).toList());
    }

    @Test
    void typesAndExecutionsAreScopedByRegion() {
        service.registerDomain(DOMAIN, "west copy", "3", Map.of(), "eu-west-1");

        // A type registered only in us-east-1 must be invisible from eu-west-1.
        assertEquals(List.of("W"), service.listWorkflowTypes(REGION, DOMAIN, null, "REGISTERED", false)
                .stream().map(SwfWorkflowType::getName).toList());
        assertEquals(List.of(), service.listWorkflowTypes("eu-west-1", DOMAIN, null, "REGISTERED", false));
        AwsException unknownType = assertThrows(AwsException.class,
                () -> service.describeWorkflowType("eu-west-1", DOMAIN, "W", "1"));
        assertEquals("UnknownResourceFault", unknownType.getErrorCode());

        // The same workflowId can run independently in each region.
        service.registerWorkflowType("eu-west-1", DOMAIN, workflowType("W", "1"));
        String eastRun = start("wf-shared");
        String westRun = service.startWorkflowExecution(new StartWorkflowExecutionRequest(
                "eu-west-1", DOMAIN, "wf-shared", "W", "1",
                null, null, null, null, null, null, null, null));
        assertNotEquals(eastRun, westRun);

        assertEquals(eastRun, service.describeWorkflowExecution(REGION, DOMAIN, "wf-shared", null).getRunId());
        assertEquals(westRun, service.describeWorkflowExecution("eu-west-1", DOMAIN, "wf-shared", null).getRunId());

        // A runId from one region must not resolve in the other.
        AwsException crossRegion = assertThrows(AwsException.class,
                () -> service.describeWorkflowExecution("eu-west-1", DOMAIN, "wf-shared", eastRun));
        assertEquals("UnknownResourceFault", crossRegion.getErrorCode());

        // Each region hands out only its own decision tasks.
        SwfDecisionTask east = service.pollForDecisionTask(REGION, DOMAIN, "tl", "d").orElseThrow();
        assertEquals(eastRun, east.getRunId());
        SwfDecisionTask west = service.pollForDecisionTask("eu-west-1", DOMAIN, "tl", "d").orElseThrow();
        assertEquals(westRun, west.getRunId());
    }

    @Test
    void pageSize_matchesTheLiveServiceLimits() {
        // Absent or zero means "no caller limit"; above the 1000 cap is rejected, not clamped.
        assertEquals(1000, service.pageSize(null));
        assertEquals(1000, service.pageSize(0));
        assertEquals(25, service.pageSize(25));
        assertEquals(1000, service.pageSize(1000));

        AwsException tooBig = assertThrows(AwsException.class, () -> service.pageSize(1001));
        assertEquals("ValidationException", tooBig.getErrorCode());
        assertTrue(tooBig.getMessage().contains("less than or equal to 1000"), tooBig.getMessage());
        assertThrows(AwsException.class, () -> service.pageSize(-1));
    }

    @Test
    void anInvalidDecisionAnywhereInABatch_leavesTheExecutionUntouched() {
        String runId = start("wf-batch");
        SwfDecisionTask task = pollFor("wf-batch");
        int before = service.getWorkflowExecutionHistory(REGION, DOMAIN, "wf-batch", runId, false).size();

        // A valid decision followed by an unknown type: the live service rejects the batch and
        // persists none of it, so the marker must not appear and the token must stay claimable.
        AwsException thrown = assertThrows(AwsException.class, () -> service.respondDecisionTaskCompleted(
                task.getTaskToken(),
                List.of(new Decision("RecordMarker", Map.of("markerName", "should-not-persist")),
                        new Decision("NotARealDecision", Map.of())),
                null));
        assertEquals("ValidationException", thrown.getErrorCode());

        // Assert the state damage first: on the previous implementation the batch was rejected
        // too, but only after DecisionTaskCompleted and MarkerRecorded had already been appended
        // and the token consumed. That — not the message wording — is what this guards.
        List<SwfHistoryEvent> after =
                service.getWorkflowExecutionHistory(REGION, DOMAIN, "wf-batch", runId, false);
        assertEquals(before, after.size(), "a rejected batch must not grow the history");
        assertTrue(after.stream().noneMatch(e -> "DecisionTaskCompleted".equals(e.getEventType())),
                "DecisionTaskCompleted must not be appended for a rejected batch");
        assertTrue(after.stream().noneMatch(e -> "MarkerRecorded".equals(e.getEventType())),
                "an earlier decision in a rejected batch must not be applied");

        // The same token still works, so the decider has a recovery path.
        service.respondDecisionTaskCompleted(task.getTaskToken(),
                List.of(new Decision("RecordMarker", Map.of("markerName", "retry-after-reject"))), null);
        assertTrue(service.getWorkflowExecutionHistory(REGION, DOMAIN, "wf-batch", runId, false).stream()
                        .anyMatch(e -> "MarkerRecorded".equals(e.getEventType())),
                "the rejected token must remain claimable for a corrected batch");

        // AWS names the offending member by its 1-based position in the list, and renders the
        // enum set in the service's own order — which is not the API model's order.
        assertEquals("1 validation error detected: Value 'NotARealDecision' at "
                        + "'decisions.2.member.decisionType' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: "
                        + "[CompleteWorkflowExecution, StartTimer, RequestCancelExternalWorkflowExecution, "
                        + "SignalExternalWorkflowExecution, CancelTimer, RecordMarker, ScheduleActivityTask, "
                        + "ContinueAsNewWorkflowExecution, ScheduleLambdaFunction, FailWorkflowExecution, "
                        + "RequestCancelActivityTask, StartChildWorkflowExecution, CancelWorkflowExecution]",
                thrown.getMessage());
    }

    @Test
    void aChildThatContinuesAsNew_keepsItsParentAndRepointsTheParentsChildEntry() {
        String parentRun = start("wf-cn-parent");
        SwfDecisionTask parentTask = pollFor("wf-cn-parent");
        service.respondDecisionTaskCompleted(parentTask.getTaskToken(),
                List.of(new Decision("StartChildWorkflowExecution", Map.of(
                        "workflowId", "wf-cn-child",
                        "workflowType", Map.of("name", "W", "version", "1"),
                        "taskList", Map.of("name", "tl"),
                        "childPolicy", "TERMINATE"))),
                null);

        SwfWorkflowExecution child = service.describeWorkflowExecution(REGION, DOMAIN, "wf-cn-child", null);
        String originalChildRun = child.getRunId();
        assertEquals("wf-cn-parent", child.getParentWorkflowId());

        SwfDecisionTask childTask = pollFor("wf-cn-child");
        service.respondDecisionTaskCompleted(childTask.getTaskToken(),
                List.of(new Decision("ContinueAsNewWorkflowExecution", Map.of(
                        "taskList", Map.of("name", "tl"), "childPolicy", "TERMINATE"))),
                null);

        SwfWorkflowExecution successor = service.describeWorkflowExecution(REGION, DOMAIN, "wf-cn-child", null);
        assertNotEquals(originalChildRun, successor.getRunId(), "continue-as-new must open a new run");
        assertEquals(originalChildRun, successor.getContinuedExecutionRunId());

        // The successor keeps the parent link the live service reports on it.
        assertEquals("wf-cn-parent", successor.getParentWorkflowId(),
                "the successor of a child must keep its parent");
        assertEquals(parentRun, successor.getParentRunId());
        assertEquals(child.getParentInitiatedEventId(), successor.getParentInitiatedEventId());

        // ...and the parent now tracks the successor, not the closed original run.
        SwfWorkflowExecution parent =
                service.describeWorkflowExecution(REGION, DOMAIN, "wf-cn-parent", parentRun);
        assertEquals(successor.getRunId(), parent.getChildExecutions().get("wf-cn-child"),
                "the parent must track the successor run, not the closed one");

        // The successor's started event carries the parent, as the live service does.
        SwfHistoryEvent startedEvent = service
                .getWorkflowExecutionHistory(REGION, DOMAIN, "wf-cn-child", successor.getRunId(), false)
                .get(0);
        assertEquals("WorkflowExecutionStarted", startedEvent.getEventType());
        assertNotNull(startedEvent.getAttributes().get("parentWorkflowExecution"),
                "the successor's WorkflowExecutionStarted must name the parent");
    }

    @Test
    void aBlockingLambdaDoesNotStallOtherExecutionsInTheDomain() throws Exception {
        // A decision that schedules a Lambda must not hold the domain lock for the duration of
        // the invocation: the lock deliberately covers the whole domain, so a cold start would
        // otherwise stall every unrelated execution in it.
        CountDownLatch invocationStarted = new CountDownLatch(1);
        CountDownLatch releaseInvocation = new CountDownLatch(1);
        lambdaGate = new LambdaGate(invocationStarted, releaseInvocation);

        service.registerWorkflowType(REGION, DOMAIN, lambdaWorkflowType("LW", "1",
                "arn:aws:iam::000000000000:role/swf-lambda"));
        String lambdaRun = service.startWorkflowExecution(new StartWorkflowExecutionRequest(
                REGION, DOMAIN, "wf-lambda", "LW", "1",
                null, null, null, null, null, null, null,
                "arn:aws:iam::000000000000:role/swf-lambda"));
        SwfDecisionTask lambdaTask = pollFor("wf-lambda");

        // A second, unrelated execution in the same domain.
        String otherRun = start("wf-other");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> scheduling = pool.submit(() -> service.respondDecisionTaskCompleted(
                    lambdaTask.getTaskToken(),
                    List.of(new Decision("ScheduleLambdaFunction",
                            Map.of("id", "lam-1", "name", "blocking-fn"))),
                    null));

            assertTrue(invocationStarted.await(5, TimeUnit.SECONDS),
                    "the Lambda invocation should have been reached");

            // While the invocation is blocked, an unrelated execution must still progress.
            Future<?> signalling = pool.submit(() -> service.signalWorkflowExecution(
                    REGION, DOMAIN, "wf-other", otherRun, "ping", null));
            try {
                signalling.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                releaseInvocation.countDown();
                throw new AssertionError(
                        "signalling an unrelated execution blocked on the Lambda invocation", e);
            }

            releaseInvocation.countDown();
            scheduling.get(10, TimeUnit.SECONDS);
        } finally {
            releaseInvocation.countDown();
            pool.shutdownNow();
        }

        // The outcome is still recorded on the execution that scheduled it.
        assertTrue(service.getWorkflowExecutionHistory(REGION, DOMAIN, "wf-lambda", lambdaRun, false)
                        .stream().anyMatch(e -> "LambdaFunctionCompleted".equals(e.getEventType())),
                "the invocation's result must still be recorded after the lock is released");
    }

    /** Blocks inside invoke() until released, so the test can observe lock behaviour. */
    private record LambdaGate(CountDownLatch started, CountDownLatch release) {
    }

    @Test
    void aFailedBatchLeavesNoLambdaWorkForTheNextRequestOnTheSameThread() throws Exception {
        // The queue of deferred invocations must be scoped to the request, not the thread. To
        // reach the window, the store is made to fail *after* a Lambda has been queued and the
        // history mutated — the point past which a ThreadLocal queue would survive the throw.
        AtomicBoolean failNextPut = new AtomicBoolean(false);
        SwfService failing = new SwfService(new FailOnDemandStorageFactory(failNextPut),
                new RegionResolver("us-east-1", "000000000000"), clock, recordingLambdaInvoker());
        failing.registerDomain(DOMAIN, "leak test", "7", Map.of(), REGION);
        failing.registerWorkflowType(REGION, DOMAIN, workflowType("W", "1"));
        failing.registerWorkflowType(REGION, DOMAIN, lambdaWorkflowType("LW", "1",
                "arn:aws:iam::000000000000:role/swf-lambda"));

        failing.startWorkflowExecution(new StartWorkflowExecutionRequest(
                REGION, DOMAIN, "wf-leak", "LW", "1", null, null, null, null, null, null, null,
                "arn:aws:iam::000000000000:role/swf-lambda"));
        SwfDecisionTask leaky = failing.pollForDecisionTask(REGION, DOMAIN, "tl", "d")
                .orElseThrow();

        String plainRun = failing.startWorkflowExecution(new StartWorkflowExecutionRequest(
                REGION, DOMAIN, "wf-plain", "W", "1", null, null, null, null, null, null, null, null));
        SwfDecisionTask plain = failing.pollForDecisionTask(REGION, DOMAIN, "tl", "d")
                .orElseThrow();

        // Queue a Lambda, then fail while persisting the batch.
        failNextPut.set(true);
        assertThrows(RuntimeException.class, () -> failing.respondDecisionTaskCompleted(
                leaky.getTaskToken(),
                List.of(new Decision("ScheduleLambdaFunction",
                        Map.of("id", "leak-1", "name", "leaked-fn"))),
                null));
        failNextPut.set(false);
        assertTrue(lambdaInvocations.isEmpty(),
                "a request that failed before the drain must not invoke anything: " + lambdaInvocations);

        // A later request on the SAME thread must not inherit that queued invocation.
        failing.respondDecisionTaskCompleted(plain.getTaskToken(),
                List.of(new Decision("RecordMarker", Map.of("markerName", "m"))), null);

        assertTrue(lambdaInvocations.isEmpty(),
                "a later request must not run a failed request's queued Lambda: " + lambdaInvocations);
        assertTrue(failing.getWorkflowExecutionHistory(REGION, DOMAIN, "wf-plain", plainRun, false)
                        .stream().noneMatch(e -> e.getEventType().startsWith("LambdaFunction")),
                "no Lambda event may appear on the unrelated execution");
    }

    /** Storage whose put() throws once on demand, to fail a request mid-flight. */
    private static final class FailOnDemandStorageFactory extends StorageFactory {

        private final AtomicBoolean failNextPut;

        private FailOnDemandStorageFactory(AtomicBoolean failNextPut) {
            super(null, null);
            this.failNextPut = failNextPut;
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                       TypeReference<Map<String, V>> typeReference) {
            StorageBackend<String, V> inner = new InMemoryStorage<>();
            if (!"swf-executions.json".equals(fileName)) {
                return new AccountAwareStorageBackend<>(inner, null, "000000000000");
            }
            StorageBackend<String, V> failing = new StorageBackend<>() {
                @Override
                public void put(String key, V value) {
                    if (failNextPut.get()) {
                        throw new IllegalStateException("simulated store failure");
                    }
                    inner.put(key, value);
                }

                @Override
                public Optional<V> get(String key) {
                    return inner.get(key);
                }

                @Override
                public void delete(String key) {
                    inner.delete(key);
                }

                @Override
                public Set<String> keys() {
                    return inner.keys();
                }

                @Override
                public List<V> scan(java.util.function.Predicate<String> keyFilter) {
                    return inner.scan(keyFilter);
                }

                @Override
                public void flush() {
                    inner.flush();
                }

                @Override
                public void load() {
                    inner.load();
                }

                @Override
                public void clear() {
                    inner.clear();
                }
            };
            return new AccountAwareStorageBackend<>(failing, null, "000000000000");
        }
    }

    @Test
    void listExecutions_separatesOpenFromClosed() {
        start("wf-open");
        String closedRunId = start("wf-done");
        SwfDecisionTask decision = pollFor("wf-done");
        service.respondDecisionTaskCompleted(decision.getTaskToken(),
                List.of(new Decision("CompleteWorkflowExecution", Map.of())), null);

        List<SwfWorkflowExecution> open = service.listExecutions(REGION, DOMAIN, ExecutionFilter.all(), false);
        List<SwfWorkflowExecution> closed = service.listExecutions(REGION, DOMAIN, ExecutionFilter.all(), true);

        assertTrue(open.stream().anyMatch(e -> "wf-open".equals(e.getWorkflowId())));
        assertFalse(open.stream().anyMatch(e -> "wf-done".equals(e.getWorkflowId())));
        assertTrue(closed.stream()
                .anyMatch(e -> "wf-done".equals(e.getWorkflowId()) && closedRunId.equals(e.getRunId())));
    }

    @Test
    void deprecatedType_cannotStartAnExecutionButRemainsDescribable() {
        service.deprecateWorkflowType(REGION, DOMAIN, "W", "1");

        AwsException thrown = assertThrows(AwsException.class, () -> start("wf-dep"));
        assertEquals("TypeDeprecatedFault", thrown.getErrorCode());
        assertNotNull(service.describeWorkflowType(REGION, DOMAIN, "W", "1"));

        service.undeprecateWorkflowType(REGION, DOMAIN, "W", "1");
        assertNotNull(start("wf-dep"));
    }

    @Test
    void deleteWorkflowType_requiresPriorDeprecation() {
        AwsException thrown = assertThrows(AwsException.class,
                () -> service.deleteWorkflowType(REGION, DOMAIN, "W", "1"));
        assertEquals("TypeNotDeprecatedFault", thrown.getErrorCode());

        service.deprecateWorkflowType(REGION, DOMAIN, "W", "1");
        service.deleteWorkflowType(REGION, DOMAIN, "W", "1");
        assertThrows(AwsException.class, () -> service.describeWorkflowType(REGION, DOMAIN, "W", "1"));
    }

    @Test
    void terminatingAParentAppliesTerminateChildPolicyToItsOpenChildren() {
        String parentRunId = start("wf-parent");
        SwfDecisionTask decision = pollFor("wf-parent");
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("StartChildWorkflowExecution", Map.of(
                        "workflowId", "wf-child",
                        "workflowType", Map.of("name", "W", "version", "1")))), null);

        SwfWorkflowExecution child = service.describeWorkflowExecution(REGION, DOMAIN, "wf-child", null);
        assertEquals("OPEN", child.getExecutionStatus());
        assertEquals("wf-parent", child.getParentWorkflowId());

        service.terminateWorkflowExecution(REGION, DOMAIN, "wf-parent", parentRunId, "stop", null, null);

        child = service.describeWorkflowExecution(REGION, DOMAIN, "wf-child", child.getRunId());
        assertEquals("CLOSED", child.getExecutionStatus());
        assertEquals("TERMINATED", child.getCloseStatus());
        assertEquals("CHILD_POLICY_APPLIED", lastAttribute(child, "WorkflowExecutionTerminated", "cause"));
    }

    @Test
    void abandonChildPolicy_leavesChildrenRunning() {
        SwfWorkflowType abandoning = workflowType("Abandon", "1");
        abandoning.setDefaultChildPolicy("ABANDON");
        service.registerWorkflowType(REGION, DOMAIN, abandoning);

        String parentRunId = service.startWorkflowExecution(new StartWorkflowExecutionRequest(
                REGION, DOMAIN, "wf-abandon", "Abandon", "1", null, null, null, null, null, null, null, null));
        SwfDecisionTask decision = pollFor("wf-abandon");
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("StartChildWorkflowExecution", Map.of(
                        "workflowId", "wf-abandoned-child",
                        "workflowType", Map.of("name", "W", "version", "1")))), null);

        service.terminateWorkflowExecution(REGION, DOMAIN, "wf-abandon", parentRunId, null, null, null);

        assertEquals("OPEN", service.describeWorkflowExecution(REGION, DOMAIN, "wf-abandoned-child", null)
                .getExecutionStatus());
    }

    @Test
    void requestCancelActivityTask_cancelsAScheduledTaskImmediately() {
        String runId = start("wf-cancel-act");
        scheduleActivity("wf-cancel-act", "act-cancel");

        SwfDecisionTask decision = pokeForDecision("wf-cancel-act");
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("RequestCancelActivityTask", Map.of("activityId", "act-cancel"))), null);

        SwfWorkflowExecution execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-cancel-act", runId);
        assertNotNull(lastAttribute(execution, "ActivityTaskCancelRequested", "activityId"));
        // No worker holds the task, so there is nothing to observe the request: SWF cancels it.
        assertNotNull(lastAttribute(execution, "ActivityTaskCanceled", "scheduledEventId"));
        assertEquals(0, service.openActivityCount(execution));
    }

    @Test
    void activityTokenOnAClosedTask_reportsUnknownActivityWithItsScheduledEventId() {
        start("wf-stale-activity");
        scheduleActivity("wf-stale-activity", "act-stale");
        SwfActivityTask task = service.pollForActivityTask(REGION, DOMAIN, "act-tl", "w").orElseThrow();
        service.respondActivityTaskCompleted(task.getTaskToken(), "done");

        // The token is genuine, so the live service names the scheduled event rather than
        // calling the token unknown — a worker that heartbeats after closing sees this.
        AwsException thrown = assertThrows(AwsException.class,
                () -> service.recordActivityTaskHeartbeat(task.getTaskToken(), "late"));
        assertEquals("UnknownResourceFault", thrown.getErrorCode());
        assertEquals("Unknown activity, scheduledEventId = " + task.getScheduledEventId(),
                thrown.getMessage());

        // A token that never existed still reports the bad token instead.
        assertEquals("Unknown or expired task token",
                assertThrows(AwsException.class,
                        () -> service.recordActivityTaskHeartbeat("bogus", null)).getMessage());
    }

    @Test
    void heartbeatReportsCancelRequested_afterTheDeciderAsksToCancelAStartedTask() {
        start("wf-cancel-started");
        scheduleActivity("wf-cancel-started", "act-running");
        SwfActivityTask task = service.pollForActivityTask(REGION, DOMAIN, "act-tl", "w").orElseThrow();

        SwfDecisionTask decision = pokeForDecision("wf-cancel-started");
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("RequestCancelActivityTask", Map.of("activityId", "act-running"))), null);

        assertTrue(service.recordActivityTaskHeartbeat(task.getTaskToken(), null),
                "the worker learns about the cancellation through its heartbeat");
        service.respondActivityTaskCanceled(task.getTaskToken(), "stopped");

        SwfWorkflowExecution execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-cancel-started", null);
        assertEquals("stopped", lastAttribute(execution, "ActivityTaskCanceled", "details"));
    }

    @Test
    void scheduleActivityTask_withUnknownType_recordsFailureAndKeepsTheExecutionOpen() {
        String runId = start("wf-bad-type");
        SwfDecisionTask decision = service.pollForDecisionTask(REGION, DOMAIN, "tl", "d").orElseThrow();

        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("ScheduleActivityTask", Map.of(
                        "activityId", "nope",
                        "activityType", Map.of("name", "Missing", "version", "9")))), null);

        SwfWorkflowExecution execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-bad-type", runId);
        assertEquals("ACTIVITY_TYPE_DOES_NOT_EXIST",
                lastAttribute(execution, "ScheduleActivityTaskFailed", "cause"));
        assertEquals("OPEN", execution.getExecutionStatus());
        assertTrue(service.pollForDecisionTask(REGION, DOMAIN, "tl", "d2").isPresent(),
                "a failed decision must give the decider another chance");
    }

    @Test
    void continueAsNew_closesTheRunAndLinksTheSuccessor() {
        String firstRunId = start("wf-can");
        SwfDecisionTask decision = pollFor("wf-can");
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("ContinueAsNewWorkflowExecution", Map.of("input", "gen-2"))), null);

        SwfWorkflowExecution closed = service.describeWorkflowExecution(REGION, DOMAIN, "wf-can", firstRunId);
        assertEquals("CONTINUED_AS_NEW", closed.getCloseStatus());

        String newRunId = (String) lastAttribute(closed, "WorkflowExecutionContinuedAsNew", "newExecutionRunId");
        assertNotNull(newRunId);
        SwfWorkflowExecution successor = service.describeWorkflowExecution(REGION, DOMAIN, "wf-can", newRunId);
        assertEquals("OPEN", successor.getExecutionStatus());
        assertEquals(firstRunId, successor.getContinuedExecutionRunId());
        assertEquals("gen-2", successor.getInput());
    }

    @Test
    void signalExternalWorkflowExecution_deliversToTheTargetAndConfirmsToTheSender() {
        start("wf-sender");
        start("wf-receiver");

        SwfDecisionTask decision = pollFor("wf-sender");
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("SignalExternalWorkflowExecution", Map.of(
                        "workflowId", "wf-receiver", "signalName", "ping", "input", "hello"))), null);

        SwfWorkflowExecution receiver = service.describeWorkflowExecution(REGION, DOMAIN, "wf-receiver", null);
        assertEquals("ping", lastAttribute(receiver, "WorkflowExecutionSignaled", "signalName"));
        assertEquals("hello", lastAttribute(receiver, "WorkflowExecutionSignaled", "input"));

        SwfWorkflowExecution sender = service.describeWorkflowExecution(REGION, DOMAIN, "wf-sender", null);
        assertNotNull(lastAttribute(sender, "ExternalWorkflowExecutionSignaled", "initiatedEventId"));
    }

    @Test
    void signalUnknownExternalExecution_recordsFailureRatherThanThrowing() {
        start("wf-lonely");
        SwfDecisionTask decision = pollFor("wf-lonely");

        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("SignalExternalWorkflowExecution", Map.of(
                        "workflowId", "not-there", "signalName", "ping"))), null);

        SwfWorkflowExecution execution = service.describeWorkflowExecution(REGION, DOMAIN, "wf-lonely", null);
        assertEquals("UNKNOWN_EXTERNAL_WORKFLOW_EXECUTION",
                lastAttribute(execution, "SignalExternalWorkflowExecutionFailed", "cause"));
    }

    @Test
    void registerDomain_rejectsARetentionPeriodOutsideTheAllowedRange() {
        AwsException thrown = assertThrows(AwsException.class,
                () -> service.registerDomain("bad-retention", null, "500", Map.of(), "us-east-1"));
        assertEquals("ValidationException", thrown.getErrorCode());

        // NONE is the documented sentinel for "keep forever" and must be accepted.
        service.registerDomain("no-retention", null, "NONE", Map.of(), REGION);
        assertEquals("NONE", service.describeDomain(REGION, "no-retention")
                .getWorkflowExecutionRetentionPeriodInDays());
    }

    @Test
    void tagResource_roundTripsThroughTheDomainArn() {
        String arn = service.domainArnFor(service.describeDomain(REGION, DOMAIN), "us-east-1");
        assertEquals("arn:aws:swf:us-east-1:000000000000:/domain/" + DOMAIN, arn);

        service.tagResource(arn, Map.of("env", "unit"));
        assertEquals("unit", service.listTagsForResource(arn).get("env"));

        service.untagResource(arn, List.of("env"));
        assertTrue(service.listTagsForResource(arn).isEmpty());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String start(String workflowId) {
        return service.startWorkflowExecution(new StartWorkflowExecutionRequest(
                REGION, DOMAIN, workflowId, "W", "1", null, null, null, null, null, null, null, null));
    }

    /**
     * Schedules {@code activityId} onto the {@code act-tl} list via a decision.
     *
     * <p>A successful ScheduleActivityTask does not schedule a further decision task — the
     * decider waits for the activity to close — so callers that need another decision task
     * while the activity is open should use {@link #pokeForDecision(String)}.
     */
    private void scheduleActivity(String workflowId, String activityId) {
        SwfDecisionTask decision = pollFor(workflowId);
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("ScheduleActivityTask", Map.of(
                        "activityId", activityId,
                        "activityType", Map.of("name", "A", "version", "1")))), null);
    }

    /** Signals the execution so a decision task is scheduled, then claims it. */
    private SwfDecisionTask pokeForDecision(String workflowId) {
        service.signalWorkflowExecution(REGION, DOMAIN, workflowId, null, "poke", null);
        return pollFor(workflowId);
    }

    /**
     * Claims decision tasks until one belongs to {@code workflowId}; sibling executions in
     * the same domain share the {@code tl} task list.
     *
     * <p>Tolerates a transient empty poll so the concurrency tests can call this from
     * several threads: another thread may hold the task this caller wants at that instant.
     */
    private SwfDecisionTask pollFor(String workflowId) {
        for (int attempt = 0; attempt < 200; attempt++) {
            Optional<SwfDecisionTask> claimed = service.pollForDecisionTask(REGION, DOMAIN, "tl", "d");
            if (claimed.isPresent()) {
                SwfDecisionTask task = claimed.get();
                if (workflowId.equals(task.getWorkflowId())) {
                    return task;
                }
                // Release a sibling's task so its own poller can claim it.
                service.respondDecisionTaskCompleted(task.getTaskToken(), List.of(), null);
                continue;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("no decision task for " + workflowId);
    }

    private Object lastAttribute(SwfWorkflowExecution execution, String eventType, String attribute) {
        List<SwfHistoryEvent> events = execution.getEvents();
        for (int i = events.size() - 1; i >= 0; i--) {
            if (eventType.equals(events.get(i).getEventType())) {
                return events.get(i).getAttributes().get(attribute);
            }
        }
        return null;
    }

    private static SwfWorkflowType workflowType(String name, String version) {
        SwfWorkflowType type = new SwfWorkflowType();
        type.setName(name);
        type.setVersion(version);
        type.setDefaultTaskList("tl");
        type.setDefaultExecutionStartToCloseTimeout("300");
        type.setDefaultTaskStartToCloseTimeout("10");
        type.setDefaultChildPolicy("TERMINATE");
        return type;
    }

    private static SwfActivityType activityType(String name, String version) {
        SwfActivityType type = new SwfActivityType();
        type.setName(name);
        type.setVersion(version);
        type.setDefaultTaskList("act-tl");
        type.setDefaultTaskScheduleToStartTimeout("30");
        type.setDefaultTaskStartToCloseTimeout("60");
        type.setDefaultTaskScheduleToCloseTimeout("NONE");
        type.setDefaultTaskHeartbeatTimeout("NONE");
        return type;
    }

    /** A workflow type carrying a {@code defaultLambdaRole}, which SWF requires to invoke Lambda. */
    private static SwfWorkflowType lambdaWorkflowType(String name, String version, String role) {
        SwfWorkflowType type = workflowType(name, version);
        type.setDefaultLambdaRole(role);
        return type;
    }

    private String startLambdaWf(String workflowId, String typeName) {
        return service.startWorkflowExecution(new StartWorkflowExecutionRequest(
                REGION, DOMAIN, workflowId, typeName, "1", null, null, null, null, null, null, null, null));
    }

    private static Decision scheduleLambda(String id, String name, String input,
                                           String control, String startToCloseTimeout) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("id", id);
        attrs.put("name", name);
        if (input != null) {
            attrs.put("input", input);
        }
        if (control != null) {
            attrs.put("control", control);
        }
        if (startToCloseTimeout != null) {
            attrs.put("startToCloseTimeout", startToCloseTimeout);
        }
        return new Decision("ScheduleLambdaFunction", attrs);
    }

    private static SwfHistoryEvent eventOfType(List<SwfHistoryEvent> events, String eventType) {
        return events.stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + eventType + " in "
                        + events.stream().map(SwfHistoryEvent::getEventType).toList()));
    }

    /**
     * Minimal in-memory {@link StorageFactory} so the service under test needs no Quarkus
     * container. Only {@link StorageFactory#create} is exercised by SwfService.
     */
    private static final class InMemoryStorageFactory extends StorageFactory {

        InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public synchronized <V> AccountAwareStorageBackend<V> create(
                String serviceName, String fileName, TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory("000000000000");
        }
    }
}
