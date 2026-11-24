package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudWatchMetricsDatapointCeilingTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "111111111111";
    private static final String NAMESPACE = "App";

    @Test
    void aWritePastTheCeilingDropsTheOldestTenthOfIt() {
        InMemoryStorage<String, MetricDatum> store = new InMemoryStorage<>();
        CloudWatchMetricsService service = service(store, 20);

        for (int minute = 1; minute <= 20; minute++) {
            put(service, minute);
        }
        assertEquals(20, store.keys().size());

        put(service, 21);

        assertEquals(18, store.keys().size());
        assertEquals(List.of(4L, 21L), List.of(oldest(store), newest(store)));

        put(service, 22);
        put(service, 23);

        assertEquals(20, store.keys().size());
        assertEquals(4L, oldest(store));
    }

    @Test
    void aLateDatapointOlderThanEveryStoredOneIsTheOneDropped() {
        InMemoryStorage<String, MetricDatum> store = new InMemoryStorage<>();
        CloudWatchMetricsService service = service(store, 3);
        put(service, 10);
        put(service, 11);
        put(service, 12);

        put(service, 5);

        assertEquals(3, store.keys().size());
        assertEquals(10L, oldest(store));
    }

    @Test
    void theCeilingCountsEachAccountOnItsOwn() {
        AccountAwareStorageBackend<MetricDatum> store = AccountAwareStorageBackend.inMemory(ACCOUNT);
        CloudWatchMetricsService service = new CloudWatchMetricsService(store, new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver(REGION, ACCOUNT), null, 2);
        for (int minute = 1; minute <= 2; minute++) {
            service.putMetricDataForAccount(OTHER_ACCOUNT, NAMESPACE, List.of(datum(minute)), REGION);
        }

        for (int minute = 1; minute <= 3; minute++) {
            service.putMetricData(NAMESPACE, List.of(datum(minute)), REGION);
        }

        assertEquals(List.of(2L, 3L), timestamps(store.scan(key -> true)));
        assertEquals(List.of(1L, 2L), timestamps(store.scanForAccount(OTHER_ACCOUNT, key -> true)));
    }

    @Test
    void aStoreLoadedPastTheCeilingIsCutBackByTheNextWrite() {
        InMemoryStorage<String, MetricDatum> store = new InMemoryStorage<>();
        CloudWatchMetricsService unbounded = new CloudWatchMetricsService(store, new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT));
        for (int minute = 1; minute <= 50; minute++) {
            put(unbounded, minute);
        }

        put(service(store, 20), 51);

        assertEquals(18, store.keys().size());
        assertEquals(List.of(34L, 51L), List.of(oldest(store), newest(store)));
    }

    @Test
    void aPublicationWrittenAgainUnderItsIdIsCountedOnce() {
        InMemoryStorage<String, MetricDatum> store = new InMemoryStorage<>();
        CloudWatchMetricsService service = service(store, 2);
        service.publishMetricForAccount(ACCOUNT, NAMESPACE, datum(1), REGION, "first");

        for (int attempt = 0; attempt < 5; attempt++) {
            service.publishMetricForAccount(ACCOUNT, NAMESPACE, datum(2), REGION, "second");
        }

        assertEquals(List.of(1L, 2L), timestamps(store.scan(key -> true)));
    }

    @Test
    void datapointsDroppedForTheirAgeMakeRoomUnderTheCeiling() {
        InMemoryStorage<String, MetricDatum> store = new InMemoryStorage<>();
        CloudWatchMetricsService service = service(store, 4);
        for (int minute = 1; minute <= 4; minute++) {
            put(service, minute);
        }

        service.deleteMetricDataBefore(NAMESPACE, Instant.ofEpochSecond(3 * 60));
        put(service, 5);
        put(service, 6);

        assertEquals(List.of(3L, 4L, 5L, 6L), timestamps(store.scan(key -> true)));
    }

    @Test
    void aWriteThatFailedAfterReachingTheStoreIsStillCounted() {
        AtomicBoolean failing = new AtomicBoolean(true);
        InMemoryStorage<String, MetricDatum> store = new InMemoryStorage<>() {
            @Override
            public void put(String key, MetricDatum value) {
                super.put(key, value);
                if (failing.compareAndSet(true, false)) {
                    throw new IllegalStateException("after store.put");
                }
            }
        };
        CloudWatchMetricsService service = service(store, 2);
        assertThrows(IllegalStateException.class, () -> put(service, 1));

        put(service, 2);
        put(service, 3);

        assertEquals(List.of(2L, 3L), timestamps(store.scan(key -> true)));
    }

    private static CloudWatchMetricsService service(InMemoryStorage<String, MetricDatum> store, int maxStoredDatapoints) {
        return new CloudWatchMetricsService(store, new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, ACCOUNT), null, maxStoredDatapoints);
    }

    private static void put(CloudWatchMetricsService service, int minute) {
        service.putMetricData(NAMESPACE, List.of(datum(minute)), REGION);
    }

    private static MetricDatum datum(int minute) {
        return MetricDatum.of("Requests", "Count", 1, List.of(), minute * 60L);
    }

    private static long oldest(InMemoryStorage<String, MetricDatum> store) {
        return timestamps(store.scan(key -> true)).getFirst();
    }

    private static long newest(InMemoryStorage<String, MetricDatum> store) {
        return timestamps(store.scan(key -> true)).getLast();
    }

    /** The stored datapoints' minutes, oldest first. */
    private static List<Long> timestamps(List<MetricDatum> datums) {
        return datums.stream().map(datum -> datum.getTimestamp() / 60).sorted().toList();
    }
}
