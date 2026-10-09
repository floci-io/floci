package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService.Datapoint;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.exec.EcsExecSessionRegistry;
import io.github.hectorvent.floci.services.ecs.model.ClusterSetting;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
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

    private CloudWatchMetricsService metrics;
    private EcsService service;

    @BeforeEach
    void setUp() {
        InMemoryStorageFactory storage = new InMemoryStorageFactory();
        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        metrics = new CloudWatchMetricsService(storage, regionResolver);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        service = new EcsService(regionResolver, mock(EcsContainerManager.class), config,
                mock(EcsLoadBalancerRegistrar.class), storage, null, new EcsExecSessionRegistry(), null, metrics);
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
        Instant now = Instant.now();
        return metrics.getMetricStatistics(NAMESPACE, metricName,
                List.of(new Dimension("ServiceName", "web"), new Dimension("ClusterName", "insights")),
                now.minusSeconds(600), now.plusSeconds(600), 60, List.of("Maximum"), null, REGION);
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName,
                    ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
