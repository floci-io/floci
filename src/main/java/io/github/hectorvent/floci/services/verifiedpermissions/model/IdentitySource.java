package io.github.hectorvent.floci.services.verifiedpermissions.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

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
