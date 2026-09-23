package io.github.hectorvent.floci.services.appsync.graphql;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the GraphQL sidecar's {@code /v1/execute} result (graphql-java's own {@code
 * ExecutionResult.toSpecification()} shape, already spec-compliant JSON by the time it crosses
 * the wire) to AWS AppSync's wire shape, with top-level {@code errorType}/{@code errorInfo}
 * (not extensions-only). No graphql-java types here: this only ever sees plain {@code
 * Map}/{@code List} JSON, since that's all the sidecar can hand back over HTTP (issue #2917).
 *
 * @see <a href="https://docs.aws.amazon.com/appsync/latest/devguide/built-in-util-js.html">AppSync DG {@code $util.error} ({@code errorType}/{@code errorInfo})</a>
 * @see <a href="https://github.com/graphql/graphql-over-http/issues/81">AppSync HTTP behavior (AppSync team / @robzhu)</a>
 */
@ApplicationScoped
public class AppSyncErrorFormatter {

    /**
     * Messages from AppSync team sample:
     * <a href="https://github.com/graphql/graphql-over-http/issues/81">graphql-over-http#81</a>
     */
    public static final String MSG_EMPTY_BODY = "Request body is empty.";
    public static final String MSG_UNABLE_TO_PARSE = "Unable to parse GraphQL query.";
    public static final String MSG_NO_SCHEMA = "No schema definition exists.";

    /** {@code sidecarResult} is exactly what {@link io.github.hectorvent.floci.services.appsync.GraphqlSidecarClient#execute} returned. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> format(Map<String, Object> sidecarResult) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (sidecarResult.containsKey("data")) {
            response.put("data", sidecarResult.get("data"));
        }
        Object errors = sidecarResult.get("errors");
        if (errors instanceof List<?> list && !list.isEmpty()) {
            List<Map<String, Object>> formatted = new ArrayList<>(list.size());
            for (Object error : list) {
                if (error instanceof Map<?, ?> map) {
                    formatted.add(formatError((Map<String, Object>) map));
                }
            }
            response.put("errors", formatted);
        }
        return response;
    }

    public Map<String, Object> transportError(String errorType, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("errorType", errorType);
        error.put("message", message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("errors", List.of(error));
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> formatError(Map<String, Object> error) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("message", error.get("message"));
        Object locations = error.get("locations");
        if (locations != null) {
            map.put("locations", locations);
        }
        Object path = error.get("path");
        if (path != null) {
            map.put("path", path);
        }
        // A resolver that called util.error chose its own errorType, errorInfo and data, and AppSync
        // reports all three on the error itself. The sidecar's resolver callback copies them onto
        // the error's extensions as type/data/info; anything else keeps the classification-derived
        // type and the null errorInfo that a server-side failure has.
        Map<?, ?> extensions = error.get("extensions") instanceof Map<?, ?> map1 ? map1 : null;
        Object suppliedType = extensions == null ? null : extensions.get("type");
        if (suppliedType instanceof String type && !type.isBlank()) {
            map.put("errorType", type);
        } else {
            Object raw = extensions == null ? null : extensions.get("classification");
            map.put("errorType", toAppSyncErrorType(raw == null ? null : String.valueOf(raw)));
        }
        map.put("errorInfo", extensions == null ? null : extensions.get("info"));
        Object suppliedData = extensions == null ? null : extensions.get("data");
        if (suppliedData != null) {
            map.put("data", suppliedData);
        }
        return map;
    }

    static String toAppSyncErrorType(String classification) {
        if (classification == null) {
            return "Unknown";
        }
        // graphql-java's own ErrorType enum names almost all match AppSync's wire names already;
        // InvalidSyntax is the one AppSync spells differently. Anything else (including a custom
        // classification like the sidecar's denyFields "Unauthorized") passes through unchanged.
        return "InvalidSyntax".equals(classification) ? "SyntaxError" : classification;
    }
}
