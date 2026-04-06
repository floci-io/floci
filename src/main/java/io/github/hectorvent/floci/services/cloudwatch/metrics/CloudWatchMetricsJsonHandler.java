package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles CloudWatch Metrics requests via the JSON 1.0 protocol.
 * AWS SDK v3 for CloudWatch uses X-Amz-Target: GraniteServiceVersion20100801.*
 */
@ApplicationScoped
public class CloudWatchMetricsJsonHandler {

    private final CloudWatchMetricsService metricsService;
    private final ObjectMapper objectMapper;

    @Inject
    public CloudWatchMetricsJsonHandler(CloudWatchMetricsService metricsService, ObjectMapper objectMapper) {
        this.metricsService = metricsService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "PutMetricData" -> handlePutMetricData(request, region);
            case "ListMetrics" -> handleListMetrics(request, region);
            case "GetMetricStatistics" -> handleGetMetricStatistics(request, region);
            case "PutMetricAlarm" -> handlePutMetricAlarm(request, region);
            case "DescribeAlarms" -> handleDescribeAlarms(request, region);
            case "DeleteAlarms" -> handleDeleteAlarms(request, region);
            case "SetAlarmState" -> handleSetAlarmState(request, region);
            case "GetMetricData" -> Response.ok(objectMapper.createObjectNode().putArray("MetricDataResults")).build();
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handlePutMetricData(JsonNode request, String region) {
        String namespace = request.path("Namespace").asText();
        List<MetricDatum> datums = parseMetricDataJson(request.path("MetricData"));
        metricsService.putMetricData(namespace, datums, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListMetrics(JsonNode request, String region) {
        String namespace = request.has("Namespace") ? request.path("Namespace").asText() : null;
        String metricName = request.has("MetricName") ? request.path("MetricName").asText() : null;
        List<Dimension> dimensions = parseDimensionsJson(request.path("Dimensions"));

        List<CloudWatchMetricsService.MetricIdentity> metrics =
                metricsService.listMetrics(namespace, metricName, dimensions, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode metricsArray = response.putArray("Metrics");
        for (var m : metrics) {
            ObjectNode mNode = metricsArray.addObject();
            mNode.put("Namespace", m.namespace());
            mNode.put("MetricName", m.metricName());
            ArrayNode dims = mNode.putArray("Dimensions");
            if (m.dimensions() != null) {
                for (Dimension d : m.dimensions()) {
                    dims.addObject().put("Name", d.name()).put("Value", d.value());
                }
            }
        }
        return Response.ok(response).build();
    }

    private Response handleGetMetricStatistics(JsonNode request, String region) {
        String namespace = request.path("Namespace").asText();
        String metricName = request.path("MetricName").asText();
        List<Dimension> dimensions = parseDimensionsJson(request.path("Dimensions"));
        int period = request.path("Period").asInt(60);
        Instant startTime = parseInstant(request.path("StartTime").asText(null));
        Instant endTime = parseInstant(request.path("EndTime").asText(null));

        List<String> statistics = new ArrayList<>();
        JsonNode statsNode = request.path("Statistics");
        if (statsNode.isArray()) {
            statsNode.forEach(s -> statistics.add(s.asText()));
        }

        List<CloudWatchMetricsService.Datapoint> datapoints =
                metricsService.getMetricStatistics(namespace, metricName, dimensions,
                        startTime, endTime, period, statistics, null, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Label", metricName);
        ArrayNode dps = response.putArray("Datapoints");
        for (var dp : datapoints) {
            ObjectNode dpNode = dps.addObject();
            dpNode.put("Timestamp", dp.timestamp().getEpochSecond());
            if (statistics.contains("Average")) dpNode.put("Average", dp.average());
            if (statistics.contains("Sum")) dpNode.put("Sum", dp.sum());
            if (statistics.contains("Minimum")) dpNode.put("Minimum", dp.minimum());
            if (statistics.contains("Maximum")) dpNode.put("Maximum", dp.maximum());
            if (statistics.contains("SampleCount")) dpNode.put("SampleCount", dp.sampleCount());
            dpNode.put("Unit", dp.unit());
        }
        return Response.ok(response).build();
    }

    private Response handlePutMetricAlarm(JsonNode request, String region) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName(request.path("AlarmName").asText());
        alarm.setAlarmDescription(request.path("AlarmDescription").asText(null));
        alarm.setMetricName(request.path("MetricName").asText(null));
        alarm.setNamespace(request.path("Namespace").asText(null));
        alarm.setStatistic(request.path("Statistic").asText(null));
        alarm.setPeriod(request.path("Period").asInt(60));
        alarm.setUnit(request.path("Unit").asText(null));
        alarm.setEvaluationPeriods(request.path("EvaluationPeriods").asInt(1));
        alarm.setDatapointsToAlarm(request.path("DatapointsToAlarm").asInt(alarm.getEvaluationPeriods()));
        alarm.setThreshold(request.path("Threshold").asDouble(0));
        alarm.setComparisonOperator(request.path("ComparisonOperator").asText(null));
        alarm.setTreatMissingData(request.path("TreatMissingData").asText(null));
        alarm.setActionsEnabled(request.path("ActionsEnabled").asBoolean(true));
        alarm.setDimensions(parseDimensionsJson(request.path("Dimensions")));

        JsonNode alarmActions = request.path("AlarmActions");
        if (alarmActions.isArray()) {
            alarmActions.forEach(a -> alarm.getAlarmActions().add(a.asText()));
        }
        JsonNode okActions = request.path("OKActions");
        if (okActions.isArray()) {
            okActions.forEach(a -> alarm.getOkActions().add(a.asText()));
        }

        metricsService.putMetricAlarm(alarm, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeAlarms(JsonNode request, String region) {
        List<String> alarmNames = new ArrayList<>();
        JsonNode namesNode = request.path("AlarmNames");
        if (namesNode.isArray()) {
            namesNode.forEach(n -> alarmNames.add(n.asText()));
        }
        String prefix = request.has("AlarmNamePrefix") ? request.path("AlarmNamePrefix").asText() : null;

        List<MetricAlarm> alarms = metricsService.describeAlarms(alarmNames, prefix, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("MetricAlarms");
        for (MetricAlarm a : alarms) {
            ObjectNode node = arr.addObject();
            node.put("AlarmName", a.getAlarmName());
            if (a.getAlarmArn() != null) node.put("AlarmArn", a.getAlarmArn());
            if (a.getAlarmDescription() != null) node.put("AlarmDescription", a.getAlarmDescription());
            if (a.getMetricName() != null) node.put("MetricName", a.getMetricName());
            if (a.getNamespace() != null) node.put("Namespace", a.getNamespace());
            if (a.getStatistic() != null) node.put("Statistic", a.getStatistic());
            node.put("Period", a.getPeriod());
            node.put("EvaluationPeriods", a.getEvaluationPeriods());
            node.put("Threshold", a.getThreshold());
            if (a.getComparisonOperator() != null) node.put("ComparisonOperator", a.getComparisonOperator());
            node.put("ActionsEnabled", a.isActionsEnabled());
            if (a.getStateValue() != null) node.put("StateValue", a.getStateValue());
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteAlarms(JsonNode request, String region) {
        List<String> alarmNames = new ArrayList<>();
        JsonNode namesNode = request.path("AlarmNames");
        if (namesNode.isArray()) {
            namesNode.forEach(n -> alarmNames.add(n.asText()));
        }
        metricsService.deleteAlarms(alarmNames, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSetAlarmState(JsonNode request, String region) {
        String name = request.path("AlarmName").asText();
        String state = request.path("StateValue").asText();
        String reason = request.path("StateReason").asText(null);
        String reasonData = request.path("StateReasonData").asText(null);
        metricsService.setAlarmState(name, state, reason, reasonData, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private List<MetricDatum> parseMetricDataJson(JsonNode node) {
        List<MetricDatum> datums = new ArrayList<>();
        if (!node.isArray()) return datums;
        for (JsonNode item : node) {
            MetricDatum datum = new MetricDatum();
            datum.setMetricName(item.path("MetricName").asText());
            datum.setValue(item.path("Value").asDouble(0));
            datum.setUnit(item.path("Unit").asText(null));
            JsonNode ts = item.path("Timestamp");
            if (!ts.isMissingNode()) {
                Instant parsed = parseInstant(ts.asText(null));
                if (parsed != null) datum.setTimestamp(parsed.getEpochSecond());
            }
            datum.setDimensions(parseDimensionsJson(item.path("Dimensions")));

            JsonNode statsValues = item.path("StatisticValues");
            if (!statsValues.isMissingNode()) {
                datum.setSampleCount(statsValues.path("SampleCount").asDouble(0));
                datum.setSum(statsValues.path("Sum").asDouble(0));
                datum.setMinimum(statsValues.path("Minimum").asDouble(0));
                datum.setMaximum(statsValues.path("Maximum").asDouble(0));
            }
            
            datums.add(datum);
        }
        return datums;
    }

    private List<Dimension> parseDimensionsJson(JsonNode node) {
        List<Dimension> dims = new ArrayList<>();
        if (!node.isArray()) return dims;
        for (JsonNode d : node) {
            dims.add(new Dimension(d.path("Name").asText(), d.path("Value").asText()));
        }
        return dims;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(value));
            } catch (Exception ex) {
                return null;
            }
        }
    }
}
