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

    record Template(String templateName, JsonNode templateData, List<MessageHeader> headers)
            implements EmailContent {
    }

    record InlineTemplate(String subject, String textPart, String htmlPart, JsonNode templateData,
                          List<MessageHeader> headers) implements EmailContent {
    }

    /**
     * A raw send reads the envelope's to, cc and bcc as one flat destination list, and falls back
     * to the MIME headers when that list is empty. It ignores {@code replyToAddresses}: the
     * message carries its own Reply-To header.
     */
    record Raw(String data) implements EmailContent {
    }
}
