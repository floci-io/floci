package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Injects failures around real backend writes, not mock invocation counts. */
class CloudWatchMetricPublicationStorageFaultTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "eu-west-1";
    private static final TypeReference<Map<String, MetricDatum>> TYPE = new TypeReference<>() {};
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"memory,false", "memory,true", "persistent,false", "persistent,true",
            "hybrid,false", "hybrid,true", "wal,false", "wal,true"})
    void beforeAndAfterPartialCommitRemainSingleSamplesInEveryBackend(String mode, boolean after) {
        StorageBackend<String, MetricDatum> actual = backend(mode);
        AtomicBoolean armed = new AtomicBoolean(true);
        AccountAwareStorageBackend<MetricDatum> faulting = new AccountAwareStorageBackend<>(actual, null, ACCOUNT) {
            @Override
            public void putForAccount(String account, String key, MetricDatum value) {
                boolean fail = value.getValue() == 7 && armed.compareAndSet(true, false);
                if (fail && !after) { throw new IllegalStateException("before actual backend write"); }
                super.putForAccount(account, key, value);
                if (fail) { throw new IllegalStateException("after actual backend write"); }
            }
        };
        try {
            actual.load();
            CloudWatchMetricsService metrics = metrics(faulting);
            assertThrows(IllegalStateException.class, () -> batch(metrics));
            assertEquals(after ? 2 : 1, actual.keys().size(), "the fault surrounds the second actual write");
            batch(metrics);
            batch(metrics);
            statistics(metrics);
            assertEquals(3, actual.keys().size());
            actual.flush();
        } finally { close(actual); }
        if (!mode.equals("memory")) {
            StorageBackend<String, MetricDatum> reloaded = backend(mode);
            try {
                reloaded.load();
                statistics(metrics(new AccountAwareStorageBackend<>(reloaded, null, ACCOUNT)));
                assertEquals(3, reloaded.keys().size());
            } finally { close(reloaded); }
        }
    }

    private StorageBackend<String, MetricDatum> backend(String mode) {
        return switch (mode) {
            case "memory" -> new InMemoryStorage<>();
            case "persistent" -> new PersistentStorage<>(directory.resolve("metrics.json"), TYPE);
            case "hybrid" -> new HybridStorage<>(directory.resolve("metrics.json"), TYPE, 3_600_000);
            case "wal" -> new WalStorage<>(directory.resolve("metrics-snapshot.json"), directory.resolve("metrics.wal"), TYPE, 3_600_000);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private static void close(StorageBackend<String, MetricDatum> backend) {
        if (backend instanceof HybridStorage<?, ?> hybrid) { hybrid.shutdown(); }
        if (backend instanceof WalStorage<?, ?> wal) { wal.shutdown(); }
    }

    private static CloudWatchMetricsService metrics(AccountAwareStorageBackend<MetricDatum> store) {
        return new CloudWatchMetricsService(store, new InMemoryStorage<>(), new RegionResolver(REGION, ACCOUNT));
    }

    private static void batch(CloudWatchMetricsService metrics) {
        double[] values = {3, 7, 3};
        for (int i = 0; i < values.length; i++) {
            MetricDatum datum = new MetricDatum();
            datum.setMetricName("Value"); datum.setValue(values[i]); datum.setTimestamp(120); datum.setUnit("Count");
            metrics.publishMetricForAccount(ACCOUNT, "App", datum, REGION, "stable-" + i);
        }
    }

    private static void statistics(CloudWatchMetricsService metrics) {
        List<CloudWatchMetricsService.Datapoint> points = metrics.getMetricStatistics("App", "Value", List.of(),
                Instant.ofEpochSecond(120), Instant.ofEpochSecond(179), 60, List.of("Sum", "SampleCount"), null, REGION);
        assertEquals(1, points.size());
        assertEquals(13, points.getFirst().sum());
        assertEquals(3, points.getFirst().sampleCount());
    }
}
