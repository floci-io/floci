package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.SendEmailRequest;
import org.apache.james.mime4j.dom.Header;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.stream.Field;

import java.util.ArrayList;
import java.util.List;

/**
 * The 320-character limit AWS applies to every address of a send (probe-confirmed). AWS measures
 * an address in UTF-16 units in the {@code name <address>} form that JavaMail's
 * {@code InternetAddress.toUnicodeString()} prints: the display name is quoted only when it holds
 * a non-ASCII character or an RFC 822 special, an encoded-word name is measured undecoded, and the
 * error echoes that form. The v2 controller remaps the code to BadRequestException.
 */
final class SesAddressLength {

    static final int MAX_LENGTH = 320;

    private static final String PHRASE_SPECIALS = "()<>@,;:\\\".[]";
    private static final List<String> RAW_RECIPIENT_HEADERS = List.of("To", "Cc", "Bcc", "Reply-To", "Return-Path");

    private SesAddressLength() {
    }

    /**
     * Checks the envelope fields in the order AWS reports them: the sender, To, Cc, Bcc, the return
     * path (v2 FeedbackForwardingEmailAddress), then Reply-To.
     */
    static void requireEnvelope(SendEmailRequest request) {
        require(request.source());
        requireRecipients(request);
    }

    /**
     * Checks a raw send: the sender parameter, the From header, the envelope fields of the request,
     * and then the remaining address headers of the MIME message. Header addresses are measured as
     * written, like the request fields: an encoded-word name stays encoded and escaped quotes count
     * (probe-confirmed). Each address of a header is checked on its own, so a header listing
     * several short addresses passes however long it is.
     */
    static void requireRaw(SendEmailRequest request, Message message) {
        require(request.source());
        requireHeader(message, "From");
        requireRecipients(request);
        for (String name : RAW_RECIPIENT_HEADERS) {
            requireHeader(message, name);
        }
    }

    static void require(String address) {
        if (address == null || address.isBlank()) {
            return;
        }
        String trimmed = address.trim();
        int open = trimmed.lastIndexOf('<');
        if (open < 0 || !trimmed.endsWith(">")) {
            requireNormalized(trimmed);
            return;
        }
        String name = trimmed.substring(0, open).trim();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
            name = name.substring(1, name.length() - 1);
        }
        requireNormalized(format(name, trimmed.substring(open + 1, trimmed.length() - 1).trim()));
    }

    private static void requireRecipients(SendEmailRequest request) {
        requireAll(request.toAddresses());
        requireAll(request.ccAddresses());
        requireAll(request.bccAddresses());
        require(request.returnPath());
        requireAll(request.replyToAddresses());
    }

    private static void requireAll(List<String> addresses) {
        if (addresses != null) {
            addresses.forEach(SesAddressLength::require);
        }
    }

    private static void requireHeader(Message message, String name) {
        Header header = message == null ? null : message.getHeader();
        if (header == null) {
            return;
        }
        for (Field field : header.getFields(name)) {
            if (field.getBody() != null) {
                splitAddressList(field.getBody()).forEach(SesAddressLength::require);
            }
        }
    }

    /**
     * Splits an RFC 5322 address-list header body at the commas that separate its addresses,
     * ignoring commas inside a quoted display name or an angle-bracketed address. A group's
     * {@code name:} prefix and closing semicolon are dropped, leaving its member addresses.
     */
    static List<String> splitAddressList(String body) {
        List<String> addresses = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean angled = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quoted && c == '\\' && i + 1 < body.length()) {
                current.append(c).append(body.charAt(++i));
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '<') {
                angled = true;
            } else if (!quoted && c == '>') {
                angled = false;
            } else if (!quoted && !angled && c == ':') {
                current.setLength(0);
                continue;
            } else if (!quoted && !angled && (c == ',' || c == ';')) {
                addresses.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addresses.add(current.toString());
        addresses.removeIf(String::isBlank);
        return addresses;
    }

    private static String format(String name, String address) {
        if (name == null || name.isEmpty()) {
            return address;
        }
        return (needsQuoting(name) ? "\"" + name + "\"" : name) + " <" + address + ">";
    }

    private static boolean needsQuoting(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c < 0x20 && c != '\t') || c >= 0x7f || PHRASE_SPECIALS.indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static void requireNormalized(String address) {
        if (address.length() > MAX_LENGTH) {
            throw new AwsException("InvalidParameterValue",
                    "Address length is more than " + MAX_LENGTH + " characters long: '" + address + "'.", 400);
        }
    }
}
