package io.floci.aws.services.cloudwatch.metrics;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import io.apicurio.registry.utils.protobuf.schema.FileDescriptorUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/** Descriptors for the OTLP metrics proto types, loaded at startup from vendored proto files. */
final class OtlpDescriptors {

    static final Descriptor EXPORT_REQUEST;
    static final Descriptor EXPORT_RESPONSE;
    static final Descriptor PARTIAL_SUCCESS;

    static final FieldDescriptor RESOURCE_METRICS_LIST;
    static final FieldDescriptor RESPONSE_PARTIAL_SUCCESS;
    static final FieldDescriptor REJECTED_DATA_POINTS;
    static final FieldDescriptor PARTIAL_ERROR_MESSAGE;

    static final FieldDescriptor RESOURCE_FIELD;
    static final FieldDescriptor SCOPE_METRICS_LIST;

    static final FieldDescriptor SCOPE_FIELD;
    static final FieldDescriptor METRICS_LIST;

    static final OneofDescriptor METRIC_DATA_ONEOF;
    static final FieldDescriptor GAUGE_FIELD;
    static final FieldDescriptor SUM_FIELD;
    static final FieldDescriptor HISTOGRAM_FIELD;
    static final FieldDescriptor EXPONENTIAL_HISTOGRAM_FIELD;
    static final FieldDescriptor SUMMARY_FIELD;
    static final FieldDescriptor METRIC_NAME;
    static final FieldDescriptor METRIC_UNIT;

    static final FieldDescriptor GAUGE_DATA_POINTS;
    static final FieldDescriptor SUM_DATA_POINTS;
    static final FieldDescriptor HISTOGRAM_DATA_POINTS;
    static final FieldDescriptor EXPONENTIAL_HISTOGRAM_DATA_POINTS;
    static final FieldDescriptor SUMMARY_DATA_POINTS;

    static final FieldDescriptor NUMBER_POINT_TIME_UNIX_NANO;
    static final FieldDescriptor NUMBER_POINT_ATTRIBUTES;
    static final FieldDescriptor HISTOGRAM_POINT_TIME_UNIX_NANO;
    static final FieldDescriptor HISTOGRAM_POINT_ATTRIBUTES;
    static final FieldDescriptor EXP_HISTOGRAM_POINT_TIME_UNIX_NANO;
    static final FieldDescriptor EXP_HISTOGRAM_POINT_ATTRIBUTES;
    static final FieldDescriptor SUMMARY_POINT_TIME_UNIX_NANO;
    static final FieldDescriptor SUMMARY_POINT_ATTRIBUTES;

    static final FieldDescriptor RESOURCE_ATTRIBUTES;
    static final FieldDescriptor SCOPE_ATTRIBUTES;
    static final FieldDescriptor SCOPE_NAME;
    static final FieldDescriptor SCOPE_VERSION;

    static final FieldDescriptor KV_KEY;
    static final FieldDescriptor KV_VALUE;

    static final OneofDescriptor ANY_VALUE_ONEOF;
    static final FieldDescriptor STRING_VALUE;
    static final FieldDescriptor BOOL_VALUE;
    static final FieldDescriptor INT_VALUE;
    static final FieldDescriptor DOUBLE_VALUE;
    static final FieldDescriptor BYTES_VALUE;
    static final FieldDescriptor ARRAY_VALUE;
    static final FieldDescriptor KVLIST_VALUE;
    static final FieldDescriptor ARRAY_VALUES;
    static final FieldDescriptor KVLIST_VALUES;

