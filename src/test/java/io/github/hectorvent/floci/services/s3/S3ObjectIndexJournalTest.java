package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Under {@code persistent} storage a PutObject is appended to the object-index journal instead of
 * rewriting {@code s3-objects.json}, which holds the metadata of every object in every bucket, so
 * the cost of one write no longer grows with the total object count. The bucket store keeps its
 * rewritten-on-every-call file.
 */
class S3ObjectIndexJournalTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String BUCKET = "journal-bucket";
    private static final String VERSIONED_BUCKET = "journal-versioned";

    @TempDir
    Path root;

    @Test
    void putObjectUnderPersistentModeAppendsToTheJournalInsteadOfRewritingTheIndex() throws IOException {
        Path directory = root.resolve("live");
        Path journal = directory.resolve("s3-objects.wal");
        Path index = directory.resolve("s3-objects.json");
        Path bucketStore = directory.resolve("s3-buckets.json");

        StorageFactory first = newFactory(directory);
        S3Service s3 = newService(first, directory);
        s3.createBucket(BUCKET, REGION);
        assertTrue(Files.readString(bucketStore).contains(BUCKET), "the bucket store is still written on every call");

        long journalSize = 0;
        for (int i = 0; i < 3; i++) {
            s3.putObject(BUCKET, key(i), body(i), "text/plain", Map.of());
            assertTrue(journalSize(journal) > journalSize, "every PutObject must append to the journal");
            journalSize = journalSize(journal);
            assertFalse(Files.exists(index), "a PutObject must not rewrite the object index");
        }
        s3.deleteObject(BUCKET, key(2));
        assertTrue(journalSize(journal) > journalSize, "a DeleteObject must append to the journal");
        first.shutdownAll();

        assertTrue(Files.exists(index), "a clean shutdown folds the journal into the object index");
        StorageFactory second = newFactory(directory);
        try {
            S3Service reopened = newService(second, directory);
            for (int i = 0; i < 2; i++) {
                assertArrayEquals(body(i), reopened.getObject(BUCKET, key(i)).getData());
            }
            assertEquals(2, reopened.listObjects(BUCKET, null, null, 100).size());
        } finally {
            second.shutdownAll();
        }
    }

    @Test
    void journaledEntriesAreReplayedWhenTheProcessStopsWithoutCompacting() throws IOException {
        Path directory = root.resolve("live");
        Path afterCrash = root.resolve("after-crash");
        StorageFactory first = newFactory(directory);
        String v1;
        try {
            S3Service s3 = newService(first, directory);
            s3.createBucket(BUCKET, REGION);
            s3.createBucket(VERSIONED_BUCKET, REGION);
            s3.putBucketVersioning(VERSIONED_BUCKET, "Enabled");
            s3.putObject(BUCKET, key(0), body(0), "text/plain", Map.of());
            v1 = s3.putObject(VERSIONED_BUCKET, key(0), body(1), "text/plain", Map.of()).getVersionId();
            s3.putObject(VERSIONED_BUCKET, key(0), body(2), "text/plain", Map.of());
            assertFalse(Files.exists(directory.resolve("s3-objects.json")), "nothing has compacted the journal yet");
            copyAsLeftByAKill(directory, afterCrash);
        } finally {
            first.shutdownAll();
        }

        StorageFactory second = newFactory(afterCrash);
        try {
            S3Service reopened = newService(second, afterCrash);
            assertArrayEquals(body(0), reopened.getObject(BUCKET, key(0)).getData());
            assertArrayEquals(body(2), reopened.getObject(VERSIONED_BUCKET, key(0)).getData());
            assertArrayEquals(body(1), reopened.getObject(VERSIONED_BUCKET, key(0), v1).getData());
        } finally {
            second.shutdownAll();
        }
    }

    @Test
    void aTagUpdateOnTheLatestKeyIsReplayedUnderTheCurrentVersionToo() throws IOException {
        Path directory = root.resolve("live");
        Path afterCrash = root.resolve("after-crash");
        StorageFactory first = newFactory(directory);
        String current;
        try {
            S3Service s3 = newService(first, directory);
            s3.createBucket(VERSIONED_BUCKET, REGION);
            s3.putBucketVersioning(VERSIONED_BUCKET, "Enabled");
            current = s3.putObject(VERSIONED_BUCKET, key(0), body(0), "text/plain", Map.of()).getVersionId();
            s3.putObjectTagging(VERSIONED_BUCKET, key(0), Map.of("stage", "reviewed"));
            copyAsLeftByAKill(directory, afterCrash);
        } finally {
            first.shutdownAll();
        }

        StorageFactory second = newFactory(afterCrash);
        try {
            S3Service reopened = newService(second, afterCrash);
            assertEquals(Map.of("stage", "reviewed"), reopened.getObjectTagging(VERSIONED_BUCKET, key(0), current));
        } finally {
            second.shutdownAll();
        }
    }

    @Test
    void aLegalHoldSetByVersionIdOnTheCurrentVersionIsReplayedUnderTheLatestKeyToo() throws IOException {
        Path directory = root.resolve("live");
        Path afterCrash = root.resolve("after-crash");
        StorageFactory first = newFactory(directory);
        try {
            S3Service s3 = newService(first, directory);
            s3.createBucket(VERSIONED_BUCKET, REGION);
            s3.putBucketVersioning(VERSIONED_BUCKET, "Enabled");
            String current = s3.putObject(VERSIONED_BUCKET, key(0), body(0), "text/plain", Map.of()).getVersionId();
            s3.putObjectLegalHold(VERSIONED_BUCKET, key(0), current, "ON");
            copyAsLeftByAKill(directory, afterCrash);
        } finally {
            first.shutdownAll();
        }

        StorageFactory second = newFactory(afterCrash);
        try {
            S3Service reopened = newService(second, afterCrash);
            assertEquals("ON", reopened.getObjectLegalHold(VERSIONED_BUCKET, key(0), null).getLegalHoldStatus());
        } finally {
            second.shutdownAll();
        }
    }

    /**
     * The storage directory as a process killed now would leave it, with no shutdown compaction.
     * Replaying a copy keeps the restarted factory from sharing the journal with the live writer.
     */
    private static void copyAsLeftByAKill(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path copy = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String key(int i) {
        return "logos/object-" + i + ".txt";
    }

    private static byte[] body(int i) {
        return ("body " + i).getBytes(StandardCharsets.UTF_8);
    }

    private static long journalSize(Path journal) throws IOException {
        return Files.exists(journal) ? Files.size(journal) : 0L;
    }

    private static S3Service newService(StorageFactory factory, Path storage) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(storage.toString());
        when(config.storage().mode()).thenReturn("persistent");
        when(config.storage().services().s3().mode()).thenReturn(Optional.of("persistent"));
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        when(config.services().s3().enforceAuth()).thenReturn(false);
        when(config.services().s3().globalBucketNamespace()).thenReturn(false);
        return new S3Service(factory, config, null, null, null, null, null,
                new RegionResolver(REGION, ACCOUNT), new ObjectMapper(), null);
    }

    private static StorageFactory newFactory(Path storage) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(storage.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(3_600_000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn("persistent");
        when(access.storageFlushInterval(anyString())).thenReturn(3_600_000L);
        return new StorageFactory(config, access);
    }
}
