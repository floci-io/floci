package io.github.hectorvent.floci.services.appsync.graphql.execution.datasource;

import io.github.hectorvent.floci.services.appsync.graphql.execution.FieldError;

/**
 * What a data source hands to the response mapping template: {@code result} becomes
 * {@code $ctx.result} and {@code error} (when present) becomes {@code $ctx.error}.
 * Both may be set at once, e.g. a conditional check failure that returns the existing item.
 */
public record DataSourceResult(Object result, FieldError error) {

    public static DataSourceResult of(Object result) {
        return new DataSourceResult(result, null);
    }

    public static DataSourceResult error(FieldError error) {
        return new DataSourceResult(null, error);
    }
}
