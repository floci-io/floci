package io.github.hectorvent.floci.services.ses.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

/**
 * A SES v2 tenant (multi-tenancy). Created by {@code CreateTenant} and returned by
 * {@code GetTenant}/{@code ListTenants}. {@code sendingStatus} is the AWS {@code SendingStatus} enum
 * (ENABLED / REINSTATED / DISABLED); a freshly created tenant is ENABLED.
 */
@RegisterForReflection
public record Tenant(String tenantName,
                     String tenantId,
                     String tenantArn,
                     Instant createdTimestamp,
                     List<Tag> tags,
                     String sendingStatus) {
}
