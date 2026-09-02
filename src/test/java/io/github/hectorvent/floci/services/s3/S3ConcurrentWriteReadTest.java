package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * In {@code hybrid}/filesystem storage mode (the default) an S3 object's bytes live on disk.
 * LZA's Bootstrap stage fans out ~15 CodeBuild actions that each redundantly re-upload the
 * same runtime-artifact keys ({@code codepipeline/<execId>/Source.zip}, {@code .../Config.zip})
 * while other builds concurrently download those same keys to stage their source. A writer that
 * truncates-then-writes a file is not atomic against a concurrent reader, so the reader can
 * observe an empty or partial object — which surfaced downstream as an empty {@code src-Config}
 * secondary source and {@code ENOENT: global-config.yaml}. getObject must therefore never return
 * a torn view of a key that always holds the same full-size payload.
 */
class S3ConcurrentWriteReadTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentOverwriteNeverYieldsTornRead() throws Exception {
        S3Service s3 = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(),
                tempDir.resolve("s3"), false);
        String bucket = "codepipeline-artifacts";
        String key = "codepipeline/exec-1/Config.zip";
        s3.createBucket(bucket, "us-east-1");

        // A sizable payload widens the truncate-then-write window so a torn read is observable.
        byte[] payload = new byte[1 << 20];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31 + 7);
        }
        // Seed the key so readers never race a not-yet-created object (that is a separate concern).
        s3.putObject(bucket, key, payload, "application/zip", Map.of());

        int writers = 4;
        int readers = 4;
        int iterations = 400;
        AtomicReference<String> firstTear = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        volatileDone done = new volatileDone();

        Thread[] threads = new Thread[writers + readers];
        for (int w = 0; w < writers; w++) {
            threads[w] = new Thread(() -> {
                for (int i = 0; i < iterations && firstTear.get() == null; i++) {
                    s3.putObject(bucket, key, payload, "application/zip", Map.of());
                }
                done.count.incrementAndGet();
            });
        }
        for (int r = 0; r < readers; r++) {
            threads[writers + r] = new Thread(() -> {
                while (done.count.get() < writers && firstTear.get() == null) {
                    S3Object obj = s3.getObject(bucket, key);
                    byte[] data = obj.getData();
                    reads.incrementAndGet();
                    if (data == null || data.length != payload.length) {
                        firstTear.compareAndSet(null,
                                "torn read: length=" + (data == null ? "null" : data.length)
                                        + " expected=" + payload.length);
                        return;
                    }
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) {
            t.join(30_000);
            assertFalse(t.isAlive(), "thread did not finish within the join timeout");
        }

        assertNull(firstTear.get(), firstTear.get());
        // Guard against a vacuous pass: the readers must actually have run.
        assertEquals(true, reads.get() > 0, "reader threads never observed the object");
    }

    /**
     * Regression test for torn GET responses under same-key overwrites: metadata (size, ETag,
     * checksums — the source of {@code Content-Length} and {@code x-amz-checksum-*} response
     * headers) and body bytes must always come from the same version. Writers install metadata
     * and data as two separate stores under the bucket monitor, so a getObject that reads the
     * pair without that monitor can return version N's metadata with version N+1's bytes; on the
     * wire that surfaces as a body longer than the declared Content-Length, whose surplus bytes
     * corrupt the keep-alive connection's framing. AWS S3 reads are atomic: one complete version,
     * never a mix.
     */
    @Test
    void concurrentOverwriteNeverPairsMetadataWithAnotherVersionsBody() throws Exception {
        S3Service s3 = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(),
                tempDir.resolve("s3"), false);
        String bucket = "torn-read-repro";
        String key = "torn-probe";
        s3.createBucket(bucket, "us-east-1");

        byte[] bodyA = new byte[256];
        Arrays.fill(bodyA, (byte) 'a');
        byte[] bodyB = new byte[300];
        Arrays.fill(bodyB, (byte) 'b');
        String eTagA = new S3Object(bucket, key, bodyA, null).getETag();
        String eTagB = new S3Object(bucket, key, bodyB, null).getETag();
        s3.putObject(bucket, key, bodyA, "application/octet-stream", Map.of());

        int writers = 2;
        int readers = 4;
        int iterations = 4000;
        AtomicReference<String> firstTear = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger();
        volatileDone done = new volatileDone();

        Thread[] threads = new Thread[writers + readers];
        for (int w = 0; w < writers; w++) {
            threads[w] = new Thread(() -> {
                for (int i = 0; i < iterations && firstTear.get() == null; i++) {
                    s3.putObject(bucket, key, i % 2 == 0 ? bodyA : bodyB,
                            "application/octet-stream", Map.of());
                }
                done.count.incrementAndGet();
            });
        }
        for (int r = 0; r < readers; r++) {
            threads[writers + r] = new Thread(() -> {
                while (done.count.get() < writers && firstTear.get() == null) {
                    S3Object obj = s3.getObject(bucket, key);
                    byte[] data = obj.getData();
                    reads.incrementAndGet();
                    String tear = null;
                    if (data == null || (!Arrays.equals(data, bodyA) && !Arrays.equals(data, bodyB))) {
                        tear = "body is neither version: length="
                                + (data == null ? "null" : data.length);
                    } else if (obj.getSize() != data.length) {
                        tear = "declared size=" + obj.getSize() + " but body has " + data.length
                                + " bytes (Content-Length/body mismatch)";
                    } else if (!obj.getETag().equals(data.length == bodyA.length ? eTagA : eTagB)) {
                        tear = "ETag " + obj.getETag() + " does not match the returned "
                                + data.length + "-byte body";
                    }
                    if (tear != null) {
                        firstTear.compareAndSet(null, tear);
                        return;
                    }
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) {
            t.join(60_000);
            assertFalse(t.isAlive(), "thread did not finish within the join timeout");
        }

        assertNull(firstTear.get(), firstTear.get());
        assertEquals(true, reads.get() > 0, "reader threads never observed the object");
    }

    /** Tiny holder so the reader loop can see writers finishing without a shared executor. */
    private static final class volatileDone {
        final AtomicInteger count = new AtomicInteger();
    }
}
