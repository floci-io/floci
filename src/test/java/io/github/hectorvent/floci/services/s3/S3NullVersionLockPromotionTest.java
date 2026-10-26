package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class S3NullVersionLockPromotionTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"legal-hold", "retention", "acl"})
    void promotionCannotDropAnAcknowledgedMetadataUpdate(String operation) throws Exception {
        InMemoryStorage<String, Bucket> buckets = new InMemoryStorage<>();
        PromotionStore objects = new PromotionStore();
        S3Service service = new S3Service(buckets, objects, tempDir, true);
        service.createBucket("bucket", "us-east-1");
        service.putBucketOwnershipControls("bucket",
                "<OwnershipControls><Rule><ObjectOwnership>BucketOwnerPreferred</ObjectOwnership></Rule></OwnershipControls>");
        service.putObject("bucket", "key", "original".getBytes(StandardCharsets.UTF_8), "text/plain", Map.of());
        service.setBucketObjectLockEnabled("bucket");
        service.putBucketVersioning("bucket", "Enabled");
        String numbered = service.putObject("bucket", "key", "numbered".getBytes(StandardCharsets.UTF_8),
                "text/plain", Map.of()).getVersionId();
        objects.arm(buckets.get("bucket").orElseThrow(), () -> service.deleteObject("bucket", "key", numbered));

        switch (operation) {
            case "legal-hold" -> service.putObjectLegalHold("bucket", "key", "null", "ON");
            case "retention" -> service.putObjectRetention("bucket", "key", "null", "COMPLIANCE",
                    Instant.now().plusSeconds(3600), false);
            case "acl" -> service.putObjectAcl("bucket", "key", "null", "", "public-read",
                    null, null, null, null, null);
            default -> throw new IllegalArgumentException(operation);
        }
        objects.promotion.get(5, TimeUnit.SECONDS);

        assertNull(service.getObject("bucket", "key").getVersionId());
        if ("acl".equals(operation)) {
            assertTrue(service.getObjectAcl("bucket", "key", "null").contains("AllUsers"));
        } else {
            if ("legal-hold".equals(operation)) {
                assertEquals("ON", service.getObjectLegalHold("bucket", "key", "null").getLegalHoldStatus());
            } else {
                assertEquals("COMPLIANCE", service.getObjectRetention("bucket", "key", "null").getObjectLockMode());
            }
            assertEquals("AccessDenied", assertThrows(AwsException.class,
                    () -> service.deleteObject("bucket", "key", "null", true)).getErrorCode());
        }
    }

    private static final class PromotionStore extends InMemoryStorage<String, S3Object> {
        private Bucket bucket;
        private Runnable promote;
        private FutureTask<Void> promotion;

        private void arm(Bucket bucket, Runnable promote) {
            this.bucket = bucket;
            this.promote = promote;
        }

        @Override
        public Optional<S3Object> get(String key) {
            Optional<S3Object> object = super.get(key);
            if (promote != null && "bucket/\0null-version/key".equals(key)) {
                Runnable action = promote;
                promote = null;
                CountDownLatch started = new CountDownLatch(1);
                promotion = new FutureTask<>(() -> {
                    started.countDown();
                    action.run();
                    return null;
                });
                Thread.ofPlatform().start(promotion);
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    if (!Thread.holdsLock(bucket)) {
                        // Expose a stale target if resolution is outside the monitor. With the
                        // monitor held, promotion follows the acknowledged metadata update.
                        promotion.get(5, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } catch (ExecutionException | TimeoutException e) {
                    throw new IllegalStateException(e);
                }
            }
            return object;
        }
    }
}
