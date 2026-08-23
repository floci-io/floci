package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
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
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CloudWatchMetricsQueryHandler {

    private static final Logger LOG = Logger.getLogger(CloudWatchMetricsQueryHandler.class);

    private CloudWatchMetricsService metricsService;

    @Inject
    public CloudWatchMetricsQueryHandler(CloudWatchMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        String normalizedAction = action.substring(0, 1).toUpperCase() + action.substring(1);
        return switch (normalizedAction) {
            case "PutMetricData" -> handlePutMetricData(params, region);
            case "ListMetrics" -> handleListMetrics(params, region);
            case "GetMetricStatistics" -> handleGetMetricStatistics(params, region);
            case "GetMetricData" -> handleGetMetricData(params, region);
            case "PutMetricAlarm" -> handlePutMetricAlarm(params, region);
            case "PutCompositeAlarm" -> handlePutCompositeAlarm(params, region);
            case "DescribeAlarms" -> handleDescribeAlarms(params, region);
            case "DeleteAlarms" -> handleDeleteAlarms(params, region);
            case "SetAlarmState" -> handleSetAlarmState(params, region);
            case "PutDashboard" -> handlePutDashboard(params, region);
            case "GetDashboard" -> handleGetDashboard(params, region);
            case "ListDashboards" -> handleListDashboards(params, region);
            case "DeleteDashboards" -> handleDeleteDashboards(params, region);
            case "PutInsightRule" -> handlePutInsightRule(params, region);
            case "DescribeInsightRules" -> handleDescribeInsightRules(params, region);
            case "DeleteInsightRules" -> handleDeleteInsightRules(params, region);
            case "EnableInsightRules" -> handleSetInsightRulesState(params, region, true);
            case "DisableInsightRules" -> handleSetInsightRulesState(params, region, false);
            case "ListTagsForResource" -> handleListTagsForResource(params, region);
            case "TagResource" -> handleTagResource(params, region);
            case "UntagResource" -> handleUntagResource(params, region);
            case "PutMetricStream" -> handlePutMetricStream(params, region);
            case "GetMetricStream" -> handleGetMetricStream(params, region);
            case "DeleteMetricStream" -> handleDeleteMetricStream(params, region);
            case "ListMetricStreams" -> handleListMetricStreams(params, region);
            case "StartMetricStreams" -> handleSetMetricStreamsState(params, region, true);
            case "StopMetricStreams" -> handleSetMetricStreamsState(params, region, false);
            default -> AwsQueryResponse.error("UnsupportedOperation",
                    "Operation " + action + " is not supported by CloudWatch Query.", AwsNamespaces.CW, 400);
        };
    }

    private Response handlePutMetricData(MultivaluedMap<String, String> params, String region) {
        String namespace = params.getFirst("Namespace");
        List<MetricDatum> datums = parseMetricData(params);
        metricsService.putMetricData(namespace, datums, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("PutMetricData", null)).build();
    }

    private Response handleListMetrics(MultivaluedMap<String, String> params, String region) {
        String namespace = params.getFirst("Namespace");
        String metricName = params.getFirst("MetricName");
        List<Dimension> dimensions = parseDimensionFilters(params);

        List<CloudWatchMetricsService.MetricIdentity> metrics =
                metricsService.listMetrics(namespace, metricName, dimensions, region);

        var xml = new XmlBuilder().start("Metrics");
        for (var m : metrics) {
            var member = xml.start("member")
                    .elem("Namespace", m.namespace())
                    .elem("MetricName", m.metricName())
                    .start("Dimensions");
            for (Dimension d : m.dimensions()) {
                xml.start("member")
                        .elem("Name", d.name())
                        .elem("Value", d.value())
                        .end("member");
            }
            xml.end("Dimensions").end("member");
        }
        xml.end("Metrics");
        return Response.ok(AwsQueryResponse.envelope("ListMetrics", null, xml.build())).build();
    }

    private Response handleGetMetricStatistics(MultivaluedMap<String, String> params, String region) {
        String namespace = params.getFirst("Namespace");
        String metricName = params.getFirst("MetricName");
        List<Dimension> dimensions = parseDimensionFilters(params);
        int period = parseIntParam(params, "Period", 60);
        String unit = params.getFirst("Unit");

        Instant startTime = parseInstant(params.getFirst("StartTime"));
        Instant endTime = parseInstant(params.getFirst("EndTime"));

        List<String> statistics = new ArrayList<>();
        for (int i = 1; ; i++) {
            String stat = params.getFirst("Statistics.member." + i);
            if (stat == null) {
                break;
            }
            statistics.add(stat);
        }

        List<CloudWatchMetricsService.Datapoint> datapoints =
                metricsService.getMetricStatistics(namespace, metricName, dimensions,
                        startTime, endTime, period, statistics, unit, region);

        DateTimeFormatter fmt = DateTimeFormatter.ISO_INSTANT;
        var xml = new XmlBuilder()
                .elem("Label", metricName)
                .start("Datapoints");
        for (var dp : datapoints) {
            xml.start("member").elem("Timestamp", fmt.format(dp.timestamp()));
            if (statistics.contains("Average")) {
                xml.elem("Average", String.valueOf(dp.average()));
            }
            if (statistics.contains("Sum")) {
                xml.elem("Sum", String.valueOf(dp.sum()));
            }
            if (statistics.contains("Minimum")) {
                xml.elem("Minimum", String.valueOf(dp.minimum()));
            }
            if (statistics.contains("Maximum")) {
                xml.elem("Maximum", String.valueOf(dp.maximum()));
            }
            if (statistics.contains("SampleCount")) {
                xml.elem("SampleCount", String.valueOf(dp.sampleCount()));
            }
            xml.elem("Unit", dp.unit()).end("member");
        }
        xml.end("Datapoints");
        return Response.ok(AwsQueryResponse.envelope("GetMetricStatistics", null, xml.build())).build();
    }

    private Response handleGetMetricData(MultivaluedMap<String, String> params, String region) {
        Instant startTime = parseInstant(params.getFirst("StartTime"));
        Instant endTime = parseInstant(params.getFirst("EndTime"));

        List<CloudWatchMetricsService.MetricDataQuery> queries = parseMetricDataQueries(params);

        List<CloudWatchMetricsService.MetricDataResult> results =
                metricsService.getMetricData(queries, startTime, endTime, region);

        DateTimeFormatter fmt = DateTimeFormatter.ISO_INSTANT;
        var xml = new XmlBuilder().start("MetricDataResults");
        for (var r : results) {
            xml.start("member")
                    .elem("Id", r.id())
                    .elem("Label", r.label())
                    .elem("StatusCode", r.statusCode());

            xml.start("Timestamps");
            for (Instant ts : r.timestamps()) {
                xml.elem("member", fmt.format(ts));
            }
            xml.end("Timestamps");

            xml.start("Values");
            for (Double v : r.values()) {
                xml.elem("member", String.valueOf(v));
            }
            xml.end("Values");

            xml.end("member");
        }
        xml.end("MetricDataResults");
        return Response.ok(AwsQueryResponse.envelope("GetMetricData", null, xml.build())).build();
    }

    private List<CloudWatchMetricsService.MetricDataQuery> parseMetricDataQueries(
            MultivaluedMap<String, String> params) {
        List<CloudWatchMetricsService.MetricDataQuery> queries = new ArrayList<>();
        for (int i = 1; ; i++) {
            String prefix = "MetricDataQueries.member." + i;
            String id = params.getFirst(prefix + ".Id");
            if (id == null) break;

            String expression = params.getFirst(prefix + ".Expression");
            String label = params.getFirst(prefix + ".Label");
            boolean returnData = !"false".equals(params.getFirst(prefix + ".ReturnData"));

            CloudWatchMetricsService.MetricStat metricStat = null;
            String msNamespace = params.getFirst(prefix + ".MetricStat.Metric.Namespace");
            if (msNamespace != null) {
                String msMetricName = params.getFirst(prefix + ".MetricStat.Metric.MetricName");
                int msPeriod = parseIntParam(params, prefix + ".MetricStat.Period", 60);
                String msStat = params.getFirst(prefix + ".MetricStat.Stat");
                String msUnit = params.getFirst(prefix + ".MetricStat.Unit");

                List<Dimension> dims = new ArrayList<>();
                for (int j = 1; ; j++) {
                    String dimName = params.getFirst(
                            prefix + ".MetricStat.Metric.Dimensions.member." + j + ".Name");
                    if (dimName == null) break;
                    String dimValue = params.getFirst(
                            prefix + ".MetricStat.Metric.Dimensions.member." + j + ".Value");
                    dims.add(new Dimension(dimName, dimValue));
                }

                metricStat = new CloudWatchMetricsService.MetricStat(
                        msNamespace, msMetricName, dims, msPeriod, msStat, msUnit);
            }

            queries.add(new CloudWatchMetricsService.MetricDataQuery(
                    id, metricStat, expression, label, returnData));
        }
        return queries;
    }

    private Response handlePutMetricAlarm(MultivaluedMap<String, String> params, String region) {
        MetricAlarm alarm = parseAlarm(params);
        metricsService.putMetricAlarm(alarm, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("PutMetricAlarm", null)).build();
    }

    private Response handlePutCompositeAlarm(MultivaluedMap<String, String> params, String region) {
        CompositeAlarm alarm = new CompositeAlarm();
        alarm.setAlarmName(params.getFirst("AlarmName"));
        alarm.setAlarmRule(params.getFirst("AlarmRule"));
        alarm.setAlarmDescription(params.getFirst("AlarmDescription"));
        alarm.setActionsEnabled(!"false".equals(params.getFirst("ActionsEnabled")));
        alarm.setOkActions(parseMemberList(params, "OKActions"));
        alarm.setAlarmActions(parseMemberList(params, "AlarmActions"));
        alarm.setInsufficientDataActions(parseMemberList(params, "InsufficientDataActions"));
        alarm.setActionsSuppressor(params.getFirst("ActionsSuppressor"));
        alarm.setActionsSuppressorWaitPeriod(parseIntegerParam(params, "ActionsSuppressorWaitPeriod"));
        alarm.setActionsSuppressorExtensionPeriod(parseIntegerParam(params, "ActionsSuppressorExtensionPeriod"));
        alarm.setTags(parseTags(params));
        metricsService.putCompositeAlarm(alarm, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("PutCompositeAlarm", null)).build();
    }

    private Response handleDescribeAlarms(MultivaluedMap<String, String> params, String region) {
        List<String> alarmNames = parseMemberList(params, "AlarmNames");
        String prefix = params.getFirst("AlarmNamePrefix");
        List<String> alarmTypes = parseMemberList(params, "AlarmTypes");
        boolean includeMetricAlarms = alarmTypes.isEmpty() || alarmTypes.contains("MetricAlarm");
        boolean includeCompositeAlarms = alarmTypes.contains("CompositeAlarm");

        var xml = new XmlBuilder().start("CompositeAlarms");
        if (includeCompositeAlarms) {
            for (CompositeAlarm a : metricsService.describeCompositeAlarms(alarmNames, prefix, region)) {
                toCompositeAlarmXml(xml, a);
            }
        }
        xml.end("CompositeAlarms").start("MetricAlarms");
        if (includeMetricAlarms) {
            for (MetricAlarm a : metricsService.describeAlarms(alarmNames, prefix, region)) {
                toAlarmXml(xml, a);
            }
        }
        xml.end("MetricAlarms");
        return Response.ok(AwsQueryResponse.envelope("DescribeAlarms", null, xml.build())).build();
    }

    private Response handleDeleteAlarms(MultivaluedMap<String, String> params, String region) {
        metricsService.deleteAlarms(parseMemberList(params, "AlarmNames"), region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteAlarms", null)).build();
    }

    // ──────────────────────────── Dashboards ────────────────────────────

    private Response handlePutDashboard(MultivaluedMap<String, String> params, String region) {
        metricsService.putDashboard(params.getFirst("DashboardName"), params.getFirst("DashboardBody"), region);
        String xml = new XmlBuilder()
                .start("DashboardValidationMessages")
                .end("DashboardValidationMessages")
                .build();
        return Response.ok(AwsQueryResponse.envelope("PutDashboard", null, xml)).build();
    }

    private Response handleGetDashboard(MultivaluedMap<String, String> params, String region) {
        Dashboard dashboard = metricsService.getDashboard(params.getFirst("DashboardName"), region);
        String xml = new XmlBuilder()
                .elem("DashboardArn", dashboard.getArn())
                .elem("DashboardBody", dashboard.getBody())
                .elem("DashboardName", dashboard.getName())
                .build();
        return Response.ok(AwsQueryResponse.envelope("GetDashboard", null, xml)).build();
    }

    private Response handleListDashboards(MultivaluedMap<String, String> params, String region) {
        XmlBuilder xml = new XmlBuilder().start("DashboardEntries");
        for (Dashboard dashboard : metricsService.listDashboards(params.getFirst("DashboardNamePrefix"), region)) {
            xml.start("member")
                    .elem("DashboardName", dashboard.getName())
                    .elem("DashboardArn", dashboard.getArn())
                    .elem("LastModified", formatEpochSeconds(dashboard.getLastModified()))
                    .elem("Size", dashboard.size())
                    .end("member");
        }
        xml.end("DashboardEntries");
        return Response.ok(AwsQueryResponse.envelope("ListDashboards", null, xml.build())).build();
    }

    private Response handleDeleteDashboards(MultivaluedMap<String, String> params, String region) {
        metricsService.deleteDashboards(parseMemberList(params, "DashboardNames"), region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("DeleteDashboards", null)).build();
    }

    // ──────────────────────────── Contributor Insight Rules ────────────────────────────

    private Response handlePutInsightRule(MultivaluedMap<String, String> params, String region) {
        InsightRule rule = new InsightRule();
        rule.setName(params.getFirst("RuleName"));
        rule.setDefinition(params.getFirst("RuleDefinition"));
        rule.setState(params.getFirst("RuleState"));
        rule.setApplyOnTransformedLogs(Boolean.parseBoolean(params.getFirst("ApplyOnTransformedLogs")));
        rule.setTags(parseTags(params));
        metricsService.putInsightRule(rule, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("PutInsightRule", null)).build();
    }

    private Response handleDescribeInsightRules(MultivaluedMap<String, String> params, String region) {
        XmlBuilder xml = new XmlBuilder().start("InsightRules");
        for (InsightRule rule : metricsService.describeInsightRules(region)) {
            xml.start("member")
                    .elem("Name", rule.getName())
                    .elem("State", rule.getState())
                    .elem("Schema", rule.getSchema())
                    .elem("Definition", rule.getDefinition())
                    .elem("ManagedRule", rule.isManagedRule())
                    .elem("ApplyOnTransformedLogs", rule.isApplyOnTransformedLogs())
                    .end("member");
        }
        xml.end("InsightRules");
        return Response.ok(AwsQueryResponse.envelope("DescribeInsightRules", null, xml.build())).build();
    }

    private Response handleDeleteInsightRules(MultivaluedMap<String, String> params, String region) {
        metricsService.deleteInsightRules(parseMemberList(params, "RuleNames"), region);
        return Response.ok(AwsQueryResponse.envelope("DeleteInsightRules", null, emptyFailuresXml())).build();
    }

    private Response handleSetInsightRulesState(MultivaluedMap<String, String> params, String region, boolean enabled) {
        metricsService.setInsightRulesState(parseMemberList(params, "RuleNames"), enabled, region);
        String action = enabled ? "EnableInsightRules" : "DisableInsightRules";
        return Response.ok(AwsQueryResponse.envelope(action, null, emptyFailuresXml())).build();
    }

    private String emptyFailuresXml() {
        return new XmlBuilder().start("Failures").end("Failures").build();
    }

    private Response handleSetAlarmState(MultivaluedMap<String, String> params, String region) {
        String name = params.getFirst("AlarmName");
        String state = params.getFirst("StateValue");
        String reason = params.getFirst("StateReason");
        String reasonData = params.getFirst("StateReasonData");
        metricsService.setAlarmState(name, state, reason, reasonData, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("SetAlarmState", null)).build();
    }

    private Response handleListTagsForResource(MultivaluedMap<String, String> params, String region) {
        String arn = params.getFirst("ResourceARN");
        Map<String, String> tags = metricsService.listTagsForResource(arn, region);
        XmlBuilder xml = new XmlBuilder().start("Tags");
        tags.forEach((k, v) -> xml.start("member").elem("Key", k).elem("Value", v).end("member"));
        xml.end("Tags");
        return Response.ok(AwsQueryResponse.envelope("ListTagsForResource", null, xml.build())).build();
    }

    private Response handleTagResource(MultivaluedMap<String, String> params, String region) {
        String arn = params.getFirst("ResourceARN");
        metricsService.tagResource(arn, parseTags(params), region);
        // CloudWatch's TagResourceOutput is an empty structure, not an absent one - the API
        // model still declares a `resultWrapper: "TagResourceResult"`. The AWS Go SDK v2
        // query-protocol unmarshaler requires that wrapper element even when it has no fields
        // (lex00/floci#88); envelopeNoResult omits it entirely, which only matches operations
        // whose API model has no output shape at all (e.g. IAM's TagUser/UntagUser).
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("TagResource", null)).build();
    }

    private Response handleUntagResource(MultivaluedMap<String, String> params, String region) {
        String arn = params.getFirst("ResourceARN");
        List<String> keys = new ArrayList<>();
        for (int i = 1; ; i++) {
            String key = params.getFirst("TagKeys.member." + i);
            if (key == null) break;
            keys.add(key);
        }
        metricsService.untagResource(arn, keys, region);
        // Same empty-but-declared UntagResourceOutput shape as TagResource above (lex00/floci#88).
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("UntagResource", null)).build();
    }

    // ──────────────────────────── Metric Streams ────────────────────────────

    private Response handlePutMetricStream(MultivaluedMap<String, String> params, String region) {
        MetricStream stream = new MetricStream();
        stream.setName(params.getFirst("Name"));
        stream.setFirehoseArn(params.getFirst("FirehoseArn"));
        stream.setRoleArn(params.getFirst("RoleArn"));
        stream.setOutputFormat(params.getFirst("OutputFormat"));
        stream.setIncludeLinkedAccountsMetrics(
                Boolean.parseBoolean(params.getFirst("IncludeLinkedAccountsMetrics")));
        stream.setIncludeFilters(parseStreamFilters(params, "IncludeFilters"));
        stream.setExcludeFilters(parseStreamFilters(params, "ExcludeFilters"));
        stream.setStatisticsConfigurations(parseStatisticsConfigurations(params));
        stream.setTags(parseTags(params));

        MetricStream stored = metricsService.putMetricStream(stream, region);
        String xml = new XmlBuilder().elem("Arn", stored.getArn()).build();
        return Response.ok(AwsQueryResponse.envelope("PutMetricStream", null, xml)).build();
    }

    private Response handleGetMetricStream(MultivaluedMap<String, String> params, String region) {
        MetricStream stream = metricsService.getMetricStream(params.getFirst("Name"), region);
        XmlBuilder xml = new XmlBuilder()
                .elem("Arn", stream.getArn())
                .elem("Name", stream.getName())
                .elem("FirehoseArn", stream.getFirehoseArn())
                .elem("RoleArn", stream.getRoleArn())
                .elem("State", stream.getState())
                .elem("OutputFormat", stream.getOutputFormat())
                .elem("CreationDate", formatEpochSeconds(stream.getCreationDate()))
                .elem("LastUpdateDate", formatEpochSeconds(stream.getLastUpdateDate()))
                .elem("IncludeLinkedAccountsMetrics", stream.isIncludeLinkedAccountsMetrics());
        appendStreamFilters(xml, "IncludeFilters", stream.getIncludeFilters());
        appendStreamFilters(xml, "ExcludeFilters", stream.getExcludeFilters());
        appendStatisticsConfigurations(xml, stream.getStatisticsConfigurations());
        return Response.ok(AwsQueryResponse.envelope("GetMetricStream", null, xml.build())).build();
    }

    private Response handleDeleteMetricStream(MultivaluedMap<String, String> params, String region) {
        metricsService.deleteMetricStream(params.getFirst("Name"), region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("DeleteMetricStream", null)).build();
    }

    private Response handleListMetricStreams(MultivaluedMap<String, String> params, String region) {
        List<MetricStream> streams = metricsService.listMetricStreams(region);
        XmlBuilder xml = new XmlBuilder().start("Entries");
        for (MetricStream stream : streams) {
            xml.start("member")
                    .elem("Arn", stream.getArn())
                    .elem("Name", stream.getName())
                    .elem("FirehoseArn", stream.getFirehoseArn())
                    .elem("State", stream.getState())
                    .elem("OutputFormat", stream.getOutputFormat())
                    .elem("CreationDate", formatEpochSeconds(stream.getCreationDate()))
                    .elem("LastUpdateDate", formatEpochSeconds(stream.getLastUpdateDate()))
                    .end("member");
        }
        xml.end("Entries");
        return Response.ok(AwsQueryResponse.envelope("ListMetricStreams", null, xml.build())).build();
    }

    private Response handleSetMetricStreamsState(MultivaluedMap<String, String> params, String region, boolean start) {
        List<String> names = new ArrayList<>();
        for (int i = 1; ; i++) {
            String name = params.getFirst("Names.member." + i);
            if (name == null) {
                break;
            }
            names.add(name);
        }
        metricsService.setMetricStreamsState(names, start, region);
        String action = start ? "StartMetricStreams" : "StopMetricStreams";
        return Response.ok(AwsQueryResponse.envelopeEmptyResult(action, null)).build();
    }

    private void appendStreamFilters(XmlBuilder xml, String element, List<MetricStreamFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return;
        }
        xml.start(element);
        for (MetricStreamFilter filter : filters) {
            xml.start("member").elem("Namespace", filter.getNamespace());
            if (!filter.getMetricNames().isEmpty()) {
                xml.start("MetricNames");
                for (String metricName : filter.getMetricNames()) {
                    xml.elem("member", metricName);
                }
                xml.end("MetricNames");
            }
            xml.end("member");
        }
        xml.end(element);
    }

    private void appendStatisticsConfigurations(XmlBuilder xml,
                                                List<MetricStreamStatisticsConfiguration> configurations) {
        if (configurations == null || configurations.isEmpty()) {
            return;
        }
        xml.start("StatisticsConfigurations");
        for (MetricStreamStatisticsConfiguration configuration : configurations) {
            xml.start("member").start("IncludeMetrics");
            for (var metric : configuration.getIncludeMetrics()) {
                xml.start("member")
                        .elem("Namespace", metric.namespace())
                        .elem("MetricName", metric.metricName())
                        .end("member");
            }
            xml.end("IncludeMetrics").start("AdditionalStatistics");
            for (String stat : configuration.getAdditionalStatistics()) {
                xml.elem("member", stat);
            }
            xml.end("AdditionalStatistics").end("member");
        }
        xml.end("StatisticsConfigurations");
    }

    private List<MetricStreamFilter> parseStreamFilters(MultivaluedMap<String, String> params, String prefix) {
        List<MetricStreamFilter> filters = new ArrayList<>();
        for (int i = 1; ; i++) {
            String base = prefix + ".member." + i;
            String namespace = params.getFirst(base + ".Namespace");
            if (namespace == null) {
                break;
            }
            List<String> metricNames = new ArrayList<>();
            for (int j = 1; ; j++) {
                String metricName = params.getFirst(base + ".MetricNames.member." + j);
                if (metricName == null) {
                    break;
                }
                metricNames.add(metricName);
            }
            filters.add(new MetricStreamFilter(namespace, metricNames));
        }
        return filters;
    }

    private List<MetricStreamStatisticsConfiguration> parseStatisticsConfigurations(
            MultivaluedMap<String, String> params) {
        List<MetricStreamStatisticsConfiguration> configurations = new ArrayList<>();
        for (int i = 1; ; i++) {
            String base = "StatisticsConfigurations.member." + i;
            if (params.getFirst(base + ".IncludeMetrics.member.1.Namespace") == null
                    && params.getFirst(base + ".AdditionalStatistics.member.1") == null) {
                break;
            }
            MetricStreamStatisticsConfiguration configuration = new MetricStreamStatisticsConfiguration();
            List<MetricStreamStatisticsConfiguration.IncludeMetric> includeMetrics = new ArrayList<>();
            for (int j = 1; ; j++) {
                String namespace = params.getFirst(base + ".IncludeMetrics.member." + j + ".Namespace");
                if (namespace == null) {
                    break;
                }
                includeMetrics.add(new MetricStreamStatisticsConfiguration.IncludeMetric(namespace,
                        params.getFirst(base + ".IncludeMetrics.member." + j + ".MetricName")));
            }
            configuration.setIncludeMetrics(includeMetrics);
            List<String> additionalStatistics = new ArrayList<>();
            for (int j = 1; ; j++) {
                String stat = params.getFirst(base + ".AdditionalStatistics.member." + j);
                if (stat == null) {
                    break;
                }
                additionalStatistics.add(stat);
            }
            configuration.setAdditionalStatistics(additionalStatistics);
            configurations.add(configuration);
        }
        return configurations;
    }

    private Map<String, String> parseTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 1; ; i++) {
            String key = params.getFirst("Tags.member." + i + ".Key");
            if (key == null) {
                break;
            }
            tags.put(key, params.getFirst("Tags.member." + i + ".Value"));
        }
        return tags;
    }

    private List<String> parseMemberList(MultivaluedMap<String, String> params, String prefix) {
        List<String> values = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = params.getFirst(prefix + ".member." + i);
            if (value == null) {
                break;
            }
            values.add(value);
        }
        return values;
    }

    private static String formatEpochSeconds(long epochSeconds) {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(epochSeconds));
    }

    // ──────────────────────────── Parsing Helpers ────────────────────────────

    private List<MetricDatum> parseMetricData(MultivaluedMap<String, String> params) {
        LOG.infov("Query PutMetricData params: {0}", params);
        List<MetricDatum> datums = new ArrayList<>();
        for (int i = 1; ; i++) {
            String metricName = params.getFirst("MetricData.member." + i + ".MetricName");
            if (metricName == null) {
                break;
            }
            MetricDatum datum = new MetricDatum();
            datum.setMetricName(metricName);
            datum.setUnit(params.getFirst("MetricData.member." + i + ".Unit"));

            String valueStr = params.getFirst("MetricData.member." + i + ".Value");
            if (valueStr != null) {
                datum.setValue(parseDouble(valueStr, 0));
            }

            String tsStr = params.getFirst("MetricData.member." + i + ".Timestamp");
            if (tsStr != null) {
                Instant ts = parseInstant(tsStr);
                datum.setTimestamp(ts != null ? ts.getEpochSecond() : 0);
            }

            // StatisticValues
            String sc = params.getFirst("MetricData.member." + i + ".StatisticValues.SampleCount");
            if (sc != null) {
                datum.setSampleCount(parseDouble(sc, 0));
                datum.setSum(parseDouble(params.getFirst("MetricData.member." + i + ".StatisticValues.Sum"), 0));
                datum.setMinimum(parseDouble(params.getFirst("MetricData.member." + i + ".StatisticValues.Minimum"), 0));
                datum.setMaximum(parseDouble(params.getFirst("MetricData.member." + i + ".StatisticValues.Maximum"), 0));
            }

            // Dimensions
            List<Dimension> dims = new ArrayList<>();
            for (int j = 1; ; j++) {
                String dimName = params.getFirst("MetricData.member." + i + ".Dimensions.member." + j + ".Name");
                String dimValue = params.getFirst("MetricData.member." + i + ".Dimensions.member." + j + ".Value");
                if (dimName == null) {
                    break;
                }
                dims.add(new Dimension(dimName, dimValue));
            }
            datum.setDimensions(dims);

            datums.add(datum);
        }
        return datums;
    }

    private List<Dimension> parseDimensionFilters(MultivaluedMap<String, String> params) {
        List<Dimension> dims = new ArrayList<>();
        for (int i = 1; ; i++) {
            String name = params.getFirst("Dimensions.member." + i + ".Name");
            String value = params.getFirst("Dimensions.member." + i + ".Value");
            if (name == null) {
                break;
            }
            dims.add(new Dimension(name, value));
        }
        return dims;
    }

    private MetricAlarm parseAlarm(MultivaluedMap<String, String> params) {
        MetricAlarm a = new MetricAlarm();
        a.setAlarmName(params.getFirst("AlarmName"));
        a.setAlarmDescription(params.getFirst("AlarmDescription"));
        a.setActionsEnabled(Boolean.parseBoolean(params.getFirst("ActionsEnabled")));
        a.setMetricName(params.getFirst("MetricName"));
        a.setNamespace(params.getFirst("Namespace"));
        a.setStatistic(params.getFirst("Statistic"));
        a.setPeriod(parseIntParam(params, "Period", 60));
        a.setUnit(params.getFirst("Unit"));
        a.setEvaluationPeriods(parseIntParam(params, "EvaluationPeriods", 1));
        a.setDatapointsToAlarm(parseIntegerParam(params, "DatapointsToAlarm"));
        a.setThreshold(parseDouble(params.getFirst("Threshold"), 0));
        a.setComparisonOperator(params.getFirst("ComparisonOperator"));
        a.setTreatMissingData(params.getFirst("TreatMissingData"));

        // Dimensions
        List<Dimension> dims = new ArrayList<>();
        for (int i = 1; ; i++) {
            String name = params.getFirst("Dimensions.member." + i + ".Name");
            String value = params.getFirst("Dimensions.member." + i + ".Value");
            if (name == null) break;
            dims.add(new Dimension(name, value));
        }
        a.setDimensions(dims);

        // Actions
        for (int i = 1; ; i++) {
            String act = params.getFirst("OKActions.member." + i);
            if (act == null) break;
            a.getOkActions().add(act);
        }
        for (int i = 1; ; i++) {
            String act = params.getFirst("AlarmActions.member." + i);
            if (act == null) break;
            a.getAlarmActions().add(act);
        }
        for (int i = 1; ; i++) {
            String act = params.getFirst("InsufficientDataActions.member." + i);
            if (act == null) break;
            a.getInsufficientDataActions().add(act);
        }

        // Tags
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 1; ; i++) {
            String key = params.getFirst("Tags.member." + i + ".Key");
            if (key == null) break;
            tags.put(key, params.getFirst("Tags.member." + i + ".Value"));
        }
        a.setTags(tags);

        return a;
    }

    private void toAlarmXml(XmlBuilder xml, MetricAlarm a) {
        xml.start("member")
                .elem("AlarmName", a.getAlarmName())
                .elem("AlarmArn", a.getAlarmArn())
                .elem("AlarmDescription", a.getAlarmDescription());
        xml.start("AlarmActions");
        a.getAlarmActions().forEach(act -> xml.elem("member", act));
        xml.end("AlarmActions")
                .elem("AlarmConfigurationUpdatedTimestamp", Instant.ofEpochSecond(a.getAlarmConfigurationUpdatedTimestamp()).toString())
                .elem("ActionsEnabled", String.valueOf(a.isActionsEnabled()));

        xml.start("OKActions");
        a.getOkActions().forEach(act -> xml.elem("member", act));
        xml.end("OKActions");
        xml.start("InsufficientDataActions");
        a.getInsufficientDataActions().forEach(act -> xml.elem("member", act));
        xml.end("InsufficientDataActions");
        xml.start("Dimensions");
        for (Dimension d : a.getDimensions()) {
            xml.start("member").elem("Name", d.name()).elem("Value", d.value()).end("member");
        }
        xml.end("Dimensions");

        xml.elem("StateValue", a.getStateValue())
                .elem("StateReason", a.getStateReason())
                .elem("StateReasonData", a.getStateReasonData())
                .elem("StateUpdatedTimestamp", Instant.ofEpochSecond(a.getStateUpdatedTimestamp()).toString())
                .elem("MetricName", a.getMetricName())
                .elem("Namespace", a.getNamespace())
                .elem("Statistic", a.getStatistic())
                .elem("Period", String.valueOf(a.getPeriod()))
                .elem("Unit", a.getUnit())
                .elem("EvaluationPeriods", String.valueOf(a.getEvaluationPeriods()))
                .elem("DatapointsToAlarm", a.getDatapointsToAlarm() == null ? null : String.valueOf(a.getDatapointsToAlarm()))
                .elem("Threshold", String.valueOf(a.getThreshold()))
                .elem("ComparisonOperator", a.getComparisonOperator())
                .elem("TreatMissingData", a.getTreatMissingData());

        xml.end("member");
    }

    private void toCompositeAlarmXml(XmlBuilder xml, CompositeAlarm a) {
        xml.start("member")
                .elem("ActionsEnabled", a.isActionsEnabled());
        xml.start("AlarmActions");
        a.getAlarmActions().forEach(act -> xml.elem("member", act));
        xml.end("AlarmActions")
                .elem("AlarmArn", a.getAlarmArn())
                .elem("AlarmConfigurationUpdatedTimestamp",
                        formatEpochSeconds(a.getAlarmConfigurationUpdatedTimestamp()))
                .elem("AlarmDescription", a.getAlarmDescription())
                .elem("AlarmName", a.getAlarmName())
                .elem("AlarmRule", a.getAlarmRule());
        xml.start("InsufficientDataActions");
        a.getInsufficientDataActions().forEach(act -> xml.elem("member", act));
        xml.end("InsufficientDataActions").start("OKActions");
        a.getOkActions().forEach(act -> xml.elem("member", act));
        xml.end("OKActions")
                .elem("StateReason", a.getStateReason())
                .elem("StateReasonData", a.getStateReasonData())
                .elem("StateUpdatedTimestamp", formatEpochSeconds(a.getStateUpdatedTimestamp()))
                .elem("StateValue", a.getStateValue())
                .elem("StateTransitionedTimestamp", formatEpochSeconds(a.getStateTransitionedTimestamp()))
                .elem("ActionsSuppressor", a.getActionsSuppressor());
        if (a.getActionsSuppressorWaitPeriod() != null) {
            xml.elem("ActionsSuppressorWaitPeriod", a.getActionsSuppressorWaitPeriod());
        }
        if (a.getActionsSuppressorExtensionPeriod() != null) {
            xml.elem("ActionsSuppressorExtensionPeriod", a.getActionsSuppressorExtensionPeriod());
        }
        xml.end("member");
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
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

    private Integer parseIntegerParam(MultivaluedMap<String, String> params, String name) {
        String value = params.getFirst(name);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseIntParam(MultivaluedMap<String, String> params, String name, int defaultValue) {
        String value = params.getFirst(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private double parseDouble(String value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
