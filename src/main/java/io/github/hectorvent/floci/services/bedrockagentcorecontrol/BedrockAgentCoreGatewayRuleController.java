package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@Path("/gateways")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockAgentCoreGatewayRuleController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreGatewayRuleController.class);

    private final BedrockAgentCoreGatewayRuleService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreGatewayRuleController(BedrockAgentCoreGatewayRuleService service,
                                                 RegionResolver regionResolver,
                                                 ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/{gatewayIdentifier}/rules")
    public Response createGatewayRule(@Context HttpHeaders headers,
                                      @PathParam("gatewayIdentifier") String gatewayId,
                                      String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode rule = service.create(gatewayId, object(body), region);
            ObjectNode response = rule.deepCopy();
            response.remove("clientToken");
            response.remove("updatedAt");
            return Response.status(202).entity(response).build();
        } catch (Exception e) {
            return error(e, "creating gateway rule");
        }
    }

    @GET
    @Path("/{gatewayIdentifier}/rules/{ruleId}")
    public Response getGatewayRule(@Context HttpHeaders headers,
                                   @PathParam("gatewayIdentifier") String gatewayId,
                                   @PathParam("ruleId") String ruleId) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode response = service.get(gatewayId, ruleId, region).deepCopy();
            response.remove("clientToken");
            return Response.ok(response).build();
        } catch (Exception e) {
            return error(e, "getting gateway rule");
        }
    }

    @GET
    @Path("/{gatewayIdentifier}/rules")
    public Response listGatewayRules(@Context HttpHeaders headers,
                                     @PathParam("gatewayIdentifier") String gatewayId,
                                     @QueryParam("maxResults") String maxResultsParam,
                                     @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            var result = service.list(gatewayId, maxResults, nextToken, region);
            ObjectNode response = objectMapper.createObjectNode();
            var rules = response.putArray("gatewayRules");
            for (ObjectNode rule : result.items()) {
                ObjectNode copy = rule.deepCopy();
                copy.remove("clientToken");
                rules.add(copy);
            }
            if (result.nextToken() != null) {
                response.put("nextToken", result.nextToken());
            }
            return Response.ok(response).build();
        } catch (Exception e) {
            return error(e, "listing gateway rules");
        }
    }

    @PATCH
    @Path("/{gatewayIdentifier}/rules/{ruleId}")
    public Response updateGatewayRule(@Context HttpHeaders headers,
                                      @PathParam("gatewayIdentifier") String gatewayId,
                                      @PathParam("ruleId") String ruleId,
                                      String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ObjectNode response = service.update(gatewayId, ruleId, object(body), region);
            response.remove("clientToken");
            return Response.status(202).entity(response).build();
        } catch (Exception e) {
            return error(e, "updating gateway rule");
        }
    }

    @DELETE
    @Path("/{gatewayIdentifier}/rules/{ruleId}")
    public Response deleteGatewayRule(@Context HttpHeaders headers,
                                      @PathParam("gatewayIdentifier") String gatewayId,
                                      @PathParam("ruleId") String ruleId) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.status(202).entity(service.delete(gatewayId, ruleId, region)).build();
        } catch (Exception e) {
            return error(e, "deleting gateway rule");
        }
    }

    private ObjectNode object(String body) throws Exception {
        JsonNode request = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
        if (!request.isObject()) {
            throw new AwsException("ValidationException", "request body must be a JSON object", 400);
        }
        return (ObjectNode) request;
    }

    private Response error(Exception e, String action) {
        return BedrockAgentCoreControllerSupport.error(LOG, e, action);
    }
}
