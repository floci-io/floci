package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntimeVersion;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.ListResult;
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
import java.util.HashMap;
import java.util.Map;

/**
 * Amazon Bedrock AgentCore control-plane REST-JSON endpoints (runtime registry).
 *
 * <p>Real AgentCore control plane uses {@code PUT /runtimes/}, {@code POST /runtimes/}
 * (list), {@code GET|PUT|DELETE /runtimes/{id}/}, and {@code POST /runtimes/{id}/versions/}.
 * Mutations return HTTP 202; reads/lists return 200. No real agent execution.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockAgentCoreControlController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreControlController.class);

    private final BedrockAgentCoreControlService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreControlController(BedrockAgentCoreControlService service,
                                             RegionResolver regionResolver,
                                             ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @PUT
    @Path("/runtimes/")
    public Response createAgentRuntime(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            AgentRuntime runtime = service.createAgentRuntime(
                    text(req, "agentRuntimeName"),
                    obj(req, "agentRuntimeArtifact"),
                    obj(req, "networkConfiguration"),
                    text(req, "roleArn"),
                    text(req, "description"),
                    stringMap(req.get("environmentVariables")),
                    obj(req, "authorizerConfiguration"),
                    obj(req, "protocolConfiguration"),
                    region);
            Map<String, String> tags = stringMap(req.get("tags"));
            if (tags != null) {
                runtime.getTags().putAll(tags);
            }
            String version = String.valueOf(runtime.getLatestVersion());

            ObjectNode out = objectMapper.createObjectNode();
            out.put("agentRuntimeArn", service.arn(runtime, version, region));
            out.put("agentRuntimeId", runtime.getAgentRuntimeId());
            out.put("agentRuntimeVersion", version);
            putInstant(out, "createdAt", runtime.getCreatedAt());
            out.put("status", runtime.getStatus());
            workloadIdentity(out, runtime);
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return error(e, "creating runtime");
        }
    }

    @POST
    @Path("/runtimes/")
    public Response listAgentRuntimes(@Context HttpHeaders headers,
                                      @QueryParam("maxResults") Integer maxResults,
                                      @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ListResult<AgentRuntime> result =
                    service.listAgentRuntimes(maxResults != null ? maxResults : 0, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode arr = out.putArray("agentRuntimes");
            for (AgentRuntime runtime : result.items()) {
                arr.add(summary(runtime, String.valueOf(runtime.getLatestVersion()), region));
            }
            if (result.nextToken() != null) {
                out.put("nextToken", result.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing runtimes");
        }
    }

    @GET
    @Path("/runtimes/{agentRuntimeId}/")
    public Response getAgentRuntime(@Context HttpHeaders headers,
                                    @PathParam("agentRuntimeId") String id,
                                    @QueryParam("version") String version) {
        String region = regionResolver.resolveRegion(headers);
        try {
            AgentRuntime runtime = service.getAgentRuntime(id, region);
            AgentRuntimeVersion snap = service.resolveVersion(runtime, version);
            String v = snap.getVersion();

            ObjectNode out = objectMapper.createObjectNode();
            out.put("agentRuntimeArn", service.arn(runtime, v, region));
            out.put("agentRuntimeId", runtime.getAgentRuntimeId());
            out.put("agentRuntimeName", runtime.getAgentRuntimeName());
            out.put("agentRuntimeVersion", v);
            if (snap.getAgentRuntimeArtifact() != null) {
                out.set("agentRuntimeArtifact", snap.getAgentRuntimeArtifact());
            }
            if (snap.getNetworkConfiguration() != null) {
                out.set("networkConfiguration", snap.getNetworkConfiguration());
            }
            if (runtime.getAuthorizerConfiguration() != null) {
                out.set("authorizerConfiguration", runtime.getAuthorizerConfiguration());
            }
            if (runtime.getProtocolConfiguration() != null) {
                out.set("protocolConfiguration", runtime.getProtocolConfiguration());
            }
            if (snap.getRoleArn() != null) {
                out.put("roleArn", snap.getRoleArn());
            }
            if (snap.getDescription() != null) {
                out.put("description", snap.getDescription());
            }
            if (snap.getEnvironmentVariables() != null && !snap.getEnvironmentVariables().isEmpty()) {
                out.set("environmentVariables", objectMapper.valueToTree(snap.getEnvironmentVariables()));
            }
            putInstant(out, "createdAt", runtime.getCreatedAt());
            putInstant(out, "lastUpdatedAt", runtime.getLastUpdatedAt());
            out.put("status", runtime.getStatus());
            workloadIdentity(out, runtime);
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "getting runtime");
        }
    }

    @PUT
    @Path("/runtimes/{agentRuntimeId}/")
    public Response updateAgentRuntime(@Context HttpHeaders headers,
                                       @PathParam("agentRuntimeId") String id,
                                       String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode req = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            AgentRuntime runtime = service.updateAgentRuntime(
                    id,
                    obj(req, "agentRuntimeArtifact"),
                    obj(req, "networkConfiguration"),
                    text(req, "roleArn"),
                    text(req, "description"),
                    stringMap(req.get("environmentVariables")),
                    obj(req, "authorizerConfiguration"),
                    obj(req, "protocolConfiguration"),
                    region);
            String version = String.valueOf(runtime.getLatestVersion());

            ObjectNode out = objectMapper.createObjectNode();
            out.put("agentRuntimeArn", service.arn(runtime, version, region));
            out.put("agentRuntimeId", runtime.getAgentRuntimeId());
            out.put("agentRuntimeVersion", version);
            putInstant(out, "createdAt", runtime.getCreatedAt());
            putInstant(out, "lastUpdatedAt", runtime.getLastUpdatedAt());
            out.put("status", runtime.getStatus());
            workloadIdentity(out, runtime);
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return error(e, "updating runtime");
        }
    }

    @POST
    @Path("/runtimes/{agentRuntimeId}/versions/")
    public Response listAgentRuntimeVersions(@Context HttpHeaders headers,
                                             @PathParam("agentRuntimeId") String id,
                                             @QueryParam("maxResults") Integer maxResults,
                                             @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            AgentRuntime runtime = service.getAgentRuntime(id, region);
            ListResult<AgentRuntimeVersion> result =
                    service.listAgentRuntimeVersions(id, maxResults != null ? maxResults : 0, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode arr = out.putArray("agentRuntimes");
            for (AgentRuntimeVersion snap : result.items()) {
                arr.add(versionSummary(runtime, snap, region));
            }
            if (result.nextToken() != null) {
                out.put("nextToken", result.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing runtime versions");
        }
    }

    @DELETE
    @Path("/runtimes/{agentRuntimeId}/")
    public Response deleteAgentRuntime(@Context HttpHeaders headers,
                                       @PathParam("agentRuntimeId") String id,
                                       @QueryParam("clientToken") String clientToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            AgentRuntime runtime = service.deleteAgentRuntime(id, region);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("agentRuntimeId", runtime.getAgentRuntimeId());
            out.put("status", runtime.getStatus());
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return error(e, "deleting runtime");
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode summary(AgentRuntime runtime, String version, String region) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("agentRuntimeArn", service.arn(runtime, version, region));
        node.put("agentRuntimeId", runtime.getAgentRuntimeId());
        node.put("agentRuntimeName", runtime.getAgentRuntimeName());
        node.put("agentRuntimeVersion", version);
        if (runtime.getDescription() != null) {
            node.put("description", runtime.getDescription());
        }
        putInstant(node, "lastUpdatedAt", runtime.getLastUpdatedAt());
        node.put("status", runtime.getStatus());
        return node;
    }

    private ObjectNode versionSummary(AgentRuntime runtime, AgentRuntimeVersion snap, String region) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("agentRuntimeArn", service.arn(runtime, snap.getVersion(), region));
        node.put("agentRuntimeId", runtime.getAgentRuntimeId());
        node.put("agentRuntimeName", runtime.getAgentRuntimeName());
        node.put("agentRuntimeVersion", snap.getVersion());
        if (snap.getDescription() != null) {
            node.put("description", snap.getDescription());
        }
        putInstant(node, "lastUpdatedAt", snap.getCreatedAt());
        node.put("status", runtime.getStatus());
        return node;
    }

    private void workloadIdentity(ObjectNode out, AgentRuntime runtime) {
        if (runtime.getWorkloadIdentityArn() != null) {
            ObjectNode wi = out.putObject("workloadIdentityDetails");
            wi.put("workloadIdentityArn", runtime.getWorkloadIdentityArn());
        }
    }

    private static void putInstant(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            // AgentCore models these timestamps as ISO-8601 strings, not epoch numbers.
            node.put(field, instant.toString());
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

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, String> map = new HashMap<>();
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
