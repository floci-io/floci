package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.MultipartUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3MultipartBucketDeletionRaceTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deletionWaitsForInitiationAndRemovesTheNewUpload(boolean inMemory) throws Exception {
        BlockingService service = new BlockingService(tempDir.resolve("initiation"), inMemory);
        service.createBucket("race-bucket", "us-east-1");
        service.blockOwnerLookup = true;
        AtomicReference<MultipartUpload> upload = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread initiator = new Thread(() -> {
            try {
                upload.set(service.initiateMultipartUpload("race-bucket", "object", "application/octet-stream"));
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "multipart-initiator");
        initiator.start();
        assertTrue(service.entered.await(5, TimeUnit.SECONDS));

        Thread deleter = new Thread(() -> {
            try {
                service.deleteBucket("race-bucket");
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "bucket-deleter");
        deleter.start();
        try {
            awaitWaiting(deleter);
        } finally {
            service.release.countDown();
        }
        initiator.join(5_000);
        deleter.join(5_000);
        assertFalse(initiator.isAlive());
        assertFalse(deleter.isAlive());
        assertNull(failure.get());
        assertFalse(service.bucketExists("race-bucket"));

        service.createBucket("race-bucket", "us-east-1");
        AwsException missing = assertThrows(AwsException.class,
                () -> service.getMultipartUpload("race-bucket", "object", upload.get().getUploadId()));
        assertEquals("NoSuchUpload", missing.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deletionWaitsForPartWriteBeforeCleaningItsBackingStore(boolean inMemory) throws Exception {
        BlockingService service = new BlockingService(tempDir.resolve("parts"), inMemory);
        service.createBucket("race-bucket", "us-east-1");
        String uploadId = service.initiateMultipartUpload("race-bucket", "object", "application/octet-stream")
                .getUploadId();
        service.blockUploadLookup = true;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                service.uploadPart("race-bucket", "object", uploadId, 1, new byte[]{1, 2, 3});
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "multipart-writer");
        writer.start();
        assertTrue(service.entered.await(5, TimeUnit.SECONDS));

        Thread deleter = new Thread(() -> {
            try {
                service.deleteBucket("race-bucket");
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "bucket-deleter");
        deleter.start();
        try {
            awaitWaiting(deleter);
        } finally {
            service.release.countDown();
        }
        writer.join(5_000);
        deleter.join(5_000);
        assertFalse(writer.isAlive());
        assertFalse(deleter.isAlive());
        assertNull(failure.get());
        assertFalse(service.bucketExists("race-bucket"));
    }

    @Test
    void multipartInitiationsInTheSameBucketCanProceedConcurrently() throws Exception {
        BlockingService service = new BlockingService(tempDir.resolve("parallel"), true);
        service.createBucket("race-bucket", "us-east-1");
        service.blockOwnerLookup = true;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                service.initiateMultipartUpload("race-bucket", "first", "application/octet-stream");
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "multipart-initiator");
        first.start();
        assertTrue(service.entered.await(5, TimeUnit.SECONDS));

        AtomicReference<MultipartUpload> secondUpload = new AtomicReference<>();
        Thread second = new Thread(() -> {
            try {
                secondUpload.set(service.initiateMultipartUpload("race-bucket", "second", "application/octet-stream"));
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "parallel-initiator");
        second.start();
        try {
            second.join(5_000);
            assertFalse(second.isAlive(), "a multipart initiation should not block another on the same bucket");
            assertNotNull(secondUpload.get());
        } finally {
            service.release.countDown();
        }
        first.join(5_000);
        assertFalse(first.isAlive());
        assertNull(failure.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void abortWaitsForPartWriteOnTheSameUpload(boolean inMemory) throws Exception {
        BlockingService service = new BlockingService(tempDir.resolve("abort"), inMemory);
        service.createBucket("race-bucket", "us-east-1");
        String uploadId = service.initiateMultipartUpload("race-bucket", "object", "application/octet-stream")
                .getUploadId();
        service.blockUploadLookup = true;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                service.uploadPart("race-bucket", "object", uploadId, 1, new byte[]{1, 2, 3});
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "multipart-writer");
        writer.start();
        assertTrue(service.entered.await(5, TimeUnit.SECONDS));

        Thread aborter = new Thread(() -> {
            try {
                service.abortMultipartUpload("race-bucket", "object", uploadId);
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "multipart-aborter");
        aborter.start();
        try {
            awaitWaiting(aborter);
        } finally {
            service.release.countDown();
        }
        writer.join(5_000);
        aborter.join(5_000);
        assertFalse(writer.isAlive());
        assertFalse(aborter.isAlive());
        assertNull(failure.get());
        AwsException missing = assertThrows(AwsException.class,
                () -> service.getMultipartUpload("race-bucket", "object", uploadId));
        assertEquals("NoSuchUpload", missing.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void completionWaitsForPartWriteOnTheSameUpload(boolean inMemory) throws Exception {
        BlockingService service = new BlockingService(tempDir.resolve("completion"), inMemory);
        service.createBucket("race-bucket", "us-east-1");
        String uploadId = service.initiateMultipartUpload("race-bucket", "object", "application/octet-stream")
                .getUploadId();
        service.blockUploadLookup = true;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                service.uploadPart("race-bucket", "object", uploadId, 1, new byte[]{1, 2, 3});
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "multipart-writer");
        writer.start();
        assertTrue(service.entered.await(5, TimeUnit.SECONDS));

        Thread completer = new Thread(() -> {
            try {
                service.completeMultipartUpload("race-bucket", "object", uploadId, List.of(1), null, null);
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "multipart-completer");
        completer.start();
        try {
            awaitWaiting(completer);
        } finally {
            service.release.countDown();
        }
        writer.join(5_000);
        completer.join(5_000);
        assertFalse(writer.isAlive());
        assertFalse(completer.isAlive());
        assertNull(failure.get());
        assertNotNull(service.getObject("race-bucket", "object"));
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && thread.getState() != Thread.State.WAITING) {
            if (!thread.isAlive()) {
                break;
            }
            Thread.sleep(10);
        }
        assertEquals(Thread.State.WAITING, thread.getState(), "operation should wait for the multipart lock");
    }

    private static final class BlockingService extends S3Service {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean blockOwnerLookup;
        private volatile boolean blockUploadLookup;

        private BlockingService(Path dataRoot, boolean inMemory) {
            super(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, inMemory);
        }

        @Override
        public String getBucketOwnerAccountId(String bucketName) {
            String owner = super.getBucketOwnerAccountId(bucketName);
            if (blockOwnerLookup && "multipart-initiator".equals(Thread.currentThread().getName())) {
                pause();
            }
            return owner;
        }

        @Override
        public MultipartUpload getMultipartUpload(String bucket, String key, String uploadId) {
            MultipartUpload upload = super.getMultipartUpload(bucket, key, uploadId);
            if (blockUploadLookup && "multipart-writer".equals(Thread.currentThread().getName())) {
                pause();
            }
            return upload;
        }

        private void pause() {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting for the race test to release the multipart operation");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError("multipart operation interrupted", error);
            }
        }
    }
}
