package io.github.hectorvent.floci.services.kinesisanalytics.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Lifecycle states for an application snapshot, mirroring the {@code SnapshotStatus} enum of the AWS
 * Kinesis Analytics V2 API exactly ({@code CREATING}, {@code READY}, {@code DELETING}, {@code FAILED}).
 * Serialized by name to match the wire shape.
 */
@RegisterForReflection
public enum SnapshotStatus {
    CREATING,
    READY,
    DELETING,
    FAILED
}
