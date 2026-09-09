package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Gateway;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.GatewayTarget;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;

/** Standard-REST endpoints for AgentCore gateways and gateway targets. */
@Path("/gateways")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockAgentCoreGatewayController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreGatewayController.class);

    private final BedrockAgentCoreGatewayService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreGatewayController(BedrockAgentCoreGatewayService service,
                                             RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/")
    public Response createGateway(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            Gateway gateway = service.create(text(req, "name"), text(req, "authorizerType"),
                    text(req, "roleArn"), text(req, "description"), stringMap(req.get("tags")), region);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("gatewayArn", service.gatewayArn(gateway, region));
            out.put("gatewayId", gateway.getGatewayId());
            out.put("gatewayUrl", gateway.getGatewayUrl());
            out.put("status", gateway.getStatus());
            out.putArray("statusReasons");
            ObjectNode wi = out.putObject("workloadIdentityDetails");
            wi.put("workloadIdentityArn", gateway.getWorkloadIdentityArn());
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return error(e, "creating gateway");
        }
    }

    @GET
    @Path("/{gatewayIdentifier}/")
    public Response getGateway(@Context HttpHeaders headers, @PathParam("gatewayIdentifier") String id) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(gatewayNode(service.get(id, region), region, true)).build();
        } catch (Exception e) {
            return error(e, "getting gateway");
        }
    }

    @PUT
    @Path("/{gatewayIdentifier}/")
    public Response updateGateway(@Context HttpHeaders headers, @PathParam("gatewayIdentifier") String id,
                                  String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            Gateway gateway = service.update(id, text(req, "name"), text(req, "authorizerType"),
                    text(req, "roleArn"), text(req, "description"), region);
            return Response.status(202).entity(gatewayNode(gateway, region, false)).build();
        } catch (Exception e) {
            return error(e, "updating gateway");
        }
    }

    @DELETE
    @Path("/{gatewayIdentifier}/")
    public Response deleteGateway(@Context HttpHeaders headers, @PathParam("gatewayIdentifier") String id) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Gateway gateway = service.delete(id, region);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("gatewayId", gateway.getGatewayId());
            out.put("status", gateway.getStatus());
            out.putArray("statusReasons");
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return error(e, "deleting gateway");
        }
    }

    @GET
    @Path("/")
    public Response listGateways(@Context HttpHeaders headers,
                                 @QueryParam("maxResults") String maxResultsParam,
                                 @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            PaginatedResult<Gateway> result = service.list(maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode arr = out.putArray("items");
            for (Gateway gateway : result.items()) {
                ObjectNode node = arr.addObject();
                node.put("gatewayId", gateway.getGatewayId());
                node.put("name", gateway.getName());
                node.put("status", gateway.getStatus());
                node.put("authorizerType", gateway.getAuthorizerType());
                node.put("protocolType", gateway.getProtocolType());
                putInstant(node, "createdAt", gateway.getCreatedAt());
                putInstant(node, "updatedAt", gateway.getUpdatedAt());
            }
            if (result.nextToken() != null) {
                out.put("nextToken", result.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing gateways");
        }
    }

    // ── Targets ──

    @POST
    @Path("/{gatewayIdentifier}/targets/")
    public Response createTarget(@Context HttpHeaders headers, @PathParam("gatewayIdentifier") String id,
                                 String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            GatewayTarget target = service.createTarget(id, text(req, "name"),
                    obj(req, "targetConfiguration"), text(req, "description"), region);
            Gateway gateway = service.get(id, region);
            return Response.status(202).entity(targetNode(gateway, target, region, false)).build();
        } catch (Exception e) {
            return error(e, "creating gateway target");
        }
    }

    @GET
    @Path("/{gatewayIdentifier}/targets/{targetId}/")
    public Response getTarget(@Context HttpHeaders headers, @PathParam("gatewayIdentifier") String id,
                              @PathParam("targetId") String targetId) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Gateway gateway = service.get(id, region);
            GatewayTarget target = service.getTarget(id, targetId, region);
            return Response.ok(targetNode(gateway, target, region, true)).build();
        } catch (Exception e) {
            return error(e, "getting gateway target");
        }
    }

    @PUT
    @Path("/{gatewayIdentifier}/targets/{targetId}/")
    public Response updateTarget(@Context HttpHeaders headers, @PathParam("gatewayIdentifier") String id,
                                 @PathParam("targetId") String targetId, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            GatewayTarget target = service.updateTarget(id, targetId,
                    obj(req, "targetConfiguration"), text(req, "description"), region);
            Gateway gateway = service.get(id, region);
            return Response.status(202).entity(targetNode(gateway, target, region, true)).build();
        } catch (Exception e) {
            return error(e, "updating gateway target");
        }
    }

    @DELETE
    @Path("/{gatewayIdentifier}/targets/{targetId}/")
    public Response deleteTarget(@Context HttpHeaders headers, @PathParam("gatewayIdentifier") String id,
                                 @PathParam("targetId") String targetId) {
        String region = regionResolver.resolveRegion(headers);
        try {
            GatewayTarget target = service.deleteTarget(id, targetId, region);
            Gateway gateway = service.get(id, region);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("targetId", target.getTargetId());
            out.put("gatewayArn", service.gatewayArn(gateway, region));
            out.put("status", target.getStatus());
            out.putArray("statusReasons");
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return error(e, "deleting gateway target");
        }
    }

    @GET
    @Path("/{gatewayIdentifier}/targets/")
    public Response listTargets(@Context HttpHeaders headers, @PathParam("gatewayIdentifier") String id,
                                @QueryParam("maxResults") String maxResultsParam,
                                @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            PaginatedResult<GatewayTarget> result = service.listTargets(id, maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode arr = out.putArray("items");
            for (GatewayTarget target : result.items()) {
                ObjectNode node = arr.addObject();
                node.put("targetId", target.getTargetId());
                if (target.getName() != null) {
                    node.put("name", target.getName());
                }
                node.put("status", target.getStatus());
                putInstant(node, "createdAt", target.getCreatedAt());
                putInstant(node, "updatedAt", target.getUpdatedAt());
            }
            if (result.nextToken() != null) {
                out.put("nextToken", result.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing gateway targets");
        }
    }

    // ── Helpers ──

    private ObjectNode gatewayNode(Gateway gateway, String region, boolean full) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("gatewayArn", service.gatewayArn(gateway, region));
        node.put("gatewayId", gateway.getGatewayId());
        node.put("gatewayUrl", gateway.getGatewayUrl());
        node.put("status", gateway.getStatus());
        node.putArray("statusReasons");
        if (full) {
            node.put("name", gateway.getName());
            node.put("roleArn", gateway.getRoleArn());
            node.put("authorizerType", gateway.getAuthorizerType());
            node.put("protocolType", gateway.getProtocolType());
            if (gateway.getDescription() != null) {
                node.put("description", gateway.getDescription());
            }
            putInstant(node, "createdAt", gateway.getCreatedAt());
            putInstant(node, "updatedAt", gateway.getUpdatedAt());
        }
        return node;
    }

    private ObjectNode targetNode(Gateway gateway, GatewayTarget target, String region, boolean full) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("targetId", target.getTargetId());
        node.put("gatewayArn", service.gatewayArn(gateway, region));
        node.put("status", target.getStatus());
        node.put("protocolType", "MCP");
        node.putArray("statusReasons");
        if (full) {
            if (target.getName() != null) {
                node.put("name", target.getName());
            }
            if (target.getTargetConfiguration() != null) {
                node.set("targetConfiguration", target.getTargetConfiguration());
            }
            if (target.getDescription() != null) {
                node.put("description", target.getDescription());
            }
            putInstant(node, "createdAt", target.getCreatedAt());
            putInstant(node, "updatedAt", target.getUpdatedAt());
        }
        return node;
    }

    private static void putInstant(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            node.put(field, instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static JsonNode obj(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull() || v.isMissingNode()) ? null : v;
    }

    private static java.util.Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        java.util.Map<String, String> map = new java.util.HashMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        return map;
    }

    private Response error(Exception e, String action) {
        if (e instanceof AwsException aws) {
            return Response.status(aws.getHttpStatus())
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", aws.jsonType())
                    .entity(new AwsErrorResponse(aws.jsonType(), aws.getMessage()))
                    .build();
        }
        LOG.errorv(e, "Error {0}", action);
        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", "ValidationException")
                .entity(new AwsErrorResponse("ValidationException", e.getMessage()))
                .build();
    }
}
