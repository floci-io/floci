package io.github.hectorvent.floci.services.firehose;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the container format of each CompressionFormat, since a consumer that
 * reads the delivered objects only works if the framing is the one AWS writes.
 * The expectations come from objects delivered by real AWS, see
 * https://github.com/floci-io/floci/issues/2328.
 */
class FirehoseCompressionTest {

    private static final byte[] PAYLOAD =
            "{\"seq\":1}\n{\"seq\":2}\n{\"seq\":3}\n".getBytes(StandardCharsets.UTF_8);

    private static String hexPrefix(byte[] body, int length) {
        return HexFormat.of().formatHex(body, 0, length);
    }

    @ParameterizedTest
    @EnumSource(FirehoseCompression.class)
    void everyFormatRoundTripsToTheOriginalPayload(FirehoseCompression format) throws IOException {
        byte[] compressed = format.compress(PAYLOAD);

        assertSamePayload(PAYLOAD, FirehoseCompressionDecoder.decompress(format, compressed));
    }

    @Test
    void uncompressedReturnsThePayloadUntouchedWithNoExtensionOrEncoding() throws IOException {
        assertEquals("", FirehoseCompression.UNCOMPRESSED.extension());
        assertNull(FirehoseCompression.UNCOMPRESSED.contentEncoding());
        assertSamePayload(PAYLOAD, FirehoseCompression.UNCOMPRESSED.compress(PAYLOAD));
    }

    @Test
    void gzipWritesAGzipStream() throws IOException {
        assertEquals(".gz", FirehoseCompression.GZIP.extension());
        assertEquals("gzip", FirehoseCompression.GZIP.contentEncoding());

        assertEquals("1f8b", hexPrefix(FirehoseCompression.GZIP.compress(PAYLOAD), 2));
    }

    /** AWS writes exactly one entry, named with a UUID unrelated to the object key. */
    @Test
    void zipWritesASingleEntryNamedWithAUuid() throws IOException {
        assertEquals(".zip", FirehoseCompression.ZIP.extension());
        assertEquals("zip", FirehoseCompression.ZIP.contentEncoding());

        byte[] compressed = FirehoseCompression.ZIP.compress(PAYLOAD);
        assertEquals("504b", hexPrefix(compressed, 2));
        FirehoseCompressionDecoder.ZipEntryContent entry =
                FirehoseCompressionDecoder.singleZipEntry(compressed);
        assertEquals(entry.name(), UUID.fromString(entry.name()).toString());
    }

    /**
     * The xerial stream format, not the official Snappy framing format: the two
     * are mutually unreadable, and AWS delivers this one.
     */
    @Test
    void snappyWritesTheXerialStreamFormat() throws IOException {
        assertEquals(".snappy", FirehoseCompression.SNAPPY.extension());
        assertEquals("snappy-java", FirehoseCompression.SNAPPY.contentEncoding());

        byte[] compressed = FirehoseCompression.SNAPPY.compress(PAYLOAD);
        assertEquals("82" + HexFormat.of().formatHex("SNAPPY".getBytes(StandardCharsets.US_ASCII)) + "00",
                hexPrefix(compressed, 8));
    }

    /**
     * Same extension as Snappy, different framing and Content-Encoding. The
     * extension is .snappy and not the .hsnappy the S3 object name documentation
     * tables, because that is what the service delivers.
     */
    @Test
    void hadoopSnappyWritesHadoopBlockFramingHeadedByTheUncompressedLength() throws IOException {
        assertEquals(".snappy", FirehoseCompression.HADOOP_SNAPPY.extension());
        assertEquals("hadoop-snappy", FirehoseCompression.HADOOP_SNAPPY.contentEncoding());

        byte[] compressed = FirehoseCompression.HADOOP_SNAPPY.compress(PAYLOAD);
        // The leading length is the first block's; this payload fits in one block.
        assertEquals(PAYLOAD.length, ByteBuffer.wrap(compressed).getInt());
        // Unlike the xerial stream format, the block framing carries no magic header.
        assertNotEquals("82", hexPrefix(compressed, 1));
    }

    /**
     * Firehose buffers up to megabytes, so the formats have to survive payloads
     * larger than snappy-java's 32 KiB block: past that, the Hadoop framing repeats
     * and its leading length stops describing the whole payload.
     */
    @ParameterizedTest
    @EnumSource(FirehoseCompression.class)
    void everyFormatRoundTripsPayloadsLargerThanOneSnappyBlock(FirehoseCompression format) throws IOException {
        byte[] large = new byte[200_000];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) ('a' + (i % 26));
        }

        byte[] compressed = format.compress(large);

        if (format == FirehoseCompression.HADOOP_SNAPPY) {
            assertNotEquals(large.length, ByteBuffer.wrap(compressed).getInt(),
                    "the leading length describes the first block, not the payload");
        }
        assertArrayEquals(large, FirehoseCompressionDecoder.decompress(format, compressed));
    }

    @Test
    void wireValuesAreMatchedCaseExactlyTheWayAwsMatchesTheEnum() {
        assertEquals(FirehoseCompression.SNAPPY, FirehoseCompression.fromWireValue("Snappy").orElseThrow());
        assertEquals(FirehoseCompression.HADOOP_SNAPPY,
                FirehoseCompression.fromWireValue("HADOOP_SNAPPY").orElseThrow());
        assertTrue(FirehoseCompression.fromWireValue("SNAPPY").isEmpty());
        assertTrue(FirehoseCompression.fromWireValue("gzip").isEmpty());
        assertTrue(FirehoseCompression.fromWireValue("BROTLI").isEmpty());
        assertTrue(FirehoseCompression.fromWireValue("").isEmpty());
        assertTrue(FirehoseCompression.fromWireValue(null).isEmpty());
    }

    /**
     * A stream stored before the value was validated must still deliver its
     * records, uncompressed, rather than losing them to a lookup failure.
     */
    @Test
    void deliveryFallsBackToUncompressedForAbsentOrUnknownFormats() {
        assertEquals(FirehoseCompression.UNCOMPRESSED, FirehoseCompression.forDelivery(null));
        assertEquals(FirehoseCompression.UNCOMPRESSED, FirehoseCompression.forDelivery("nonsense"));
        assertEquals(FirehoseCompression.GZIP, FirehoseCompression.forDelivery("GZIP"));
    }

    /** Compares as text so a mismatch is readable, then as bytes so it is exact. */
    private static void assertSamePayload(byte[] expected, byte[] actual) {
        assertEquals(new String(expected, StandardCharsets.UTF_8), new String(actual, StandardCharsets.UTF_8));
        assertArrayEquals(expected, actual);
    }
}
