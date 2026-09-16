package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/2026-02-05")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MarketplaceDiscoveryController {
    private final MarketplaceDiscoveryService service;
    private final ObjectReader strictReader;
    private final RequestContext context;

    @Inject
    public MarketplaceDiscoveryController(MarketplaceDiscoveryService service, ObjectMapper mapper, RequestContext context) {
        this.service = service;
        this.strictReader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.context = context;
    }
    @POST
    @Path("/getListing")
    public Response getListing(String body) { return ok(service.getListing(parse(body), region())); }

    @POST
    @Path("/getOffer")
    public Response getOffer(String body) { return ok(service.getOffer(parse(body), region())); }

    @POST
    @Path("/getOfferSet")
    public Response getOfferSet(String body) { return ok(service.getOfferSet(parse(body), region())); }

    @POST
    @Path("/getOfferTerms")
    public Response getOfferTerms(String body) { return ok(service.getOfferTerms(parse(body), region())); }

    @POST
    @Path("/getProduct")
    public Response getProduct(String body) { return ok(service.getProduct(parse(body), region())); }

    @POST
    @Path("/listFulfillmentOptions")
    public Response listFulfillmentOptions(String body) { return ok(service.listFulfillmentOptions(parse(body), region())); }

    @POST
    @Path("/listPurchaseOptions")
    public Response listPurchaseOptions(String body) { return ok(service.listPurchaseOptions(parse(body), region())); }

    @POST
    @Path("/searchFacets")
    public Response searchFacets(String body) { return ok(service.searchFacets(parse(body), region())); }

    @POST
    @Path("/searchListings")
    public Response searchListings(String body) { return ok(service.searchListings(parse(body), region())); }

    private JsonNode parse(String body) {
        try {
            JsonNode node = strictReader.readTree(body == null || body.isBlank() ? "{}" : body);
            if (node == null || !node.isObject()) {
                throw validation("Request body must be a JSON object.");
            }
            return node;
        } catch (AwsException e) { throw e; }
        catch (Exception e) { throw validation("Request body is not valid JSON."); }
    }
    private String region() { return context.getRegion() == null ? "us-east-1" : context.getRegion(); }
    private Response ok(JsonNode node) { return Response.ok(node).build(); }
    private static AwsException validation(String message) { return new AwsException("ValidationException", message, 400); }
}
