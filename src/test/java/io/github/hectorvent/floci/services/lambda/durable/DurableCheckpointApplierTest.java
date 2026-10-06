package io.github.hectorvent.floci.services.lambda.durable;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableErrorObject;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecution;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperation;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationAction;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationStatus;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationType;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationUpdate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The batch rules of CheckpointDurableExecution, and that a rejected batch changes nothing. */
class DurableCheckpointApplierTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final String EXECUTION_ARN =
            "arn:aws:lambda:us-east-1:000000000000:function:fn:1/durable-execution/exec/exec-id";

    @Test
    void executionUpdateMustBeTheOnlyOneAndTheLast() {
        assertRejected(List.of(executionSucceed(), executionSucceed()), "Cannot checkpoint multiple EXECUTION updates.");
        assertRejected(List.of(executionSucceed(), stepStart("s1", null)), "EXECUTION checkpoint must be the last update.");
    }

    @Test
    void anExecutionCloseCarriesOnlyItsOwnResultKind() {
        DurableErrorObject error = DurableErrorObject.of("m", "T");
        assertRejected(List.of(new DurableOperationUpdate("result", null, null, DurableOperationType.EXECUTION, null,
                DurableOperationAction.SUCCEED, "\"p\"", error, null, null, null, null, null)),
                "Cannot provide an Error for SUCCEED action.");
        assertRejected(List.of(new DurableOperationUpdate("result", null, null, DurableOperationType.EXECUTION, null,
                DurableOperationAction.FAIL, "\"p\"", error, null, null, null, null, null)),
                "Cannot provide a Payload for FAIL action.");
    }

    @Test
    void anExecutionErrorHasTheSameSizeLimitAsAnOperationError() {
        DurableErrorObject large = DurableErrorObject.of("x".repeat(DurableCheckpointApplier.MAX_ERROR_BYTES), "T");
        assertRejected(List.of(new DurableOperationUpdate("result", null, null, DurableOperationType.EXECUTION, null,
                DurableOperationAction.FAIL, null, large, null, null, null, null, null)),
                "Error object size must be less than 32768 bytes.");
    }

    @Test
    void duplicateIdsAreRejectedUnlessAStartIsClosedInTheSameBatch() {
        assertRejected(List.of(stepStart("s1", null), stepStart("s1", null)),
                "Cannot update the same operation twice in a single request.");
        assertRejected(List.of(waitStart("w1"), waitStart("w1")),
                "Cannot update the same operation twice in a single request.");
        DurableExecution execution = execution();
        DurableCheckpointApplier.apply(execution, List.of(stepStart("s1", null), update("s1", null,
                DurableOperationType.STEP, DurableOperationAction.SUCCEED, "1")), NOW);
        assertEquals(DurableOperationStatus.SUCCEEDED, execution.getOperations().get("s1").getStatus());
    }

    @Test
    void parentMustBeAContextAndMetadataMustStayConsistent() {
        assertRejected(List.of(stepStart("s1", "missing")), "Invalid parent operation id.");
        DurableExecution execution = execution();
        DurableCheckpointApplier.apply(execution, List.of(stepStart("s1", null)), NOW);
        assertEquals("Inconsistent operation type.", reject(execution, List.of(waitStart("s1"))));
        assertEquals("Inconsistent parent operation id.", reject(execution, List.of(stepStart("ctx", null),
                update("s1", "ctx", DurableOperationType.STEP, DurableOperationAction.SUCCEED, "1"))));
    }

    @Test
    void stepTransitionsAreGuarded() {
        DurableExecution execution = execution();
        DurableCheckpointApplier.apply(execution, List.of(stepStart("s1", null)), NOW);
        assertEquals("Invalid current STEP state to start.", reject(execution, List.of(stepStart("s1", null))));
        assertEquals("Cannot provide an Error for SUCCEED action.", reject(execution, List.of(
                new DurableOperationUpdate("s1", null, null, DurableOperationType.STEP, null,
                        DurableOperationAction.SUCCEED, null, DurableErrorObject.of("x", "y"), null, null, null, null, null))));
        assertEquals("Invalid StepOptions for the given action.", reject(execution, List.of(
                update("s1", null, DurableOperationType.STEP, DurableOperationAction.RETRY, null))));
        DurableCheckpointApplier.apply(execution, List.of(update("s1", null, DurableOperationType.STEP,
                DurableOperationAction.FAIL, null)), NOW);
        assertEquals("Invalid current STEP state to close.", reject(execution, List.of(
                update("s1", null, DurableOperationType.STEP, DurableOperationAction.SUCCEED, "1"))));
    }

    @Test
    void waitsNeedOptionsAndCancelOnlyWhileStarted() {
        assertRejected(List.of(update("w1", null, DurableOperationType.WAIT, DurableOperationAction.START, null)),
                "Update for WAIT operation requires WaitOptions.");
        assertRejected(List.of(update("w1", null, DurableOperationType.WAIT, DurableOperationAction.CANCEL, null)),
                "Cannot cancel a WAIT that does not exist or has already completed.");
        DurableExecution execution = execution();
        DurableCheckpointApplier.apply(execution, List.of(waitStart("w1")), NOW);
        assertEquals("Cannot start a WAIT that already exist.", reject(execution, List.of(waitStart("w1"))));
        DurableCheckpointApplier.apply(execution, List.of(update("w1", null, DurableOperationType.WAIT,
                DurableOperationAction.CANCEL, null)), NOW);
        assertEquals(DurableOperationStatus.CANCELLED, execution.getOperations().get("w1").getStatus());
    }

    @Test
    void optionsOutsideTheirRangeAreValidationErrorsNamingTheUpdate() {
        AwsException wait = assertThrows(AwsException.class, () -> DurableWire.parseUpdates(
                List.of(Map.of("Id", "w1", "Type", "WAIT", "Action", "START", "WaitOptions", Map.of("WaitSeconds", 0)))));
        assertEquals("ValidationException", wait.getErrorCode());
        assertEquals("1 validation error detected: Value '0' at 'updates.1.member.waitOptions.waitSeconds' failed to "
                + "satisfy constraint: Member must have value greater than or equal to 1", wait.getMessage());

        AwsException callback = assertThrows(AwsException.class, () -> DurableWire.parseUpdates(List.of(
                Map.of("Id", "c1", "Type", "CALLBACK", "Action", "START"),
                Map.of("Id", "c2", "Type", "CALLBACK", "Action", "START",
                        "CallbackOptions", Map.of("HeartbeatTimeoutSeconds", 100_000_000)))));
        assertEquals("1 validation error detected: Value '100000000' at "
                + "'updates.2.member.callbackOptions.heartbeatTimeoutSeconds' failed to satisfy constraint: Member "
                + "must have value less than or equal to 99999999", callback.getMessage());
    }

    @Test
    void chainedInvokesAreNotSupportedYet() {
        assertRejected(List.of(update("i1", null, DurableOperationType.CHAINED_INVOKE, DurableOperationAction.START,
                null)), "CHAINED_INVOKE operations are not supported yet");
    }

    @Test
    void aCallbackStartsWithAnIdThatNamesItsExecution() {
        DurableExecution execution = execution();
        DurableCheckpointApplier.apply(execution, List.of(callbackStart("c1", 30, 10)), NOW);

        DurableOperation callback = execution.getOperations().get("c1");
        assertEquals(DurableOperationStatus.STARTED, callback.getStatus());
        assertEquals(EXECUTION_ARN, DurableTokens.callbackExecutionArn(callback.getCallbackId()).orElseThrow());
        assertEquals(NOW + 30_000, callback.getCallbackDeadline());
        assertEquals(NOW + 10_000, callback.getHeartbeatDeadline());
        assertEquals(Map.of("CallbackStartedDetails", Map.of("CallbackId", callback.getCallbackId(), "Timeout", 30,
                "HeartbeatTimeout", 10)), execution.getHistory().get(0).getDetails());

        assertEquals("Cannot start a CALLBACK that already exist.", reject(execution, List.of(callbackStart("c1", 0, 0))));
        assertEquals("Invalid action for the given operation type.", reject(execution, List.of(
                update("c1", null, DurableOperationType.CALLBACK, DurableOperationAction.SUCCEED, "\"r\""))));
    }

    @Test
    void aCallbackTimesOutOnTheDeadlineItMissesFirst() {
        DurableExecution execution = execution();
        DurableCheckpointApplier.apply(execution, List.of(callbackStart("slow", 5, 0), callbackStart("silent", 60, 3)),
                NOW);

        DurableCheckpointApplier.fireDueTimers(execution, NOW + 3_000);
        DurableOperation silent = execution.getOperations().get("silent");
        assertEquals(DurableOperationStatus.TIMED_OUT, silent.getStatus());
        assertEquals("Callback.Heartbeat", silent.getError().getErrorType());
        assertEquals("Callback timed out on heartbeat", silent.getError().getErrorMessage());
        assertEquals(DurableOperationStatus.STARTED, execution.getOperations().get("slow").getStatus());

        DurableCheckpointApplier.fireDueTimers(execution, NOW + 5_000);
        DurableOperation slow = execution.getOperations().get("slow");
        assertEquals("Callback.Timeout", slow.getError().getErrorType());
        assertEquals("Callback timed out", slow.getError().getErrorMessage());
        assertEquals(Map.of("CallbackTimedOutDetails", Map.of("Error", Map.of("Payload",
                Map.of("ErrorType", "Callback.Timeout"), "Truncated", false))),
                execution.getHistory().get(execution.getHistory().size() - 1).getDetails());
    }

    @Test
    void aRejectedBatchLeavesTheExecutionUntouched() {
        DurableExecution execution = execution();
        long sequence = execution.getChangeSequence();
        int events = execution.getHistory().size();
        reject(execution, List.of(stepStart("s1", null), waitStart("w1"), waitStart("w1")));
        assertEquals(1, execution.getOperations().size());
        assertEquals(sequence, execution.getChangeSequence());
        assertEquals(events, execution.getHistory().size());
    }

    private static void assertRejected(List<DurableOperationUpdate> updates, String message) {
        assertEquals(message, reject(execution(), updates));
    }

    private static String reject(DurableExecution execution, List<DurableOperationUpdate> updates) {
        AwsException rejected = assertThrows(AwsException.class,
                () -> DurableCheckpointApplier.apply(execution, updates, NOW));
        assertEquals(400, rejected.getHttpStatus());
        assertEquals("InvalidParameterValueException", rejected.getErrorCode());
        return rejected.getMessage();
    }

    private static DurableExecution execution() {
        DurableExecution execution = new DurableExecution();
        execution.setExecutionId("exec-id");
        execution.setExecutionArn(EXECUTION_ARN);
        execution.setName("exec");
        execution.setMaxResultBytes(DurableExecutionService.ASYNC_PAYLOAD_LIMIT);
        DurableOperation root = new DurableOperation();
        root.setId("exec-id");
        root.setType(DurableOperationType.EXECUTION);
        root.setStatus(DurableOperationStatus.STARTED);
        root.setStartTimestamp(NOW);
        root.setChangeSequence(execution.nextChangeSequence());
        execution.getOperations().put("exec-id", root);
        return execution;
    }

    private static DurableOperationUpdate stepStart(String id, String parentId) {
        return update(id, parentId, DurableOperationType.STEP, DurableOperationAction.START, null);
    }

    private static DurableOperationUpdate waitStart(String id) {
        return new DurableOperationUpdate(id, null, null, DurableOperationType.WAIT, null, DurableOperationAction.START,
                null, null, null, 5, null, null, null);
    }

    private static DurableOperationUpdate callbackStart(String id, int timeoutSeconds, int heartbeatSeconds) {
        return new DurableOperationUpdate(id, null, null, DurableOperationType.CALLBACK, "Callback",
                DurableOperationAction.START, null, null, null, null, null, timeoutSeconds, heartbeatSeconds);
    }

    private static DurableOperationUpdate executionSucceed() {
        return update("result", null, DurableOperationType.EXECUTION, DurableOperationAction.SUCCEED, "{}");
    }

    private static DurableOperationUpdate update(String id, String parentId, DurableOperationType type,
                                                 DurableOperationAction action, String payload) {
        return new DurableOperationUpdate(id, parentId, null, type, null, action, payload, null, null, null, null, null,
                null);
    }
}
