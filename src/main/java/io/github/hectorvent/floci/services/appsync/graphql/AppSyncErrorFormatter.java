package io.github.hectorvent.floci.services.appsync.graphql;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AppSync data-plane transport error envelope. Query-result formatting (graphql-java's
 * {@code ExecutionResult}/{@code GraphQLError} → this same shape) now happens inside the
 * floci-app-sync sidecar (issue #2917) — this class keeps only the transport-error half,
 * used for errors Floci itself detects before ever calling the sidecar (bad content-type,
 * malformed body, unknown API, auth failures).
 */
@ApplicationScoped
public class AppSyncErrorFormatter {

    public static final String MSG_EMPTY_BODY = "Request body is empty.";
    public static final String MSG_UNABLE_TO_PARSE = "Unable to parse GraphQL query.";
    public static final String MSG_MISSING_OPERATION_NAME = "Missing operation name.";
    public static final String MSG_NO_SCHEMA = "No schema definition exists.";

    public Map<String, Object> transportError(String errorType, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("errorType", errorType);
        error.put("message", message);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("errors", List.of(error));
        return response;
    }
}
