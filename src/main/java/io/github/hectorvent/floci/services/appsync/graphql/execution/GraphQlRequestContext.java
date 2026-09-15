package io.github.hectorvent.floci.services.appsync.graphql.execution;

import java.util.Map;

/**
 * Per-request facts snapshotted before execution starts and carried in the graphql-java context.
 * Fetchers read these instead of the CDI request scope for identity, headers and variables. Store
 * lookups still use the account established by the existing AppSync HTTP and authentication path.
 *
 * @param authTypeDisplay the value {@code $util.authType()} returns, e.g. "API Key Authorization"
 * @param identity        {@code $ctx.identity}; null for API key requests, matching AWS
 * @param requestHeaders  {@code $ctx.request.headers}: lower-cased names, a String for a single value
 *                        and a List for repeated headers; never contains {@code cookie}
 */
public record GraphQlRequestContext(
        String apiId,
        String accountId,
        String region,
        String authTypeDisplay,
        Map<String, Object> identity,
        Map<String, Object> requestHeaders,
        Map<String, Object> variables
) {
    /** Key under which the context is stored in {@link graphql.GraphQLContext}. */
    public static final String CONTEXT_KEY = "floci.appsync.request";
}
