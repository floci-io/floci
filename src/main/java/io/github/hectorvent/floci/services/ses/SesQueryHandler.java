package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.Identity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Query-protocol handler for SES actions.
 * Receives pre-dispatched calls from {@link io.github.hectorvent.floci.core.common.AwsQueryController}.
 */
@ApplicationScoped
public class SesQueryHandler {

    private static final Logger LOG = Logger.getLogger(SesQueryHandler.class);

    private final SesService sesService;
    private final ObjectMapper objectMapper;

    @Inject
    public SesQueryHandler(SesService sesService, ObjectMapper objectMapper) {
        this.sesService = sesService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        LOG.debugv("SES action: {0}", action);

        try {
            return switch (action) {
                case "VerifyEmailIdentity" -> handleVerifyEmailIdentity(params, region);
                case "VerifyEmailAddress" -> handleVerifyEmailAddress(params, region);
                case "VerifyDomainIdentity" -> handleVerifyDomainIdentity(params, region);
                case "DeleteIdentity" -> handleDeleteIdentity(params, region);
                case "ListIdentities" -> handleListIdentities(params, region);
                case "GetIdentityVerificationAttributes" -> handleGetIdentityVerificationAttributes(params, region);
                case "SendEmail" -> handleSendEmail(params, region);
                case "SendRawEmail" -> handleSendRawEmail(params, region);
                case "GetSendQuota" -> handleGetSendQuota(region);
                case "GetSendStatistics" -> handleGetSendStatistics(region);
                case "GetAccountSendingEnabled" -> handleGetAccountSendingEnabled(region);
                case "ListVerifiedEmailAddresses" -> handleListVerifiedEmailAddresses(region);
                case "DeleteVerifiedEmailAddress" -> handleDeleteVerifiedEmailAddress(params, region);
                case "SetIdentityNotificationTopic" -> handleSetIdentityNotificationTopic(params, region);
                case "GetIdentityNotificationAttributes" -> handleGetIdentityNotificationAttributes(params, region);
                case "GetIdentityDkimAttributes" -> handleGetIdentityDkimAttributes(params, region);
                case "CreateTemplate" -> handleCreateTemplate(params, region);
                case "UpdateTemplate" -> handleUpdateTemplate(params, region);
                case "GetTemplate" -> handleGetTemplate(params, region);
                case "DeleteTemplate" -> handleDeleteTemplate(params, region);
                case "ListTemplates" -> handleListTemplates(region);
                case "SendTemplatedEmail" -> handleSendTemplatedEmail(params, region);
                default -> AwsQueryResponse.error("UnsupportedOperation",
                        "Operation " + action + " is not supported by SES.", AwsNamespaces.SES, 400);
            };
        } catch (AwsException e) {
            return AwsQueryResponse.error(e.getErrorCode(), e.getMessage(), AwsNamespaces.SES, e.getHttpStatus());
        }
    }

    private Response handleVerifyEmailIdentity(MultivaluedMap<String, String> params, String region) {
        String emailAddress = getParam(params, "EmailAddress");
        sesService.verifyEmailIdentity(emailAddress, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("VerifyEmailIdentity", AwsNamespaces.SES)).build();
    }

    private Response handleVerifyEmailAddress(MultivaluedMap<String, String> params, String region) {
        String emailAddress = getParam(params, "EmailAddress");
        sesService.verifyEmailIdentity(emailAddress, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("VerifyEmailAddress", AwsNamespaces.SES)).build();
    }

    private Response handleVerifyDomainIdentity(MultivaluedMap<String, String> params, String region) {
        String domain = getParam(params, "Domain");
        Identity identity = sesService.verifyDomainIdentity(domain, region);
        String result = new XmlBuilder().elem("VerificationToken", identity.getVerificationToken()).build();
        return Response.ok(AwsQueryResponse.envelope("VerifyDomainIdentity", AwsNamespaces.SES, result)).build();
    }

    private Response handleDeleteIdentity(MultivaluedMap<String, String> params, String region) {
        String identityValue = getParam(params, "Identity");
        sesService.deleteIdentity(identityValue, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("DeleteIdentity", AwsNamespaces.SES)).build();
    }

