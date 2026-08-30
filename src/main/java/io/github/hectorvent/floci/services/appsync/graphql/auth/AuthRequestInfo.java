package io.github.hectorvent.floci.services.appsync.graphql.auth;

import java.util.List;
import java.util.Map;

public record AuthRequestInfo(
        String query,
        String operationName,
        Map<String, Object> variables,
        List<String> sourceIp,
        String requestId,
        String accountId,
        String region,
        Map<String, String> requestHeaders
) {
    public AuthRequestInfo {
        sourceIp = sourceIp == null ? List.of() : List.copyOf(sourceIp);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        requestHeaders = requestHeaders == null ? Map.of() : Map.copyOf(requestHeaders);
    }
}
