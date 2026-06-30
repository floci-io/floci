package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code efsVolumeConfiguration} of an ECS task-definition volume:
 * {@code {"fileSystemId": ..., "rootDirectory": ..., "transitEncryption": ...,
 * "transitEncryptionPort": ..., "authorizationConfig": {"accessPointId": ..., "iam": ...}}}.
 *
 * <p>A container references the owning {@link Volume} by name via a {@link MountPoint}.
 * Docker cannot mount a real Amazon EFS file system, so Floci materialises an EFS-backed
 * volume as a shared local Docker <em>named volume</em> ({@code floci-efs-<fileSystemId>}):
 * every container that mounts the same file system shares persistent storage that survives
 * task restarts — the local equivalent of an EFS mount.
 *
 * <p>{@code rootDirectory}, {@code transitEncryption}, {@code transitEncryptionPort} and
 * {@code authorizationConfig} are modelled for RegisterTaskDefinition/DescribeTaskDefinition
 * round-trip fidelity (so Terraform sees no drift) but have no effect on the local mount.
 */
@RegisterForReflection
public record EfsVolumeConfiguration(
        String fileSystemId,
        String rootDirectory,
        String transitEncryption,
        Integer transitEncryptionPort,
        String accessPointId,
        String iam) {
}
