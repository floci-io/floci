package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.model.ChecksumAlgorithm;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AwsChunkedInputStreamTest {

    @Test
    void decodesSignedChunksUpToTheFinalChunk() throws IOException {
        assertEquals("hello world", decode("5;chunk-signature=abc\r\nhello\r\n6;chunk-signature=def\r\n world\r\n"
                + "0;chunk-signature=ghi\r\n\r\n"));
    }

    @Test
    void skipsTrailerLinesAfterTheFinalChunk() throws IOException {
        assertEquals("hello", decode("5\r\nhello\r\n0\r\nx-amz-checksum-crc32:NhCmhg==\r\n"
                + "x-amz-trailer-signature:ghi\r\n\r\n"));
    }

    @Test
    void retainsOnlyTheDeclaredChecksumTrailer() throws IOException {
        StringBuilder framed = new StringBuilder("5\r\nhello\r\n0\r\n");
        for (int i = 0; i < 1000; i++) {
            framed.append("x-amz-trailer-signature:").append(i).append("\r\n");
        }
        framed.append("x-amz-checksum-crc32:NhCmhg==\r\n\r\n");

        try (AwsChunkedInputStream in = new AwsChunkedInputStream(
                new ByteArrayInputStream(framed.toString().getBytes(StandardCharsets.US_ASCII)),
                ChecksumAlgorithm.CRC32)) {
            assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.US_ASCII));
            assertEquals("NhCmhg==", in.trailerChecksum());
            assertNull(in.undeclaredTrailer());
            assertFalse(in.hasUndeclaredChecksum());
        }
    }

    @Test
    void recordsAChecksumTrailerOtherThanTheDeclaredOne() throws IOException {
        try (AwsChunkedInputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(
                ("5\r\nhello\r\n0\r\nx-amz-checksum-crc32:NhCmhg==\r\n"
                        + "x-amz-checksum-sha256:abc\r\n\r\n").getBytes(StandardCharsets.US_ASCII)),
                ChecksumAlgorithm.CRC32)) {
            assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.US_ASCII));
            assertEquals("NhCmhg==", in.trailerChecksum());
            assertEquals(ChecksumAlgorithm.SHA256, in.undeclaredTrailer());
            assertTrue(in.hasUndeclaredChecksum());
        }
    }

    @Test
    void aChecksumTrailerWithNoDeclaredAlgorithmIsUndeclared() throws IOException {
        try (AwsChunkedInputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(
                "5\r\nhello\r\n0\r\nx-amz-checksum-crc32:NhCmhg==\r\n\r\n".getBytes(StandardCharsets.US_ASCII)))) {
            assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.US_ASCII));
            assertNull(in.trailerChecksum());
            assertEquals(ChecksumAlgorithm.CRC32, in.undeclaredTrailer());
            assertTrue(in.hasUndeclaredChecksum());
        }
    }

    @Test
    void acceptsBareLineFeeds() throws IOException {
        assertEquals("hello", decode("5\nhello\n0\n\n"));
    }

    @Test
    void decodesManyChunksWhateverTheReadSize() throws IOException {
        Random random = new Random(5080);
        byte[] payload = new byte[200_003];
        random.nextBytes(payload);
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        for (int offset = 0; offset < payload.length; offset += 8192) {
            int size = Math.min(8192, payload.length - offset);
            framed.writeBytes((Integer.toHexString(size) + ";chunk-signature=sig\r\n").getBytes(StandardCharsets.US_ASCII));
            framed.write(payload, offset, size);
            framed.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        framed.writeBytes("0;chunk-signature=sig\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

        for (int readSize : List.of(1, 7, 4096, 65_536)) {
            try (InputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(framed.toByteArray()))) {
                ByteArrayOutputStream decoded = new ByteArrayOutputStream();
                byte[] buffer = new byte[readSize];
                for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                    decoded.write(buffer, 0, read);
                }
                assertArrayEquals(payload, decoded.toByteArray(), "read size " + readSize);
            }
        }
    }

    @Test
    void readsOneByteAtATime() throws IOException {
        try (InputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(
                "3\r\nabc\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)))) {
            assertEquals('a', in.read());
            assertEquals('b', in.read());
            assertEquals('c', in.read());
            assertEquals(-1, in.read());
            assertEquals(-1, in.read(), "the stream stays at its end");
        }
    }

    @Test
    void brokenFramingIsAnIncompleteBody() {
        for (String body : List.of(
                "10\r\nonly-five\r\n",
                "5\r\nhello\r\n",
                "five\r\nhello\r\n0\r\n\r\n",
                "5\r\nhello0\r\n\r\n",
                "5\r\nhello\r\n0\r\nx-amz-checksum-crc32:NhCmhg==\r\n",
                "5;chunk-signature=abc\r\nhello\r\n0;chunk-signature=def\r\n",
                "-5\r\nhello\r\n0\r\n\r\n",
                "")) {
            AwsException error = assertThrows(AwsException.class, () -> decode(body), body);
            assertEquals("IncompleteBody", error.getErrorCode(), body);
            assertEquals(400, error.getHttpStatus(), body);
        }
    }

    private static String decode(String framed) throws IOException {
        try (InputStream in = new AwsChunkedInputStream(
                new ByteArrayInputStream(framed.getBytes(StandardCharsets.ISO_8859_1)))) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
