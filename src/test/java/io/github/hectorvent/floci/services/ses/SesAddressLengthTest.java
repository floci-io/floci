package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The normalization rules of {@link SesAddressLength#require(String)} and the header splitting of
 * {@link SesAddressLength#splitAddressList(String)}. The ASCII, non-ASCII, RFC 822 special,
 * quoted-name, UTF-16, encoded-word and escaped-quote cases are probe-confirmed; the remaining edge
 * cases pin Floci's own handling of inputs that were not probed.
 */
class SesAddressLengthTest {

    private static final String DOMAIN = "example.com";
    private static final String SENDER = "sender@" + DOMAIN;
    private static final String SUFFIX = " <" + SENDER + ">";

    @Test
    void nullAndBlankAreIgnored() {
        assertDoesNotThrow(() -> SesAddressLength.require(null));
        assertDoesNotThrow(() -> SesAddressLength.require("   "));
    }

    @Test
    void bareAddressLimitIsInclusive() {
        assertDoesNotThrow(() -> SesAddressLength.require(address(320)));
        assertRejects(address(321), address(321));
    }

    @Test
    void asciiNameIsMeasuredWithoutTheCallersQuotes() {
        String name = "N".repeat(320 - SUFFIX.length());

        assertDoesNotThrow(() -> SesAddressLength.require("\"" + name + "\"" + SUFFIX));
        assertRejects("\"" + name + "N\"" + SUFFIX, name + "N" + SUFFIX);
    }

    @Test
    void asciiNameExtraWhitespaceBeforeTheBracketDoesNotCount() {
        String name = "N".repeat(320 - SUFFIX.length());

        assertDoesNotThrow(() -> SesAddressLength.require(name + "   <" + SENDER + ">"));
    }

    @Test
    void nonAsciiNameIsMeasuredQuoted() {
        String name = "山".repeat(320 - SUFFIX.length() - 2);

        assertDoesNotThrow(() -> SesAddressLength.require(name + SUFFIX));
        assertRejects(name + "山" + SUFFIX, "\"" + name + "山\"" + SUFFIX);
    }

    @Test
    void nameWithSpecialIsMeasuredQuoted() {
        String name = "N".repeat(320 - SUFFIX.length() - 3) + ",";

        assertDoesNotThrow(() -> SesAddressLength.require(name + SUFFIX));
        assertRejects("N" + name + SUFFIX, "\"N" + name + "\"" + SUFFIX);
    }

    @Test
    void lengthIsCountedInUtf16Units() {
        int emoji = (320 - SUFFIX.length() - 2) / 2;

        assertDoesNotThrow(() -> SesAddressLength.require("😀".repeat(emoji) + SUFFIX));
        assertRejects("😀".repeat(emoji + 1) + SUFFIX, "\"" + "😀".repeat(emoji + 1) + "\"" + SUFFIX);
    }

    @Test
    void encodedWordNameIsMeasuredUndecoded() {
        String encoded = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(
                "山".repeat(90).getBytes(StandardCharsets.UTF_8)) + "?=" + SUFFIX;

        assertRejects(encoded, encoded);
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertDoesNotThrow(() -> SesAddressLength.require("  " + address(320) + "  "));
    }

    @Test
    void angleOnlyAddressIsMeasuredWithoutTheBrackets() {
        assertDoesNotThrow(() -> SesAddressLength.require("<" + address(320) + ">"));
        assertRejects("<" + address(321) + ">", address(321));
    }

    @Test
    void emptyAngleAddressKeepsTheName() {
        String name = "N".repeat(321 - " <>".length());

        assertRejects(name + " <>", name + " <>");
    }

    @Test
    void unclosedAngleBracketIsMeasuredAsWritten() {
        String input = "N".repeat(321 - SUFFIX.length() + 1) + " <" + SENDER;

        assertRejects(input + " ", input);
    }

    @Test
    void angleBracketInsideQuotedNameSplitsAtTheLastBracket() {
        String name = "a<b" + "N".repeat(320 - SUFFIX.length() - 5);

        assertDoesNotThrow(() -> SesAddressLength.require("\"" + name + "\"" + SUFFIX));
        assertRejects("\"" + name + "N\"" + SUFFIX, "\"" + name + "N\"" + SUFFIX);
    }

    @Test
    void escapedQuotesInNameAreKeptAsWritten() {
        String name = "a \\\"b\\\" " + "N".repeat(321 - SUFFIX.length() - 10);

        assertRejects("\"" + name + "\"" + SUFFIX, "\"" + name + "\"" + SUFFIX);
    }

    @Test
    void tabInNameDoesNotForceQuoting() {
        String name = "a\tb" + "N".repeat(320 - SUFFIX.length() - 3);

        assertDoesNotThrow(() -> SesAddressLength.require(name + SUFFIX));
    }

    @Test
    void controlCharacterInNameForcesQuoting() {
        String name = "a\u0001b" + "N".repeat(320 - SUFFIX.length() - 3);

        assertRejects(name + SUFFIX, "\"" + name + "\"" + SUFFIX);
    }

    @Test
    void splitAddressListSeparatesAddressesAtTopLevelCommas() {
        assertEquals(List.of("a@b.com", " \"Doe, John\" <c@d.com>", " \"x \\\"y, z\\\"\" <e@f.com>"),
                SesAddressLength.splitAddressList("a@b.com, \"Doe, John\" <c@d.com>, \"x \\\"y, z\\\"\" <e@f.com>"));
    }

    @Test
    void splitAddressListKeepsCommasInsideAngleBrackets() {
        assertEquals(List.of("Name <a,b@c.com>"), SesAddressLength.splitAddressList("Name <a,b@c.com>"));
    }

    @Test
    void splitAddressListDropsGroupSyntax() {
        assertEquals(List.of(" a@b.com", " c@d.com"),
                SesAddressLength.splitAddressList("team: a@b.com, c@d.com;"));
        assertEquals(List.of(), SesAddressLength.splitAddressList("undisclosed-recipients:;"));
    }

    private static void assertRejects(String input, String reported) {
        AwsException e = assertThrows(AwsException.class, () -> SesAddressLength.require(input));
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals("Address length is more than 320 characters long: '" + reported + "'.", e.getMessage());
    }

    private static String address(int length) {
        return "a".repeat(length - DOMAIN.length() - 1) + "@" + DOMAIN;
    }
}
