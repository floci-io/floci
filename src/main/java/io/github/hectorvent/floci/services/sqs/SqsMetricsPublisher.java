package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.MinuteMetricsPublisher;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Supplies the AWS/SQS metrics of every active queue to the minute publisher, shaped as SQS sends
 * them: one sample of each depth gauge, and for each counter one sample per call in the minute plus
 * the zero sample AWS adds to every minute. SentMessageSize has one sample per message and no zero
 * sample.
 */
@ApplicationScoped
public class SqsMetricsPublisher {

    static final String NAMESPACE = "AWS/SQS";
    private static final String COUNT = "Count";

    private final MinuteMetricsPublisher<SqsService.QueueMetrics> publisher;

    @Inject
    public SqsMetricsPublisher(SqsService sqsService, CloudWatchMetricsService metricsService, EmulatorConfig config) {
        boolean enabled = config.services().sqs().enabled() && config.services().sqs().cloudwatchMetricsEnabled()
                && config.services().cloudwatchmetrics().enabled();
        this.publisher = new MinuteMetricsPublisher<>(metricsService, NAMESPACE, enabled) {
            @Override
            protected List<SqsService.QueueMetrics> collectTargets(Instant now) {
                return sqsService.collectQueueMetrics(now);
            }

            @Override
            protected List<MetricDatum> collectDatums(SqsService.QueueMetrics target, long timestampSeconds) {
                return datums(target, timestampSeconds);
            }

            @Override
            protected String accountId(SqsService.QueueMetrics target) {
                return target.accountId();
            }

            @Override
            protected String region(SqsService.QueueMetrics target) {
                return target.region();
            }

            @Override
            protected String targetName(SqsService.QueueMetrics target) {
                return target.queueName();
            }
        };
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

    private static List<MetricDatum> datums(SqsService.QueueMetrics queue, long timestamp) {
        List<Dimension> dimensions = List.of(new Dimension("QueueName", queue.queueName()));
        GuardedMessageQueue.MessageCounts counts = queue.gauges().counts();
        SqsQueueActivity.Snapshot activity = queue.activity();
        List<MetricDatum> datums = new ArrayList<>(List.of(
                MetricDatum.of("ApproximateNumberOfMessagesVisible", COUNT, counts.visible(), dimensions, timestamp),
                MetricDatum.of("ApproximateNumberOfMessagesNotVisible", COUNT, counts.inFlight(), dimensions,
                        timestamp),
                MetricDatum.of("ApproximateNumberOfMessagesDelayed", COUNT, counts.delayed(), dimensions, timestamp),
                MetricDatum.of("ApproximateAgeOfOldestMessage", "Seconds",
                        queue.gauges().ageOfOldestMessageSeconds(), dimensions, timestamp),
                counter("NumberOfMessagesSent", activity.sent(), dimensions, timestamp),
                counter("NumberOfMessagesReceived", activity.received(), dimensions, timestamp),
                counter("NumberOfMessagesDeleted", activity.deleted(), dimensions, timestamp),
                counter("NumberOfEmptyReceives", activity.emptyReceives(), dimensions, timestamp)));
        if (activity.sentSize().count() > 0) {
            datums.add(samples("SentMessageSize", "Bytes", activity.sentSize(), dimensions, timestamp));
        }
        if (queue.fifo()) {
            datums.add(MetricDatum.of("ApproximateNumberOfGroupsWithInflightMessages", COUNT,
                    queue.gauges().groupsWithInflightMessages(), dimensions, timestamp));
            datums.add(counter("NumberOfDeduplicatedSentMessages", activity.deduplicated(), dimensions, timestamp));
        }
        return datums;
    }

    private static MetricDatum counter(String metricName, SqsQueueActivity.Samples calls,
                                       List<Dimension> dimensions, long timestamp) {
        return samples(metricName, COUNT, calls.add(0), dimensions, timestamp);
    }

    private static MetricDatum samples(String metricName, String unit, SqsQueueActivity.Samples samples,
                                       List<Dimension> dimensions, long timestamp) {
        MetricDatum datum = MetricDatum.of(metricName, unit, samples.sum(), dimensions, timestamp);
        datum.setSampleCount(samples.count());
        datum.setSum(samples.sum());
        datum.setMinimum(samples.minimum());
        datum.setMaximum(samples.maximum());
        return datum;
    }
}
