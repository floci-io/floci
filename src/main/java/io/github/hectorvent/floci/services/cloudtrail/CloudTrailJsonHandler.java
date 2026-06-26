package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudtrail.model.CloudTrailTrail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CloudTrailJsonHandler {
    private final CloudTrailService service;
    private final ObjectMapper mapper;

    @Inject
    public CloudTrailJsonHandler(CloudTrailService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateTrail" -> createTrail(request, region);
            case "UpdateTrail" -> updateTrail(request, region);
            case "DescribeTrails" -> describeTrails(request, region);
            case "StartLogging" -> startLogging(request, region);
            case "StopLogging" -> stopLogging(request, region);
            case "DeleteTrail" -> deleteTrail(request, region);
            case "GetTrailStatus" -> getTrailStatus(request, region);
            case "PutEventSelectors" -> putEventSelectors(request, region);
            case "LookupEvents" -> lookupEvents();
            default -> throw new AwsException("UnsupportedOperation",
                    "Operation " + action + " is not supported.", 400);
        };
    }

    private Response createTrail(JsonNode request, String region) {
        CloudTrailTrail trail = service.createTrail(
                region,
                request.path("Name").asText(null),
                request.path("S3BucketName").asText(null),
                request.path("IncludeGlobalServiceEvents").asBoolean(false),
                request.path("IsMultiRegionTrail").asBoolean(false),
                request.path("IsOrganizationTrail").asBoolean(false),
                parseTags(request.path("TagsList")));
        return Response.ok(trailNode(trail)).build();
    }

    private Response updateTrail(JsonNode request, String region) {
        CloudTrailTrail trail = service.updateTrail(
                region,
                request.path("Name").asText(null),
                request.path("S3BucketName").asText(null),
                optionalBoolean(request, "IncludeGlobalServiceEvents"),
                optionalBoolean(request, "IsMultiRegionTrail"));
        return Response.ok(trailNode(trail)).build();
    }

    private Response describeTrails(JsonNode request, String region) {
        List<String> names = stringList(request.path("trailNameList"));
        ObjectNode response = mapper.createObjectNode();
        ArrayNode trails = response.putArray("trailList");
        for (CloudTrailTrail trail : service.describeTrails(region, names)) {
            trails.add(trailNode(trail));
        }
        return Response.ok(response).build();
    }

    private Response startLogging(JsonNode request, String region) {
        service.startLogging(region, request.path("Name").asText(null));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response stopLogging(JsonNode request, String region) {
        service.stopLogging(region, request.path("Name").asText(null));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response deleteTrail(JsonNode request, String region) {
        service.deleteTrail(region, request.path("Name").asText(null));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response getTrailStatus(JsonNode request, String region) {
        CloudTrailTrail trail = service.requireTrail(region, request.path("Name").asText(null));
        ObjectNode response = mapper.createObjectNode();
        response.put("IsLogging", trail.isLogging());
        if (trail.getUpdated() != null) {
            response.put("LatestDeliveryTime", trail.getUpdated().toEpochMilli() / 1000.0);
        }
        return Response.ok(response).build();
    }

    private Response putEventSelectors(JsonNode request, String region) {
        service.putEventSelectors(region, request.path("TrailName").asText(null));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response lookupEvents() {
        ObjectNode response = mapper.createObjectNode();
        response.putArray("Events");
        return Response.ok(response).build();
    }

    private ObjectNode trailNode(CloudTrailTrail trail) {
        ObjectNode node = mapper.createObjectNode();
        node.put("Name", trail.getName());
        node.put("TrailARN", trail.getTrailArn());
        node.put("S3BucketName", trail.getS3BucketName());
        node.put("IncludeGlobalServiceEvents", trail.isIncludeGlobalServiceEvents());
        node.put("IsMultiRegionTrail", trail.isMultiRegionTrail());
        node.put("IsOrganizationTrail", trail.isOrganizationTrail());
        node.put("HomeRegion", trail.getHomeRegion());
        if (trail.getCreated() != null) {
            node.put("CreationDate", trail.getCreated().toEpochMilli() / 1000.0);
        }
        return node;
    }

    private static Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = tag.path("Key").asText(null);
                if (key != null) {
                    tags.put(key, tag.path("Value").asText(""));
                }
            }
        }
        return tags;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static Boolean optionalBoolean(JsonNode node, String fieldName) {
        return node != null && node.has(fieldName) ? node.path(fieldName).asBoolean() : null;
    }
}
