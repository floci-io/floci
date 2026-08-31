package io.github.hectorvent.floci.services.appsync.graphql.auth;

import java.util.List;
import java.util.Map;

public record LambdaAuthorizerResult(
        boolean authorized,
        List<String> deniedFields,
        Map<String, Object> resolverContext,
        Integer ttlOverride,
        int responseSizeBytes
) {
    public LambdaAuthorizerResult {
        deniedFields = deniedFields == null ? List.of() : List.copyOf(deniedFields);
        resolverContext = resolverContext == null ? Map.of() : Map.copyOf(resolverContext);
    }
}
