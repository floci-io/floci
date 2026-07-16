package io.github.hectorvent.floci.services.bedrockagentcorecontrol.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * A page of list results plus an optional pagination token.
 */
@RegisterForReflection
public record ListResult<T>(List<T> items, String nextToken) {
}
