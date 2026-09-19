package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * A task-level volume in an ECS task definition. Two shapes are modelled:
 * <ul>
 *   <li>EC2-launch-type {@code host} volume: {@code {"name": ..., "host": {"sourcePath": ...}}};
 *       {@code hostSourcePath} is the absolute path on the Docker host that the volume binds to.</li>
 *   <li>{@code efsVolumeConfiguration} volume: {@code {"name": ..., "efsVolumeConfiguration": {...}}};
 *       see {@link EfsVolumeConfiguration}.</li>
 * </ul>
 * The two are mutually exclusive. A container references the volume by {@code name} via a
 * {@link MountPoint}.
 *
 * <p>{@code unparsed} keeps the volume members Floci backs with nothing, which is the rest of
 * AWS's {@code Volume} shape: {@code dockerVolumeConfiguration},
 * {@code fsxWindowsFileServerVolumeConfiguration}, {@code s3filesVolumeConfiguration} and
 * {@code configuredAtLaunch}. They have to survive the round trip or a client that registered one
 * reads the definition back without it and proposes the same change forever.
 */
@RegisterForReflection
public record Volume(String name, String hostSourcePath, EfsVolumeConfiguration efs,
                     Map<String, Object> unparsed) {

    /** Convenience constructor for a {@code host} volume (no EFS configuration). */
    public Volume(String name, String hostSourcePath) {
        this(name, hostSourcePath, null, null);
    }

    public Volume(String name, String hostSourcePath, EfsVolumeConfiguration efs) {
        this(name, hostSourcePath, efs, null);
    }
}
