package io.github.hectorvent.floci.services.ses.address;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntry;
import io.github.hectorvent.floci.services.ses.model.EmailContent;
import io.github.hectorvent.floci.services.ses.model.SendEmailRequest;
import org.apache.james.mime4j.dom.Message;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The address syntax check AWS applies to every address of a send ahead of the destination,
 * configuration-set and length checks (probe-confirmed). The messages are those of JavaMail's
 * strict {@code InternetAddress} check, which scans the addr-spec once from the left, and only
 * the four AWS was seen to return are reproduced: a control, whitespace or non-ASCII character in
 * the local part, a missing {@code @}, an empty domain, and such a character in the domain. A
 * display name or a comment may hold any character, and a quoted local part is not inspected
 * except for a CR or LF, which AWS rejects with its own wording even before whitespace.
 * In a raw message AWS checks the From, To, Cc and Bcc headers (probe-confirmed);
 * the Reply-To and Return-Path headers were not probed and are left unchecked. The v2 controller
 * remaps the code to BadRequestException. A v2 raw send checks its From header only without a
 * sender parameter, and then answers an invalid From address for every violation but a missing
 * {@code @}, which it reports later (probe-confirmed for the four JavaMail messages; a quoted CR
 * or LF is assumed to follow). A v1 raw send with a sender parameter checks every From header
 * address but lets a missing {@code @} pass, as JavaMail's non-strict header parse does, and
 * reports one only on the first address, later and in its own wording (probe-confirmed).
 */
public final class SesAddressSyntax {

    private static final List<String> RAW_CHECKED_HEADERS = List.of("To", "Cc", "Bcc");
    private static final String MISSING_FINAL_DOMAIN = "Missing final '@domain'";
    private static final String NULL_ADDRESS = "Invalid email address null.";
    private static final String EMPTY_DESTINATION = "Destination cannot be empty";
    private static final String PHRASE_SPECIALS = "()<>@,;:\\\".[]";
    private static final String ADDRESS_SPECIALS = "()<>,;:\\\"[]";

    private SesAddressSyntax() {
    }

    public static void requireEnvelope(SendEmailRequest request) {
        SesSendAddresses.forEachEnvelope(request, SesAddressSyntax::require);
    }

    public static void requireRaw(SendEmailRequest request, EmailContent.Raw raw, Message message) {
        SesSendAddresses.forEachRaw(request, message, RAW_CHECKED_HEADERS, fromHeaderCheck(request, raw),
                SesAddressSyntax::require);
    }

    private static Consumer<String> fromHeaderCheck(SendEmailRequest request, EmailContent.Raw raw) {
        if (readsFromHeaderAsSender(request, raw)) {
            return SesAddressSyntax::requireFallbackFrom;
        }
        if (checksFromHeaderBesideSender(request, raw)) {
            return SesAddressSyntax::requireExceptAt;
        }
        return raw.checksFromHeader(request.hasSource()) ? SesAddressSyntax::require : SesSendAddresses.UNCHECKED;
    }

    private static boolean readsFromHeaderAsSender(SendEmailRequest request, EmailContent.Raw raw) {
        return raw.fromHeaderAsFallback() && !request.hasSource();
    }

    private static boolean checksFromHeaderBesideSender(SendEmailRequest request, EmailContent.Raw raw) {
        return !raw.fromHeaderAsFallback() && request.hasSource();
    }

    private static void requireFallbackFrom(String address) {
        if (violationExceptAt(address) != null) {
            throw new AwsException("InvalidParameterValue", "Invalid From address provided.", 400);
        }
    }

    private static void requireExceptAt(String address) {
        String violation = violationExceptAt(address);
        if (violation != null) {
            throw new AwsException("InvalidParameterValue", violation, 400);
        }
    }

    private static String violationExceptAt(String address) {
        String violation = violation(address);
        return MISSING_FINAL_DOMAIN.equals(violation) ? null : violation;
    }

    /**
     * The missing {@code @} the From header check let pass, which AWS reports only after the
     * configuration set and the missing-destination checks (probe-confirmed). A v2 send reports it
     * on any From header address read in place of the sender, before the length check
     * (probe-confirmed). A v1 send with a sender parameter reports it on the first address only,
     * as JavaMail's "User name is missing" around that address's {@code toString()} form
     * (probe-confirmed; the same place relative to the length check is assumed).
     */
    public static void requireFromHeaderAt(SendEmailRequest request, EmailContent.Raw raw, Message message) {
        if (readsFromHeaderAsSender(request, raw)) {
            SesSendAddresses.forEachHeaderAddress(message, "From", SesAddressSyntax::requireAt);
        } else if (checksFromHeaderBesideSender(request, raw)) {
            String first = SesSendAddresses.firstHeaderAddress(message, "From");
            if (MISSING_FINAL_DOMAIN.equals(violation(first))) {
                throw new AwsException("InvalidParameterValue",
                        "User name is missing: '" + javaMailString(first) + "'.", 400);
            }
        }
    }

    private static void requireAt(String address) {
        if (MISSING_FINAL_DOMAIN.equals(violation(address))) {
            throw new AwsException("InvalidParameterValue", MISSING_FINAL_DOMAIN, 400);
        }
    }

