package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;

/** What the invokers of the optimized integrations share in reading a Task's request and failing it. */
final class OptimizedIntegrations {

    private OptimizedIntegrations() {
    }

    /** A field the integration's request names a resource or member by, as a non-blank string. */
    static String required(JsonNode request, String field) {
        return requiredText(request.path(field), field);
    }

    /** {@code value}, the one the caller found the request giving {@code field}, as a non-blank string. */
    static String requiredText(JsonNode value, String field) {
        if (!value.isTextual() || value.asText().isBlank()) {
            throw refused("The field " + field + " is required.");
        }
        return value.asText();
    }

    /** A request the integration refuses before the service sees it. */
    static AslExecutor.FailStateException refused(String cause) {
        return new AslExecutor.FailStateException("States.TaskFailed", cause);
    }

    /** A job that ended without succeeding: the cause is its describe response, as a JSON string. */
    static AslExecutor.FailStateException failed(JsonNode described) {
        return new AslExecutor.FailStateException("States.TaskFailed", described.toString());
    }

    static AslExecutor.FailStateException unsupported(StatesIntegration integration) {
        return new AslExecutor.FailStateException("States.TaskFailed", "Unsupported resource: " + integration);
    }
}
