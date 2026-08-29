package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A per-resource failure returned alongside the results of an ECS {@code Describe*} call.
 * ECS reports a resource it could not find here rather than throwing, so callers get partial
 * results; {@code reason} is {@code MISSING} for a resource that does not exist.
 */
@RegisterForReflection
public record Failure(String arn, String reason, String detail) {

    public static Failure missing(String arn) {
        return new Failure(arn, "MISSING", null);
    }
}
