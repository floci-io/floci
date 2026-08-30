package io.github.hectorvent.floci.services.ses.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A SES v2 tenant→resource association, created by {@code CreateTenantResourceAssociation}.
 * {@code resourceType} is the wire value AWS uses for these APIs — the ARN segment
 * ({@code identity} / {@code configuration-set} / {@code template}), not the SDK enum spelling.
 */
@RegisterForReflection
public record TenantResourceAssociation(String tenantName,
                                        String tenantId,
                                        String resourceArn,
                                        String resourceType,
                                        Instant associatedTimestamp) {
}
