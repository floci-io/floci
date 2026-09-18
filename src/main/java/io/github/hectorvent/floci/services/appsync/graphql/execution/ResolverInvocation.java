package io.github.hectorvent.floci.services.appsync.graphql.execution;

import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.Resolver;

import java.util.Map;

/**
 * Everything a unit resolver needs for one field: the request facts, the resolver definition, its
 * data source (null when the configured name does not resolve), and the field's arguments, parent
 * object and {@code $ctx.info} map.
 */
public record ResolverInvocation(
        GraphQlRequestContext requestContext,
        Resolver resolver,
        DataSource dataSource,
        Map<String, Object> arguments,
        Map<String, Object> source,
        Map<String, Object> info
) {
}
