package io.github.hectorvent.floci.core.common;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** A page of list results plus an optional pagination token. */
@RegisterForReflection
public record PaginatedResult<T>(List<T> items, String nextToken) {
}
