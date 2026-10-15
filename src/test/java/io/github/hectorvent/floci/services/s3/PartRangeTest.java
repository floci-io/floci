package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.s3.model.Part;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartRangeTest {

    @Test
    void aPartNumberOfOneOrMoreIsRead() {
        assertEquals(BigInteger.ONE, PartRange.parsePartNumber("1"));
        assertEquals(BigInteger.valueOf(10000), PartRange.parsePartNumber("10000"));
        // Past 10,000, or past what a long holds, is left to the part lookup, which finds no such part.
        assertEquals(BigInteger.valueOf(10001), PartRange.parsePartNumber("10001"));
        assertEquals(new BigInteger("9223372036854775808"), PartRange.parsePartNumber("9223372036854775808"));
    }

    @Test
    void aPartNumberBelowOneOrNotANumberIsInvalidArgumentNamingIt() {
        for (String value : List.of("0", "-1", "abc", "", "1.5")) {
            AwsException error = assertThrows(AwsException.class, () -> PartRange.parsePartNumber(value));

            assertEquals("InvalidArgument", error.getErrorCode());
            assertEquals(400, error.getHttpStatus());
            assertEquals("Part number must be an integer between 1 and 10000, inclusive", error.getMessage());
            assertEquals(Map.of("ArgumentName", "partNumber", "ArgumentValue", value), error.getExtendedData());
        }
    }

    @Test
    void eachPartStartsWhereTheOneBeforeItEnded() {
        // Part numbers need not be consecutive; a part is found by its own number.
        S3Object obj = multipartObject(new Part(1, "\"a\"", 5), new Part(2, "\"b\"", 3), new Part(4, "\"c\"", 2));

        PartRange second = PartRange.of(obj, BigInteger.TWO).orElseThrow();
        assertEquals(5, second.first());
        assertEquals(3, second.length());
        assertSame(obj.getParts().get(1), second.part());
        assertEquals("bytes 5-7/10", second.contentRange(10));

        PartRange last = PartRange.of(obj, BigInteger.valueOf(4)).orElseThrow();
        assertEquals(8, last.first());
        assertEquals(2, last.length());
        assertEquals("bytes 8-9/10", last.contentRange(10));

        assertTrue(PartRange.of(obj, BigInteger.valueOf(3)).isEmpty());
        assertTrue(PartRange.of(obj, BigInteger.valueOf(5)).isEmpty());
        assertTrue(PartRange.of(obj, BigInteger.valueOf(10001)).isEmpty());
        assertTrue(PartRange.of(obj, new BigInteger("9223372036854775808")).isEmpty());
        assertEquals(3, PartRange.partCount(obj));
    }

    @Test
    void anObjectStoredInOneRequestIsItsOwnOnlyPart() {
        S3Object obj = new S3Object("bucket", "key", 8, "text/plain", "\"etag\"");

        PartRange only = PartRange.of(obj, BigInteger.ONE).orElseThrow();
        assertEquals(0, only.first());
        assertEquals(8, only.length());
        assertNull(only.part());
        assertTrue(only.coversObject(8));
        assertEquals("bytes 0-7/8", only.contentRange(8));

        assertTrue(PartRange.of(obj, BigInteger.TWO).isEmpty());
        assertEquals(1, PartRange.partCount(obj));
    }

    @Test
    void aPartOfNoBytesHasNoContentRange() {
        S3Object obj = new S3Object("bucket", "key", 0, "text/plain", "\"etag\"");
        PartRange empty = PartRange.of(obj, BigInteger.ONE).orElseThrow();

        assertNull(empty.contentRange(0));
        assertTrue(empty.coversObject(0));
    }

    @Test
    void onlyAPartHoldingAllOfTheObjectCoversIt() {
        S3Object obj = multipartObject(new Part(1, "\"a\"", 5), new Part(2, "\"b\"", 3));

        assertFalse(PartRange.of(obj, BigInteger.ONE).orElseThrow().coversObject(8));
        assertFalse(PartRange.of(obj, BigInteger.TWO).orElseThrow().coversObject(8));
    }

    @Test
    void aSliceIsThePartsBytes() throws IOException {
        S3Object obj = multipartObject(new Part(1, "\"a\"", 5), new Part(2, "\"b\"", 3));
        InputStream whole = new ByteArrayInputStream("helloabc".getBytes(StandardCharsets.UTF_8));

        try (InputStream part = PartRange.of(obj, BigInteger.TWO).orElseThrow().slice(whole)) {
            assertEquals("abc", new String(part.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static S3Object multipartObject(Part... parts) {
        long size = 0;
        for (Part part : parts) {
            size += part.getSize();
        }
        S3Object obj = new S3Object("bucket", "key", size, "application/octet-stream", "\"etag-" + parts.length + "\"");
        obj.setParts(List.of(parts));
        return obj;
    }
}
