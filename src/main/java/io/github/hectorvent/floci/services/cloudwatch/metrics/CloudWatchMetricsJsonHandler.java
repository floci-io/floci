package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.model.Dashboard;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmHistoryItem;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmMetricDataQuery;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.CloudWatchMetricStreamsService;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.model.MetricStream;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.model.MetricStreamFilter;
import io.github.hectorvent.floci.services.cloudwatch.metricstreams.model.MetricStreamStatisticsConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles CloudWatch Metrics requests via the JSON 1.0 protocol.
 * AWS SDK v3 for CloudWatch uses X-Amz-Target: GraniteServiceVersion20100801.*
 */
@ApplicationScoped
public class CloudWatchMetricsJsonHandler {

    private static final Logger LOG = Logger.getLogger(CloudWatchMetricsJsonHandler.class);

    /**
     * The operations served through the console's {@code CloudWatchVersion20130116.} target, each
     * with the request members accepted there: the ones this handler honours, plus
     * {@code NextToken} and {@code MaxRecords}, which change nothing because every result comes
     * back at once.
     */
    private static final Map<String, Set<String>> CONSOLE_TARGET_OPERATIONS = Map.of(
            "ListMetrics", Set.of("Namespace", "MetricName", "Dimensions", "NextToken"),
            "DescribeAlarms", Set.of("AlarmNames", "AlarmNamePrefix", "MaxRecords", "NextToken"),
            "GetMetricStatistics", Set.of("Namespace", "MetricName", "Dimensions", "StartTime", "EndTime",
                    "Period", "Statistics", "Unit"),
            "DeleteAlarms", Set.of("AlarmNames"));

    private final CloudWatchMetricsService metricsService;
    private final CloudWatchDashboardsService dashboardsService;
    private final CloudWatchMetricStreamsService metricStreamsService;
    private final ObjectMapper objectMapper;