    /**
     * A mailbox as JavaMail's {@code InternetAddress.toString()} writes it once parsed: the display
     * name as written from its first character outside a comment to the angle bracket, later
     * comments included, and dropped when it is blank once unquoted, or the first comment only when
     * only comments precede the angle bracket (probe-confirmed each way). The name is quoted again only when it holds a special character, then the addr-spec
     * follows in angle brackets; a bare addr-spec keeps no brackets unless it holds a special
     * character. A raw non-ASCII name comes out as
     * {@link SesEncodedWord} writes it and an encoded-word stays as written (probe-confirmed for a
     * short all non-ASCII name, which is one B encoded-word; longer and mixed names follow JavaMail
     * and are assumed).
     */
    static String javaMailString(String mailbox) {
        String addrSpec = addrSpec(mailbox);
        String personal = personal(mailbox);
        if (personal != null) {
            return encodePhrase(personal) + " <" + addrSpec + ">";
        }
        return containsAny(addrSpec, ADDRESS_SPECIALS) ? "<" + addrSpec + ">" : addrSpec;
    }

    private static String personal(String mailbox) {
        Stripped stripped = stripComments(mailbox);
        if (stripped.lastAngle() >= 0 && stripped.text().trim().endsWith(">")
                && stripped.phraseStart() < stripped.lastAngle()) {
            String phrase = unquote(mailbox.substring(stripped.phraseStart(), stripped.lastAngle()).trim());
            return phrase.isBlank() ? null : phrase;
        }
        String comment = stripped.firstComment();
        return comment == null || comment.isBlank() ? null : comment.trim();
    }

    private static String unquote(String phrase) {
        if (phrase.length() < 2 || !phrase.startsWith("\"") || !phrase.endsWith("\"")) {
            return phrase;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < phrase.length() - 1; i++) {
            char c = phrase.charAt(i);
            if (c == '\\' && i + 1 < phrase.length() - 1) {
                c = phrase.charAt(++i);
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String encodePhrase(String personal) {
        if (SesEncodedWord.needsEncoding(personal)) {
            return SesEncodedWord.encode(personal);
        }
        boolean needsQuotes = false;
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < personal.length(); i++) {
            char c = personal.charAt(i);
            if (c == '"' || c == '\\') {
                quoted.append('\\');
                needsQuotes = true;
            } else if (PHRASE_SPECIALS.indexOf(c) >= 0) {
                needsQuotes = true;
            }
            quoted.append(c);
        }
        return needsQuotes ? quoted.append('"').toString() : personal;
    }

    private static boolean containsAny(String value, String characters) {
        return value.chars().anyMatch(c -> characters.indexOf(c) >= 0);
    }

    /**
     * Checks a bulk sender or return path (v2 FeedbackForwardingEmailAddress). A bulk send words a
     * malformed or over-long one as an invalid address for the whole request, echoing it as given,
     * and reports it before a missing stored template (probe-confirmed; an over-long display-name
     * address is assumed to be measured as on a single send). The v1 handler and the v2 controller
     * call it as soon as the address is read, ahead of the template lookup they perform.
     */
    public static void requireBulkAddress(String address) {
        if (violation(address) != null || SesAddressLength.exceedsLimit(address)) {
            throw new AwsException("InvalidParameterValue", "Invalid email address<" + address + ">.", 400);
        }
    }

    /**
     * Checks the To, Cc and Bcc of one bulk entry, then the request's Reply-To. AWS reports a
     * malformed entry recipient as that entry's status, and a malformed Reply-To as the status of
     * every entry, not as a request error (probe-confirmed). An address list of the wrong JSON type
     * never reaches this check: the controller rejects the whole request with SerializationException.
     *
     * <p>A null element passes deserialization and fails here as "Invalid email address null.",
     * unless no entry of the request has a non-null recipient: then an entry holding one answers
     * "Destination cannot be empty" (probe-confirmed for To lists of nulls; how SES words an entry
     * whose lists are merely empty was not probed, so that case is left as it was).
     */
    public static void requireBulkDestination(BulkEmailEntry entry, List<String> replyToAddresses,
                                              boolean requestHasRecipient) {
        for (List<String> addresses : Arrays.asList(entry.toAddresses(), entry.ccAddresses(), entry.bccAddresses())) {
            requireBulkAddresses(addresses, requestHasRecipient ? NULL_ADDRESS : EMPTY_DESTINATION);
        }
        requireBulkAddresses(replyToAddresses, NULL_ADDRESS);
    }

    private static void requireBulkAddresses(List<String> addresses, String nullMessage) {
        if (addresses == null) {
            return;
        }
        for (String address : addresses) {
            if (address == null) {
                throw new AwsException("InvalidParameterValue", nullMessage, 400);
            }
            require(address);
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
            return MISSING_FINAL_DOMAIN;
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
        String withoutComments = stripComments(mailbox).text().trim();
        int open = SesSendAddresses.lastUnquotedAngle(withoutComments);
        if (open >= 0 && withoutComments.endsWith(">")) {
            return withoutComments.substring(open + 1, withoutComments.length() - 1).trim();
        }
        return withoutComments;
    }

    private record Stripped(String text, String firstComment, int phraseStart, int lastAngle) {
    }

    private static Stripped stripComments(String mailbox) {
        StringBuilder out = new StringBuilder();
        String firstComment = null;
        int commentStart = -1;
        int phraseStart = -1;
        int lastAngle = -1;
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
                } else if (c == ')' && --commentDepth == 0 && firstComment == null) {
                    firstComment = mailbox.substring(commentStart, i);
                }
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                commentDepth = 1;
                commentStart = i + 1;
                continue;
            } else if (!quoted && c == '<') {
                lastAngle = i;
            }
            if (phraseStart < 0 && !Character.isWhitespace(c)) {
                phraseStart = i;
            }
            out.append(c);
        }
        return new Stripped(out.toString(), firstComment, phraseStart, lastAngle);
    }
}
