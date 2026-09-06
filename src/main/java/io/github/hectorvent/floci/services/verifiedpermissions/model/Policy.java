package io.github.hectorvent.floci.services.verifiedpermissions.model;

import java.time.Instant;

public record Policy(
        String policyStoreId,
        String policyId,
        String name,
        String policyType,
        String statement,
        String description,
        String policyTemplateId,
        EntityIdentifier principal,
        EntityIdentifier resource,
        String effect,
        Instant createdDate,
        Instant lastUpdatedDate) {
    public Policy updated(String nextName, String nextStatement, String nextDescription, Instant updatedAt) {
        return new Policy(policyStoreId, policyId, nextName, policyType, nextStatement, nextDescription,
                policyTemplateId, principal, resource, effect, createdDate, updatedAt);
    }
}