    private Response handleListIdentities(MultivaluedMap<String, String> params, String region) {
        String identityType = getParam(params, "IdentityType");
        List<Identity> identities = sesService.listIdentities(identityType, region);

        var xml = new XmlBuilder().start("Identities");
        for (Identity id : identities) {
            xml.elem("member", id.getIdentity());
        }
        xml.end("Identities");
        return Response.ok(AwsQueryResponse.envelope("ListIdentities", AwsNamespaces.SES, xml.build())).build();
    }

    private Response handleGetIdentityVerificationAttributes(MultivaluedMap<String, String> params, String region) {
        List<String> identities = extractMembers(params, "Identities");

        var xml = new XmlBuilder().start("VerificationAttributes");
        for (String identityValue : identities) {
            Identity identity = sesService.getIdentityVerificationAttributes(identityValue, region);
            xml.start("entry");
            xml.elem("key", identityValue);
            xml.start("value");
            if (identity != null) {
                xml.elem("VerificationStatus", identity.getVerificationStatus());
                if (identity.getVerificationToken() != null) {
                    xml.elem("VerificationToken", identity.getVerificationToken());
                }
            } else {
                xml.elem("VerificationStatus", "NotStarted");
            }
            xml.end("value");
            xml.end("entry");
        }
        xml.end("VerificationAttributes");
        return Response.ok(AwsQueryResponse.envelope("GetIdentityVerificationAttributes", AwsNamespaces.SES, xml.build())).build();
    }

    private Response handleSendEmail(MultivaluedMap<String, String> params, String region) {
        String source = getParam(params, "Source");
        List<String> toAddresses = extractMembers(params, "Destination.ToAddresses");
        List<String> ccAddresses = extractMembers(params, "Destination.CcAddresses");
        List<String> bccAddresses = extractMembers(params, "Destination.BccAddresses");
        List<String> replyToAddresses = extractMembers(params, "ReplyToAddresses");
        String subject = getParam(params, "Message.Subject.Data");
        String bodyText = getParam(params, "Message.Body.Text.Data");
        String bodyHtml = getParam(params, "Message.Body.Html.Data");

        String messageId = sesService.sendEmail(source, toAddresses, ccAddresses, bccAddresses,
                replyToAddresses, subject, bodyText, bodyHtml, region);

        String result = new XmlBuilder().elem("MessageId", messageId).build();
        return Response.ok(AwsQueryResponse.envelope("SendEmail", AwsNamespaces.SES, result)).build();
    }

    private Response handleSendRawEmail(MultivaluedMap<String, String> params, String region) {
        String source = getParam(params, "Source");
        List<String> destinations = extractMembers(params, "Destinations");
        String rawMessage = getParam(params, "RawMessage.Data");

        String messageId = sesService.sendRawEmail(source, destinations, rawMessage, region);

        String result = new XmlBuilder().elem("MessageId", messageId).build();
        return Response.ok(AwsQueryResponse.envelope("SendRawEmail", AwsNamespaces.SES, result)).build();
    }

    private Response handleGetSendQuota(String region) {
        var xml = new XmlBuilder()
                .elem("Max24HourSend", "200.0")
                .elem("MaxSendRate", "1.0")
                .elem("SentLast24Hours", String.valueOf((double) sesService.getSentEmailCount(region)));
        return Response.ok(AwsQueryResponse.envelope("GetSendQuota", AwsNamespaces.SES, xml.build())).build();
    }

    private Response handleGetSendStatistics(String region) {
        long sentCount = sesService.getSentEmailCount(region);
        var xml = new XmlBuilder().start("SendDataPoints");
        if (sentCount > 0) {
            xml.start("member")
               .elem("DeliveryAttempts", String.valueOf(sentCount))
               .elem("Bounces", "0")
               .elem("Complaints", "0")
               .elem("Rejects", "0")
               .elem("Timestamp", java.time.Instant.now().toString())
               .end("member");
        }
        xml.end("SendDataPoints");
        return Response.ok(AwsQueryResponse.envelope("GetSendStatistics", AwsNamespaces.SES, xml.build())).build();
    }

