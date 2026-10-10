package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.model.SendEmailRequest;
import org.apache.james.mime4j.dom.Header;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.stream.Field;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Walks the addresses of a send in the order AWS reports them, so every per-address check
 * (length, syntax) visits the same fields in the same order.
 */
final class SesSendAddresses {

    private SesSendAddresses() {
    }

    /**
     * The envelope fields: the sender, To, Cc, Bcc, the return path (v2
     * FeedbackForwardingEmailAddress), then Reply-To.
     */
    static void forEachEnvelope(SendEmailRequest request, Consumer<String> check) {
        check.accept(request.source());
        forEachRecipient(request, check);
    }

    /**
     * A raw send: the sender parameter, the From header, the envelope fields of the request, and
     * then the named headers of the MIME message. Each address of a header is visited on its own,
     * as written (an encoded-word name stays encoded).
     */
    static void forEachRaw(SendEmailRequest request, Message message, List<String> headerNames,
                           Consumer<String> check) {
        check.accept(request.source());
        forEachHeaderAddress(message, "From", check);
        forEachRecipient(request, check);
        for (String name : headerNames) {
            forEachHeaderAddress(message, name, check);
        }
    }

    /**
     * Splits an RFC 5322 address-list header body at the commas that separate its addresses,
     * ignoring commas inside a quoted display name, a comment, an angle-bracketed address or a
     * domain literal ({@code [IPv6:2001:db8::1]}). A quote inside a comment is literal. A group's {@code name:} prefix and closing semicolon are
     * dropped, leaving its member addresses.
     */
    static List<String> splitAddressList(String body) {
        List<String> addresses = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean angled = false;
        boolean bracketed = false;
        int commentDepth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if ((quoted || commentDepth > 0 || bracketed) && c == '\\' && i + 1 < body.length()) {
                current.append(c).append(body.charAt(++i));
                continue;
            }
            if (commentDepth > 0) {
                if (c == '(') {
                    commentDepth++;
                } else if (c == ')') {
                    commentDepth--;
                }
            } else if (bracketed) {
                if (c == ']') {
                    bracketed = false;
                }
            } else if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                commentDepth = 1;
            } else if (!quoted && c == '[') {
                bracketed = true;
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

    /**
     * The index of the last {@code <} outside a quoted string, so a quoted local part holding a
     * {@code <} is not mistaken for the start of the angle-bracketed address; -1 when there is none.
     */
    static int lastUnquotedAngle(String mailbox) {
        int open = -1;
        boolean quoted = false;
        for (int i = 0; i < mailbox.length(); i++) {
            char c = mailbox.charAt(i);
            if (quoted && c == '\\') {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '<') {
                open = i;
            }
        }
        return open;
    }

    private static void forEachRecipient(SendEmailRequest request, Consumer<String> check) {
        forEach(request.toAddresses(), check);
        forEach(request.ccAddresses(), check);
        forEach(request.bccAddresses(), check);
        check.accept(request.returnPath());
        forEach(request.replyToAddresses(), check);
    }

    private static void forEach(List<String> addresses, Consumer<String> check) {
        if (addresses != null) {
            addresses.forEach(check);
        }
    }

    private static void forEachHeaderAddress(Message message, String name, Consumer<String> check) {
        Header header = message == null ? null : message.getHeader();
        if (header == null) {
            return;
        }
        for (Field field : header.getFields(name)) {
            if (field.getBody() != null) {
                splitAddressList(field.getBody()).forEach(check);
            }
        }
    }
}
