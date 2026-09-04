package io.github.hectorvent.flociappsync.api;

import io.github.hectorvent.flociappsync.graphql.auth.AppSyncAuthContext;

import java.util.Map;

/**
 * Body of {@code POST /schemas/{apiId}/execute}. Floci does content-type validation, body
 * parsing, API lookup, and top-level request authentication (API key/IAM/Cognito/OIDC/Lambda
 * authorizer) before calling this — {@code authContext} carries the outcome of that, plus
 * whatever field-level authorization (IAM policy, denied fields) needs.
 */
public record ExecuteRequest(
        String query,
        Map<String, Object> variables,
        String operationName,
        AppSyncAuthContext authContext
) {
}
