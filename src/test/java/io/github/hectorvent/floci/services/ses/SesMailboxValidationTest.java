package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.SesMailboxValidation.Result;
import io.github.hectorvent.floci.services.ses.SesMailboxValidation.Verdict;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.services.ses.SesMailboxValidation.Verdict.HIGH;
import static io.github.hectorvent.floci.services.ses.SesMailboxValidation.Verdict.LOW;
import static io.github.hectorvent.floci.services.ses.SesMailboxValidation.Verdict.MEDIUM;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SesMailboxValidationTest {

    private static final Result ALL_LOW = new Result(LOW, LOW, LOW, LOW, LOW, LOW, LOW);

    private static Result result(Verdict isValid, Verdict syntax, Verdict dns, Verdict mailbox, Verdict role) {
        return new Result(isValid, syntax, dns, mailbox, role, LOW, LOW);
    }

    // The expected rows below marked "probed" are AWS's answers for the same address (2026-10-25).

    @Test
    void ordinaryDomainIsValid() {
        assertEquals(result(HIGH, HIGH, HIGH, MEDIUM, LOW), SesMailboxValidation.evaluate("alice@gmail.com"));
    }

    @Test
    void exampleDomainHasNoDnsRecords() {
        // probed
        assertEquals(result(LOW, HIGH, LOW, MEDIUM, LOW), SesMailboxValidation.evaluate("user@example.com"));
    }

    @Test
    void subdomainOfAReservedDomainHasNoDnsRecords() {
        assertEquals(result(LOW, HIGH, LOW, MEDIUM, LOW), SesMailboxValidation.evaluate("user@mail.example.org"));
    }

    @Test
    void invalidTopLevelDomainLowersSyntaxAndHasNoDnsRecords() {
        // probed
        assertEquals(result(LOW, MEDIUM, LOW, MEDIUM, LOW),
                SesMailboxValidation.evaluate("user@floci-probe-nonexistent-7f3a.invalid"));
    }

    @Test
    void singleLabelDomainIsTreatedAsAnUnrecognisedTopLevelDomain() {
        assertEquals(result(LOW, MEDIUM, LOW, MEDIUM, LOW), SesMailboxValidation.evaluate("user@localhost"));
    }

    @Test
    void displayNameLowersSyntax() {
        // probed
        assertEquals(result(LOW, MEDIUM, LOW, MEDIUM, LOW),
                SesMailboxValidation.evaluate("Alice <alice@example.com>"));
    }

    @Test
    void displayNameOnAnOrdinaryDomainStaysValid() {
        assertEquals(result(HIGH, MEDIUM, HIGH, MEDIUM, LOW),
                SesMailboxValidation.evaluate("Alice <alice@gmail.com>"));
    }

    @Test
    void angleBracketsWithoutANameLowerSyntax() {
        assertEquals(result(HIGH, MEDIUM, HIGH, MEDIUM, LOW), SesMailboxValidation.evaluate("<alice@gmail.com>"));
    }

    @Test
    void simulatorBounceAndComplaintAreNotSpecial() {
        // probed
        Result expected = result(HIGH, HIGH, HIGH, MEDIUM, LOW);
        assertEquals(expected, SesMailboxValidation.evaluate("success@simulator.amazonses.com"));
        assertEquals(expected, SesMailboxValidation.evaluate("bounce@simulator.amazonses.com"));
        assertEquals(expected, SesMailboxValidation.evaluate("complaint@simulator.amazonses.com"));
    }

    @Test
    void simulatorSuppressionListMailboxDoesNotExist() {
        // probed
        assertEquals(result(LOW, HIGH, HIGH, LOW, LOW),
                SesMailboxValidation.evaluate("suppressionlist@simulator.amazonses.com"));
    }

    @Test
    void labelledSimulatorSuppressionListMailboxDoesNotExist() {
        assertEquals(result(LOW, HIGH, HIGH, LOW, LOW),
                SesMailboxValidation.evaluate("suppressionlist+run-1@simulator.amazonses.com"));
    }

    @Test
    void roleAddressIsReportedWithoutChangingValidity() {
        // probed on example.com; gmail.com isolates the role from the DNS verdict
        assertEquals(result(LOW, HIGH, LOW, MEDIUM, HIGH), SesMailboxValidation.evaluate("admin@example.com"));
        assertEquals(result(HIGH, HIGH, HIGH, MEDIUM, HIGH), SesMailboxValidation.evaluate("Support+eu@gmail.com"));
    }

    @Test
    void malformedAddressIsLowEverywhere() {
        // probed
        assertEquals(ALL_LOW, SesMailboxValidation.evaluate("no-at-sign"));
        assertEquals(ALL_LOW, SesMailboxValidation.evaluate("user@"));
        assertEquals(ALL_LOW, SesMailboxValidation.evaluate("a b@example.com"));
    }

    @Test
    void nonAsciiOutsideQuotesIsLowEverywhere() {
        assertEquals(ALL_LOW, SesMailboxValidation.evaluate("é@gmail.com"));
        assertEquals(ALL_LOW, SesMailboxValidation.evaluate("user@bücher.de"));
    }

    @Test
    void nonAsciiInAQuotedLocalPartIsRatedAsASendAcceptsIt() {
        // a send does not inspect a quoted local part, so the verdicts follow the send check
        assertEquals(result(HIGH, HIGH, HIGH, MEDIUM, LOW), SesMailboxValidation.evaluate("\"é\"@gmail.com"));
    }

    @Test
    void blankAddressIsLowEverywhere() {
        assertEquals(ALL_LOW, SesMailboxValidation.evaluate("   "));
    }

    @Test
    void overLongAddressIsLowEverywhere() {
        // probed for the verdicts other than IsRandomInput, which AWS rated HIGH for a run of one letter
        String address = "a".repeat(309) + "@example.com";
        assertEquals(321, address.length());
        assertEquals(ALL_LOW, SesMailboxValidation.evaluate(address));
    }

    @Test
    void addressAtTheLengthLimitIsEvaluated() {
        String address = "a".repeat(310) + "@gmail.com";
        assertEquals(320, address.length());
        assertEquals(result(HIGH, HIGH, HIGH, MEDIUM, LOW), SesMailboxValidation.evaluate(address));
    }
}
