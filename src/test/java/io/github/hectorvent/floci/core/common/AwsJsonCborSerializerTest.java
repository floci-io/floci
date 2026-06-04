package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, schema-less unit test for {@link AwsJsonCborController#nodeToSmithyCbor(JsonNode)}.
 * <p>
 * Verifies that timestamp shapes are encoded as CBOR tag(1) values per the
 * smithy-rpc-v2-cbor spec, for both scalar timestamps and timestamp lists, and that
 * integral epoch seconds are preserved as CBOR integers (not floats). CBOR tag 1 is the
 * single byte {@code 0xC1} (major type 6, value 1) immediately preceding the encoded
 * number. These assertions are deterministic and do not boot Quarkus.
 * <p>
 * The end-to-end acceptance gate that tag(1)+integer is actually accepted by
 * aws-sdk-go-v2 / smithy-go is the OTel awscloudwatch receiver run; this test guards the
 * wire format so a regression fails fast in CI without needing the live receiver.
 */
class AwsJsonCborSerializerTest {

    private static final byte CBOR_TAG_1 = (byte) 0xC1;
    private static final int CBOR_MAJOR_UNSIGNED_INT = 0; // major type 0
    private static final int CBOR_MAJOR_FLOAT = 7;         // major type 7 (0xF9/0xFA/0xFB)

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper cborMapper = new ObjectMapper(new CBORFactory());

    /** Counts occurrences of the tag(1) marker byte in the encoded output. */
    private static int countTag1(byte[] bytes) {
        int count = 0;
        for (byte b : bytes) {
            if (b == CBOR_TAG_1) {
                count++;
            }
        }
        return count;
    }

    /** CBOR major type of a head byte (top 3 bits). */
    private static int majorType(byte headByte) {
        return (headByte & 0xFF) >> 5;
    }

    @Test
    void scalarTimestampIsTaggedAsInteger() throws Exception {
        JsonNode node = jsonMapper.readTree("{\"Timestamp\": 1700000000}");

        byte[] cbor = AwsJsonCborController.nodeToSmithyCbor(node);

        // Exactly one tag(1) byte, and it is immediately followed by an unsigned integer
        // (major type 0) — i.e. integer-ness is preserved, not coerced to a float.
        assertEquals(1, countTag1(cbor), "scalar Timestamp must be tagged exactly once");
        int tagIndex = indexOf(cbor, CBOR_TAG_1);
        assertTrue(tagIndex >= 0, "tag(1) byte must be present");
        assertTrue(tagIndex + 1 < cbor.length, "tag(1) byte must not be the last byte in the output");
        assertEquals(CBOR_MAJOR_UNSIGNED_INT, majorType(cbor[tagIndex + 1]),
                "integral epoch seconds must be encoded as a CBOR integer, not a float");

        JsonNode decoded = cborMapper.readTree(cbor);
        assertTrue(decoded.path("Timestamp").isIntegralNumber(), "decoded timestamp must be integral");
        assertEquals(1700000000L, decoded.path("Timestamp").asLong());
    }

    @Test
    void timestampListTagsEveryElementAsInteger() throws Exception {
        // Core regression guard for CloudWatch GetMetricData: every element of a
        // Timestamps list must be tagged (not just a scalar named "Timestamp").
        JsonNode node = jsonMapper.readTree("{\"Timestamps\": [1700000000, 1700000060]}");

        byte[] cbor = AwsJsonCborController.nodeToSmithyCbor(node);

        assertEquals(2, countTag1(cbor), "each Timestamps element must be tagged with tag(1)");
        // Both tagged elements must be integers, not floats.
        for (int i = 0; i < cbor.length; i++) {
            if (cbor[i] == CBOR_TAG_1) {
                assertTrue(i + 1 < cbor.length, "tag(1) byte at index " + i + " must not be the last byte in the output");
                assertEquals(CBOR_MAJOR_UNSIGNED_INT, majorType(cbor[i + 1]),
                        "timestamp list elements must be encoded as CBOR integers");
            }
        }

        JsonNode decoded = cborMapper.readTree(cbor);
        JsonNode ts = decoded.path("Timestamps");
        assertTrue(ts.isArray());
        assertEquals(2, ts.size());
        assertEquals(1700000000L, ts.get(0).asLong());
        assertEquals(1700000060L, ts.get(1).asLong());
    }

    @Test
    void suffixedTimestampFieldIsTagged() throws Exception {
        // Future-proofing: the heuristic matches by "Timestamp" suffix, so AWS fields such
        // as StateUpdatedTimestamp are tagged even though Floci does not emit them today.
        JsonNode node = jsonMapper.readTree("{\"StateUpdatedTimestamp\": 1700000000}");

        byte[] cbor = AwsJsonCborController.nodeToSmithyCbor(node);

        assertEquals(1, countTag1(cbor), "a *Timestamp-suffixed field must be tagged");
        assertEquals(1700000000L, cborMapper.readTree(cbor).path("StateUpdatedTimestamp").asLong());
    }

    @Test
    void fractionalTimestampIsTaggedAsFloat() throws Exception {
        // A non-integral timestamp is a valid tag(1) floating-point value (RFC 8949 3.4.2).
        JsonNode node = jsonMapper.readTree("{\"Timestamp\": 1700000000.5}");

        byte[] cbor = AwsJsonCborController.nodeToSmithyCbor(node);

        assertEquals(1, countTag1(cbor), "fractional Timestamp must still be tagged");
        int tagIndex = indexOf(cbor, CBOR_TAG_1);
        assertTrue(tagIndex + 1 < cbor.length, "tag(1) byte must not be the last byte in the output");
        assertEquals(CBOR_MAJOR_FLOAT, majorType(cbor[tagIndex + 1]),
                "fractional epoch seconds must be encoded as a CBOR float");
        assertEquals(1700000000.5, cborMapper.readTree(cbor).path("Timestamp").asDouble());
    }

    @Test
    void nonTimestampNumberIsNotTagged() throws Exception {
        // A plain numeric field (e.g. CloudWatch Period) must remain an untagged integer.
        JsonNode node = jsonMapper.readTree("{\"Period\": 60}");

        byte[] cbor = AwsJsonCborController.nodeToSmithyCbor(node);

        assertEquals(0, countTag1(cbor), "non-timestamp numbers must not carry a tag(1) byte");
        assertFalse(contains(cbor, CBOR_TAG_1));

        JsonNode decoded = cborMapper.readTree(cbor);
        assertEquals(60L, decoded.path("Period").asLong());
    }

    private static int indexOf(byte[] bytes, byte target) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == target) {
                return i;
            }
        }
        return -1;
    }

    private static boolean contains(byte[] bytes, byte target) {
        return indexOf(bytes, target) >= 0;
    }
}
