package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end coverage for the ResultPath fix: a non-applicable ResultPath fails the execution with
 * {@code States.ResultPathMatchFailure} (was a silent input discard), the error is catchable via a
 * Parallel state's {@code Catch}, object inputs merge unchanged, and a bracket-index ResultPath
 * (e.g. {@code $[1].payload}) merges into the addressed array element instead of discarding the
 * array input.
 *
 * <p>CI-only: constructing {@link AslExecutor} pulls in Vert.x (absent in the offline sandbox). The merge
 * logic is covered locally by {@link ResultPathMergeTest}.
 */
class AslExecutorResultPathTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AslExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        executor = AslExecutorFixture.builder()
                .objectMapper(mapper)
                .customResourceLiveness(null)
                .build();
    }

    private Execution run(String definition, String input) {
        StateMachine sm = new StateMachine();
        sm.setName("rp");
        sm.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:rp");
        sm.setRoleArn("arn:aws:iam::000000000000:role/r");
        sm.setDefinition(definition);
        Execution exec = new Execution();
        exec.setName("rp-exec");
        exec.setExecutionArn("arn:aws:states:us-east-1:000000000000:execution:rp:e");
        exec.setStateMachineArn(sm.getStateMachineArn());
        exec.setInput(input);
        executor.executeSync(sm, exec, new ArrayList<HistoryEvent>(), (u, e) -> {
        });
        return exec;
    }

    @Test
    void nonObjectInputWithObjectResultPathFailsExecution() {
        Execution exec = run(
                "{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Pass\",\"ResultPath\":\"$.x\",\"End\":true}}}",
                "\"just-a-string\"");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.ResultPathMatchFailure", exec.getError());
    }

    @Test
    void objectInputMergesNormally() {
        Execution exec = run(
                "{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Pass\",\"Result\":{\"r\":1},"
                        + "\"ResultPath\":\"$.x\",\"End\":true}}}",
                "{\"k\":1}");
        assertEquals("SUCCEEDED", exec.getStatus());
    }

    @Test
    void resultPathMatchFailureIsUncaughtInParallelWithoutCatch() {
        Execution exec = run(
                "{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Parallel\","
                        + "\"Branches\":[{\"StartAt\":\"B\",\"States\":{\"B\":{\"Type\":\"Pass\",\"End\":true}}}],"
                        + "\"ResultPath\":\"$.x\",\"End\":true}}}",
                "\"just-a-string\"");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.ResultPathMatchFailure", exec.getError());
    }

    @Test
    void resultPathMatchFailureIsCatchableInParallel() {
        Execution exec = run(
                "{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Parallel\","
                        + "\"Branches\":[{\"StartAt\":\"B\",\"States\":{\"B\":{\"Type\":\"Pass\",\"End\":true}}}],"
                        + "\"ResultPath\":\"$.x\","
                        + "\"Catch\":[{\"ErrorEquals\":[\"States.ResultPathMatchFailure\"],\"Next\":\"Recover\"}],"
                        + "\"End\":true},"
                        + "\"Recover\":{\"Type\":\"Pass\",\"End\":true}}}",
                "\"just-a-string\"");
        assertEquals("SUCCEEDED", exec.getStatus());
    }

    @Test
    void arrayIndexResultPathMergesIntoIndexedElementAndKeepsArrayInput() throws Exception {
        Execution exec = run(
                "{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Pass\",\"Result\":{\"subject_status\":1},"
                        + "\"ResultPath\":\"$[1].payload\",\"End\":true}}}",
                "[{\"a\":1},{\"id\":7},{\"c\":3},4]");
        assertEquals("SUCCEEDED", exec.getStatus());
        assertEquals(mapper.readTree("[{\"a\":1},{\"id\":7,\"payload\":{\"subject_status\":1}},{\"c\":3},4]"),
                mapper.readTree(exec.getOutput()));
    }

    @Test
    void arrayIndexOutOfBoundsResultPathFailsExecution() {
        Execution exec = run(
                "{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Pass\",\"Result\":{\"x\":1},"
                        + "\"ResultPath\":\"$[10].payload\",\"End\":true}}}",
                "[{\"a\":1},{\"id\":7},{\"c\":3},4]");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.ResultPathMatchFailure", exec.getError());
    }

    @Test
    void catcherResultPathFailureReportsResultPathMatchFailureNotRuntime() {
        // A matched Catcher whose own ResultPath cannot apply to the (non-object) input must terminate
        // with States.ResultPathMatchFailure, not a relabeled States.Runtime from the outer handler.
        Execution exec = run(
                "{\"StartAt\":\"P\",\"States\":{\"P\":{\"Type\":\"Parallel\","
                        + "\"Branches\":[{\"StartAt\":\"F\",\"States\":{\"F\":{\"Type\":\"Fail\","
                        + "\"Error\":\"BranchErr\",\"Cause\":\"x\"}}}],"
                        + "\"Catch\":[{\"ErrorEquals\":[\"States.ALL\"],\"Next\":\"Recover\",\"ResultPath\":\"$.error\"}],"
                        + "\"End\":true},"
                        + "\"Recover\":{\"Type\":\"Pass\",\"End\":true}}}",
                "\"just-a-string\"");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.ResultPathMatchFailure", exec.getError());
    }
}
