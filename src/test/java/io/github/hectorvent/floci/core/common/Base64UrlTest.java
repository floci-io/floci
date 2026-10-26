package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Base64UrlTest {

    @Test
    void encodesWithTheUrlAlphabetAndNoPadding() {
        assertEquals("-_8", Base64Url.encode(new byte[] {(byte) 0xfb, (byte) 0xff}));
    }

    @Test
    void encodesTextAsUtf8() {
        assertEquals("w6k", Base64Url.encode("é"));
    }

    @Test
    void decodesUnpaddedTextAsUtf8() {
        assertEquals("é", Base64Url.decode("w6k"));
    }

    @Test
    void decodesPaddedText() {
        assertEquals("é", Base64Url.decode("w6k="));
    }

    @Test
    void refusesTextThatIsNotBase64Url() {
        assertThrows(IllegalArgumentException.class, () -> Base64Url.decode("!!!"));
    }
}