    static {
        try {
            String common = "opentelemetry/proto/common/v1/common.proto";
            String resource = "opentelemetry/proto/resource/v1/resource.proto";
            String metrics = "opentelemetry/proto/metrics/v1/metrics.proto";
            String service = "opentelemetry/proto/collector/metrics/v1/metrics_service.proto";
            String commonText = schema(common);
            String resourceText = schema(resource);
            String metricsText = schema(metrics);
            String serviceText = schema(service);
            Map<String, String> commonSchema = Map.of("opentelemetry.proto.common.v1/common.proto", commonText);
            Map<String, String> metricSchemas = Map.of(
                    "opentelemetry.proto.common.v1/common.proto", commonText,
                    "opentelemetry.proto.resource.v1/resource.proto", resourceText);
            Map<String, String> serviceSchemas = Map.of(
                    "opentelemetry.proto.common.v1/common.proto", commonText,
                    "opentelemetry.proto.resource.v1/resource.proto", resourceText,
                    "opentelemetry.proto.metrics.v1/metrics.proto", metricsText);
            FileDescriptor commonFile = descriptor(common, commonText, Map.of());
            FileDescriptor resourceFile = descriptor(resource, resourceText, commonSchema, commonFile);
            FileDescriptor metricsFile = descriptor(metrics, metricsText, metricSchemas, commonFile, resourceFile);
            FileDescriptor serviceFile = descriptor(service, serviceText, serviceSchemas, commonFile, resourceFile,
                    metricsFile);

            EXPORT_REQUEST = serviceFile.findMessageTypeByName("ExportMetricsServiceRequest");
            EXPORT_RESPONSE = serviceFile.findMessageTypeByName("ExportMetricsServiceResponse");
            PARTIAL_SUCCESS = serviceFile.findMessageTypeByName("ExportMetricsPartialSuccess");

            Descriptor metricDesc = metricsFile.findMessageTypeByName("Metric");
            Descriptor numberPointDesc = metricsFile.findMessageTypeByName("NumberDataPoint");
            Descriptor histPointDesc = metricsFile.findMessageTypeByName("HistogramDataPoint");
            Descriptor expHistPointDesc = metricsFile.findMessageTypeByName("ExponentialHistogramDataPoint");
            Descriptor summaryPointDesc = metricsFile.findMessageTypeByName("SummaryDataPoint");
            Descriptor resourceMetricsDesc = metricsFile.findMessageTypeByName("ResourceMetrics");
            Descriptor scopeMetricsDesc = metricsFile.findMessageTypeByName("ScopeMetrics");

            Descriptor keyValueDesc = commonFile.findMessageTypeByName("KeyValue");
            Descriptor anyValueDesc = commonFile.findMessageTypeByName("AnyValue");
            Descriptor arrayValueDesc = commonFile.findMessageTypeByName("ArrayValue");
            Descriptor kvListDesc = commonFile.findMessageTypeByName("KeyValueList");
            Descriptor scopeDesc = commonFile.findMessageTypeByName("InstrumentationScope");
            Descriptor resourceDesc = resourceFile.findMessageTypeByName("Resource");

            RESOURCE_METRICS_LIST = EXPORT_REQUEST.findFieldByName("resource_metrics");
            RESPONSE_PARTIAL_SUCCESS = EXPORT_RESPONSE.findFieldByName("partial_success");
            REJECTED_DATA_POINTS = PARTIAL_SUCCESS.findFieldByName("rejected_data_points");
            PARTIAL_ERROR_MESSAGE = PARTIAL_SUCCESS.findFieldByName("error_message");

            RESOURCE_FIELD = resourceMetricsDesc.findFieldByName("resource");
            SCOPE_METRICS_LIST = resourceMetricsDesc.findFieldByName("scope_metrics");
            SCOPE_FIELD = scopeMetricsDesc.findFieldByName("scope");
            METRICS_LIST = scopeMetricsDesc.findFieldByName("metrics");

            METRIC_DATA_ONEOF = metricDesc.getOneofs().get(0);
            GAUGE_FIELD = metricDesc.findFieldByName("gauge");
            SUM_FIELD = metricDesc.findFieldByName("sum");
            HISTOGRAM_FIELD = metricDesc.findFieldByName("histogram");
            EXPONENTIAL_HISTOGRAM_FIELD = metricDesc.findFieldByName("exponential_histogram");
            SUMMARY_FIELD = metricDesc.findFieldByName("summary");
            METRIC_NAME = metricDesc.findFieldByName("name");
            METRIC_UNIT = metricDesc.findFieldByName("unit");

            GAUGE_DATA_POINTS = metricsFile.findMessageTypeByName("Gauge").findFieldByName("data_points");
            SUM_DATA_POINTS = metricsFile.findMessageTypeByName("Sum").findFieldByName("data_points");
            HISTOGRAM_DATA_POINTS = metricsFile.findMessageTypeByName("Histogram").findFieldByName("data_points");
            EXPONENTIAL_HISTOGRAM_DATA_POINTS =
                    metricsFile.findMessageTypeByName("ExponentialHistogram").findFieldByName("data_points");
            SUMMARY_DATA_POINTS = metricsFile.findMessageTypeByName("Summary").findFieldByName("data_points");

            NUMBER_POINT_TIME_UNIX_NANO = numberPointDesc.findFieldByName("time_unix_nano");
            NUMBER_POINT_ATTRIBUTES = numberPointDesc.findFieldByName("attributes");
            HISTOGRAM_POINT_TIME_UNIX_NANO = histPointDesc.findFieldByName("time_unix_nano");
            HISTOGRAM_POINT_ATTRIBUTES = histPointDesc.findFieldByName("attributes");
            EXP_HISTOGRAM_POINT_TIME_UNIX_NANO = expHistPointDesc.findFieldByName("time_unix_nano");
            EXP_HISTOGRAM_POINT_ATTRIBUTES = expHistPointDesc.findFieldByName("attributes");
            SUMMARY_POINT_TIME_UNIX_NANO = summaryPointDesc.findFieldByName("time_unix_nano");
            SUMMARY_POINT_ATTRIBUTES = summaryPointDesc.findFieldByName("attributes");

            RESOURCE_ATTRIBUTES = resourceDesc.findFieldByName("attributes");
            SCOPE_ATTRIBUTES = scopeDesc.findFieldByName("attributes");
            SCOPE_NAME = scopeDesc.findFieldByName("name");
            SCOPE_VERSION = scopeDesc.findFieldByName("version");

            KV_KEY = keyValueDesc.findFieldByName("key");
            KV_VALUE = keyValueDesc.findFieldByName("value");

            ANY_VALUE_ONEOF = anyValueDesc.getOneofs().get(0);
            STRING_VALUE = anyValueDesc.findFieldByName("string_value");
            BOOL_VALUE = anyValueDesc.findFieldByName("bool_value");
            INT_VALUE = anyValueDesc.findFieldByName("int_value");
            DOUBLE_VALUE = anyValueDesc.findFieldByName("double_value");
            BYTES_VALUE = anyValueDesc.findFieldByName("bytes_value");
            ARRAY_VALUE = anyValueDesc.findFieldByName("array_value");
            KVLIST_VALUE = anyValueDesc.findFieldByName("kvlist_value");
            ARRAY_VALUES = arrayValueDesc.findFieldByName("values");
            KVLIST_VALUES = kvListDesc.findFieldByName("values");

        } catch (DescriptorValidationException | IOException e) {
            throw new IllegalStateException("Failed to load OTLP proto descriptors", e);
        }
    }

    private static FileDescriptor descriptor(String name, String text, Map<String, String> dependencies,
                                             FileDescriptor... files) throws DescriptorValidationException {
        String packageName = name.substring(0, name.lastIndexOf('/')).replace('/', '.');
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        return FileDescriptor.buildFrom(FileDescriptorUtils.toFileDescriptorProto(text, fileName,
                Optional.of(packageName), dependencies).toBuilder().setName(name).build(), files);
    }

    private static String schema(String name) throws IOException {
        try (InputStream input = OtlpDescriptors.class.getClassLoader().getResourceAsStream("proto/" + name)) {
            if (input == null) {
                throw new IOException("OTLP proto file not found: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private OtlpDescriptors() {}
}
