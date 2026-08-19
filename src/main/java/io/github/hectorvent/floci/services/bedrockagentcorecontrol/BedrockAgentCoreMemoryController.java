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
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Memory;
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

/**
 * AgentCore memory endpoints. The operation is a literal path suffix
 * ({@code /memories/create}, {@code /memories/{id}/details}, …).
 */
@Path("/memories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockAgentCoreMemoryController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreMemoryController.class);

    private final BedrockAgentCoreMemoryService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreMemoryController(BedrockAgentCoreMemoryService service,
                                            RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/create")
    public Response createMemory(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            Integer expiry = req.hasNonNull("eventExpiryDuration") ? req.get("eventExpiryDuration").asInt() : null;
            Memory memory = service.create(text(req, "name"), expiry, text(req, "description"),
                    text(req, "clientToken"), region);
            return Response.status(202).entity(wrapped(memory, region)).build();
        } catch (Exception e) {
            return error(e, "creating memory");
        }
    }

    @GET
    @Path("/{memoryId}/details")
    public Response getMemory(@Context HttpHeaders headers, @PathParam("memoryId") String id,
                              @QueryParam("view") String view) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(wrapped(service.get(id, region), region)).build();
        } catch (Exception e) {
            return error(e, "getting memory");
        }
    }

    @PUT
    @Path("/{memoryId}/update")
    public Response updateMemory(@Context HttpHeaders headers, @PathParam("memoryId") String id, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            Memory memory = service.update(id, text(req, "description"), region);
            return Response.status(202).entity(wrapped(memory, region)).build();
        } catch (Exception e) {
            return error(e, "updating memory");
        }
    }

    @DELETE
    @Path("/{memoryId}/delete")
    public Response deleteMemory(@Context HttpHeaders headers, @PathParam("memoryId") String id,
                                 @QueryParam("clientToken") String clientToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Memory memory = service.delete(id, clientToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("memoryId", memory.getMemoryId());
            out.put("status", memory.getStatus());
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return error(e, "deleting memory");
        }
    }

    @POST
    @Path("/")
    public Response listMemories(@Context HttpHeaders headers,
                                 @QueryParam("maxResults") String maxResultsParam,
                                 @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            PaginatedResult<Memory> result = service.list(maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode arr = out.putArray("memories");
            for (Memory memory : result.items()) {
                ObjectNode node = arr.addObject();
                node.put("arn", service.arn(memory, region));
                node.put("id", memory.getMemoryId());
                node.put("status", memory.getStatus());
                putInstant(node, "createdAt", memory.getCreatedAt());
                putInstant(node, "updatedAt", memory.getUpdatedAt());
            }
            if (result.nextToken() != null) {
                out.put("nextToken", result.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing memories");
        }
    }

    private ObjectNode wrapped(Memory memory, String region) {
        ObjectNode out = objectMapper.createObjectNode();
        ObjectNode node = out.putObject("memory");
        node.put("arn", service.arn(memory, region));
        node.put("id", memory.getMemoryId());
        node.put("name", memory.getName());
        node.put("status", memory.getStatus());
        if (memory.getDescription() != null) {
            node.put("description", memory.getDescription());
        }
        if (memory.getEventExpiryDuration() != null) {
            node.put("eventExpiryDuration", memory.getEventExpiryDuration());
        }
        putInstant(node, "createdAt", memory.getCreatedAt());
        putInstant(node, "updatedAt", memory.getUpdatedAt());
        return out;
    }

    private static void putInstant(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            // Memory timestamps are modeled as epoch seconds (unixTimestamp).
            node.put(field, instant.getEpochSecond());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
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
