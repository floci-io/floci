package io.github.hectorvent.floci.services.appsync.graphql.auth;

import java.util.List;
import java.util.Map;

public record AuthRequestInfo(
        String query,
        String operationName,
        Map<String, Object> variables,
        List<String> sourceIp,
        String requestId,
        String apiAccountId,
        String apiRegion,
        String callerAccountId,
        Map<String, String> requestHeaders,
        String rawBody
) {
    /** Existing same-account callers can omit a distinct caller account. */
    public AuthRequestInfo(
            String query,
            String operationName,
            Map<String, Object> variables,
            List<String> sourceIp,
            String requestId,
            String apiAccountId,
            String apiRegion,
            Map<String, String> requestHeaders,
            String rawBody
    ) {
        this(query, operationName, variables, sourceIp, requestId, apiAccountId, apiRegion,
                apiAccountId, requestHeaders, rawBody);
    }

    public AuthRequestInfo {
        sourceIp = sourceIp == null ? List.of() : List.copyOf(sourceIp);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
        rawBody = rawBody == null ? "" : rawBody;
        callerAccountId = callerAccountId == null ? apiAccountId : callerAccountId;
    }
}
