package io.github.hectorvent.floci.services.marketplace.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** Buyer dashboard embed result returned by Marketplace Reporting. */
@RegisterForReflection
public record MarketplaceBuyerDashboard(
        String dashboardIdentifier,
        String embedUrl,
        List<String> embeddingDomains) {
    public MarketplaceBuyerDashboard {
        embeddingDomains = embeddingDomains == null ? List.of() : List.copyOf(embeddingDomains);
    }
}
