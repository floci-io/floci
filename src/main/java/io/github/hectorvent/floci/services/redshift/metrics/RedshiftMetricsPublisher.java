package io.github.hectorvent.floci.services.redshift.metrics;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.MinuteMetricsPublisher;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Supplies available clusters and collector samples to the shared minute publisher. */
@ApplicationScoped
public class RedshiftMetricsPublisher {

    static final String NAMESPACE = "AWS/Redshift";

    private final MinuteMetricsPublisher<RedshiftMetricTarget> publisher;

    @Inject
    public RedshiftMetricsPublisher(RedshiftService redshiftService, CloudWatchMetricsService metricsService,
                                    Instance<RedshiftMetricCollector> collectors, EmulatorConfig config) {
        this(redshiftService, metricsService, collectors, isEnabled(config));
    }

    RedshiftMetricsPublisher(RedshiftService redshiftService, CloudWatchMetricsService metricsService,
                             Iterable<RedshiftMetricCollector> collectors, boolean enabled) {
        this.publisher = new MinuteMetricsPublisher<>(metricsService, NAMESPACE, enabled) {
            @Override
            protected List<RedshiftMetricTarget> collectTargets(Instant now) {
                return redshiftService.availableMetricTargets();
            }

            @Override
            protected List<MetricDatum> collectDatums(RedshiftMetricTarget target, long timestampSeconds) {
                List<MetricDatum> datums = new ArrayList<>();
                for (RedshiftMetricCollector collector : collectors) {
                    datums.addAll(collector.collect(target, timestampSeconds));
                }
                return datums;
            }

            @Override
            protected String accountId(RedshiftMetricTarget target) {
                return target.accountId();
            }

            @Override
            protected String region(RedshiftMetricTarget target) {
                return target.region();
            }

            @Override
            protected String targetName(RedshiftMetricTarget target) {
                return target.cluster().getClusterIdentifier();
            }
        };
    }

    static boolean isEnabled(EmulatorConfig config) {
        return config.services().redshift().enabled() && config.services().redshift().cloudwatchMetricsEnabled()
                && config.services().cloudwatchmetrics().enabled();
    }

    void onStart(@Observes StartupEvent ignored) {
        publisher.start();
    }

    void onStop(@Observes @Priority(1000) ShutdownEvent ignored) {
        publisher.stop();
    }

    void publish(Instant now) {
        publisher.publish(now);
    }

}
