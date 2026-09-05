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
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end coverage for nested {@code states:startExecution}: the parent's resolved {@code Input} and
 * {@code Name} reach the child {@code StartExecution} correctly. Verifies the fix: a
 * {@code States.JsonToString} Input is passed as JSON text (child {@code $} becomes an object), a plain
 * string stays a string, {@code Name}/{@code Name.$} are honored (previously ignored), and a supplied
 * {@code Name} that does not resolve to a non-empty string fails rather than silently generating one.
 *
 * <p>CI-only: constructing {@link AslExecutor} pulls in Vert.x, unavailable in the offline sandbox. The
 * encoding/provenance logic itself is covered locally by {@link NestedExecutionInputTest}.
 */
class AslExecutorNestedStartExecutionTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private AslExecutor executor;
    private StepFunctionsService childSfn;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        childSfn = mock(StepFunctionsService.class);
        Instance<StepFunctionsService> sfnInstance = mock(Instance.class);
        when(sfnInstance.get()).thenReturn(childSfn);
        Execution childExec = new Execution();
        childExec.setExecutionArn("arn:aws:states:us-east-1:000000000000:execution:child:e1");
        childExec.setStatus("RUNNING");
        childExec.setStartDate(1.0);
        when(childSfn.startExecution(any(), any(), any(), any())).thenReturn(childExec);

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
                sfnInstance,
                mock(EmulatorConfig.class),
                null, null);
    }

    private Execution runParent(String parentDefinition, String input) {
        StateMachine sm = new StateMachine();
        sm.setName("parent");
        sm.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:parent");
        sm.setRoleArn("arn:aws:iam::000000000000:role/r");
        sm.setDefinition(parentDefinition);
        Execution exec = new Execution();
        exec.setName("parent-exec");
        exec.setExecutionArn("arn:aws:states:us-east-1:000000000000:execution:parent:pe");
        exec.setStateMachineArn(sm.getStateMachineArn());
        exec.setInput(input);
        executor.executeSync(sm, exec, new ArrayList<HistoryEvent>(), (u, e) -> {
        });
        return exec;
    }

    /** Returns {capturedName, capturedChildInput} from the single child startExecution call. */
    private String[] captureChildStart(String parentDefinition, String input) {
        runParent(parentDefinition, input);
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> childInput = ArgumentCaptor.forClass(String.class);
        verify(childSfn).startExecution(any(), name.capture(), childInput.capture(), any());
        return new String[]{name.getValue(), childInput.getValue()};
    }

    private static String parent(String parametersJson) {
        return "{\"StartAt\":\"Nest\",\"States\":{\"Nest\":{\"Type\":\"Task\","
                + "\"Resource\":\"arn:aws:states:::states:startExecution\","
                + "\"Parameters\":" + parametersJson + ",\"End\":true}}}";
    }

    private static final String CHILD_ARN =
            "arn:aws:states:us-east-1:000000000000:stateMachine:child";

    @Test
    void jsonToStringInputBecomesObjectAndNameIsHonored() throws Exception {
        String[] captured = captureChildStart(
                parent("{\"StateMachineArn\":\"" + CHILD_ARN + "\","
                        + "\"Input.$\":\"States.JsonToString($.payload)\",\"Name\":\"child-run-1\"}"),
                "{\"payload\":{\"a\":1}}");
        assertEquals("child-run-1", captured[0], "Name must be honored (was ignored/null before)");
        assertTrue(mapper.readTree(captured[1]).isObject(), "JsonToString Input must reach child as an object");
        assertEquals(1, mapper.readTree(captured[1]).get("a").asInt());
    }

    @Test
    void objectPathInputStaysObject() throws Exception {
        String[] captured = captureChildStart(
                parent("{\"StateMachineArn\":\"" + CHILD_ARN + "\",\"Input.$\":\"$.payload\"}"),
                "{\"payload\":{\"a\":1}}");
        assertNull(captured[0], "no Name -> child gets a generated name (null passed through)");
        assertTrue(mapper.readTree(captured[1]).isObject());
    }

    @Test
    void plainStringInputStaysString() throws Exception {
        String[] captured = captureChildStart(
                parent("{\"StateMachineArn\":\"" + CHILD_ARN + "\",\"Input.$\":\"$.s\"}"),
                "{\"s\":\"hello\"}");
        assertTrue(mapper.readTree(captured[1]).isTextual(), "a plain string Input must not be turned into an object");
        assertEquals("hello", mapper.readTree(captured[1]).asText());
    }

    @Test
    void nameDollarIsHonored() throws Exception {
        String[] captured = captureChildStart(
                parent("{\"StateMachineArn\":\"" + CHILD_ARN + "\",\"Input\":{},\"Name.$\":\"$.nm\"}"),
                "{\"nm\":\"dynamic-name\"}");
        assertEquals("dynamic-name", captured[0]);
    }

    @Test
    void suppliedNonStringNameFailsExecutionAndDoesNotLaunch() {
        // A supplied Name that does not resolve to a non-empty string is a runtime error, not a silent
        // fall-through to a generated name; the child must not be launched.
        Execution exec = runParent(
                parent("{\"StateMachineArn\":\"" + CHILD_ARN + "\",\"Input\":{},\"Name\":{}}"),
                "{}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("States.Runtime", exec.getError());
        verify(childSfn, never()).startExecution(any(), any(), any(), any());
    }
}
