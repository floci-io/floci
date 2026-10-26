package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.address.SesAddressLength;
import io.github.hectorvent.floci.services.ses.address.SesAddressSyntax;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The verdicts {@code GetEmailAddressInsights} answers for one address, derived offline so the same
 * address always gets the same answer. Floci performs no DNS lookup and no mailbox probe, so the
 * rules reproduce what AWS was seen to return (probe-confirmed 2026-10-25, ap-northeast-1) and fill
 * the rest with fixed values.
 *
 * <p>Probe-confirmed: a malformed or over-long address answers 200 with every evaluation LOW, not a
 * 400; a display-name form or an unrecognised top-level domain lowers the syntax verdict to MEDIUM;
 * {@code example.com} and an {@code .invalid} domain have no valid DNS records; the mailbox
 * simulator's domain has them, its suppression-list address is the only one whose mailbox is LOW,
 * and its bounce and complaint addresses are not special; an ordinary mailbox is MEDIUM; and the
 * overall verdict is LOW whenever the syntax, DNS or mailbox verdict is LOW, HIGH otherwise (no
 * MEDIUM overall verdict was observed).
 *
 * <p>Floci's own choices: every domain that is not reserved by RFC 2606 or RFC 6761 is taken to have
 * valid DNS records, the role-address check is a fixed list of local parts, and the disposable and
 * random-input checks always answer LOW (AWS rated a long run of one letter as random input).
 */
final class SesMailboxValidation {

    enum Verdict { LOW, MEDIUM, HIGH }

    record Result(Verdict isValid, Verdict hasValidSyntax, Verdict hasValidDnsRecords, Verdict mailboxExists,
                  Verdict isRoleAddress, Verdict isDisposable, Verdict isRandomInput) {
    }

    private static final String SIMULATOR_DOMAIN = "simulator.amazonses.com";
    private static final Set<String> RESERVED_TLDS = Set.of("example", "invalid", "localhost", "test");
    private static final List<String> RESERVED_DOMAINS = List.of("example.com", "example.net", "example.org");
    private static final Set<String> ROLE_LOCAL_PARTS = Set.of(
            "abuse", "admin", "administrator", "billing", "contact", "help", "hostmaster", "info",
            "marketing", "noc", "no-reply", "noreply", "postmaster", "sales", "security", "support",
            "webmaster");
    private static final Result MALFORMED = new Result(Verdict.LOW, Verdict.LOW, Verdict.LOW, Verdict.LOW,
            Verdict.LOW, Verdict.LOW, Verdict.LOW);

    private SesMailboxValidation() {
    }

    static Result evaluate(String emailAddress) {
        if (!SesAddressSyntax.isWellFormed(emailAddress) || SesAddressLength.exceedsLimit(emailAddress)) {
            return MALFORMED;
        }
        String addrSpec = SesAddressSyntax.addrSpec(emailAddress);
        int at = addrSpec.lastIndexOf('@');
        String localPart = addrSpec.substring(0, at);
        String domain = addrSpec.substring(at + 1).toLowerCase(Locale.ROOT);

        boolean displayName = !addrSpec.equals(emailAddress.trim());
        Verdict syntax = displayName || hasUnrecognisedTld(domain) ? Verdict.MEDIUM : Verdict.HIGH;
        Verdict dns = isReserved(domain) ? Verdict.LOW : Verdict.HIGH;
        Verdict mailbox = SimulatorAddresses.isSuppressionList(addrSpec) ? Verdict.LOW : Verdict.MEDIUM;
        Verdict role = ROLE_LOCAL_PARTS.contains(SimulatorAddresses.withoutLabel(localPart).toLowerCase(Locale.ROOT))
                ? Verdict.HIGH : Verdict.LOW;
        Verdict overall = syntax == Verdict.LOW || dns == Verdict.LOW || mailbox == Verdict.LOW
                ? Verdict.LOW : Verdict.HIGH;
        return new Result(overall, syntax, dns, mailbox, role, Verdict.LOW, Verdict.LOW);
    }

    private static boolean hasUnrecognisedTld(String domain) {
        int dot = domain.lastIndexOf('.');
        return dot < 0 || RESERVED_TLDS.contains(domain.substring(dot + 1));
    }

    private static boolean isReserved(String domain) {
        if (domain.equals(SIMULATOR_DOMAIN)) {
            return false;
        }
        if (hasUnrecognisedTld(domain)) {
            return true;
        }
        for (String reserved : RESERVED_DOMAINS) {
            if (domain.equals(reserved) || domain.endsWith("." + reserved)) {
                return true;
            }
        }
        return false;
    }
}
