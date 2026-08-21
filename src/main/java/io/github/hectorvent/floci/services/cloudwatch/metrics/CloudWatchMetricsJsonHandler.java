package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dashboard;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.InsightRule;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricStream;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricStreamFilter;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricStreamStatisticsConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Handles CloudWatch Metrics requests via the JSON 1.0 protocol.
 * AWS SDK v3 for CloudWatch uses X-Amz-Target: GraniteServiceVersion20100801.*
 */
@ApplicationScoped
public class CloudWatchMetricsJsonHandler {

    private static final Logger LOG = Logger.getLogger(CloudWatchMetricsJsonHandler.class);
    private final CloudWatchMetricsService metricsService;
    private final ObjectMapper objectMapper;

    @Inject
    public CloudWatchMetricsJsonHandler(CloudWatchMetricsService metricsService, ObjectMapper objectMapper) {
        this.metricsService = metricsService;
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
            case "PutDashboard" -> handlePutDashboard(request, region);
            case "GetDashboard" -> handleGetDashboard(request, region);
            case "ListDashboards" -> handleListDashboards(request, region);
            case "DeleteDashboards" -> handleDeleteDashboards(request, region);
            case "PutInsightRule" -> handlePutInsightRule(request, region);
            case "DescribeInsightRules" -> handleDescribeInsightRules(request, region);
            case "DeleteInsightRules" -> handleDeleteInsightRules(request, region);
            case "EnableInsightRules" -> handleSetInsightRulesState(request, region, true);
            case "DisableInsightRules" -> handleSetInsightRulesState(request, region, false);
            case "ListTagsForResource" -> handleListTagsForResource(request, region);
            case "TagResource" -> handleTagResource(request, region);
            case "UntagResource" -> handleUntagResource(request, region);
            case "GetMetricData" -> handleGetMetricData(request, region);
            case "PutMetricStream" -> handlePutMetricStream(request, region);
            case "GetMetricStream" -> handleGetMetricStream(request, region);
            case "DeleteMetricStream" -> handleDeleteMetricStream(request, region);
            case "ListMetricStreams" -> handleListMetricStreams(request, region);
            case "StartMetricStreams" -> handleSetMetricStreamsState(request, region, true);
            case "StopMetricStreams" -> handleSetMetricStreamsState(request, region, false);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported by CloudWatch JSON."))
                    .build();
        };
    }

    private Response handlePutMetricData(JsonNode request, String region) {
        String namespace = request.path("Namespace").asText();
        LOG.infov("JSON PutMetricData raw: {0}", request);
        List<MetricDatum> datums = parseMetricDataJson(request.path("MetricData"));
        LOG.infov("JSON PutMetricData parsed {0} datums, sums={1}", datums.size(),
                datums.stream().map(d -> d.getMetricName() + "=" + d.getSum()).toList());
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
        Instant startTime = parseInstantNode(request.path("StartTime"));
        Instant endTime = parseInstantNode(request.path("EndTime"));

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

        metricsService.putMetricAlarm(alarm, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handlePutCompositeAlarm(JsonNode request, String region) {
        CompositeAlarm alarm = new CompositeAlarm();
        alarm.setAlarmName(request.path("AlarmName").asText());
        alarm.setAlarmRule(textOrNull(request, "AlarmRule"));
        alarm.setAlarmDescription(textOrNull(request, "AlarmDescription"));
        alarm.setActionsEnabled(request.path("ActionsEnabled").asBoolean(true));
        alarm.setAlarmActions(stringList(request.path("AlarmActions")));
        alarm.setOkActions(stringList(request.path("OKActions")));
        alarm.setInsufficientDataActions(stringList(request.path("InsufficientDataActions")));
        alarm.setActionsSuppressor(textOrNull(request, "ActionsSuppressor"));
        if (request.hasNonNull("ActionsSuppressorWaitPeriod")) {
            alarm.setActionsSuppressorWaitPeriod(request.path("ActionsSuppressorWaitPeriod").asInt());
        }
        if (request.hasNonNull("ActionsSuppressorExtensionPeriod")) {
            alarm.setActionsSuppressorExtensionPeriod(request.path("ActionsSuppressorExtensionPeriod").asInt());
        }
        alarm.setTags(parseTagList(request.path("Tags")));
        metricsService.putCompositeAlarm(alarm, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeAlarms(JsonNode request, String region) {
        List<String> alarmNames = stringList(request.path("AlarmNames"));
        String prefix = request.has("AlarmNamePrefix") ? request.path("AlarmNamePrefix").asText() : null;
        List<String> alarmTypes = stringList(request.path("AlarmTypes"));
        boolean includeMetricAlarms = alarmTypes.isEmpty() || alarmTypes.contains("MetricAlarm");
        boolean includeCompositeAlarms = alarmTypes.contains("CompositeAlarm");

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode compositeArr = response.putArray("CompositeAlarms");
        if (includeCompositeAlarms) {
            for (CompositeAlarm a : metricsService.describeCompositeAlarms(alarmNames, prefix, region)) {
                compositeArr.add(compositeAlarmNode(a));
            }
        }
        ArrayNode arr = response.putArray("MetricAlarms");
        List<MetricAlarm> alarms = includeMetricAlarms
                ? metricsService.describeAlarms(alarmNames, prefix, region)
                : List.of();
        for (MetricAlarm a : alarms) {
            ObjectNode node = arr.addObject();
            node.put("AlarmName", a.getAlarmName());
            if (a.getAlarmArn() != null) node.put("AlarmArn", a.getAlarmArn());
            if (a.getAlarmDescription() != null) node.put("AlarmDescription", a.getAlarmDescription());
            ArrayNode alarmActions = node.putArray("AlarmActions");
            a.getAlarmActions().forEach(alarmActions::add);
            ArrayNode okActions = node.putArray("OKActions");
            a.getOkActions().forEach(okActions::add);
            ArrayNode insufficientDataActions = node.putArray("InsufficientDataActions");
            a.getInsufficientDataActions().forEach(insufficientDataActions::add);
            if (a.getMetricName() != null) node.put("MetricName", a.getMetricName());
            if (a.getNamespace() != null) node.put("Namespace", a.getNamespace());
            if (a.getStatistic() != null) node.put("Statistic", a.getStatistic());
            ArrayNode dimensions = node.putArray("Dimensions");
            a.getDimensions().forEach(d -> {
                ObjectNode dimNode = dimensions.addObject();
                dimNode.put("Name", d.name());
                dimNode.put("Value", d.value());
            });
            node.put("Period", a.getPeriod());
            node.put("EvaluationPeriods", a.getEvaluationPeriods());
            node.put("DatapointsToAlarm", a.getDatapointsToAlarm());
            node.put("Threshold", a.getThreshold());
            if (a.getComparisonOperator() != null) node.put("ComparisonOperator", a.getComparisonOperator());
            node.put("ActionsEnabled", a.isActionsEnabled());
            if (a.getStateValue() != null) node.put("StateValue", a.getStateValue());
            if (a.getStateReason() != null) node.put("StateReason", a.getStateReason());
            if (a.getStateReasonData() != null) node.put("StateReasonData", a.getStateReasonData());
            node.put("StateUpdatedTimestamp", a.getStateUpdatedTimestamp());

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

    private Response handleListTagsForResource(JsonNode request, String region) {
        String arn = request.has("ResourceARN") ? request.path("ResourceARN").asText() : request.path("ResourceArn").asText();
        if (arn.isEmpty()) arn = request.path("resourceArn").asText();

        Map<String, String> tags = metricsService.listTagsForResource(arn, region);
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
        metricsService.tagResource(arn, tags, region);
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
        metricsService.untagResource(arn, keys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ──────────────────────────── Dashboards ────────────────────────────

    private Response handlePutDashboard(JsonNode request, String region) {
        metricsService.putDashboard(request.path("DashboardName").asText(null),
                request.path("DashboardBody").asText(null), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("DashboardValidationMessages");
        return Response.ok(response).build();
    }

    private Response handleGetDashboard(JsonNode request, String region) {
        Dashboard dashboard = metricsService.getDashboard(request.path("DashboardName").asText(null), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("DashboardArn", dashboard.getArn());
        response.put("DashboardBody", dashboard.getBody());
        response.put("DashboardName", dashboard.getName());
        return Response.ok(response).build();
    }

    private Response handleListDashboards(JsonNode request, String region) {
        String prefix = request.has("DashboardNamePrefix") ? request.path("DashboardNamePrefix").asText() : null;
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("DashboardEntries");
        for (Dashboard dashboard : metricsService.listDashboards(prefix, region)) {
            ObjectNode entry = entries.addObject();
            entry.put("DashboardName", dashboard.getName());
            entry.put("DashboardArn", dashboard.getArn());
            entry.put("LastModified", dashboard.getLastModified());
            entry.put("Size", dashboard.size());
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteDashboards(JsonNode request, String region) {
        metricsService.deleteDashboards(stringList(request.path("DashboardNames")), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ──────────────────────────── Contributor Insight Rules ────────────────────────────

    private Response handlePutInsightRule(JsonNode request, String region) {
        InsightRule rule = new InsightRule();
        rule.setName(request.path("RuleName").asText(null));
        rule.setDefinition(request.path("RuleDefinition").asText(null));
        rule.setState(textOrNull(request, "RuleState"));
        rule.setApplyOnTransformedLogs(request.path("ApplyOnTransformedLogs").asBoolean(false));
        rule.setTags(parseTagList(request.path("Tags")));
        metricsService.putInsightRule(rule, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeInsightRules(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode rules = response.putArray("InsightRules");
        for (InsightRule rule : metricsService.describeInsightRules(region)) {
            ObjectNode node = rules.addObject();
            node.put("Name", rule.getName());
            node.put("State", rule.getState());
            node.put("Schema", rule.getSchema());
            node.put("Definition", rule.getDefinition());
            node.put("ManagedRule", rule.isManagedRule());
            node.put("ApplyOnTransformedLogs", rule.isApplyOnTransformedLogs());
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteInsightRules(JsonNode request, String region) {
        metricsService.deleteInsightRules(stringList(request.path("RuleNames")), region);
        return Response.ok(emptyFailuresNode()).build();
    }

    private Response handleSetInsightRulesState(JsonNode request, String region, boolean enabled) {
        metricsService.setInsightRulesState(stringList(request.path("RuleNames")), enabled, region);
        return Response.ok(emptyFailuresNode()).build();
    }

    private ObjectNode emptyFailuresNode() {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Failures");
        return response;
    }

    private ObjectNode compositeAlarmNode(CompositeAlarm a) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ActionsEnabled", a.isActionsEnabled());
        ArrayNode alarmActions = node.putArray("AlarmActions");
        a.getAlarmActions().forEach(alarmActions::add);
        node.put("AlarmArn", a.getAlarmArn());
        node.put("AlarmConfigurationUpdatedTimestamp", a.getAlarmConfigurationUpdatedTimestamp());
        if (a.getAlarmDescription() != null) {
            node.put("AlarmDescription", a.getAlarmDescription());
        }
        node.put("AlarmName", a.getAlarmName());
        node.put("AlarmRule", a.getAlarmRule());
        ArrayNode insufficientDataActions = node.putArray("InsufficientDataActions");
        a.getInsufficientDataActions().forEach(insufficientDataActions::add);
        ArrayNode okActions = node.putArray("OKActions");
        a.getOkActions().forEach(okActions::add);
        node.put("StateReason", a.getStateReason());
        if (a.getStateReasonData() != null) {
            node.put("StateReasonData", a.getStateReasonData());
        }
        node.put("StateUpdatedTimestamp", a.getStateUpdatedTimestamp());
        node.put("StateValue", a.getStateValue());
        node.put("StateTransitionedTimestamp", a.getStateTransitionedTimestamp());
        if (a.getActionsSuppressor() != null) {
            node.put("ActionsSuppressor", a.getActionsSuppressor());
        }
        if (a.getActionsSuppressorWaitPeriod() != null) {
            node.put("ActionsSuppressorWaitPeriod", a.getActionsSuppressorWaitPeriod());
        }
        if (a.getActionsSuppressorExtensionPeriod() != null) {
            node.put("ActionsSuppressorExtensionPeriod", a.getActionsSuppressorExtensionPeriod());
        }
        return node;
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    private Map<String, String> parseTagList(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node.isArray()) {
            node.forEach(t -> tags.put(t.path("Key").asText(), t.path("Value").asText()));
        }
        return tags;
    }

    // ──────────────────────────── Metric Streams ────────────────────────────

    private Response handlePutMetricStream(JsonNode request, String region) {
        MetricStream stream = new MetricStream();
        stream.setName(request.path("Name").asText());
        stream.setFirehoseArn(textOrNull(request, "FirehoseArn"));
        stream.setRoleArn(textOrNull(request, "RoleArn"));
        stream.setOutputFormat(textOrNull(request, "OutputFormat"));
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

        MetricStream stored = metricsService.putMetricStream(stream, region);
        return Response.ok(objectMapper.createObjectNode().put("Arn", stored.getArn())).build();
    }

    private Response handleGetMetricStream(JsonNode request, String region) {
        MetricStream stream = metricsService.getMetricStream(request.path("Name").asText(), region);
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
        response.set("IncludeFilters", streamFiltersNode(stream.getIncludeFilters()));
        response.set("ExcludeFilters", streamFiltersNode(stream.getExcludeFilters()));
        response.set("StatisticsConfigurations",
                statisticsConfigurationsNode(stream.getStatisticsConfigurations()));
        return Response.ok(response).build();
    }

    private Response handleDeleteMetricStream(JsonNode request, String region) {
        metricsService.deleteMetricStream(request.path("Name").asText(), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListMetricStreams(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        for (MetricStream stream : metricsService.listMetricStreams(region)) {
            ObjectNode entry = entries.addObject();
            entry.put("Arn", stream.getArn());
            entry.put("Name", stream.getName());
            entry.put("FirehoseArn", stream.getFirehoseArn());
            entry.put("State", stream.getState());
            entry.put("OutputFormat", stream.getOutputFormat());
            entry.put("CreationDate", stream.getCreationDate());
            entry.put("LastUpdateDate", stream.getLastUpdateDate());
        }
        return Response.ok(response).build();
    }

    private Response handleSetMetricStreamsState(JsonNode request, String region, boolean start) {
        List<String> names = new ArrayList<>();
        JsonNode namesNode = request.path("Names");
        if (namesNode.isArray()) {
            namesNode.forEach(n -> names.add(n.asText()));
        }
        metricsService.setMetricStreamsState(names, start, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ArrayNode streamFiltersNode(List<MetricStreamFilter> filters) {
        ArrayNode array = objectMapper.createArrayNode();
        for (MetricStreamFilter filter : filters) {
            ObjectNode node = array.addObject();
            node.put("Namespace", filter.getNamespace());
            ArrayNode metricNames = node.putArray("MetricNames");
            filter.getMetricNames().forEach(metricNames::add);
        }
        return array;
    }

    private ArrayNode statisticsConfigurationsNode(List<MetricStreamStatisticsConfiguration> configurations) {
        ArrayNode array = objectMapper.createArrayNode();
        for (MetricStreamStatisticsConfiguration configuration : configurations) {
            ObjectNode node = array.addObject();
            ArrayNode includeMetrics = node.putArray("IncludeMetrics");
            for (var metric : configuration.getIncludeMetrics()) {
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
            filters.add(new MetricStreamFilter(entry.path("Namespace").asText(), metricNames));
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
                            m.path("Namespace").asText(), m.path("MetricName").asText())));
            configuration.setIncludeMetrics(includeMetrics);
            List<String> additionalStatistics = new ArrayList<>();
            entry.path("AdditionalStatistics").forEach(s -> additionalStatistics.add(s.asText()));
            configuration.setAdditionalStatistics(additionalStatistics);
            configurations.add(configuration);
        }
        return configurations;
    }

    private static String textOrNull(JsonNode request, String field) {
        return request.hasNonNull(field) ? request.path(field).asText() : null;
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
                    String unit = msNode.has("Unit") ? msNode.path("Unit").asText() : null;
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
        for (var r : results) {
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
            return Instant.ofEpochSecond(node.asLong());
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
