package io.github.hectorvent.floci.services.ses.address;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JavaMail {@code MimeUtility.encodeWord} rules {@link SesEncodedWord} follows: which names are
 * encoded, the B or Q choice by character count, Q's word specials, and the recursive halving.
 */
class SesEncodedWordTest {

    @Test
    void onlyNonAsciiOrControlCharactersNeedEncoding() {
        assertFalse(SesEncodedWord.needsEncoding("Alice B. (x)"));
        assertFalse(SesEncodedWord.needsEncoding("Alice\tB"));
        assertTrue(SesEncodedWord.needsEncoding("山田"));
        assertTrue(SesEncodedWord.needsEncoding("Alice\u007f"));
        assertTrue(SesEncodedWord.needsEncoding("Alice\u0001"));
    }

    @Test
    void anEvenSplitOfAsciiAndNonAsciiIsB() {
        assertEquals("=?UTF-8?B?YeWxsQ==?=", SesEncodedWord.encode("a山"));
    }

    @Test
    void qEscapesWordSpecialsAndWritesASpaceAsUnderscore() {
        assertEquals("=?UTF-8?Q?Al_B=2E=E5=B1=B1?=", SesEncodedWord.encode("Al B.山"));
    }

    @Test
    void halvingNeverSplitsASurrogatePair() {
        String encoded = SesEncodedWord.encode("a" + "😀".repeat(16));

        assertEquals("=?UTF-8?B?YfCfmIDwn5iA8J+YgPCfmIDwn5iA8J+YgPCfmIA=?="
                + " =?UTF-8?B?8J+YgPCfmIDwn5iA8J+YgPCfmIDwn5iA8J+YgPCfmIDwn5iA?=", encoded);
    }
}
