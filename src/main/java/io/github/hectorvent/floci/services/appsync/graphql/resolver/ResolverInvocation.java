package io.github.hectorvent.floci.services.appsync.graphql.resolver;

import java.util.List;
import java.util.Map;

/**
 * One field's resolution request, as the GraphQL sidecar's resolver callback describes it.
 *
 * <p>This is the whole of what {@link AppSyncResolverExecutor} needs to know about the GraphQL
 * execution around a field. It used to read the same values off graphql-java's {@code
 * DataFetchingEnvironment}, back when Floci executed the query itself; since the engine moved into
 * the {@code floci-sidecar-graphql} container, they arrive over HTTP instead and Floci carries no
 * graphql-java types at all.
 *
 * @param identity the caller's AppSync identity, or null for an auth mode that has none (API key)
 * @param authType the AppSync auth mode name that authorised the request
 */
public record ResolverInvocation(String apiId, String typeName, String fieldName,
                                 Map<String, Object> arguments, Object source, List<Object> path,
                                 Map<String, Object> variables, List<String> selectionSetList,
                                 Object identity, String authType) {

    public ResolverInvocation {
        // Normalised here rather than at each read: the callback may omit any of them, and every
        // consumer wants the empty collection rather than a null it has to re-check.
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        selectionSetList = selectionSetList == null ? List.of() : List.copyOf(selectionSetList);
        path = path == null || path.isEmpty() ? List.of(fieldName) : List.copyOf(path);
    }
}
