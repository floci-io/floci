package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The {@code efsVolumeConfiguration} of an ECS task-definition volume:
 * {@code {"fileSystemId": ..., "rootDirectory": ..., "transitEncryption": ...,
 * "transitEncryptionPort": ..., "authorizationConfig": {"accessPointId": ..., "iam": ...}}}.
 *
 * <p>A container references the owning {@link Volume} by name via a {@link MountPoint}.
 * Docker cannot mount a real Amazon EFS file system, so Floci materialises an EFS-backed
 * volume as a shared local Docker <em>named volume</em>: every container that mounts the same
 * file system with the same effective root (its {@code rootDirectory}, or the access point's
 * own root directory when {@code authorizationConfig.accessPointId} is set) shares persistent
 * storage that survives task restarts, the local equivalent of an EFS mount, while a different
 * {@code rootDirectory} or {@code accessPointId} on the same file system lands on an isolated
 * volume, matching how AWS scopes an EFS mount to a subpath.
 *
 * <p>{@code transitEncryption} and {@code transitEncryptionPort} are modelled for
 * RegisterTaskDefinition/DescribeTaskDefinition round-trip fidelity (so Terraform sees no
 * drift) but have no effect on the local mount, since Docker has no notion of an encrypted
 * NFS transport to emulate.
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
