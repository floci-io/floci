package io.github.hectorvent.floci.services.kinesisanalytics.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Lifecycle states for a Managed Service for Apache Flink application, mirroring the
 * {@code ApplicationStatus} enum of the AWS Kinesis Analytics V2 API. Serialized by name
 * (e.g. {@code "READY"}) to match the wire shape.
 *
 * <p>Only the states this emulator transitions through are modelled:
 * {@code CreateApplication} lands in {@link #READY}; {@code StartApplication} moves
 * {@link #STARTING} → {@link #RUNNING} (the backing Flink container coming up);
 * {@code StopApplication} returns to {@link #READY}; {@code DeleteApplication} passes
 * through {@link #DELETING}.
 */
@RegisterForReflection
public enum ApplicationStatus {
    READY,
    STARTING,
    RUNNING,
    STOPPING,
    UPDATING,
    DELETING
}
