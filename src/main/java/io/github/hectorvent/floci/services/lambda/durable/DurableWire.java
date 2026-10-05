package io.github.hectorvent.floci.services.lambda.durable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableErrorObject;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableExecution;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableHistoryEvent;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperation;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationAction;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationType;
import io.github.hectorvent.floci.services.lambda.durable.model.DurableOperationUpdate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Maps the stored durable model to and from the PascalCase wire shapes. REST responses carry
 * epoch-second timestamps. The invocation event handed to the function carries epoch millis,
 * which is what the Durable Execution SDK parses.
 */
public final class DurableWire {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final long MAX_DELAY_SECONDS = 31_622_400L;

    private DurableWire() {
    }

    public record HandlerResponse(String status, String result, DurableErrorObject error) {
    }

    /** Lambda's function-error body uses lower camel case, unlike the ErrorObject. */
    public static byte[] functionErrorPayload(DurableErrorObject error) {
        ObjectNode body = NODES.objectNode();
        if (error != null) {
            putIfPresent(body, "errorMessage", error.getErrorMessage());
            putIfPresent(body, "errorType", error.getErrorType());
            if (error.getStackTrace() != null) {
                ArrayNode stackTrace = body.putArray("stackTrace");
                error.getStackTrace().forEach(stackTrace::add);
            }
        }
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** {@code updatedOperationIds} names the operations that changed since the previous invocation saw them. */
    public static ObjectNode invocationEvent(DurableExecution execution, String checkpointToken,
                                             List<String> updatedOperationIds) {
        ObjectNode event = NODES.objectNode();
        event.put("DurableExecutionArn", execution.getExecutionArn());
        event.put("CheckpointToken", checkpointToken);
        ObjectNode state = event.putObject("InitialExecutionState");
        ArrayNode operations = state.putArray("Operations");
        for (DurableOperation operation : execution.getOperations().values()) {
            operations.add(operation(operation, true));
        }
        state.put("NextMarker", "");
        ArrayNode updated = event.putArray("UpdatedOperationIds");
        updatedOperationIds.forEach(updated::add);
        return event;
    }

    public static ObjectNode operations(Collection<DurableOperation> operations, String nextMarker) {
        ObjectNode node = NODES.objectNode();
        ArrayNode array = node.putArray("Operations");
        for (DurableOperation operation : operations) {
            array.add(operation(operation, false));
        }
        if (nextMarker != null) {
            node.put("NextMarker", nextMarker);
        }
        return node;
    }

    public static ObjectNode operation(DurableOperation operation, boolean millis) {
        ObjectNode node = NODES.objectNode();
        node.put("Id", operation.getId());
        putIfPresent(node, "ParentId", operation.getParentId());
        putIfPresent(node, "Name", operation.getName());
        node.put("Type", operation.getType().name());
        putIfPresent(node, "SubType", operation.getSubType());
        putTimestamp(node, "StartTimestamp", operation.getStartTimestamp(), millis);
        if (operation.getEndTimestamp() != null) {
            putTimestamp(node, "EndTimestamp", operation.getEndTimestamp(), millis);
        }
        node.put("Status", operation.getStatus().name());
        switch (operation.getType()) {
            case EXECUTION -> {
                ObjectNode details = node.putObject("ExecutionDetails");
                putIfPresent(details, "InputPayload", operation.getInputPayload());
            }
            case CONTEXT -> {
                ObjectNode details = node.putObject("ContextDetails");
                if (operation.getReplayChildren() != null) {
                    details.put("ReplayChildren", operation.getReplayChildren());
                }
                putResultAndError(details, operation);
            }
            case STEP -> {
                ObjectNode details = node.putObject("StepDetails");
                if (operation.getAttempt() != null) {
                    details.put("Attempt", operation.getAttempt());
                }
                if (operation.getNextAttemptTimestamp() != null) {
                    putTimestamp(details, "NextAttemptTimestamp", operation.getNextAttemptTimestamp(), millis);
                }
                putResultAndError(details, operation);
            }
            case WAIT -> {
                ObjectNode details = node.putObject("WaitDetails");
                if (operation.getScheduledEndTimestamp() != null) {
                    putTimestamp(details, "ScheduledEndTimestamp", operation.getScheduledEndTimestamp(), millis);
                }
            }
            default -> {
            }
        }
        return node;
    }

    public static ObjectNode execution(DurableExecution execution, boolean includeExecutionData) {
        ObjectNode node = NODES.objectNode();
        node.put("DurableExecutionArn", execution.getExecutionArn());
        node.put("DurableExecutionName", execution.getName());
        node.put("FunctionArn", execution.getFunctionArn());
        node.put("Version", execution.getVersion());
        node.put("Status", execution.getStatus().name());
        putTimestamp(node, "StartTimestamp", execution.getStartTimestamp(), false);
        if (execution.getEndTimestamp() != null) {
            putTimestamp(node, "EndTimestamp", execution.getEndTimestamp(), false);
        }
        ObjectNode config = node.putObject("DurableConfig");
        config.put("ExecutionTimeout", execution.getExecutionTimeoutSeconds());
        config.put("RetentionPeriodInDays", execution.getRetentionPeriodInDays());
        node.put("ExecutionDataIncluded", includeExecutionData);
        if (includeExecutionData) {
            putIfPresent(node, "InputPayload", execution.getInputPayload());
            putIfPresent(node, "Result", execution.getResult());
            if (execution.getError() != null) {
                node.set("Error", error(execution.getError()));
            }
        }
        return node;
    }

    public static ObjectNode executionSummary(DurableExecution execution) {
        ObjectNode node = NODES.objectNode();
        node.put("DurableExecutionArn", execution.getExecutionArn());
        node.put("DurableExecutionName", execution.getName());
        node.put("FunctionArn", execution.getFunctionArn());
        node.put("Status", execution.getStatus().name());
        putTimestamp(node, "StartTimestamp", execution.getStartTimestamp(), false);
        if (execution.getEndTimestamp() != null) {
            putTimestamp(node, "EndTimestamp", execution.getEndTimestamp(), false);
        }
        return node;
    }

    public static ObjectNode historyEvent(DurableHistoryEvent event, boolean includeExecutionData) {
        ObjectNode node = NODES.objectNode();
        node.put("EventType", event.getEventType());
        node.put("EventId", event.getEventId());
        putIfPresent(node, "Id", event.getId());
        putIfPresent(node, "Name", event.getName());
        putIfPresent(node, "SubType", event.getSubType());
        putIfPresent(node, "ParentId", event.getParentId());
        putTimestamp(node, "EventTimestamp", event.getEventTimestamp(), false);
        if (event.getDetails() != null) {
            for (Map.Entry<String, Object> detailsEntry : event.getDetails().entrySet()) {
                node.set(detailsEntry.getKey(), detailsNode(detailsEntry.getValue(), includeExecutionData));
            }
        }
        return node;
    }

    public static ObjectNode error(DurableErrorObject error) {
        ObjectNode node = NODES.objectNode();
        putIfPresent(node, "ErrorMessage", error.getErrorMessage());
        putIfPresent(node, "ErrorType", error.getErrorType());
        putIfPresent(node, "ErrorData", error.getErrorData());
        if (error.getStackTrace() != null) {
            ArrayNode stackTrace = node.putArray("StackTrace");
            error.getStackTrace().forEach(stackTrace::add);
        }
        return node;
    }

    public static List<DurableOperationUpdate> parseUpdates(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new AwsException("SerializationException", "Updates must be a JSON array or null", 400);
        }
        List<DurableOperationUpdate> updates = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new AwsException("SerializationException", "Updates members must be JSON objects", 400);
            }
            updates.add(parseUpdate(map));
        }
        return updates;
    }

    public static DurableErrorObject parseError(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new AwsException("SerializationException", "Error must be a JSON object or null", 400);
        }
        DurableErrorObject error = new DurableErrorObject();
        error.setErrorMessage(string(map, "ErrorMessage"));
        error.setErrorType(string(map, "ErrorType"));
        error.setErrorData(string(map, "ErrorData"));
        Object stackTrace = map.get("StackTrace");
        if (stackTrace instanceof List<?> lines) {
            error.setStackTrace(lines.stream().map(String::valueOf).toList());
        }
        return error;
    }

    /** Null when the body is not the {@code {Status, Result, Error}} object the protocol requires. */
    public static HandlerResponse parseHandlerResponse(JsonNode body) {
        if (body == null || !body.isObject() || !body.path("Status").isTextual()) {
            return null;
        }
        JsonNode result = body.get("Result");
        JsonNode errorNode = body.get("Error");
        DurableErrorObject error = null;
        if (errorNode != null && errorNode.isObject()) {
            error = new DurableErrorObject();
            error.setErrorMessage(text(errorNode, "ErrorMessage"));
            error.setErrorType(text(errorNode, "ErrorType"));
            error.setErrorData(text(errorNode, "ErrorData"));
            JsonNode stackTrace = errorNode.get("StackTrace");
            if (stackTrace != null && stackTrace.isArray()) {
                List<String> lines = new ArrayList<>();
                stackTrace.forEach(line -> lines.add(line.asText()));
                error.setStackTrace(lines);
            }
        }
        return new HandlerResponse(body.get("Status").asText(),
                result != null && result.isTextual() ? result.asText() : null, error);
    }

    private static DurableOperationUpdate parseUpdate(Map<?, ?> map) {
        DurableOperationType type = enumMember(map, "Type", DurableOperationType.class);
        DurableOperationAction action = enumMember(map, "Action", DurableOperationAction.class);
        Map<?, ?> stepOptions = structure(map, "StepOptions");
        Map<?, ?> waitOptions = structure(map, "WaitOptions");
        Map<?, ?> contextOptions = structure(map, "ContextOptions");
        return new DurableOperationUpdate(
                string(map, "Id"),
                string(map, "ParentId"),
                string(map, "Name"),
                type,
                string(map, "SubType"),
                action,
                string(map, "Payload"),
                parseError(map.get("Error")),
                stepOptions == null ? null : integer(stepOptions, "NextAttemptDelaySeconds", "StepOptions"),
                waitOptions == null ? null : integer(waitOptions, "WaitSeconds", "WaitOptions"),
                contextOptions == null ? null : bool(contextOptions, "ReplayChildren"));
    }

    private static <E extends Enum<E>> E enumMember(Map<?, ?> map, String member, Class<E> type) {
        Object value = map.get(member);
        if (value == null) {
            throw new AwsException("InvalidParameterValueException", member + " is required", 400);
        }
        try {
            return Enum.valueOf(type, String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValueException", "Unknown operation " + member.toLowerCase() + ".",
                    400);
        }
    }

    private static Map<?, ?> structure(Map<?, ?> map, String member) {
        Object value = map.get(member);
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> structure) {
            return structure;
        }
        throw new AwsException("SerializationException", member + " must be a JSON object or null", 400);
    }

    private static String string(Map<?, ?> map, String member) {
        Object value = map.get(member);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        throw new AwsException("SerializationException", member + " must be a string", 400);
    }

    private static Integer integer(Map<?, ?> map, String member, String structure) {
        Object value = map.get(member);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number && (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long))) {
            throw new AwsException("SerializationException", structure + "." + member + " must be an integer", 400);
        }
        long seconds = number.longValue();
        if (seconds < 1 || MAX_DELAY_SECONDS < seconds) {
            String field = Character.toLowerCase(structure.charAt(0)) + structure.substring(1) + "."
                    + Character.toLowerCase(member.charAt(0)) + member.substring(1);
            String bound = seconds < 1 ? "greater than or equal to 1" : "less than or equal to " + MAX_DELAY_SECONDS;
            throw new AwsException("ValidationException", "1 validation error detected: Value '" + seconds
                    + "' at '" + field + "' failed to satisfy constraint: Member must have value " + bound, 400);
        }
        return (int) seconds;
    }

    private static Boolean bool(Map<?, ?> map, String member) {
        Object value = map.get(member);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new AwsException("SerializationException", member + " must be a boolean", 400);
    }

    private static String text(JsonNode node, String member) {
        JsonNode value = node.get(member);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static void putResultAndError(ObjectNode details, DurableOperation operation) {
        putIfPresent(details, "Result", operation.getResult());
        if (operation.getError() != null) {
            details.set("Error", error(operation.getError()));
        }
    }

    private static void putIfPresent(ObjectNode node, String member, String value) {
        if (value != null) {
            node.put(member, value);
        }
    }

    private static void putTimestamp(ObjectNode node, String member, long millis, boolean asMillis) {
        if (asMillis) {
            node.put(member, millis);
        } else {
            node.put(member, millis / 1000.0);
        }
    }

    /** Stored details are already wire shaped. Only the payload wrappers change with IncludeExecutionData. */
    private static JsonNode detailsNode(Object value, boolean includeExecutionData) {
        if (value instanceof Map<?, ?> map) {
            ObjectNode node = NODES.objectNode();
            boolean wrapper = map.containsKey(DurableHistory.TRUNCATED);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (wrapper && !includeExecutionData) {
                    if (DurableHistory.PAYLOAD.equals(key)) {
                        continue;
                    }
                    if (DurableHistory.TRUNCATED.equals(key)) {
                        node.put(key, true);
                        continue;
                    }
                }
                node.set(key, detailsNode(entry.getValue(), includeExecutionData));
            }
            return node;
        }
        if (value instanceof List<?> list) {
            ArrayNode array = NODES.arrayNode();
            for (Object item : list) {
                array.add(detailsNode(item, includeExecutionData));
            }
            return array;
        }
        if (value instanceof Long l) {
            return NODES.numberNode(l / 1000.0);
        }
        if (value instanceof Integer i) {
            return NODES.numberNode(i);
        }
        if (value instanceof Number n) {
            return NODES.numberNode(n.doubleValue());
        }
        if (value instanceof Boolean b) {
            return NODES.booleanNode(b);
        }
        return value == null ? NODES.nullNode() : NODES.textNode(String.valueOf(value));
    }
}
