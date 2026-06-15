package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsQueryResponse;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntry;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntryResult;
import io.github.hectorvent.floci.services.ses.model.CloudWatchDestination;
import io.github.hectorvent.floci.services.ses.model.CloudWatchDimensionConfiguration;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.KinesisFirehoseDestination;
import io.github.hectorvent.floci.services.ses.model.MessageTag;
import io.github.hectorvent.floci.services.ses.model.SnsDestination;
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
                case "UpdateAccountSendingEnabled" -> handleUpdateAccountSendingEnabled(params, region);
                case "ListVerifiedEmailAddresses" -> handleListVerifiedEmailAddresses(region);
                case "DeleteVerifiedEmailAddress" -> handleDeleteVerifiedEmailAddress(params, region);
                case "SetIdentityNotificationTopic" -> handleSetIdentityNotificationTopic(params, region);
                case "GetIdentityNotificationAttributes" -> handleGetIdentityNotificationAttributes(params, region);
                case "SetIdentityFeedbackForwardingEnabled" -> handleSetIdentityFeedbackForwardingEnabled(params, region);
                case "SetIdentityHeadersInNotificationsEnabled" -> handleSetIdentityHeadersInNotificationsEnabled(params, region);
                case "SetIdentityMailFromDomain" -> handleSetIdentityMailFromDomain(params, region);
                case "GetIdentityMailFromDomainAttributes" -> handleGetIdentityMailFromDomainAttributes(params, region);
                case "GetIdentityDkimAttributes" -> handleGetIdentityDkimAttributes(params, region);
                case "CreateTemplate" -> handleCreateTemplate(params, region);
                case "UpdateTemplate" -> handleUpdateTemplate(params, region);
                case "GetTemplate" -> handleGetTemplate(params, region);
                case "DeleteTemplate" -> handleDeleteTemplate(params, region);
                case "ListTemplates" -> handleListTemplates(region);
                case "SendTemplatedEmail" -> handleSendTemplatedEmail(params, region);
                case "SendBulkTemplatedEmail" -> handleSendBulkTemplatedEmail(params, region);
                case "TestRenderTemplate" -> handleTestRenderTemplate(params, region);
                case "CreateConfigurationSet" -> handleCreateConfigurationSet(params, region);
                case "DescribeConfigurationSet" -> handleDescribeConfigurationSet(params, region);
                case "ListConfigurationSets" -> handleListConfigurationSets(region);
                case "DeleteConfigurationSet" -> handleDeleteConfigurationSet(params, region);
                case "CreateConfigurationSetEventDestination" ->
                        handleCreateConfigurationSetEventDestination(params, region);
                case "UpdateConfigurationSetEventDestination" ->
                        handleUpdateConfigurationSetEventDestination(params, region);
                case "DeleteConfigurationSetEventDestination" ->
                        handleDeleteConfigurationSetEventDestination(params, region);
                case "UpdateConfigurationSetSendingEnabled" ->
                        handleUpdateConfigurationSetSendingEnabled(params, region);
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
        if (!sesService.isAccountSendingEnabled(region)) {
            throw new AwsException("AccountSendingPausedException",
                    "Account sending is disabled.", 400);
        }
        String source = getParam(params, "Source");
        List<String> toAddresses = extractMembers(params, "Destination.ToAddresses");
        List<String> ccAddresses = extractMembers(params, "Destination.CcAddresses");
        List<String> bccAddresses = extractMembers(params, "Destination.BccAddresses");
        List<String> replyToAddresses = extractMembers(params, "ReplyToAddresses");
        String subject = getParam(params, "Message.Subject.Data");
        String bodyText = getParam(params, "Message.Body.Text.Data");
        String bodyHtml = getParam(params, "Message.Body.Html.Data");
        String configurationSetName = getParam(params, "ConfigurationSetName");
        List<MessageTag> emailTags = extractMessageTags(params, "Tags");

        String messageId = sesService.sendEmail(source, toAddresses, ccAddresses, bccAddresses,
                replyToAddresses, subject, bodyText, bodyHtml, configurationSetName,
                emailTags, List.of(), region);

        String result = new XmlBuilder().elem("MessageId", messageId).build();
        return Response.ok(AwsQueryResponse.envelope("SendEmail", AwsNamespaces.SES, result)).build();
    }

    private Response handleSendRawEmail(MultivaluedMap<String, String> params, String region) {
        if (!sesService.isAccountSendingEnabled(region)) {
            throw new AwsException("AccountSendingPausedException",
                    "Account sending is disabled.", 400);
        }
        String source = getParam(params, "Source");
        List<String> destinations = extractMembers(params, "Destinations");
        String rawMessage = getParam(params, "RawMessage.Data");
        String configurationSetName = getParam(params, "ConfigurationSetName");
        List<MessageTag> emailTags = extractMessageTags(params, "Tags");

        String messageId = sesService.sendRawEmail(source, destinations, rawMessage,
                configurationSetName, emailTags, region);

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

    private Response handleUpdateAccountSendingEnabled(MultivaluedMap<String, String> params, String region) {
        boolean enabled = parseOptionalBoolean(params, "Enabled", false);
        sesService.setAccountSendingEnabled(region, enabled);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("UpdateAccountSendingEnabled", AwsNamespaces.SES)).build();
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
            if (identity == null) {
                continue;
            }
            xml.start("entry");
            xml.elem("key", identityValue);
            xml.start("value");
            xml.elem("BounceTopic", identity.getNotificationAttributes().getOrDefault("BounceTopic", ""));
            xml.elem("ComplaintTopic", identity.getNotificationAttributes().getOrDefault("ComplaintTopic", ""));
            xml.elem("DeliveryTopic", identity.getNotificationAttributes().getOrDefault("DeliveryTopic", ""));
            xml.elem("ForwardingEnabled", String.valueOf(identity.isFeedbackForwardingEnabled()));
            xml.elem("HeadersInBounceNotificationsEnabled",
                    String.valueOf(identity.getHeadersInNotificationsEnabled().getOrDefault("Bounce", false)));
            xml.elem("HeadersInComplaintNotificationsEnabled",
                    String.valueOf(identity.getHeadersInNotificationsEnabled().getOrDefault("Complaint", false)));
            xml.elem("HeadersInDeliveryNotificationsEnabled",
                    String.valueOf(identity.getHeadersInNotificationsEnabled().getOrDefault("Delivery", false)));
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

    private Response handleSetIdentityFeedbackForwardingEnabled(MultivaluedMap<String, String> params, String region) {
        String identityValue = getParam(params, "Identity");
        boolean enabled = parseRequiredBoolean(params, "ForwardingEnabled");
        sesService.setFeedbackForwardingEnabled(identityValue, enabled, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("SetIdentityFeedbackForwardingEnabled", AwsNamespaces.SES)).build();
    }

    private Response handleSetIdentityHeadersInNotificationsEnabled(MultivaluedMap<String, String> params, String region) {
        String identityValue = getParam(params, "Identity");
        String notificationType = getParam(params, "NotificationType");
        boolean enabled = parseRequiredBoolean(params, "Enabled");
        sesService.setHeadersInNotificationsEnabled(identityValue, notificationType, enabled, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("SetIdentityHeadersInNotificationsEnabled", AwsNamespaces.SES)).build();
    }

    private Response handleSetIdentityMailFromDomain(MultivaluedMap<String, String> params, String region) {
        String identityValue = getParam(params, "Identity");
        String mailFromDomain = getParam(params, "MailFromDomain");
        if (mailFromDomain == null) {
            throw new AwsException("InvalidParameterValue",
                    "MailFromDomain is required (use an empty string to clear the existing setting).", 400);
        }
        String behaviorOnMxFailure = getParam(params, "BehaviorOnMXFailure");
        sesService.setMailFromDomain(identityValue, mailFromDomain, behaviorOnMxFailure, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("SetIdentityMailFromDomain", AwsNamespaces.SES)).build();
    }

    private static boolean parseRequiredBoolean(MultivaluedMap<String, String> params, String name) {
        String raw = params.getFirst(name);
        if (raw == null) {
            throw new AwsException("InvalidParameterValue", name + " is required.", 400);
        }
        if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
            throw new AwsException("InvalidParameterValue",
                    name + " must be \"true\" or \"false\".", 400);
        }
        return Boolean.parseBoolean(raw);
    }

    private static boolean parseOptionalBoolean(MultivaluedMap<String, String> params, String name, boolean defaultValue) {
        String raw = params.getFirst(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
            throw new AwsException("InvalidParameterValue",
                    name + " must be \"true\" or \"false\".", 400);
        }
        return Boolean.parseBoolean(raw);
    }

    private Response handleGetIdentityMailFromDomainAttributes(MultivaluedMap<String, String> params, String region) {
        List<String> identities = extractMembers(params, "Identities");
        var xml = new XmlBuilder().start("MailFromDomainAttributes");
        for (String identityValue : identities) {
            Identity identity = sesService.getMailFromAttributes(identityValue, region);
            xml.start("entry");
            xml.elem("key", identityValue);
            xml.start("value");
            xml.elem("MailFromDomain", identity != null && identity.getMailFromDomain() != null
                    ? identity.getMailFromDomain() : "");
            xml.elem("MailFromDomainStatus", identity != null
                    ? identity.getMailFromDomainStatus() : "Pending");
            xml.elem("BehaviorOnMXFailure", identity != null
                    ? identity.getBehaviorOnMxFailure() : "UseDefaultValue");
            xml.end("value");
            xml.end("entry");
        }
        xml.end("MailFromDomainAttributes");
        return Response.ok(AwsQueryResponse.envelope("GetIdentityMailFromDomainAttributes", AwsNamespaces.SES, xml.build())).build();
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
        if (!sesService.isAccountSendingEnabled(region)) {
            throw new AwsException("AccountSendingPausedException",
                    "Account sending is disabled.", 400);
        }
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
        String configurationSetName = getParam(params, "ConfigurationSetName");
        List<MessageTag> emailTags = extractMessageTags(params, "Tags");
        String messageId = sesService.sendTemplatedEmail(source, toAddresses, ccAddresses,
                bccAddresses, replyToAddresses, resolvedName, templateData,
                configurationSetName, emailTags, List.of(), region);

        String result = new XmlBuilder().elem("MessageId", messageId).build();
        return Response.ok(AwsQueryResponse.envelope("SendTemplatedEmail", AwsNamespaces.SES, result)).build();
    }

    private Response handleTestRenderTemplate(MultivaluedMap<String, String> params, String region) {
        String templateName = getParam(params, "TemplateName");
        if (templateName == null || templateName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "TemplateName is required.", 400);
        }
        String templateDataRaw = getParam(params, "TemplateData");
        String rendered = sesService.renderTestTemplate(templateName, templateDataRaw, region);
        // XML 1.0 character data forbids C0 controls except \t \n \r; strip them
        // so SDK clients can parse the response when template data injects \x01 etc.
        String xmlSafe = SesService.stripXml10InvalidChars(rendered);
        String result = new XmlBuilder().elem("RenderedTemplate", xmlSafe).build();
        return Response.ok(AwsQueryResponse.envelope("TestRenderTemplate", AwsNamespaces.SES, result)).build();
    }

    private Response handleSendBulkTemplatedEmail(MultivaluedMap<String, String> params, String region) {
        if (!sesService.isAccountSendingEnabled(region)) {
            throw new AwsException("AccountSendingPausedException",
                    "Account sending is disabled.", 400);
        }
        String source = getParam(params, "Source");
        List<String> replyToAddresses = extractMembers(params, "ReplyToAddresses");
        String templateName = getParam(params, "Template");
        String templateArn = getParam(params, "TemplateArn");
        String defaultDataRaw = getParam(params, "DefaultTemplateData");

        boolean hasName = templateName != null && !templateName.isBlank();
        boolean hasArn = templateArn != null && !templateArn.isBlank();
        if (!hasName && !hasArn) {
            throw new AwsException("InvalidParameterValue",
                    "Template or TemplateArn is required.", 400);
        }
        String resolvedName = hasName ? templateName : SesService.templateNameFromArn(templateArn);
        EmailTemplate template = sesService.getTemplate(resolvedName, region);
        JsonNode defaultTemplateData = parseTemplateData(defaultDataRaw);

        List<BulkEmailEntry> entries = new ArrayList<>();
        for (int i = 1; ; i++) {
            String destPrefix = "Destinations.member." + i;
            List<String> to = extractMembers(params, destPrefix + ".Destination.ToAddresses");
            List<String> cc = extractMembers(params, destPrefix + ".Destination.CcAddresses");
            List<String> bcc = extractMembers(params, destPrefix + ".Destination.BccAddresses");
            String replacementRaw = getParam(params, destPrefix + ".ReplacementTemplateData");
            List<MessageTag> replacementTags = extractMessageTags(params, destPrefix + ".ReplacementTags");
            if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()
                    && replacementRaw == null && replacementTags.isEmpty()) {
                break;
            }
            entries.add(new BulkEmailEntry(to, cc, bcc,
                    parseTemplateData(replacementRaw), replacementTags, List.of()));
        }
        if (entries.isEmpty()) {
            throw new AwsException("InvalidParameterValue",
                    "At least one destination is required.", 400);
        }

        String configurationSetName = getParam(params, "ConfigurationSetName");
        List<MessageTag> defaultEmailTags = extractMessageTags(params, "DefaultTags");
        List<BulkEmailEntryResult> results = sesService.sendBulkTemplatedEmail(source, replyToAddresses,
                template.getSubject(), template.getTextPart(), template.getHtmlPart(),
                defaultTemplateData, entries, configurationSetName,
                defaultEmailTags, List.of(), region);

        XmlBuilder xml = new XmlBuilder().start("Status");
        for (BulkEmailEntryResult result : results) {
            xml.start("member").elem("Status", result.getStatus().toV1String());
            if (result.getMessageId() != null) {
                xml.elem("MessageId", result.getMessageId());
            }
            if (result.getError() != null) {
                xml.elem("Error", result.getError());
            }
            xml.end("member");
        }
        xml.end("Status");
        return Response.ok(AwsQueryResponse.envelope("SendBulkTemplatedEmail", AwsNamespaces.SES, xml.build())).build();
    }

    private Response handleCreateConfigurationSet(MultivaluedMap<String, String> params, String region) {
        String name = getParam(params, "ConfigurationSet.Name");
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ConfigurationSet.Name is required.", 400);
        }
        sesService.createConfigurationSet(new ConfigurationSet(name), region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("CreateConfigurationSet", AwsNamespaces.SES)).build();
    }

    private Response handleDescribeConfigurationSet(MultivaluedMap<String, String> params, String region) {
        String name = getParam(params, "ConfigurationSetName");
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ConfigurationSetName is required.", 400);
        }
        ConfigurationSet cs = sesService.getConfigurationSet(name, region);
        List<String> attrs = extractMembers(params, "ConfigurationSetAttributeNames");
        XmlBuilder xml = new XmlBuilder()
                .start("ConfigurationSet")
                    .elem("Name", cs.getName())
                .end("ConfigurationSet");
        if (attrs.contains("eventDestinations")) {
            xml.start("EventDestinations");
            List<EventDestination> destinations = cs.getEventDestinations();
            if (destinations != null) {
                for (EventDestination ed : destinations) {
                    xml.start("member");
                    writeEventDestination(xml, ed);
                    xml.end("member");
                }
            }
            xml.end("EventDestinations");
        }
        if (attrs.contains("reputationOptions")) {
            xml.start("ReputationOptions")
                .elem("SendingEnabled", String.valueOf(cs.isSendingEnabledEffective()))
               .end("ReputationOptions");
        }
        return Response.ok(AwsQueryResponse.envelope("DescribeConfigurationSet",
                AwsNamespaces.SES, xml.build())).build();
    }

    /**
     * Render an {@link EventDestination} into the V1 SES XML shape. V1 wire names use the
     * uppercase {@code ARN}/{@code NS} suffix variants (e.g., {@code SNSDestination},
     * {@code TopicARN}, {@code IAMRoleARN}, {@code DeliveryStreamARN}). Skips destination
     * blocks for which no configuration is present.
     */
    private static void writeEventDestination(XmlBuilder xml, EventDestination ed) {
        xml.elem("Name", ed.getName())
           .elem("Enabled", String.valueOf(ed.isEnabled()));
        xml.start("MatchingEventTypes");
        if (ed.getMatchingEventTypes() != null) {
            for (String t : ed.getMatchingEventTypes()) {
                xml.elem("member", INTERNAL_EVENT_TYPE_TO_V1.getOrDefault(t, t));
            }
        }
        xml.end("MatchingEventTypes");
        if (ed.getSnsDestination() != null
                && ed.getSnsDestination().getTopicArn() != null
                && !ed.getSnsDestination().getTopicArn().isBlank()) {
            xml.start("SNSDestination")
               .elem("TopicARN", ed.getSnsDestination().getTopicArn())
               .end("SNSDestination");
        }
        if (ed.getKinesisFirehoseDestination() != null
                && ed.getKinesisFirehoseDestination().getIamRoleArn() != null
                && !ed.getKinesisFirehoseDestination().getIamRoleArn().isBlank()
                && ed.getKinesisFirehoseDestination().getDeliveryStreamArn() != null
                && !ed.getKinesisFirehoseDestination().getDeliveryStreamArn().isBlank()) {
            xml.start("KinesisFirehoseDestination")
               .elem("IAMRoleARN", ed.getKinesisFirehoseDestination().getIamRoleArn())
               .elem("DeliveryStreamARN", ed.getKinesisFirehoseDestination().getDeliveryStreamArn())
               .end("KinesisFirehoseDestination");
        }
        if (ed.getCloudWatchDestination() != null
                && ed.getCloudWatchDestination().getDimensionConfigurations() != null) {
            List<CloudWatchDimensionConfiguration> validDims = new ArrayList<>();
            for (CloudWatchDimensionConfiguration dim
                    : ed.getCloudWatchDestination().getDimensionConfigurations()) {
                if (dim != null
                        && dim.getDimensionName() != null && !dim.getDimensionName().isBlank()
                        && dim.getDimensionValueSource() != null && !dim.getDimensionValueSource().isBlank()
                        && dim.getDefaultDimensionValue() != null && !dim.getDefaultDimensionValue().isBlank()) {
                    validDims.add(dim);
                }
            }
            if (!validDims.isEmpty()) {
                xml.start("CloudWatchDestination").start("DimensionConfigurations");
                for (CloudWatchDimensionConfiguration dim : validDims) {
                    xml.start("member")
                       .elem("DimensionName", dim.getDimensionName())
                       .elem("DimensionValueSource", dim.getDimensionValueSource())
                       .elem("DefaultDimensionValue", dim.getDefaultDimensionValue())
                       .end("member");
                }
                xml.end("DimensionConfigurations").end("CloudWatchDestination");
            }
        }
    }

    private Response handleListConfigurationSets(String region) {
        List<ConfigurationSet> all = sesService.listConfigurationSets(region);
        XmlBuilder xml = new XmlBuilder().start("ConfigurationSets");
        for (ConfigurationSet cs : all) {
            xml.start("member").elem("Name", cs.getName()).end("member");
        }
        xml.end("ConfigurationSets");
        return Response.ok(AwsQueryResponse.envelope("ListConfigurationSets", AwsNamespaces.SES, xml.build())).build();
    }

    private Response handleDeleteConfigurationSet(MultivaluedMap<String, String> params, String region) {
        String name = getParam(params, "ConfigurationSetName");
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ConfigurationSetName is required.", 400);
        }
        sesService.deleteConfigurationSet(name, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult("DeleteConfigurationSet", AwsNamespaces.SES)).build();
    }

    private Response handleCreateConfigurationSetEventDestination(MultivaluedMap<String, String> params,
                                                                  String region) {
        String configSet = requireParam(params, "ConfigurationSetName");
        String edName = requireParam(params, "EventDestination.Name");
        EventDestination dest = readEventDestination(params, "EventDestination");
        sesService.createConfigurationSetEventDestination(configSet, edName, dest, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult(
                "CreateConfigurationSetEventDestination", AwsNamespaces.SES)).build();
    }

    private Response handleUpdateConfigurationSetEventDestination(MultivaluedMap<String, String> params,
                                                                  String region) {
        String configSet = requireParam(params, "ConfigurationSetName");
        String edName = requireParam(params, "EventDestination.Name");
        EventDestination dest = readEventDestination(params, "EventDestination");
        sesService.updateConfigurationSetEventDestination(configSet, edName, dest, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult(
                "UpdateConfigurationSetEventDestination", AwsNamespaces.SES)).build();
    }

    private Response handleDeleteConfigurationSetEventDestination(MultivaluedMap<String, String> params,
                                                                  String region) {
        String configSet = requireParam(params, "ConfigurationSetName");
        String edName = requireParam(params, "EventDestinationName");
        sesService.deleteConfigurationSetEventDestination(configSet, edName, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult(
                "DeleteConfigurationSetEventDestination", AwsNamespaces.SES)).build();
    }

    private Response handleUpdateConfigurationSetSendingEnabled(MultivaluedMap<String, String> params,
                                                                String region) {
        String configSet = requireParam(params, "ConfigurationSetName");
        boolean enabled = parseRequiredBoolean(params, "Enabled");
        sesService.setConfigurationSetSendingEnabled(configSet, enabled, region);
        return Response.ok(AwsQueryResponse.envelopeEmptyResult(
                "UpdateConfigurationSetSendingEnabled", AwsNamespaces.SES)).build();
    }

    /**
     * AWS V1 SES uses lowercased event-type names on the wire (e.g. {@code send},
     * {@code complaint}, {@code renderingFailure}) while V2 uses
     * {@code SCREAMING_SNAKE_CASE} (e.g. {@code SEND}, {@code RENDERING_FAILURE}).
     * Floci stores the V2 canonical form internally, so the V1 handler translates
     * in both directions.
     */
    private static final java.util.Map<String, String> V1_EVENT_TYPE_TO_INTERNAL =
            java.util.Map.ofEntries(
                    java.util.Map.entry("send", "SEND"),
                    java.util.Map.entry("reject", "REJECT"),
                    java.util.Map.entry("bounce", "BOUNCE"),
                    java.util.Map.entry("complaint", "COMPLAINT"),
                    java.util.Map.entry("delivery", "DELIVERY"),
                    java.util.Map.entry("open", "OPEN"),
                    java.util.Map.entry("click", "CLICK"),
                    java.util.Map.entry("renderingFailure", "RENDERING_FAILURE"),
                    java.util.Map.entry("deliveryDelay", "DELIVERY_DELAY"),
                    java.util.Map.entry("subscription", "SUBSCRIPTION"));

    private static final java.util.Map<String, String> INTERNAL_EVENT_TYPE_TO_V1;
    static {
        java.util.Map<String, String> reverse = new java.util.HashMap<>();
        for (var e : V1_EVENT_TYPE_TO_INTERNAL.entrySet()) {
            reverse.put(e.getValue(), e.getKey());
        }
        INTERNAL_EVENT_TYPE_TO_V1 = java.util.Map.copyOf(reverse);
    }

    /**
     * Parse a V1 SES Query {@code EventDestination.*} parameter group into an
     * {@link EventDestination}. V1 wire names use uppercase {@code ARN} / {@code NS}
     * suffixes ({@code SNSDestination.TopicARN}, {@code KinesisFirehoseDestination.IAMRoleARN}
     * / {@code DeliveryStreamARN}) which are mapped onto the model's Title-cased fields,
     * and the lowercase event-type wire forms are normalized to the V2 canonical form
     * via {@link #V1_EVENT_TYPE_TO_INTERNAL}.
     */
    private EventDestination readEventDestination(MultivaluedMap<String, String> params, String prefix) {
        EventDestination dest = new EventDestination();
        dest.setName(getParam(params, prefix + ".Name"));
        dest.setEnabled(parseOptionalBoolean(params, prefix + ".Enabled", false));
        List<String> rawTypes = extractMembers(params, prefix + ".MatchingEventTypes");
        List<String> normalizedTypes = new ArrayList<>(rawTypes.size());
        for (String t : rawTypes) {
            normalizedTypes.add(V1_EVENT_TYPE_TO_INTERNAL.getOrDefault(t, t));
        }
        dest.setMatchingEventTypes(normalizedTypes);

        String topicArn = getParam(params, prefix + ".SNSDestination.TopicARN");
        if (topicArn != null) {
            SnsDestination sns = new SnsDestination();
            sns.setTopicArn(topicArn);
            dest.setSnsDestination(sns);
        }

        String firehoseIam = getParam(params, prefix + ".KinesisFirehoseDestination.IAMRoleARN");
        String firehoseStream = getParam(params, prefix + ".KinesisFirehoseDestination.DeliveryStreamARN");
        if (firehoseIam != null || firehoseStream != null) {
            KinesisFirehoseDestination fh = new KinesisFirehoseDestination();
            fh.setIamRoleArn(firehoseIam);
            fh.setDeliveryStreamArn(firehoseStream);
            dest.setKinesisFirehoseDestination(fh);
        }

        List<CloudWatchDimensionConfiguration> cwDims = new ArrayList<>();
        for (int i = 1; ; i++) {
            String dimPrefix = prefix + ".CloudWatchDestination.DimensionConfigurations.member." + i;
            String name = getParam(params, dimPrefix + ".DimensionName");
            String source = getParam(params, dimPrefix + ".DimensionValueSource");
            String def = getParam(params, dimPrefix + ".DefaultDimensionValue");
            if (name == null && source == null && def == null) {
                break;
            }
            CloudWatchDimensionConfiguration dim = new CloudWatchDimensionConfiguration();
            dim.setDimensionName(name);
            dim.setDimensionValueSource(source);
            dim.setDefaultDimensionValue(def);
            cwDims.add(dim);
        }
        if (!cwDims.isEmpty()) {
            CloudWatchDestination cw = new CloudWatchDestination();
            cw.setDimensionConfigurations(cwDims);
            dest.setCloudWatchDestination(cw);
        }

        return dest;
    }

    private static String requireParam(MultivaluedMap<String, String> params, String name) {
        String v = params.getFirst(name);
        if (v == null || v.isBlank()) {
            throw new AwsException("InvalidParameterValue", name + " is required.", 400);
        }
        return v;
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
        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("InvalidTemplate",
                    "Invalid TemplateData JSON: " + e.getMessage(), 400);
        }
        if (!node.isObject()) {
            throw new AwsException("InvalidParameterValue",
                    "TemplateData must be a JSON object.", 400);
        }
        return node;
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

    /**
     * Parse a V1 SES {@code MessageTag} list ({@code <prefix>.member.N.Name} /
     * {@code .Value}) into a list of {@link MessageTag} records. Returns an empty list when
     * no members are present.
     */
    private List<MessageTag> extractMessageTags(MultivaluedMap<String, String> params, String prefix) {
        List<MessageTag> tags = new ArrayList<>();
        for (int i = 1; ; i++) {
            String name = getParam(params, prefix + ".member." + i + ".Name");
            String value = getParam(params, prefix + ".member." + i + ".Value");
            if (name == null && value == null) break;
            if (name == null || name.isBlank()) {
                throw new AwsException("InvalidParameterValue",
                        "The tag name must be specified.", 400);
            }
            tags.add(new MessageTag(name, value));
        }
        return tags;
    }

    private String getParam(MultivaluedMap<String, String> params, String name) {
        return params.getFirst(name);
    }
}
