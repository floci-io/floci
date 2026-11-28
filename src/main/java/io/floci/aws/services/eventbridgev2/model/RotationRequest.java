package io.floci.aws.services.eventbridgev2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** A rotation a source remembers by its ClientToken: a hash of the request and the time it rotated. */
@RegisterForReflection
public record RotationRequest(String requestHash, Instant rotatedAt) {
}
