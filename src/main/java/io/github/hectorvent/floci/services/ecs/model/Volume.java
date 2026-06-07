package io.github.hectorvent.floci.services.ecs.model;

/**
 * A task-level volume in an ECS task definition. Only the EC2-launch-type
 * {@code host} volume shape is modelled: {@code {"name": ..., "host": {"sourcePath": ...}}}.
 * {@code hostSourcePath} is the absolute path on the Docker host that the volume
 * binds to; a container references this volume by {@code name} via a {@link MountPoint}.
 */
public record Volume(String name, String hostSourcePath) {
}
