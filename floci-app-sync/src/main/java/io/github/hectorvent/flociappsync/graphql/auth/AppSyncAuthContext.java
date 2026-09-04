package io.github.hectorvent.flociappsync.graphql.auth;

import io.github.hectorvent.flociappsync.iam.model.CallerContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapted from Floci's {@code services.appsync.graphql.auth.AppSyncAuthContext}: carries
 * {@link GraphqlApiAuthConfig} instead of the full {@code GraphqlApi} model, and adds
 * {@code callerContext} so IAM field-level checks don't need a callback into Floci —
 * Floci resolves it once (it owns IAM state) and sends it as part of the request.
 * This is the whole JSON body Floci posts as {@code ExecuteRequest.authContext}.
 */
public record AppSyncAuthContext(
        Map<String, Object> identity,
        String authType,
        AuthenticationType authenticationType,
        Set<String> deniedFields,
        GraphqlApiAuthConfig graphqlApi,
        String accessKeyId,
        String region,
        String accountId,
        CallerContext callerContext
) {
    public static final String KEY = "appsyncAuthContext";

    public AppSyncAuthContext {
        deniedFields = deniedFields == null ? Set.of() : Set.copyOf(deniedFields);
    }

    public List<String> deniedFieldsList() {
        return List.copyOf(deniedFields);
    }
}
