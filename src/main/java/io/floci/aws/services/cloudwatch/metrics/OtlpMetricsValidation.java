package io.floci.aws.services.cloudwatch.metrics;

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogram;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.metrics.v1.Summary;
import io.opentelemetry.proto.metrics.v1.SummaryDataPoint;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

final class OtlpMetricsValidation {

    private static final int MAX_DATA_POINTS = 1000;
    private static final int MAX_ATTRIBUTES = 150;
    private static final int MAX_METADATA_BYTES = 40_000;
    private static final int MAX_ATTRIBUTE_LENGTH = 1024;
    private static final int MAX_METRIC_NAME_LENGTH = 256;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Duration MAX_AGE = Duration.ofDays(14);
    private static final Duration MAX_FUTURE = Duration.ofHours(2);

    record Result(ExportMetricsServiceRequest request, int rejectedDataPoints, List<String> errors) {
        boolean hasErrors() {
            return !errors.isEmpty();
        }

        String errorMessage() {
            return String.join("\n", errors);
        }
    }

    private record PointContext(ResourceMetrics resource, ScopeMetrics scope, Metric metric,
                                String path, long oldestNanos, long newestNanos) {}

    Result validate(ExportMetricsServiceRequest request) {
        int count = request.getResourceMetricsList().stream().mapToInt(this::dataPointCount).sum();
        if (count > MAX_DATA_POINTS) {
            return new Result(null, count, List.of("Maximum number of datapoints per request exceeded: 1000."));
        }
        Instant now = Instant.now();
        long oldest = epochNanos(now.minus(MAX_AGE));
        long newest = epochNanos(now.plus(MAX_FUTURE));
        int rejected = 0;
        List<String> errors = new ArrayList<>();
        ExportMetricsServiceRequest.Builder accepted = request.toBuilder().clearResourceMetrics();
        int resourceIndex = 0;
        for (ResourceMetrics resource : request.getResourceMetricsList()) {
            String resourcePath = "ResourceMetrics." + ++resourceIndex;
            int resourcePoints = dataPointCount(resource);
            boolean resourceInvalid = resourcePoints > 0
                    && hasOversizedStringAttribute(resource.getResource().getAttributesList());
            if (resourceInvalid) {
                errors.add(sharedAttributeError(resourcePath, resourcePoints));
            }
            ResourceMetrics.Builder acceptedResource = resource.toBuilder().clearScopeMetrics();
            int scopeIndex = 0;
            for (ScopeMetrics scope : resource.getScopeMetricsList()) {
                String scopePath = resourcePath + ".ScopeMetrics." + ++scopeIndex;
                int scopePoints = dataPointCount(scope);
                boolean scopeInvalid = scopePoints > 0
                        && hasOversizedStringAttribute(scope.getScope().getAttributesList());
                if (scopeInvalid) {
                    errors.add(sharedAttributeError(scopePath, scopePoints));
                }
                ScopeMetrics.Builder acceptedScope = scope.toBuilder().clearMetrics();
                int metricIndex = 0;
                for (Metric metric : scope.getMetricsList()) {
                    String metricPath = scopePath + ".Metrics." + ++metricIndex;
                    int metricPoints = dataPointCount(metric);
                    if (metricPoints == 0) {
                        errors.add("Invalid OTel metric type [" + metricPath + "].");
                        continue;
                    }
                    boolean metricInvalid = metric.getName().length() > MAX_METRIC_NAME_LENGTH;
                    if (metricInvalid) {
                        errors.add("Metric name exceeds maximum length: 256 [" + metricPath + "] ("
                                + rejectedCount(metricPoints) + ").");
                    }
                    Metric.Builder acceptedMetric = metric.toBuilder();
                    PointContext context = new PointContext(resource, scope, metric, metricPath, oldest, newest);
                    List<String> pointErrors = filterMetric(acceptedMetric, context);
                    errors.addAll(pointErrors);
                    boolean sharedInvalid = resourceInvalid || scopeInvalid || metricInvalid;
                    rejected += sharedInvalid ? metricPoints : pointErrors.size();
                    if (!sharedInvalid && dataPointCount(acceptedMetric.build()) > 0) {
                        acceptedScope.addMetrics(acceptedMetric);
                    }
                }
                if (acceptedScope.getMetricsCount() > 0) {
                    acceptedResource.addScopeMetrics(acceptedScope);
                }
            }
            if (acceptedResource.getScopeMetricsCount() > 0) {
                accepted.addResourceMetrics(acceptedResource);
            }
        }
        errors.sort(String::compareTo);
        return new Result(accepted.build(), rejected, List.copyOf(errors));
    }

