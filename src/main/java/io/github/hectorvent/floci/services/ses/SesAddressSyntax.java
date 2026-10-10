package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntry;
import io.github.hectorvent.floci.services.ses.model.SendEmailRequest;
import org.apache.james.mime4j.dom.Message;

import java.util.Arrays;
import java.util.List;

/**
 * The address syntax check AWS applies to every address of a send ahead of the destination,
 * configuration-set and length checks (probe-confirmed). The messages are those of JavaMail's
 * strict {@code InternetAddress} check, which scans the addr-spec once from the left, and only
 * the four AWS was seen to return are reproduced: a control, whitespace or non-ASCII character in
 * the local part, a missing {@code @}, an empty domain, and such a character in the domain. A
 * display name or a comment may hold any character, and a quoted local part is not inspected
 * except for a CR or LF, which AWS rejects with its own wording even before whitespace.
 * Of the raw headers, only From and To were probed, so only those are checked. The v2 controller
 * remaps the code to BadRequestException.
 */
final class SesAddressSyntax {

    private static final List<String> RAW_CHECKED_HEADERS = List.of("To");

    private SesAddressSyntax() {
    }

    static void requireEnvelope(SendEmailRequest request) {
        SesSendAddresses.forEachEnvelope(request, SesAddressSyntax::require);
    }

    static void requireRaw(SendEmailRequest request, Message message) {
        SesSendAddresses.forEachRaw(request, message, RAW_CHECKED_HEADERS, SesAddressSyntax::require);
    }

    /**
     * Checks a bulk sender or return path (v2 FeedbackForwardingEmailAddress). A bulk send words a
     * malformed or over-long one as an invalid address for the whole request, echoing it as given,
     * and reports it before a missing stored template (probe-confirmed; an over-long display-name
     * address is assumed to be measured as on a single send). The v1 handler and the v2 controller
     * call it as soon as the address is read, ahead of the template lookup they perform.
     */
    static void requireBulkAddress(String address) {
        if (violation(address) != null || SesAddressLength.exceedsLimit(address)) {
            throw new AwsException("InvalidParameterValue", "Invalid email address<" + address + ">.", 400);
        }
    }

    /**
     * Checks the To, Cc and Bcc of one bulk entry, then the request's Reply-To. AWS reports a
     * malformed entry recipient as that entry's status, and a malformed Reply-To as the status of
     * every entry, not as a request error (probe-confirmed).
     */
    static void requireBulkDestination(BulkEmailEntry entry, List<String> replyToAddresses) {
        for (List<String> addresses : Arrays.asList(entry.toAddresses(), entry.ccAddresses(), entry.bccAddresses(),
                replyToAddresses)) {
            if (addresses != null) {
                addresses.forEach(SesAddressSyntax::require);
            }
        }
    }

    static void require(String address) {
        String violation = violation(address);
        if (violation != null) {
            throw new AwsException("InvalidParameterValue", violation, 400);
        }
    }

    /**
     * Returns the message AWS answers for a malformed address, or null when the address passes or
     * is blank (a missing address is reported by the caller's own required-field check).
     */
    static String violation(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String addrSpec = addrSpec(address);
        boolean quoted = false;
        int i = 0;
        for (; i < addrSpec.length(); i++) {
            char c = addrSpec.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (quoted) {
                if (c == '\r' || c == '\n') {
                    return "Invalid email address " + address + ".";
                }
            } else if (c == '@') {
                break;
            } else if (isControlOrWhitespace(c)) {
                return "Local address contains control or whitespace";
            }
        }
        if (i >= addrSpec.length()) {
            return "Missing final '@domain'";
        }
        String domain = addrSpec.substring(i + 1);
        if (domain.isEmpty()) {
            return "Missing domain";
        }
        for (int j = 0; j < domain.length(); j++) {
            if (isControlOrWhitespace(domain.charAt(j))) {
                return "Domain contains control or whitespace";
            }
        }
        return null;
    }

    private static boolean isControlOrWhitespace(char c) {
        return c <= ' ' || c >= 0x7f;
    }

    private static String addrSpec(String mailbox) {
        String withoutComments = withoutComments(mailbox).trim();
        int open = SesSendAddresses.lastUnquotedAngle(withoutComments);
        if (open >= 0 && withoutComments.endsWith(">")) {
            return withoutComments.substring(open + 1, withoutComments.length() - 1).trim();
        }
        return withoutComments;
    }

    private static String withoutComments(String mailbox) {
        StringBuilder out = new StringBuilder();
        boolean quoted = false;
        int commentDepth = 0;
        for (int i = 0; i < mailbox.length(); i++) {
            char c = mailbox.charAt(i);
            if ((quoted || commentDepth > 0) && c == '\\' && i + 1 < mailbox.length()) {
                if (commentDepth == 0) {
                    out.append(c).append(mailbox.charAt(i + 1));
                }
                i++;
                continue;
            }
            if (commentDepth > 0) {
                if (c == '(') {
                    commentDepth++;
                } else if (c == ')') {
                    commentDepth--;
                }
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                commentDepth = 1;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
