package io.github.hectorvent.floci.services.pipes;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import io.github.hectorvent.floci.services.pipes.model.PipeState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EventBridge Pipes REST-JSON controller.
 *
 * <p>Pipes uses standard HTTP verbs with JSON bodies — not JSON 1.1 (X-Amz-Target) or Query protocol.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PipesController {

    private static final Logger LOG = Logger.getLogger(PipesController.class);

    private final PipesService pipesService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public PipesController(PipesService pipesService, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.pipesService = pipesService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/v1/pipes/{name}")
    public Response createPipe(@PathParam("name") String name,
                               @Context HttpHeaders headers,
                               String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String source = textOrNull(request, "Source");
            String target = textOrNull(request, "Target");
            String roleArn = textOrNull(request, "RoleArn");
            String description = textOrNull(request, "Description");
            String enrichment = textOrNull(request, "Enrichment");
            DesiredState desiredState = parseDesiredState(textOrNull(request, "DesiredState"));
            JsonNode sourceParameters = request.path("SourceParameters").isMissingNode() ? null : request.get("SourceParameters");
            JsonNode targetParameters = request.path("TargetParameters").isMissingNode() ? null : request.get("TargetParameters");
            JsonNode enrichmentParameters = request.path("EnrichmentParameters").isMissingNode() ? null : request.get("EnrichmentParameters");
            Map<String, String> tags = parseTags(request.get("Tags"));

            Pipe pipe = pipesService.createPipe(name, source, target, roleArn, description,
                    desiredState, enrichment, sourceParameters, targetParameters,
                    enrichmentParameters, tags, region);

            return Response.ok(buildPipeResponse(pipe)).build();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.getErrorCode(), e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorv("Error creating pipe: {0}", e.getMessage());
            return Response.status(500)
                    .entity(new AwsErrorResponse("InternalException", e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/v1/pipes/{name}")
    public Response describePipe(@PathParam("name") String name,
                                 @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Pipe pipe = pipesService.describePipe(name, region);
            return Response.ok(pipe).build();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.getErrorCode(), e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/v1/pipes/{name}")
    public Response updatePipe(@PathParam("name") String name,
                               @Context HttpHeaders headers,
                               String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String target = textOrNull(request, "Target");
            String roleArn = textOrNull(request, "RoleArn");
            String description = textOrNull(request, "Description");
            String enrichment = textOrNull(request, "Enrichment");
            DesiredState desiredState = parseDesiredState(textOrNull(request, "DesiredState"));
            JsonNode sourceParameters = request.path("SourceParameters").isMissingNode() ? null : request.get("SourceParameters");
            JsonNode targetParameters = request.path("TargetParameters").isMissingNode() ? null : request.get("TargetParameters");
            JsonNode enrichmentParameters = request.path("EnrichmentParameters").isMissingNode() ? null : request.get("EnrichmentParameters");

            Pipe pipe = pipesService.updatePipe(name, target, roleArn, description,
                    desiredState, enrichment, sourceParameters, targetParameters,
                    enrichmentParameters, region);

            return Response.ok(buildPipeResponse(pipe)).build();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.getErrorCode(), e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorv("Error updating pipe: {0}", e.getMessage());
            return Response.status(500)
                    .entity(new AwsErrorResponse("InternalException", e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/v1/pipes/{name}")
    public Response deletePipe(@PathParam("name") String name,
                               @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        try {
            pipesService.deletePipe(name, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.getErrorCode(), e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/v1/pipes")
    public Response listPipes(@QueryParam("NamePrefix") String namePrefix,
                              @QueryParam("SourcePrefix") String sourcePrefix,
                              @QueryParam("TargetPrefix") String targetPrefix,
                              @QueryParam("DesiredState") String desiredStateStr,
                              @QueryParam("CurrentState") String currentStateStr,
                              @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        try {
            DesiredState desiredState = parseDesiredState(desiredStateStr);
            PipeState currentState = parsePipeState(currentStateStr);
            List<Pipe> pipes = pipesService.listPipes(namePrefix, sourcePrefix, targetPrefix,
                    desiredState, currentState, region);

            ObjectNode response = objectMapper.createObjectNode();
            var pipesArray = response.putArray("Pipes");
            for (Pipe pipe : pipes) {
                pipesArray.add(buildPipeListEntry(pipe));
            }
            return Response.ok(response).build();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.getErrorCode(), e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/v1/pipes/{name}/start")
    public Response startPipe(@PathParam("name") String name,
                              @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Pipe pipe = pipesService.startPipe(name, region);
            return Response.ok(buildPipeResponse(pipe)).build();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.getErrorCode(), e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/v1/pipes/{name}/stop")
    public Response stopPipe(@PathParam("name") String name,
                             @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Pipe pipe = pipesService.stopPipe(name, region);
            return Response.ok(buildPipeResponse(pipe)).build();
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.getErrorCode(), e.getMessage()))
                    .build();
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode buildPipeResponse(Pipe pipe) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", pipe.getArn());
        node.put("Name", pipe.getName());
        node.put("Source", pipe.getSource());
        node.put("Target", pipe.getTarget());
        node.put("RoleArn", pipe.getRoleArn());
        node.put("DesiredState", pipe.getDesiredState().name());
        node.put("CurrentState", pipe.getCurrentState().name());
        if (pipe.getDescription() != null) node.put("Description", pipe.getDescription());
        if (pipe.getEnrichment() != null) node.put("Enrichment", pipe.getEnrichment());
        if (pipe.getCreationTime() != null) node.put("CreationTime", pipe.getCreationTime().getEpochSecond());
        if (pipe.getLastModifiedTime() != null) node.put("LastModifiedTime", pipe.getLastModifiedTime().getEpochSecond());
        if (pipe.getStateReason() != null) node.put("StateReason", pipe.getStateReason());
        return node;
    }

    private ObjectNode buildPipeListEntry(Pipe pipe) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", pipe.getArn());
        node.put("Name", pipe.getName());
        node.put("Source", pipe.getSource());
        node.put("Target", pipe.getTarget());
        node.put("DesiredState", pipe.getDesiredState().name());
        node.put("CurrentState", pipe.getCurrentState().name());
        if (pipe.getCreationTime() != null) node.put("CreationTime", pipe.getCreationTime().getEpochSecond());
        if (pipe.getLastModifiedTime() != null) node.put("LastModifiedTime", pipe.getLastModifiedTime().getEpochSecond());
        return node;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        return value.asText();
    }

    private DesiredState parseDesiredState(String state) {
        if (state == null || state.isBlank()) return null;
        try {
            return DesiredState.valueOf(state.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private PipeState parsePipeState(String state) {
        if (state == null || state.isBlank()) return null;
        try {
            return PipeState.valueOf(state.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }
}
