package io.floci.aws.services.cloudtrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.EpochSeconds;
import io.floci.aws.core.common.JsonNumberCoercion;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.services.cloudtrail.model.AdvancedEventSelector;
import io.floci.aws.services.cloudtrail.model.DataResource;
import io.floci.aws.services.cloudtrail.model.EventSelector;
import io.floci.aws.services.cloudtrail.model.Trail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class CloudTrailJsonHandler {

    /** {@code LookupEvents} returns at most 50 events a page, whatever {@code MaxResults} asks for. */
    private static final int MAX_LOOKUP_EVENTS = 50;
    private static final Set<String> LOOKUP_ATTRIBUTE_KEYS = Set.of("EventId", "EventName", "ReadOnly",
            "Username", "ResourceType", "ResourceName", "EventSource", "AccessKeyId");

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
            case "ListTrails" -> listTrails(request, region);
            case "AddTags" -> addTags(request);
            case "RemoveTags" -> removeTags(request);
            case "ListTags" -> listTags(request);
            default -> throw new AwsException(
                    "InvalidAction", "Could not find operation " + action, 400);
        };
    }

    private Response createTrail(JsonNode req, String region) {
        String name = req.path("Name").asText(null);
        String s3BucketName = req.path("S3BucketName").asText(null);
        String s3KeyPrefix = req.has("S3KeyPrefix") ? req.path("S3KeyPrefix").asText(null) : null;
        String snsTopicArn = req.has("SnsTopicARN") ? req.path("SnsTopicARN").asText(null)
                : req.has("SnsTopicName") ? req.path("SnsTopicName").asText(null) : null;
        boolean includeGlobal = req.path("IncludeGlobalServiceEvents").asBoolean(true);
        boolean isMultiRegion = req.path("IsMultiRegionTrail").asBoolean(false);
        boolean enableLogFileValidation = req.path("EnableLogFileValidation").asBoolean(false);
        boolean isOrganizationTrail = req.path("IsOrganizationTrail").asBoolean(false);

        Map<String, String> tags = parseTagsList(req.path("TagsList"));

        Trail trail = service.createTrail(region, name, s3BucketName, s3KeyPrefix, snsTopicArn,
                includeGlobal, isMultiRegion, enableLogFileValidation, isOrganizationTrail, tags,
                textOrNull(req, "KmsKeyId"), textOrNull(req, "CloudWatchLogsLogGroupArn"),
                textOrNull(req, "CloudWatchLogsRoleArn"));

        ObjectNode resp = mapper.createObjectNode();
        resp.put("Name", trail.name());
        resp.put("S3BucketName", trail.s3BucketName());
        if (trail.s3KeyPrefix() != null) resp.put("S3KeyPrefix", trail.s3KeyPrefix());
        if (trail.snsTopicArn() != null) {
            resp.put("SnsTopicARN", trail.snsTopicArn());
            resp.put("SnsTopicName", trail.snsTopicArn());
        }
        resp.put("IncludeGlobalServiceEvents", trail.includeGlobalServiceEvents());
        resp.put("IsMultiRegionTrail", trail.isMultiRegionTrail());
        resp.put("TrailARN", trail.trailArn());
        resp.put("LogFileValidationEnabled", trail.logFileValidationEnabled());
        resp.put("IsOrganizationTrail", trail.isOrganizationTrail());
        putIfSet(resp, "KmsKeyId", trail.kmsKeyId());
        putIfSet(resp, "CloudWatchLogsLogGroupArn", trail.cloudWatchLogsLogGroupArn());
        putIfSet(resp, "CloudWatchLogsRoleArn", trail.cloudWatchLogsRoleArn());
        return Response.ok(resp).build();
    }

    private Response deleteTrail(JsonNode req, String region) {
        String name = req.path("Name").asText(null);
        service.deleteTrail(region, name);
        return Response.ok(mapper.createObjectNode()).build();
    }

    /** The member's text when present (an empty string included: UpdateTrail uses it to clear a setting). */
    private static String textOrNull(JsonNode req, String field) {
        return req.has(field) && !req.get(field).isNull() ? req.get(field).asText() : null;
    }

    private static void putIfSet(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private Response updateTrail(JsonNode req, String region) {
        String name = req.path("Name").asText(null);
        String s3BucketName = req.has("S3BucketName") ? req.path("S3BucketName").asText(null) : null;
        String s3KeyPrefix = req.has("S3KeyPrefix") ? req.path("S3KeyPrefix").asText(null) : null;
        String snsTopicName = req.has("SnsTopicARN") ? req.path("SnsTopicARN").asText(null)
                : req.has("SnsTopicName") ? req.path("SnsTopicName").asText(null) : null;
        Boolean includeGlobal = req.has("IncludeGlobalServiceEvents")
                ? req.path("IncludeGlobalServiceEvents").asBoolean() : null;
        Boolean isMultiRegion = req.has("IsMultiRegionTrail")
                ? req.path("IsMultiRegionTrail").asBoolean() : null;
        Boolean enableLogFileValidation = req.has("EnableLogFileValidation")
                ? req.path("EnableLogFileValidation").asBoolean() : null;
        Boolean isOrganizationTrail = req.has("IsOrganizationTrail")
                ? req.path("IsOrganizationTrail").asBoolean() : null;

        Trail trail = service.updateTrail(region, name, s3BucketName, s3KeyPrefix, snsTopicName,
                includeGlobal, isMultiRegion, enableLogFileValidation, isOrganizationTrail,
                textOrNull(req, "KmsKeyId"), textOrNull(req, "CloudWatchLogsLogGroupArn"),
                textOrNull(req, "CloudWatchLogsRoleArn"));

        ObjectNode resp = mapper.createObjectNode();
        resp.put("Name", trail.name());
        resp.put("S3BucketName", trail.s3BucketName());
        if (trail.s3KeyPrefix() != null) resp.put("S3KeyPrefix", trail.s3KeyPrefix());
        if (trail.snsTopicArn() != null) {
            resp.put("SnsTopicARN", trail.snsTopicArn());
            resp.put("SnsTopicName", trail.snsTopicArn());
        }
        resp.put("IncludeGlobalServiceEvents", trail.includeGlobalServiceEvents());
        resp.put("IsMultiRegionTrail", trail.isMultiRegionTrail());
        resp.put("TrailARN", trail.trailArn());
        resp.put("LogFileValidationEnabled", trail.logFileValidationEnabled());
        resp.put("IsOrganizationTrail", trail.isOrganizationTrail());
        putIfSet(resp, "KmsKeyId", trail.kmsKeyId());
        putIfSet(resp, "CloudWatchLogsLogGroupArn", trail.cloudWatchLogsLogGroupArn());
        putIfSet(resp, "CloudWatchLogsRoleArn", trail.cloudWatchLogsRoleArn());
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
        boolean hasBasic = req.has("EventSelectors") && req.path("EventSelectors").isArray()
                && !req.path("EventSelectors").isEmpty();
        boolean hasAdvanced = req.has("AdvancedEventSelectors") && req.path("AdvancedEventSelectors").isArray()
                && !req.path("AdvancedEventSelectors").isEmpty();
        if (hasBasic && hasAdvanced) {
            throw new AwsException("InvalidEventSelectorsException",
                    "EventSelectors and AdvancedEventSelectors are mutually exclusive on a single trail.", 400);
        }

        ObjectNode resp = mapper.createObjectNode();
        Trail trail = trailName != null ? firstTrail(service.describeTrails(region, List.of(trailName))) : null;
        if (trail != null) {
            resp.put("TrailARN", trail.trailArn());
        }

        if (hasAdvanced) {
            List<AdvancedEventSelector> advanced =
                    CloudTrailSelectorJson.parseAdvancedEventSelectors(req.path("AdvancedEventSelectors"));
            List<AdvancedEventSelector> stored = service.putAdvancedEventSelectors(region, trailName, advanced);
            resp.set("AdvancedEventSelectors", mapper.valueToTree(stored));
        } else {
            List<EventSelector> selectors = parseEventSelectors(req.path("EventSelectors"));
            List<EventSelector> stored = service.putEventSelectors(region, trailName, selectors);
            resp.set("EventSelectors", mapper.valueToTree(stored));
        }
        return Response.ok(resp).build();
    }

    private Response getEventSelectors(JsonNode req, String region) {
        String trailName = req.path("TrailName").asText(null);
        List<AdvancedEventSelector> advancedSelectors = service.getAdvancedEventSelectors(region, trailName);

        ObjectNode resp = mapper.createObjectNode();
        Trail trail = trailName != null ? firstTrail(service.describeTrails(region, List.of(trailName))) : null;
        if (trail != null) {
            resp.put("TrailARN", trail.trailArn());
        }
        if (!advancedSelectors.isEmpty()) {
            resp.set("AdvancedEventSelectors", mapper.valueToTree(advancedSelectors));
        } else {
            List<EventSelector> selectors = service.getEventSelectors(region, trailName);
            resp.set("EventSelectors", mapper.valueToTree(selectors));
        }
        return Response.ok(resp).build();
    }

    private Response listTrails(JsonNode req, String region) {
        List<CloudTrailService.TrailInfo> trails = service.listTrails(region);
        ObjectNode resp = mapper.createObjectNode();
        resp.set("Trails", mapper.valueToTree(trails));
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
            resp.put("StartLoggingTime", EpochSeconds.fromMillis(status.startLoggingTime()));
        }
        if (status.latestDeliveryTime() != null) {
            resp.put("LatestDeliveryTime", EpochSeconds.fromMillis(status.latestDeliveryTime()));
        }
        if (status.latestDeliveryError() != null) {
            resp.put("LatestDeliveryError", status.latestDeliveryError());
        }
        if (status.stopLoggingTime() != null) {
            resp.put("StopLoggingTime", EpochSeconds.fromMillis(status.stopLoggingTime()));
        }
        return Response.ok(resp).build();
    }

    private Response lookupEvents(JsonNode req, String region) {
        Integer maxResults = validateMaxResults(req.path("MaxResults"));
        CloudTrailEventHistory.LookupAttribute attribute = lookupAttribute(req.path("LookupAttributes"));
        Instant startTime = timestamp(req.path("StartTime"));
        Instant endTime = timestamp(req.path("EndTime"));
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new AwsException("InvalidTimeRangeException",
                    "Start time must be earlier than or equal to the end time.", 400);
        }
        PaginatedResult<CloudTrailEventHistory.HistoryEvent> page = Pagination.paginate(
                service.lookupEvents(region, attribute, startTime, endTime),
                event -> String.format("%019d", Long.MAX_VALUE - event.sequence()),
                maxResults == null ? MAX_LOOKUP_EVENTS : Math.min(maxResults, MAX_LOOKUP_EVENTS),
                textOrNull(req, "NextToken"), "lookup-events",
                token -> new AwsException("InvalidNextTokenException", "Invalid NextToken.", 400));
        ObjectNode resp = mapper.createObjectNode();
        ArrayNode events = resp.putArray("Events");
        for (CloudTrailEventHistory.HistoryEvent event : page.items()) {
            ObjectNode record = event.record();
            ObjectNode out = events.addObject();
            out.put("EventId", record.path("eventID").asText());
            out.put("EventName", record.path("eventName").asText());
            out.put("ReadOnly", record.path("readOnly").asText());
            out.put("AccessKeyId", record.path("userIdentity").path("accessKeyId").asText());
            out.put("EventTime", EpochSeconds.fromMillis(event.eventTime().toEpochMilli()));
            out.put("EventSource", record.path("eventSource").asText());
            out.put("Username", record.path("userIdentity").path("userName").asText());
            out.putArray("Resources");
            out.put("CloudTrailEvent", record.toString());
        }
        if (page.nextToken() != null) {
            resp.put("NextToken", page.nextToken());
        }
        return Response.ok(resp).build();
    }

    /** AWS takes at most one lookup attribute, keyed by one of the documented names. */
    private static CloudTrailEventHistory.LookupAttribute lookupAttribute(JsonNode attributes) {
        if (!attributes.isArray() || attributes.isEmpty()) {
            return null;
        }
        if (attributes.size() > 1) {
            throw new AwsException("InvalidLookupAttributesException",
                    "You cannot specify more than one lookup attribute.", 400);
        }
        JsonNode attribute = attributes.get(0);
        String key = attribute.path("AttributeKey").asText(null);
        String value = attribute.path("AttributeValue").asText(null);
        if (key == null || !LOOKUP_ATTRIBUTE_KEYS.contains(key) || value == null) {
            throw new AwsException("InvalidLookupAttributesException",
                    "Specify a valid lookup attribute key and value.", 400);
        }
        return new CloudTrailEventHistory.LookupAttribute(key, value);
    }

    private static Instant timestamp(JsonNode node) {
        if (!node.isNumber()) {
            return null;
        }
        return Instant.ofEpochMilli(Math.round(node.doubleValue() * 1000));
    }

    /** An omitted or null {@code MaxResults} is optional and takes the service default. */
    private static Integer validateMaxResults(JsonNode maxResults) {
        if (maxResults.isMissingNode() || maxResults.isNull()) {
            return null;
        }
        int value;
        if (maxResults.isNumber()) {
            value = JsonNumberCoercion.truncateAndClampToInt(maxResults);
        } else if (maxResults.isTextual()) {
            throw serialization("STRING_VALUE can not be converted to an Integer");
        } else if (maxResults.isArray()) {
            throw serialization("Start of list found where not expected");
        } else if (maxResults.isObject()) {
            throw serialization("Start of structure or map found where not expected.");
        } else {
            throw serialization((maxResults.booleanValue() ? "TRUE_VALUE" : "FALSE_VALUE")
                    + " can not be converted to an Integer");
        }
        if (value < 1) {
            throw invalidMaxResults(value);
        }
        return value;
    }

    private static AwsException invalidMaxResults(int value) {
        return new AwsException("InvalidMaxResultsException",
                "A max results value of " + value + " is invalid. Specify max results between 1 and 1000.", 400);
    }

    private static AwsException serialization(String message) {
        return new AwsException("SerializationException", message, 400);
    }

    private Response addTags(JsonNode req) {
        String resourceId = req.path("ResourceId").asText(null);
        service.addTags(resourceId, parseTagsList(req.path("TagsList")));
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response removeTags(JsonNode req) {
        String resourceId = req.path("ResourceId").asText(null);
        List<String> keys = new ArrayList<>(parseTagsList(req.path("TagsList")).keySet());
        service.removeTags(resourceId, keys);
        return Response.ok(mapper.createObjectNode()).build();
    }

    private Response listTags(JsonNode req) {
        List<String> resourceIdList = extractStringList(req, "ResourceIdList");
        ObjectNode resp = mapper.createObjectNode();
        ArrayNode resourceTagList = resp.putArray("ResourceTagList");
        for (String resourceId : resourceIdList) {
            Map<String, String> tags = service.listTags(resourceId);
            ObjectNode entry = resourceTagList.addObject();
            entry.put("ResourceId", resourceId);
            ArrayNode tagsList = entry.putArray("TagsList");
            tags.forEach((k, v) -> {
                ObjectNode tag = tagsList.addObject().put("Key", k);
                if (v != null) {
                    tag.put("Value", v);
                }
            });
        }
        return Response.ok(resp).build();
    }

    // --- Helpers ---

    private Map<String, String> parseTagsList(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isArray()) {
            tagsNode.forEach(t -> {
                tags.put(t.path("Key").asText(), t.path("Value").asText(null));
            });
        }
        return tags;
    }

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
