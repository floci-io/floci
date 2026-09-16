package io.github.hectorvent.floci.services.verifiedpermissions.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public record IdentitySource(
        String policyStoreId,
        String identitySourceId,
        String principalEntityType,
        JsonNode configuration,
        Instant createdDate,
        Instant lastUpdatedDate) {

    public IdentitySource updated(String nextPrincipalEntityType, JsonNode nextConfiguration, Instant updatedAt) {
        return new IdentitySource(policyStoreId, identitySourceId, nextPrincipalEntityType,
                nextConfiguration, createdDate, updatedAt);
    }
}
