package io.github.hectorvent.floci.services.s3;

import com.sun.management.ThreadMXBean;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3ObjectStreamTest {

    @TempDir
    Path tempDir;

    @Test
    void readingAPersistedObjectDoesNotLoadItIntoTheHeap() throws Exception {
        S3Service s3 = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir.resolve("s3"), false);
        s3.createBucket("large-objects", "us-east-1");
        int objectSize = 64 * 1024 * 1024;
        s3.putObject("large-objects", "blob.bin", new byte[objectSize], "application/octet-stream", Map.of());

        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        long allocatedBefore = threads.getThreadAllocatedBytes(threadId);
        try (S3Service.ObjectRead read = s3.openObject("large-objects", "blob.bin", null)) {
            int n;
            while ((n = read.body().read(buffer)) > 0) {
                total += n;
            }
        }
        long allocated = threads.getThreadAllocatedBytes(threadId) - allocatedBefore;

        assertEquals(objectSize, total);
        assertTrue(allocated < objectSize / 4,
                "reading a " + objectSize + " byte object allocated " + allocated + " bytes");
    }

    @Test
    void overwriteAfterOpenDoesNotChangeTheOpenedBody() throws Exception {
        S3Service s3 = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir.resolve("s3"), false);
        s3.createBucket("objects", "us-east-1");
        byte[] first = "first version".getBytes(StandardCharsets.UTF_8);
        s3.putObject("objects", "key.txt", first, "text/plain", Map.of());

        try (S3Service.ObjectRead read = s3.openObject("objects", "key.txt", null)) {
            s3.putObject("objects", "key.txt", "second, longer version".getBytes(StandardCharsets.UTF_8),
                    "text/plain", Map.of());
            InputStream body = read.body();

            assertEquals(first.length, read.object().getSize());
            assertArrayEquals(first, body.readAllBytes());
        }
    }
}
