package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MinuteMetricsPublisherTest {

    private final CloudWatchMetricsService metrics = mock(CloudWatchMetricsService.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T12:34:20Z"), ZoneOffset.UTC);

    @Test
    void startupAlignsTicksToTheMinuteAndStampsTheEndedMinute() {
        MinuteMetricsPublisher<String> publisher = publisher(true);
        publisher.start();
        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleAtFixedRate(tick.capture(), eq(41_000L), eq(60_000L), eq(TimeUnit.MILLISECONDS));

        tick.getValue().run();
        tick.getValue().run();

        ArgumentCaptor<List<MetricDatum>> datums = ArgumentCaptor.forClass(List.class);
        verify(metrics).putMetricDataForAccount(eq("111111111111"), eq("AWS/Test"), datums.capture(), eq("us-east-1"));
        assertEquals(Instant.parse("2026-10-27T12:33:00Z").getEpochSecond(),
                datums.getValue().getFirst().getTimestamp());
    }

    @Test
    void disabledPublisherDoesNotScheduleTicks() {
        publisher(false).start();

        verifyNoInteractions(scheduler, metrics);
    }

    @Test
    void shutdownWaitsFiveSecondsForAnActiveTick() throws Exception {
        publisher(true).stop();

        verify(scheduler).shutdown();
        verify(scheduler).awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void shutdownRestoresTheInterruptFlag() throws Exception {
        when(scheduler.awaitTermination(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException("stopping"));
        try {
            publisher(true).stop();

            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private MinuteMetricsPublisher<String> publisher(boolean enabled) {
        return new MinuteMetricsPublisher<>(metrics, "AWS/Test", enabled, clock, scheduler) {
            @Override
            protected List<String> collectTargets(Instant now) {
                return List.of("target");
            }

            @Override
            protected List<MetricDatum> collectDatums(String target, long timestampSeconds) {
                return List.of(MetricDatum.of("Value", "Count", 1, List.of(), timestampSeconds));
            }

            @Override
            protected String accountId(String target) {
                return "111111111111";
            }

            @Override
            protected String region(String target) {
                return "us-east-1";
            }

            @Override
            protected String targetName(String target) {
                return target;
            }
        };
    }
}
