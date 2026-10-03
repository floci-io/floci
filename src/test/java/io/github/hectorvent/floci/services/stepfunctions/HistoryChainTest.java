package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryChainTest {

    /** The history-event limit of AslExecutor. A counted event past it fails the execution. */
    private static final int MAX_HISTORY_EVENTS = 25_000;

    @Test
    void publishingAfterTheExecutionEndedRecordsNothingAndDoesNotCountTowardsTheLimit() {
        List<HistoryEvent> history = new ArrayList<>();
        HistoryChain chain = HistoryChain.of(history);
        chain.end("ExecutionSucceeded", Map.of());

        assertDoesNotThrow(() -> {
            for (int i = 0; i < MAX_HISTORY_EVENTS; i++) {
                assertEquals(0L, chain.publish("PassStateEntered", null));
                chain.publishAside("PassStateSucceeded", null);
            }
        });
        assertEquals(1, history.size());
    }

    @Test
    void publishingIntoAHistorySealedByStopExecutionStopsCountingAsWell() {
        StepFunctionsService.ExecutionHistory history = new StepFunctionsService.ExecutionHistory();
        HistoryChain chain = HistoryChain.of(history);
        HistoryChain branch = chain.fork();
        history.sealWith("ExecutionAborted", Map.of());

        assertDoesNotThrow(() -> {
            for (int i = 0; i < MAX_HISTORY_EVENTS; i++) {
                assertEquals(0L, chain.publish("PassStateEntered", null));
                assertEquals(0L, branch.publish("PassStateEntered", null));
            }
        });
        assertEquals(1, history.size());
    }

    @Test
    void aStateCutAfterItsEnteredEventIsReportedAndRecordsNoExitedEvent() {
        List<HistoryEvent> history = new ArrayList<>();
        HistoryChain branch = HistoryChain.of(history).fork();
        branch.publishStateEntered("Task", "TaskStateEntered", null);

        assertEquals("Task", branch.abandonInState());
        branch.publishStateExited("TaskStateExited", null);

        assertEquals(List.of("TaskStateEntered"), typesOf(history));
    }

    @Test
    void aStateThatExitedBeforeTheCutIsNotReported() {
        List<HistoryEvent> history = new ArrayList<>();
        HistoryChain branch = HistoryChain.of(history).fork();
        branch.publishStateEntered("Task", "TaskStateEntered", null);
        branch.publishStateExited("TaskStateExited", null);

        assertNull(branch.abandonInState());
        assertEquals(List.of("TaskStateEntered", "TaskStateExited"), typesOf(history));
    }

    @Test
    void aTaskThatEndsItsBranchBeforeTheCutIsNotReportedTwice() {
        List<HistoryEvent> history = new ArrayList<>();
        HistoryChain branch = HistoryChain.of(history).fork();
        branch.publishStateEntered("Task", "TaskStateEntered", null);
        branch.leaveStateAside("TaskStateAborted", null);

        assertNull(branch.abandonInState());
        assertEquals(List.of("TaskStateEntered", "TaskStateAborted"), typesOf(history));
    }

    @Test
    void aTaskCutBeforeItEndsItsBranchIsReportedByTheCutOnly() {
        List<HistoryEvent> history = new ArrayList<>();
        HistoryChain branch = HistoryChain.of(history).fork();
        branch.publishStateEntered("Task", "TaskStateEntered", null);

        assertEquals("Task", branch.abandonInState());
        branch.leaveStateAside("TaskStateAborted", null);

        assertEquals(List.of("TaskStateEntered"), typesOf(history));
    }

    @Test
    void anIterationThatEndedBeforeTheCutIsReportedEndedAndNotCut() {
        List<HistoryEvent> history = new ArrayList<>();
        HistoryChain iteration = HistoryChain.of(history).fork();
        iteration.publishIterationStart("MapIterationStarted", null);
        iteration.publishStateEntered("Task", "TaskStateEntered", null);
        iteration.publishStateExited("TaskStateExited", null);
        iteration.publishIterationEnd("MapIterationSucceeded", null);

        HistoryChain.IterationCut cut = iteration.abandonIteration();

        assertTrue(cut.started());
        assertTrue(cut.ended());
        assertFalse(cut.recorded());
        assertNull(cut.stateType());
        assertEquals(List.of("MapIterationStarted", "TaskStateEntered", "TaskStateExited", "MapIterationSucceeded"),
                typesOf(history));
    }

    @Test
    void anIterationCutBeforeItEndsIsReportedInItsStateAndRecordsNoEnd() {
        List<HistoryEvent> history = new ArrayList<>();
        HistoryChain iteration = HistoryChain.of(history).fork();
        iteration.publishIterationStart("MapIterationStarted", null);
        iteration.publishStateEntered("Task", "TaskStateEntered", null);

        HistoryChain.IterationCut cut = iteration.abandonIteration();
        iteration.publishStateExited("TaskStateExited", null);
        iteration.publishIterationEnd("MapIterationSucceeded", null);

        assertTrue(cut.recorded());
        assertFalse(cut.ended());
        assertEquals("Task", cut.stateType());
        assertEquals(List.of("MapIterationStarted", "TaskStateEntered"), typesOf(history));
    }

    @Test
    void anIterationCutBeforeItStartsIsNotReportedAndRecordsNoStart() {
        List<HistoryEvent> history = new ArrayList<>();
        HistoryChain iteration = HistoryChain.of(history).fork();

        HistoryChain.IterationCut cut = iteration.abandonIteration();
        iteration.publishIterationStart("MapIterationStarted", null);
        iteration.publishStateEntered("Task", "TaskStateEntered", null);

        assertFalse(cut.started());
        assertFalse(cut.recorded());
        assertEquals(List.of(), typesOf(history));
    }

    @Test
    void aCutRacingAnIterationNeverDisagreesWithItsRecordedStartAndEnd() throws Exception {
        for (int round = 0; round < 2_000; round++) {
            List<HistoryEvent> history = Collections.synchronizedList(new ArrayList<>());
            HistoryChain iteration = HistoryChain.of(history).fork();
            CountDownLatch start = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                iteration.publishIterationStart("MapIterationStarted", null);
                iteration.publishStateEntered("Task", "TaskStateEntered", null);
                iteration.publishStateExited("TaskStateExited", null);
                iteration.publishIterationEnd("MapIterationSucceeded", null);
            });
            worker.start();
            start.countDown();
            HistoryChain.IterationCut cut = iteration.abandonIteration();
            worker.join();

            List<String> types = typesOf(history);
            assertEquals(types.contains("MapIterationStarted"), cut.started(), "round " + round + ": " + cut + " with " + types);
            assertEquals(types.contains("MapIterationSucceeded"), cut.ended(), "round " + round + ": " + cut + " with " + types);
            boolean cutMidState = types.contains("TaskStateEntered") && !types.contains("TaskStateExited");
            assertEquals(cutMidState, "Task".equals(cut.stateType()), "round " + round + ": " + cut + " with " + types);
        }
    }

    @Test
    void aCutRacingAStateNeverDisagreesWithTheRecordedEvents() throws Exception {
        for (int round = 0; round < 2_000; round++) {
            List<HistoryEvent> history = Collections.synchronizedList(new ArrayList<>());
            HistoryChain branch = HistoryChain.of(history).fork();
            CountDownLatch start = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                branch.publishStateEntered("Task", "TaskStateEntered", null);
                branch.publishStateExited("TaskStateExited", null);
            });
            worker.start();
            start.countDown();
            String reported = branch.abandonInState();
            worker.join();

            List<String> types = typesOf(history);
            boolean cutMidState = types.contains("TaskStateEntered") && !types.contains("TaskStateExited");
            assertEquals(cutMidState, "Task".equals(reported), "round " + round + ": " + reported + " with " + types);
        }
    }

    private static List<String> typesOf(List<HistoryEvent> history) {
        synchronized (history) {
            return history.stream().map(HistoryEvent::getType).toList();
        }
    }
}
