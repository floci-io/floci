package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchMetricPublicationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialStorageFailureBeforeOrAfterWriteIsIdempotentWithStableIds(boolean after) {
        AtomicBoolean armed = new AtomicBoolean(true);
        List<String> secondKeys = new ArrayList<>();
        InMemoryStorage<String, MetricDatum> store = new InMemoryStorage<>() {
            @Override
            public void put(String key, MetricDatum value) {
                if (value.getValue() == 7) { secondKeys.add(key); }
                boolean fail = value.getValue() == 7 && armed.compareAndSet(true, false);
                if (fail && !after) { throw new IllegalStateException("before store.put"); }
                super.put(key, value);
                if (fail) { throw new IllegalStateException("after store.put"); }
            }
        };
        CloudWatchMetricsService service = service(store);
        assertThrows(IllegalStateException.class, () -> publishBatch(service));
        publishBatch(service);
        publishBatch(service);
        List<CloudWatchMetricsService.Datapoint> points = service.getMetricStatistics("App", "Value", List.of(),
                Instant.ofEpochSecond(120), Instant.ofEpochSecond(179), 60, List.of("Sum", "SampleCount"), null, "eu-west-1");
        assertEquals(1, points.size());
        assertEquals(13, points.getFirst().sum());
        assertEquals(3, points.getFirst().sampleCount());
        assertEquals(1, secondKeys.stream().distinct().count(), "ambiguous writes must reuse the exact storage key");
    }

    private static void publishBatch(CloudWatchMetricsService service) {
        double[] values = {3, 7, 3};
        for (int i = 0; i < values.length; i++) {
            service.publishMetricForAccount("111111111111", "App", datum(values[i], 120), "eu-west-1", "batch-" + i);
        }
    }

    @Test
    void ordinaryPutMetricDataStillAppendsIdenticalInputsAndSuppliesMissingTime() {
        CloudWatchMetricsService service = service(new InMemoryStorage<>());
        service.putMetricDataForAccount("111111111111", "App", List.of(datum(7, 120)), "eu-west-1");
        service.putMetricDataForAccount("111111111111", "App", List.of(datum(7, 120)), "eu-west-1");
        List<CloudWatchMetricsService.Datapoint> points = service.getMetricStatistics("App", "Value", List.of(),
                Instant.ofEpochSecond(120), Instant.ofEpochSecond(179), 60, List.of("Sum"), null, "eu-west-1");
        assertEquals(14, points.getFirst().sum());
        assertEquals(2, points.getFirst().sampleCount());
        MetricDatum missingTime = datum(1, 0);
        service.putMetricData("App", List.of(missingTime), "eu-west-1");
        assertTrue(missingTime.getTimestamp() > 0);
    }

    @Test
    void internalPublicationPreservesExplicitEpochZeroAndDoesNotMutateCallerDatum() {
        CloudWatchMetricsService service = service(new InMemoryStorage<>());
        MetricDatum input = datum(7, 0);
        input.setDimensions(new ArrayList<>(List.of(new Dimension("A", "a"))));
        service.publishMetricForAccount("111111111111", "App", input, "eu-west-1", "epoch");
        assertEquals(0, input.getTimestamp());
        assertNull(input.getNamespace());
        input.getDimensions().clear();
        List<CloudWatchMetricsService.Datapoint> points = service.getMetricStatistics("App", "Value",
                List.of(new Dimension("A", "a")), Instant.EPOCH, Instant.ofEpochSecond(59), 60, List.of("Sum"), null, "eu-west-1");
        assertEquals(1, points.size());
        assertEquals(7, points.getFirst().sum());
    }

    private static MetricDatum datum(double value, long timestamp) {
        MetricDatum d = new MetricDatum();
        d.setMetricName("Value"); d.setValue(value); d.setTimestamp(timestamp); d.setUnit("Count");
        return d;
    }

    private static CloudWatchMetricsService service(InMemoryStorage<String, MetricDatum> store) {
        return new CloudWatchMetricsService(new AccountAwareStorageBackend<>(store, null, "111111111111"),
                new InMemoryStorage<>(), new RegionResolver("eu-west-1", "111111111111"));
    }
}
