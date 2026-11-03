package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreHarnessRegistry.HarnessView;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Harness;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.HarnessVersion;
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

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The AgentCore harness control plane under {@code /harnesses}: {@code CreateHarness},
 * {@code GetHarness}, {@code UpdateHarness}, {@code DeleteHarness}, {@code ListHarnesses} and
 * {@code ListHarnessVersions}. The data plane's {@code InvokeHarness} has a class of its own rooted at
 * {@code /harnesses/invoke}, {@code BedrockAgentCoreHarnessInvokeController}.
 */
@Path("/harnesses")
@Produces(MediaType.APPLICATION_JSON)
public class BedrockAgentCoreHarnessController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreHarnessController.class);

    private final BedrockAgentCoreHarnessRegistry registry;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreHarnessController(BedrockAgentCoreHarnessRegistry registry,
                                             RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.registry = registry;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    public Response createHarness(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            HarnessView view = registry.create(object(body), region);
            return Response.status(201).entity(wrapped(view, region)).build();
        } catch (Exception e) {
            return error(e, "creating harness");
        }
    }

    @GET
    public Response listHarnesses(@Context HttpHeaders headers,
                                  @QueryParam("maxResults") String maxResultsParam,
                                  @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            PaginatedResult<Harness> page = registry.list(maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode harnesses = out.putArray("harnesses");
            for (Harness harness : page.items()) {
                ObjectNode summary = harnesses.addObject();
                summary.put("harnessId", harness.getHarnessId());
                summary.put("harnessName", harness.getHarnessName());
                summary.put("arn", registry.arn(harness, region));
                summary.put("status", harness.getStatus());
                putInstant(summary, "createdAt", harness.getCreatedAt());
                putInstant(summary, "updatedAt", harness.getUpdatedAt());
                summary.put("harnessVersion", harness.getCurrentVersion().getVersion());
            }
            if (page.nextToken() != null) {
                out.put("nextToken", page.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing harnesses");
        }
    }

    @GET
    @Path("/{harnessId}")
    public Response getHarness(@Context HttpHeaders headers, @PathParam("harnessId") String harnessId,
                               @QueryParam("harnessVersion") String harnessVersion) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(wrapped(registry.get(harnessId, harnessVersion, region), region)).build();
        } catch (Exception e) {
            return error(e, "getting harness");
        }
    }

    @PATCH
    @Path("/{harnessId}")
    @Consumes(MediaType.WILDCARD)
    public Response updateHarness(@Context HttpHeaders headers, @PathParam("harnessId") String harnessId,
                                  String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(wrapped(registry.update(harnessId, object(body), region), region)).build();
        } catch (Exception e) {
            return error(e, "updating harness");
        }
    }

    /** {@code deleteManagedMemory} is accepted and ignored: Floci creates no managed memory for a harness. */
    @DELETE
    @Path("/{harnessId}")
    public Response deleteHarness(@Context HttpHeaders headers, @PathParam("harnessId") String harnessId,
                                  @QueryParam("clientToken") String clientToken,
                                  @QueryParam("deleteManagedMemory") String deleteManagedMemory) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(wrapped(registry.delete(harnessId, region), region)).build();
        } catch (Exception e) {
            return error(e, "deleting harness");
        }
    }

    @GET
    @Path("/{harnessId}/versions")
    public Response listHarnessVersions(@Context HttpHeaders headers, @PathParam("harnessId") String harnessId,
                                        @QueryParam("maxResults") String maxResultsParam,
                                        @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            Harness harness = registry.get(harnessId, null, region).harness();
            PaginatedResult<HarnessVersion> page = registry.listVersions(harnessId, maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode versions = out.putArray("harnessVersions");
            for (HarnessVersion version : page.items()) {
                ObjectNode summary = versions.addObject();
                summary.put("harnessId", harness.getHarnessId());
                summary.put("harnessName", harness.getHarnessName());
                summary.put("arn", registry.arn(harness, region));
                summary.put("harnessVersion", version.getVersion());
                summary.put("status", harness.getStatus());
                putInstant(summary, "createdAt", version.getCreatedAt());
                putInstant(summary, "updatedAt", version.getCreatedAt());
            }
            if (page.nextToken() != null) {
                out.put("nextToken", page.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing harness versions");
        }
    }

    /** {@code {"harness": {...}}}, the members in the order AWS returns them. */
    private ObjectNode wrapped(HarnessView view, String region) {
        Harness harness = view.harness();
        HarnessVersion version = view.version();
        ObjectNode out = objectMapper.createObjectNode();
        ObjectNode node = out.putObject("harness");
        node.put("harnessId", harness.getHarnessId());
        node.put("harnessName", harness.getHarnessName());
        node.put("arn", registry.arn(harness, region));
        node.put("status", harness.getStatus());
        node.put("harnessVersion", version.getVersion());
        node.put("executionRoleArn", version.getExecutionRoleArn());
        putInstant(node, "createdAt", harness.getCreatedAt());
        putInstant(node, "updatedAt", version.getCreatedAt());
        for (String member : BedrockAgentCoreHarnessRegistry.memberOrder()) {
            JsonNode value = version.getConfiguration().get(member);
            if (value != null) {
                node.set(member, value.deepCopy());
            }
        }
        return out;
    }

    private ObjectNode object(String body) throws IOException {
        JsonNode parsed = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
        if (!parsed.isObject()) {
            throw new AwsException("ValidationException", "Request body must be a JSON object", 400);
        }
        return (ObjectNode) parsed;
    }

    private static void putInstant(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            // ISO-8601, as AgentCore models these timestamps.
            node.put(field, instant.truncatedTo(ChronoUnit.MILLIS).toString());
        }
    }

    private Response error(Exception e, String action) {
        return BedrockAgentCoreControllerSupport.error(LOG, e, action);
    }
}
