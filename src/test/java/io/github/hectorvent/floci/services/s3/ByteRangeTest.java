package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.s3.ByteRange.Outcome;
import io.github.hectorvent.floci.services.s3.ByteRange.Resolution;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteRangeTest {

    @Test
    void aSingleRangeResolvesToItsBytes() {
        assertPart("bytes=0-9", 100, 0, 9);
        assertPart("bytes=90-", 100, 90, 99);
        assertPart("bytes=-10", 100, 90, 99);
        assertPart("bytes= 5-6 ", 100, 5, 6);
    }

    @Test
    void theRangeUnitMatchesInAnyCase() {
        assertPart("Bytes=0-9", 100, 0, 9);
        assertPart("BYTES=90-", 100, 90, 99);
        assertOutcome(Outcome.WHOLE, "byte=0-9", 100);
    }

    @Test
    void aByteRangeRunsForwardFromAFirstByteThatExists() {
        assertThrows(IllegalArgumentException.class, () -> new ByteRange(5, 2));
        assertThrows(IllegalArgumentException.class, () -> new ByteRange(-1, 2));
        assertEquals(1, new ByteRange(7, 7).length());
        assertEquals("bytes */100", ByteRange.unsatisfiedContentRange(100));
    }

    @Test
    void aRangeRunningPastTheEndStopsAtTheLastByte() {
        assertPart("bytes=95-200", 100, 95, 99);
        assertPart("bytes=-500", 100, 0, 99);
        assertPart("bytes=0-99999999999999999999", 100, 0, 99);
        assertPart("bytes=-99999999999999999999", 100, 0, 99);
    }

    @Test
    void offsetsPastTwoGibibytesResolve() {
        long size = 3L * 1024 * 1024 * 1024;
        ByteRange range = assertPart("bytes=3000000000-3000000009", size, 3_000_000_000L, 3_000_000_009L);

        assertEquals(10, range.length());
        assertEquals("bytes 3000000000-3000000009/" + size, range.contentRange(size));
    }

    @Test
    void aRangeStartingPastTheEndIsNotSatisfiable() {
        for (String header : List.of("bytes=100-", "bytes=100-200", "bytes=-0", "bytes=99999999999999999999-")) {
            assertOutcome(Outcome.NOT_SATISFIABLE, header, 100);
        }
        assertOutcome(Outcome.NOT_SATISFIABLE, "bytes=0-", 0);
        assertOutcome(Outcome.NOT_SATISFIABLE, "bytes=0-0", 0);
    }

    @Test
    void anythingButOneValidRangeIsInvalid() {
        for (String header : List.of("bytes=5-2", "bytes=0-1,4-5", "bytes=abc", "bytes=-", "bytes=1",
                "bytes=--5", "bytes=+1-2", "bytes=")) {
            assertOutcome(Outcome.INVALID, header, 100);
        }
    }

    @Test
    void noHeaderAnotherUnitOrASuffixOfAnEmptyObjectIsTheWholeObject() {
        assertOutcome(Outcome.WHOLE, null, 100);
        assertOutcome(Outcome.WHOLE, "items=0-1", 100);
        assertOutcome(Outcome.WHOLE, "bytes=-5", 0);
    }

    @Test
    void anIfRangeThatStillDescribesTheObjectLetsTheRangeApply() {
        Instant modified = Instant.parse("2015-10-21T07:28:00.250Z");

        assertTrue(ByteRange.rangeApplies(null, "\"abc\"", modified));
        assertTrue(ByteRange.rangeApplies("\"abc\"", "\"abc\"", modified));
        assertTrue(ByteRange.rangeApplies("\"abc-2\"", "\"abc-2\"", modified), "a multipart ETag, whole");
        assertTrue(ByteRange.rangeApplies("Wed, 21 Oct 2015 07:28:00 GMT", "\"abc\"", modified),
                "Last-Modified, to the second");
    }

    @Test
    void anIfRangeThatNoLongerDescribesTheObjectServesItWhole() {
        Instant modified = Instant.parse("2015-10-21T07:28:00.250Z");

        assertFalse(ByteRange.rangeApplies("\"old\"", "\"abc\"", modified));
        assertFalse(ByteRange.rangeApplies("\"2\"", "\"abc-2\"", modified), "a multipart suffix alone");
        assertFalse(ByteRange.rangeApplies("Tue, 20 Oct 2015 07:28:00 GMT", "\"abc\"", modified));
        assertFalse(ByteRange.rangeApplies("Thu, 22 Oct 2015 07:28:00 GMT", "\"abc\"", modified),
                "a date later than Last-Modified");
        for (String malformed : List.of("W/\"abc\"", "abc", "\"", "2015-10-21T07:28:00Z", "")) {
            assertFalse(ByteRange.rangeApplies(malformed, "\"abc\"", modified), malformed);
        }
    }

    @Test
    void aSliceReadsTheRangeFromAStreamOverTheWholeObject() throws IOException {
        byte[] object = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
        ByteRange range = assertPart("bytes=2-5", object.length, 2, 5);

        try (InputStream slice = range.slice(new ByteArrayInputStream(object))) {
            assertArrayEquals("cdef".getBytes(StandardCharsets.US_ASCII), slice.readAllBytes());
        }
    }

    private static ByteRange assertPart(String header, long size, long first, long last) {
        Resolution resolution = ByteRange.resolve(header, size);
        assertEquals(Outcome.PART, resolution.outcome(), header);
        assertEquals(new ByteRange(first, last), resolution.range(), header);
        return resolution.range();
    }

    private static void assertOutcome(Outcome outcome, String header, long size) {
        Resolution resolution = ByteRange.resolve(header, size);
        assertEquals(outcome, resolution.outcome(), header + " of " + size + " bytes");
        assertNull(resolution.range(), header);
    }
}
