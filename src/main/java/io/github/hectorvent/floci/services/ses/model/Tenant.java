package io.github.hectorvent.floci.services.ses.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

/**
 * A SES v2 tenant (multi-tenancy). Created by {@code CreateTenant} and returned by
 * {@code GetTenant}/{@code ListTenants}. {@code sendingStatus} is the AWS {@code SendingStatus} enum
 * (ENABLED / REINSTATED / DISABLED): the tenant's aggregate status as a reputation entity, ENABLED
 * for a freshly created tenant. {@code reputationPolicy} is the policy ARN set by
 * {@code UpdateReputationEntityPolicy}, null for the default Standard policy, and
 * {@code customerManagedStatus} the record set by {@code UpdateReputationEntityCustomerManagedStatus},
 * null until the first update.
 */
@RegisterForReflection
public record Tenant(String tenantName,
                     String tenantId,
                     String tenantArn,
                     Instant createdTimestamp,
                     List<Tag> tags,
                     String sendingStatus,
                     TenantSuppressionAttributes suppressionAttributes,
                     String reputationPolicy,
                     ReputationStatusRecord customerManagedStatus) {

    /** Copy with different tags (records are immutable; the store is replace-only). */
    public Tenant withTags(List<Tag> newTags) {
        return new Tenant(tenantName, tenantId, tenantArn, createdTimestamp, newTags, sendingStatus,
                suppressionAttributes, reputationPolicy, customerManagedStatus);
    }

    /** Copy with different suppression attributes (records are immutable; the store is replace-only). */
    public Tenant withSuppressionAttributes(TenantSuppressionAttributes attrs) {
        return new Tenant(tenantName, tenantId, tenantArn, createdTimestamp, tags, sendingStatus, attrs,
                reputationPolicy, customerManagedStatus);
    }

    /** Copy with a different reputation management policy ARN. */
    public Tenant withReputationPolicy(String policy) {
        return new Tenant(tenantName, tenantId, tenantArn, createdTimestamp, tags, sendingStatus,
                suppressionAttributes, policy, customerManagedStatus);
    }

    /**
     * Copy with a different customer-managed status. SES manages no status of its own here, so the
     * customer-managed one is the aggregate the tenant reports as its {@code sendingStatus}.
     */
    public Tenant withCustomerManagedStatus(ReputationStatusRecord status) {
        return new Tenant(tenantName, tenantId, tenantArn, createdTimestamp, tags, status.status(),
                suppressionAttributes, reputationPolicy, status);
    }
}