    private List<String> filterMetric(Metric.Builder accepted, PointContext context) {
        Metric metric = context.metric();
        List<String> errors = new ArrayList<>();
        switch (metric.getDataCase()) {
            case GAUGE -> {
                Gauge.Builder data = metric.getGauge().toBuilder().clearDataPoints();
                filterPoints(metric.getGauge().getDataPointsList(), NumberDataPoint::getTimeUnixNano,
                        NumberDataPoint::getAttributesList, data::addDataPoints, context, errors);
                accepted.setGauge(data);
            }
            case SUM -> {
                Sum.Builder data = metric.getSum().toBuilder().clearDataPoints();
                filterPoints(metric.getSum().getDataPointsList(), NumberDataPoint::getTimeUnixNano,
                        NumberDataPoint::getAttributesList, data::addDataPoints, context, errors);
                accepted.setSum(data);
            }
            case HISTOGRAM -> {
                Histogram.Builder data = metric.getHistogram().toBuilder().clearDataPoints();
                filterPoints(metric.getHistogram().getDataPointsList(), HistogramDataPoint::getTimeUnixNano,
                        HistogramDataPoint::getAttributesList, data::addDataPoints, context, errors);
                accepted.setHistogram(data);
            }
            case EXPONENTIAL_HISTOGRAM -> {
                ExponentialHistogram.Builder data = metric.getExponentialHistogram().toBuilder().clearDataPoints();
                filterPoints(metric.getExponentialHistogram().getDataPointsList(),
                        ExponentialHistogramDataPoint::getTimeUnixNano,
                        ExponentialHistogramDataPoint::getAttributesList, data::addDataPoints, context, errors);
                accepted.setExponentialHistogram(data);
            }
            case SUMMARY -> {
                Summary.Builder data = metric.getSummary().toBuilder().clearDataPoints();
                filterPoints(metric.getSummary().getDataPointsList(), SummaryDataPoint::getTimeUnixNano,
                        SummaryDataPoint::getAttributesList, data::addDataPoints, context, errors);
                accepted.setSummary(data);
            }
            case DATA_NOT_SET -> { }
        }
        return errors;
    }

    private <T> void filterPoints(List<T> points, ToLongFunction<T> timestamp,
                                  Function<T, List<KeyValue>> attributes, Consumer<T> add,
                                  PointContext context, List<String> errors) {
        int pointIndex = 0;
        for (T point : points) {
            String path = context.path() + ".Datapoint." + ++pointIndex;
            String error = pointError(timestamp.applyAsLong(point), attributes.apply(point), context, path);
            if (error == null) {
                add.accept(point);
            } else {
                errors.add(error);
            }
        }
    }

