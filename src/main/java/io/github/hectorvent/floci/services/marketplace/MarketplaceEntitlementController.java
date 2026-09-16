package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** Service-specific controller invoked by the shared AWS JSON 1.1 protocol handler. */
@ApplicationScoped
public class MarketplaceEntitlementController {
    private final MarketplaceEntitlementService service;

    @Inject
    public MarketplaceEntitlementController(MarketplaceEntitlementService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "GetEntitlements" -> Response.ok(service.getEntitlements(request, region)).build();
            default -> null;
        };
    }
}
