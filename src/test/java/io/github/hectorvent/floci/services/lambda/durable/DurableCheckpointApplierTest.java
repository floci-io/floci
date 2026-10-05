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

    @Test
    void executionUpdateMustBeTheOnlyOneAndTheLast() {
        assertRejected(List.of(executionSucceed(), executionSucceed()), "Cannot checkpoint multiple EXECUTION updates.");
        assertRejected(List.of(executionSucceed(), stepStart("s1", null)), "EXECUTION checkpoint must be the last update.");
    }

    @Test
    void anExecutionCloseCarriesOnlyItsOwnResultKind() {
        DurableErrorObject error = DurableErrorObject.of("m", "T");
        assertRejected(List.of(new DurableOperationUpdate("result", null, null, DurableOperationType.EXECUTION, null,
                DurableOperationAction.SUCCEED, "\"p\"", error, null, null, null)),
                "Cannot provide an Error for SUCCEED action.");
        assertRejected(List.of(new DurableOperationUpdate("result", null, null, DurableOperationType.EXECUTION, null,
                DurableOperationAction.FAIL, "\"p\"", error, null, null, null)),
                "Cannot provide a Payload for FAIL action.");
    }

    @Test
    void anExecutionErrorHasTheSameSizeLimitAsAnOperationError() {
        DurableErrorObject large = DurableErrorObject.of("x".repeat(DurableCheckpointApplier.MAX_ERROR_BYTES), "T");
        assertRejected(List.of(new DurableOperationUpdate("result", null, null, DurableOperationType.EXECUTION, null,
                DurableOperationAction.FAIL, null, large, null, null, null)),
                "Error object size must be less than 32768 bytes.");
    }

    @Test
    void duplicateIdsAreRejectedUnlessAStartIsClosedInTheSameBatch() {
        assertRejected(List.of(stepStart("s1", null), stepStart("s1", null)),
                "Cannot checkpoint multiple operations with the same ID.");
        assertRejected(List.of(waitStart("w1"), waitStart("w1")), "Cannot checkpoint multiple operations with the same ID.");
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
                        DurableOperationAction.SUCCEED, null, DurableErrorObject.of("x", "y"), null, null, null))));
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
    void delaysOutsideTheirRangeAreValidationErrors() {
        AwsException rejected = assertThrows(AwsException.class, () -> DurableWire.parseUpdates(
                List.of(Map.of("Id", "w1", "Type", "WAIT", "Action", "START", "WaitOptions", Map.of("WaitSeconds", 0)))));
        assertEquals("ValidationException", rejected.getErrorCode());
        assertEquals("1 validation error detected: Value '0' at 'waitOptions.waitSeconds' failed to satisfy "
                + "constraint: Member must have value greater than or equal to 1", rejected.getMessage());
    }

    @Test
    void callbacksAndChainedInvokesAreNotSupportedYet() {
        assertRejected(List.of(update("c1", null, DurableOperationType.CALLBACK, DurableOperationAction.START, null)),
                "CALLBACK operations are not supported yet");
        assertRejected(List.of(update("i1", null, DurableOperationType.CHAINED_INVOKE, DurableOperationAction.START,
                null)), "CHAINED_INVOKE operations are not supported yet");
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
                null, null, null, 5, null);
    }

    private static DurableOperationUpdate executionSucceed() {
        return update("result", null, DurableOperationType.EXECUTION, DurableOperationAction.SUCCEED, "{}");
    }

    private static DurableOperationUpdate update(String id, String parentId, DurableOperationType type,
                                                 DurableOperationAction action, String payload) {
        return new DurableOperationUpdate(id, parentId, null, type, null, action, payload, null, null, null, null);
    }
}
