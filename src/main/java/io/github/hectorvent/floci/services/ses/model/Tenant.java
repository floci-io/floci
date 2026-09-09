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
                     String sendingStatus,
                     TenantSuppressionAttributes suppressionAttributes) {

    /** Copy with different tags (records are immutable; the store is replace-only). */
    public Tenant withTags(List<Tag> newTags) {
        return new Tenant(tenantName, tenantId, tenantArn, createdTimestamp, newTags, sendingStatus,
                suppressionAttributes);
    }

    /** Copy with different suppression attributes (records are immutable; the store is replace-only). */
    public Tenant withSuppressionAttributes(TenantSuppressionAttributes attrs) {
        return new Tenant(tenantName, tenantId, tenantArn, createdTimestamp, tags, sendingStatus, attrs);
    }
}
