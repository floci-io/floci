package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Shared scheduling, minute claims and retention for service-generated CloudWatch metrics. A tick
 * runs just after each minute boundary and stamps its samples with the minute that ended, so a call
 * lands in the minute it was made, as on AWS. Once an hour it drops the namespace's datapoints older
 * than the 15 days AWS keeps one-minute data, so the generated history stays bounded.
 */
public abstract class MinuteMetricsPublisher<T> {

    private static final Logger LOG = Logger.getLogger(MinuteMetricsPublisher.class);
    private static final long MINUTE_MILLIS = 60_000;
    private static final long TICK_OFFSET_MILLIS = 1_000;
    private static final Duration RETENTION = Duration.ofDays(15);

    private final CloudWatchMetricsService metricsService;
    private final String namespace;
    private final boolean enabled;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private long lastAttemptedMinute = Long.MIN_VALUE;

    protected MinuteMetricsPublisher(CloudWatchMetricsService metricsService, String namespace, boolean enabled) {
        this(metricsService, namespace, enabled, Clock.systemUTC(), Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, namespace + "-metrics-publisher");
            thread.setDaemon(true);
            return thread;
        }));
    }

    protected MinuteMetricsPublisher(CloudWatchMetricsService metricsService, String namespace, boolean enabled,
                                     Clock clock, ScheduledExecutorService scheduler) {
        this.metricsService = metricsService;
        this.namespace = namespace;
        this.enabled = enabled;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    public void start() {
        if (!enabled) {
            return;
        }
        long untilNextMinute = MINUTE_MILLIS - clock.millis() % MINUTE_MILLIS;
        scheduler.scheduleAtFixedRate(() -> publish(clock.instant()), untilNextMinute + TICK_OFFSET_MILLIS,
                MINUTE_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Finish a running tick before EmulatorLifecycle shuts down storage. */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warnv("The {0} metrics publisher did not finish its tick within 5 seconds", namespace);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnv(e, "Interrupted while stopping the {0} metrics publisher", namespace);
        }
    }

    /**
     * Nothing may escape: the scheduler stops for good after a tick throws. One target's failed write
     * drops only that target's samples for the minute.
     */
    public synchronized void publish(Instant now) {
        long minute = endedMinute(now);
        if (minute <= lastAttemptedMinute) {
            return;
        }
        // Collection can drain counters, and a failed write may already have stored some samples.
        lastAttemptedMinute = minute;
        List<T> targets;
        try {
            targets = collectTargets(now);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Collecting {0} metric targets failed; this minute's samples are dropped", namespace);
            return;
        }
        for (T target : targets) {
            try {
                List<MetricDatum> datums = collectDatums(target, minute);
                if (!datums.isEmpty()) {
                    metricsService.putMetricDataForAccount(accountId(target), namespace, datums, region(target));
                }
            } catch (RuntimeException e) {
                LOG.warnv(e, "Publishing {0} metrics for {1} of account {2} in {3} failed; its samples are dropped",
                        namespace, targetName(target), accountId(target), region(target));
            }
        }
        if (minute % 3600 == 0) {
            try {
                metricsService.deleteMetricDataBefore(namespace, Instant.ofEpochSecond(minute).minus(RETENTION));
            } catch (RuntimeException e) {
                LOG.warnv(e, "Dropping {0} datapoints older than {1} failed; the next hour tries again",
                        namespace, RETENTION);
            }
        }
    }

    /** Reading half a minute back absorbs drift of the fixed-rate schedule away from the boundary. */
    private static long endedMinute(Instant tick) {
        return tick.minusSeconds(30).truncatedTo(ChronoUnit.MINUTES).getEpochSecond();
    }

    protected abstract List<T> collectTargets(Instant now);

    protected abstract List<MetricDatum> collectDatums(T target, long timestampSeconds);

    protected abstract String accountId(T target);

    protected abstract String region(T target);

    protected abstract String targetName(T target);
}
