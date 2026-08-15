package io.github.hectorvent.floci.services.ivschat;

import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ivschat.model.IvschatLoggingConfiguration;
import io.github.hectorvent.floci.services.ivschat.model.IvschatRoom;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Amazon IVS Chat REST-JSON controller. Like IVS, every operation is a POST to
 * /{OperationName} with a JSON body; timestamps are ISO-8601 strings.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IvschatController {

    private final IvschatService ivschatService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public IvschatController(IvschatService ivschatService, RegionResolver regionResolver,
                             ObjectMapper objectMapper) {
        this.ivschatService = ivschatService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── Rooms ────────────────────────────

    @POST
    @Path("/CreateRoom")
    public Response createRoom(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        IvschatRoom room = ivschatService.createRoom(
                textOrNull(request, "name"),
                intOrNull(request, "maximumMessageRatePerSecond"),
                intOrNull(request, "maximumMessageLength"),
                request.get("messageReviewHandler"),
                stringList(request.get("loggingConfigurationIdentifiers")),
                parseTags(request.get("tags")),
                region);
        return Response.ok(roomNode(room)).build();
    }

    @POST
    @Path("/GetRoom")
    public Response getRoom(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        IvschatRoom room = ivschatService.getRoom(textOrNull(readTree(body), "identifier"), region);
        return Response.ok(roomNode(room)).build();
    }

    @POST
    @Path("/DeleteRoom")
    public Response deleteRoom(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        ivschatService.deleteRoom(textOrNull(readTree(body), "identifier"), region);
        return Response.status(204).build();
    }

    // ─────────────────── Logging configurations ───────────────────

    @POST
    @Path("/CreateLoggingConfiguration")
    public Response createLoggingConfiguration(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        IvschatLoggingConfiguration configuration = ivschatService.createLoggingConfiguration(
                textOrNull(request, "name"),
                request.get("destinationConfiguration"),
                parseTags(request.get("tags")),
                region);
        return Response.ok(loggingConfigurationNode(configuration)).build();
    }

    @POST
    @Path("/GetLoggingConfiguration")
    public Response getLoggingConfiguration(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        IvschatLoggingConfiguration configuration =
                ivschatService.getLoggingConfiguration(textOrNull(readTree(body), "identifier"), region);
        return Response.ok(loggingConfigurationNode(configuration)).build();
    }

    @POST
    @Path("/DeleteLoggingConfiguration")
    public Response deleteLoggingConfiguration(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        ivschatService.deleteLoggingConfiguration(textOrNull(readTree(body), "identifier"), region);
        return Response.status(204).build();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode roomNode(IvschatRoom room) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", room.getArn());
        node.put("id", room.getId());
        node.put("name", room.getName());
        node.put("createTime", iso8601(room));
        node.put("updateTime", DateTimeFormatter.ISO_INSTANT.format(room.getUpdateTime()));
        node.put("maximumMessageRatePerSecond", room.getMaximumMessageRatePerSecond());
        node.put("maximumMessageLength", room.getMaximumMessageLength());
        if (room.getMessageReviewHandler() != null && !room.getMessageReviewHandler().isNull()) {
            node.set("messageReviewHandler", room.getMessageReviewHandler());
        }
        node.set("tags", objectMapper.valueToTree(room.getTags()));
        var identifiers = node.putArray("loggingConfigurationIdentifiers");
        if (room.getLoggingConfigurationIdentifiers() != null) {
            room.getLoggingConfigurationIdentifiers().forEach(identifiers::add);
        }
        return node;
    }

    private ObjectNode loggingConfigurationNode(IvschatLoggingConfiguration configuration) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", configuration.getArn());
        node.put("id", configuration.getId());
        node.put("createTime", DateTimeFormatter.ISO_INSTANT.format(configuration.getCreateTime()));
        node.put("updateTime", DateTimeFormatter.ISO_INSTANT.format(configuration.getUpdateTime()));
        node.put("name", configuration.getName());
        node.set("destinationConfiguration", configuration.getDestinationConfiguration());
        node.put("state", "ACTIVE");
        node.set("tags", objectMapper.valueToTree(configuration.getTags()));
        return node;
    }

    private static String iso8601(IvschatRoom room) {
        return DateTimeFormatter.ISO_INSTANT.format(room.getCreateTime());
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

    private Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
    }

    private List<String> stringList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        arrayNode.forEach(v -> values.add(v.asText()));
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
