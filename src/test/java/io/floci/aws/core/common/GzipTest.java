package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GzipTest {

    @Test
    void compressedContentGunzipsBackToTheSameBytes() throws IOException {
        byte[] content = "{\"Records\":[{\"eventName\":\"PutObject\"}]}\n".repeat(500).getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(content, gunzip(Gzip.compress(content)));
    }

    @Test
    void emptyContentIsStillAGzipMember() throws IOException {
        byte[] compressed = Gzip.compress(new byte[0]);

        assertEquals((byte) 0x1f, compressed[0]);
        assertEquals((byte) 0x8b, compressed[1]);
        assertArrayEquals(new byte[0], gunzip(compressed));
    }

    @Test
    void membersCompressedApartReadBackAsOneStreamWhenJoined() throws IOException {
        // A log file grown one member at a time, as Redshift's audit log is, reads as the lines in order.
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        joined.write(Gzip.compress("first\n".getBytes(StandardCharsets.UTF_8)));
        joined.write(Gzip.compress("second\n".getBytes(StandardCharsets.UTF_8)));

        assertEquals("first\nsecond\n", new String(gunzip(joined.toByteArray()), StandardCharsets.UTF_8));
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }
}
