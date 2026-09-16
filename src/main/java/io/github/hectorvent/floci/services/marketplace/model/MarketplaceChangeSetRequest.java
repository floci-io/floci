package io.github.hectorvent.floci.services.marketplace.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Immutable snapshot used to validate StartChangeSet idempotent retries. */
public record MarketplaceChangeSetRequest(String clientRequestToken, JsonNode payload) {
    public MarketplaceChangeSetRequest {
        payload = payload == null ? null : payload.deepCopy();
    }

    public boolean matches(JsonNode other) {
        return payload != null && payload.equals(other);
    }
}
