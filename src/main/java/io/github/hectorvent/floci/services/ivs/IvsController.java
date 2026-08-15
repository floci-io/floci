package io.github.hectorvent.floci.services.ivs;

import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ivs.model.IvsChannel;
import io.github.hectorvent.floci.services.ivs.model.IvsPlaybackKeyPair;
import io.github.hectorvent.floci.services.ivs.model.IvsRecordingConfiguration;
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

import java.util.HashMap;
import java.util.Map;

/**
 * Amazon IVS REST-JSON controller. IVS is rest-json with RPC-style paths: every
 * operation is a POST to /{OperationName} with a JSON body.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IvsController {

    private final IvsService ivsService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public IvsController(IvsService ivsService, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.ivsService = ivsService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── Channels ────────────────────────────

    @POST
    @Path("/CreateChannel")
    public Response createChannel(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        IvsChannel channel = ivsService.createChannel(
                textOrNull(request, "name"),
                textOrNull(request, "latencyMode"),
                textOrNull(request, "type"),
                textOrNull(request, "preset"),
                request.path("authorized").asBoolean(false),
                request.path("insecureIngest").asBoolean(false),
                textOrNull(request, "recordingConfigurationArn"),
                parseTags(request.get("tags")),
                region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("channel", channelNode(channel));
        ObjectNode streamKey = response.putObject("streamKey");
        streamKey.put("arn", channel.getStreamKeyArn());
        streamKey.put("value", channel.getStreamKeyValue());
        streamKey.put("channelArn", channel.getArn());
        streamKey.set("tags", objectMapper.createObjectNode());
        return Response.ok(response).build();
    }

    @POST
    @Path("/GetChannel")
    public Response getChannel(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        IvsChannel channel = ivsService.getChannel(textOrNull(readTree(body), "arn"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("channel", channelNode(channel));
        return Response.ok(response).build();
    }

    @POST
    @Path("/DeleteChannel")
    public Response deleteChannel(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        ivsService.deleteChannel(textOrNull(readTree(body), "arn"), region);
        return Response.status(204).build();
    }

    // ─────────────────────── Playback key pairs ───────────────────────

    @POST
    @Path("/ImportPlaybackKeyPair")
    public Response importPlaybackKeyPair(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        IvsPlaybackKeyPair keyPair = ivsService.importPlaybackKeyPair(
                textOrNull(request, "publicKeyMaterial"),
                textOrNull(request, "name"),
                parseTags(request.get("tags")),
                region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("keyPair", keyPairNode(keyPair));
        return Response.ok(response).build();
    }

    @POST
    @Path("/GetPlaybackKeyPair")
    public Response getPlaybackKeyPair(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        IvsPlaybackKeyPair keyPair = ivsService.getPlaybackKeyPair(textOrNull(readTree(body), "arn"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("keyPair", keyPairNode(keyPair));
        return Response.ok(response).build();
    }

    @POST
    @Path("/DeletePlaybackKeyPair")
    public Response deletePlaybackKeyPair(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        ivsService.deletePlaybackKeyPair(textOrNull(readTree(body), "arn"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ─────────────────── Recording configurations ───────────────────

    @POST
    @Path("/CreateRecordingConfiguration")
    public Response createRecordingConfiguration(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        IvsRecordingConfiguration configuration = ivsService.createRecordingConfiguration(
                textOrNull(request, "name"),
                request.get("destinationConfiguration"),
                request.get("thumbnailConfiguration"),
                request.get("renditionConfiguration"),
                request.hasNonNull("recordingReconnectWindowSeconds")
                        ? request.get("recordingReconnectWindowSeconds").asInt()
                        : null,
                parseTags(request.get("tags")),
                region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("recordingConfiguration", recordingConfigurationNode(configuration));
        return Response.ok(response).build();
    }

    @POST
    @Path("/GetRecordingConfiguration")
    public Response getRecordingConfiguration(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        IvsRecordingConfiguration configuration =
                ivsService.getRecordingConfiguration(textOrNull(readTree(body), "arn"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("recordingConfiguration", recordingConfigurationNode(configuration));
        return Response.ok(response).build();
    }

    @POST
    @Path("/DeleteRecordingConfiguration")
    public Response deleteRecordingConfiguration(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        ivsService.deleteRecordingConfiguration(textOrNull(readTree(body), "arn"), region);
        return Response.status(204).build();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode channelNode(IvsChannel channel) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", channel.getArn());
        node.put("name", channel.getName());
        node.put("latencyMode", channel.getLatencyMode());
        node.put("type", channel.getType());
        node.put("recordingConfigurationArn", channel.getRecordingConfigurationArn());
        node.put("ingestEndpoint", channel.getIngestEndpoint());
        node.put("playbackUrl", channel.getPlaybackUrl());
        node.put("authorized", channel.isAuthorized());
        node.set("tags", objectMapper.valueToTree(channel.getTags()));
        node.put("insecureIngest", channel.isInsecureIngest());
        node.put("preset", channel.getPreset());
        return node;
    }

    private ObjectNode keyPairNode(IvsPlaybackKeyPair keyPair) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", keyPair.getArn());
        node.put("name", keyPair.getName());
        node.put("fingerprint", keyPair.getFingerprint());
        node.set("tags", objectMapper.valueToTree(keyPair.getTags()));
        return node;
    }

    private ObjectNode recordingConfigurationNode(IvsRecordingConfiguration configuration) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", configuration.getArn());
        node.put("name", configuration.getName());
        node.set("destinationConfiguration", configuration.getDestinationConfiguration());
        node.put("state", "ACTIVE");
        node.set("tags", objectMapper.valueToTree(configuration.getTags()));
        if (configuration.getThumbnailConfiguration() != null) {
            node.set("thumbnailConfiguration", configuration.getThumbnailConfiguration());
        }
        if (configuration.getRenditionConfiguration() != null) {
            node.set("renditionConfiguration", configuration.getRenditionConfiguration());
        }
        if (configuration.getRecordingReconnectWindowSeconds() != null) {
            node.put("recordingReconnectWindowSeconds", configuration.getRecordingReconnectWindowSeconds());
        }
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
