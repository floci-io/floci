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

@Path("/")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MarketplaceReportingController {
    private final MarketplaceReportingService service;
    private final ObjectReader strictReader;
    private final RequestContext context;

    @Inject
    public MarketplaceReportingController(MarketplaceReportingService service, ObjectMapper mapper, RequestContext context) {
        this.service = service;
        this.strictReader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.context = context;
    }

    @POST
    @Path("/getBuyerDashboard")
    public Response getBuyerDashboard(String body) {
        return Response.ok(service.getBuyerDashboard(parse(body), accountId())).build();
    }

    private JsonNode parse(String body) {
        try {
            JsonNode node = strictReader.readTree(body == null || body.isBlank() ? "{}" : body);
            if (node == null || !node.isObject()) {
                throw badRequest("Request body must be a JSON object.");
            }
            return node;
        } catch (AwsException e) { throw e; }
        catch (Exception e) { throw badRequest("Request body is not valid JSON."); }
    }

    private String accountId() { return context.getAccountId() == null ? "000000000000" : context.getAccountId(); }
    private static AwsException badRequest(String message) { return new AwsException("BadRequestException", message, 400); }
}
