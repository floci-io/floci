package io.floci.aws.services.lambda.zip;

import io.floci.aws.core.common.Digests;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DeploymentPackageTest {

    @TempDir
    Path dir;

    @Test
    void aBodyS3HoldsInMemoryIsKeptAsItIs() throws IOException {
        byte[] zip = ZipExtractorTest.zipWith("index.js", "v1");
        S3Service s3 = mock(S3Service.class);
        Path staging = dir.resolve("staging");

        try (DeploymentPackage pkg = DeploymentPackage.read(object(zip, zip), staging)) {
            assertEquals(zip.length, pkg.size());
            assertEquals(Base64.getEncoder().encodeToString(Digests.sha256(zip)), pkg.sha256());
            pkg.putObject(s3, "tasks", "code.zip", "application/zip");
        }

        assertFalse(Files.exists(staging));
        verify(s3).putObject("tasks", "code.zip", zip, "application/zip", Map.of());
    }

    @Test
    void aBodyOnDiskIsStagedAndRemovedOnClose() throws IOException {
        byte[] zip = ZipExtractorTest.zipWith("index.js", "v1");
        S3Service s3 = mock(S3Service.class);
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        doAnswer(inv -> {
            inv.<InputStream>getArgument(2).transferTo(stored);
            return null;
        }).when(s3).putObjectFromStream(eq("tasks"), eq("code.zip"), any(InputStream.class), eq("application/zip"),
                eq(Map.of()));
        Path staging = dir.resolve("staging");
        Path target = dir.resolve("code");

        try (DeploymentPackage pkg = DeploymentPackage.read(object(null, zip), staging)) {
            assertEquals(zip.length, pkg.size());
            assertEquals(Base64.getEncoder().encodeToString(Digests.sha256(zip)), pkg.sha256());
            assertEquals(1, entries(staging).size());
            pkg.extractTo(new ZipExtractor(), target, ZipExtractor.DEFAULT_MAX_ENTRIES, null);
            pkg.putObject(s3, "tasks", "code.zip", "application/zip");
        }

        assertEquals("v1", Files.readString(target.resolve("index.js")));
        assertArrayEquals(zip, stored.toByteArray());
        assertEquals(List.of(), entries(staging));
    }

    @Test
    void aBodyOverTheCompressedLimitIsRejectedUnread() throws IOException {
        long size = ZipExtractor.S3_MAX_COMPRESSED_BYTES + 1;
        InputStream unread = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("the body was read");
            }
        };
        S3Service.ObjectRead object = new S3Service.ObjectRead(
                new S3Object("code", "app.zip", size, "application/zip", "\"etag\""), unread);
        Path staging = dir.resolve("staging");

        try (DeploymentPackage pkg = DeploymentPackage.read(object, staging)) {
            IOException e = assertThrows(IOException.class, () -> pkg.extractTo(new ZipExtractor(),
                    dir.resolve("code"), ZipExtractor.DEFAULT_MAX_ENTRIES, () -> fail("installed")));
            assertEquals("ZIP archive exceeds the 262144000 byte compressed limit: 262144001", e.getMessage());
        }

        assertFalse(Files.exists(staging));
        assertFalse(Files.exists(dir.resolve("code")));
    }

    private static S3Service.ObjectRead object(byte[] held, byte[] body) {
        S3Object metadata = held != null
                ? new S3Object("code", "app.zip", held, "application/zip")
                : new S3Object("code", "app.zip", body.length, "application/zip", S3Object.computeETag(body));
        return new S3Service.ObjectRead(metadata, new ByteArrayInputStream(body));
    }

    private static List<Path> entries(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.toList();
        }
    }
}
