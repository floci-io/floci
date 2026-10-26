package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends the AWS/SQS metrics of every active queue to CloudWatch once a minute, shaped as SQS sends
 * them: one sample of each depth gauge, and for each counter one sample per call in the minute plus
 * the zero sample AWS adds to every minute. SentMessageSize has one sample per message and no zero
 * sample. A tick runs just after each minute boundary and stamps its samples with the minute that
 * ended, so a call lands in the minute it was made, as on AWS. Once an hour it drops the AWS/SQS
 * datapoints older than the 15 days AWS keeps one-minute data, so the generated history stays bounded.
 */
@ApplicationScoped
public class SqsMetricsPublisher {

    static final String NAMESPACE = "AWS/SQS";
    private static final Logger LOG = Logger.getLogger(SqsMetricsPublisher.class);
    private static final long MINUTE_MILLIS = 60_000;
    private static final long TICK_OFFSET_MILLIS = 1_000;
    private static final Duration RETENTION = Duration.ofDays(15);
    private static final String COUNT = "Count";

    private final SqsService sqsService;
    private final CloudWatchMetricsService metricsService;
    private final boolean enabled;
    private final ScheduledExecutorService scheduler;

    @Inject
    public SqsMetricsPublisher(SqsService sqsService, CloudWatchMetricsService metricsService, EmulatorConfig config) {
        this.sqsService = sqsService;
        this.metricsService = metricsService;
        this.enabled = config.services().sqs().enabled() && config.services().sqs().cloudwatchMetricsEnabled()
                && config.services().cloudwatchmetrics().enabled();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sqs-metrics-publisher");
            t.setDaemon(true);
            return t;
        });
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            return;
        }
        long untilNextMinute = MINUTE_MILLIS - System.currentTimeMillis() % MINUTE_MILLIS;
        scheduler.scheduleAtFixedRate(() -> publish(Instant.now()), untilNextMinute + TICK_OFFSET_MILLIS,
                MINUTE_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Before EmulatorLifecycle's default-priority storage shutdown, letting a running tick finish its writes. */
    void onStop(@Observes @Priority(1000) ShutdownEvent ignored) {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("The AWS/SQS metrics publisher did not finish its tick within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnv(e, "Interrupted while stopping the AWS/SQS metrics publisher");
        }
    }

    /**
     * Nothing may escape: the scheduler stops for good after a tick throws. One queue's failed write
     * drops only that queue's samples for the minute.
     */
    void publish(Instant now) {
        List<SqsService.QueueMetrics> queues;
        try {
            queues = sqsService.collectQueueMetrics(now);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Collecting AWS/SQS queue metrics failed; this minute's samples are dropped");
            return;
        }
        long minute = endedMinute(now);
        for (SqsService.QueueMetrics queue : queues) {
            try {
                metricsService.putMetricDataForAccount(queue.accountId(), NAMESPACE, datums(queue, minute),
                        queue.region());
            } catch (RuntimeException e) {
                LOG.warnv(e, "Publishing AWS/SQS metrics for queue {0} of account {1} in {2} failed; its samples "
                        + "for this minute are dropped", queue.queueName(), queue.accountId(), queue.region());
            }
        }
        if (minute % 3600 == 0) {
            try {
                metricsService.deleteMetricDataBefore(NAMESPACE, Instant.ofEpochSecond(minute).minus(RETENTION));
            } catch (RuntimeException e) {
                LOG.warnv(e, "Dropping AWS/SQS datapoints older than {0} failed; the next hour tries again", RETENTION);
            }
        }
    }

    /** The minute a tick's samples belong to. Reading it half a minute back absorbs any drift of the
     *  fixed-rate schedule away from the minute boundary. */
    private static long endedMinute(Instant tick) {
        return tick.minusSeconds(30).truncatedTo(ChronoUnit.MINUTES).getEpochSecond();
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
