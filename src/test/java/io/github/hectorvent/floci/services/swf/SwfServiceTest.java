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
import io.github.hectorvent.floci.services.swf.model.SwfHistoryEvent;
import io.github.hectorvent.floci.services.swf.model.SwfWorkflowExecution;
import io.github.hectorvent.floci.services.swf.model.SwfWorkflowType;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static final String DOMAIN = "unit-domain";

    private MutableClock clock;
    private SwfService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = new SwfService(new InMemoryStorageFactory(),
                new RegionResolver("us-east-1", "000000000000"), clock);

        service.registerDomain(DOMAIN, "unit test domain", "7", Map.of(), "us-east-1");
        service.registerWorkflowType(DOMAIN, workflowType("W", "1"));
        service.registerActivityType(DOMAIN, activityType("A", "1"));
    }

    @Test
    void startWorkflowExecution_seedsStartedAndDecisionTaskScheduledEvents() {
        String runId = start("wf-1");

        List<SwfHistoryEvent> events = service.getWorkflowExecutionHistory(DOMAIN, "wf-1", runId, false);
        assertEquals(2, events.size());
        assertEquals(1, events.get(0).getEventId());
        assertEquals("WorkflowExecutionStarted", events.get(0).getEventType());
        assertEquals(2, events.get(1).getEventId());
        assertEquals("DecisionTaskScheduled", events.get(1).getEventType());
    }

    @Test
    void startWorkflowExecution_resolvesUnsetFieldsFromTheWorkflowTypeDefaults() {
        String runId = start("wf-defaults");
        SwfWorkflowExecution execution = service.describeWorkflowExecution(DOMAIN, "wf-defaults", runId);

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
        service.registerWorkflowType(DOMAIN, bare);

        AwsException thrown = assertThrows(AwsException.class, () -> service.startWorkflowExecution(
                new StartWorkflowExecutionRequest(DOMAIN, "wf-bare", "Bare", "1",
                        null, null, null, null, null, null, null, null)));
        assertEquals("DefaultUndefinedFault", thrown.getErrorCode());
        assertEquals("com.amazonaws.swf.base.model#DefaultUndefinedFault", thrown.jsonType());
    }

    @Test
    void faults_reportBareErrorCodeAndNamespacedJsonType() {
        AwsException thrown = assertThrows(AwsException.class, () -> service.describeDomain("no-such"));

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

        assertTrue(service.pollForDecisionTask(DOMAIN, "tl", "d1").isPresent());
        assertTrue(service.pollForDecisionTask(DOMAIN, "tl", "d2").isEmpty(),
                "a second decider must not receive the same execution's task");
    }

    @Test
    void signalWhileDecisionOutstanding_defersTheNextTaskUntilTheCurrentOneCompletes() {
        String runId = start("wf-defer");
        SwfDecisionTask first = service.pollForDecisionTask(DOMAIN, "tl", "d1").orElseThrow();

        service.signalWorkflowExecution(DOMAIN, "wf-defer", runId, "poke", null);
        assertTrue(service.pollForDecisionTask(DOMAIN, "tl", "d2").isEmpty(),
                "the signal must not create a concurrent decision task");

        service.respondDecisionTaskCompleted(first.getTaskToken(), List.of(), null);
        assertTrue(service.pollForDecisionTask(DOMAIN, "tl", "d3").isPresent(),
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
        SwfDecisionTask task = service.pollForDecisionTask(DOMAIN, "tl", "d").orElseThrow();

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
        SwfWorkflowExecution execution = service.describeWorkflowExecution(DOMAIN, "wf-after-close", runId);
        assertEquals("OPEN", execution.getExecutionStatus());
        assertTrue(execution.getActivities().isEmpty());
        assertNull(lastAttribute(execution, "DecisionTaskCompleted", "scheduledEventId"),
                "a rejected batch must not append DecisionTaskCompleted");

        service.respondDecisionTaskCompleted(task.getTaskToken(), List.of(
                new Decision("CompleteWorkflowExecution", Map.of("result", "done"))), null);
        assertEquals("COMPLETED",
                service.describeWorkflowExecution(DOMAIN, "wf-after-close", runId).getCloseStatus());
    }

    @Test
    void respondDecisionTaskCompleted_acceptsAClosingDecisionInFinalPosition() {
        String runId = start("wf-close-last");
        SwfDecisionTask task = service.pollForDecisionTask(DOMAIN, "tl", "d").orElseThrow();

        service.respondDecisionTaskCompleted(task.getTaskToken(), List.of(
                new Decision("RecordMarker", Map.of("markerName", "m-1")),
                new Decision("CompleteWorkflowExecution", Map.of("result", "done"))), null);

        SwfWorkflowExecution execution = service.describeWorkflowExecution(DOMAIN, "wf-close-last", runId);
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

        SwfWorkflowExecution execution = service.describeWorkflowExecution(DOMAIN, "wf-unhandled", runId);
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

        SwfWorkflowExecution execution = service.describeWorkflowExecution(DOMAIN, "wf-s2s", runId);
        assertEquals("SCHEDULE_TO_START", lastAttribute(execution, "ActivityTaskTimedOut", "timeoutType"));
        // Never started, so the live service reports startedEventId 0 rather than omitting it.
        assertEquals(0L, lastAttribute(execution, "ActivityTaskTimedOut", "startedEventId"));
        assertEquals(0, service.openActivityCount(execution));
        assertTrue(service.pollForDecisionTask(DOMAIN, "tl", "d").isPresent(),
                "the timeout must schedule a decision task");
    }

    @Test
    void activityStartToCloseTimeout_expiresAStartedTask() {
        start("wf-s2c");
        scheduleActivity("wf-s2c", "act-s2c");
        SwfActivityTask task = service.pollForActivityTask(DOMAIN, "act-tl", "w").orElseThrow();
        assertEquals("act-s2c", task.getActivityId());

        clock.advance(Duration.ofSeconds(61));
        service.sweep();

        SwfWorkflowExecution execution = service.describeWorkflowExecution(DOMAIN, "wf-s2c", null);
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
        service.registerActivityType(DOMAIN, heartbeating);

        start("wf-hb");
        SwfDecisionTask decision = service.pollForDecisionTask(DOMAIN, "tl", "d").orElseThrow();
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("ScheduleActivityTask", Map.of(
                        "activityId", "act-hb",
                        "activityType", Map.of("name", "HB", "version", "1")))), null);
        SwfActivityTask task = service.pollForActivityTask(DOMAIN, "act-tl", "w").orElseThrow();

        // A heartbeat resets the window, so the task survives the first advance.
        clock.advance(Duration.ofSeconds(8));
        assertFalse(service.recordActivityTaskHeartbeat(task.getTaskToken(), "alive"));
        clock.advance(Duration.ofSeconds(8));
        service.sweep();
        SwfWorkflowExecution execution = service.describeWorkflowExecution(DOMAIN, "wf-hb", null);
        assertNull(lastAttribute(execution, "ActivityTaskTimedOut", "timeoutType"),
                "a heartbeat within the window must keep the task alive");

        clock.advance(Duration.ofSeconds(11));
        service.sweep();
        execution = service.describeWorkflowExecution(DOMAIN, "wf-hb", null);
        assertEquals("HEARTBEAT", lastAttribute(execution, "ActivityTaskTimedOut", "timeoutType"));
    }

    @Test
    void decisionTaskStartToCloseTimeout_reschedulesTheTaskForAnotherDecider() {
        String runId = start("wf-dt-timeout");
        SwfDecisionTask abandoned = service.pollForDecisionTask(DOMAIN, "tl", "dead").orElseThrow();

        clock.advance(Duration.ofSeconds(11));
        service.sweep();

        SwfWorkflowExecution execution = service.describeWorkflowExecution(DOMAIN, "wf-dt-timeout", runId);
        assertEquals("START_TO_CLOSE", lastAttribute(execution, "DecisionTaskTimedOut", "timeoutType"));
        assertThrows(AwsException.class,
                () -> service.respondDecisionTaskCompleted(abandoned.getTaskToken(), List.of(), null));

        SwfDecisionTask replacement = service.pollForDecisionTask(DOMAIN, "tl", "fresh").orElseThrow();
        assertEquals(abandoned.getStartedEventId(), replacement.getPreviousStartedEventId());
    }

    @Test
    void workflowExecutionStartToCloseTimeout_closesTheExecutionAsTimedOut() {
        String runId = start("wf-exec-timeout");

        clock.advance(Duration.ofSeconds(301));
        service.sweep();

        SwfWorkflowExecution execution = service.describeWorkflowExecution(DOMAIN, "wf-exec-timeout", runId);
        assertEquals("CLOSED", execution.getExecutionStatus());
        assertEquals("TIMED_OUT", execution.getCloseStatus());
        assertEquals("START_TO_CLOSE", lastAttribute(execution, "WorkflowExecutionTimedOut", "timeoutType"));
    }

    @Test
    void timerFires_onceItsStartToFireTimeoutElapses() {
        String runId = start("wf-timer");
        SwfDecisionTask decision = service.pollForDecisionTask(DOMAIN, "tl", "d").orElseThrow();
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("StartTimer", Map.of("timerId", "t-1", "startToFireTimeout", "30"))), null);

        service.sweep();
        assertEquals(1, service.openTimerCount(
                service.describeWorkflowExecution(DOMAIN, "wf-timer", runId)));

        clock.advance(Duration.ofSeconds(31));
        service.sweep();

        SwfWorkflowExecution execution = service.describeWorkflowExecution(DOMAIN, "wf-timer", runId);
        assertEquals("t-1", lastAttribute(execution, "TimerFired", "timerId"));
        assertEquals(0, service.openTimerCount(execution));
    }

    @Test
    void timeoutSweep_neverTouchesClosedExecutions() {
        String runId = start("wf-closed");
        SwfDecisionTask decision = service.pollForDecisionTask(DOMAIN, "tl", "d").orElseThrow();
        service.respondDecisionTaskCompleted(decision.getTaskToken(),
                List.of(new Decision("CompleteWorkflowExecution", Map.of("result", "ok"))), null);
        int eventCount = service.getWorkflowExecutionHistory(DOMAIN, "wf-closed", runId, false).size();

        clock.advance(Duration.ofHours(2));
        service.sweep();

        assertEquals(eventCount,
                service.getWorkflowExecutionHistory(DOMAIN, "wf-closed", runId, false).size(),
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
                            DOMAIN, "wf-race", "W", "1", null, null, null, null, null, null, null, null));
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
            assertEquals(1, service.listExecutions(DOMAIN, ExecutionFilter.all(), false).stream()
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

        SwfWorkflowExecution parent = service.describeWorkflowExecution(DOMAIN, "wf-cc-parent", parentRunId);
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
    void listExecutions_separatesOpenFromClosed() {
        start("wf-open");
        String closedRunId = start("wf-done");
        SwfDecisionTask decision = pollFor("wf-done");
        service.respondDecisionTaskCompleted(decision.getTaskToken(),
                List.of(new Decision("CompleteWorkflowExecution", Map.of())), null);

        List<SwfWorkflowExecution> open = service.listExecutions(DOMAIN, ExecutionFilter.all(), false);
        List<SwfWorkflowExecution> closed = service.listExecutions(DOMAIN, ExecutionFilter.all(), true);

        assertTrue(open.stream().anyMatch(e -> "wf-open".equals(e.getWorkflowId())));
        assertFalse(open.stream().anyMatch(e -> "wf-done".equals(e.getWorkflowId())));
        assertTrue(closed.stream()
                .anyMatch(e -> "wf-done".equals(e.getWorkflowId()) && closedRunId.equals(e.getRunId())));
    }

    @Test
    void deprecatedType_cannotStartAnExecutionButRemainsDescribable() {
        service.deprecateWorkflowType(DOMAIN, "W", "1");

        AwsException thrown = assertThrows(AwsException.class, () -> start("wf-dep"));
        assertEquals("TypeDeprecatedFault", thrown.getErrorCode());
        assertNotNull(service.describeWorkflowType(DOMAIN, "W", "1"));

        service.undeprecateWorkflowType(DOMAIN, "W", "1");
        assertNotNull(start("wf-dep"));
    }

    @Test
    void deleteWorkflowType_requiresPriorDeprecation() {
        AwsException thrown = assertThrows(AwsException.class,
                () -> service.deleteWorkflowType(DOMAIN, "W", "1"));
        assertEquals("TypeNotDeprecatedFault", thrown.getErrorCode());

        service.deprecateWorkflowType(DOMAIN, "W", "1");
        service.deleteWorkflowType(DOMAIN, "W", "1");
        assertThrows(AwsException.class, () -> service.describeWorkflowType(DOMAIN, "W", "1"));
    }

    @Test
    void terminatingAParentAppliesTerminateChildPolicyToItsOpenChildren() {
        String parentRunId = start("wf-parent");
        SwfDecisionTask decision = pollFor("wf-parent");
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("StartChildWorkflowExecution", Map.of(
                        "workflowId", "wf-child",
                        "workflowType", Map.of("name", "W", "version", "1")))), null);

        SwfWorkflowExecution child = service.describeWorkflowExecution(DOMAIN, "wf-child", null);
        assertEquals("OPEN", child.getExecutionStatus());
        assertEquals("wf-parent", child.getParentWorkflowId());

        service.terminateWorkflowExecution(DOMAIN, "wf-parent", parentRunId, "stop", null, null);

        child = service.describeWorkflowExecution(DOMAIN, "wf-child", child.getRunId());
        assertEquals("CLOSED", child.getExecutionStatus());
        assertEquals("TERMINATED", child.getCloseStatus());
        assertEquals("CHILD_POLICY_APPLIED", lastAttribute(child, "WorkflowExecutionTerminated", "cause"));
    }

    @Test
    void abandonChildPolicy_leavesChildrenRunning() {
        SwfWorkflowType abandoning = workflowType("Abandon", "1");
        abandoning.setDefaultChildPolicy("ABANDON");
        service.registerWorkflowType(DOMAIN, abandoning);

        String parentRunId = service.startWorkflowExecution(new StartWorkflowExecutionRequest(
                DOMAIN, "wf-abandon", "Abandon", "1", null, null, null, null, null, null, null, null));
        SwfDecisionTask decision = pollFor("wf-abandon");
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("StartChildWorkflowExecution", Map.of(
                        "workflowId", "wf-abandoned-child",
                        "workflowType", Map.of("name", "W", "version", "1")))), null);

        service.terminateWorkflowExecution(DOMAIN, "wf-abandon", parentRunId, null, null, null);

        assertEquals("OPEN", service.describeWorkflowExecution(DOMAIN, "wf-abandoned-child", null)
                .getExecutionStatus());
    }

    @Test
    void requestCancelActivityTask_cancelsAScheduledTaskImmediately() {
        String runId = start("wf-cancel-act");
        scheduleActivity("wf-cancel-act", "act-cancel");

        SwfDecisionTask decision = pokeForDecision("wf-cancel-act");
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("RequestCancelActivityTask", Map.of("activityId", "act-cancel"))), null);

        SwfWorkflowExecution execution = service.describeWorkflowExecution(DOMAIN, "wf-cancel-act", runId);
        assertNotNull(lastAttribute(execution, "ActivityTaskCancelRequested", "activityId"));
        // No worker holds the task, so there is nothing to observe the request: SWF cancels it.
        assertNotNull(lastAttribute(execution, "ActivityTaskCanceled", "scheduledEventId"));
        assertEquals(0, service.openActivityCount(execution));
    }

    @Test
    void activityTokenOnAClosedTask_reportsUnknownActivityWithItsScheduledEventId() {
        start("wf-stale-activity");
        scheduleActivity("wf-stale-activity", "act-stale");
        SwfActivityTask task = service.pollForActivityTask(DOMAIN, "act-tl", "w").orElseThrow();
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
        SwfActivityTask task = service.pollForActivityTask(DOMAIN, "act-tl", "w").orElseThrow();

        SwfDecisionTask decision = pokeForDecision("wf-cancel-started");
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("RequestCancelActivityTask", Map.of("activityId", "act-running"))), null);

        assertTrue(service.recordActivityTaskHeartbeat(task.getTaskToken(), null),
                "the worker learns about the cancellation through its heartbeat");
        service.respondActivityTaskCanceled(task.getTaskToken(), "stopped");

        SwfWorkflowExecution execution = service.describeWorkflowExecution(DOMAIN, "wf-cancel-started", null);
        assertEquals("stopped", lastAttribute(execution, "ActivityTaskCanceled", "details"));
    }

    @Test
    void scheduleActivityTask_withUnknownType_recordsFailureAndKeepsTheExecutionOpen() {
        String runId = start("wf-bad-type");
        SwfDecisionTask decision = service.pollForDecisionTask(DOMAIN, "tl", "d").orElseThrow();

        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("ScheduleActivityTask", Map.of(
                        "activityId", "nope",
                        "activityType", Map.of("name", "Missing", "version", "9")))), null);

        SwfWorkflowExecution execution = service.describeWorkflowExecution(DOMAIN, "wf-bad-type", runId);
        assertEquals("ACTIVITY_TYPE_DOES_NOT_EXIST",
                lastAttribute(execution, "ScheduleActivityTaskFailed", "cause"));
        assertEquals("OPEN", execution.getExecutionStatus());
        assertTrue(service.pollForDecisionTask(DOMAIN, "tl", "d2").isPresent(),
                "a failed decision must give the decider another chance");
    }

    @Test
    void continueAsNew_closesTheRunAndLinksTheSuccessor() {
        String firstRunId = start("wf-can");
        SwfDecisionTask decision = pollFor("wf-can");
        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("ContinueAsNewWorkflowExecution", Map.of("input", "gen-2"))), null);

        SwfWorkflowExecution closed = service.describeWorkflowExecution(DOMAIN, "wf-can", firstRunId);
        assertEquals("CONTINUED_AS_NEW", closed.getCloseStatus());

        String newRunId = (String) lastAttribute(closed, "WorkflowExecutionContinuedAsNew", "newExecutionRunId");
        assertNotNull(newRunId);
        SwfWorkflowExecution successor = service.describeWorkflowExecution(DOMAIN, "wf-can", newRunId);
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

        SwfWorkflowExecution receiver = service.describeWorkflowExecution(DOMAIN, "wf-receiver", null);
        assertEquals("ping", lastAttribute(receiver, "WorkflowExecutionSignaled", "signalName"));
        assertEquals("hello", lastAttribute(receiver, "WorkflowExecutionSignaled", "input"));

        SwfWorkflowExecution sender = service.describeWorkflowExecution(DOMAIN, "wf-sender", null);
        assertNotNull(lastAttribute(sender, "ExternalWorkflowExecutionSignaled", "initiatedEventId"));
    }

    @Test
    void signalUnknownExternalExecution_recordsFailureRatherThanThrowing() {
        start("wf-lonely");
        SwfDecisionTask decision = pollFor("wf-lonely");

        service.respondDecisionTaskCompleted(decision.getTaskToken(), List.of(
                new Decision("SignalExternalWorkflowExecution", Map.of(
                        "workflowId", "not-there", "signalName", "ping"))), null);

        SwfWorkflowExecution execution = service.describeWorkflowExecution(DOMAIN, "wf-lonely", null);
        assertEquals("UNKNOWN_EXTERNAL_WORKFLOW_EXECUTION",
                lastAttribute(execution, "SignalExternalWorkflowExecutionFailed", "cause"));
    }

    @Test
    void registerDomain_rejectsARetentionPeriodOutsideTheAllowedRange() {
        AwsException thrown = assertThrows(AwsException.class,
                () -> service.registerDomain("bad-retention", null, "500", Map.of(), "us-east-1"));
        assertEquals("ValidationException", thrown.getErrorCode());

        // NONE is the documented sentinel for "keep forever" and must be accepted.
        service.registerDomain("no-retention", null, "NONE", Map.of(), "us-east-1");
        assertEquals("NONE", service.describeDomain("no-retention")
                .getWorkflowExecutionRetentionPeriodInDays());
    }

    @Test
    void tagResource_roundTripsThroughTheDomainArn() {
        String arn = service.domainArnFor(service.describeDomain(DOMAIN), "us-east-1");
        assertEquals("arn:aws:swf:us-east-1:000000000000:/domain/" + DOMAIN, arn);

        service.tagResource(arn, Map.of("env", "unit"));
        assertEquals("unit", service.listTagsForResource(arn).get("env"));

        service.untagResource(arn, List.of("env"));
        assertTrue(service.listTagsForResource(arn).isEmpty());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String start(String workflowId) {
        return service.startWorkflowExecution(new StartWorkflowExecutionRequest(
                DOMAIN, workflowId, "W", "1", null, null, null, null, null, null, null, null));
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
        service.signalWorkflowExecution(DOMAIN, workflowId, null, "poke", null);
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
            Optional<SwfDecisionTask> claimed = service.pollForDecisionTask(DOMAIN, "tl", "d");
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
