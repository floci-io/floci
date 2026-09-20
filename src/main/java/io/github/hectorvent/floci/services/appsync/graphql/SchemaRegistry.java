package io.github.hectorvent.floci.services.appsync.graphql;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@code apiId -> raw SDL} cache for the AppSync data-plane path (issue #2917).
 *
 * <p>Used to exist to hold a compiled {@code GraphQLSchema}/{@code GraphQL} engine per API:
 * that compilation now happens inside the (stateless) GraphQL sidecar on every call, so there's
 * nothing to compile or cache here beyond the raw SDL text itself. This still exists, rather
 * than reading {@code schemaStore} directly, so the data-plane execute path has a simple
 * non-account-scoped lookup by {@code apiId}, the same shape the durable store doesn't
 * naturally give a single request.
 */
@ApplicationScoped
public class SchemaRegistry {
    private final Map<String, String> sdls = new ConcurrentHashMap<>();

    public void register(String apiId, String sdl) {
        sdls.put(apiId, sdl);
    }

    public Optional<String> getSdl(String apiId) {
        return Optional.ofNullable(sdls.get(apiId));
    }

    public void remove(String apiId) {
        sdls.remove(apiId);
    }
}
