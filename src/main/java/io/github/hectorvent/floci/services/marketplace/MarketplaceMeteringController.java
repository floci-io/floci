package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** Service-specific controller invoked by the shared AWS JSON protocol handler. */
@ApplicationScoped
public class MarketplaceMeteringController {
    private final MarketplaceMeteringService service;

    @Inject
    public MarketplaceMeteringController(MarketplaceMeteringService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        JsonNode result = service.handle(action, request, region);
        return result == null ? null : Response.ok(result).build();
    }
}
