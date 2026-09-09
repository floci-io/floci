package io.github.hectorvent.floci.services.ses.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * A SES v2 tenant's suppression attributes, set by {@code PutTenantSuppressionAttributes} (or on
 * {@code CreateTenant}). AWS treats the pair as all-or-nothing: both members are set together (an
 * empty reason list is a valid state) and the whole block is cleared by a put with neither member.
 */
@RegisterForReflection
public record TenantSuppressionAttributes(List<String> suppressedReasons, String suppressionScope) {
}