    private String pointError(long timestamp, List<KeyValue> attributes, PointContext context, String path) {
        if (hasOversizedStringAttribute(attributes)) {
            return "Attribute string value exceeds maximum length: 1024 [" + path + "].";
        }
        List<KeyValue> resourceAttributes = context.resource().getResource().getAttributesList();
        List<KeyValue> scopeAttributes = context.scope().getScope().getAttributesList();
        if (resourceAttributes.size() + scopeAttributes.size() + attributes.size() > MAX_ATTRIBUTES) {
            return "Maximum number of attributes exceeded: 150 [" + path + "].";
        }
        long metadataSize = utf8Size(context.metric().getName()) + utf8Size(context.metric().getUnit())
                + utf8Size(context.scope().getScope().getName()) + utf8Size(context.scope().getScope().getVersion())
                + attributesSize(resourceAttributes) + attributesSize(scopeAttributes) + attributesSize(attributes);
        if (metadataSize > MAX_METADATA_BYTES) {
            return "Maximum size of datapoint metadata exceeded: 40 KB [" + path + "].";
        }
        if (Long.compareUnsigned(timestamp, context.oldestNanos()) >= 0
                && Long.compareUnsigned(timestamp, context.newestNanos()) <= 0) {
            return null;
        }
        String direction = Long.compareUnsigned(timestamp, context.oldestNanos()) < 0
                ? "too far in the past" : "too far in the future";
        String resourceName = resourceAttributes.stream().filter(attribute -> "service.name".equals(attribute.getKey()))
                .map(attribute -> "service.name='" + attribute.getValue().getStringValue() + "'")
                .findFirst().orElse("service.name is not present");
        String metricName = context.metric().getName();
        if (metricName.length() > 60) {
            metricName = metricName.substring(0, 60) + "...";
        }
        return "TimeUnixNano " + Long.toUnsignedString(timestamp) + " is " + direction + " for metric '"
                + metricName + "' for resource where " + resourceName + " at [" + path + "].";
    }

    private long epochNanos(Instant instant) {
        return instant.getEpochSecond() * NANOS_PER_SECOND + instant.getNano();
    }

    private int dataPointCount(ResourceMetrics resource) {
        return resource.getScopeMetricsList().stream().mapToInt(this::dataPointCount).sum();
    }

    private int dataPointCount(ScopeMetrics scope) {
        return scope.getMetricsList().stream().mapToInt(OtlpMetricsValidation::dataPointCount).sum();
    }

    static int dataPointCount(Metric metric) {
        return switch (metric.getDataCase()) {
            case GAUGE -> metric.getGauge().getDataPointsCount();
            case SUM -> metric.getSum().getDataPointsCount();
            case HISTOGRAM -> metric.getHistogram().getDataPointsCount();
            case EXPONENTIAL_HISTOGRAM -> metric.getExponentialHistogram().getDataPointsCount();
            case SUMMARY -> metric.getSummary().getDataPointsCount();
            case DATA_NOT_SET -> 0;
        };
    }

    private boolean hasOversizedStringAttribute(List<KeyValue> attributes) {
        // CloudWatch counts UTF-16 code units here; the metadata limit counts UTF-8 bytes.
        return attributes.stream().map(KeyValue::getValue).anyMatch(this::hasOversizedString);
    }

    private boolean hasOversizedString(AnyValue value) {
        return switch (value.getValueCase()) {
            case STRING_VALUE -> value.getStringValue().length() > MAX_ATTRIBUTE_LENGTH;
            case ARRAY_VALUE -> value.getArrayValue().getValuesList().stream().anyMatch(this::hasOversizedString);
            case KVLIST_VALUE -> hasOversizedStringAttribute(value.getKvlistValue().getValuesList());
            default -> false;
        };
    }

    private long attributesSize(List<KeyValue> attributes) {
        long size = 0;
        for (KeyValue attribute : attributes) {
            size += utf8Size(attribute.getKey());
            size += valueSize(attribute.getValue());
        }
        return size;
    }

    private long valueSize(AnyValue value) {
        return switch (value.getValueCase()) {
            case STRING_VALUE -> utf8Size(value.getStringValue());
            case BOOL_VALUE -> 1;
            case INT_VALUE, DOUBLE_VALUE -> 8;
            case BYTES_VALUE -> value.getBytesValue().size();
            case ARRAY_VALUE -> value.getArrayValue().getValuesList().stream().mapToLong(this::valueSize).sum();
            case KVLIST_VALUE -> attributesSize(value.getKvlistValue().getValuesList());
            case VALUE_NOT_SET -> 0;
        };
    }

    private int utf8Size(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String sharedAttributeError(String path, int count) {
        return "Attribute string value exceeds maximum length: 1024 [" + path + "] ("
                + rejectedCount(count) + ").";
    }

    private String rejectedCount(int count) {
        return count + (count == 1 ? " datapoint rejected" : " datapoints rejected");
    }
}
