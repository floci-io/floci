package io.github.hectorvent.floci.services.ses;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test signature is obtained from {@link SesContentScan#signature()} and never spelled out
 * here, so this source file is as safe to check out as the production one.
 */
class SesContentScanTest {

    private static final String SIGNATURE = SesContentScan.signature();

    @Test
    void assembledSignatureHasTheDocumentedLength() {
        assertEquals(68, SIGNATURE.length());
    }

    @Test
    void cleanTextIsNotFlagged() {
        assertFalse(SesContentScan.containsTestVirus("hello", "<p>hello</p>", null));
    }

    @Test
    void signatureInsideATextBodyIsFlagged() {
        assertTrue(SesContentScan.containsTestVirus(null, "prefix " + SIGNATURE + " suffix"));
    }
}
