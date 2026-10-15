package io.github.hectorvent.floci.services.ses.address;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The address forms of {@link SesAddressSyntax#violation(String)}. The four messages and the
 * accepted Punycode, dotless, display-name, encoded-word and comment forms are probe-confirmed;
 * the quoted local part follows the probe-confirmed CVET handling, and the scan order and the
 * first-{@code @} split follow JavaMail's {@code InternetAddress} check, whose messages AWS returns.
 */
class SesAddressSyntaxTest {

    private static final String MISSING_FINAL_DOMAIN = "Missing final '@domain'";
    private static final String MISSING_DOMAIN = "Missing domain";
    private static final String LOCAL_CONTROL = "Local address contains control or whitespace";
    private static final String DOMAIN_CONTROL = "Domain contains control or whitespace";

    @Test
    void nullAndBlankAreLeftToTheRequiredFieldCheck() {
        assertNull(SesAddressSyntax.violation(null));
        assertNull(SesAddressSyntax.violation("   "));
    }

    @Test
    void addressWithoutAtIsMissingFinalDomain() {
        assertEquals(MISSING_FINAL_DOMAIN, SesAddressSyntax.violation("sender.example.com"));
        assertEquals(MISSING_FINAL_DOMAIN, SesAddressSyntax.violation("Alice <sender.example.com>"));
    }

    @Test
    void addressWithEmptyDomainIsMissingDomain() {
        assertEquals(MISSING_DOMAIN, SesAddressSyntax.violation("sender@"));
        assertEquals(MISSING_DOMAIN, SesAddressSyntax.violation("Alice <sender@>"));
    }

    @Test
    void nonAsciiLocalPartIsRejected() {
        assertEquals(LOCAL_CONTROL, SesAddressSyntax.violation("やまだ@example.com"));
        assertEquals(LOCAL_CONTROL, SesAddressSyntax.violation("Alice <やまだ@example.com>"));
    }

    @Test
    void whitespaceInLocalPartIsRejected() {
        assertEquals(LOCAL_CONTROL, SesAddressSyntax.violation("a b@example.com"));
    }

    @Test
    void nonAsciiDomainIsRejected() {
        assertEquals(DOMAIN_CONTROL, SesAddressSyntax.violation("sender@例え.jp"));
    }

    @Test
    void localPartIsReportedBeforeDomain() {
        assertEquals(LOCAL_CONTROL, SesAddressSyntax.violation("やまだ@例え.jp"));
    }

    @Test
    void punycodeAndDotlessDomainsPass() {
        assertNull(SesAddressSyntax.violation("sender@xn--r8jz45g.jp"));
        assertNull(SesAddressSyntax.violation("sender@b"));
    }

    @Test
    void nonAsciiDisplayNamesPass() {
        assertNull(SesAddressSyntax.violation("山田 <sender@example.com>"));
        assertNull(SesAddressSyntax.violation("\"山田\" <sender@example.com>"));
        assertNull(SesAddressSyntax.violation("=?UTF-8?B?5bGx55Sw?= <sender@example.com>"));
    }

    @Test
    void nonAsciiCommentPasses() {
        assertNull(SesAddressSyntax.violation("sender@example.com (山田)"));
    }

    @Test
    void localPartIsScannedBeforeTheMissingAtIsReported() {
        assertEquals(LOCAL_CONTROL, SesAddressSyntax.violation("やまだ"));
        assertEquals(LOCAL_CONTROL, SesAddressSyntax.violation("a b"));
    }

    @Test
    void firstUnquotedAtSeparatesTheDomain() {
        assertEquals(DOMAIN_CONTROL, SesAddressSyntax.violation("a@例え@example.com"));
        assertNull(SesAddressSyntax.violation("a@b@example.com"));
    }

    @Test
    void commentAfterTheAngleBracketsIsIgnored() {
        assertNull(SesAddressSyntax.violation("Alice <sender@example.com> (work)"));
        assertEquals(LOCAL_CONTROL, SesAddressSyntax.violation("Alice <やまだ@example.com> (work)"));
    }

    @Test
    void atInsideAQuotedLocalPartIsNotTheSeparator() {
        assertNull(SesAddressSyntax.violation("\"a@b\"@example.com"));
        assertNull(SesAddressSyntax.violation("\"a b\"@example.com"));
        assertEquals(MISSING_FINAL_DOMAIN, SesAddressSyntax.violation("\"a@b\""));
    }

    @Test
    void newlineInsideAQuotedLocalPartIsAnInvalidAddress() {
        for (String local : new String[] {"\"a\nb\"", "\"a\r\nb\"", "\"a\rb\"", "\"a\r\n b\""}) {
            String address = local + "@example.com";
            assertEquals("Invalid email address " + address + ".", SesAddressSyntax.violation(address));
        }
    }

    @Test
    void lessThanInsideAQuotedLocalPartIsNotTheAngleBracket() {
        assertNull(SesAddressSyntax.violation("Display <\"a<b\"@example.com>"));
        assertEquals(DOMAIN_CONTROL, SesAddressSyntax.violation("Display <\"a<b\"@例え.jp>"));
    }

    @Test
    void requireThrowsInvalidParameterValue() {
        AwsException e = assertThrows(AwsException.class, () -> SesAddressSyntax.require("sender@"));

        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        assertEquals(MISSING_DOMAIN, e.getMessage());
        assertDoesNotThrow(() -> SesAddressSyntax.require("sender@example.com"));
    }

    @Test
    void javaMailStringRendersTheProbedForms() {
        assertEquals("probe.example.invalid", SesAddressSyntax.javaMailString("probe.example.invalid"));
        assertEquals("Alice <probe.example.invalid>", SesAddressSyntax.javaMailString("Alice <probe.example.invalid>"));
        assertEquals("Alice <probe.example.invalid>", SesAddressSyntax.javaMailString("probe.example.invalid (Alice)"));
        assertEquals("<\"probe example\">", SesAddressSyntax.javaMailString("\"probe example\""));
        assertEquals("=?UTF-8?B?5bGx55Sw?= <probe.example.invalid>",
                SesAddressSyntax.javaMailString("山田 <probe.example.invalid>"));
        assertEquals("\"Alice, B\" <probe.example.invalid>",
                SesAddressSyntax.javaMailString("\"Alice, B\" <probe.example.invalid>"));
    }

    @Test
    void javaMailStringQuotesTheNameOnlyWhenItHoldsASpecial() {
        assertEquals("Alice B <probe.example.invalid>",
                SesAddressSyntax.javaMailString("\"Alice B\" <probe.example.invalid>"));
        assertEquals("\"Alice B.\" <probe.example.invalid>",
                SesAddressSyntax.javaMailString("Alice B. <probe.example.invalid>"));
        assertEquals("\"Alice \\\"B\\\"\" <probe.example.invalid>",
                SesAddressSyntax.javaMailString("\"Alice \\\"B\\\"\" <probe.example.invalid>"));
    }

    @Test
    void javaMailStringKeepsAnEncodedWordAndPrefersTheDisplayNameToAComment() {
        assertEquals("=?UTF-8?B?5bGx55Sw?= <probe.example.invalid>",
                SesAddressSyntax.javaMailString("=?UTF-8?B?5bGx55Sw?= <probe.example.invalid>"));
        assertEquals("Alice <probe.example.invalid>",
                SesAddressSyntax.javaMailString("Alice <probe.example.invalid> (Bob)"));
        assertEquals("\"Alice (Bob)\" <probe.example.invalid>",
                SesAddressSyntax.javaMailString("Alice (Bob) <probe.example.invalid>"));
        assertEquals("Bob <probe.example.invalid>", SesAddressSyntax.javaMailString("(Bob) <probe.example.invalid>"));
        assertEquals("Alice <probe.example.invalid>",
                SesAddressSyntax.javaMailString("(Bob) Alice <probe.example.invalid>"));
        assertEquals("probe.example.invalid", SesAddressSyntax.javaMailString("<probe.example.invalid>"));
    }

    @Test
    void javaMailStringEncodesAMostlyAsciiNameAsQAndHalvesALongName() {
        assertEquals("=?UTF-8?Q?Alice_=E5=B1=B1=E7=94=B0?= <probe.example.invalid>",
                SesAddressSyntax.javaMailString("Alice 山田 <probe.example.invalid>"));
        String word = "=?UTF-8?B?5bGx55Sw5bGx55Sw5bGx55Sw5bGx55Sw5bGx55Sw?=";
        assertEquals(String.join(" ", word, word, word, word) + " <probe.example.invalid>",
                SesAddressSyntax.javaMailString("山田".repeat(20) + " <probe.example.invalid>"));
    }

    @Test
    void javaMailStringDropsABlankDisplayName() {
        assertEquals("probe.example.invalid", SesAddressSyntax.javaMailString("\"\" <probe.example.invalid>"));
        assertEquals("probe.example.invalid", SesAddressSyntax.javaMailString("\" \" <probe.example.invalid> (Alice)"));
        assertEquals("Alice <probe.example.invalid>", SesAddressSyntax.javaMailString("<probe.example.invalid> (Alice)"));
    }

    @Test
    void javaMailStringUnquotesAPhraseOnlyAtItsOuterQuotesLikeJavaMail() {
        assertEquals("\"Alice\\\" \\\"Bob\" <probe.example.invalid>",
                SesAddressSyntax.javaMailString("\"Alice\" \"Bob\" <probe.example.invalid>"));
    }
}
