package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService.Datapoint;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.exec.EcsExecSessionRegistry;
import io.github.hectorvent.floci.services.ecs.model.ClusterSetting;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Container Insights task counts, published by the service reconciler into a real CloudWatch
 * metric store: {@code ECS/ContainerInsights} on {@code ClusterName} and {@code ServiceName}, once
 * per minute, only for a cluster whose containerInsights setting turns it on and only while the
 * service has a RUNNING task.
 */
class EcsServiceContainerInsightsTest {

    private static final String REGION = "us-east-1";
    private static final String NAMESPACE = "ECS/ContainerInsights";
    private static final Instant MINUTE = Instant.parse("2026-10-20T12:00:00Z");

    private CloudWatchMetricsService metrics;
    private EcsService service;

    @BeforeEach
    void setUp() {
        InMemoryStorageFactory storage = new InMemoryStorageFactory("000000000000");
        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        metrics = spy(new CloudWatchMetricsService(storage, regionResolver));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        service = new EcsService(regionResolver, mock(EcsContainerManager.class), config,
                mock(EcsLoadBalancerRegistrar.class), storage, null, new EcsExecSessionRegistry(), null, metrics,
                Clock.fixed(MINUTE.plusSeconds(42), ZoneOffset.UTC));
        service.initializeStorage();
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage("nginx:alpine");
        service.registerTaskDefinition("ci-fam", List.of(cd), null, null, null, null, null, List.of(), REGION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"enabled", "enhanced"})
    void anInsightsClusterPublishesItsServiceTaskCounts(String mode) {
        startService(List.of(new ClusterSetting("containerInsights", mode)), 1);

        service.reconcile();

        Datapoint running = only("RunningTaskCount");
        assertEquals(MINUTE, running.timestamp());
        assertEquals(1.0, running.maximum());
        assertEquals("Count", running.unit());
        assertEquals(0.0, only("PendingTaskCount").maximum());
        assertEquals(1.0, only("DesiredTaskCount").maximum());
    }

    @Test
    void repeatedTicksKeepOneSamplePerMinute() {
        startService(List.of(new ClusterSetting("containerInsights", "enabled")), 1);

        service.reconcile();
        service.reconcile();

        List<Datapoint> points = statistics("RunningTaskCount");
        assertFalse(points.isEmpty());
        assertTrue(points.stream().allMatch(p -> p.sampleCount() == 1.0), points.toString());
    }

    @Test
    void anUnchangedSampleIsNotWrittenAgainWithinTheMinute() {
        startService(List.of(new ClusterSetting("containerInsights", "enabled")), 1);

        service.reconcile();
        service.reconcile();

        verify(metrics, times(3)).publishMetricForAccount(anyString(), eq(NAMESPACE), any(), eq(REGION), anyString());
    }

    @Test
    void aWriteThatFailsPartwayIsRepeatedEvenWhenTheCountsReturn() {
        startService(List.of(new ClusterSetting("containerInsights", "enabled")), 1);
        service.reconcile();
        service.updateService("insights", "web", null, 2, null, REGION);
        // One of the three counts lands, the next write fails.
        doCallRealMethod().doThrow(new IllegalStateException("disk full")).doCallRealMethod().when(metrics)
                .publishMetricForAccount(anyString(), eq(NAMESPACE), any(), eq(REGION), anyString());
        service.reconcile();

        service.updateService("insights", "web", null, 1, null, REGION);
        clearInvocations(metrics);
        service.reconcile();

        verify(metrics, times(3)).publishMetricForAccount(anyString(), eq(NAMESPACE), any(), eq(REGION), anyString());
    }

    @Test
    void aScaleInIsCountedInTheTickThatStopsTheTask() {
        startService(List.of(new ClusterSetting("containerInsights", "enabled")), 2);
        service.reconcile();

        service.updateService("insights", "web", null, 1, null, REGION);
        service.reconcile();

        assertEquals(1.0, statistics("RunningTaskCount").getLast().maximum());
        assertEquals(1.0, statistics("DesiredTaskCount").getLast().maximum());
    }

    @Test
    void aClusterWithoutInsightsPublishesNothing() {
        startService(List.of(new ClusterSetting("containerInsights", "disabled")), 1);
        service.createCluster("plain", REGION);
        service.createService("plain", "web", "ci-fam", 1, LaunchType.FARGATE, List.of(), null, REGION);

        service.reconcile();

        assertTrue(metrics.listMetrics(NAMESPACE, null, null, REGION).isEmpty());
    }

    @Test
    void aServiceWithoutARunningTaskPublishesNothing() {
        startService(List.of(new ClusterSetting("containerInsights", "enabled")), 0);

        service.reconcile();

        assertTrue(metrics.listMetrics(NAMESPACE, null, null, REGION).isEmpty());
    }

    private void startService(List<ClusterSetting> settings, int desiredCount) {
        service.createCluster("insights", null, settings, REGION);
        service.createService("insights", "web", "ci-fam", desiredCount, LaunchType.FARGATE, List.of(), null, REGION);
    }

    private Datapoint only(String metricName) {
        List<Datapoint> points = statistics(metricName);
        assertEquals(1, points.size(), metricName + ": " + points);
        return points.get(0);
    }

    private List<Datapoint> statistics(String metricName) {
        return metrics.getMetricStatistics(NAMESPACE, metricName,
                List.of(new Dimension("ServiceName", "web"), new Dimension("ClusterName", "insights")),
                MINUTE.minusSeconds(600), MINUTE.plusSeconds(600), 60, List.of("Maximum"), null, REGION);
    }
}
