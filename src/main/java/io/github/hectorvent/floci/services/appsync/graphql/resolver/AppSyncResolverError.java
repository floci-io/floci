package io.github.hectorvent.floci.services.appsync.graphql.resolver;

import java.util.List;

/**
 * A resolver's own error, shaped the way AppSync reports one.
 *
 * <p>{@code util.error("...", "Forbidden")} does not produce a generic
 * "exception while fetching data": it produces an error carrying that {@code errorType}, which
 * clients switch on, alongside the {@code data} and {@code errorInfo} the resolver chose.
 *
 * <p>The four payload fields are exactly the sidecar callback's {@code {message, type, data, info}}
 * error shape, which the sidecar copies onto the GraphQL error's {@code extensions} and
 * {@link io.github.hectorvent.floci.services.appsync.graphql.AppSyncErrorFormatter} lifts back to
 * AppSync's top-level {@code errorType}/{@code errorInfo}.
 */
public record AppSyncResolverError(String message, String errorType, Object data, Object errorInfo,
                                   List<Object> path) {
}
