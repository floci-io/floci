package io.github.hectorvent.floci.services.kinesis;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.kinesis.model.KinesisRecord;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent producers must not lose records across a WAL persist/reload cycle.
 *
 * <p>Serialize-order must equal append-order must equal WAL-write-order per stream key, so a
 * process restart replays every record in sequence order. See issue #571 (Kinesis append race).
 */
class KinesisWalPersistenceTest {

    private static final String REGION = "us-east-1";

    @TempDir
    Path tmp;

    @Test
    void concurrentPutRecords_survive_wal_reload() throws Exception {
        Path snapshot = tmp.resolve("kinesis-streams.json");
        Path wal = tmp.resolve("kinesis-streams.wal");
        RegionResolver rr = new RegionResolver("us-east-1", "000000000000");

        int threads = 16;
        int opsPerThread = 50;
        int expected = threads * opsPerThread; // 800
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);

        // Large compaction interval so replay (not snapshot) is what proves durability.
        WalStorage<String, KinesisStream> store1 = new WalStorage<>(
                snapshot, wal, new TypeReference<Map<String, KinesisStream>>() {}, 3_600_000L);
        WalStorage<String, KinesisStream> store2 = null;
        try {
            store1.load(); // opens the WAL writer: appends before load() are silent no-ops
            KinesisService writer = new KinesisService(store1, new InMemoryStorage<>(), rr);
            writer.createStream("wal-stream", 1, REGION);

            List<Throwable> errors = runConcurrently(threads, opsPerThread,
                    () -> writer.putRecord("wal-stream", data, "pk", REGION));
            assertTrue(errors.isEmpty(), () -> "unexpected errors: " + errors);

            // Copy the still-uncompacted WAL (and snapshot, if one exists) aside for replay. Each
            // appendPut flushes to the file, so the copy holds every entry. Reloading from the copy
            // lets us shut store1 down cleanly in the finally: shutdown() compacts and truncates
            // store1's own WAL, but that no longer touches the files the reader replays.
            Path snapshot2 = tmp.resolve("reload-streams.json");
            Path wal2 = tmp.resolve("reload-streams.wal");
            Files.copy(wal, wal2, StandardCopyOption.REPLACE_EXISTING);
            if (Files.exists(snapshot)) {
                Files.copy(snapshot, snapshot2, StandardCopyOption.REPLACE_EXISTING);
            }

            // Fresh instance over the copied files: load() replays the WAL.
            store2 = new WalStorage<>(
                    snapshot2, wal2, new TypeReference<Map<String, KinesisStream>>() {}, 3_600_000L);
            store2.load();
            KinesisService reader = new KinesisService(store2, new InMemoryStorage<>(), rr);

            List<KinesisRecord> all = drain(reader, "wal-stream");
            assertEquals(expected, all.size(), "every record must survive the WAL reload");

            Set<String> distinct = new HashSet<>();
            all.forEach(r -> distinct.add(r.getSequenceNumber()));
            assertEquals(expected, distinct.size(), "every reloaded record must carry a distinct sequence");

            long previous = Long.MIN_VALUE;
            for (KinesisRecord r : all) {
                long current = Long.parseLong(r.getSequenceNumber());
                assertTrue(current > previous, "reloaded records must be in strictly increasing sequence order");
                previous = current;
            }
        } finally {
            // Shut down both stores so neither leaves a compaction scheduler or open WAL writer.
            if (store2 != null) {
                store2.shutdown();
            }
            store1.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private List<KinesisRecord> drain(KinesisService service, String streamName) {
        String shardId = service.describeStream(streamName, REGION).getShards().getFirst().getShardId();
        String iterator = service.getShardIterator(streamName, shardId, "TRIM_HORIZON", null, REGION);
        List<KinesisRecord> all = new ArrayList<>();
        while (true) {
            Map<String, Object> page = service.getRecords(iterator, 1000, REGION);
            List<KinesisRecord> records = (List<KinesisRecord>) page.get("Records");
            if (records.isEmpty()) {
                break;
            }
            all.addAll(records);
            iterator = (String) page.get("NextShardIterator");
        }
        return all;
    }

    private List<Throwable> runConcurrently(int threadCount, int opsPerThread, Runnable op)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int t = 0; t < threadCount; t++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            op.run();
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    } finally {
                        doneGate.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(doneGate.await(60, TimeUnit.SECONDS), "producers did not complete within 60s");
            return errors;
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "thread pool did not terminate");
        }
    }
}
