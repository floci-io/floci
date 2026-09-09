package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, schema-less unit test for {@link AwsJsonCborController}'s two CBOR timestamp
 * dialects.
 * <p>
 * There are genuinely two different tag(1) conventions sharing the same CBOR tag
 * mechanism, not one protocol with an inconsistency (see floci-io/floci#2368's
 * resolution):
 * <ul>
 *   <li>{@link AwsJsonCborController#nodeToSmithyCbor} / plain {@link
 *   AwsJsonCborController#bodyToJson} - the smithy-rpc-v2-cbor protocol (CloudWatch
 *   Metrics, confirmed via a Go SDK compat test), which uses RFC 8949 section 3.4.2's
 *   own tag(1) convention: plain, unscaled epoch seconds.</li>
 *   <li>{@link AwsJsonCborController#nodeToLegacyCbor} / {@link
 *   AwsJsonCborController#normalizeLegacyCborTimestampsFromMillis} - the older,
 *   separate {@code application/x-amz-cbor-1.1} dialect (Kinesis, confirmed via a real
 *   wire capture), whose tag(1) is epoch <em>milliseconds</em> instead.</li>
 * </ul>
 * This file previously (before #2368's resolution was itself corrected) asserted that
 * {@code nodeToSmithyCbor} scales to milliseconds - that was wrong for the
 * smithy-rpc-v2-cbor protocol it actually covers (it broke CloudWatch's
 * {@code GetMetricStatistics}/{@code GetMetricData}, whose timestamps came back
 * outside any query window once wrongly divided by 1000) and is why the two dialects
 * now have distinctly-named methods rather than one shared, ambiguous pair. CBOR tag 1
 * is the single byte {@code 0xC1} (major type 6, value 1) immediately preceding the
 * encoded number. These assertions are deterministic and do not boot Quarkus.
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

    // --- smithy-rpc-v2-cbor (nodeToSmithyCbor) - plain, unscaled epoch seconds ---

    @Test
    void smithyRpcV2ScalarTimestampIsTaggedAsIntegerUnscaled() throws Exception {
        JsonNode node = jsonMapper.readTree("{\"Timestamp\": 1700000000}");

        byte[] cbor = AwsJsonCborController.nodeToSmithyCbor(node);

        assertEquals(1, countTag1(cbor), "scalar Timestamp must be tagged exactly once");
        int tagIndex = indexOf(cbor, CBOR_TAG_1);
        assertTrue(tagIndex >= 0, "tag(1) byte must be present");
        assertTrue(tagIndex + 1 < cbor.length, "tag(1) byte must not be the last byte in the output");
        assertEquals(CBOR_MAJOR_UNSIGNED_INT, majorType(cbor[tagIndex + 1]),
                "integral epoch seconds must be encoded as a CBOR integer, not a float");

        // smithy-rpc-v2-cbor's tag(1) is plain epoch seconds - the value round-trips
        // unchanged, unlike the legacy dialect's millisecond scaling below.
        JsonNode decoded = cborMapper.readTree(cbor);
        assertTrue(decoded.path("Timestamp").isIntegralNumber(), "decoded timestamp must be integral");
        assertEquals(1700000000L, decoded.path("Timestamp").asLong());
    }

    @Test
    void smithyRpcV2FractionalTimestampIsTaggedAsFloat() throws Exception {
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
    void smithyRpcV2NonTimestampNumberIsNotTagged() throws Exception {
        // A plain numeric field (e.g. CloudWatch Period) must remain an untagged integer.
        JsonNode node = jsonMapper.readTree("{\"Period\": 60}");

        byte[] cbor = AwsJsonCborController.nodeToSmithyCbor(node);

        assertEquals(0, countTag1(cbor), "non-timestamp numbers must not carry a tag(1) byte");
        assertFalse(contains(cbor, CBOR_TAG_1));
        assertEquals(60L, cborMapper.readTree(cbor).path("Period").asLong());
    }

    @Test
    void smithyRpcV2DecodedRequestTimestampIsNotScaled() throws Exception {
        // The read side (plain bodyToJson, no legacyDialect) must leave a decoded
        // timestamp-shaped field completely untouched - this is the exact regression
        // that broke CloudWatch's GetMetricStatistics/GetMetricData: a real value
        // wrongly divided by 1000 landed outside any query window.
        byte[] cbor = AwsJsonCborController.nodeToSmithyCbor(jsonMapper.readTree("{\"Timestamp\": 1700000000}"));

        JsonNode decoded = cborMapper.readTree(cbor);

        assertEquals(1700000000L, decoded.path("Timestamp").asLong());
    }

    // --- legacy application/x-amz-cbor-1.1 (nodeToLegacyCbor) - epoch milliseconds ---

    @Test
    void legacyScalarTimestampIsTaggedAsIntegerMillis() throws Exception {
        JsonNode node = jsonMapper.readTree("{\"Timestamp\": 1700000000}");

        byte[] cbor = AwsJsonCborController.nodeToLegacyCbor(node);

        // Exactly one tag(1) byte, and it is immediately followed by an unsigned integer
        // (major type 0) — i.e. integer-ness is preserved, not coerced to a float.
        assertEquals(1, countTag1(cbor), "scalar Timestamp must be tagged exactly once");
        int tagIndex = indexOf(cbor, CBOR_TAG_1);
        assertTrue(tagIndex >= 0, "tag(1) byte must be present");
        assertTrue(tagIndex + 1 < cbor.length, "tag(1) byte must not be the last byte in the output");
        assertEquals(CBOR_MAJOR_UNSIGNED_INT, majorType(cbor[tagIndex + 1]),
                "epoch millis must be encoded as a CBOR integer, not a float");

        // 1700000000 epoch SECONDS (this codebase's internal convention) must be written
        // under tag(1) as 1700000000000 epoch MILLISECONDS (the legacy dialect's real
        // convention) — not the same number unchanged, which is exactly the
        // pre-#2368-fix bug for this dialect.
        JsonNode decoded = cborMapper.readTree(cbor);
        assertTrue(decoded.path("Timestamp").isIntegralNumber(), "decoded timestamp must be integral");
        assertEquals(1700000000000L, decoded.path("Timestamp").asLong());
    }

    @Test
    void legacyTimestampListTagsEveryElementAsIntegerMillis() throws Exception {
        JsonNode node = jsonMapper.readTree("{\"Timestamps\": [1700000000, 1700000060]}");

        byte[] cbor = AwsJsonCborController.nodeToLegacyCbor(node);

        assertEquals(2, countTag1(cbor), "each Timestamps element must be tagged with tag(1)");
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
    void legacySuffixedTimestampFieldIsTagged() throws Exception {
        // Future-proofing: the heuristic matches by "Timestamp" suffix.
        JsonNode node = jsonMapper.readTree("{\"StateUpdatedTimestamp\": 1700000000}");

        byte[] cbor = AwsJsonCborController.nodeToLegacyCbor(node);

        assertEquals(1, countTag1(cbor), "a *Timestamp-suffixed field must be tagged");
        assertEquals(1700000000000L, cborMapper.readTree(cbor).path("StateUpdatedTimestamp").asLong());
    }

    @Test
    void legacyMillisecondPrecisionFractionalTimestampBecomesAnExactIntegerMillisValue() throws Exception {
        // A fractional epoch-SECONDS value with real AWS's own millisecond-precision limit
        // (3 decimal places) converts to an EXACT integer number of milliseconds - not a
        // float - once written under tag(1). This is the realistic case (AWS timestamps
        // never carry sub-millisecond precision) and the actual reproducer from
        // floci-io/floci#2368: 1700000000.712s -> 1700000000712ms, exactly.
        JsonNode node = jsonMapper.readTree("{\"Timestamp\": 1700000000.712}");

        byte[] cbor = AwsJsonCborController.nodeToLegacyCbor(node);

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
    void legacyRoundTripsThroughEncodeAndDecodeBackToTheOriginalEpochSecondsValue() throws Exception {
        // The actual end-to-end guarantee that matters: whatever
        // normalizeLegacyCborTimestampsFromMillis decodes back out must equal what
        // nodeToLegacyCbor was given, for both an integral and a millisecond-precision
        // fractional epoch-seconds value - proving the two directions are genuine
        // inverses of each other, not just independently plausible.
        JsonNode integral = jsonMapper.readTree("{\"Timestamp\": 1700000000}");
        JsonNode fractional = jsonMapper.readTree("{\"Timestamp\": 1700000000.712}");

        JsonNode decodedIntegral = AwsJsonCborController.normalizeLegacyCborTimestampsFromMillis(
                cborMapper.readTree(AwsJsonCborController.nodeToLegacyCbor(integral)));
        JsonNode decodedFractional = AwsJsonCborController.normalizeLegacyCborTimestampsFromMillis(
                cborMapper.readTree(AwsJsonCborController.nodeToLegacyCbor(fractional)));

        assertEquals(1700000000, decodedIntegral.path("Timestamp").decimalValue().doubleValue());
        assertEquals(1700000000.712, decodedFractional.path("Timestamp").decimalValue().doubleValue());
    }

    @Test
    void legacyNonTimestampFieldIsNotConvertedByNormalization() {
        // normalizeLegacyCborTimestampsFromMillis must use the identical field-name
        // heuristic as the writer - a plain numeric field (e.g. CloudWatch Period) must
        // pass through completely unchanged.
        JsonNode node = jsonMapper.createObjectNode().put("Period", 60);

        JsonNode normalized = AwsJsonCborController.normalizeLegacyCborTimestampsFromMillis(node);

        assertEquals(60L, normalized.path("Period").asLong());
    }

    @Test
    void legacyNonTimestampNumberIsNotTagged() throws Exception {
        // A plain numeric field (e.g. CloudWatch Period) must remain an untagged integer.
        JsonNode node = jsonMapper.readTree("{\"Period\": 60}");

        byte[] cbor = AwsJsonCborController.nodeToLegacyCbor(node);

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
