package io.floci.aws.services.kinesis;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.kinesis.model.KinesisRecord;
import io.floci.aws.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KinesisUpdateShardCountAtomicityTest {

    private static final String REGION = "us-east-1";

    @Test
    void updateShardCountFailureMustNotLeavePartialTopologyOrRollingCount() {
        // UpdateShardCount first publishes the UPDATING status window, then commits the staged
        // topology. Fail the final topology write (create=1, status=2, commit=3).
        FailingPutStorage store = new FailingPutStorage(3);
        KinesisService service = new KinesisService(store, new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"));
        KinesisStream original = service.createStream("my-stream", 2, REGION);

        assertThrows(RuntimeException.class,
                () -> service.updateShardCount("my-stream", 4, "UNIFORM_SCALING", REGION));

        KinesisStream persisted = store.get(REGION + "::my-stream").orElseThrow();
        long openShardCount = persisted.getShards().stream().filter(shard -> !shard.isClosed()).count();
        assertEquals(2, persisted.getShards().size(), "failed update must not publish shard records");
        assertEquals(2, openShardCount, "failed update must leave the original open topology");
        assertEquals(0, persisted.getShardCountUpdateTimestamps().size(),
                "failed update must not consume a rolling update slot");
        assertSame(original, persisted, "a failed update must retain the stored stream identity");
    }

    @Test
    void updateShardCountFailureRestoresStateWhenStoragePublishesBeforeThrowing() {
        FailingPutStorage store = new FailingPutStorage(3, true);
        KinesisService service = new KinesisService(store, new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"));
        KinesisStream original = service.createStream("my-stream", 2, REGION);

        assertThrows(RuntimeException.class,
                () -> service.updateShardCount("my-stream", 4, "UNIFORM_SCALING", REGION));

        KinesisStream persisted = store.get(REGION + "::my-stream").orElseThrow();
        assertEquals(2, persisted.getShards().size(), "rollback must restore the published shard list");
        assertEquals(2, persisted.getShards().stream().filter(shard -> !shard.isClosed()).count(),
                "rollback must restore the original open topology");
        assertEquals(0, persisted.getShardCountUpdateTimestamps().size(),
                "rollback must restore the original rolling update history");
        assertSame(original, persisted, "rollback must retain the stored stream identity");
    }

    @Test
    void updateShardCountFailureDoesNotReplaceRetainedRecords() {
        FailingPutStorage store = new FailingPutStorage(4, true);
        KinesisService service = new KinesisService(store, new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"));
        KinesisStream original = service.createStream("my-stream", 2, REGION);
        service.putRecord("my-stream", new byte[]{1, 2, 3}, "partition", REGION);
        KinesisRecord retained = original.getShards().getFirst().getRecords().getFirst();

        assertThrows(RuntimeException.class,
                () -> service.updateShardCount("my-stream", 4, "UNIFORM_SCALING", REGION));

        assertSame(retained, original.getShards().getFirst().getRecords().getFirst(),
                "rollback must preserve retained record identity");
    }

    @Test
    void successfulUpdateKeepsReturnedStreamReferenceCurrent() {
        StorageBackend<String, KinesisStream> store = new InMemoryStorage<>();
        KinesisService service = new KinesisService(store, new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"));
        KinesisStream original = service.createStream("my-stream", 2, REGION);

        service.updateShardCount("my-stream", 4, "UNIFORM_SCALING", REGION);

        assertSame(original, service.describeStream("my-stream", REGION));
        assertEquals(4, original.getShards().stream().filter(shard -> !shard.isClosed()).count());
    }

    @Test
    void updateShardCountUsesTheServiceClockForItsRollingWindow() {
        Instant now = Instant.parse("2026-01-02T03:04:05Z");
        KinesisService service = new KinesisService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"), Clock.fixed(now, Clock.systemUTC().getZone()));
        service.createStream("my-stream", 2, REGION);

        service.updateShardCount("my-stream", 4, "UNIFORM_SCALING", REGION);

        KinesisStream stream = service.describeStream("my-stream", REGION);
        assertEquals(List.of(now), stream.getShardCountUpdateTimestamps());
    }

    private static final class FailingPutStorage implements StorageBackend<String, KinesisStream> {
        private final StorageBackend<String, KinesisStream> delegate = new InMemoryStorage<>();
        private final int failOnPut;
        private final boolean publishBeforeFailure;
        private int putCount;

        private FailingPutStorage(int failOnPut) {
            this(failOnPut, false);
        }

        private FailingPutStorage(int failOnPut, boolean publishBeforeFailure) {
            this.failOnPut = failOnPut;
            this.publishBeforeFailure = publishBeforeFailure;
        }

        @Override
        public void put(String key, KinesisStream value) {
            putCount++;
            if (putCount == failOnPut) {
                if (publishBeforeFailure) {
                    delegate.put(key, value);
                }
                throw new RuntimeException("injected store.put failure");
            }
            delegate.put(key, value);
        }

        @Override
        public Optional<KinesisStream> get(String key) {
            return delegate.get(key);
        }

        @Override
        public void delete(String key) {
            delegate.delete(key);
        }

        @Override
        public List<KinesisStream> scan(Predicate<String> keyFilter) {
            return new ArrayList<>(delegate.scan(keyFilter));
        }

        @Override
        public Set<String> keys() {
            return delegate.keys();
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public void load() {
            delegate.load();
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }
}
