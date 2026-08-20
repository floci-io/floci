package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, schema-less unit test for {@link AwsJsonCborController#nodeToSmithyCbor(JsonNode)}
 * and {@link AwsJsonCborController#normalizeCborTimestampsFromMillis(JsonNode)}.
 * <p>
 * Verifies that timestamp shapes are encoded as CBOR tag(1) <em>epoch-millisecond</em>
 * values per AWS's legacy CBOR protocol convention — not the fractional epoch-second
 * value RFC 8949 section 3.4.2 itself uses as an example, and not the value that was
 * actually written under tag(1) before floci-io/floci#2368 was fixed (this file's own
 * assertions previously locked in that bug as intended behaviour: an integral epoch
 * *second* value round-tripped unchanged, which is exactly what let an unmodified AWS
 * SDK for Java v2 client — CBOR by default — decode every timestamp 1000x too small).
 * CBOR tag 1 is the single byte {@code 0xC1} (major type 6, value 1) immediately
 * preceding the encoded number. These assertions are deterministic and do not boot
 * Quarkus.
 * <p>
 * The end-to-end acceptance gate that tag(1)+integer is actually accepted by
 * aws-sdk-go-v2 / smithy-go is the OTel awscloudwatch receiver run; this test guards the
 * wire format so a regression fails fast in CI without needing the live receiver.
 */
class AwsJsonCborSerializerTest {

    private static final byte CBOR_TAG_1 = (byte) 0xC1;
    private static final int CBOR_MAJOR_UNSIGNED_INT = 0; // major type 0

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
    void scalarTimestampIsTaggedAsIntegerMillis() throws Exception {
        JsonNode node = jsonMapper.readTree("{\"Timestamp\": 1700000000}");

        byte[] cbor = AwsJsonCborController.nodeToSmithyCbor(node);

        // Exactly one tag(1) byte, and it is immediately followed by an unsigned integer
        // (major type 0) — i.e. integer-ness is preserved, not coerced to a float.
        assertEquals(1, countTag1(cbor), "scalar Timestamp must be tagged exactly once");
        int tagIndex = indexOf(cbor, CBOR_TAG_1);
        assertTrue(tagIndex >= 0, "tag(1) byte must be present");
        assertTrue(tagIndex + 1 < cbor.length, "tag(1) byte must not be the last byte in the output");
        assertEquals(CBOR_MAJOR_UNSIGNED_INT, majorType(cbor[tagIndex + 1]),
                "epoch millis must be encoded as a CBOR integer, not a float");

        // 1700000000 epoch SECONDS (this codebase's internal convention) must be written
        // under tag(1) as 1700000000000 epoch MILLISECONDS (AWS's real CBOR convention) —
        // not the same number unchanged, which is exactly the pre-#2368-fix bug.
        JsonNode decoded = cborMapper.readTree(cbor);
        assertTrue(decoded.path("Timestamp").isIntegralNumber(), "decoded timestamp must be integral");
        assertEquals(1700000000000L, decoded.path("Timestamp").asLong());
    }

    @Test
    void timestampListTagsEveryElementAsIntegerMillis() throws Exception {
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
        assertEquals(1700000000000L, ts.get(0).asLong());
        assertEquals(1700000060000L, ts.get(1).asLong());
    }

    @Test
    void suffixedTimestampFieldIsTagged() throws Exception {
        // Future-proofing: the heuristic matches by "Timestamp" suffix, so AWS fields such
        // as StateUpdatedTimestamp are tagged even though Floci does not emit them today.
        JsonNode node = jsonMapper.readTree("{\"StateUpdatedTimestamp\": 1700000000}");

        byte[] cbor = AwsJsonCborController.nodeToSmithyCbor(node);

        assertEquals(1, countTag1(cbor), "a *Timestamp-suffixed field must be tagged");
        assertEquals(1700000000000L, cborMapper.readTree(cbor).path("StateUpdatedTimestamp").asLong());
    }

    @Test
    void millisecondPrecisionFractionalTimestampBecomesAnExactIntegerMillisValue() throws Exception {
        // A fractional epoch-SECONDS value with real AWS's own millisecond-precision limit
        // (3 decimal places) converts to an EXACT integer number of milliseconds - not a
        // float - once written under tag(1). This is the realistic case (AWS timestamps
        // never carry sub-millisecond precision) and the actual reproducer from
        // floci-io/floci#2368: 1700000000.712s -> 1700000000712ms, exactly.
        JsonNode node = jsonMapper.readTree("{\"Timestamp\": 1700000000.712}");

        byte[] cbor = AwsJsonCborController.nodeToSmithyCbor(node);

        int tagIndex = indexOf(cbor, CBOR_TAG_1);
        assertTrue(tagIndex >= 0, "tag(1) byte must be present");
        assertTrue(tagIndex + 1 < cbor.length, "tag(1) byte must not be the last byte in the output");
        assertEquals(CBOR_MAJOR_UNSIGNED_INT, majorType(cbor[tagIndex + 1]),
                "millisecond-precision epoch seconds must convert to an exact integer number "
                        + "of milliseconds, not a float");

        JsonNode decoded = cborMapper.readTree(cbor);
        assertTrue(decoded.path("Timestamp").isIntegralNumber(), "decoded millis value must be integral");
        assertEquals(1700000000712L, decoded.path("Timestamp").asLong());
    }

    @Test
    void roundTripsThroughEncodeAndDecodeBackToTheOriginalEpochSecondsValue() throws Exception {
        // The actual end-to-end guarantee that matters: whatever normalizeCborTimestampsFromMillis
        // decodes back out must equal what nodeToSmithyCbor was given, for both an integral
        // and a millisecond-precision fractional epoch-seconds value - proving the two
        // directions are genuine inverses of each other, not just independently plausible.
        JsonNode integral = jsonMapper.readTree("{\"Timestamp\": 1700000000}");
        JsonNode fractional = jsonMapper.readTree("{\"Timestamp\": 1700000000.712}");

        JsonNode decodedIntegral = AwsJsonCborController.normalizeCborTimestampsFromMillis(
                cborMapper.readTree(AwsJsonCborController.nodeToSmithyCbor(integral)));
        JsonNode decodedFractional = AwsJsonCborController.normalizeCborTimestampsFromMillis(
                cborMapper.readTree(AwsJsonCborController.nodeToSmithyCbor(fractional)));

        assertEquals(1700000000, decodedIntegral.path("Timestamp").decimalValue().doubleValue());
        assertEquals(1700000000.712, decodedFractional.path("Timestamp").decimalValue().doubleValue());
    }

    @Test
    void nonTimestampFieldIsNotConvertedByNormalization() {
        // normalizeCborTimestampsFromMillis must use the identical field-name heuristic as
        // the writer - a plain numeric field (e.g. CloudWatch Period) must pass through
        // completely unchanged, exactly mirroring nonTimestampNumberIsNotTagged below for
        // the write direction.
        JsonNode node = jsonMapper.createObjectNode().put("Period", 60);

        JsonNode normalized = AwsJsonCborController.normalizeCborTimestampsFromMillis(node);

        assertEquals(60L, normalized.path("Period").asLong());
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
