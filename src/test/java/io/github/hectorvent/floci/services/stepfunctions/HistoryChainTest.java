package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryChainTest {

    /** The history-event limit of AslExecutor. A counted event past it fails the execution. */
    private static final int MAX_HISTORY_EVENTS = 25_000;

    @Test
    void publishingAfterTheExecutionEndedRecordsNothingAndDoesNotCountTowardsTheLimit() {
        List<HistoryEvent> history = new ArrayList<>();
        var chain = HistoryChain.of(history);
        chain.end("ExecutionSucceeded", Map.of());

        assertDoesNotThrow(() -> {
            for (var i = 0; i < MAX_HISTORY_EVENTS; i++) {
                assertEquals(0L, chain.publish("PassStateEntered", null));
                chain.publishAside("PassStateSucceeded", null);
            }
        });
        assertEquals(1, history.size());
    }

    @Test
    void publishingIntoAHistorySealedByStopExecutionStopsCountingAsWell() {
        var history = new StepFunctionsService.ExecutionHistory();
        var chain = HistoryChain.of(history);
        var branch = chain.fork();
        history.sealWith("ExecutionAborted", Map.of());

        assertDoesNotThrow(() -> {
            for (var i = 0; i < MAX_HISTORY_EVENTS; i++) {
                assertEquals(0L, chain.publish("PassStateEntered", null));
                assertEquals(0L, branch.publish("PassStateEntered", null));
            }
        });
        assertEquals(1, history.size());
    }
}
