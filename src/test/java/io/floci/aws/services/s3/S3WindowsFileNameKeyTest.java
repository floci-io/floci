package io.floci.aws.services.s3;

import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Object keys whose segments Windows cannot hold as file names (a colon from a timestamp, a
 * backslash, a trailing dot or space), in disk-storage mode. S3 accepts all of them, so the
 * body file gets an encoded name there while the object store keeps the key.
 */
class S3WindowsFileNameKeyTest {

    @TempDir
    Path tempDir;

    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(), tempDir.resolve("s3"), false);
        s3Service.createBucket("bucket", "us-east-1");
    }

    @Test
    void keysWithCharactersWindowsRejectsRoundTrip() {
        List<String> keys = List.of(
                "logs/2026-11-05T10:00:00Z.json",
                "a<b>c|d?e*f\"g",
                "tab\there",
                "trailing-dot./x",
                "trailing-space /x");

        for (String key : keys) {
            byte[] body = ("body of " + key).getBytes(StandardCharsets.UTF_8);
            s3Service.putObject("bucket", key, body, "text/plain", Map.of());
            assertArrayEquals(body, s3Service.getObject("bucket", key).getData(), key);
        }

        List<String> listed = s3Service.listObjects("bucket", null, null, 1000)
                .stream().map(S3Object::getKey).sorted().toList();
        assertEquals(keys.stream().sorted().toList(), listed);
    }

    @Test
    void keysWindowsWouldFoldOntoOneFileStayDistinct() {
        s3Service.putObject("bucket", "dir/file", bytes("slash"), "text/plain", Map.of());
        s3Service.putObject("bucket", "dir\\file", bytes("backslash"), "text/plain", Map.of());
        s3Service.putObject("bucket", "dir./file", bytes("trailing dot"), "text/plain", Map.of());

        assertArrayEquals(bytes("slash"), s3Service.getObject("bucket", "dir/file").getData());
        assertArrayEquals(bytes("backslash"), s3Service.getObject("bucket", "dir\\file").getData());
        assertArrayEquals(bytes("trailing dot"), s3Service.getObject("bucket", "dir./file").getData());
    }

    @Test
    void windowsFileNameRulesMatchTheFileSystem() {
        assertTrue(S3Service.isWindowsFileName("report.json"));
        assertTrue(S3Service.isWindowsFileName("."));
        assertTrue(S3Service.isWindowsFileName(".."));
        assertTrue(S3Service.isWindowsFileName(""));

        assertFalse(S3Service.isWindowsFileName("10:00:00Z"));
        assertFalse(S3Service.isWindowsFileName("a\\b"));
        assertFalse(S3Service.isWindowsFileName("q?"));
        assertFalse(S3Service.isWindowsFileName("bell\u0007"));
        assertFalse(S3Service.isWindowsFileName("dir."));
        assertFalse(S3Service.isWindowsFileName("dir "));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
