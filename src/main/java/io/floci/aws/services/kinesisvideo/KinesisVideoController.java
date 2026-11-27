package io.floci.aws.services.kinesisvideo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.EpochSeconds;
import io.floci.aws.core.common.JsonErrorResponseUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.kinesisvideo.model.KinesisVideoStream;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Kinesis Video Streams control plane (rest-json, API version 2017-09-30): the stream lifecycle. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class KinesisVideoController {
    private final KinesisVideoService kinesisVideoService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public KinesisVideoController(KinesisVideoService kinesisVideoService, RegionResolver regionResolver,
                                  ObjectMapper objectMapper) {
        this.kinesisVideoService = kinesisVideoService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/createStream")
    public Response createStream(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        KinesisVideoService.CreateStreamRequest createRequest = new KinesisVideoService.CreateStreamRequest(
                text(request, "StreamName"),
                text(request, "DeviceName"),
                text(request, "MediaType"),
                text(request, "KmsKeyId"),
                integer(request, "DataRetentionInHours"),
                tags(request));
        KinesisVideoStream stream = kinesisVideoService.createStream(
                region(headers), regionResolver.getAccountId(), createRequest);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("StreamARN", stream.streamArn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/deleteStream")
    public Response deleteStream(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        kinesisVideoService.deleteStream(region(headers), regionResolver.getAccountId(),
                text(request, "StreamARN"), text(request, "CurrentVersion"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/describeStream")
    public Response describeStream(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        KinesisVideoStream stream = kinesisVideoService.describeStream(region(headers),
                regionResolver.getAccountId(), text(request, "StreamName"), text(request, "StreamARN"));
        ObjectNode response = objectMapper.createObjectNode();
        writeStreamInfo(response.putObject("StreamInfo"), stream);
        return Response.ok(response).build();
    }

    @POST
    @Path("/listStreams")
    public Response listStreams(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        String namePrefix = null;
        JsonNode condition = request.path("StreamNameCondition");
        if (condition.isObject()) {
            String operator = text(condition, "ComparisonOperator");
            if (operator != null && !"BEGINS_WITH".equals(operator)) {
                throw invalidArgument("ComparisonOperator must be BEGINS_WITH.");
            }
            namePrefix = text(condition, "ComparisonValue");
        }
        KinesisVideoService.Page page = kinesisVideoService.listStreams(region(headers),
                regionResolver.getAccountId(), integer(request, "MaxResults"), text(request, "NextToken"),
                namePrefix);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode streamInfoList = response.putArray("StreamInfoList");
        for (KinesisVideoStream stream : page.streams()) {
            writeStreamInfo(streamInfoList.addObject(), stream);
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private static void writeStreamInfo(ObjectNode info, KinesisVideoStream stream) {
        if (stream.deviceName() != null) {
            info.put("DeviceName", stream.deviceName());
        }
        info.put("StreamName", stream.streamName());
        info.put("StreamARN", stream.streamArn());
        if (stream.mediaType() != null) {
            info.put("MediaType", stream.mediaType());
        }
        info.put("KmsKeyId", stream.kmsKeyId());
        info.put("Version", stream.version());
        info.put("Status", stream.status());
        info.put("CreationTime", EpochSeconds.fromMillis(stream.creationTimeMillis()));
        info.put("DataRetentionInHours", stream.dataRetentionInHours());
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalidArgument(field + " must be a string.");
        }
        return value.asText();
    }

    private static Integer integer(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isInt()) {
            throw invalidArgument(field + " must be an integer.");
        }
        return value.intValue();
    }

    private static Map<String, String> tags(JsonNode request) {
        JsonNode value = request.get("Tags");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw invalidArgument("Tags must be a map of strings.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isTextual()) {
                throw invalidArgument("Tags must be a map of strings.");
            }
            tags.put(field.getKey(), field.getValue().asText());
        }
        return tags;
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private JsonNode readTree(String body) {
        try {
            JsonNode tree = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            if (!tree.isObject()) {
                throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
            }
            return tree;
        } catch (JsonProcessingException e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }

    private static AwsException invalidArgument(String message) {
        return new AwsException("InvalidArgumentException", message, 400);
    }
}
