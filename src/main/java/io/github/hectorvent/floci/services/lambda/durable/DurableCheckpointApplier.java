package io.github.hectorvent.floci.services.lambda.durable;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableErrorObject;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecution;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecutionStatus;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableHistoryEvent;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperation;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationAction;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationStatus;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationType;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationUpdate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Applies one CheckpointDurableExecution batch. Validation and transitions run on a copy of the
 * operations, so a rejected batch leaves the execution untouched, and timers that fell due are
 * completed at the same time so a polling handler sees them in the response.
 */
final class DurableCheckpointApplier {

    static final int MAX_OPERATION_PAYLOAD_BYTES = 256 * 1024;
    static final int MAX_ERROR_BYTES = 32 * 1024;
    private static final Pattern OPERATION_ID = Pattern.compile("[a-zA-Z0-9-_]{1,64}");

    private DurableCheckpointApplier() {
    }

    record Outcome(boolean closed) {
    }

    static Outcome apply(DurableExecution execution, List<DurableOperationUpdate> updates, long now) {
        validateBatch(updates);
        Draft draft = new Draft(execution, now);
        for (DurableOperationUpdate update : updates) {
            draft.apply(update);
        }
        draft.commit();
        return new Outcome(draft.closingStatus != null);
    }

    /** Completes waits, step retries and callback timeouts whose time has come. The sweeper and every checkpoint call it. */
    static boolean fireDueTimers(DurableExecution execution, long now) {
        boolean changed = false;
        for (DurableOperation operation : execution.getOperations().values()) {
            if (operation.getType() == DurableOperationType.WAIT
                    && operation.getStatus() == DurableOperationStatus.STARTED
                    && operation.getScheduledEndTimestamp() != null
                    && operation.getScheduledEndTimestamp() <= now) {
                operation.setStatus(DurableOperationStatus.SUCCEEDED);
                operation.setEndTimestamp(now);
                operation.setChangeSequence(execution.nextChangeSequence());
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("Duration", waitSeconds(operation));
                DurableHistory.operationEvent(execution, operation, "WaitSucceeded", now, details);
                changed = true;
            } else if (operation.getType() == DurableOperationType.STEP
                    && operation.getStatus() == DurableOperationStatus.PENDING
                    && operation.getNextAttemptTimestamp() != null
                    && operation.getNextAttemptTimestamp() <= now) {
                operation.setStatus(DurableOperationStatus.READY);
                operation.setNextAttemptTimestamp(null);
                operation.setChangeSequence(execution.nextChangeSequence());
                changed = true;
            } else if (operation.getType() == DurableOperationType.CALLBACK
                    && operation.getStatus() == DurableOperationStatus.STARTED) {
                changed |= timeOutCallback(execution, operation, now);
            }
        }
        return changed;
    }

    private static boolean timeOutCallback(DurableExecution execution, DurableOperation operation, long now) {
        boolean timedOut = operation.getCallbackDeadline() != null && operation.getCallbackDeadline() <= now;
        boolean heartbeatMissed = operation.getHeartbeatDeadline() != null && operation.getHeartbeatDeadline() <= now;
        if (!timedOut && !heartbeatMissed) {
            return false;
        }
        boolean heartbeatFirst = heartbeatMissed
                && (!timedOut || operation.getHeartbeatDeadline() < operation.getCallbackDeadline());
        DurableErrorObject error = heartbeatFirst
                ? DurableErrorObject.of("Callback timed out on heartbeat", "Callback.Heartbeat")
                : DurableErrorObject.of("Callback timed out", "Callback.Timeout");
        operation.setStatus(DurableOperationStatus.TIMED_OUT);
        operation.setError(error);
        operation.setEndTimestamp(now);
        operation.setCallbackDeadline(null);
        operation.setHeartbeatDeadline(null);
        operation.setChangeSequence(execution.nextChangeSequence());
        // The history event names only the error type.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("Error", DurableHistory.errorWrapper(DurableErrorObject.of(null, error.getErrorType())));
        DurableHistory.operationEvent(execution, operation, "CallbackTimedOut", now, details);
        return true;
    }

    private static void validateBatch(List<DurableOperationUpdate> updates) {
        long executionUpdates = updates.stream().filter(update -> update.type() == DurableOperationType.EXECUTION).count();
        if (executionUpdates > 1) {
            throw invalid("Cannot checkpoint multiple EXECUTION updates.");
        }
        Map<String, DurableOperationUpdate> seen = new HashMap<>();
        for (int i = 0; i < updates.size(); i++) {
            DurableOperationUpdate update = updates.get(i);
            if (update.type() == DurableOperationType.EXECUTION) {
                if (i != updates.size() - 1) {
                    throw invalid("EXECUTION checkpoint must be the last update.");
                }
                continue;
            }
            DurableOperationUpdate previous = seen.put(update.id(), update);
            if (previous != null && !(previous.action() == DurableOperationAction.START
                    && previous.type() == update.type() && closesInSameBatch(update))) {
                throw invalid("Cannot update the same operation twice in a single request.");
            }
        }
    }

