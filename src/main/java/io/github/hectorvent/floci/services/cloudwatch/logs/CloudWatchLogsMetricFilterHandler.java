package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricTransformation;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wire shapes of the metric filter operations, reached through {@link CloudWatchLogsHandler}:
 * request members in the JSON 1.1 form the Logs API takes, responses as the API reference lists
 * them.
 */
class CloudWatchLogsMetricFilterHandler {

    private final CloudWatchLogsMetricFilterService metricFilters;
    private final ObjectMapper objectMapper;

    CloudWatchLogsMetricFilterHandler(CloudWatchLogsMetricFilterService metricFilters, ObjectMapper objectMapper) {
        this.metricFilters = metricFilters;
        this.objectMapper = objectMapper;
    }

    Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "PutMetricFilter" -> putMetricFilter(request, region);
            case "DescribeMetricFilters" -> describeMetricFilters(request, region);
            case "DeleteMetricFilter" -> deleteMetricFilter(request, region);
            case "TestMetricFilter" -> testMetricFilter(request);
            default -> throw new IllegalStateException("not a metric filter action: " + action);
        };
    }

    int metricFilterCount(String logGroupName, String region) {
        return metricFilters.countMetricFilters(logGroupName, region);
    }

    private Response putMetricFilter(JsonNode request, String region) {
        MetricFilter definition = new MetricFilter();
        definition.setLogGroupName(text(request, "logGroupName"));
        definition.setFilterName(text(request, "filterName"));
        definition.setFilterPattern(request.has("filterPattern") ? request.path("filterPattern").asText() : null);
        List<MetricTransformation> transformations = new ArrayList<>();
        request.path("metricTransformations").forEach(node -> transformations.add(transformation(node)));
        definition.setMetricTransformations(transformations);
        if (request.hasNonNull("applyOnTransformedLogs")) {
            definition.setApplyOnTransformedLogs(request.path("applyOnTransformedLogs").asBoolean());
        }
        definition.setFieldSelectionCriteria(text(request, "fieldSelectionCriteria"));
        if (request.hasNonNull("emitSystemFieldDimensions")) {
            List<String> fields = new ArrayList<>();
            request.path("emitSystemFieldDimensions").forEach(node -> fields.add(node.asText()));
            definition.setEmitSystemFieldDimensions(fields);
        }
        metricFilters.putMetricFilter(definition, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private static MetricTransformation transformation(JsonNode node) {
        MetricTransformation t = new MetricTransformation();
        t.setMetricName(text(node, "metricName"));
        t.setMetricNamespace(text(node, "metricNamespace"));
        t.setMetricValue(text(node, "metricValue"));
        if (node.hasNonNull("defaultValue")) {
            JsonNode defaultValue = node.get("defaultValue");
            if (!defaultValue.isNumber() && !(defaultValue.isTextual() && defaultValue.asText().matches("[+-]?\\d+(\\.\\d+)?"))) {
                throw new AwsException("InvalidParameterException", "defaultValue must be a number.", 400);
            }
            t.setDefaultValue(defaultValue.asDouble());
        }
        if (node.hasNonNull("dimensions")) {
            Map<String, String> dimensions = new LinkedHashMap<>();
            node.get("dimensions").fields().forEachRemaining(entry -> dimensions.put(entry.getKey(), entry.getValue().asText()));
            t.setDimensions(dimensions);
        }
        t.setUnit(text(node, "unit"));
        return t;
    }

    private Response describeMetricFilters(JsonNode request, String region) {
        CloudWatchLogsMetricFilterService.DescribeMetricFiltersResult result = metricFilters.describeMetricFilters(
                text(request, "logGroupName"), text(request, "filterNamePrefix"), text(request, "metricName"),
                text(request, "metricNamespace"), text(request, "nextToken"),
                request.hasNonNull("limit") ? request.path("limit").asInt() : null, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode filters = response.putArray("metricFilters");
        result.metricFilters().forEach(filter -> filters.add(render(filter)));
        if (result.nextToken() != null) {
            response.put("nextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    private ObjectNode render(MetricFilter filter) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("filterName", filter.getFilterName());
        node.put("filterPattern", filter.getFilterPattern());
        ArrayNode transformations = node.putArray("metricTransformations");
        for (MetricTransformation t : filter.getMetricTransformations()) {
            ObjectNode rendered = transformations.addObject();
            rendered.put("metricName", t.getMetricName());
            rendered.put("metricNamespace", t.getMetricNamespace());
            rendered.put("metricValue", t.getMetricValue());
            if (t.getDefaultValue() != null) {
                rendered.put("defaultValue", t.getDefaultValue());
            }
            if (t.getDimensions() != null && !t.getDimensions().isEmpty()) {
                ObjectNode dimensions = rendered.putObject("dimensions");
                t.getDimensions().forEach(dimensions::put);
            }
            if (t.getUnit() != null) {
                rendered.put("unit", t.getUnit());
            }
        }
        node.put("creationTime", filter.getCreationTime());
        node.put("logGroupName", filter.getLogGroupName());
        if (filter.getApplyOnTransformedLogs() != null) {
            node.put("applyOnTransformedLogs", filter.getApplyOnTransformedLogs());
        }
        if (filter.getFieldSelectionCriteria() != null) {
            node.put("fieldSelectionCriteria", filter.getFieldSelectionCriteria());
        }
        if (filter.getEmitSystemFieldDimensions() != null) {
            ArrayNode fields = node.putArray("emitSystemFieldDimensions");
            filter.getEmitSystemFieldDimensions().forEach(fields::add);
        }
        return node;
    }

    private Response deleteMetricFilter(JsonNode request, String region) {
        metricFilters.deleteMetricFilter(text(request, "logGroupName"), text(request, "filterName"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response testMetricFilter(JsonNode request) {
        List<String> messages = new ArrayList<>();
        request.path("logEventMessages").forEach(node -> messages.add(node.asText()));
        List<CloudWatchLogsMetricFilterService.MetricFilterMatchRecord> matches = metricFilters.testMetricFilter(
                request.has("filterPattern") ? request.path("filterPattern").asText() : null, messages);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode rendered = response.putArray("matches");
        for (CloudWatchLogsMetricFilterService.MetricFilterMatchRecord match : matches) {
            ObjectNode node = rendered.addObject();
            node.put("eventNumber", match.eventNumber());
            node.put("eventMessage", match.eventMessage());
            ObjectNode extracted = node.putObject("extractedValues");
            match.extractedValues().forEach(extracted::put);
        }
        return Response.ok(response).build();
    }

    private static String text(JsonNode node, String member) {
        return node.hasNonNull(member) ? node.get(member).asText() : null;
    }
}
