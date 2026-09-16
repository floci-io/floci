package io.github.hectorvent.floci.services.marketplace.model;

import java.time.Instant;
import java.util.Map;

/** Immutable deployment parameter identity used for local AWS-compatible state. */
public record MarketplaceDeploymentParameter(
        String catalog,
        String productId,
        String agreementId,
        String name,
        String secretString,
        Instant expirationDate,
        Map<String, String> tags) {
    public MarketplaceDeploymentParameter {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }
}
