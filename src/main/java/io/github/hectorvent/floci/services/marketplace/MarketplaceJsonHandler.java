package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class MarketplaceJsonHandler {
    private final MarketplaceAgreementController agreementController;

    @Inject
    public MarketplaceJsonHandler(MarketplaceAgreementController agreementController) {
        this.agreementController = agreementController;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode response = agreementController.handle(action, request, region);
        return response == null ? null : Response.ok(response).build();
    }
}
