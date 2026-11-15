package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XRayTraceIdsTest {

    @Test
    void traceIdHasTheRootShapeAndEmbedsTheEpochSeconds() {
        String id = XRayTraceIds.traceId(0x6af70000L);

        assertTrue(id.matches("1-6af70000-[0-9a-f]{24}"), id);
    }

    @Test
    void traceIdPadsSmallEpochSecondsToEightDigits() {
        assertTrue(XRayTraceIds.traceId(255).startsWith("1-000000ff-"));
    }

    @Test
    void traceIdsDiffer() {
        assertNotEquals(XRayTraceIds.traceId(1), XRayTraceIds.traceId(1));
    }

    @Test
    void segmentIdIsSixteenLowercaseHexDigitsAndNeverAllZeros() {
        for (int i = 0; i < 1000; i++) {
            String id = XRayTraceIds.segmentId();
            assertEquals(16, id.length());
            assertTrue(id.matches("[0-9a-f]{16}"), id);
            assertNotEquals("0000000000000000", id);
        }
    }
}
