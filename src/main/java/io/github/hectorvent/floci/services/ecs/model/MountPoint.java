package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A container mount point in an ECS container definition:
 * {@code {"sourceVolume": ..., "containerPath": ..., "readOnly": ...}}.
 * {@code sourceVolume} references a task-level {@link Volume} by name; the volume's
 * host source path is bind-mounted into the container at {@code containerPath}.
 */
@RegisterForReflection
public record MountPoint(String sourceVolume, String containerPath, boolean readOnly) {
}
