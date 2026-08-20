package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.WorkloadIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Workload identity operations for the AgentCore control plane. These are RPC-style:
 * the operation name is a literal path segment ({@code POST /identities/<Operation>})
 * and all input travels in the JSON body.
 */
@Path("/identities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockAgentCoreIdentityController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreIdentityController.class);

    private final BedrockAgentCoreIdentityService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreIdentityController(BedrockAgentCoreIdentityService service,
                                              RegionResolver regionResolver,
                                              ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/CreateWorkloadIdentity")
    public Response create(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            WorkloadIdentity identity = service.create(text(req, "name"),
                    urls(req.get("allowedResourceOauth2ReturnUrls")), region);
            return Response.status(201).entity(identityNode(identity, false)).build();
        } catch (Exception e) {
            return error(e, "creating workload identity");
        }
    }

    @POST
    @Path("/GetWorkloadIdentity")
    public Response get(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            WorkloadIdentity identity = service.get(text(req, "name"), region);
            return Response.ok(identityNode(identity, true)).build();
        } catch (Exception e) {
            return error(e, "getting workload identity");
        }
    }

    @POST
    @Path("/UpdateWorkloadIdentity")
    public Response update(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            WorkloadIdentity identity = service.update(text(req, "name"),
                    urls(req.get("allowedResourceOauth2ReturnUrls")), region);
            return Response.ok(identityNode(identity, true)).build();
        } catch (Exception e) {
            return error(e, "updating workload identity");
        }
    }

    @POST
    @Path("/DeleteWorkloadIdentity")
    public Response delete(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            service.delete(text(req, "name"), region);
            return Response.noContent().build();
        } catch (Exception e) {
            return error(e, "deleting workload identity");
        }
    }

    @POST
    @Path("/ListWorkloadIdentities")
    public Response list(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            Integer maxResults = req.hasNonNull("maxResults") ? req.get("maxResults").asInt() : null;
            String nextToken = text(req, "nextToken");
            PaginatedResult<WorkloadIdentity> result = service.list(maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode arr = out.putArray("workloadIdentities");
            for (WorkloadIdentity identity : result.items()) {
                ObjectNode node = arr.addObject();
                node.put("name", identity.getName());
                node.put("workloadIdentityArn", identity.getWorkloadIdentityArn());
            }
            if (result.nextToken() != null) {
                out.put("nextToken", result.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing workload identities");
        }
    }

    private ObjectNode identityNode(WorkloadIdentity identity, boolean full) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", identity.getName());
        node.put("workloadIdentityArn", identity.getWorkloadIdentityArn());
        ArrayNode urls = node.putArray("allowedResourceOauth2ReturnUrls");
        if (identity.getAllowedResourceOauth2ReturnUrls() != null) {
            identity.getAllowedResourceOauth2ReturnUrls().forEach(urls::add);
        }
        if (full) {
            putInstant(node, "createdTime", identity.getCreatedTime());
            putInstant(node, "lastUpdatedTime", identity.getLastUpdatedTime());
        }
        return node;
    }

    private static void putInstant(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            // Workload-identity timestamps are modeled as epoch seconds (unixTimestamp),
            // unlike the runtime timestamps which are ISO-8601 strings.
            node.put(field, instant.getEpochSecond());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static List<String> urls(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        node.forEach(n -> list.add(n.asText()));
        return list;
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
