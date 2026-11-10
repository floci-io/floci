package io.floci.aws.services.cloudwatch.metrics;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class OtlpMetricsValidation {

    private static final int MAX_DATA_POINTS = 1000;
    private static final int MAX_ATTRIBUTES = 150;
    private static final int MAX_METADATA_BYTES = 40_000;
    private static final int MAX_ATTRIBUTE_LENGTH = 1024;
    private static final int MAX_METRIC_NAME_LENGTH = 256;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Duration MAX_AGE = Duration.ofDays(14);
    private static final Duration MAX_FUTURE = Duration.ofHours(2);

    record Result(byte[] requestBytes, int rejectedDataPoints, List<String> errors) {
        boolean hasErrors() {
            return !errors.isEmpty();
        }

        String errorMessage() {
            return String.join("\n", errors);
        }
    }

    private record PointContext(DynamicMessage resource, DynamicMessage scope, DynamicMessage metric,
                                String path, long oldestNanos, long newestNanos) {}

    Result validate(DynamicMessage request) {
        List<DynamicMessage> resourceList = getList(request, OtlpDescriptors.RESOURCE_METRICS_LIST);
        int count = resourceList.stream().mapToInt(this::dataPointCount).sum();
        if (count > MAX_DATA_POINTS) {
            return new Result(null, count, List.of("Maximum number of datapoints per request exceeded: 1000."));
        }
        Instant now = Instant.now();
        long oldest = epochNanos(now.minus(MAX_AGE));
        long newest = epochNanos(now.plus(MAX_FUTURE));
        int rejected = 0;
        List<String> errors = new ArrayList<>();
        DynamicMessage.Builder accepted = request.toBuilder().clearField(OtlpDescriptors.RESOURCE_METRICS_LIST);
        int resourceIndex = 0;
        for (DynamicMessage resource : resourceList) {
            String resourcePath = "ResourceMetrics." + ++resourceIndex;
            int resourcePoints = dataPointCount(resource);
            DynamicMessage resourceProto = getSubMessage(resource, OtlpDescriptors.RESOURCE_FIELD);
            List<DynamicMessage> resourceAttributes = getList(resourceProto, OtlpDescriptors.RESOURCE_ATTRIBUTES);
            boolean resourceInvalid = resourcePoints > 0 && hasOversizedStringAttribute(resourceAttributes);
            if (resourceInvalid) {
                errors.add(sharedAttributeError(resourcePath, resourcePoints));
            }
            DynamicMessage.Builder acceptedResource = resource.toBuilder()
                    .clearField(OtlpDescriptors.SCOPE_METRICS_LIST);
            int scopeIndex = 0;
            for (DynamicMessage scope : getList(resource, OtlpDescriptors.SCOPE_METRICS_LIST)) {
                String scopePath = resourcePath + ".ScopeMetrics." + ++scopeIndex;
                int scopePoints = scopeDataPointCount(scope);
                DynamicMessage scopeProto = getSubMessage(scope, OtlpDescriptors.SCOPE_FIELD);
                List<DynamicMessage> scopeAttributes = getList(scopeProto, OtlpDescriptors.SCOPE_ATTRIBUTES);
                boolean scopeInvalid = scopePoints > 0 && hasOversizedStringAttribute(scopeAttributes);
                if (scopeInvalid) {
                    errors.add(sharedAttributeError(scopePath, scopePoints));
                }
                DynamicMessage.Builder acceptedScope = scope.toBuilder()
                        .clearField(OtlpDescriptors.METRICS_LIST);
                int metricIndex = 0;
                for (DynamicMessage metric : getList(scope, OtlpDescriptors.METRICS_LIST)) {
                    String metricPath = scopePath + ".Metrics." + ++metricIndex;
                    int metricPoints = metricDataPointCount(metric);
                    if (metricPoints == 0) {
                        errors.add("Invalid OTel metric type [" + metricPath + "].");
                        continue;
                    }
                    String metricName = (String) metric.getField(OtlpDescriptors.METRIC_NAME);
                    boolean metricInvalid = metricName.length() > MAX_METRIC_NAME_LENGTH;
                    if (metricInvalid) {
                        errors.add("Metric name exceeds maximum length: 256 [" + metricPath + "] ("
                                + rejectedCount(metricPoints) + ").");
                    }
                    DynamicMessage.Builder acceptedMetric = metric.toBuilder();
                    PointContext context = new PointContext(resource, scope, metric, metricPath, oldest, newest);
                    List<String> pointErrors = filterMetric(acceptedMetric, context);
                    errors.addAll(pointErrors);
                    boolean sharedInvalid = resourceInvalid || scopeInvalid || metricInvalid;
                    rejected += sharedInvalid ? metricPoints : pointErrors.size();
                    if (!sharedInvalid && metricDataPointCount(acceptedMetric.build()) > 0) {
                        acceptedScope.addRepeatedField(OtlpDescriptors.METRICS_LIST, acceptedMetric.build());
                    }
                }
                if (!getList(acceptedScope.build(), OtlpDescriptors.METRICS_LIST).isEmpty()) {
                    acceptedResource.addRepeatedField(OtlpDescriptors.SCOPE_METRICS_LIST, acceptedScope.build());
                }
            }
            if (!getList(acceptedResource.build(), OtlpDescriptors.SCOPE_METRICS_LIST).isEmpty()) {
                accepted.addRepeatedField(OtlpDescriptors.RESOURCE_METRICS_LIST, acceptedResource.build());
            }
        }
        errors.sort(String::compareTo);
        return new Result(accepted.build().toByteArray(), rejected, List.copyOf(errors));
    }

    private List<String> filterMetric(DynamicMessage.Builder accepted, PointContext context) {
        DynamicMessage metric = context.metric();
        List<String> errors = new ArrayList<>();
        FieldDescriptor activeField = metric.getOneofFieldDescriptor(OtlpDescriptors.METRIC_DATA_ONEOF);
        if (activeField == null) {
            return errors;
        }
        DynamicMessage data = (DynamicMessage) metric.getField(activeField);
        FieldDescriptor dataPointsField = dataPointsFieldFor(activeField);
        DynamicMessage.Builder dataBuilder = data.toBuilder().clearField(dataPointsField);
        FieldDescriptor timestampField = timestampFieldFor(activeField);
        FieldDescriptor attributesField = attributesFieldFor(activeField);
        int pointIndex = 0;
        for (DynamicMessage point : getList(data, dataPointsField)) {
            String path = context.path() + ".Datapoint." + ++pointIndex;
            long timestamp = (Long) point.getField(timestampField);
            String error = pointError(timestamp, getList(point, attributesField), context, path);
            if (error == null) {
                dataBuilder.addRepeatedField(dataPointsField, point);
            } else {
                errors.add(error);
            }
        }
        accepted.setField(activeField, dataBuilder.build());
        return errors;
    }

    private static FieldDescriptor dataPointsFieldFor(FieldDescriptor metricField) {
        if (metricField == OtlpDescriptors.GAUGE_FIELD) {
            return OtlpDescriptors.GAUGE_DATA_POINTS;
        }
        if (metricField == OtlpDescriptors.SUM_FIELD) {
            return OtlpDescriptors.SUM_DATA_POINTS;
        }
        if (metricField == OtlpDescriptors.HISTOGRAM_FIELD) {
            return OtlpDescriptors.HISTOGRAM_DATA_POINTS;
        }
        if (metricField == OtlpDescriptors.EXPONENTIAL_HISTOGRAM_FIELD) {
            return OtlpDescriptors.EXPONENTIAL_HISTOGRAM_DATA_POINTS;
        }
        return OtlpDescriptors.SUMMARY_DATA_POINTS;
    }

    private static FieldDescriptor timestampFieldFor(FieldDescriptor metricField) {
        if (metricField == OtlpDescriptors.GAUGE_FIELD || metricField == OtlpDescriptors.SUM_FIELD) {
            return OtlpDescriptors.NUMBER_POINT_TIME_UNIX_NANO;
        }
        if (metricField == OtlpDescriptors.HISTOGRAM_FIELD) {
            return OtlpDescriptors.HISTOGRAM_POINT_TIME_UNIX_NANO;
        }
        if (metricField == OtlpDescriptors.EXPONENTIAL_HISTOGRAM_FIELD) {
            return OtlpDescriptors.EXP_HISTOGRAM_POINT_TIME_UNIX_NANO;
        }
        return OtlpDescriptors.SUMMARY_POINT_TIME_UNIX_NANO;
    }

    private static FieldDescriptor attributesFieldFor(FieldDescriptor metricField) {
        if (metricField == OtlpDescriptors.GAUGE_FIELD || metricField == OtlpDescriptors.SUM_FIELD) {
            return OtlpDescriptors.NUMBER_POINT_ATTRIBUTES;
        }
        if (metricField == OtlpDescriptors.HISTOGRAM_FIELD) {
            return OtlpDescriptors.HISTOGRAM_POINT_ATTRIBUTES;
        }
        if (metricField == OtlpDescriptors.EXPONENTIAL_HISTOGRAM_FIELD) {
            return OtlpDescriptors.EXP_HISTOGRAM_POINT_ATTRIBUTES;
        }
        return OtlpDescriptors.SUMMARY_POINT_ATTRIBUTES;
    }

    private String pointError(long timestamp, List<DynamicMessage> attributes,
                              PointContext context, String path) {
        if (hasOversizedStringAttribute(attributes)) {
            return "Attribute string value exceeds maximum length: 1024 [" + path + "].";
        }
        DynamicMessage resourceProto = getSubMessage(context.resource(), OtlpDescriptors.RESOURCE_FIELD);
        List<DynamicMessage> resourceAttributes = getList(resourceProto, OtlpDescriptors.RESOURCE_ATTRIBUTES);
        DynamicMessage scopeProto = getSubMessage(context.scope(), OtlpDescriptors.SCOPE_FIELD);
        List<DynamicMessage> scopeAttributes = getList(scopeProto, OtlpDescriptors.SCOPE_ATTRIBUTES);
        if (resourceAttributes.size() + scopeAttributes.size() + attributes.size() > MAX_ATTRIBUTES) {
            return "Maximum number of attributes exceeded: 150 [" + path + "].";
        }
        String metricName = (String) context.metric().getField(OtlpDescriptors.METRIC_NAME);
        String metricUnit = (String) context.metric().getField(OtlpDescriptors.METRIC_UNIT);
        String scopeName = (String) scopeProto.getField(OtlpDescriptors.SCOPE_NAME);
        String scopeVersion = (String) scopeProto.getField(OtlpDescriptors.SCOPE_VERSION);
        long metadataSize = utf8Size(metricName) + utf8Size(metricUnit)
                + utf8Size(scopeName) + utf8Size(scopeVersion)
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
        String serviceNameValue = resourceAttributes.stream()
                .filter(kv -> "service.name".equals(kv.getField(OtlpDescriptors.KV_KEY)))
                .map(kv -> {
                    DynamicMessage value = (DynamicMessage) kv.getField(OtlpDescriptors.KV_VALUE);
                    return "service.name='" + value.getField(OtlpDescriptors.STRING_VALUE) + "'";
                })
                .findFirst().orElse("service.name is not present");
        String displayName = metricName.length() > 60 ? metricName.substring(0, 60) + "..." : metricName;
        return "TimeUnixNano " + Long.toUnsignedString(timestamp) + " is " + direction + " for metric '"
                + displayName + "' for resource where " + serviceNameValue + " at [" + path + "].";
    }

    private long epochNanos(Instant instant) {
        return instant.getEpochSecond() * NANOS_PER_SECOND + instant.getNano();
    }

    int dataPointCount(DynamicMessage resource) {
        return getList(resource, OtlpDescriptors.SCOPE_METRICS_LIST).stream()
                .mapToInt(this::scopeDataPointCount).sum();
    }

    private int scopeDataPointCount(DynamicMessage scope) {
        return getList(scope, OtlpDescriptors.METRICS_LIST).stream()
                .mapToInt(OtlpMetricsValidation::metricDataPointCount).sum();
    }

    static int metricDataPointCount(DynamicMessage metric) {
        FieldDescriptor activeField = metric.getOneofFieldDescriptor(OtlpDescriptors.METRIC_DATA_ONEOF);
        if (activeField == null) {
            return 0;
        }
        DynamicMessage data = (DynamicMessage) metric.getField(activeField);
        return getList(data, dataPointsFieldFor(activeField)).size();
    }

    private boolean hasOversizedStringAttribute(List<DynamicMessage> attributes) {
        // CloudWatch counts UTF-16 code units here; the metadata limit counts UTF-8 bytes.
        return attributes.stream()
                .map(kv -> (DynamicMessage) kv.getField(OtlpDescriptors.KV_VALUE))
                .anyMatch(this::hasOversizedString);
    }

    private boolean hasOversizedString(DynamicMessage value) {
        FieldDescriptor active = value.getOneofFieldDescriptor(OtlpDescriptors.ANY_VALUE_ONEOF);
        if (active == null) {
            return false;
        }
        if (active == OtlpDescriptors.STRING_VALUE) {
            return ((String) value.getField(active)).length() > MAX_ATTRIBUTE_LENGTH;
        }
        if (active == OtlpDescriptors.ARRAY_VALUE) {
            DynamicMessage array = (DynamicMessage) value.getField(active);
            return getList(array, OtlpDescriptors.ARRAY_VALUES).stream().anyMatch(this::hasOversizedString);
        }
        if (active == OtlpDescriptors.KVLIST_VALUE) {
            DynamicMessage kvList = (DynamicMessage) value.getField(active);
            return hasOversizedStringAttribute(getList(kvList, OtlpDescriptors.KVLIST_VALUES));
        }
        return false;
    }

    private long attributesSize(List<DynamicMessage> attributes) {
        long size = 0;
        for (DynamicMessage kv : attributes) {
            size += utf8Size((String) kv.getField(OtlpDescriptors.KV_KEY));
            size += valueSize((DynamicMessage) kv.getField(OtlpDescriptors.KV_VALUE));
        }
        return size;
    }

    private long valueSize(DynamicMessage value) {
        FieldDescriptor active = value.getOneofFieldDescriptor(OtlpDescriptors.ANY_VALUE_ONEOF);
        if (active == null) {
            return 0;
        }
        if (active == OtlpDescriptors.STRING_VALUE) {
            return utf8Size((String) value.getField(active));
        }
        if (active == OtlpDescriptors.BOOL_VALUE) {
            return 1;
        }
        if (active == OtlpDescriptors.INT_VALUE || active == OtlpDescriptors.DOUBLE_VALUE) {
            return 8;
        }
        if (active == OtlpDescriptors.BYTES_VALUE) {
            return ((ByteString) value.getField(active)).size();
        }
        if (active == OtlpDescriptors.ARRAY_VALUE) {
            DynamicMessage array = (DynamicMessage) value.getField(active);
            return getList(array, OtlpDescriptors.ARRAY_VALUES).stream().mapToLong(this::valueSize).sum();
        }
        if (active == OtlpDescriptors.KVLIST_VALUE) {
            DynamicMessage kvList = (DynamicMessage) value.getField(active);
            return attributesSize(getList(kvList, OtlpDescriptors.KVLIST_VALUES));
        }
        return 0;
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

    @SuppressWarnings("unchecked")
    static List<DynamicMessage> getList(DynamicMessage msg, FieldDescriptor field) {
        return (List<DynamicMessage>) msg.getField(field);
    }

    private static DynamicMessage getSubMessage(DynamicMessage msg, FieldDescriptor field) {
        return (DynamicMessage) msg.getField(field);
    }
}
