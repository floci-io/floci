package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudtrail.model.DataResource;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.Trail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class CloudTrailJsonHandler {

    private final CloudTrailService service;
    private final ObjectMapper mapper;

    @Inject
    public CloudTrailJsonHandler(CloudTrailService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "CreateTrail" -> createTrail(request, region);
            case "DescribeTrails" -> describeTrails(request, region);
            case "DeleteTrail" -> deleteTrail(request, region);
            case "UpdateTrail" -> updateTrail(request, region);
            case "PutEventSelectors" -> putEventSelectors(request, region);
            case "GetEventSelectors" -> getEventSelectors(request, region);
            case "StartLogging" -> startLogging(request, region);
            case "StopLogging" -> stopLogging(request, region);
            case "GetTrailStatus" -> getTrailStatus(request, region);
            case "LookupEvents" -> lookupEvents(request, region);
            default -> throw new AwsException(
                    "InvalidAction", "Could not find operation " + action, 400);
        };
    }

    private Response createTrail(JsonNode req, String region) {
        String name = req.path("Name").asText(null);
        String s3BucketName = req.path("S3BucketName").asText(null);
        String s3KeyPrefix = req.has("S3KeyPrefix") ? req.path("S3KeyPrefix").asText(null) : null;
        String snsTopicArn = req.has("SnsTopicName") ? req.path("SnsTopicName").asText(null) : null;
        boolean includeGlobal = req.path("IncludeGlobalServiceEvents").asBoolean(true);
        boolean isMultiRegion = req.path("IsMultiRegionTrail").asBoolean(false);
        boolean enableLogFileValidation = req.path("EnableLogFileValidation").asBoolean(false);
        boolean isOrganizationTrail = req.path("IsOrganizationTrail").asBoolean(false);

        Trail trail = service.createTrail(region, name, s3BucketName, s3KeyPrefix, snsTopicArn,
                includeGlobal, isMultiRegion, enableLogFileValidation, isOrganizationTrail);

        ObjectNode resp = mapper.createObjectNode();
        resp.put("Name", trail.name());
        resp.put("S3BucketName", trail.s3BucketName());
        if (trail.s3KeyPrefix() != null) resp.put("S3KeyPrefix", trail.s3KeyPrefix());
        if (trail.snsTopicArn() != null) resp.put("SnsTopicName", trail.snsTopicArn());
        resp.put("IncludeGlobalServiceEvents", trail.includeGlobalServiceEvents());
        resp.put("IsMultiRegionTrail", trail.isMultiRegionTrail());
        resp.put("TrailARN", trail.trailArn());
        resp.put("LogFileValidationEnabled", trail.logFileValidationEnabled());
        resp.put("IsOrganizationTrail", trail.isOrganizationTrail());
        return Response.ok(resp).build();
    }

    private Response deleteTrail(JsonNode req, String region) {
        String name = req.path("Name").asText(null);
        service.deleteTrail(region, name);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response updateTrail(JsonNode req, String region) {
        String name = req.path("Name").asText(null);
        String s3BucketName = req.has("S3BucketName") ? req.path("S3BucketName").asText(null) : null;
        String s3KeyPrefix = req.has("S3KeyPrefix") ? req.path("S3KeyPrefix").asText(null) : null;
        String snsTopicName = req.has("SnsTopicName") ? req.path("SnsTopicName").asText(null) : null;
        Boolean includeGlobal = req.has("IncludeGlobalServiceEvents")
                ? req.path("IncludeGlobalServiceEvents").asBoolean() : null;
        Boolean isMultiRegion = req.has("IsMultiRegionTrail")
                ? req.path("IsMultiRegionTrail").asBoolean() : null;
        Boolean enableLogFileValidation = req.has("EnableLogFileValidation")
                ? req.path("EnableLogFileValidation").asBoolean() : null;
        Boolean isOrganizationTrail = req.has("IsOrganizationTrail")
                ? req.path("IsOrganizationTrail").asBoolean() : null;

        Trail trail = service.updateTrail(region, name, s3BucketName, s3KeyPrefix, snsTopicName,
                includeGlobal, isMultiRegion, enableLogFileValidation, isOrganizationTrail);

        ObjectNode resp = mapper.createObjectNode();
        resp.put("Name", trail.name());
        resp.put("S3BucketName", trail.s3BucketName());
        if (trail.s3KeyPrefix() != null) resp.put("S3KeyPrefix", trail.s3KeyPrefix());
        if (trail.snsTopicArn() != null) resp.put("SnsTopicName", trail.snsTopicArn());
        resp.put("IncludeGlobalServiceEvents", trail.includeGlobalServiceEvents());
        resp.put("IsMultiRegionTrail", trail.isMultiRegionTrail());
        resp.put("TrailARN", trail.trailArn());
        resp.put("LogFileValidationEnabled", trail.logFileValidationEnabled());
        resp.put("IsOrganizationTrail", trail.isOrganizationTrail());
        return Response.ok(resp).build();
    }

    private Response describeTrails(JsonNode req, String region) {
        List<String> nameList = extractStringList(req, "trailNameList");
        List<Trail> trails = service.describeTrails(region, nameList);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("trailList", mapper.valueToTree(trails));
        return Response.ok(resp).build();
    }

    private Response putEventSelectors(JsonNode req, String region) {
        String trailName = req.path("TrailName").asText(null);
        List<EventSelector> selectors = parseEventSelectors(req.path("EventSelectors"));
        List<EventSelector> stored = service.putEventSelectors(region, trailName, selectors);

        ObjectNode resp = mapper.createObjectNode();
        Trail trail = firstTrail(service.describeTrails(region, List.of(trailName)));
        if (trail != null) {
            resp.put("TrailARN", trail.trailArn());
        }
        resp.set("EventSelectors", mapper.valueToTree(stored));
        return Response.ok(resp).build();
    }

    private Response getEventSelectors(JsonNode req, String region) {
        String trailName = req.path("TrailName").asText(null);
        List<EventSelector> selectors = service.getEventSelectors(region, trailName);

        ObjectNode resp = mapper.createObjectNode();
        Trail trail = firstTrail(service.describeTrails(region, List.of(trailName)));
        if (trail != null) {
            resp.put("TrailARN", trail.trailArn());
        }
        resp.set("EventSelectors", mapper.valueToTree(selectors));
        return Response.ok(resp).build();
    }

    private Response startLogging(JsonNode req, String region) {
        String trailName = req.path("Name").asText(null);
        service.startLogging(region, trailName);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response stopLogging(JsonNode req, String region) {
        String trailName = req.path("Name").asText(null);
        service.stopLogging(region, trailName);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response getTrailStatus(JsonNode req, String region) {
        String trailName = req.path("Name").asText(null);
        CloudTrailService.TrailStatus status = service.getTrailStatus(region, trailName);

        ObjectNode resp = mapper.createObjectNode();
        resp.put("IsLogging", status.logging());
        if (status.startLoggingTime() != null) {
            resp.put("StartLoggingTime", status.startLoggingTime() / 1000.0);
            resp.put("LatestDeliveryTime", status.startLoggingTime() / 1000.0);
        }
        if (status.stopLoggingTime() != null) {
            resp.put("StopLoggingTime", status.stopLoggingTime() / 1000.0);
        }
        return Response.ok(resp).build();
    }

    private Response lookupEvents(JsonNode req, String region) {
        // Stub: return an empty event page (full event store not implemented).
        ObjectNode resp = mapper.createObjectNode();
        resp.putArray("Events");
        return Response.ok(resp).build();
    }

    // --- Helpers ---

    private List<EventSelector> parseEventSelectors(JsonNode selectorsNode) {
        List<EventSelector> result = new ArrayList<>();
        if (selectorsNode == null || !selectorsNode.isArray()) return result;
        for (JsonNode sel : selectorsNode) {
            String readWriteType = sel.has("ReadWriteType") ? sel.path("ReadWriteType").asText() : "All";
            Boolean includeManagement = sel.has("IncludeManagementEvents")
                    ? sel.path("IncludeManagementEvents").asBoolean() : null;
            List<String> excludeManagement = extractStringList(sel, "ExcludeManagementEventSources");
            List<DataResource> dataResources = new ArrayList<>();
            if (sel.has("DataResources")) {
                for (JsonNode dr : sel.path("DataResources")) {
                    String type = dr.path("Type").asText(null);
                    List<String> values = extractStringList(dr, "Values");
                    dataResources.add(new DataResource(type, values));
                }
            }
            result.add(new EventSelector(readWriteType, includeManagement, dataResources,
                    excludeManagement.isEmpty() ? null : excludeManagement));
        }
        return result;
    }

    private static Trail firstTrail(List<Trail> trails) {
        return trails.isEmpty() ? null : trails.get(0);
    }

    private List<String> extractStringList(JsonNode req, String fieldName) {
        List<String> result = new ArrayList<>();
        if (req != null && req.has(fieldName)) {
            req.path(fieldName).forEach(n -> result.add(n.asText()));
        }
        return result;
    }
}
