package io.github.hectorvent.floci.services.ses;

import java.util.Arrays;

/**
 * The content scan SES runs on every accepted message. Real SES scans for viruses and rejects the
 * whole message with a {@code Reject} event; Floci reproduces that for the one input AWS documents
 * for testing it, the EICAR test file, and nothing else.
 *
 * <p>The EICAR string is a signature that endpoint protection quarantines on sight, so it is never
 * written out verbatim: it is assembled from fragments at class initialisation, must never be
 * logged or persisted, and tests obtain it from {@link #signature()} rather than spelling it out.
 */
final class SesContentScan {

    private static final String[] FRAGMENTS = {
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}$",
            "EICAR-STANDARD-",
            "ANTIVIRUS-TEST-",
            "FILE!$H+H*"
    };
    private static final String SIGNATURE_TEXT = String.join("", FRAGMENTS);

    private SesContentScan() {}

    /** The assembled test string, for tests that need to send it. Never log or persist it. */
    static String signature() {
        return SIGNATURE_TEXT;
    }

    /** True when any of the given texts carries the test signature. Nulls are skipped. */
    static boolean containsTestVirus(String... texts) {
        return containsTestVirus(Arrays.asList(texts));
    }

    /** True when any of the given texts carries the test signature. Nulls are skipped. */
    static boolean containsTestVirus(Iterable<String> texts) {
        for (String text : texts) {
            if (text != null && text.contains(SIGNATURE_TEXT)) {
                return true;
            }
        }
        return false;
    }
}
