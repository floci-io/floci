package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * An ECS container volume inheritance reference:
 * {@code {"sourceContainer": ..., "readOnly": ...}}.
 */
@RegisterForReflection
public record VolumeFrom(String sourceContainer, boolean readOnly) {
}