    private Response handleGetAccountSendingEnabled(String region) {
        boolean enabled = sesService.isAccountSendingEnabled(region);
        String result = new XmlBuilder().elem("Enabled", String.valueOf(enabled)).build();
        return Response.ok(AwsQueryResponse.envelope("GetAccountSendingEnabled", AwsNamespaces.SES, result)).build();
    }

    private Response handleListVerifiedEmailAddresses(String region) {
        List<String> emails = sesService.getVerifiedEmailAddresses(region);
        var xml = new XmlBuilder().start("VerifiedEmailAddresses");
        for (String email : emails) {
            xml.elem("member", email);
        }
        xml.end("VerifiedEmailAddresses");
        return Response.ok(AwsQueryResponse.envelope("ListVerifiedEmailAddresses", AwsNamespaces.SES, xml.build())).build();
    }

    private Response handleDeleteVerifiedEmailAddress(MultivaluedMap<String, String> params, String region) {
        String emailAddress = getParam(params, "EmailAddress");
        sesService.deleteIdentity(emailAddress, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteVerifiedEmailAddress", AwsNamespaces.SES)).build();
    }

    private Response handleSetIdentityNotificationTopic(MultivaluedMap<String, String> params, String region) {
        String identityValue = getParam(params, "Identity");
        String notificationType = getParam(params, "NotificationType");
        String snsTopic = getParam(params, "SnsTopic");
        sesService.setIdentityNotificationTopic(identityValue, notificationType, snsTopic, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("SetIdentityNotificationTopic", AwsNamespaces.SES)).build();
    }

    private Response handleGetIdentityNotificationAttributes(MultivaluedMap<String, String> params, String region) {
        List<String> identities = extractMembers(params, "Identities");

        var xml = new XmlBuilder().start("NotificationAttributes");
        for (String identityValue : identities) {
            Identity identity = sesService.getIdentityNotificationAttributes(identityValue, region);
            xml.start("entry");
            xml.elem("key", identityValue);
            xml.start("value");
            if (identity != null) {
                xml.elem("BounceTopic", identity.getNotificationAttributes().getOrDefault("BounceTopic", ""));
                xml.elem("ComplaintTopic", identity.getNotificationAttributes().getOrDefault("ComplaintTopic", ""));
                xml.elem("DeliveryTopic", identity.getNotificationAttributes().getOrDefault("DeliveryTopic", ""));
                xml.elem("ForwardingEnabled", "true");
                xml.elem("HeadersInBounceNotificationsEnabled", "false");
                xml.elem("HeadersInComplaintNotificationsEnabled", "false");
                xml.elem("HeadersInDeliveryNotificationsEnabled", "false");
            }
            xml.end("value");
            xml.end("entry");
        }
        xml.end("NotificationAttributes");
        return Response.ok(AwsQueryResponse.envelope("GetIdentityNotificationAttributes", AwsNamespaces.SES, xml.build())).build();
    }

    private Response handleGetIdentityDkimAttributes(MultivaluedMap<String, String> params, String region) {
        List<String> identities = extractMembers(params, "Identities");

        var xml = new XmlBuilder().start("DkimAttributes");
        for (String identityValue : identities) {
            Identity identity = sesService.getIdentityVerificationAttributes(identityValue, region);
            xml.start("entry");
            xml.elem("key", identityValue);
            xml.start("value");
            xml.elem("DkimEnabled", identity != null ? String.valueOf(identity.isDkimEnabled()) : "false");
            xml.elem("DkimVerificationStatus", identity != null ? identity.getDkimVerificationStatus() : "NotStarted");
            xml.start("DkimTokens").end("DkimTokens");
            xml.end("value");
            xml.end("entry");
        }
        xml.end("DkimAttributes");
        return Response.ok(AwsQueryResponse.envelope("GetIdentityDkimAttributes", AwsNamespaces.SES, xml.build())).build();
    }

    // --- Templates ---

    private Response handleCreateTemplate(MultivaluedMap<String, String> params, String region) {
        EmailTemplate template = readTemplateParams(params);
        sesService.createTemplate(template, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("CreateTemplate", AwsNamespaces.SES)).build();
    }

    private Response handleUpdateTemplate(MultivaluedMap<String, String> params, String region) {
        EmailTemplate template = readTemplateParams(params);
        sesService.updateTemplate(template, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("UpdateTemplate", AwsNamespaces.SES)).build();
    }

    private Response handleGetTemplate(MultivaluedMap<String, String> params, String region) {
        String templateName = getParam(params, "TemplateName");
        EmailTemplate template = sesService.getTemplate(templateName, region);
        var xml = new XmlBuilder().start("Template")
                .elem("TemplateName", template.getTemplateName());
        if (template.getSubject() != null) {
            xml.elem("SubjectPart", template.getSubject());
        }
        if (template.getTextPart() != null) {
            xml.elem("TextPart", template.getTextPart());
        }
        if (template.getHtmlPart() != null) {
            xml.elem("HtmlPart", template.getHtmlPart());
        }
        xml.end("Template");
        return Response.ok(AwsQueryResponse.envelope("GetTemplate", AwsNamespaces.SES, xml.build())).build();
    }

    private Response handleDeleteTemplate(MultivaluedMap<String, String> params, String region) {
        String templateName = getParam(params, "TemplateName");
        sesService.deleteTemplate(templateName, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("DeleteTemplate", AwsNamespaces.SES)).build();
    }

    private Response handleListTemplates(String region) {
        List<EmailTemplate> templates = sesService.listTemplates(region);
        var xml = new XmlBuilder().start("TemplatesMetadata");
        for (EmailTemplate t : templates) {
            xml.start("member")
                    .elem("Name", t.getTemplateName());
            if (t.getCreatedTimestamp() != null) {
                xml.elem("CreatedTimestamp", t.getCreatedTimestamp().toString());
            }
            xml.end("member");
        }
        xml.end("TemplatesMetadata");
        return Response.ok(AwsQueryResponse.envelope("ListTemplates", AwsNamespaces.SES, xml.build())).build();
    }

    private Response handleSendTemplatedEmail(MultivaluedMap<String, String> params, String region) {
        String source = getParam(params, "Source");
        List<String> toAddresses = extractMembers(params, "Destination.ToAddresses");
        List<String> ccAddresses = extractMembers(params, "Destination.CcAddresses");
        List<String> bccAddresses = extractMembers(params, "Destination.BccAddresses");
        List<String> replyToAddresses = extractMembers(params, "ReplyToAddresses");
        String templateName = getParam(params, "Template");
        String templateArn = getParam(params, "TemplateArn");
        String templateDataRaw = getParam(params, "TemplateData");

        boolean hasName = templateName != null && !templateName.isBlank();
        boolean hasArn = templateArn != null && !templateArn.isBlank();
        if (!hasName && !hasArn) {
            throw new AwsException("InvalidParameterValue",
                    "Template or TemplateArn is required.", 400);
        }
        String resolvedName = hasName ? templateName : SesService.templateNameFromArn(templateArn);

        JsonNode templateData = parseTemplateData(templateDataRaw);
        String messageId = sesService.sendTemplatedEmail(source, toAddresses, ccAddresses,
                bccAddresses, replyToAddresses, resolvedName, templateData, region);

        String result = new XmlBuilder().elem("MessageId", messageId).build();
        return Response.ok(AwsQueryResponse.envelope("SendTemplatedEmail", AwsNamespaces.SES, result)).build();
    }

    private EmailTemplate readTemplateParams(MultivaluedMap<String, String> params) {
        String name = getParam(params, "Template.TemplateName");
        String subject = getParam(params, "Template.SubjectPart");
        String text = getParam(params, "Template.TextPart");
        String html = getParam(params, "Template.HtmlPart");
        return new EmailTemplate(name, subject, text, html);
    }

    private JsonNode parseTemplateData(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("InvalidTemplate",
                    "Invalid TemplateData JSON: " + e.getMessage(), 400);
        }
    }

    // --- Helpers ---

    private List<String> extractMembers(MultivaluedMap<String, String> params, String prefix) {
        List<String> members = new ArrayList<>();
        for (int i = 1; ; i++) {
            String value = getParam(params, prefix + ".member." + i);
            if (value == null) break;
            members.add(value);
        }
        return members;
    }

    private String getParam(MultivaluedMap<String, String> params, String name) {
        return params.getFirst(name);
    }
}
