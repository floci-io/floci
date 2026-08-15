package io.github.hectorvent.floci.services.mediapackage;

import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.mediapackage.model.MediaPackageChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * AWS Elemental MediaPackage (v1) REST-JSON controller. All wire field names
 * are camelCase; channels live at {@code /channels} and {@code /channels/{id}}.
 * Tag operations ride the shared {@code /tags/{arn}} path.
 */
@Path("/channels")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MediaPackageController {

    private final MediaPackageService mediaPackageService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public MediaPackageController(MediaPackageService mediaPackageService, RegionResolver regionResolver,
                                  ObjectMapper objectMapper) {
        this.mediaPackageService = mediaPackageService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    public Response createChannel(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        MediaPackageChannel channel = mediaPackageService.createChannel(
                textOrNull(request, "id"),
                textOrNull(request, "description"),
                parseTags(request.get("tags")),
                region);
        return Response.ok(channelNode(channel)).build();
    }

    @GET
    @Path("/{id}")
    public Response describeChannel(@PathParam("id") String id) {
        return Response.ok(channelNode(mediaPackageService.getChannel(id))).build();
    }

    @PUT
    @Path("/{id}")
    public Response updateChannel(@PathParam("id") String id, String body) {
        JsonNode request = readTree(body);
        MediaPackageChannel channel =
                mediaPackageService.updateChannel(id, textOrNull(request, "description"));
        return Response.ok(channelNode(channel)).build();
    }

    @DELETE
    @Path("/{id}")
    public Response deleteChannel(@PathParam("id") String id) {
        mediaPackageService.deleteChannel(id);
        return Response.status(202).entity(objectMapper.createObjectNode()).build();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode channelNode(MediaPackageChannel channel) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", channel.getArn());
        node.put("id", channel.getId());
        node.put("description", channel.getDescription());
        node.put("createdAt", channel.getCreatedAt());
        node.set("hlsIngest", channel.getHlsIngest());
        node.set("tags", objectMapper.valueToTree(channel.getTags()));
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

    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }
}
