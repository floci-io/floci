package io.floci.aws.services.cloudwatch.metrics;

import com.google.protobuf.Any;
import com.google.rpc.Status;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsPartialSuccess;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.metrics.v1.DataPointFlags;
import io.opentelemetry.proto.metrics.v1.Exemplar;
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
import io.opentelemetry.proto.resource.v1.Resource;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Registers protobuf message accessors used reflectively by protobuf's JSON codec. */
@RegisterForReflection(targets = {
        Any.class, Status.class, ExportMetricsPartialSuccess.class, ExportMetricsServiceRequest.class,
        ExportMetricsServiceResponse.class, AnyValue.class, ArrayValue.class, InstrumentationScope.class,
        KeyValue.class, KeyValueList.class, AggregationTemporality.class, DataPointFlags.class,
        Exemplar.class, ExponentialHistogram.class,
        ExponentialHistogramDataPoint.class, ExponentialHistogramDataPoint.Buckets.class, Gauge.class,
        Histogram.class, HistogramDataPoint.class, Metric.class, NumberDataPoint.class, ResourceMetrics.class,
        ScopeMetrics.class, Sum.class, Summary.class, SummaryDataPoint.class,
        SummaryDataPoint.ValueAtQuantile.class, Resource.class
})
public class OtlpMetricsNativeSupport {}
