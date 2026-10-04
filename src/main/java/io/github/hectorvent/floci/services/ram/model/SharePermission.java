package io.github.hectorvent.floci.services.ram.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * One entry of a RAM ListResourceSharePermissions response: the AWS managed permission that
 * governs one resource type of a share.
 */
@RegisterForReflection
public record SharePermission(String arn, String name, String resourceType, Instant creationTime,
                              Instant lastUpdatedTime) {
}
