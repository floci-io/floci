package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VendoredPatternsTest {

    /** STS's {@code arnType}, as botocore writes it and as the vendored file carries it. */
    private static final String ARN_PATTERN = "[\\u0009\\u000A\\u000D\\u0020-\\u007E\\u0085"
            + "\\u00A0-\\uD7FF\\uE000-\\uFFFD\\u10000-\\u10FFFF]+";

    private static final String ARN = "arn:aws:iam::000000000000:role/r";

    @Test
    void aWideEscapeIsReadAsTheCodePointItSpells() {
        assertEquals("[\\x{10000}-\\x{10FFFF}]", VendoredPatterns.rewrite("[\\u10000-\\u10FFFF]"));
        assertEquals("[\\x{10000}]", VendoredPatterns.rewrite("[\\u10000]"),
                "five digits name a code point of their own");
    }

    @Test
    void anEscapeJavaReadsItselfIsHandedBackUntouched() {
        String narrow = "[\\u0009\\u0020-\\u007E]+";

        assertSame(narrow, VendoredPatterns.rewrite(narrow));
        assertSame("[a-z]+", VendoredPatterns.rewrite("[a-z]+"));
    }

    /**
     * Five digits under U+10000 are four digits and a literal, which is how Java reads them and
     * what the model must mean by them, so the escape is handed back as it was written. The string
     * is rebuilt rather than returned as it came, the escape having matched before its digits were
     * read, so this is equality rather than identity.
     */
    @Test
    void digitsNamingNoCodePointAreLeftAsTheModelWroteThem() {
        String narrow = "[\\u00412]";

        assertEquals(narrow, VendoredPatterns.rewrite(narrow));
    }

    /**
     * The class the model declares admits every supplementary character and excludes the C1
     * controls other than U+0085. Compiled as the model wrote it, Java reads the five-digit escape
     * as four digits and a literal zero, which turns the tail of the class into a range from
     * {@code 0} to U+10FF and flips both answers: that is the reading this object exists to fix,
     * so both are pinned here.
     */
    @Test
    void theCompiledClassAdmitsTheCodePointsTheModelAdmits() {
        String supplementary = ARN + Character.toString(0x1F600);
        String c1Control = ARN + Character.toString(0x81);

        Pattern asTheModelMeansIt = VendoredPatterns.compile(ARN_PATTERN);
        assertTrue(asTheModelMeansIt.matcher(supplementary).matches());
        assertFalse(asTheModelMeansIt.matcher(c1Control).matches());

        Pattern asWritten = Pattern.compile(ARN_PATTERN);
        assertFalse(asWritten.matcher(supplementary).matches(),
                "compiled as written, the class refuses what the model admits");
        assertTrue(asWritten.matcher(c1Control).matches(),
                "and admits what the model excludes");
    }
}
