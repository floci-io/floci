package io.github.hectorvent.floci.services.mediapackagev2;

import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.mediapackagev2.model.MediaPackageV2ChannelGroup;
import com.fasterxml.jackson.databind.JsonNode;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * AWS Elemental MediaPackage V2 REST-JSON controller. Wire field names are
 * PascalCase - except the tag map, which the service's own model names
 * {@code Tags} in create responses but {@code tags} in get responses (verified
 * against the SDK's JSON model; the two really do differ). Tag operations ride
 * the shared {@code /tags/{arn}} path.
 */
@Path("/channelGroup")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MediaPackageV2Controller {

    private final MediaPackageV2Service mediaPackageV2Service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public MediaPackageV2Controller(MediaPackageV2Service mediaPackageV2Service, RegionResolver regionResolver,
                                    ObjectMapper objectMapper) {
        this.mediaPackageV2Service = mediaPackageV2Service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    public Response createChannelGroup(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        JsonNode tagsNode = request.hasNonNull("Tags") ? request.get("Tags") : request.get("tags");
        MediaPackageV2ChannelGroup group = mediaPackageV2Service.createChannelGroup(
                textOrNull(request, "ChannelGroupName"),
                textOrNull(request, "Description"),
                parseTags(tagsNode),
                region);
        return Response.ok(channelGroupNode(group, "Tags")).build();
    }

    @GET
    @Path("/{name}")
    public Response getChannelGroup(@PathParam("name") String name) {
        MediaPackageV2ChannelGroup group = mediaPackageV2Service.getChannelGroup(name);
        return Response.ok(channelGroupNode(group, "tags")).build();
    }

    @DELETE
    @Path("/{name}")
    public Response deleteChannelGroup(@PathParam("name") String name) {
        mediaPackageV2Service.deleteChannelGroup(name);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode channelGroupNode(MediaPackageV2ChannelGroup group, String tagsKey) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ChannelGroupName", group.getChannelGroupName());
        node.put("Arn", group.getArn());
        node.put("EgressDomain", group.getEgressDomain());
        node.put("CreatedAt", group.getCreatedAt());
        node.put("ModifiedAt", group.getModifiedAt());
        node.put("ETag", group.getETag());
        node.put("Description", group.getDescription());
        node.set(tagsKey, objectMapper.valueToTree(group.getTags()));
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
