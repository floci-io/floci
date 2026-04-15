package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            case "DescribeAlarms" -> handleDescribeAlarms(params, region);
            case "DeleteAlarms" -> handleDeleteAlarms(params, region);
            case "SetAlarmState" -> handleSetAlarmState(params, region);
            case "ListTagsForResource" -> handleListTagsForResource(params, region);
            case "TagResource" -> handleTagResource(params, region);
            case "UntagResource" -> handleUntagResource(params, region);
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

    private Response handleDescribeAlarms(MultivaluedMap<String, String> params, String region) {
        List<String> alarmNames = new ArrayList<>();
        for (int i = 1; ; i++) {
            String name = params.getFirst("AlarmNames.member." + i);
            if (name == null) break;
            alarmNames.add(name);
        }
        String prefix = params.getFirst("AlarmNamePrefix");

        List<MetricAlarm> alarms = metricsService.describeAlarms(alarmNames, prefix, region);

        var xml = new XmlBuilder().start("MetricAlarms");
        for (MetricAlarm a : alarms) {
            toAlarmXml(xml, a);
        }
        xml.end("MetricAlarms");
        return Response.ok(AwsQueryResponse.envelope("DescribeAlarms", null, xml.build())).build();
    }

    private Response handleDeleteAlarms(MultivaluedMap<String, String> params, String region) {
        List<String> alarmNames = new ArrayList<>();
        for (int i = 1; ; i++) {
            String name = params.getFirst("AlarmNames.member." + i);
            if (name == null) break;
            alarmNames.add(name);
        }
        metricsService.deleteAlarms(alarmNames, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteAlarms", null)).build();
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
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 1; ; i++) {
            String key = params.getFirst("Tags.member." + i + ".Key");
            if (key == null) break;
            tags.put(key, params.getFirst("Tags.member." + i + ".Value"));
        }
        metricsService.tagResource(arn, tags, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("TagResource", null)).build();
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
        return Response.ok(AwsQueryResponse.envelopeNoResult("UntagResource", null)).build();
    }

    // ──────────────────────────── Parsing Helpers ────────────────────────────

    private List<MetricDatum> parseMetricData(MultivaluedMap<String, String> params) {
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
        a.setDatapointsToAlarm(parseIntParam(params, "DatapointsToAlarm", a.getEvaluationPeriods()));
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
                .elem("AlarmDescription", a.getAlarmDescription())
                .elem("AlarmConfigurationUpdatedTimestamp", Instant.ofEpochSecond(a.getAlarmConfigurationUpdatedTimestamp()).toString())
                .elem("ActionsEnabled", String.valueOf(a.isActionsEnabled()))
                .elem("StateValue", a.getStateValue())
                .elem("StateReason", a.getStateReason())
                .elem("StateReasonData", a.getStateReasonData())
                .elem("StateUpdatedTimestamp", Instant.ofEpochSecond(a.getStateUpdatedTimestamp()).toString())
                .elem("MetricName", a.getMetricName())
                .elem("Namespace", a.getNamespace())
                .elem("Statistic", a.getStatistic())
                .elem("Period", String.valueOf(a.getPeriod()))
                .elem("Unit", a.getUnit())
                .elem("EvaluationPeriods", String.valueOf(a.getEvaluationPeriods()))
                .elem("DatapointsToAlarm", String.valueOf(a.getDatapointsToAlarm()))
                .elem("Threshold", String.valueOf(a.getThreshold()))
                .elem("ComparisonOperator", a.getComparisonOperator())
                .elem("TreatMissingData", a.getTreatMissingData());

        if (!a.getOkActions().isEmpty()) {
            xml.start("OKActions");
            a.getOkActions().forEach(act -> xml.elem("member", act));
            xml.end("OKActions");
        }
        if (!a.getAlarmActions().isEmpty()) {
            xml.start("AlarmActions");
            a.getAlarmActions().forEach(act -> xml.elem("member", act));
            xml.end("AlarmActions");
        }
        if (!a.getInsufficientDataActions().isEmpty()) {
            xml.start("InsufficientDataActions");
            a.getInsufficientDataActions().forEach(act -> xml.elem("member", act));
            xml.end("InsufficientDataActions");
        }

        if (!a.getDimensions().isEmpty()) {
            xml.start("Dimensions");
            for (Dimension d : a.getDimensions()) {
                xml.start("member").elem("Name", d.name()).elem("Value", d.value()).end("member");
            }
            xml.end("Dimensions");
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
