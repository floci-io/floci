package io.github.hectorvent.floci.services.ram.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** One row of a {@code ListPrincipals} response: a principal associated with a resource share. */
@RegisterForReflection
public record PrincipalAssociation(
        String id,
        String resourceShareArn,
        Instant creationTime,
        Instant lastUpdatedTime,
        boolean external) {
}
