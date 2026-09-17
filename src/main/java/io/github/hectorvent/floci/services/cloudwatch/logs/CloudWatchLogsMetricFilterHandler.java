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
        requireObject(request, "request");
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
        definition.setFilterPattern(text(request, "filterPattern"));
        List<MetricTransformation> transformations = new ArrayList<>();
        JsonNode transformationNodes = request.get("metricTransformations");
        requireArray(transformationNodes, "metricTransformations");
        transformationNodes.forEach(node -> transformations.add(transformation(node)));
        definition.setMetricTransformations(transformations);
        if (request.has("applyOnTransformedLogs")) {
            JsonNode flag = request.get("applyOnTransformedLogs");
            if (!flag.isBoolean()) {
                throw invalid("applyOnTransformedLogs must be a boolean.");
            }
            definition.setApplyOnTransformedLogs(flag.booleanValue());
        }
        definition.setFieldSelectionCriteria(text(request, "fieldSelectionCriteria"));
        if (request.has("emitSystemFieldDimensions")) {
            List<String> fields = new ArrayList<>();
            JsonNode fieldNodes = request.get("emitSystemFieldDimensions");
            requireArray(fieldNodes, "emitSystemFieldDimensions");
            fieldNodes.forEach(node -> fields.add(string(node, "emitSystemFieldDimensions member")));
            definition.setEmitSystemFieldDimensions(fields);
        }
        metricFilters.putMetricFilter(definition, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private static MetricTransformation transformation(JsonNode node) {
        requireObject(node, "metricTransformation");
        MetricTransformation t = new MetricTransformation();
        t.setMetricName(text(node, "metricName"));
        t.setMetricNamespace(text(node, "metricNamespace"));
        t.setMetricValue(text(node, "metricValue"));
        if (node.has("defaultValue")) {
            JsonNode defaultValue = node.get("defaultValue");
            if (defaultValue.isTextual()) {
                throw new AwsException("SerializationException", "STRING_VALUE cannot be converted to Double", 400);
            }
            if (!defaultValue.isNumber()) {
                throw invalid("defaultValue must be a number.");
            }
            double value = defaultValue.asDouble();
            if (!Double.isFinite(value)) {
                throw invalid("defaultValue must be finite.");
            }
            t.setDefaultValue(value);
        }
        if (node.has("dimensions")) {
            Map<String, String> dimensions = new LinkedHashMap<>();
            requireObject(node.get("dimensions"), "dimensions");
            node.get("dimensions").fields().forEachRemaining(entry ->
                    dimensions.put(entry.getKey(), string(entry.getValue(), "dimension value")));
            t.setDimensions(dimensions);
        }
        t.setUnit(text(node, "unit"));
        return t;
    }

    private Response describeMetricFilters(JsonNode request, String region) {
        CloudWatchLogsMetricFilterService.DescribeMetricFiltersResult result = metricFilters.describeMetricFilters(
                text(request, "logGroupName"), text(request, "filterNamePrefix"), text(request, "metricName"),
                text(request, "metricNamespace"), text(request, "nextToken"),
                limit(request), region);
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
        JsonNode messageNodes = request.get("logEventMessages");
        requireArray(messageNodes, "logEventMessages");
        messageNodes.forEach(node -> messages.add(string(node, "logEventMessages member")));
        List<CloudWatchLogsMetricFilterService.MetricFilterMatchRecord> matches = metricFilters.testMetricFilter(
                text(request, "filterPattern"), messages);
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
        return node.has(member) ? string(node.get(member), member) : null;
    }

    private static String string(JsonNode node, String member) {
        if (!node.isTextual()) {
            throw invalid(member + " must be a string.");
        }
        return node.textValue();
    }

    private static void requireObject(JsonNode node, String member) {
        if (node == null || !node.isObject()) {
            throw invalid(member + " must be an object.");
        }
    }

    private static void requireArray(JsonNode node, String member) {
        if (node == null || !node.isArray()) {
            throw invalid(member + " must be an array.");
        }
    }

    private static Integer limit(JsonNode request) {
        if (!request.has("limit")) {
            return null;
        }
        JsonNode node = request.get("limit");
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1 || node.intValue() > 50) {
            throw invalid("limit must be an integer between 1 and 50.");
        }
        return node.intValue();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
