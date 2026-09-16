package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Service-specific controller invoked by the shared AWS JSON 1.0 protocol handler. */
@ApplicationScoped
public class MarketplaceAgreementController {
    private final MarketplaceAgreementService service;

    @Inject
    public MarketplaceAgreementController(MarketplaceAgreementService service) {
        this.service = service;
    }

    public JsonNode handle(String action, JsonNode request, String region) {
        return service.handle(action, request, region);
    }
}
