package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogGroup;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CloudWatchLogsHandler {

    private final CloudWatchLogsService logsService;
    private final ObjectMapper objectMapper;

    @Inject
    public CloudWatchLogsHandler(CloudWatchLogsService logsService, ObjectMapper objectMapper) {
        this.logsService = logsService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateLogGroup" -> handleCreateLogGroup(request, region);
            case "DeleteLogGroup" -> handleDeleteLogGroup(request, region);
            case "DescribeLogGroups" -> handleDescribeLogGroups(request, region);
            case "CreateLogStream" -> handleCreateLogStream(request, region);
            case "DeleteLogStream" -> handleDeleteLogStream(request, region);
            case "DescribeLogStreams" -> handleDescribeLogStreams(request, region);
            case "PutLogEvents" -> handlePutLogEvents(request, region);
            case "GetLogEvents" -> handleGetLogEvents(request, region);
            case "FilterLogEvents" -> handleFilterLogEvents(request, region);
            case "PutRetentionPolicy" -> handlePutRetentionPolicy(request, region);
            case "DeleteRetentionPolicy" -> handleDeleteRetentionPolicy(request, region);
            case "TagLogGroup" -> handleTagLogGroup(request, region);
            case "UntagLogGroup" -> handleUntagLogGroup(request, region);
            case "ListTagsLogGroup" -> handleListTagsLogGroup(request, region);
            case "ListTagsForResource" -> handleListTagsForResource(request, region);
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleCreateLogGroup(JsonNode request, String region) {
        String name = request.path("logGroupName").asText();
        Integer retentionInDays = request.has("retentionInDays")
                ? request.path("retentionInDays").asInt() : null;
        Map<String, String> tags = extractTags(request.path("tags"));
        logsService.createLogGroup(name, retentionInDays, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDeleteLogGroup(JsonNode request, String region) {
        String name = request.path("logGroupName").asText();
        logsService.deleteLogGroup(name, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeLogGroups(JsonNode request, String region) {
        String prefix = request.path("logGroupNamePrefix").asText(null);
        List<LogGroup> groups = logsService.describeLogGroups(prefix, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode groupsArray = objectMapper.createArrayNode();
        for (LogGroup g : groups) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("logGroupName", g.getLogGroupName());
            node.put("createdTime", g.getCreatedTime());
            node.put("arn", logsService.buildArn(g.getLogGroupName(), region));
            if (g.getRetentionInDays() != null) {
                node.put("retentionInDays", g.getRetentionInDays());
            }
            node.put("storedBytes", 0);
            node.put("metricFilterCount", 0);
            groupsArray.add(node);
        }
        response.set("logGroups", groupsArray);
        return Response.ok(response).build();
    }

    private Response handleCreateLogStream(JsonNode request, String region) {
        String groupName = request.path("logGroupName").asText();
        String streamName = request.path("logStreamName").asText();
        logsService.createLogStream(groupName, streamName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDeleteLogStream(JsonNode request, String region) {
        String groupName = request.path("logGroupName").asText();
        String streamName = request.path("logStreamName").asText();
        logsService.deleteLogStream(groupName, streamName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeLogStreams(JsonNode request, String region) {
        String groupName = request.path("logGroupName").asText();
        String prefix = request.path("logStreamNamePrefix").asText(null);
        List<LogStream> streams = logsService.describeLogStreams(groupName, prefix, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode streamsArray = objectMapper.createArrayNode();
        for (LogStream s : streams) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("logStreamName", s.getLogStreamName());
            node.put("createdTime", s.getCreatedTime());
            node.put("lastIngestionTime", s.getLastIngestionTime());
            node.put("uploadSequenceToken", s.getUploadSequenceToken());
            node.put("storedBytes", s.getStoredBytes());
            if (s.getFirstEventTimestamp() != null) {
                node.put("firstEventTimestamp", s.getFirstEventTimestamp());
            }
            if (s.getLastEventTimestamp() != null) {
                node.put("lastEventTimestamp", s.getLastEventTimestamp());
            }
            streamsArray.add(node);
        }
        response.set("logStreams", streamsArray);
        return Response.ok(response).build();
    }

    private Response handlePutLogEvents(JsonNode request, String region) {
        String groupName = request.path("logGroupName").asText();
        String streamName = request.path("logStreamName").asText();

        List<Map<String, Object>> events = new ArrayList<>();
        request.path("logEvents").forEach(evt -> {
            Map<String, Object> event = new HashMap<>();
            event.put("timestamp", evt.path("timestamp").asLong());
            event.put("message", evt.path("message").asText());
            events.add(event);
        });

        String nextToken = logsService.putLogEvents(groupName, streamName, events, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("nextSequenceToken", nextToken);
        return Response.ok(response).build();
    }

    private Response handleGetLogEvents(JsonNode request, String region) {
        String groupName = request.path("logGroupName").asText();
        String streamName = request.path("logStreamName").asText();
        Long startTime = request.has("startTime") ? request.path("startTime").asLong() : null;
        Long endTime = request.has("endTime") ? request.path("endTime").asLong() : null;
        int limit = request.path("limit").asInt(0);
        boolean startFromHead = request.path("startFromHead").asBoolean(false);

        CloudWatchLogsService.LogEventsResult result =
                logsService.getLogEvents(groupName, streamName, startTime, endTime, limit, startFromHead, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("events", buildEventsArray(result.events()));
        response.put("nextForwardToken", result.nextForwardToken());
        response.put("nextBackwardToken", result.nextBackwardToken());
        return Response.ok(response).build();
    }

    private Response handleFilterLogEvents(JsonNode request, String region) {
        String groupName = request.path("logGroupName").asText();
        Long startTime = request.has("startTime") ? request.path("startTime").asLong() : null;
        Long endTime = request.has("endTime") ? request.path("endTime").asLong() : null;
        String filterPattern = request.path("filterPattern").asText(null);
        int limit = request.path("limit").asInt(0);

        List<String> streamNames = new ArrayList<>();
        request.path("logStreamNames").forEach(n -> streamNames.add(n.asText()));

        CloudWatchLogsService.FilteredLogEventsResult result =
                logsService.filterLogEvents(groupName, streamNames, startTime, endTime, filterPattern, limit, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("events", buildEventsArray(result.events()));
        if (result.nextToken() != null) {
            response.put("nextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handlePutRetentionPolicy(JsonNode request, String region) {
        String groupName = request.path("logGroupName").asText();
        int days = request.path("retentionInDays").asInt();
        logsService.putRetentionPolicy(groupName, days, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDeleteRetentionPolicy(JsonNode request, String region) {
        String groupName = request.path("logGroupName").asText();
        logsService.deleteRetentionPolicy(groupName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleTagLogGroup(JsonNode request, String region) {
        String groupName = request.path("logGroupName").asText();
        Map<String, String> tags = extractTags(request.path("tags"));
        logsService.tagLogGroup(groupName, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagLogGroup(JsonNode request, String region) {
        String groupName = request.path("logGroupName").asText();
        List<String> tagKeys = new ArrayList<>();
        request.path("tags").forEach(k -> tagKeys.add(k.asText()));
        logsService.untagLogGroup(groupName, tagKeys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsLogGroup(JsonNode request, String region) {
        String groupName = request.path("logGroupName").asText();
        Map<String, String> tags = logsService.listTagsLogGroup(groupName, region);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode tagsNode = objectMapper.createObjectNode();
        tags.forEach(tagsNode::put);
        response.set("tags", tagsNode);
        return Response.ok(response).build();
    }

    private Response handleListTagsForResource(JsonNode request, String region) {
        String resourceArn = request.path("resourceArn").asText();
        String groupName = extractLogGroupNameFromArn(resourceArn);
        Map<String, String> tags = logsService.listTagsLogGroup(groupName, region);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode tagsNode = objectMapper.createObjectNode();
        tags.forEach(tagsNode::put);
        response.set("tags", tagsNode);
        return Response.ok(response).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        String resourceArn = request.path("resourceArn").asText();
        String groupName = extractLogGroupNameFromArn(resourceArn);
        Map<String, String> tags = extractTags(request.path("tags"));
        logsService.tagLogGroup(groupName, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        String resourceArn = request.path("resourceArn").asText();
        String groupName = extractLogGroupNameFromArn(resourceArn);
        List<String> tagKeys = new ArrayList<>();
        request.path("tagKeys").forEach(k -> tagKeys.add(k.asText()));
        logsService.untagLogGroup(groupName, tagKeys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private String extractLogGroupNameFromArn(String arn) {
        if (arn != null && arn.contains(":log-group:")) {
            String name = arn.substring(arn.indexOf(":log-group:") + ":log-group:".length());
            if (name.endsWith(":*")) {
                name = name.substring(0, name.length() - 2);
            }
            return name;
        }
        return arn;
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ArrayNode buildEventsArray(List<LogEvent> events) {
        ArrayNode array = objectMapper.createArrayNode();
        for (LogEvent e : events) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("eventId", e.getEventId());
            node.put("timestamp", e.getTimestamp());
            node.put("message", e.getMessage());
            node.put("ingestionTime", e.getIngestionTime());
            array.add(node);
        }
        return array;
    }

    private Map<String, String> extractTags(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        }
        return tags;
    }

}
