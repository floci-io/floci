package io.github.hectorvent.floci.services.ssm;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SsmCommandTimeoutTest {
    @Test
    void markerIsRecognizedAcrossFramesAfterOutputLimit() {
        SsmCommandTimeout timeout = new SsmCommandTimeout(3);
        byte[] marker = marker(timeout).getBytes(StandardCharsets.UTF_8);
        timeout.writeError("abcdef".getBytes(StandardCharsets.UTF_8));
        for (byte value : marker) {
            timeout.writeError(new byte[]{value});
        }
        assertTrue(timeout.timedOut());
        assertEquals("abc", timeout.standardError());
    }

    @Test
    void markerIsRemovedWithoutChangingSurroundingUnicodeOrPartialMatches() {
        SsmCommandTimeout timeout = new SsmCommandTimeout(100);
        String output = "é\036floci-x" + marker(timeout) + "後\036partial";
        for (byte value : output.getBytes(StandardCharsets.UTF_8)) {
            timeout.writeError(new byte[]{value});
        }
        assertTrue(timeout.timedOut());
        assertEquals("é\036floci-x後\036partial", timeout.standardError());
    }

    @Test
    void ordinaryTextAndDifferentMarkersAreNotTimeouts() {
        SsmCommandTimeout timeout = new SsmCommandTimeout(100);
        String text = marker(new SsmCommandTimeout(100)) + "natural exit 124";
        timeout.writeError(text.getBytes(StandardCharsets.UTF_8));
        assertFalse(timeout.timedOut());
        assertEquals(text, timeout.standardError());
    }

    private static String marker(SsmCommandTimeout timeout) {
        String script = timeout.wrap("true", 5);
        return script.substring(script.indexOf('\036'), script.indexOf('\037') + 1);
    }
}
