package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.SendEmailRequest;
import org.apache.james.mime4j.dom.Message;

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

    static void requireEnvelope(SendEmailRequest request) {
        SesSendAddresses.forEachEnvelope(request, SesAddressLength::require);
    }

    /**
     * Header addresses are measured as written, like the request fields: an encoded-word name stays
     * encoded and escaped quotes count (probe-confirmed). Each address of a header is checked on its
     * own, so a header listing several short addresses passes however long it is.
     */
    static void requireRaw(SendEmailRequest request, Message message) {
        SesSendAddresses.forEachRaw(request, message, RAW_RECIPIENT_HEADERS, SesAddressLength::require);
    }

    static void require(String address) {
        String normalized = normalized(address);
        if (normalized != null && normalized.length() > MAX_LENGTH) {
            throw new AwsException("InvalidParameterValue",
                    "Address length is more than " + MAX_LENGTH + " characters long: '" + normalized + "'.", 400);
        }
    }

    static boolean exceedsLimit(String address) {
        String normalized = normalized(address);
        return normalized != null && normalized.length() > MAX_LENGTH;
    }

    private static String normalized(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String trimmed = address.trim();
        int open = SesSendAddresses.lastUnquotedAngle(trimmed);
        if (open < 0 || !trimmed.endsWith(">")) {
            return trimmed;
        }
        String name = trimmed.substring(0, open).trim();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
            name = name.substring(1, name.length() - 1);
        }
        return format(name, trimmed.substring(open + 1, trimmed.length() - 1).trim());
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
}
