package io.github.hectorvent.floci.services.redshift.metrics;

import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;

import java.util.List;

/**
 * Supplies AWS/Redshift samples for one cluster each minute. Every CDI bean of this type is asked by
 * {@link RedshiftMetricsPublisher}; a collector returns nothing for a cluster it has no real value for.
 */
public interface RedshiftMetricCollector {

    /** {@code timestampSeconds} is the epoch second of the minute that ended; stamp every sample with it. */
    List<MetricDatum> collect(RedshiftMetricTarget target, long timestampSeconds);
}
