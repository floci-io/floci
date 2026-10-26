package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The body of a send, mirroring the v2 {@code EmailContent} union: exactly one of a simple
 * message, a template, or a raw MIME message. A stored template and inline template content are
 * separate variants here, since the controllers have already resolved the v2 selector
 * (TemplateName, TemplateArn or TemplateContent) by the time a request is built.
 */
public sealed interface EmailContent {

    record Simple(String subject, String bodyText, String bodyHtml, List<MessageHeader> headers,
                  List<MessageAttachment> attachments) implements EmailContent {

        public Simple(String subject, String bodyText, String bodyHtml, List<MessageHeader> headers) {
            this(subject, bodyText, bodyHtml, headers, List.of());
        }

        public boolean hasHeaders() {
            return headers != null && !headers.isEmpty();
        }

        public boolean hasAttachments() {
            return attachments != null && !attachments.isEmpty();
        }

        public Simple withSafeHeaders() {
            if (!hasHeaders()) {
                return this;
            }
            return new Simple(subject, bodyText, bodyHtml, headers.stream().filter(MessageHeader::isSafe).toList(),
                    attachments);
        }
    }

    /**
     * The two template variants, the only content a bulk send's default content can be.
     */
    sealed interface TemplateContent extends EmailContent {
    }

    record Template(String templateName, JsonNode templateData, List<MessageHeader> headers)
            implements TemplateContent {
    }

    record InlineTemplate(String subject, String textPart, String htmlPart, JsonNode templateData,
                          List<MessageHeader> headers) implements TemplateContent {
    }

    /**
     * A raw send reads the envelope's to, cc and bcc as one flat destination list, and falls back
     * to the MIME headers when that list is empty. It ignores {@code replyToAddresses}: the
     * message carries its own Reply-To header. {@code fromHeaderAsFallback} marks a v2 send, which
     * reads the From header only in place of a missing sender parameter: it skips the header's
     * syntax and length checks when the sender is given and words a malformed one as an invalid
     * From address (probe-confirmed). A v1 send always checks the header with JavaMail's messages.
     */
    record Raw(String data, boolean fromHeaderAsFallback) implements EmailContent {

        public Raw(String data) {
            this(data, false);
        }

        public boolean checksFromHeader(boolean senderGiven) {
            return !fromHeaderAsFallback || !senderGiven;
        }
    }
}
