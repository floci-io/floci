package io.github.hectorvent.floci.services.lambda.durable;

import io.github.hectorvent.floci.services.lambda.durable.model.DurableErrorObject;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecution;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableHistoryEvent;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds GetDurableExecutionHistory events in their wire form. Event type values are PascalCase on
 * the wire (ExecutionStarted, StepSucceeded), unlike the SCREAMING_SNAKE operation enums.
 */
final class DurableHistory {

    static final String PAYLOAD = "Payload";
    static final String TRUNCATED = "Truncated";

    private DurableHistory() {
    }

    static void executionStarted(DurableExecution execution, long now) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("Input", payloadWrapper(execution.getInputPayload()));
        details.put("ExecutionTimeout", execution.getExecutionTimeoutSeconds());
        executionEvent(execution, "ExecutionStarted", now, details);
    }

    static void executionSucceeded(DurableExecution execution, long now) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("Result", payloadWrapper(execution.getResult()));
        executionEvent(execution, "ExecutionSucceeded", now, details);
    }

    /** ExecutionFailed or ExecutionStopped, both of which carry the execution's error. */
    static void executionEnded(DurableExecution execution, String eventType, long now) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("Error", errorWrapper(execution.getError()));
        executionEvent(execution, eventType, now, details);
    }

    /** AWS leaves ExecutionTimedOutDetails empty. The error is only on the execution itself. */
    static void executionTimedOut(DurableExecution execution, long now) {
        executionEvent(execution, "ExecutionTimedOut", now, new LinkedHashMap<>());
    }

    static void invocationCompleted(DurableExecution execution, long startedAt, long endedAt, String requestId,
                                    DurableErrorObject error) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("StartTimestamp", startedAt);
        details.put("EndTimestamp", endedAt);
        details.put("RequestId", requestId);
        if (error != null) {
            details.put("Error", errorWrapper(error));
        }
        DurableHistoryEvent event = execution.appendEvent("InvocationCompleted", endedAt);
        event.setDetails(Map.of("InvocationCompletedDetails", details));
    }

    static void operationEvent(DurableExecution execution, DurableOperation operation, String eventType, long now,
                               Map<String, Object> details) {
        DurableHistoryEvent event = execution.appendEvent(eventType, now);
        event.setId(operation.getId());
        event.setName(operation.getName());
        event.setSubType(operation.getSubType());
        event.setParentId(operation.getParentId());
        event.setDetails(Map.of(eventType + "Details", details));
    }

    static Map<String, Object> payloadWrapper(String payload) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        if (payload != null) {
            wrapper.put(PAYLOAD, payload);
        }
        wrapper.put(TRUNCATED, false);
        return wrapper;
    }

    static Map<String, Object> errorWrapper(DurableErrorObject error) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        if (error != null) {
            wrapper.put(PAYLOAD, errorMap(error));
        }
        wrapper.put(TRUNCATED, false);
        return wrapper;
    }

    private static Map<String, Object> errorMap(DurableErrorObject error) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (error.getErrorMessage() != null) {
            map.put("ErrorMessage", error.getErrorMessage());
        }
        if (error.getErrorType() != null) {
            map.put("ErrorType", error.getErrorType());
        }
        if (error.getErrorData() != null) {
            map.put("ErrorData", error.getErrorData());
        }
        if (error.getStackTrace() != null) {
            map.put("StackTrace", error.getStackTrace());
        }
        return map;
    }

    private static void executionEvent(DurableExecution execution, String eventType, long now,
                                       Map<String, Object> details) {
        DurableHistoryEvent event = execution.appendEvent(eventType, now);
        event.setId(execution.getExecutionId());
        event.setName(execution.getName());
        event.setDetails(Map.of(eventType + "Details", details));
    }
}
