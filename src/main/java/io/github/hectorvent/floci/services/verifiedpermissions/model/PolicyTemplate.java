package io.github.hectorvent.floci.services.verifiedpermissions.model;

import java.time.Instant;

public record PolicyTemplate(
        String policyStoreId,
        String policyTemplateId,
        String name,
        String statement,
        String description,
        Instant createdDate,
        Instant lastUpdatedDate) {
    public PolicyTemplate updated(String nextName, String nextStatement, String nextDescription, Instant updatedAt) {
        return new PolicyTemplate(policyStoreId, policyTemplateId, nextName, nextStatement, nextDescription,
                createdDate, updatedAt);
    }
}