    /** A START may be followed by the action that finishes it in the same batch, once. */
    private static boolean closesInSameBatch(DurableOperationUpdate update) {
        return switch (update.type()) {
            case STEP -> update.action() == DurableOperationAction.SUCCEED
                    || update.action() == DurableOperationAction.FAIL
                    || update.action() == DurableOperationAction.RETRY;
            case CONTEXT -> update.action() == DurableOperationAction.SUCCEED
                    || update.action() == DurableOperationAction.FAIL;
            default -> false;
        };
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    private static int waitSeconds(DurableOperation operation) {
        return (int) ((operation.getScheduledEndTimestamp() - operation.getStartTimestamp()) / 1000);
    }

    static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** The batch's copy of the operations, its queued history events and the closing outcome. */
    private static final class Draft {

        private final DurableExecution execution;
        private final long now;
        private final LinkedHashMap<String, DurableOperation> operations = new LinkedHashMap<>();
        private final List<Consumer<DurableExecution>> events = new ArrayList<>();
        private long changeSequence;
        private DurableExecutionStatus closingStatus;
        private String closingResult;
        private DurableErrorObject closingError;

        Draft(DurableExecution execution, long now) {
            this.execution = execution;
            this.now = now;
            this.changeSequence = execution.getChangeSequence();
            for (Map.Entry<String, DurableOperation> entry : execution.getOperations().entrySet()) {
                operations.put(entry.getKey(), entry.getValue().copy());
            }
        }

        void apply(DurableOperationUpdate update) {
            if (update.type() == DurableOperationType.EXECUTION) {
                applyExecution(update);
                return;
            }
            if (update.id() == null || !OPERATION_ID.matcher(update.id()).matches()) {
                throw invalid("Invalid operation id.");
            }
            DurableOperation existing = operations.get(update.id());
            if (existing != null) {
                requireConsistent(existing, update);
            }
            if (update.parentId() != null) {
                DurableOperation parent = operations.get(update.parentId());
                if (parent == null || parent.getType() != DurableOperationType.CONTEXT) {
                    throw invalid("Invalid parent operation id.");
                }
            }
            requireErrorSize(update);
            if (utf8Length(update.payload()) > MAX_OPERATION_PAYLOAD_BYTES) {
                throw invalid(update.type() + " payload size must be less than or equal to "
                        + MAX_OPERATION_PAYLOAD_BYTES + " bytes.");
            }
            switch (update.type()) {
                case CONTEXT -> applyContext(update, existing);
                case STEP -> applyStep(update, existing);
                case WAIT -> applyWait(update, existing);
                case CALLBACK -> applyCallback(update, existing);
                case CHAINED_INVOKE -> throw invalid(update.type() + " operations are not supported yet");
                default -> throw invalid("Unknown operation type.");
            }
        }

        void commit() {
            execution.setOperations(operations);
            execution.setChangeSequence(changeSequence);
            for (Consumer<DurableExecution> event : events) {
                event.accept(execution);
            }
            if (closingStatus != null) {
                execution.setStatus(closingStatus);
                execution.setResult(closingResult);
                execution.setError(closingError);
                execution.setEndTimestamp(now);
                if (closingStatus == DurableExecutionStatus.SUCCEEDED) {
                    DurableHistory.executionSucceeded(execution, now);
                } else {
                    DurableHistory.executionEnded(execution, "ExecutionFailed", now);
                }
            }
        }

        private void applyExecution(DurableOperationUpdate update) {
            if (update.action() != DurableOperationAction.SUCCEED && update.action() != DurableOperationAction.FAIL) {
                throw invalid("Invalid EXECUTION action.");
            }
            requireMatchingResult(update);
            requireErrorSize(update);
            if (utf8Length(update.payload()) > execution.getMaxResultBytes()) {
                throw invalid("Execution output payload size must be less than or equal to "
                        + execution.getMaxResultBytes() + " bytes.");
            }
            boolean succeeded = update.action() == DurableOperationAction.SUCCEED;
            closingStatus = succeeded ? DurableExecutionStatus.SUCCEEDED : DurableExecutionStatus.FAILED;
            closingResult = succeeded ? update.payload() : null;
            closingError = succeeded ? null : update.error();
            DurableOperation operation = operations.get(execution.getExecutionId());
            if (operation != null) {
                operation.setStatus(succeeded ? DurableOperationStatus.SUCCEEDED : DurableOperationStatus.FAILED);
                operation.setEndTimestamp(now);
                touch(operation);
            }
        }

        private void applyContext(DurableOperationUpdate update, DurableOperation existing) {
            switch (update.action()) {
                case START -> {
                    if (existing != null) {
                        throw invalid("Cannot start a CONTEXT that already exist.");
                    }
                    DurableOperation operation = create(update);
                    event(operation, "ContextStarted", new LinkedHashMap<>());
                }
                case SUCCEED, FAIL -> {
                    if (existing == null || existing.getStatus() != DurableOperationStatus.STARTED) {
                        throw invalid("Invalid current CONTEXT state to close.");
                    }
                    boolean succeeded = update.action() == DurableOperationAction.SUCCEED;
                    existing.setStatus(succeeded ? DurableOperationStatus.SUCCEEDED : DurableOperationStatus.FAILED);
                    existing.setResult(succeeded ? update.payload() : null);
                    existing.setError(succeeded ? null : update.error());
                    if (update.replayChildren() != null) {
                        existing.setReplayChildren(update.replayChildren());
                    }
                    existing.setEndTimestamp(now);
                    touch(existing);
                    event(existing, succeeded ? "ContextSucceeded" : "ContextFailed", resultOrError(existing));
                }
                default -> throw invalid("Invalid action for the given operation type.");
            }
        }

        private void applyStep(DurableOperationUpdate update, DurableOperation existing) {
            switch (update.action()) {
                case START -> {
                    if (update.nextAttemptDelaySeconds() != null) {
                        throw invalid("Invalid StepOptions for the given action.");
                    }
                    if (existing == null) {
                        event(create(update), "StepStarted", new LinkedHashMap<>());
                    } else if (existing.getStatus() == DurableOperationStatus.READY) {
                        existing.setStatus(DurableOperationStatus.STARTED);
                        touch(existing);
                        event(existing, "StepStarted", new LinkedHashMap<>());
                    } else {
                        throw invalid("Invalid current STEP state to start.");
                    }
                }
                case SUCCEED, FAIL, RETRY -> closeStep(update, existing);
                default -> throw invalid("Invalid action for the given operation type.");
            }
        }

        private void closeStep(DurableOperationUpdate update, DurableOperation existing) {
            DurableOperationAction action = update.action();
            requireMatchingResult(update);
            if (action == DurableOperationAction.RETRY && update.payload() != null && update.error() != null) {
                throw invalid("Cannot provide both error and payload to RETRY a STEP.");
            }
            if ((action == DurableOperationAction.RETRY) != (update.nextAttemptDelaySeconds() != null)) {
                throw invalid("Invalid StepOptions for the given action.");
            }
            DurableOperation operation = existing;
            if (operation == null) {
                operation = create(update);
            } else if (operation.getStatus() != DurableOperationStatus.STARTED
                    && operation.getStatus() != DurableOperationStatus.READY) {
                throw invalid(action == DurableOperationAction.RETRY
                        ? "Invalid current STEP state to re-attempt." : "Invalid current STEP state to close.");
            }
            int attempt = (operation.getAttempt() == null ? 0 : operation.getAttempt()) + 1;
            operation.setAttempt(attempt);
            operation.setResult(update.payload());
            operation.setError(update.error());
            Map<String, Object> details = resultOrError(operation);
            Map<String, Object> retryDetails = new LinkedHashMap<>();
            retryDetails.put("CurrentAttempt", attempt);
            details.put("RetryDetails", retryDetails);
            switch (action) {
                case SUCCEED -> {
                    operation.setStatus(DurableOperationStatus.SUCCEEDED);
                    operation.setEndTimestamp(now);
                    event(operation, "StepSucceeded", details);
                }
                case FAIL -> {
                    operation.setStatus(DurableOperationStatus.FAILED);
                    operation.setEndTimestamp(now);
                    event(operation, "StepFailed", details);
                }
                default -> {
                    operation.setStatus(DurableOperationStatus.PENDING);
                    operation.setNextAttemptTimestamp(now + update.nextAttemptDelaySeconds() * 1000L);
                    retryDetails.put("NextAttemptDelaySeconds", update.nextAttemptDelaySeconds());
                    event(operation, update.error() != null ? "StepFailed" : "StepSucceeded", details);
                }
            }
            touch(operation);
        }

        private void applyWait(DurableOperationUpdate update, DurableOperation existing) {
            switch (update.action()) {
                case START -> {
                    if (existing != null) {
                        throw invalid("Cannot start a WAIT that already exist.");
                    }
                    if (update.waitSeconds() == null) {
                        throw invalid("Update for WAIT operation requires WaitOptions.");
                    }
                    DurableOperation operation = create(update);
                    operation.setScheduledEndTimestamp(now + update.waitSeconds() * 1000L);
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("Duration", update.waitSeconds());
                    details.put("ScheduledEndTimestamp", operation.getScheduledEndTimestamp());
                    event(operation, "WaitStarted", details);
                }
                case CANCEL -> {
                    if (existing == null || existing.getStatus() != DurableOperationStatus.STARTED) {
                        throw invalid("Cannot cancel a WAIT that does not exist or has already completed.");
                    }
                    existing.setStatus(DurableOperationStatus.CANCELLED);
                    existing.setEndTimestamp(now);
                    touch(existing);
                    event(existing, "WaitCancelled", new LinkedHashMap<>());
                }
                default -> throw invalid("Invalid action for the given operation type.");
            }
        }

        /** Only the function starts a callback. SendDurableExecutionCallback* completes it. */
        private void applyCallback(DurableOperationUpdate update, DurableOperation existing) {
            if (update.action() != DurableOperationAction.START) {
                throw invalid("Invalid action for the given operation type.");
            }
            if (existing != null) {
                throw invalid("Cannot start a CALLBACK that already exist.");
            }
            DurableOperation operation = create(update);
            operation.setCallbackId(DurableTokens.callbackId(execution.getExecutionArn(), update.id()));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("CallbackId", operation.getCallbackId());
            Integer timeout = update.callbackTimeoutSeconds();
            if (timeout != null && timeout > 0) {
                operation.setCallbackDeadline(now + timeout * 1000L);
                details.put("Timeout", timeout);
            }
            Integer heartbeat = update.callbackHeartbeatTimeoutSeconds();
            if (heartbeat != null && heartbeat > 0) {
                operation.setHeartbeatTimeoutSeconds(heartbeat);
                operation.setHeartbeatDeadline(now + heartbeat * 1000L);
                details.put("HeartbeatTimeout", heartbeat);
            }
            event(operation, "CallbackStarted", details);
        }

        private static void requireMatchingResult(DurableOperationUpdate update) {
            if (update.action() == DurableOperationAction.SUCCEED && update.error() != null) {
                throw invalid("Cannot provide an Error for SUCCEED action.");
            }
            if (update.action() == DurableOperationAction.FAIL && update.payload() != null) {
                throw invalid("Cannot provide a Payload for FAIL action.");
            }
        }

        private static void requireErrorSize(DurableOperationUpdate update) {
            if (update.error() != null && utf8Length(errorJson(update.error())) >= MAX_ERROR_BYTES) {
                throw invalid("Error object size must be less than " + MAX_ERROR_BYTES + " bytes.");
            }
        }

        private DurableOperation create(DurableOperationUpdate update) {
            DurableOperation operation = new DurableOperation();
            operation.setId(update.id());
            operation.setParentId(update.parentId());
            operation.setName(update.name());
            operation.setType(update.type());
            operation.setSubType(update.subType());
            operation.setStatus(DurableOperationStatus.STARTED);
            operation.setStartTimestamp(now);
            touch(operation);
            operations.put(update.id(), operation);
            return operation;
        }

        private void touch(DurableOperation operation) {
            changeSequence++;
            operation.setChangeSequence(changeSequence);
        }

        /** Events are queued against the operation copy and appended only once the whole batch is accepted. */
        private void event(DurableOperation operation, String eventType, Map<String, Object> details) {
            events.add(target -> DurableHistory.operationEvent(target, operation, eventType, now, details));
        }

        private static void requireConsistent(DurableOperation existing, DurableOperationUpdate update) {
            if (existing.getType() != update.type()) {
                throw invalid("Inconsistent operation type.");
            }
            if (update.subType() != null && !Objects.equals(existing.getSubType(), update.subType())) {
                throw invalid("Inconsistent operation subtype.");
            }
            if (update.name() != null && !Objects.equals(existing.getName(), update.name())) {
                throw invalid("Inconsistent operation name.");
            }
            if (!Objects.equals(existing.getParentId(), update.parentId())) {
                throw invalid("Inconsistent parent operation id.");
            }
        }

        private static Map<String, Object> resultOrError(DurableOperation operation) {
            Map<String, Object> details = new LinkedHashMap<>();
            if (operation.getError() != null) {
                details.put("Error", DurableHistory.errorWrapper(operation.getError()));
            } else if (operation.getResult() != null) {
                details.put("Result", DurableHistory.payloadWrapper(operation.getResult()));
            }
            return details;
        }

        private static String errorJson(DurableErrorObject error) {
            return DurableWire.error(error).toString();
        }
    }
}
