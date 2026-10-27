package io.github.hectorvent.floci.services.redshift.metrics;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftMetricsPublisherTest {

    private static final String REGION = "eu-west-1";
    private static final String NAMESPACE = "AWS/Redshift";

    private RedshiftService redshift;
    private CloudWatchMetricsService cloudWatch;

    @BeforeEach
    void setUp() {
        redshift = mock(RedshiftService.class);
        cloudWatch = mock(CloudWatchMetricsService.class);
    }

    private static RedshiftMetricTarget target(String accountId, String identifier) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(identifier);
        cluster.setClusterStatus("available");
        return new RedshiftMetricTarget(accountId, REGION, cluster, "proxy-for-" + identifier);
    }

    private static MetricDatum sample(String identifier, long timestamp) {
        return MetricDatum.of("HealthStatus", "Count", 1, List.of(new Dimension("ClusterIdentifier", identifier)),
                timestamp);
    }

    private RedshiftMetricsPublisher publisher(RedshiftMetricCollector... collectors) {
        return new RedshiftMetricsPublisher(redshift, cloudWatch, List.of(collectors), true);
    }

    @Test
    void publishWritesCollectorSamplesStampedWithTheEndedMinute() {
        when(redshift.availableMetricTargets()).thenReturn(List.of(target("111111111111", "warehouse")));
        List<Long> stamps = new ArrayList<>();
        RedshiftMetricCollector collector = (target, timestamp) -> {
            stamps.add(timestamp);
            return List.of(sample(target.cluster().getClusterIdentifier(), timestamp));
        };

        publisher(collector).publish(Instant.parse("2026-10-27T12:00:20Z"));

        long endedMinute = Instant.parse("2026-10-27T11:59:00Z").getEpochSecond();
        assertEquals(List.of(endedMinute), stamps);
        ArgumentCaptor<List<MetricDatum>> written = ArgumentCaptor.forClass(List.class);
        verify(cloudWatch).putMetricDataForAccount(eq("111111111111"), eq(NAMESPACE), written.capture(), eq(REGION));
        assertEquals(1, written.getValue().size());
        assertEquals("HealthStatus", written.getValue().getFirst().getMetricName());
        assertEquals(endedMinute, written.getValue().getFirst().getTimestamp());
    }

    @Test
    void overdueTicksPublishEachMinuteOnlyOnce() {
        when(redshift.availableMetricTargets()).thenReturn(List.of(target("111111111111", "warehouse")));
        RedshiftMetricsPublisher publisher = publisher((target, timestamp) -> List.of(sample("warehouse", timestamp)));

        publisher.publish(Instant.parse("2026-10-27T12:04:01Z"));
        publisher.publish(Instant.parse("2026-10-27T12:04:02Z"));
        publisher.publish(Instant.parse("2026-10-27T12:04:20Z"));
        publisher.publish(Instant.parse("2026-10-27T12:05:01Z"));

        ArgumentCaptor<List<MetricDatum>> written = ArgumentCaptor.forClass(List.class);
        verify(cloudWatch, times(2)).putMetricDataForAccount(eq("111111111111"), eq(NAMESPACE),
                written.capture(), eq(REGION));
        assertEquals(List.of(Instant.parse("2026-10-27T12:03:00Z").getEpochSecond(),
                        Instant.parse("2026-10-27T12:04:00Z").getEpochSecond()),
                written.getAllValues().stream().map(datums -> datums.getFirst().getTimestamp()).toList());
        verify(redshift, times(2)).availableMetricTargets();
    }

    @Test
    void clockMovingBackwardsDoesNotRepublishAnEarlierMinute() {
        when(redshift.availableMetricTargets()).thenReturn(List.of(target("111111111111", "warehouse")));
        RedshiftMetricsPublisher publisher = publisher((target, timestamp) -> List.of(sample("warehouse", timestamp)));

        publisher.publish(Instant.parse("2026-10-27T12:04:01Z"));
        publisher.publish(Instant.parse("2026-10-27T12:03:01Z"));
        publisher.publish(Instant.parse("2026-10-27T12:04:01Z"));

        verify(cloudWatch).putMetricDataForAccount(eq("111111111111"), eq(NAMESPACE), anyList(), eq(REGION));
    }

    @Test
    void aFailedWriteIsNotRetriedWithinTheMinute() {
        when(redshift.availableMetricTargets()).thenReturn(List.of(target("111111111111", "warehouse")));
        doThrow(new IllegalStateException("write may have stored some samples")).when(cloudWatch)
                .putMetricDataForAccount(anyString(), anyString(), anyList(), anyString());
        RedshiftMetricsPublisher publisher = publisher((target, timestamp) -> List.of(sample("warehouse", timestamp)));

        publisher.publish(Instant.parse("2026-10-27T12:04:01Z"));
        publisher.publish(Instant.parse("2026-10-27T12:04:02Z"));

        verify(cloudWatch).putMetricDataForAccount(eq("111111111111"), eq(NAMESPACE), anyList(), eq(REGION));
    }

    @Test
    void aThrowingCollectorDropsOnlyThatClustersSamples() {
        when(redshift.availableMetricTargets()).thenReturn(List.of(
                target("111111111111", "broken"), target("111111111111", "healthy")));
        RedshiftMetricCollector collector = (target, timestamp) -> {
            if ("broken".equals(target.cluster().getClusterIdentifier())) {
                throw new IllegalStateException("collector failed");
            }
            return List.of(sample(target.cluster().getClusterIdentifier(), timestamp));
        };

        assertDoesNotThrow(() -> publisher(collector).publish(Instant.parse("2026-10-27T12:00:20Z")));

        ArgumentCaptor<List<MetricDatum>> written = ArgumentCaptor.forClass(List.class);
        verify(cloudWatch).putMetricDataForAccount(eq("111111111111"), eq(NAMESPACE), written.capture(), eq(REGION));
        assertEquals("healthy", written.getValue().getFirst().getDimensions().getFirst().value());
    }

    @Test
    void aFailedWriteForOneClusterDoesNotStopTheNext() {
        when(redshift.availableMetricTargets()).thenReturn(List.of(
                target("111111111111", "first"), target("222222222222", "second")));
        doThrow(new IllegalStateException("store failed")).when(cloudWatch)
                .putMetricDataForAccount(eq("111111111111"), anyString(), anyList(), anyString());

        assertDoesNotThrow(() -> publisher((target, timestamp) -> List.of(sample("x", timestamp)))
                .publish(Instant.parse("2026-10-27T12:00:20Z")));

        verify(cloudWatch).putMetricDataForAccount(eq("222222222222"), eq(NAMESPACE), anyList(), eq(REGION));
    }

    @Test
    void sameClusterIdentifierInTwoAccountsLandsInEachAccount() {
        when(redshift.availableMetricTargets()).thenReturn(List.of(
                target("111111111111", "warehouse"), target("222222222222", "warehouse")));

        publisher((target, timestamp) -> List.of(sample("warehouse", timestamp)))
                .publish(Instant.parse("2026-10-27T12:00:20Z"));

        verify(cloudWatch).putMetricDataForAccount(eq("111111111111"), eq(NAMESPACE), anyList(), eq(REGION));
        verify(cloudWatch).putMetricDataForAccount(eq("222222222222"), eq(NAMESPACE), anyList(), eq(REGION));
    }

    @Test
    void aClusterWithNoSamplesWritesNothing() {
        when(redshift.availableMetricTargets()).thenReturn(List.of(target("111111111111", "warehouse")));

        publisher((target, timestamp) -> List.of()).publish(Instant.parse("2026-10-27T12:00:20Z"));

        verify(cloudWatch, never()).putMetricDataForAccount(anyString(), anyString(), anyList(), anyString());
    }

    @Test
    void enumerationFailureDropsTheTickWithoutThrowing() {
        when(redshift.availableMetricTargets()).thenThrow(new IllegalStateException("storage unavailable"));

        assertDoesNotThrow(() -> publisher((target, timestamp) -> List.of(sample("x", timestamp)))
                .publish(Instant.parse("2026-10-27T12:00:20Z")));

        verify(cloudWatch, never()).putMetricDataForAccount(anyString(), anyString(), anyList(), anyString());
    }

    @Test
    void publishDropsDatapointsOlderThanFifteenDaysOnTheHour() {
        when(redshift.availableMetricTargets()).thenReturn(List.of());
        RedshiftMetricsPublisher publisher = publisher();

        publisher.publish(Instant.parse("2026-10-27T12:01:00Z"));

        Instant hour = Instant.parse("2026-10-27T12:00:00Z");
        verify(cloudWatch).deleteMetricDataBefore(NAMESPACE, hour.minus(Duration.ofDays(15)));
    }

    @Test
    void publishKeepsHistoryBetweenHours() {
        when(redshift.availableMetricTargets()).thenReturn(List.of());

        publisher().publish(Instant.parse("2026-10-27T12:30:20Z"));

        verify(cloudWatch, never()).deleteMetricDataBefore(anyString(), any());
    }

    @Test
    void isDisabledWhenTheFlagOrCloudwatchMetricsIsOff() {
        assertTrue(RedshiftMetricsPublisher.isEnabled(config(true, true, true)));
        assertFalse(RedshiftMetricsPublisher.isEnabled(config(true, false, true)));
        assertFalse(RedshiftMetricsPublisher.isEnabled(config(true, true, false)));
        assertFalse(RedshiftMetricsPublisher.isEnabled(config(false, true, true)));
    }

    private static EmulatorConfig config(boolean redshiftEnabled, boolean flag, boolean cloudwatchEnabled) {
        EmulatorConfig config = mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS);
        when(config.services().redshift().enabled()).thenReturn(redshiftEnabled);
        when(config.services().redshift().cloudwatchMetricsEnabled()).thenReturn(flag);
        when(config.services().cloudwatchmetrics().enabled()).thenReturn(cloudwatchEnabled);
        return config;
    }
}
