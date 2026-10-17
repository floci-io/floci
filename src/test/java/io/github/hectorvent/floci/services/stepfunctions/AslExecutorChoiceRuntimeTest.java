package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end Choice-state routing through the full {@code executeSync} loop, covering the
 * {@link AslExecutor} delegation to {@link ChoiceOperators} and the mapping of a
 * {@code ChoiceEvaluationException} to a {@code States.Runtime} execution failure. Complements the
 * unit-level {@link ChoiceOperatorsTest} by exercising the actual wiring the comparator fix depends on.
 *
 * <p>Plain JUnit5 (mirrors {@code AslExecutorIntrinsicFunctionsTest}'s harness), executed by CI only:
 * it cannot run in the offline sandbox because a full {@code AslExecutor} compile pulls in Vert.x.
 */
class AslExecutorChoiceRuntimeTest {

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

    private static final String GTE_MACHINE = """
            {
              "StartAt": "Pick",
              "States": {
                "Pick": {
                  "Type": "Choice",
                  "Choices": [{"Variable": "$.a", "NumericGreaterThanEqualsPath": "$.b", "Next": "TAKEN"}],
                  "Default": "FELL"
                },
                "TAKEN": {"Type": "Pass", "End": true},
                "FELL": {"Type": "Fail", "Error": "FELL", "Cause": "took the Default branch"}
              }
            }
            """;

    @Test
    void numericGreaterThanEqualsPath_routesToTakenWhenTrue() {
        // 5 >= 3: previously the unsupported comparator was silently false and the machine took Default (FELL, a Fail).
        assertEquals("SUCCEEDED", run(GTE_MACHINE, "{\"a\":5,\"b\":3}").getStatus());
        assertEquals("SUCCEEDED", run(GTE_MACHINE, "{\"a\":3,\"b\":3}").getStatus());
    }

    @Test
    void numericGreaterThanEqualsPath_routesToDefaultWhenFalse() {
        Execution exec = run(GTE_MACHINE, "{\"a\":2,\"b\":3}"); // 2 >= 3 is false -> Default -> FELL (Fail)
        assertEquals("FAILED", exec.getStatus());
    }

    @Test
    void contextObjectVariableRoutesUsingOriginalExecutionInput() {
        Execution exec = run("""
                {
                  "StartAt": "Pick",
                  "States": {
                    "Pick": {
                      "Type": "Choice",
                      "InputPath": "$.scoped",
                      "Choices": [{
                        "Variable": "$$.Execution.Input.token",
                        "StringEquals": "keep",
                        "Next": "TAKEN"
                      }],
                      "Default": "FELL"
                    },
                    "TAKEN": {"Type": "Pass", "End": true},
                    "FELL": {"Type": "Fail", "Error": "FELL"}
                  }
                }
                """, "{\"token\":\"keep\",\"scoped\":{\"token\":\"wrong\"}}");
        assertEquals("SUCCEEDED", exec.getStatus());
    }

    @Test
    void contextObjectPathOperandRoutesUsingOriginalExecutionInput() {
        Execution exec = run("""
                {
                  "StartAt": "Pick",
                  "States": {
                    "Pick": {
                      "Type": "Choice",
                      "InputPath": "$.scoped",
                      "Choices": [{
                        "Variable": "$.district_id",
                        "StringEqualsPath": "$$.Execution.Input.expected_district_id",
                        "Next": "TAKEN"
                      }],
                      "Default": "FELL"
                    },
                    "TAKEN": {"Type": "Pass", "End": true},
                    "FELL": {"Type": "Fail", "Error": "FELL"}
                  }
                }
                """, "{\"expected_district_id\":\"42\",\"scoped\":{\"district_id\":\"42\"}}");
        assertEquals("SUCCEEDED", exec.getStatus());
    }

    @Test
    void unknownComparatorFailsExecutionWithStatesRuntime() {
        // executeSync bypasses create-time validation, so the runtime backstop must fire loudly.
        Execution exec = run("""
                {
                  "StartAt": "Pick",
                  "States": {
                    "Pick": {
                      "Type": "Choice",
                      "Choices": [{"Variable": "$.a", "NumericSpicyThan": 1, "Next": "TAKEN"}],
                      "Default": "FELL"
                    },
                    "TAKEN": {"Type": "Pass", "End": true},
                    "FELL": {"Type": "Pass", "End": true}
                  }
                }
                """, "{\"a\":5}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.Runtime", exec.getError());
    }

    @Test
    void missingPathFailsExecutionWithStatesRuntime() {
        Execution exec = run("""
                {
                  "StartAt": "Pick",
                  "States": {
                    "Pick": {
                      "Type": "Choice",
                      "Choices": [{"Variable": "$.missing", "NumericGreaterThan": 1, "Next": "TAKEN"}],
                      "Default": "FELL"
                    },
                    "TAKEN": {"Type": "Pass", "End": true},
                    "FELL": {"Type": "Pass", "End": true}
                  }
                }
                """, "{\"a\":5}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.Runtime", exec.getError());
    }

    private Execution run(String definition, String input) {
        StateMachine sm = new StateMachine();
        sm.setName("choice-test");
        sm.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:choice-test");
        sm.setRoleArn("arn:aws:iam::000000000000:role/test-role");
        sm.setDefinition(definition);

        Execution exec = new Execution();
        exec.setName("choice-test-execution");
        exec.setExecutionArn(
                "arn:aws:states:us-east-1:000000000000:execution:choice-test:choice-test-execution");
        exec.setStateMachineArn(sm.getStateMachineArn());
        exec.setInput(input);

        List<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(sm, exec, history, (updated, events) -> {
        });
        return exec;
    }
}