    @Inject
    public CloudWatchMetricsJsonHandler(CloudWatchMetricsService metricsService,
                                        CloudWatchDashboardsService dashboardsService,
                                        CloudWatchMetricStreamsService metricStreamsService,
                                        ObjectMapper objectMapper) {
        this.metricsService = metricsService;
        this.dashboardsService = dashboardsService;
        this.metricStreamsService = metricStreamsService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        String normalizedAction = action.substring(0, 1).toUpperCase() + action.substring(1);
        return switch (normalizedAction) {
            case "PutMetricData" -> handlePutMetricData(request, region);
            case "ListMetrics" -> handleListMetrics(request, region);
            case "GetMetricStatistics" -> handleGetMetricStatistics(request, region);
            case "PutMetricAlarm" -> handlePutMetricAlarm(request, region);
            case "PutCompositeAlarm" -> handlePutCompositeAlarm(request, region);
            case "DescribeAlarms" -> handleDescribeAlarms(request, region);
            case "DeleteAlarms" -> handleDeleteAlarms(request, region);
            case "SetAlarmState" -> handleSetAlarmState(request, region);
            case "DescribeAlarmHistory" -> handleDescribeAlarmHistory(request, region);
            case "ListTagsForResource" -> handleListTagsForResource(request, region);
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "GetMetricData" -> handleGetMetricData(request, region);
            case "PutDashboard" -> handlePutDashboard(request, region);
            case "GetDashboard" -> handleGetDashboard(request, region);
            case "ListDashboards" -> handleListDashboards(request, region);
            case "DeleteDashboards" -> handleDeleteDashboards(request, region);
            case "PutMetricStream" -> handlePutMetricStream(request, region);
            case "GetMetricStream" -> handleGetMetricStream(request, region);
            case "ListMetricStreams" -> handleListMetricStreams(request, region);
            case "DeleteMetricStream" -> handleDeleteMetricStream(request, region);
            case "StartMetricStreams" -> handleStartMetricStreams(request, region);
            case "StopMetricStreams" -> handleStopMetricStreams(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported by CloudWatch JSON."))
                    .build();
        };
    }

    /**
     * Handles an operation named with the {@code CloudWatchVersion20130116.} target of the
     * CloudWatch console's API. The console's model gives many operations and members shapes of
     * their own, which this handler would silently misread or ignore, such as {@code GetMetricData}
     * with {@code Defaults} and {@code Metrics}, or {@code PutMetricAlarm} metric items with
     * {@code Alias} and {@code MetricName}. So only the operations and top-level members in
     * {@code CONSOLE_TARGET_OPERATIONS} are served: any other operation, and any other member set
     * to a value other than {@code null} or {@code false}, is refused rather than answered as if it
     * were unset, and a top-level {@code null} member is dropped. Members nested inside the served
     * ones are passed on unchecked, nulls included. A {@code DescribeAlarms} answer that would list
     * an alarm defined with {@code Metrics} is refused too, since the console's API gives those
     * items its own shape, with {@code Alias} instead of {@code Id} and {@code MetricStat}. The
     * name matches {@code DynamoDbJsonHandler.handleLegacy}, the entry point of the other JSON-only
     * target.
     */
    public Response handleLegacy(String action, JsonNode request, String region) {
        Set<String> servedMembers = CONSOLE_TARGET_OPERATIONS.get(action);
        if (servedMembers == null) {
            return unsupportedWithConsoleTarget("Operation " + action);
        }
        ObjectNode served = objectMapper.createObjectNode();
        for (Map.Entry<String, JsonNode> member : request.properties()) {
            JsonNode value = member.getValue();
            if (value.isNull()) {
                continue;
            }
            if (servedMembers.contains(member.getKey())) {
                served.set(member.getKey(), value);
            } else if (!value.isBoolean() || value.booleanValue()) {
                return unsupportedWithConsoleTarget("Parameter " + member.getKey() + " of " + action);
            }
        }
        Response response = handle(action, served, region);
        if ("DescribeAlarms".equals(action) && response.getEntity() instanceof JsonNode body) {
            for (JsonNode alarm : body.path("MetricAlarms")) {
                if (alarm.has("Metrics")) {
                    return unsupportedWithConsoleTarget("DescribeAlarms of alarm "
                            + alarm.path("AlarmName").asText() + ", which is defined with Metrics,");
                }
            }
        }
        return response;
    }

    private static Response unsupportedWithConsoleTarget(String subject) {
        return Response.status(400)
                .entity(new AwsErrorResponse("UnsupportedOperation", subject
                        + " is not supported by CloudWatch JSON with the CloudWatchVersion20130116 target."))
                .build();
    }

    private Response handlePutMetricData(JsonNode request, String region) {
        String namespace = request.path("Namespace").asText();
        List<MetricDatum> datums = parseMetricDataJson(request.path("MetricData"), "MetricData");
        int entityIndex = 0;
        for (JsonNode entityMetricData : request.path("EntityMetricData")) {
            entityIndex++;
            datums.addAll(parseMetricDataJson(entityMetricData.path("MetricData"),
                    "EntityMetricData.member." + entityIndex + ".MetricData"));
        }
        LOG.debugv("JSON PutMetricData parsed {0} datums for namespace {1}", datums.size(), namespace);
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
        for (CloudWatchMetricsService.MetricIdentity m : metrics) {
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
        Instant startTime = parseInstantNode(request.path("StartTime"));
        Instant endTime = parseInstantNode(request.path("EndTime"));

        List<String> statistics = new ArrayList<>();
        JsonNode statsNode = request.path("Statistics");
        if (statsNode.isArray()) {
            statsNode.forEach(s -> statistics.add(s.asText()));
        }

        String unit = request.hasNonNull("Unit") ? request.path("Unit").asText() : null;

        List<CloudWatchMetricsService.Datapoint> datapoints =
                metricsService.getMetricStatistics(namespace, metricName, dimensions,
                        startTime, endTime, period, statistics, unit, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Label", metricName);
        ArrayNode dps = response.putArray("Datapoints");
        for (CloudWatchMetricsService.Datapoint dp : datapoints) {
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
        parseAlarmFields(request, alarm);
        alarm.setMetricName(request.path("MetricName").asText(null));
        alarm.setNamespace(request.path("Namespace").asText(null));
        alarm.setStatistic(request.path("Statistic").asText(null));
        JsonNode metrics = request.path("Metrics");
        if (metrics.isArray()) {
            for (JsonNode query : metrics) {
                alarm.getMetrics().add(objectMapper.convertValue(query, AlarmMetricDataQuery.class));
            }
        }
        alarm.setPeriod(alarm.getMetrics().isEmpty() ? request.path("Period").asInt(60) : null);
        alarm.setUnit(request.path("Unit").asText(null));
        alarm.setEvaluationPeriods(request.path("EvaluationPeriods").asInt(1));
        alarm.setDatapointsToAlarm(request.hasNonNull("DatapointsToAlarm")
                ? request.path("DatapointsToAlarm").asInt() : null);
        alarm.setThreshold(request.path("Threshold").asDouble(0));
        alarm.setComparisonOperator(request.path("ComparisonOperator").asText(null));
        alarm.setTreatMissingData(request.path("TreatMissingData").asText(null));
        alarm.setDimensions(parseDimensionsJson(request.path("Dimensions")));

        metricsService.putMetricAlarm(alarm, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handlePutCompositeAlarm(JsonNode request, String region) {
        rejectSuppressor(request);
        CompositeAlarm alarm = new CompositeAlarm();
        parseAlarmFields(request, alarm);
        alarm.setAlarmRule(request.path("AlarmRule").asText(null));
        metricsService.putCompositeAlarm(alarm, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private static void rejectSuppressor(JsonNode request) {
        if (request.has("ActionsSuppressor") || request.has("ActionsSuppressorWaitPeriod")
                || request.has("ActionsSuppressorExtensionPeriod")) {
            throw new AwsException("ValidationError", "Composite action suppression is not supported", 400);
        }
    }

    private void parseAlarmFields(JsonNode request, Alarm alarm) {
        alarm.setAlarmName(request.path("AlarmName").asText());
        alarm.setAlarmDescription(request.path("AlarmDescription").asText(null));
        alarm.setActionsEnabled(request.path("ActionsEnabled").asBoolean(true));

        JsonNode alarmActions = request.path("AlarmActions");
        if (alarmActions.isArray()) {
            alarmActions.forEach(a -> alarm.getAlarmActions().add(a.asText()));
        }
        JsonNode okActions = request.path("OKActions");
        if (okActions.isArray()) {
            okActions.forEach(a -> alarm.getOkActions().add(a.asText()));
        }
        JsonNode insufficientDataActions = request.path("InsufficientDataActions");
        if (insufficientDataActions.isArray()) {
            insufficientDataActions.forEach(a -> alarm.getInsufficientDataActions().add(a.asText()));
        }

        JsonNode tagsNode = request.has("Tags") ? request.path("Tags") : request.path("tags");
        if (tagsNode.isArray()) {
            Map<String, String> tags = new LinkedHashMap<>();
            tagsNode.forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText()));
            alarm.setTags(tags);
        }

    }

    private Response handleDescribeAlarms(JsonNode request, String region) {
        List<String> alarmNames = new ArrayList<>();
        JsonNode namesNode = request.path("AlarmNames");
        if (namesNode.isArray()) {
            namesNode.forEach(n -> alarmNames.add(n.asText()));
        }
        String prefix = request.has("AlarmNamePrefix") ? request.path("AlarmNamePrefix").asText() : null;

        List<String> types = new ArrayList<>();
        request.path("AlarmTypes").forEach(value -> types.add(value.asText()));
        metricsService.validateAlarmTypes(types);
        List<MetricAlarm> alarms = types.isEmpty() || types.contains("MetricAlarm")
                ? metricsService.describeAlarms(alarmNames, prefix, region) : List.of();

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("MetricAlarms");
        for (MetricAlarm a : alarms) {
            ObjectNode node = arr.addObject();
            describeAlarmFields(node, a);
            if (!a.getMetrics().isEmpty()) {
                node.set("Metrics", objectMapper.valueToTree(a.getMetrics()));
            } else {
                if (a.getMetricName() != null) {
                    node.put("MetricName", a.getMetricName());
                }
                if (a.getNamespace() != null) {
                    node.put("Namespace", a.getNamespace());
                }
                if (a.getStatistic() != null) {
                    node.put("Statistic", a.getStatistic());
                }
                ArrayNode dimensions = node.putArray("Dimensions");
                a.getDimensions().forEach(d -> {
                    ObjectNode dimNode = dimensions.addObject();
                    dimNode.put("Name", d.name());
                    dimNode.put("Value", d.value());
                });
                if (a.getPeriod() != null) {
                    node.put("Period", a.getPeriod());
                }
                if (a.getUnit() != null) {
                    node.put("Unit", a.getUnit());
                }
            }
            node.put("EvaluationPeriods", a.getEvaluationPeriods());
            if (a.getDatapointsToAlarm() != null) {
                node.put("DatapointsToAlarm", a.getDatapointsToAlarm());
            }
            node.put("Threshold", a.getThreshold());
            if (a.getComparisonOperator() != null) node.put("ComparisonOperator", a.getComparisonOperator());
            if (a.getTreatMissingData() != null) node.put("TreatMissingData", a.getTreatMissingData());

        }
        ArrayNode composites = response.putArray("CompositeAlarms");
        if (types.contains("CompositeAlarm")) {
            for (CompositeAlarm alarm : metricsService.describeCompositeAlarms(alarmNames, prefix, region)) {
                ObjectNode node = composites.addObject();
                describeAlarmFields(node, alarm);
                node.put("AlarmRule", alarm.getAlarmRule());
            }
        }
        response.putArray("LogAlarms");
        return Response.ok(response).build();
    }

    private void describeAlarmFields(ObjectNode node, Alarm a) {
        node.put("AlarmName", a.getAlarmName());
        if (a.getAlarmArn() != null) {
            node.put("AlarmArn", a.getAlarmArn());
        }
        if (a.getAlarmDescription() != null) {
            node.put("AlarmDescription", a.getAlarmDescription());
        }
        ArrayNode alarmActions = node.putArray("AlarmActions");
        a.getAlarmActions().forEach(alarmActions::add);
        ArrayNode okActions = node.putArray("OKActions");
        a.getOkActions().forEach(okActions::add);
        ArrayNode insufficientDataActions = node.putArray("InsufficientDataActions");
        a.getInsufficientDataActions().forEach(insufficientDataActions::add);
        node.put("ActionsEnabled", a.isActionsEnabled());
        if (a.getStateValue() != null) {
            node.put("StateValue", a.getStateValue());
        }
        if (a.getStateReason() != null) {
            node.put("StateReason", a.getStateReason());
        }
        if (a.getStateReasonData() != null) {
            node.put("StateReasonData", a.getStateReasonData());
        }
        node.put("StateUpdatedTimestamp", a.getStateUpdatedTimestamp());
        node.put("StateTransitionedTimestamp", a.lastTransitionTimestamp());
        node.put("AlarmConfigurationUpdatedTimestamp", a.getAlarmConfigurationUpdatedTimestamp());
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

    private Response handleDescribeAlarmHistory(JsonNode request, String region) {
        List<String> types = new ArrayList<>();
        request.path("AlarmTypes").forEach(value -> types.add(value.asText()));
        PaginatedResult<AlarmHistoryItem> page = metricsService.describeAlarmHistory(
                request.path("AlarmName").asText(null), types, request.path("HistoryItemType").asText(null),
                parseInstantNode(request.path("StartDate")), parseInstantNode(request.path("EndDate")),
                Pagination.parseMaxResults(request, "MaxRecords", "MalformedInput"),
                request.path("NextToken").asText(null), request.path("ScanBy").asText(null), region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("AlarmHistoryItems");
        for (AlarmHistoryItem item : page.items()) {
            items.addObject()
                    .put("AlarmName", item.alarmName())
                    .put("AlarmType", item.alarmType())
                    .put("HistoryData", item.historyData())
                    .put("HistoryItemType", item.historyItemType())
                    .put("HistorySummary", item.historySummary())
                    .put("Timestamp", item.timestamp() / 1000.0);
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleListTagsForResource(JsonNode request, String region) {
        String arn = request.has("ResourceARN") ? request.path("ResourceARN").asText() : request.path("ResourceArn").asText();
        if (arn.isEmpty()) arn = request.path("resourceArn").asText();

        Map<String, String> tags;
        if (CloudWatchDashboardsService.isDashboardArn(arn)) {
            tags = dashboardsService.listTagsForResource(arn, region);
        } else if (CloudWatchMetricStreamsService.isMetricStreamArn(arn)) {
            tags = metricStreamsService.listTagsForResource(arn, region);
        } else {
            tags = metricsService.listTagsForResource(arn, region);
        }
        ArrayNode tagsArray = objectMapper.createArrayNode();
        tags.forEach((k, v) -> tagsArray.addObject().put("Key", k).put("Value", v));
        return Response.ok(objectMapper.createObjectNode().set("Tags", tagsArray)).build();
    }

    private Response handleTagResource(JsonNode request, String region) {
        String arn = request.has("ResourceARN") ? request.path("ResourceARN").asText() : request.path("ResourceArn").asText();
        if (arn.isEmpty()) arn = request.path("resourceArn").asText();

        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode tagsNode = request.has("Tags") ? request.path("Tags") : request.path("tags");
        if (tagsNode.isArray()) {
            tagsNode.forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText()));
        }
        if (CloudWatchDashboardsService.isDashboardArn(arn)) {
            dashboardsService.tagResource(arn, tags, region);
        } else if (CloudWatchMetricStreamsService.isMetricStreamArn(arn)) {
            metricStreamsService.tagResource(arn, tags, region);
        } else {
            metricsService.tagResource(arn, tags, region);
        }
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagResource(JsonNode request, String region) {
        String arn = request.has("ResourceARN") ? request.path("ResourceARN").asText() : request.path("ResourceArn").asText();
        if (arn.isEmpty()) arn = request.path("resourceArn").asText();

        List<String> keys = new ArrayList<>();
        JsonNode keysNode = request.has("TagKeys") ? request.path("TagKeys") : request.path("tagKeys");
        if (keysNode.isArray()) {
            keysNode.forEach(k -> keys.add(k.asText()));
        }
        if (CloudWatchDashboardsService.isDashboardArn(arn)) {
            dashboardsService.untagResource(arn, keys, region);
        } else if (CloudWatchMetricStreamsService.isMetricStreamArn(arn)) {
            metricStreamsService.untagResource(arn, keys, region);
        } else {
            metricsService.untagResource(arn, keys, region);
        }
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetMetricData(JsonNode request, String region) {
        Instant startTime = parseInstantNode(request.path("StartTime"));
        Instant endTime = parseInstantNode(request.path("EndTime"));

        List<CloudWatchMetricsService.MetricDataQuery> queries = new ArrayList<>();
        JsonNode queriesNode = request.path("MetricDataQueries");
        if (queriesNode.isArray()) {
            for (JsonNode qNode : queriesNode) {
                String id = qNode.path("Id").asText();
                String expression = qNode.has("Expression") ? qNode.path("Expression").asText() : null;
                String label = qNode.has("Label") ? qNode.path("Label").asText() : null;
                boolean returnData = qNode.path("ReturnData").asBoolean(true);

                CloudWatchMetricsService.MetricStat metricStat = null;
                JsonNode msNode = qNode.path("MetricStat");
                if (!msNode.isMissingNode()) {
                    JsonNode metricNode = msNode.path("Metric");
                    String namespace = metricNode.path("Namespace").asText();
                    String metricName = metricNode.path("MetricName").asText();
                    int period = msNode.path("Period").asInt(60);
                    String stat = msNode.path("Stat").asText();
                    String unit = msNode.hasNonNull("Unit") ? msNode.path("Unit").asText() : null;
                    List<Dimension> dims = parseDimensionsJson(metricNode.path("Dimensions"));

                    metricStat = new CloudWatchMetricsService.MetricStat(
                            namespace, metricName, dims, period, stat, unit);
                }

                queries.add(new CloudWatchMetricsService.MetricDataQuery(
                        id, metricStat, expression, label, returnData));
            }
        }

        List<CloudWatchMetricsService.MetricDataResult> results =
                metricsService.getMetricData(queries, startTime, endTime, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode resultsArray = response.putArray("MetricDataResults");
        for (CloudWatchMetricsService.MetricDataResult r : results) {
            ObjectNode rNode = resultsArray.addObject();
            rNode.put("Id", r.id());
            rNode.put("Label", r.label());
            rNode.put("StatusCode", r.statusCode());

            ArrayNode tsArray = rNode.putArray("Timestamps");
            for (Instant ts : r.timestamps()) {
                tsArray.add(ts.getEpochSecond());
            }

            ArrayNode valArray = rNode.putArray("Values");
            for (Double v : r.values()) {
                valArray.add(v);
            }
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Dashboards ────────────────────────────

    private Response handlePutDashboard(JsonNode request, String region) {
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode tagsNode = request.path("Tags");
        if (tagsNode.isArray()) {
            tagsNode.forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText()));
        }
        dashboardsService.putDashboard(
                request.path("DashboardName").asText(null),
                request.path("DashboardBody").asText(null),
                tags,
                region);
        // DashboardValidationMessages is optional on the response shape, but AWS always
        // sends the list. It stays empty here: the body is stored opaquely, so nothing
        // inspects it and no validation warning can be produced.
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("DashboardValidationMessages");
        return Response.ok(response).build();
    }

    private Response handleGetDashboard(JsonNode request, String region) {
        Dashboard dashboard = dashboardsService.getDashboard(
                request.path("DashboardName").asText(null), region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("DashboardArn", dashboard.getDashboardArn());
        response.put("DashboardName", dashboard.getDashboardName());
        response.put("DashboardBody", dashboard.getDashboardBody());
        return Response.ok(response).build();
    }

    private Response handleListDashboards(JsonNode request, String region) {
        String prefix = request.has("DashboardNamePrefix")
                ? request.path("DashboardNamePrefix").asText() : null;
        List<Dashboard> dashboards = dashboardsService.listDashboards(prefix, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("DashboardEntries");
        for (Dashboard d : dashboards) {
            ObjectNode node = entries.addObject();
            node.put("DashboardName", d.getDashboardName());
            node.put("DashboardArn", d.getDashboardArn());
            node.put("LastModified", d.getLastModified());
            node.put("Size", d.getSize());
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteDashboards(JsonNode request, String region) {
        List<String> names = new ArrayList<>();
        JsonNode namesNode = request.path("DashboardNames");
        if (namesNode.isArray()) {
            namesNode.forEach(n -> names.add(n.asText()));
        }
        dashboardsService.deleteDashboards(names, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ──────────────────────────── Metric Streams ────────────────────────────

    private Response handlePutMetricStream(JsonNode request, String region) {
        MetricStream stream = new MetricStream();
        stream.setName(request.path("Name").asText(null));
        stream.setFirehoseArn(request.path("FirehoseArn").asText(null));
        stream.setRoleArn(request.path("RoleArn").asText(null));
        stream.setOutputFormat(request.path("OutputFormat").asText(null));
        stream.setIncludeLinkedAccountsMetrics(request.path("IncludeLinkedAccountsMetrics").asBoolean(false));
        stream.setIncludeFilters(parseStreamFiltersJson(request.path("IncludeFilters")));
        stream.setExcludeFilters(parseStreamFiltersJson(request.path("ExcludeFilters")));
        stream.setStatisticsConfigurations(parseStatisticsConfigurationsJson(request.path("StatisticsConfigurations")));
        JsonNode tagsNode = request.path("Tags");
        if (tagsNode.isArray()) {
            Map<String, String> tags = new LinkedHashMap<>();
            tagsNode.forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText()));
            stream.setTags(tags);
        }

        MetricStream stored = metricStreamsService.putMetricStream(stream, region);
        return Response.ok(objectMapper.createObjectNode().put("Arn", stored.getArn())).build();
    }

    private Response handleGetMetricStream(JsonNode request, String region) {
        MetricStream stream = metricStreamsService.getMetricStream(request.path("Name").asText(null), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Arn", stream.getArn());
        response.put("Name", stream.getName());
        response.put("FirehoseArn", stream.getFirehoseArn());
        response.put("RoleArn", stream.getRoleArn());
        response.put("State", stream.getState());
        response.put("OutputFormat", stream.getOutputFormat());
        response.put("CreationDate", stream.getCreationDate());
        response.put("LastUpdateDate", stream.getLastUpdateDate());
        response.put("IncludeLinkedAccountsMetrics", stream.isIncludeLinkedAccountsMetrics());
        if (!stream.getIncludeFilters().isEmpty()) {
            response.set("IncludeFilters", streamFiltersNode(stream.getIncludeFilters()));
        }
        if (!stream.getExcludeFilters().isEmpty()) {
            response.set("ExcludeFilters", streamFiltersNode(stream.getExcludeFilters()));
        }
        if (!stream.getStatisticsConfigurations().isEmpty()) {
            response.set("StatisticsConfigurations",
                    statisticsConfigurationsNode(stream.getStatisticsConfigurations()));
        }
        return Response.ok(response).build();
    }

    private Response handleListMetricStreams(JsonNode request, String region) {
        Integer maxResults = request.hasNonNull("MaxResults") ? request.path("MaxResults").asInt() : null;
        PaginatedResult<MetricStream> page = metricStreamsService.listMetricStreams(
                maxResults, request.path("NextToken").asText(null), region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        for (MetricStream stream : page.items()) {
            ObjectNode entry = entries.addObject();
            entry.put("Arn", stream.getArn());
            entry.put("Name", stream.getName());
            entry.put("FirehoseArn", stream.getFirehoseArn());
            entry.put("State", stream.getState());
            entry.put("OutputFormat", stream.getOutputFormat());
            entry.put("CreationDate", stream.getCreationDate());
            entry.put("LastUpdateDate", stream.getLastUpdateDate());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteMetricStream(JsonNode request, String region) {
        metricStreamsService.deleteMetricStream(request.path("Name").asText(null), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleStartMetricStreams(JsonNode request, String region) {
        metricStreamsService.startMetricStreams(parseNamesJson(request.path("Names")), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleStopMetricStreams(JsonNode request, String region) {
        metricStreamsService.stopMetricStreams(parseNamesJson(request.path("Names")), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private List<String> parseNamesJson(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> names.add(n.asText()));
        }
        return names;
    }

    private ArrayNode streamFiltersNode(List<MetricStreamFilter> filters) {
        ArrayNode array = objectMapper.createArrayNode();
        for (MetricStreamFilter filter : filters) {
            ObjectNode node = array.addObject();
            node.put("Namespace", filter.getNamespace());
            if (!filter.getMetricNames().isEmpty()) {
                ArrayNode metricNames = node.putArray("MetricNames");
                filter.getMetricNames().forEach(metricNames::add);
            }
        }
        return array;
    }

    private ArrayNode statisticsConfigurationsNode(List<MetricStreamStatisticsConfiguration> configurations) {
        ArrayNode array = objectMapper.createArrayNode();
        for (MetricStreamStatisticsConfiguration configuration : configurations) {
            ObjectNode node = array.addObject();
            ArrayNode includeMetrics = node.putArray("IncludeMetrics");
            for (MetricStreamStatisticsConfiguration.IncludeMetric metric : configuration.getIncludeMetrics()) {
                includeMetrics.addObject()
                        .put("Namespace", metric.namespace())
                        .put("MetricName", metric.metricName());
            }
            ArrayNode additionalStatistics = node.putArray("AdditionalStatistics");
            configuration.getAdditionalStatistics().forEach(additionalStatistics::add);
        }
        return array;
    }

    private List<MetricStreamFilter> parseStreamFiltersJson(JsonNode node) {
        List<MetricStreamFilter> filters = new ArrayList<>();
        if (!node.isArray()) {
            return filters;
        }
        for (JsonNode entry : node) {
            List<String> metricNames = new ArrayList<>();
            entry.path("MetricNames").forEach(n -> metricNames.add(n.asText()));
            filters.add(new MetricStreamFilter(entry.path("Namespace").asText(null), metricNames));
        }
        return filters;
    }

    private List<MetricStreamStatisticsConfiguration> parseStatisticsConfigurationsJson(JsonNode node) {
        List<MetricStreamStatisticsConfiguration> configurations = new ArrayList<>();
        if (!node.isArray()) {
            return configurations;
        }
        for (JsonNode entry : node) {
            MetricStreamStatisticsConfiguration configuration = new MetricStreamStatisticsConfiguration();
            List<MetricStreamStatisticsConfiguration.IncludeMetric> includeMetrics = new ArrayList<>();
            entry.path("IncludeMetrics").forEach(m -> includeMetrics.add(
                    new MetricStreamStatisticsConfiguration.IncludeMetric(
                            m.path("Namespace").asText(null), m.path("MetricName").asText(null))));
            configuration.setIncludeMetrics(includeMetrics);
            List<String> additionalStatistics = new ArrayList<>();
            entry.path("AdditionalStatistics").forEach(s -> additionalStatistics.add(s.asText()));
            configuration.setAdditionalStatistics(additionalStatistics);
            configurations.add(configuration);
        }
        return configurations;
    }

    private List<MetricDatum> parseMetricDataJson(JsonNode node, String listPath) {
        List<MetricDatum> datums = new ArrayList<>();
        if (!node.isArray()) return datums;
        int index = 0;
        for (JsonNode item : node) {
            index++;
            JsonNode metricName = item.path("MetricName");
            if (!metricName.isTextual() || metricName.asText().isEmpty()) {
                throw CloudWatchMetricsException.missingParameter(listPath + ".member." + index + ".MetricName");
            }
            MetricDatum datum = new MetricDatum();
            datum.setMetricName(metricName.asText());
            datum.setValue(item.path("Value").asDouble(0));
            datum.setUnit(item.path("Unit").asText(null));
            JsonNode ts = item.path("Timestamp");
            if (!ts.isMissingNode()) {
                Instant parsed = parseInstantNode(ts);
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

    /**
     * Parse a JsonNode that may be a numeric epoch (long/double) or an ISO-8601 string.
     */
    private Instant parseInstantNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isNumber()) {
            BigDecimal seconds = node.decimalValue();
            return Instant.ofEpochSecond(seconds.longValue(),
                    seconds.remainder(BigDecimal.ONE).movePointRight(9).longValue());
        }
        return parseInstant(node.asText(null));
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
