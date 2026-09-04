package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    // ── Nested StartExecution refusal → typed, catchable error on the optimized states:startExecution
    //    path. AWS reports StepFunctions.<Code>Exception here (the aws-sdk:sfn: path uses Sfn.<Code>),
    //    never the uncatchable States.Runtime it collapsed to before this fix.

    private static final String PARAMS = "{\"StateMachineArn\":\"" + CHILD_ARN + "\"}";

    private static String parentMode(String mode) {
        return "{\"StartAt\":\"Nest\",\"States\":{"
                + "\"Nest\":{\"Type\":\"Task\","
                + "\"Resource\":\"arn:aws:states:::states:startExecution" + mode + "\","
                + "\"Parameters\":" + PARAMS + ",\"End\":true}}}";
    }

    private static String parentCatch(String errorEquals) {
        return "{\"StartAt\":\"Nest\",\"States\":{"
                + "\"Nest\":{\"Type\":\"Task\","
                + "\"Resource\":\"arn:aws:states:::states:startExecution\","
                + "\"Parameters\":" + PARAMS + ","
                + "\"Catch\":[{\"ErrorEquals\":[\"" + errorEquals + "\"],\"Next\":\"Recover\"}],\"End\":true},"
                + "\"Recover\":{\"Type\":\"Pass\",\"End\":true}}}";
    }

    private static String parentRetry(String errorEquals) {
        return "{\"StartAt\":\"Nest\",\"States\":{"
                + "\"Nest\":{\"Type\":\"Task\","
                + "\"Resource\":\"arn:aws:states:::states:startExecution\","
                + "\"Parameters\":" + PARAMS + ","
                + "\"Retry\":[{\"ErrorEquals\":[\"" + errorEquals + "\"],\"MaxAttempts\":1,\"IntervalSeconds\":0}],"
                + "\"End\":true}}}";
    }

    private void childRefuses(String errorCode) {
        doThrow(new AwsException(errorCode, "Execution already exists: " + CHILD_ARN, 400))
                .when(childSfn).startExecution(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".sync", ".sync:2"})
    void childRefusalIsATypedFailure(String mode) {
        childRefuses("ExecutionAlreadyExists");
        Execution exec = runParent(parentMode(mode), "{}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("StepFunctions.ExecutionAlreadyExistsException", exec.getError());
    }

    @Test
    void childRefusalIsCaughtByItsTypedName() {
        childRefuses("ExecutionAlreadyExists");
        Execution exec = runParent(parentCatch("StepFunctions.ExecutionAlreadyExistsException"), "{}");
        assertEquals("SUCCEEDED", exec.getStatus());
    }

    @Test
    void childRefusalIsCaughtByStatesTaskFailed() {
        childRefuses("ExecutionAlreadyExists");
        Execution exec = runParent(parentCatch("States.TaskFailed"), "{}");
        assertEquals("SUCCEEDED", exec.getStatus());
    }

    @Test
    void childRefusalIsRetriedByItsTypedName() {
        childRefuses("ExecutionAlreadyExists");
        Execution exec = runParent(parentRetry("StepFunctions.ExecutionAlreadyExistsException"), "{}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("StepFunctions.ExecutionAlreadyExistsException", exec.getError());
        // MaxAttempts=1: the initial attempt plus one retry both reach the child.
        verify(childSfn, times(2)).startExecution(any(), any(), any(), any());
    }

    @Test
    void sfnPrefixedCatchDoesNotMatchTheOptimizedPath() {
        // The aws-sdk integration's Sfn. prefix must NOT catch the optimized path's StepFunctions. error
        // (catchMatches is exact for non-States names), so the task still fails with the typed name.
        childRefuses("ExecutionAlreadyExists");
        Execution exec = runParent(parentCatch("Sfn.ExecutionAlreadyExistsException"), "{}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("StepFunctions.ExecutionAlreadyExistsException", exec.getError());
    }

    @Test
    void missingChildIsATypedFailure() {
        childRefuses("StateMachineDoesNotExist");
        Execution exec = runParent(parentMode(""), "{}");
        assertEquals("FAILED", exec.getStatus());
        assertEquals("StepFunctions.StateMachineDoesNotExistException", exec.getError());
    }

    @ParameterizedTest
    @ValueSource(strings = {".sync", ".sync:2"})
    void syncChildSuccessIsUndisturbedByTheWrap(String mode) throws Exception {
        // Regression guard: wrapping the start call in try/catch must not disturb the .sync/.sync:2
        // success branches. The child starts RUNNING (setUp) then the poll observes it SUCCEEDED.
        Execution done = new Execution();
        done.setExecutionArn("arn:aws:states:us-east-1:000000000000:execution:child:e1");
        done.setStateMachineArn(CHILD_ARN);
        done.setName("e1");
        done.setStatus("SUCCEEDED");
        done.setStartDate(1.0);
        done.setStopDate(2.0);
        done.setOutput("{\"ok\":true}");
        when(childSfn.describeExecution(any())).thenReturn(done);

        Execution exec = runParent(parentMode(mode), "{}");
        assertEquals("SUCCEEDED", exec.getStatus());
        var output = mapper.readTree(exec.getOutput());
        if (".sync:2".equals(mode)) {
            assertTrue(output.path("ok").asBoolean(), "sync:2 returns the parsed child output");
        } else {
            assertEquals("SUCCEEDED", output.path("status").asText(), "sync returns the execution envelope");
            assertEquals("{\"ok\":true}", output.path("output").asText(), "envelope output is the child output JSON string");
        }
    }
}
