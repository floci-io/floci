package io.github.hectorvent.floci.services.medialive;

import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.medialive.model.MediaLiveMultiplex;
import io.github.hectorvent.floci.services.medialive.model.MediaLiveMultiplexProgram;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS Elemental MediaLive REST-JSON controller. Every MediaLive path carries
 * the service's {@code /prod} stage prefix; wire field names are camelCase.
 * Tag operations use the service's own {@code /prod/tags/{arn}} path, not the
 * shared {@code /tags} dispatcher.
 */
@Path("/prod")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MediaLiveController {

    private final MediaLiveService mediaLiveService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public MediaLiveController(MediaLiveService mediaLiveService, RegionResolver regionResolver,
                               ObjectMapper objectMapper) {
        this.mediaLiveService = mediaLiveService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── Multiplexes ────────────────────────────

    @POST
    @Path("/multiplexes")
    public Response createMultiplex(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        MediaLiveMultiplex multiplex = mediaLiveService.createMultiplex(
                textOrNull(request, "name"),
                stringList(request.get("availabilityZones")),
                request.get("multiplexSettings"),
                parseTags(request.get("tags")),
                region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("multiplex", multiplexNode(multiplex));
        return Response.status(201).entity(response).build();
    }

    @GET
    @Path("/multiplexes/{multiplexId}")
    public Response describeMultiplex(@PathParam("multiplexId") String multiplexId) {
        return Response.ok(multiplexNode(mediaLiveService.getMultiplex(multiplexId))).build();
    }

    @DELETE
    @Path("/multiplexes/{multiplexId}")
    public Response deleteMultiplex(@PathParam("multiplexId") String multiplexId) {
        MediaLiveMultiplex multiplex = mediaLiveService.deleteMultiplex(multiplexId);
        return Response.status(202).entity(multiplexNode(multiplex)).build();
    }

    // ──────────────────────────── Programs ────────────────────────────

    @POST
    @Path("/multiplexes/{multiplexId}/programs")
    public Response createMultiplexProgram(@PathParam("multiplexId") String multiplexId, String body) {
        JsonNode request = readTree(body);
        MediaLiveMultiplexProgram program = mediaLiveService.createProgram(
                multiplexId,
                textOrNull(request, "programName"),
                request.get("multiplexProgramSettings"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("multiplexProgram", programNode(program));
        return Response.status(201).entity(response).build();
    }

    @GET
    @Path("/multiplexes/{multiplexId}/programs/{programName}")
    public Response describeMultiplexProgram(@PathParam("multiplexId") String multiplexId,
                                             @PathParam("programName") String programName) {
        return Response.ok(programNode(mediaLiveService.getProgram(multiplexId, programName))).build();
    }

    @DELETE
    @Path("/multiplexes/{multiplexId}/programs/{programName}")
    public Response deleteMultiplexProgram(@PathParam("multiplexId") String multiplexId,
                                           @PathParam("programName") String programName) {
        MediaLiveMultiplexProgram program = mediaLiveService.deleteProgram(multiplexId, programName);
        return Response.ok(programNode(program)).build();
    }

    // ──────────────────────────── Tags ────────────────────────────

    @GET
    @Path("/tags/{arn: .+}")
    public Response listTagsForResource(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode tags = response.putObject("tags");
        mediaLiveService.listTags(region, arn).forEach(tags::put);
        return Response.ok(response).build();
    }

    @POST
    @Path("/tags/{arn: .+}")
    public Response createTags(@Context HttpHeaders headers, @PathParam("arn") String arn, String body) {
        String region = regionResolver.resolveRegion(headers);
        mediaLiveService.tagResource(region, arn, parseTags(readTree(body).get("tags")));
        return Response.noContent().build();
    }

    @DELETE
    @Path("/tags/{arn: .+}")
    public Response deleteTags(@Context HttpHeaders headers, @PathParam("arn") String arn,
                               @QueryParam("tagKeys") List<String> tagKeys) {
        String region = regionResolver.resolveRegion(headers);
        mediaLiveService.untagResource(region, arn, tagKeys != null ? tagKeys : List.of());
        return Response.noContent().build();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode multiplexNode(MediaLiveMultiplex multiplex) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", multiplex.getArn());
        ArrayNode zones = node.putArray("availabilityZones");
        if (multiplex.getAvailabilityZones() != null) {
            multiplex.getAvailabilityZones().forEach(zones::add);
        }
        node.putArray("destinations");
        node.put("id", multiplex.getId());
        if (multiplex.getMultiplexSettings() != null && !multiplex.getMultiplexSettings().isNull()) {
            node.set("multiplexSettings", multiplex.getMultiplexSettings());
        }
        node.put("name", multiplex.getName());
        node.put("pipelinesRunning", 0);
        node.put("programCount", mediaLiveService.programCount(multiplex.getId()));
        node.put("state", multiplex.getState());
        node.set("tags", objectMapper.valueToTree(multiplex.getTags()));
        return node;
    }

    private ObjectNode programNode(MediaLiveMultiplexProgram program) {
        ObjectNode node = objectMapper.createObjectNode();
        if (program.getChannelId() != null) {
            node.put("channelId", program.getChannelId());
        }
        if (program.getMultiplexProgramSettings() != null
                && !program.getMultiplexProgramSettings().isNull()) {
            node.set("multiplexProgramSettings", program.getMultiplexProgramSettings());
        }
        node.putObject("packetIdentifiersMap");
        node.putArray("pipelineDetails");
        node.put("programName", program.getProgramName());
        return node;
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new jakarta.ws.rs.WebApplicationException(
                    JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private List<String> stringList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            arrayNode.forEach(v -> values.add(v.asText()));
        }
        return values;
    }

    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }
}
