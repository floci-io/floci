package io.github.hectorvent.floci.services.aps;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.aps.model.PrometheusWorkspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * Amazon Managed Service for Prometheus (Smithy restJson1, SigV4 scope {@code aps}) — the
 * workspace lifecycle only: create/describe/list/delete plus alias update. Tagging goes through
 * the shared {@code /tags/{resourceArn}} dispatcher, which routes {@code arn:aws:aps:...} to
 * {@link ApsService}'s TagHandler implementation.
 *
 * <p>The literal {@code /workspaces} path segment takes JAX-RS precedence over S3's
 * {@code /{bucket}} template route, so these routes need no extra routing wiring (see
 * {@link io.github.hectorvent.floci.services.controltower.ControlTowerController}).
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ApsController {

    private final ApsService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public ApsController(ApsService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/workspaces")
    public Response createWorkspace(@Context HttpHeaders headers, Map<String, Object> request) {
        Map<String, Object> body = request != null ? request : Map.of();
        String alias = asString(body.get("alias"), "alias");
        String kmsKeyArn = asString(body.get("kmsKeyArn"), "kmsKeyArn");
        Map<String, String> tags = asStringMap(body.get("tags"), "tags");
        // clientToken is accepted and ignored: creates are not deduplicated.

        PrometheusWorkspace workspace =
                service.createWorkspace(regionResolver.resolveRegion(headers), alias, tags, kmsKeyArn);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("workspaceId", workspace.getWorkspaceId());
        response.put("arn", workspace.getArn());
        response.set("status", statusNode(workspace));
        response.set("tags", objectMapper.valueToTree(workspace.getTags()));
        if (workspace.getKmsKeyArn() != null) {
            response.put("kmsKeyArn", workspace.getKmsKeyArn());
        }
        return Response.status(202).entity(response).build();
    }

    @GET
    @Path("/workspaces")
    public Response listWorkspaces(@Context HttpHeaders headers,
                                   @QueryParam("alias") String alias,
                                   @QueryParam("maxResults") String maxResultsParam,
                                   @QueryParam("nextToken") String nextToken) {
        PaginatedResult<PrometheusWorkspace> result = service.listWorkspaces(
                regionResolver.resolveRegion(headers), alias,
                Pagination.parseMaxResults(maxResultsParam, "ValidationException"), nextToken);

        Map<String, Object> response = new HashMap<>();
        response.put("workspaces", result.items().stream().map(this::toWorkspaceSummary).toList());
        if (result.nextToken() != null) {
            response.put("nextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}")
    public Response describeWorkspace(@Context HttpHeaders headers,
                                      @PathParam("workspaceId") String workspaceId) {
        PrometheusWorkspace workspace =
                service.describeWorkspace(regionResolver.resolveRegion(headers), workspaceId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("workspace", toWorkspaceDescription(workspace));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/workspaces/{workspaceId}")
    public Response deleteWorkspace(@Context HttpHeaders headers,
                                    @PathParam("workspaceId") String workspaceId) {
        service.deleteWorkspace(regionResolver.resolveRegion(headers), workspaceId);
        // The model documents "an HTTP 202 response with an empty HTTP body".
        return Response.status(202).build();
    }

    @POST
    @Path("/workspaces/{workspaceId}/alias")
    public Response updateWorkspaceAlias(@Context HttpHeaders headers,
                                         @PathParam("workspaceId") String workspaceId,
                                         Map<String, Object> request) {
        Map<String, Object> body = request != null ? request : Map.of();
        String alias = asString(body.get("alias"), "alias");
        service.updateWorkspaceAlias(regionResolver.resolveRegion(headers), workspaceId, alias);
        return Response.status(204).build();
    }

    private ObjectNode toWorkspaceDescription(PrometheusWorkspace workspace) {
        ObjectNode node = toWorkspaceSummary(workspace);
        node.put("prometheusEndpoint", workspace.getPrometheusEndpoint());
        return node;
    }

    // AWS's WorkspaceSummary shape (ListWorkspaces) carries every WorkspaceDescription member
    // except prometheusEndpoint.
    private ObjectNode toWorkspaceSummary(PrometheusWorkspace workspace) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workspaceId", workspace.getWorkspaceId());
        if (workspace.getAlias() != null) {
            node.put("alias", workspace.getAlias());
        }
        node.put("arn", workspace.getArn());
        node.set("status", statusNode(workspace));
        // Smithy restJson1 timestamps with no timestampFormat trait are epoch-seconds; an ISO
        // string here fails deserialization inside the AWS SDKs.
        node.put("createdAt", workspace.getCreatedAt().toEpochMilli() / 1000.0);
        node.set("tags", objectMapper.valueToTree(workspace.getTags()));
        if (workspace.getKmsKeyArn() != null) {
            node.put("kmsKeyArn", workspace.getKmsKeyArn());
        }
        return node;
    }

    private ObjectNode statusNode(PrometheusWorkspace workspace) {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("statusCode", workspace.getStatus());
        return status;
    }

    private String asString(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new AwsException("ValidationException", fieldName + " must be a string.", 400);
        }
        return s;
    }

    private Map<String, String> asStringMap(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)
                || map.entrySet().stream().anyMatch(e -> !(e.getKey() instanceof String) || !(e.getValue() instanceof String))) {
            throw new AwsException("ValidationException", fieldName + " must be a map of strings.", 400);
        }
        Map<String, String> result = new HashMap<>();
        map.forEach((k, v) -> result.put((String) k, (String) v));
        return result;
    }
}
