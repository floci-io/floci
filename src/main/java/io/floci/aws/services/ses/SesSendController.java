package io.floci.aws.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.services.ses.address.SesAddressSyntax;
import io.floci.aws.services.ses.model.BulkEmailEntry;
import io.floci.aws.services.ses.model.BulkEmailEntryResult;
import io.floci.aws.services.ses.model.EmailContent;
import io.floci.aws.services.ses.model.ListManagementOptions;
import io.floci.aws.services.ses.model.MessageAttachment;
import io.floci.aws.services.ses.model.MessageHeader;
import io.floci.aws.services.ses.model.MessageTag;
import io.floci.aws.services.ses.model.SendBulkEmailRequest;
import io.floci.aws.services.ses.model.SendEmailRequest;
import io.floci.aws.services.ses.model.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.ses.SesV2Json.namesRawContent;
import static io.floci.aws.services.ses.SesV2Json.readOptionBody;
import static io.floci.aws.services.ses.SesV2Json.remapCvetException;
import static io.floci.aws.services.ses.SesV2Json.remapV1Exception;

/**
 * REST JSON controller for the three SES V2 send endpoints:
 * {@code /v2/email/outbound-emails}, {@code /v2/email/outbound-bulk-emails} and
 * {@code /v2/email/outbound-custom-verification-emails}. Every other v2 URL group has its own
 * controller in this package; the send path stays here because it is the one group that reads
 * across several domains. It goes through the {@link SesService} facade for that reason, and
 * through {@link SesAccountService} for the account-level sending switch.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesSendController {

    private static final Logger LOG = Logger.getLogger(SesSendController.class);

    private static final List<String> TRACKING_VALUES = List.of("ENABLED", "DISABLED");

    private final SesService sesService;
    // The send endpoints read the account-level sending switch before building the message.
    private final SesAccountService accountService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesSendController(SesService sesService, SesAccountService accountService,
                             RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.sesService = sesService;
        this.accountService = accountService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── Send Email ────────────────────────────

    @POST
    @Path("/outbound-emails")
    public Response sendEmail(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (!accountService.isAccountSendingEnabled(region) && !namesRawContent(objectMapper, body)) {
                throw SesAccountService.sendingPaused();
            }

            JsonNode request = readOptionBody(objectMapper, body);
            SesV2RequestShapes.check("SendEmail", request);
            validateTrackingOverrides(request);

            // FromEmailAddress is optional per the AWS v2 contract. Each content type below
            // enforces the sender requirement the way AWS does: Raw can take its From from the
            // MIME message, while Simple and Templated require FromEmailAddress.
            String fromEmailAddress = request.path("FromEmailAddress").asText(null);

            JsonNode destination = request.path("Destination");
            List<String> toAddresses = addressList(destination, "ToAddresses");
            List<String> ccAddresses = addressList(destination, "CcAddresses");
            List<String> bccAddresses = addressList(destination, "BccAddresses");
            List<String> replyToAddresses = addressList(request, "ReplyToAddresses");
            String feedbackForwardingAddress =
                    request.path("FeedbackForwardingEmailAddress").asText(null);
            // "For Raw emails, the FromEmailAddressIdentityArn value overrides the X-SES-SOURCE-ARN
            // and X-SES-FROM-ARN headers", so it stands for both ARNs of a raw send.
            String fromIdentityArn = request.path("FromEmailAddressIdentityArn").asText(null);
            String configurationSetName = request.path("ConfigurationSetName").asText(null);
            String tenantName = request.path("TenantName").asText(null);
            JsonNode content = request.path("Content");
            JsonNode template = content.path("Template");
            TemplateSelector selector = TemplateSelector.of(template);
            if (!content.has("Raw") && !content.has("Simple")) {
                requireSendEmailTemplateContent(selector, template);
            }
            List<MessageTag> emailTags = parseEmailTagsArray(request.path("EmailTags"), "EmailTags");
            ListManagementOptions listManagement =
                    parseListManagementOptions(request.path("ListManagementOptions"));

            SendEmailRequest.Builder sendRequest = SendEmailRequest.builder()
                    .source(fromEmailAddress)
                    .toAddresses(toAddresses)
                    .ccAddresses(ccAddresses)
                    .bccAddresses(bccAddresses)
                    .replyToAddresses(replyToAddresses)
                    .returnPath(feedbackForwardingAddress)
                    .sourceArn(fromIdentityArn)
                    .fromArn(fromIdentityArn)
                    .returnPathArn(request.path("FeedbackForwardingEmailAddressIdentityArn").asText(null))
                    .configurationSetName(configurationSetName)
                    .emailTags(emailTags)
                    .listManagement(listManagement)
                    .tenantName(tenantName)
                    .region(region);
            String messageId;

            if (content.has("Raw")) {
                String rawData = content.path("Raw").path("Data").asText(null);
                if (rawData == null || rawData.isBlank()) {
                    throw new AwsException("BadRequestException",
                            "Content.Raw.Data is required.", 400);
                }
                requireNoNullAddress(toAddresses, ccAddresses, bccAddresses, replyToAddresses);
                SendEmailRequest rawRequest = sendRequest.content(new EmailContent.Raw(rawData, true)).build();
                messageId = sesService.sendEmail(rawRequest);
            } else if (content.has("Simple")) {
                if (fromEmailAddress == null || fromEmailAddress.isBlank()) {
                    // AWS returns BadRequestException with a null message body here.
                    throw new AwsException("BadRequestException", null, 400);
                }
                JsonNode simple = content.path("Simple");
                String subject = simple.path("Subject").path("Data").asText("");
                String bodyText = simple.path("Body").path("Text").path("Data").asText(null);
                String bodyHtml = simple.path("Body").path("Html").path("Data").asText(null);
                List<MessageHeader> additionalHeaders =
                        parseHeadersArray(simple.path("Headers"), "content.simple.headers");
                List<MessageAttachment> attachments =
                        parseAttachmentsArray(simple.path("Attachments"), "content.simple.attachments");
                requireNoNullAddress(toAddresses, ccAddresses, bccAddresses, replyToAddresses);
                SendEmailRequest simpleRequest = sendRequest
                        .content(new EmailContent.Simple(subject, bodyText, bodyHtml, additionalHeaders,
                                attachments))
                        .build();
                messageId = sesService.sendEmail(simpleRequest);
            } else if (content.has("Template")) {
                if (fromEmailAddress == null || fromEmailAddress.isEmpty()) {
                    throw new AwsException("BadRequestException", "Source cannot be empty", 400);
                }
                if (fromEmailAddress.isBlank()) {
                    throw new AwsException("BadRequestException",
                            "Invalid email address " + fromEmailAddress + ".", 400);
                }
                if (selector.isEmpty()) {
                    throw new AwsException("BadRequestException",
                            "TemplateName and TemplateArn cannot be both empty", 400);
                }
                selector.requireNameAlignsWithArn(region);
                JsonNode templateData = parseTemplateData(template, "TemplateData");
                List<MessageHeader> additionalHeaders =
                        parseHeadersArray(template.path("Headers"), "content.template.headers");
                if (selector.namesStoredTemplate()) {
                    requireNoNullAddress(toAddresses, ccAddresses, bccAddresses, replyToAddresses);
                    SendEmailRequest templateRequest = sendRequest
                            .content(new EmailContent.Template(selector.storedName(region), templateData,
                                    additionalHeaders))
                            .build();
                    messageId = sesService.sendEmail(templateRequest);
                } else {
                    JsonNode inline = template.path("TemplateContent");
                    String subject = inline.path("Subject").asText(null);
                    String text = inline.path("Text").asText(null);
                    String html = inline.path("Html").asText(null);
                    requireNoNullAddress(toAddresses, ccAddresses, bccAddresses, replyToAddresses);
                    SendEmailRequest inlineRequest = sendRequest
                            .content(new EmailContent.InlineTemplate(subject, text, html, templateData,
                                    additionalHeaders))
                            .build();
                    messageId = sesService.sendEmail(inlineRequest);
                }
            } else {
                throw new AwsException("BadRequestException",
                        "Content must contain Raw, Simple, or Template.", 400);
            }

            ObjectNode result = objectMapper.createObjectNode();
            result.put("MessageId", messageId);

            LOG.infov("SES V2 SendEmail: from={0}, to={1}, messageId={2}",
                    fromEmailAddress, toAddresses, messageId);
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/outbound-bulk-emails")
    public Response sendBulkEmail(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(objectMapper, body);
            SesV2RequestShapes.check("SendBulkEmail", request);
            validateTrackingOverrides(request);
            JsonNode defaultTemplateName = request.path("DefaultContent").path("Template").path("TemplateName");
            if (defaultTemplateName.isTextual() && defaultTemplateName.textValue().isEmpty()) {
                throw SmithyValidationErrors.badRequest(SmithyValidationErrors.Violations.WITHOUT_VALUES
                        .lengthTooShort("defaultContent.template.templateName", "", 1));
            }
            JsonNode bulkEntries = request.path("BulkEmailEntries");
            if (bulkEntries.isMissingNode() || bulkEntries.isNull()) {
                throw SmithyValidationErrors.badRequest(SmithyValidationErrors.notNullWithoutValue("bulkEmailEntries"));
            }
            requireInlineTemplateFields(request.path("DefaultContent").path("Template"));
            String fromEmailAddress = request.path("FromEmailAddress").asText(null);
            if (fromEmailAddress == null || fromEmailAddress.isBlank()) {
                throw new AwsException("BadRequestException", "Email address not specified.", 400);
            }
            SesAddressSyntax.requireBulkAddress(fromEmailAddress);
            List<String> replyToAddresses = addressList(request, "ReplyToAddresses");
            String feedbackForwardingAddress =
                    request.path("FeedbackForwardingEmailAddress").asText(null);
            SesAddressSyntax.requireBulkAddress(feedbackForwardingAddress);
            String configurationSetName = request.path("ConfigurationSetName").asText(null);
            String tenantName = request.path("TenantName").asText(null);

            JsonNode template = request.path("DefaultContent").path("Template");
            if (template.isMissingNode() || template.isNull()) {
                throw new AwsException("BadRequestException",
                        "DefaultContent.Template is required.", 400);
            }
            TemplateSelector selector = TemplateSelector.of(template);
            if (selector.isEmpty()) {
                throw new AwsException("BadRequestException",
                        "TemplateName and TemplateArn both can not be empty", 400);
            }
            // AWS counts the entries after the inline content check and the sender checks, and
            // before it rejects a stored template named beside the content or looks one up
            // (probe-confirmed).
            SesService.requireBulkDestinationCount(bulkEntries.size());
            if (selector.inline() && selector.namesStoredTemplate()) {
                throw new AwsException("BadRequestException",
                        "TemplateName or TemplateArn cannot be provided with EmailTemplateContent", 400);
            }
            selector.requireNameAlignsWithArn(region);

            String subject = null;
            String text = null;
            String html = null;
            // The send looks the stored template up only after its sender checks (probe-confirmed).
            String storedTemplateName = null;
            if (selector.inline()) {
                JsonNode inline = template.path("TemplateContent");
                subject = inline.path("Subject").asText(null);
                text = inline.path("Text").asText(null);
                html = inline.path("Html").asText(null);
            } else {
                storedTemplateName = selector.storedName(region);
            }

            JsonNode defaultTemplateData = parseTemplateData(template, "TemplateData");
            List<MessageTag> defaultEmailTags = parseEmailTagsArray(request.path("DefaultEmailTags"), "DefaultEmailTags");
            List<MessageHeader> defaultHeaders =
                    parseHeadersArray(template.path("Headers"), "defaultContent.template.headers");

            List<BulkEmailEntry> entries = new ArrayList<>();
            int entryIndex = 1;
            for (JsonNode node : bulkEntries) {
                if (!node.isObject()) {
                    throw new AwsException("BadRequestException",
                            "BulkEmailEntries elements must be JSON objects.", 400);
                }
                JsonNode dest = node.path("Destination");
                List<String> to = addressList(dest, "ToAddresses");
                List<String> cc = addressList(dest, "CcAddresses");
                List<String> bcc = addressList(dest, "BccAddresses");
                JsonNode replacementTemplate = node.path("ReplacementEmailContent").path("ReplacementTemplate");
                JsonNode replacementData = parseTemplateData(replacementTemplate, "ReplacementTemplateData");
                List<MessageTag> replacementTags = parseEmailTagsArray(node.path("ReplacementTags"), "ReplacementTags");
                List<MessageHeader> entryReplacementHeaders = parseHeadersArray(node.path("ReplacementHeaders"),
                        "bulkEmailEntries." + entryIndex + ".replacementHeaders");
                entries.add(new BulkEmailEntry(to, cc, bcc, replacementData, replacementTags, entryReplacementHeaders));
                entryIndex++;
            }

            // The send looks the tenant up, so only after every part of the request has been parsed
            // and validated: AWS reports malformed content before a missing tenant (probe-confirmed).
            EmailContent.TemplateContent defaultContent = storedTemplateName == null
                    ? new EmailContent.InlineTemplate(subject, text, html, defaultTemplateData, defaultHeaders)
                    : new EmailContent.Template(storedTemplateName, defaultTemplateData, defaultHeaders);

            List<BulkEmailEntryResult> results = sesService.sendBulkEmail(SendBulkEmailRequest.builder()
                    .source(fromEmailAddress)
                    .sourceArn(request.path("FromEmailAddressIdentityArn").asText(null))
                    .replyToAddresses(replyToAddresses)
                    .returnPath(feedbackForwardingAddress)
                    .returnPathArn(request.path("FeedbackForwardingEmailAddressIdentityArn").asText(null))
                    .configurationSetName(configurationSetName)
                    .defaultEmailTags(defaultEmailTags)
                    .tenantName(tenantName)
                    .region(region)
                    .defaultContent(defaultContent)
                    .entries(entries)
                    .build());

            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode arr = response.putArray("BulkEmailEntryResults");
            for (BulkEmailEntryResult r : results) {
                ObjectNode item = objectMapper.createObjectNode();
                item.put("Status", r.getStatus().name());
                if (r.getMessageId() != null) {
                    item.put("MessageId", r.getMessageId());
                }
                if (r.getError() != null) {
                    item.put("Error", r.getError());
                }
                arr.add(item);
            }

            LOG.infov("SES V2 SendBulkEmail: from={0}, entries={1}",
                    fromEmailAddress, entries.size());
            return Response.ok(response).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // ──────────────── Custom verification email templates ────────────────

    @POST
    @Path("/outbound-custom-verification-emails")
    public Response sendCustomVerificationEmail(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        String templateName = null;
        try {
            accountService.requireAccountSendingEnabled(region);
            JsonNode request = readOptionBody(objectMapper, body);
            SesV2RequestShapes.check("SendCustomVerificationEmail", request);
            templateName = request.path("TemplateName").asText(null);
            String messageId = sesService.sendCustomVerificationEmail(
                    request.path("EmailAddress").asText(null), templateName,
                    request.path("ConfigurationSetName").asText(null), region);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("MessageId", messageId);
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapCvetException(e, templateName);
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    // A null element passes deserialization on AWS and is kept: it fails later as an invalid
    // address, after the content and sender checks (probe-confirmed).
    private static List<String> addressList(JsonNode parent, String field) {
        List<String> addresses = new ArrayList<>();
        for (JsonNode item : parent.path(field)) {
            addresses.add(item.isNull() ? null : item.textValue());
        }
        return addresses;
    }

    // On SendEmail a null address is reported after the content and sender checks and before the
    // tenant lookup (probe-confirmed).
    @SafeVarargs
    private static void requireNoNullAddress(List<String>... lists) {
        for (List<String> addresses : lists) {
            if (addresses.contains(null)) {
                throw new AwsException("BadRequestException", "Invalid email address null.", 400);
            }
        }
    }

    // AWS reports an incomplete inline template ahead of a missing or malformed sender, the
    // destinations, the entry count, the template data, the message tags, the list management
    // options, the feedback address and the configuration set and tenant lookups (probe-confirmed
    // on SendEmail and SendBulkEmail). SendBulkEmail reports it even when the template also names
    // a stored one.
    private static void requireInlineTemplateFields(JsonNode template) {
        if (template.hasNonNull("TemplateContent")) {
            JsonNode inline = template.path("TemplateContent");
            SesService.requireInlineTemplateContent(inline.path("Subject").asText(null),
                    inline.path("Text").asText(null), inline.path("Html").asText(null));
        }
    }

    // SendEmail rejects a stored template named beside TemplateContent ahead of the sender, the
    // destinations, the template data, the message tags, the list management options, the feedback
    // address and the configuration set and tenant lookups, whatever the content holds
    // (probe-confirmed).
    private static void requireSendEmailTemplateContent(TemplateSelector selector, JsonNode template) {
        if (selector.inline() && selector.namesStoredTemplate()) {
            throw new AwsException("BadRequestException",
                    "TemplateName and TemplateArn cannot be provided when TemplateContent is provided.", 400);
        }
        requireInlineTemplateFields(template);
    }

    /**
     * Which of TemplateName, TemplateArn and TemplateContent a send's template carries. A member
     * sent as an explicit null counts as absent (probe-confirmed).
     */
    private record TemplateSelector(String name, String arn, boolean inline) {

        static TemplateSelector of(JsonNode template) {
            return new TemplateSelector(template.path("TemplateName").asText(null),
                    template.path("TemplateArn").asText(null), template.hasNonNull("TemplateContent"));
        }

        boolean hasName() {
            return name != null && !name.isBlank();
        }

        boolean hasArn() {
            return arn != null && !arn.isBlank();
        }

        boolean namesStoredTemplate() {
            return hasName() || hasArn();
        }

        boolean isEmpty() {
            return !inline && !namesStoredTemplate();
        }

        String storedName(String region) {
            return hasName() ? name : SesTemplateService.templateNameFromArn(arn, region);
        }

        void requireNameAlignsWithArn(String region) {
            if (hasName() && hasArn()) {
                SesTemplateService.requireNameAlignsWithArn(name, arn, region);
            }
        }
    }

    private JsonNode parseTemplateData(JsonNode parent, String fieldName) {
        return parseTemplateData(parent.path(fieldName).asText(null));
    }

    private JsonNode parseTemplateData(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new AwsException("BadRequestException",
                    "Invalid TemplateData JSON: " + e.getMessage(), 400);
        }
        if (!node.isObject()) {
            throw new AwsException("BadRequestException",
                    "TemplateData must be a JSON object.", 400);
        }
        return node;
    }

    /**
     * Parse a V2 SES {@code Content.Simple.Headers} / {@code Content.Template.Headers} array
     * (additional message headers, elements use {@code Name}/{@code Value}). Returns an empty
     * list when the node is absent so callers can pass it through unconditionally. Both members
     * are required: an entry that omits {@code Name} or {@code Value} is rejected the way AWS
     * does, with a Smithy constraint message anchored at {@code location} (e.g.
     * {@code content.simple.headers}) and the offending 1-based index.
     */
    private List<MessageHeader> parseHeadersArray(JsonNode headersNode, String location) {
        List<MessageHeader> out = new ArrayList<>();
        int index = 1;
        for (JsonNode h : headersNode) {
            if (!h.isObject()) {
                throw new AwsException("BadRequestException",
                        "Headers entries must be JSON objects.", 400);
            }
            JsonNode nameNode = h.get("Name");
            JsonNode valueNode = h.get("Value");
            if (nameNode == null || nameNode.isNull()) {
                throw missingHeaderMember(location, index, "name");
            }
            if (valueNode == null || valueNode.isNull()) {
                throw missingHeaderMember(location, index, "value");
            }
            String name = nameNode.asText();
            if (name.isBlank()) {
                throw new AwsException("BadRequestException",
                        "The header name must be specified.", 400);
            }
            out.add(new MessageHeader(name, valueNode.asText()));
            index++;
        }
        return out;
    }

    /**
     * Parse a V2 SES {@code Content.Simple.Attachments} array. {@code FileName} and
     * {@code RawContent} are required, the string members carry the documented length limits and
     * the two enum members their value sets; a violation is reported with a Smithy constraint
     * message anchored at {@code location} and the offending 1-based index, as for headers.
     */
    private List<MessageAttachment> parseAttachmentsArray(JsonNode attachmentsNode, String location) {
        List<MessageAttachment> out = new ArrayList<>();
        int index = 1;
        for (JsonNode node : attachmentsNode) {
            if (!node.isObject()) {
                throw new AwsException("BadRequestException",
                        "Attachments entries must be JSON objects.", 400);
            }
            String memberPrefix = location + "." + index + ".member.";
            String fileName = attachmentString(node, "FileName", memberPrefix + "fileName", 0, 255);
            if (fileName == null) {
                throw SmithyValidationErrors.badRequest(
                        SmithyValidationErrors.notNullWithoutValue(memberPrefix + "fileName"));
            }
            JsonNode rawNode = node.get("RawContent");
            if (rawNode == null || rawNode.isNull()) {
                throw SmithyValidationErrors.badRequest(
                        SmithyValidationErrors.notNullWithoutValue(memberPrefix + "rawContent"));
            }
            byte[] rawContent;
            try {
                rawContent = Base64.getDecoder().decode(rawNode.textValue());
            } catch (IllegalArgumentException e) {
                throw new AwsException("BadRequestException",
                        "RawContent is not valid base64: " + e.getMessage(), 400);
            }
            String contentType = attachmentHeaderValue(node, "ContentType", memberPrefix + "contentType", 1, 78);
            String description = attachmentHeaderValue(node, "ContentDescription",
                    memberPrefix + "contentDescription", 0, 1000);
            String contentId = attachmentHeaderValue(node, "ContentId", memberPrefix + "contentId", 1, 78);
            MessageAttachment.Disposition disposition = attachmentEnum(node, "ContentDisposition",
                    memberPrefix + "contentDisposition", MessageAttachment.Disposition.class);
            MessageAttachment.TransferEncoding transferEncoding = attachmentEnum(node,
                    "ContentTransferEncoding", memberPrefix + "contentTransferEncoding",
                    MessageAttachment.TransferEncoding.class);
            out.add(new MessageAttachment(fileName, rawContent, contentType, disposition, description,
                    contentId, transferEncoding));
            index++;
        }
        return List.copyOf(out);
    }

    private static String attachmentString(JsonNode node, String member, String path, int min, int max) {
        JsonNode value = node.get(member);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.textValue();
        if (text.length() < min) {
            throw constraintViolation(text, path, "Member must have length greater than or equal to " + min);
        }
        if (text.length() > max) {
            throw constraintViolation(text, path, "Member must have length less than or equal to " + max);
        }
        return text;
    }

    /**
     * An attachment member written verbatim into the part's MIME headers. The mail encoder only
     * encodes the file name, so a CR or LF here would add header lines to the part; it is refused
     * by the same rule {@link MessageHeader#isSafe()} applies to custom headers.
     */
    private static String attachmentHeaderValue(JsonNode node, String member, String path, int min, int max) {
        String text = attachmentString(node, member, path, min, max);
        if (text != null && !MessageHeader.noCrlf(text)) {
            throw new AwsException("BadRequestException",
                    member + " must not contain line breaks.", 400);
        }
        return text;
    }

    private static <E extends Enum<E>> E attachmentEnum(JsonNode node, String member, String path,
                                                        Class<E> type) {
        String text = attachmentString(node, member, path, 0, Integer.MAX_VALUE);
        if (text == null) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(text)) {
                return constant;
            }
        }
        throw constraintViolation(text, path,
                "Member must satisfy enum value set: " + Arrays.toString(type.getEnumConstants()));
    }

    private static AwsException constraintViolation(String value, String path, String constraint) {
        return SmithyValidationErrors.badRequest(
                "Value '" + value + "' at '" + path + "' failed to satisfy constraint: " + constraint);
    }

    private static AwsException missingHeaderMember(String location, int index, String member) {
        return SmithyValidationErrors.badRequest(
                SmithyValidationErrors.notNullWithoutValue(location + "." + index + ".member." + member));
    }

    /**
     * {@code ConfigurationOverrides.Tracking}'s enum, checked the way SES checks it (probed
     * 2026-10-06): a value outside it is a validation error, both values reported together with the
     * click one first. The shapes check has already refused a wrong JSON type. Floci has no open or
     * click tracking, so a valid override is accepted and has no effect.
     */
    private static void validateTrackingOverrides(JsonNode request) {
        JsonNode tracking = request.path("ConfigurationOverrides").path("Tracking");
        String open = tracking.path("OpenTrackingEnabled").asText(null);
        String click = tracking.path("ClickTrackingEnabled").asText(null);
        List<String> violations = new ArrayList<>();
        if (click != null && !TRACKING_VALUES.contains(click)) {
            violations.add(trackingViolation("clickTrackingEnabled"));
        }
        if (open != null && !TRACKING_VALUES.contains(open)) {
            violations.add(trackingViolation("openTrackingEnabled"));
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.badRequest(violations);
        }
    }

    private static String trackingViolation(String member) {
        return "Value at 'configurationOverrides.tracking." + member
                + "' failed to satisfy constraint: Member must satisfy enum value set: ["
                + String.join(", ", TRACKING_VALUES) + "]";
    }

    private static ListManagementOptions parseListManagementOptions(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode listNode = node.path("ContactListName");
        if (!listNode.isTextual() || listNode.textValue().isBlank()) {
            throw new AwsException("BadRequestException",
                    "ListManagementOptions.ContactListName is required.", 400);
        }
        return new ListManagementOptions(listNode.textValue(), node.path("TopicName").asText(null));
    }

    /**
     * Parse a V2 SES {@code EmailTags} / {@code DefaultEmailTags} / {@code ReplacementTags}
     * array (per-message {@link MessageTag} list whose elements use {@code Name}/{@code Value},
     * distinct from the resource-tag {@link Tag} {@code Key}/{@code Value} shape). Note that
     * the per-entry name is {@code ReplacementTags} on the wire: only the top-level field
     * carries the {@code EmailTags} suffix. Returns an empty list when the node is absent so
     * callers can pass it through unconditionally.
     * The {@code fieldName} parameter is reported in the error message when an element is
     * null.
     */
    private List<MessageTag> parseEmailTagsArray(JsonNode tagsNode, String fieldName) {
        List<MessageTag> out = new ArrayList<>();
        for (JsonNode t : tagsNode) {
            if (!t.isObject()) {
                throw new AwsException("BadRequestException",
                        fieldName + " entries must be JSON objects.", 400);
            }
            String name = t.path("Name").asText(null);
            String value = t.path("Value").asText(null);
            if (name == null || name.isBlank()) {
                throw new AwsException("BadRequestException",
                        "The tag name must be specified.", 400);
            }
            out.add(new MessageTag(name, value));
        }
        return out;
    }

}
