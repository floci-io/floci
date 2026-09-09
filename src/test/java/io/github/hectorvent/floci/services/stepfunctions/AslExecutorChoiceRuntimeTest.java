package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

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
        executor = new AslExecutor(
                mock(LambdaExecutorService.class),
                mock(LambdaFunctionStore.class),
                mock(DynamoDbService.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class),
                mock(CloudFormationQueryHandler.class),
                mock(Ec2Service.class),
                mock(S3Service.class),
                mock(EcsService.class),
                mock(EcsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerService.class),
                mock(io.github.hectorvent.floci.services.scheduler.SchedulerController.class),
                mapper,
                new JsonataEvaluator(mapper),
                mock(Instance.class),
                mock(EmulatorConfig.class),
                null, null);
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
