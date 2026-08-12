package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Before account-scoped object storage, every disk-mode S3 object lived at
 * {@code dataRoot/bucketName/key.s3data}, with no account segment. Upgrading past that change
 * must not lose access to objects already on disk, and — since a legacy file isn't known to
 * belong to any one account, and two accounts can share a bucket name — migrating it must never
 * let one account's write/delete steal or destroy another account's still-unmigrated object.
 */
class S3LegacyDiskLayoutIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void existingObjectWrittenUnderTheLegacyUnscopedPathIsStillReadable() throws Exception {
        Path dataRoot = tempDir.resolve("s3");
        S3Service s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, false);

        s3Service.createBucket("legacy-bucket", "us-east-1");
        byte[] data = "pre-upgrade-content".getBytes(StandardCharsets.UTF_8);
        // Write normally, then relocate to the legacy path to simulate pre-upgrade data.
        s3Service.putObject("legacy-bucket", "legacy-key.txt", data, "text/plain", null);

        Path newLayoutFile = dataRoot.resolve(".accounts").resolve("000000000000").resolve("legacy-bucket").resolve("legacy-key.txt.s3data");
        Path legacyLayoutFile = dataRoot.resolve("legacy-bucket").resolve("legacy-key.txt.s3data");
        assertTrue(Files.exists(newLayoutFile), "test setup: object should initially be written at the new path");

        Files.createDirectories(legacyLayoutFile.getParent());
        Files.move(newLayoutFile, legacyLayoutFile, StandardCopyOption.REPLACE_EXISTING);

        S3Object retrieved = s3Service.getObject("legacy-bucket", "legacy-key.txt");
        assertArrayEquals(data, retrieved.getData(),
                "an object physically stored under the pre-upgrade unscoped layout must still be readable");
    }

    @Test
    void bucketNamedLikeTheDefaultAccountIdDoesNotCollideWithAccountScopedStorage() throws Exception {
        // A 12-digit bucket name is valid on S3 and shares its shape with an account ID: a
        // legacy bucket "000000000000" with key "orders/report.csv" must not collide with
        // account "000000000000"'s own "orders" bucket, key "report.csv".
        Path dataRoot = tempDir.resolve("s3");
        S3Service s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, false);

        String accountIdShapedBucket = "000000000000";
        s3Service.createBucket(accountIdShapedBucket, "us-east-1");
        byte[] legacyData = "legacy-bucket-named-like-an-account-id".getBytes(StandardCharsets.UTF_8);
        s3Service.putObject(accountIdShapedBucket, "orders/report.csv", legacyData, "text/plain", null);

        Path newLayoutFile = dataRoot.resolve(".accounts").resolve("000000000000")
                .resolve(accountIdShapedBucket).resolve("orders/report.csv.s3data");
        Path legacyLayoutFile = dataRoot.resolve(accountIdShapedBucket).resolve("orders/report.csv.s3data");
        Files.createDirectories(legacyLayoutFile.getParent());
        Files.move(newLayoutFile, legacyLayoutFile, StandardCopyOption.REPLACE_EXISTING);

        // The default account's own, unrelated "orders" bucket and "report.csv" key.
        s3Service.createBucket("orders", "us-east-1");
        byte[] newData = "unrelated-object-in-the-orders-bucket".getBytes(StandardCharsets.UTF_8);
        s3Service.putObject("orders", "report.csv", newData, "text/plain", null);

        assertArrayEquals(newData, s3Service.getObject("orders", "report.csv").getData(),
                "the unrelated new-layout object must not be shadowed by the account-ID-shaped legacy bucket");
        assertArrayEquals(legacyData, s3Service.getObject(accountIdShapedBucket, "orders/report.csv").getData(),
                "the legacy bucket named like an account ID must still resolve to its own, distinct content");
    }

    @Test
    void bucketNamedLikeTheReservedAccountStorageRootIsRejected() {
        Path dataRoot = tempDir.resolve("s3");
        S3Service s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, false);

        assertThrows(io.github.hectorvent.floci.core.common.AwsException.class,
                () -> s3Service.createBucket(".accounts", "us-east-1"),
                "a bucket named exactly like the reserved account-storage root must be rejected, "
                        + "since Floci doesn't otherwise validate bucket name format at all");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void migrationFailureIsNotPermanentAndDoesNotLeaveAFileBehind() throws Exception {
        // Note: permission-denied fails before Files.copy creates a destination on this JVM,
        // so this doesn't reproduce a genuinely partial write (disk-full mid-copy isn't
        // reliably forceable for a file this small) — it verifies the property that matters
        // to a caller: a transient failure isn't permanent, and a retry recovers.
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
                "root ignores file read permissions");

        Path dataRoot = tempDir.resolve("s3");
        S3Service s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, false);
        s3Service.createBucket("legacy-bucket", "us-east-1");
        byte[] data = "pre-upgrade-content".getBytes(StandardCharsets.UTF_8);
        s3Service.putObject("legacy-bucket", "legacy-key.txt", data, "text/plain", null);

        Path newLayoutFile = dataRoot.resolve(".accounts").resolve("000000000000").resolve("legacy-bucket").resolve("legacy-key.txt.s3data");
        Path legacyLayoutFile = dataRoot.resolve("legacy-bucket").resolve("legacy-key.txt.s3data");
        Files.createDirectories(legacyLayoutFile.getParent());
        Files.move(newLayoutFile, legacyLayoutFile, StandardCopyOption.REPLACE_EXISTING);

        assertTrue(legacyLayoutFile.toFile().setReadable(false));
        try {
            assertThrows(UncheckedIOException.class, () -> s3Service.getObject("legacy-bucket", "legacy-key.txt"));
            assertFalse(Files.exists(newLayoutFile),
                    "a failed migration must not leave a file behind that would satisfy the "
                            + "exists-check on a later attempt and permanently shadow the intact legacy source");
        } finally {
            assertTrue(legacyLayoutFile.toFile().setReadable(true));
        }

        S3Object retrieved = s3Service.getObject("legacy-bucket", "legacy-key.txt");
        assertArrayEquals(data, retrieved.getData(),
                "a retry after a failed migration must recover the still-intact legacy content");
    }

    @Test
    void anotherAccountsWriteMustNotDestroyThisAccountsUnmigratedLegacyObject() throws Exception {
        Path dataRoot = tempDir.resolve("s3");
        // Two accounts sharing the underlying stores, like production — isolation comes only
        // from AccountAwareStorageBackend's key prefixing.
        InMemoryStorage<String, Bucket> sharedBucketStore = new InMemoryStorage<>();
        InMemoryStorage<String, S3Object> sharedObjectStore = new InMemoryStorage<>();

        S3Service s3ForA = new S3Service(
                new AccountAwareStorageBackend<>(sharedBucketStore, null, "111111111111"),
                new AccountAwareStorageBackend<>(sharedObjectStore, null, "111111111111"),
                dataRoot, false, (LambdaService) null, new RegionResolver("us-east-1", "111111111111"));
        S3Service s3ForB = new S3Service(
                new AccountAwareStorageBackend<>(sharedBucketStore, null, "222222222222"),
                new AccountAwareStorageBackend<>(sharedObjectStore, null, "222222222222"),
                dataRoot, false, (LambdaService) null, new RegionResolver("us-east-1", "222222222222"));

        s3ForA.createBucket("shared-bucket", "us-east-1");
        byte[] legacyData = "account-A-legacy-content".getBytes(StandardCharsets.UTF_8);
        s3ForA.putObject("shared-bucket", "shared-key.txt", legacyData, "text/plain", null);

        // A hasn't read it since upgrading, so it's still sitting at the legacy path.
        Path newLayoutFile = dataRoot.resolve(".accounts").resolve("111111111111").resolve("shared-bucket").resolve("shared-key.txt.s3data");
        Path legacyLayoutFile = dataRoot.resolve("shared-bucket").resolve("shared-key.txt.s3data");
        Files.createDirectories(legacyLayoutFile.getParent());
        Files.move(newLayoutFile, legacyLayoutFile, StandardCopyOption.REPLACE_EXISTING);

        // B is unrelated to A and just happens to reuse the same bucket/key names (bucket
        // names are only unique per account in Floci) — pure coincidence, not shared history.
        s3ForB.createBucket("shared-bucket", "us-east-1");
        byte[] bData = "account-B-content".getBytes(StandardCharsets.UTF_8);
        s3ForB.putObject("shared-bucket", "shared-key.txt", bData, "text/plain", null);

        assertArrayEquals(bData, s3ForB.getObject("shared-bucket", "shared-key.txt").getData());
        assertArrayEquals(legacyData, s3ForA.getObject("shared-bucket", "shared-key.txt").getData(),
                "account B's unrelated write must not destroy account A's still-unmigrated legacy object");
    }

    @Test
    void concurrentOverwriteNeverLosesToARacingLegacyMigration() throws Exception {
        // A racing legacy-migration read must never reinstall stale bytes on top of a write
        // that already landed. Repeated across many keys, since a lock-based fix can only be
        // shown to hold under real concurrent scheduling, not forced into one interleaving.
        int rounds = 50;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                Path dataRoot = tempDir.resolve("race-" + i).resolve("s3");
                S3Service s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, false);
                String bucket = "legacy-bucket";
                String key = "key.txt";
                s3Service.createBucket(bucket, "us-east-1");

                byte[] legacyData = "legacy-content".getBytes(StandardCharsets.UTF_8);
                s3Service.putObject(bucket, key, legacyData, "text/plain", null);
                Path newLayoutFile = dataRoot.resolve(".accounts").resolve("000000000000").resolve(bucket).resolve(key + ".s3data");
                Path legacyLayoutFile = dataRoot.resolve(bucket).resolve(key + ".s3data");
                Files.createDirectories(legacyLayoutFile.getParent());
                Files.move(newLayoutFile, legacyLayoutFile, StandardCopyOption.REPLACE_EXISTING);

                byte[] writeData = ("write-content-" + i).getBytes(StandardCharsets.UTF_8);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<byte[]> readFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return s3Service.getObject(bucket, key).getData();
                });
                Future<?> writeFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    s3Service.putObject(bucket, key, writeData, "text/plain", null);
                    return null;
                });
                readFuture.get(5, TimeUnit.SECONDS);
                writeFuture.get(5, TimeUnit.SECONDS);

                assertArrayEquals(writeData, s3Service.getObject(bucket, key).getData(),
                        "a completed write must never be clobbered by a racing legacy migration (round " + i + ")");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentFirstReadsOfTheSameLegacyObjectBothSucceed() throws Exception {
        Path dataRoot = tempDir.resolve("s3");
        S3Service s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), dataRoot, false);
        s3Service.createBucket("legacy-bucket", "us-east-1");

        // Migration copies rather than moves, so there's no "source vanished" case to race —
        // this just confirms many concurrent first reads never fail, regardless of scheduling.
        int rounds = 50;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < rounds; i++) {
                String key = "legacy-key-" + i + ".txt";
                byte[] data = ("pre-upgrade-content-" + i).getBytes(StandardCharsets.UTF_8);
                s3Service.putObject("legacy-bucket", key, data, "text/plain", null);

                Path newLayoutFile = dataRoot.resolve(".accounts").resolve("000000000000").resolve("legacy-bucket").resolve(key + ".s3data");
                Path legacyLayoutFile = dataRoot.resolve("legacy-bucket").resolve(key + ".s3data");
                Files.createDirectories(legacyLayoutFile.getParent());
                Files.move(newLayoutFile, legacyLayoutFile, StandardCopyOption.REPLACE_EXISTING);

                CyclicBarrier barrier = new CyclicBarrier(2);
                List<Future<byte[]>> futures = new ArrayList<>();
                for (int t = 0; t < 2; t++) {
                    futures.add(pool.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return s3Service.getObject("legacy-bucket", key).getData();
                    }));
                }
                for (Future<byte[]> future : futures) {
                    try {
                        assertArrayEquals(data, future.get(5, TimeUnit.SECONDS));
                    } catch (Exception e) {
                        fail("concurrent first read of a legacy-layout object must not fail (round " + i + ")", e);
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
