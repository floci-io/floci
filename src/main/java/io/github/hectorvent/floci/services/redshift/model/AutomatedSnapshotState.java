package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** Restart checkpoint for a cluster's currently effective snapshot rules. */
@RegisterForReflection
public record AutomatedSnapshotState(String scheduleKey, Instant anchor, Instant lastScheduledTime) { }
