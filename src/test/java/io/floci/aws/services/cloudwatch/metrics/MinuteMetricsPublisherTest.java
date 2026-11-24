package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    void everyTickCutsTheNamespaceBackToTheCeiling() {
        publisher(metrics, 500).publish(clock.instant());

        verify(metrics).deleteOldestMetricData("AWS/Test", 500);
    }

    @Test
    void aCeilingOfZeroKeepsEveryDatapoint() {
        publisher(metrics, 0).publish(clock.instant());

        verify(metrics, never()).deleteOldestMetricData(anyString(), anyInt());
    }

    @Test
    void aFailedCutIsTriedAgainTheNextMinute() {
        doThrow(new IllegalStateException("store closed")).when(metrics).deleteOldestMetricData("AWS/Test", 500);
        MinuteMetricsPublisher<String> publisher = publisher(metrics, 500);

        publisher.publish(clock.instant());
        publisher.publish(clock.instant().plusSeconds(60));

        verify(metrics, times(2)).deleteOldestMetricData("AWS/Test", 500);
    }

    @Test
    void theCeilingLeavesAClientsOwnNamespaceAlone() {
        CloudWatchMetricsService stored = new CloudWatchMetricsService(new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver("us-east-1", "111111111111"));
        Instant start = clock.instant().minusSeconds(86_400);
        for (int i = 0; i < 3; i++) {
            stored.putMetricData("MyApp", List.of(MetricDatum.of("Value", "Count", 1, List.of(),
                    start.plusSeconds(60L * i).getEpochSecond())), "us-east-1");
        }
        MinuteMetricsPublisher<String> publisher = publisher(stored, 2);

        for (int i = 0; i < 3; i++) {
            publisher.publish(clock.instant().plusSeconds(60L * i));
        }

        Instant end = clock.instant().plusSeconds(600);
        assertEquals(3, stored.getMetricStatistics("MyApp", "Value", null, start, end, 60, List.of("Sum"), null,
                "us-east-1").size());
        assertEquals(List.of(Instant.parse("2026-10-27T12:34:00Z"), Instant.parse("2026-10-27T12:35:00Z")),
                stored.getMetricStatistics("AWS/Test", "Value", null, start, end, 60, List.of("Sum"), null,
                        "us-east-1").stream().map(CloudWatchMetricsService.Datapoint::timestamp).toList());
    }

    private MinuteMetricsPublisher<String> publisher(boolean enabled) {
        return publisher(metrics, enabled, 0);
    }

    private MinuteMetricsPublisher<String> publisher(CloudWatchMetricsService service, int maxStoredDatapoints) {
        return publisher(service, true, maxStoredDatapoints);
    }

    private MinuteMetricsPublisher<String> publisher(CloudWatchMetricsService service, boolean enabled,
                                                     int maxStoredDatapoints) {
        return new MinuteMetricsPublisher<>(service, "AWS/Test", enabled, maxStoredDatapoints, clock, scheduler) {
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
