package io.github.hectorvent.floci.services.ecs.model;

/**
 * A task-level volume in an ECS task definition. Two shapes are modelled:
 * <ul>
 *   <li>EC2-launch-type {@code host} volume — {@code {"name": ..., "host": {"sourcePath": ...}}};
 *       {@code hostSourcePath} is the absolute path on the Docker host that the volume binds to.</li>
 *   <li>{@code efsVolumeConfiguration} volume — {@code {"name": ..., "efsVolumeConfiguration": {...}}};
 *       see {@link EfsVolumeConfiguration}.</li>
 * </ul>
 * The two are mutually exclusive. A container references the volume by {@code name} via a
 * {@link MountPoint}.
 */
public record Volume(String name, String hostSourcePath, EfsVolumeConfiguration efs) {

    /** Convenience constructor for a {@code host} volume (no EFS configuration). */
    public Volume(String name, String hostSourcePath) {
        this(name, hostSourcePath, null);
    }
}
