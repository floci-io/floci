package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntry;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntryResult;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.ListManagementOptions;
import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import io.github.hectorvent.floci.services.ses.model.MessageTag;
import io.github.hectorvent.floci.services.ses.model.Tag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.ses.SesV2Json.remapV1Exception;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireJsonObject;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireObjectOrAbsent;
import static io.github.hectorvent.floci.services.ses.SesV2Json.stringMemberOrAbsent;

/**
 * REST JSON controller for the AWS SES V2 API.
 * Implements the AWS SES V2 wire protocol at /v2/email/* for the operations
 * exposed by this controller.
 * Reuses the shared {@link SesService} for business logic shared with other SES
 * protocol handlers.
 *
 * Follows the same pattern as {@code LambdaController}: AwsExceptions are thrown
 * directly and converted by the global {@code AwsExceptionMapper}.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesController {

    private static final Logger LOG = Logger.getLogger(SesController.class);

    private final SesService sesService;
    // The bulk send resolves a stored template's content before handing the entries to the facade.
    private final SesTemplateService templateService;
    // The send endpoints read the account-level sending switch before building the message.
    private final SesAccountService accountService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesController(SesService sesService, SesTemplateService templateService,
                         SesAccountService accountService, RegionResolver regionResolver,
                         ObjectMapper objectMapper) {
        this.sesService = sesService;
        this.templateService = templateService;
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
            if (!accountService.isAccountSendingEnabled(region)) {
                throw new AwsException("SendingPausedException",
                        "Account sending is disabled.", 400);
            }

            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);

            // FromEmailAddress is optional per the AWS v2 contract. Each content type below
            // enforces the sender requirement the way AWS does: Raw can take its From from the
            // MIME message, while Simple and Templated require FromEmailAddress.
            String fromEmailAddress = request.path("FromEmailAddress").asText(null);

            JsonNode destination = requireObjectOrAbsent(request, "Destination");
            List<String> toAddresses = jsonArrayToList(destination.path("ToAddresses"));
            List<String> ccAddresses = jsonArrayToList(destination.path("CcAddresses"));
            List<String> bccAddresses = jsonArrayToList(destination.path("BccAddresses"));
            List<String> replyToAddresses = jsonArrayToList(request.path("ReplyToAddresses"));
            String feedbackForwardingAddress =
                    request.path("FeedbackForwardingEmailAddress").asText(null);
            List<String> allDestinations = mergeLists(toAddresses, ccAddresses, bccAddresses);
            String configurationSetName = request.path("ConfigurationSetName").asText(null);
            String tenantName = stringMemberOrAbsent(request, "TenantName");
            List<MessageTag> emailTags = parseEmailTagsArray(request.path("EmailTags"), "EmailTags");
            ListManagementOptions listManagement =
                    parseListManagementOptions(request.path("ListManagementOptions"));

            JsonNode content = request.path("Content");
            String messageId;

            if (content.has("Raw")) {
                String rawData = content.path("Raw").path("Data").asText(null);
                if (rawData == null || rawData.isBlank()) {
                    throw new AwsException("BadRequestException",
                            "Content.Raw.Data is required.", 400);
                }
                if (allDestinations.isEmpty()) {
                    throw new AwsException("BadRequestException",
                            "At least one destination address is required.", 400);
                }
                sesService.checkTenantRawSendAccess(tenantName, fromEmailAddress, rawData,
                        configurationSetName, regionResolver.getAccountId(), region);
                messageId = sesService.sendRawEmail(fromEmailAddress, allDestinations, rawData,
                        feedbackForwardingAddress, configurationSetName, emailTags, listManagement,
                        region);
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
                sesService.checkTenantSendAccess(tenantName, fromEmailAddress, configurationSetName,
                        null, regionResolver.getAccountId(), region);
                messageId = sesService.sendEmail(fromEmailAddress, toAddresses, ccAddresses,
                        bccAddresses, replyToAddresses, feedbackForwardingAddress,
                        subject, bodyText, bodyHtml,
                        configurationSetName, emailTags, additionalHeaders, listManagement, region);
            } else if (content.has("Template")) {
                if (fromEmailAddress == null || fromEmailAddress.isBlank()) {
                    throw new AwsException("BadRequestException", "Source cannot be empty", 400);
                }
                JsonNode template = content.path("Template");
                String templateName = template.path("TemplateName").asText(null);
                String templateArn = template.path("TemplateArn").asText(null);
                boolean hasName = templateName != null && !templateName.isBlank();
                boolean hasArn = templateArn != null && !templateArn.isBlank();
                boolean hasInline = template.has("TemplateContent");
                int selectorCount = (hasName ? 1 : 0) + (hasArn ? 1 : 0) + (hasInline ? 1 : 0);
                if (selectorCount > 1) {
                    throw new AwsException("BadRequestException",
                            "Content.Template must specify exactly one of TemplateName, TemplateArn, or TemplateContent.",
                            400);
                }
                if (selectorCount == 0) {
                    throw new AwsException("BadRequestException",
                            "Content.Template requires TemplateName, TemplateArn, or TemplateContent.", 400);
                }
                JsonNode templateData = parseTemplateData(template, "TemplateData");
                List<MessageHeader> additionalHeaders =
                        parseHeadersArray(template.path("Headers"), "content.template.headers");
                if (hasName || hasArn) {
                    String resolvedName = hasName
                            ? templateName
                            : SesTemplateService.templateNameFromArn(templateArn);
                    sesService.checkTenantSendAccess(tenantName, fromEmailAddress,
                            configurationSetName, resolvedName, regionResolver.getAccountId(), region);
                    messageId = sesService.sendTemplatedEmail(fromEmailAddress, toAddresses, ccAddresses,
                            bccAddresses, replyToAddresses, feedbackForwardingAddress,
                            resolvedName, templateData,
                            configurationSetName, emailTags, additionalHeaders, listManagement, region);
                } else {
                    JsonNode inline = template.path("TemplateContent");
                    String subject = inline.path("Subject").asText(null);
                    String text = inline.path("Text").asText(null);
                    String html = inline.path("Html").asText(null);
                    // An empty inline template is reported before the tenant lookup on AWS; the
                    // inline content is not a stored template resource, so only the identity and
                    // configuration set pass through the gate.
                    SesService.requireInlineTemplateContent(subject, text, html);
                    sesService.checkTenantSendAccess(tenantName, fromEmailAddress,
                            configurationSetName, null, regionResolver.getAccountId(), region);
                    messageId = sesService.sendInlineTemplatedEmail(fromEmailAddress, toAddresses,
                            ccAddresses, bccAddresses, replyToAddresses, feedbackForwardingAddress,
                            subject, text, html, templateData,
                            configurationSetName, emailTags, additionalHeaders, listManagement, region);
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
            if (!accountService.isAccountSendingEnabled(region)) {
                throw new AwsException("SendingPausedException",
                        "Account sending is disabled.", 400);
            }

            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            String fromEmailAddress = request.path("FromEmailAddress").asText(null);
            if (fromEmailAddress == null || fromEmailAddress.isBlank()) {
                throw new AwsException("BadRequestException",
                        "FromEmailAddress is required.", 400);
            }
            List<String> replyToAddresses = jsonArrayToList(request.path("ReplyToAddresses"));
            String feedbackForwardingAddress =
                    request.path("FeedbackForwardingEmailAddress").asText(null);
            String configurationSetName = request.path("ConfigurationSetName").asText(null);
            String tenantName = stringMemberOrAbsent(request, "TenantName");

            JsonNode template = request.path("DefaultContent").path("Template");
            if (template.isMissingNode() || template.isNull()) {
                throw new AwsException("BadRequestException",
                        "DefaultContent.Template is required.", 400);
            }
            String templateName = template.path("TemplateName").asText(null);
            String templateArn = template.path("TemplateArn").asText(null);
            boolean hasName = templateName != null && !templateName.isBlank();
            boolean hasArn = templateArn != null && !templateArn.isBlank();
            boolean hasInline = template.has("TemplateContent");
            int selectorCount = (hasName ? 1 : 0) + (hasArn ? 1 : 0) + (hasInline ? 1 : 0);
            if (selectorCount > 1) {
                throw new AwsException("BadRequestException",
                        "DefaultContent.Template must specify exactly one of TemplateName, TemplateArn, or TemplateContent.",
                        400);
            }
            if (selectorCount == 0) {
                throw new AwsException("BadRequestException",
                        "DefaultContent.Template requires TemplateName, TemplateArn, or TemplateContent.", 400);
            }

            String subject;
            String text;
            String html;
            // Inline template content is not a stored template resource, so it stays out of the
            // tenant gate below.
            String gateTemplateName = null;
            if (hasInline) {
                JsonNode inline = template.path("TemplateContent");
                subject = inline.path("Subject").asText(null);
                text = inline.path("Text").asText(null);
                html = inline.path("Html").asText(null);
                // Shape validation belongs before the tenant gate below, as on SendEmail.
                SesService.requireInlineTemplateContent(subject, text, html);
            } else {
                String resolvedName = hasName
                        ? templateName
                        : SesTemplateService.templateNameFromArn(templateArn);
                gateTemplateName = resolvedName;
                EmailTemplate stored = templateService.getTemplate(resolvedName, region);
                subject = stored.getSubject();
                text = stored.getTextPart();
                html = stored.getHtmlPart();
            }

            JsonNode defaultTemplateData = parseTemplateData(template, "TemplateData");
            List<MessageTag> defaultEmailTags = parseEmailTagsArray(request.path("DefaultEmailTags"), "DefaultEmailTags");
            List<MessageHeader> defaultHeaders =
                    parseHeadersArray(template.path("Headers"), "defaultContent.template.headers");

            JsonNode bulkEntries = request.path("BulkEmailEntries");
            if (!bulkEntries.isArray() || bulkEntries.isEmpty()) {
                throw new AwsException("BadRequestException",
                        "BulkEmailEntries must be a non-empty array.", 400);
            }

            List<BulkEmailEntry> entries = new ArrayList<>();
            int entryIndex = 1;
            for (JsonNode node : bulkEntries) {
                if (!node.isObject()) {
                    throw new AwsException("BadRequestException",
                            "BulkEmailEntries elements must be JSON objects.", 400);
                }
                JsonNode dest = requireObjectOrAbsent(node, "Destination");
                List<String> to = jsonArrayToList(dest.path("ToAddresses"));
                List<String> cc = jsonArrayToList(dest.path("CcAddresses"));
                List<String> bcc = jsonArrayToList(dest.path("BccAddresses"));
                JsonNode replacementContent = requireObjectOrAbsent(node, "ReplacementEmailContent");
                JsonNode replacementTemplate = requireObjectOrAbsent(replacementContent, "ReplacementTemplate");
                JsonNode replacementData = parseTemplateData(replacementTemplate, "ReplacementTemplateData");
                List<MessageTag> replacementTags = parseEmailTagsArray(node.path("ReplacementTags"), "ReplacementTags");
                List<MessageHeader> entryReplacementHeaders = parseHeadersArray(node.path("ReplacementHeaders"),
                        "bulkEmailEntries." + entryIndex + ".replacementHeaders");
                entries.add(new BulkEmailEntry(to, cc, bcc, replacementData, replacementTags, entryReplacementHeaders));
                entryIndex++;
            }

            // The tenant gate runs only after every part of the request has been parsed and
            // validated — AWS reports malformed content before a missing tenant (probe-confirmed).
            sesService.checkTenantSendAccess(tenantName, fromEmailAddress, configurationSetName,
                    gateTemplateName, regionResolver.getAccountId(), region);

            List<BulkEmailEntryResult> results = sesService.sendBulkTemplatedEmail(fromEmailAddress,
                    replyToAddresses, feedbackForwardingAddress, subject, text, html,
                    defaultTemplateData, entries, configurationSetName,
                    defaultEmailTags, defaultHeaders, region);

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
            if (!accountService.isAccountSendingEnabled(region)) {
                throw new AwsException("SendingPausedException",
                        "Account sending is disabled.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            templateName = request.path("TemplateName").asText(null);
            String messageId = sesService.sendCustomVerificationEmail(
                    request.path("EmailAddress").asText(null), templateName,
                    request.path("ConfigurationSetName").asText(null), region);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("MessageId", messageId);
            return Response.ok(result).build();
        } catch (AwsException e) {
            // AWS returns a longer not-found message on v2 than the v1 send message ("Template <name>
            // does not exist"); the service throws the v1-native form, so restate it in the v2 wording.
            if ("CustomVerificationEmailTemplateDoesNotExist".equals(e.getErrorCode())) {
                throw new AwsException("NotFoundException",
                        "Custom verification email template <" + templateName + "> does not exist", 404);
            }
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private List<String> jsonArrayToList(JsonNode arrayNode) {
        if (arrayNode == null || arrayNode.isMissingNode() || !arrayNode.isArray()) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        arrayNode.forEach(node -> list.add(node.asText()));
        return list;
    }

    private List<String> mergeLists(List<String> to, List<String> cc, List<String> bcc) {
        List<String> all = new ArrayList<>(to);
        all.addAll(cc);
        all.addAll(bcc);
        return all;
    }

    private JsonNode parseTemplateData(JsonNode parent, String fieldName) {
        if (parent == null || parent.isMissingNode() || parent.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (!parent.isObject()) {
            throw new AwsException("BadRequestException",
                    "Parent of " + fieldName + " must be a JSON object.", 400);
        }
        JsonNode field = parent.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (!field.isTextual()) {
            throw new AwsException("BadRequestException",
                    fieldName + " must be a JSON-encoded string.", 400);
        }
        return parseTemplateData(field.asText(""));
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
        if (headersNode.isMissingNode() || headersNode.isNull()) {
            return List.of();
        }
        if (!headersNode.isArray()) {
            throw new AwsException("BadRequestException", "Headers must be an array.", 400);
        }
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

    private AwsException missingHeaderMember(String location, int index, String member) {
        return new AwsException("BadRequestException",
                "1 validation error detected: Value at '" + location + "." + index + ".member." + member
                        + "' failed to satisfy constraint: Member must not be null", 400);
    }

    private static ListManagementOptions parseListManagementOptions(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new AwsException("BadRequestException", "ListManagementOptions must be an object.", 400);
        }
        JsonNode listNode = node.path("ContactListName");
        if (!listNode.isTextual() || listNode.textValue().isBlank()) {
            throw new AwsException("BadRequestException",
                    "ListManagementOptions.ContactListName is required.", 400);
        }
        JsonNode topicNode = node.path("TopicName");
        String topicName = null;
        if (!topicNode.isMissingNode() && !topicNode.isNull()) {
            if (!topicNode.isTextual()) {
                throw new AwsException("BadRequestException",
                        "ListManagementOptions.TopicName must be a string.", 400);
            }
            topicName = topicNode.textValue();
        }
        return new ListManagementOptions(listNode.textValue(), topicName);
    }

    /**
     * Parse a V2 SES {@code EmailTags} / {@code DefaultEmailTags} / {@code ReplacementTags}
     * array (per-message {@link MessageTag} list whose elements use {@code Name}/{@code Value},
     * distinct from the resource-tag {@link Tag} {@code Key}/{@code Value} shape). Note that
     * the per-entry name is {@code ReplacementTags} on the wire: only the top-level field
     * carries the {@code EmailTags} suffix. Returns an empty list when the node is absent so
     * callers can pass it through unconditionally.
     * The {@code fieldName} parameter is reported in the error message when the node is
     * present but not an array.
     */
    private List<MessageTag> parseEmailTagsArray(JsonNode tagsNode, String fieldName) {
        if (tagsNode.isMissingNode() || tagsNode.isNull()) {
            return List.of();
        }
        if (!tagsNode.isArray()) {
            throw new AwsException("BadRequestException", fieldName + " must be an array.", 400);
        }
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
