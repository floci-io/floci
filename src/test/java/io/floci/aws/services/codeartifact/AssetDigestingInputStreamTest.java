package io.floci.aws.services.codeartifact;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssetDigestingInputStreamTest {

    @Test
    void computesEveryAssetHashAlgorithmInOnePass() throws IOException, NoSuchAlgorithmException {
        byte[] content = new byte[1024 * 1024 + 37];
        new Random(7).nextBytes(content);

        AssetDigestingInputStream digesting = new AssetDigestingInputStream(
                new ByteArrayInputStream(content), content.length);
        byte[] buffer = new byte[4096];
        int read;
        while ((read = digesting.read(buffer)) >= 0) {
            assertEquals(read >= 0, true);
        }

        assertEquals((long) content.length, digesting.size());
        for (String algorithm : new String[] {"MD5", "SHA-1", "SHA-256", "SHA-512"}) {
            String expected = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
            assertEquals(expected, digesting.hashesHex().get(algorithm), algorithm);
        }
    }

    @Test
    void throwsOnceTheCapIsExceededMidStream() {
        byte[] content = new byte[10_000];
        AssetDigestingInputStream digesting = new AssetDigestingInputStream(new ByteArrayInputStream(content), 100);

        assertThrows(AssetDigestingInputStream.AssetTooLargeException.class, () -> {
            byte[] buffer = new byte[256];
            int read;
            while ((read = digesting.read(buffer)) >= 0) {
                // keep reading until the cap trips
            }
        });
    }

    @Test
    void readOneByteAtATimeStillUpdatesTheDigest() throws IOException, NoSuchAlgorithmException {
        byte[] content = "hello world".getBytes();
        AssetDigestingInputStream digesting = new AssetDigestingInputStream(
                new ByteArrayInputStream(content), content.length);

        int next;
        while ((next = digesting.read()) >= 0) {
            assertEquals(true, next >= 0 && next <= 255);
        }

        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        assertEquals(expected, digesting.hashesHex().get("SHA-256"));
        assertEquals((long) content.length, digesting.size());
    }

    /** Every external-connection client's own streamed fetch shares this one copy helper. */
    @Test
    void copyCappedWritesTheRealBytesAndReportsTheirSize() throws IOException {
        byte[] content = "tarball-content".getBytes();
        Path destination = Files.createTempFile("asset-digesting-input-stream-test-", ".bin");

        try {
            long size = AssetDigestingInputStream.copyCapped(new ByteArrayInputStream(content), destination,
                    content.length);

            assertEquals((long) content.length, size);
            assertArrayEquals(content, Files.readAllBytes(destination));
        } finally {
            Files.deleteIfExists(destination);
        }
    }

    @Test
    void copyCappedThrowsOnceTheCapIsExceededMidCopy() throws IOException {
        byte[] content = new byte[10_000];
        Path destination = Files.createTempFile("asset-digesting-input-stream-test-", ".bin");

        try {
            assertThrows(AssetDigestingInputStream.AssetTooLargeException.class,
                    () -> AssetDigestingInputStream.copyCapped(new ByteArrayInputStream(content), destination, 100));
        } finally {
            Files.deleteIfExists(destination);
        }
    }
}
