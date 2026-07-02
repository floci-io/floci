package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.ArchivingOptions;
import io.github.hectorvent.floci.services.ses.model.DashboardOptions;
import io.github.hectorvent.floci.services.ses.model.GuardianOptions;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntry;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntryResult;
import io.github.hectorvent.floci.services.ses.model.ConfigurationSet;
import io.github.hectorvent.floci.services.ses.model.DedicatedIpPool;
import io.github.hectorvent.floci.services.ses.model.DeliveryOptions;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;
import io.github.hectorvent.floci.services.ses.model.EventDestination;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.MessageHeader;
import io.github.hectorvent.floci.services.ses.model.MessageTag;
import io.github.hectorvent.floci.services.ses.model.SuppressedDestination;
import io.github.hectorvent.floci.services.ses.model.SuppressionOptions;
import io.github.hectorvent.floci.services.ses.model.Tag;
import io.github.hectorvent.floci.services.ses.model.TrackingOptions;
import io.github.hectorvent.floci.services.ses.model.VdmOptions;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesController(SesService sesService, RegionResolver regionResolver,
                           ObjectMapper objectMapper) {
        this.sesService = sesService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── Identities ────────────────────────────

    @POST
    @Path("/identities")
    public Response createEmailIdentity(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String emailIdentity = request.path("EmailIdentity").asText(null);
            if (emailIdentity == null || emailIdentity.isBlank()) {
                throw new AwsException("BadRequestException", "EmailIdentity is required.", 400);
            }

            if (sesService.getIdentityVerificationAttributes(emailIdentity, region) != null) {
                throw new AwsException("AlreadyExistsException",
                        "Email identity " + emailIdentity + " already exist.", 400);
            }

            Identity identity = emailIdentity.contains("@")
                    ? sesService.verifyEmailIdentity(emailIdentity, region)
                    : sesService.verifyDomainIdentity(emailIdentity, region);

            List<Tag> parsedTags = parseTagsArray(request.path("Tags"));
            if (parsedTags != null) {
                sesService.setIdentityTags(emailIdentity, region, parsedTags);
            }

            ObjectNode result = objectMapper.createObjectNode();
            result.put("IdentityType", toV2IdentityType(identity.getIdentityType()));
            result.put("VerifiedForSendingStatus", true);
            result.set("DkimAttributes", buildDkimAttributes(identity));

            LOG.infov("SES V2 CreateEmailIdentity: {0}", emailIdentity);
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/identities")
    public Response listEmailIdentities(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<Identity> identities = sesService.listIdentities(null, region);

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode items = result.putArray("EmailIdentities");
        for (Identity id : identities) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("IdentityType", toV2IdentityType(id.getIdentityType()));
            item.put("IdentityName", id.getIdentity());
            item.put("SendingEnabled", true);
            items.add(item);
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/identities/{emailIdentity}")
    public Response getEmailIdentity(@Context HttpHeaders headers,
                                     @PathParam("emailIdentity") String emailIdentity) {
        String region = regionResolver.resolveRegion(headers);
        Identity identity = sesService.getIdentityVerificationAttributes(emailIdentity, region);
        if (identity == null) {
            throw new AwsException("NotFoundException",
                    "Identity " + emailIdentity + " does not exist.", 404);
        }
        return Response.ok(buildFullIdentityResponse(identity)).build();
    }

    @DELETE
    @Path("/identities/{emailIdentity}")
    public Response deleteEmailIdentity(@Context HttpHeaders headers,
                                        @PathParam("emailIdentity") String emailIdentity) {
        String region = regionResolver.resolveRegion(headers);
        if (sesService.getIdentityVerificationAttributes(emailIdentity, region) == null) {
            throw new AwsException("NotFoundException",
                    "Email identity " + emailIdentity + " does not exist.", 404);
        }
        sesService.deleteIdentity(emailIdentity, region);
        LOG.infov("SES V2 DeleteEmailIdentity: {0}", emailIdentity);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ──────────────────────── Identity DKIM ─────────────────────────

    @PUT
    @Path("/identities/{emailIdentity}/dkim")
    public Response putEmailIdentityDkimAttributes(@Context HttpHeaders headers,
                                                    @PathParam("emailIdentity") String emailIdentity,
                                                    String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            JsonNode signingEnabledNode = request.get("SigningEnabled");
            if (signingEnabledNode == null || !signingEnabledNode.isBoolean()) {
                throw new AwsException("BadRequestException",
                        "SigningEnabled must be present and must be a boolean", 400);
            }
            boolean signingEnabled = signingEnabledNode.booleanValue();
            sesService.setDkimAttributes(emailIdentity, signingEnabled, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // ────────────────── Identity MAIL FROM ──────────────────────────

    @PUT
    @Path("/identities/{emailIdentity}/mail-from")
    public Response putEmailIdentityMailFromAttributes(@Context HttpHeaders headers,
                                                        @PathParam("emailIdentity") String emailIdentity,
                                                        String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            JsonNode mailFromDomainNode = request.path("MailFromDomain");
            if (mailFromDomainNode.isMissingNode()) {
                throw new AwsException("BadRequestException",
                        "MailFromDomain is required (use an empty string to clear the existing setting).", 400);
            }
            if (!mailFromDomainNode.isNull() && !mailFromDomainNode.isTextual()) {
                throw new AwsException("BadRequestException",
                        "MailFromDomain must be a JSON string (or null).", 400);
            }
            String mailFromDomain = mailFromDomainNode.isNull()
                    ? ""
                    : mailFromDomainNode.asText("");
            JsonNode behaviorNode = request.path("BehaviorOnMxFailure");
            String behaviorV2 = null;
            if (!behaviorNode.isMissingNode() && !behaviorNode.isNull()) {
                if (!behaviorNode.isTextual()) {
                    throw new AwsException("BadRequestException",
                            "BehaviorOnMxFailure must be a JSON string.", 400);
                }
                behaviorV2 = behaviorNode.asText(null);
            }
            String behaviorV1 = v2BehaviorToV1(behaviorV2);
            sesService.setMailFromDomain(emailIdentity, mailFromDomain, behaviorV1, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // ──────────────────── Identity Feedback ─────────────────────────

    @PUT
    @Path("/identities/{emailIdentity}/feedback")
    public Response putEmailIdentityFeedbackAttributes(@Context HttpHeaders headers,
                                                        @PathParam("emailIdentity") String emailIdentity,
                                                        String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            JsonNode emailForwardingEnabledNode = request.get("EmailForwardingEnabled");
            if (emailForwardingEnabledNode == null || !emailForwardingEnabledNode.isBoolean()) {
                throw new AwsException("BadRequestException",
                        "EmailForwardingEnabled must be present and must be a boolean", 400);
            }
            boolean emailForwardingEnabled = emailForwardingEnabledNode.booleanValue();
            sesService.setFeedbackForwardingEnabled(emailIdentity, emailForwardingEnabled, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // ──────────────────────────── Send Email ────────────────────────────

    @POST
    @Path("/outbound-emails")
    public Response sendEmail(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (!sesService.isAccountSendingEnabled(region)) {
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
            List<String> allDestinations = mergeLists(toAddresses, ccAddresses, bccAddresses);
            String configurationSetName = request.path("ConfigurationSetName").asText(null);
            List<MessageTag> emailTags = parseEmailTagsArray(request.path("EmailTags"), "EmailTags");

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
                messageId = sesService.sendRawEmail(fromEmailAddress, allDestinations, rawData,
                        configurationSetName, emailTags, region);
            } else if (content.has("Simple")) {
                if (fromEmailAddress == null || fromEmailAddress.isBlank()) {
                    // AWS returns BadRequestException with a null message body here.
                    throw new AwsException("BadRequestException", null, 400);
                }
                JsonNode simple = content.path("Simple");
                String subject = simple.path("Subject").path("Data").asText("");
                String bodyText = simple.path("Body").path("Text").path("Data").asText(null);
                String bodyHtml = simple.path("Body").path("Html").path("Data").asText(null);
                List<MessageHeader> additionalHeaders = parseHeadersArray(simple.path("Headers"));
                messageId = sesService.sendEmail(fromEmailAddress, toAddresses, ccAddresses,
                        bccAddresses, replyToAddresses, subject, bodyText, bodyHtml,
                        configurationSetName, emailTags, additionalHeaders, region);
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
                List<MessageHeader> additionalHeaders = parseHeadersArray(template.path("Headers"));
                if (hasName || hasArn) {
                    String resolvedName = hasName
                            ? templateName
                            : SesService.templateNameFromArn(templateArn);
                    messageId = sesService.sendTemplatedEmail(fromEmailAddress, toAddresses, ccAddresses,
                            bccAddresses, replyToAddresses, resolvedName, templateData,
                            configurationSetName, emailTags, additionalHeaders, region);
                } else {
                    JsonNode inline = template.path("TemplateContent");
                    String subject = inline.path("Subject").asText(null);
                    String text = inline.path("Text").asText(null);
                    String html = inline.path("Html").asText(null);
                    messageId = sesService.sendInlineTemplatedEmail(fromEmailAddress, toAddresses,
                            ccAddresses, bccAddresses, replyToAddresses,
                            subject, text, html, templateData,
                            configurationSetName, emailTags, additionalHeaders, region);
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
            if (!sesService.isAccountSendingEnabled(region)) {
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
            String configurationSetName = request.path("ConfigurationSetName").asText(null);

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
            if (hasInline) {
                JsonNode inline = template.path("TemplateContent");
                subject = inline.path("Subject").asText(null);
                text = inline.path("Text").asText(null);
                html = inline.path("Html").asText(null);
            } else {
                String resolvedName = hasName
                        ? templateName
                        : SesService.templateNameFromArn(templateArn);
                EmailTemplate stored = sesService.getTemplate(resolvedName, region);
                subject = stored.getSubject();
                text = stored.getTextPart();
                html = stored.getHtmlPart();
            }

            JsonNode defaultTemplateData = parseTemplateData(template, "TemplateData");
            List<MessageTag> defaultEmailTags = parseEmailTagsArray(request.path("DefaultEmailTags"), "DefaultEmailTags");
            List<MessageHeader> defaultHeaders = parseHeadersArray(template.path("Headers"));

            JsonNode bulkEntries = request.path("BulkEmailEntries");
            if (!bulkEntries.isArray() || bulkEntries.isEmpty()) {
                throw new AwsException("BadRequestException",
                        "BulkEmailEntries must be a non-empty array.", 400);
            }

            List<BulkEmailEntry> entries = new ArrayList<>();
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
                List<MessageHeader> entryReplacementHeaders = parseHeadersArray(node.path("ReplacementHeaders"));
                entries.add(new BulkEmailEntry(to, cc, bcc, replacementData, replacementTags, entryReplacementHeaders));
            }

            List<BulkEmailEntryResult> results = sesService.sendBulkTemplatedEmail(fromEmailAddress,
                    replyToAddresses, subject, text, html,
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

    // ──────────────────────────── Templates ────────────────────────────

    @POST
    @Path("/templates")
    public Response createEmailTemplate(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String templateName = request.path("TemplateName").asText(null);
            if (templateName == null || templateName.isBlank()) {
                throw new AwsException("BadRequestException", "TemplateName is required.", 400);
            }
            EmailTemplate template = parseTemplateContent(templateName, request.path("TemplateContent"));
            List<Tag> parsedTags = parseTagsArray(request.path("Tags"));
            if (parsedTags != null) {
                template.setTags(parsedTags);
            }
            sesService.createTemplate(template, region);
            LOG.infov("SES V2 CreateEmailTemplate: {0}", templateName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/templates")
    public Response listEmailTemplates(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<EmailTemplate> templates = sesService.listTemplates(region);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode items = result.putArray("TemplatesMetadata");
        for (EmailTemplate t : templates) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("TemplateName", t.getTemplateName());
            if (t.getCreatedTimestamp() != null) {
                item.put("CreatedTimestamp", t.getCreatedTimestamp().getEpochSecond());
            }
            items.add(item);
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/templates/{templateName}")
    public Response getEmailTemplate(@Context HttpHeaders headers,
                                      @PathParam("templateName") String templateName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            EmailTemplate template = sesService.getTemplate(templateName, region);
            return Response.ok(buildTemplateResponse(template)).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/templates/{templateName}")
    public Response updateEmailTemplate(@Context HttpHeaders headers,
                                         @PathParam("templateName") String templateName,
                                         String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            EmailTemplate template = parseTemplateContent(templateName, request.path("TemplateContent"));
            sesService.updateTemplate(template, region);
            LOG.infov("SES V2 UpdateEmailTemplate: {0}", templateName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/templates/{templateName}")
    public Response deleteEmailTemplate(@Context HttpHeaders headers,
                                         @PathParam("templateName") String templateName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.deleteTemplate(templateName, region);
            LOG.infov("SES V2 DeleteEmailTemplate: {0}", templateName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @POST
    @Path("/templates/{templateName}/render")
    public Response testRenderEmailTemplate(@Context HttpHeaders headers,
                                             @PathParam("templateName") String templateName,
                                             String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            JsonNode templateDataNode = request.path("TemplateData");
            if (!templateDataNode.isMissingNode() && !templateDataNode.isNull()
                    && !templateDataNode.isTextual()) {
                throw new AwsException("BadRequestException",
                        "TemplateData must be a JSON-encoded string.", 400);
            }
            String templateDataRaw = templateDataNode.asText("");
            String rendered = sesService.renderTestTemplate(templateName, templateDataRaw, region);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("RenderedTemplate", rendered);
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // ──────────────────────── Configuration Sets ───────────────────────

    @POST
    @Path("/configuration-sets")
    public Response createConfigurationSet(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String name = request.path("ConfigurationSetName").asText(null);
            if (name == null || name.isBlank()) {
                throw new AwsException("BadRequestException", "ConfigurationSetName is required.", 400);
            }
            ConfigurationSet cs = new ConfigurationSet(name);
            List<Tag> parsedTags = parseTagsArray(request.path("Tags"));
            if (parsedTags != null) {
                cs.setTags(parsedTags);
            }
            JsonNode suppressionNode = request.path("SuppressionOptions");
            if (!suppressionNode.isMissingNode() && !suppressionNode.isNull()) {
                if (!suppressionNode.isObject()) {
                    throw new AwsException("SerializationException", "Expected null", 400);
                }
                JsonNode reasonsNode = suppressionNode.path("SuppressedReasons");
                if (reasonsNode.isMissingNode() || reasonsNode.isNull()) {
                    throw new AwsException("InternalFailure",
                            "An internal failure has occurred.", 500);
                }
                SuppressionOptions options = new SuppressionOptions();
                options.setSuppressedReasons(parseSuppressedReasons(reasonsNode));
                cs.setSuppressionOptions(options);
            }
            JsonNode sendingNode = request.path("SendingOptions");
            if (!sendingNode.isMissingNode() && !sendingNode.isNull()) {
                if (!sendingNode.isObject()) {
                    throw new AwsException("SerializationException", "Expected null", 400);
                }
                cs.setSendingEnabled(parseSendingEnabled(sendingNode.path("SendingEnabled")));
            }
            JsonNode reputationNode = request.path("ReputationOptions");
            if (!reputationNode.isMissingNode() && !reputationNode.isNull()) {
                requireOptionObject(reputationNode);
                Boolean rme = parseReputationMetricsEnabled(reputationNode.path("ReputationMetricsEnabled"));
                if (rme != null) {
                    cs.setReputationMetricsEnabled(rme);
                }
            }
            JsonNode trackingNode = request.path("TrackingOptions");
            if (!trackingNode.isMissingNode() && !trackingNode.isNull()) {
                cs.setTrackingOptions(parseTrackingOptions(trackingNode));
            }
            JsonNode deliveryNode = request.path("DeliveryOptions");
            if (!deliveryNode.isMissingNode() && !deliveryNode.isNull()) {
                cs.setDeliveryOptions(parseDeliveryOptions(deliveryNode));
            }
            JsonNode archivingNode = request.path("ArchivingOptions");
            if (!archivingNode.isMissingNode() && !archivingNode.isNull()) {
                cs.setArchivingOptions(parseArchivingOptions(archivingNode));
            }
            JsonNode vdmNode = request.path("VdmOptions");
            if (!vdmNode.isMissingNode() && !vdmNode.isNull()) {
                cs.setVdmOptions(parseVdmOptions(vdmNode));
            }
            sesService.createConfigurationSet(cs, region);
            LOG.infov("SES V2 CreateConfigurationSet: {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/configuration-sets")
    public Response listConfigurationSets(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<ConfigurationSet> all = sesService.listConfigurationSets(region);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode arr = result.putArray("ConfigurationSets");
        for (ConfigurationSet cs : all) {
            arr.add(cs.getName());
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/configuration-sets/{configurationSetName}")
    public Response getConfigurationSet(@Context HttpHeaders headers,
                                         @PathParam("configurationSetName") String name) {
        String region = regionResolver.resolveRegion(headers);
        try {
            ConfigurationSet cs = sesService.getConfigurationSet(name, region);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("ConfigurationSetName", cs.getName());
            ArrayNode tags = result.putArray("Tags");
            for (Tag t : cs.getTags()) {
                ObjectNode tagNode = objectMapper.createObjectNode();
                tagNode.put("Key", t.key());
                tagNode.put("Value", t.value());
                tags.add(tagNode);
            }
            if (cs.getSuppressionOptions() != null) {
                ObjectNode suppressionNode = result.putObject("SuppressionOptions");
                ArrayNode reasons = suppressionNode.putArray("SuppressedReasons");
                for (String r : cs.getSuppressionOptions().getSuppressedReasons()) {
                    reasons.add(r);
                }
            }
            ObjectNode sendingNode = result.putObject("SendingOptions");
            sendingNode.put("SendingEnabled", cs.isSendingEnabledEffective());
            // AWS always returns ReputationOptions (true by default), like SendingOptions.
            ObjectNode reputationNode = result.putObject("ReputationOptions");
            reputationNode.put("ReputationMetricsEnabled", cs.isReputationMetricsEnabledEffective());
            // The option models carry @JsonProperty/@JsonInclude(NON_NULL), so let
            // Jackson shape the response and omit unset members.
            if (cs.getTrackingOptions() != null) {
                result.set("TrackingOptions", objectMapper.valueToTree(cs.getTrackingOptions()));
            }
            if (cs.getDeliveryOptions() != null) {
                result.set("DeliveryOptions", objectMapper.valueToTree(cs.getDeliveryOptions()));
            }
            if (cs.getArchivingOptions() != null) {
                result.set("ArchivingOptions", objectMapper.valueToTree(cs.getArchivingOptions()));
            }
            if (cs.getVdmOptions() != null) {
                result.set("VdmOptions", objectMapper.valueToTree(cs.getVdmOptions()));
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/suppression-options")
    public Response putConfigurationSetSuppressionOptions(@Context HttpHeaders headers,
                                                          @PathParam("configurationSetName") String name,
                                                          String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            List<String> reasons = parseSuppressedReasons(request.path("SuppressedReasons"));
            sesService.putConfigurationSetSuppressionOptions(name, reasons, region);
            LOG.infov("SES V2 PutConfigurationSetSuppressionOptions: {0} on {1}", reasons, name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/sending")
    public Response putConfigurationSetSendingOptions(@Context HttpHeaders headers,
                                                       @PathParam("configurationSetName") String name,
                                                       String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            // Reuse the AWS-aligned SendingEnabled deserialization shared with CreateConfigurationSet:
            // absent -> false, string -> true, null/number -> SerializationException. An empty body
            // / {} therefore disables sending (200). Verified against real AWS.
            boolean enabled = parseSendingEnabled(readOptionBody(body).path("SendingEnabled"));
            sesService.setConfigurationSetSendingEnabled(name, enabled, region);
            LOG.infov("SES V2 PutConfigurationSetSendingOptions: {0} on {1}", enabled, name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/reputation-options")
    public Response putConfigurationSetReputationOptions(@Context HttpHeaders headers,
                                                         @PathParam("configurationSetName") String name,
                                                         String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(body);
            Boolean enabled = parseReputationMetricsEnabled(request.path("ReputationMetricsEnabled"));
            boolean effectiveEnabled = enabled != null && enabled;
            sesService.setConfigurationSetReputationOptions(name, effectiveEnabled, region);
            LOG.infov("SES V2 PutConfigurationSetReputationOptions: {0} on {1}", effectiveEnabled, name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/tracking-options")
    public Response putConfigurationSetTrackingOptions(@Context HttpHeaders headers,
                                                       @PathParam("configurationSetName") String name,
                                                       String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(body);
            sesService.setConfigurationSetTrackingOptions(name, parseTrackingOptions(request), region);
            LOG.infov("SES V2 PutConfigurationSetTrackingOptions on {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/delivery-options")
    public Response putConfigurationSetDeliveryOptions(@Context HttpHeaders headers,
                                                       @PathParam("configurationSetName") String name,
                                                       String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(body);
            sesService.setConfigurationSetDeliveryOptions(name, parseDeliveryOptions(request), region);
            LOG.infov("SES V2 PutConfigurationSetDeliveryOptions on {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/archiving-options")
    public Response putConfigurationSetArchivingOptions(@Context HttpHeaders headers,
                                                        @PathParam("configurationSetName") String name,
                                                        String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(body);
            sesService.setConfigurationSetArchivingOptions(name, parseArchivingOptions(request), region);
            LOG.infov("SES V2 PutConfigurationSetArchivingOptions on {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/vdm-options")
    public Response putConfigurationSetVdmOptions(@Context HttpHeaders headers,
                                                  @PathParam("configurationSetName") String name,
                                                  String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(body);
            JsonNode vdmNode = request.path("VdmOptions");
            VdmOptions options = (vdmNode.isMissingNode() || vdmNode.isNull())
                    ? null : parseVdmOptions(vdmNode);
            sesService.setConfigurationSetVdmOptions(name, options, region);
            LOG.infov("SES V2 PutConfigurationSetVdmOptions on {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    /** Reject a non-object option block, mirroring the AWS deserialization layer. */
    private static void requireOptionObject(JsonNode node) {
        if (!node.isObject()) {
            throw new AwsException("SerializationException", "Expected null", 400);
        }
    }

    /** Parse a configuration-set option PUT body into a JSON object, treating an empty body as {}. */
    private JsonNode readOptionBody(String body) {
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            return request;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    private static Boolean parseReputationMetricsEnabled(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw new AwsException("BadRequestException",
                    "ReputationMetricsEnabled must be a boolean.", 400);
        }
        return node.booleanValue();
    }

    /** Read an optional string member, rejecting a non-string value the way the AWS deserialization layer does. */
    private static String parseOptionString(JsonNode node, String field) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new AwsException("BadRequestException", field + " must be a JSON string.", 400);
        }
        return node.asText();
    }

    private static TrackingOptions parseTrackingOptions(JsonNode node) {
        requireOptionObject(node);
        TrackingOptions t = new TrackingOptions();
        t.setCustomRedirectDomain(parseOptionString(node.path("CustomRedirectDomain"), "CustomRedirectDomain"));
        t.setHttpsPolicy(parseOptionString(node.path("HttpsPolicy"), "HttpsPolicy"));
        // An all-null block (e.g. an empty PUT body) clears the options rather
        // than persisting an empty object that GetConfigurationSet would echo.
        if (t.getCustomRedirectDomain() == null && t.getHttpsPolicy() == null) {
            return null;
        }
        return t;
    }

    private static DeliveryOptions parseDeliveryOptions(JsonNode node) {
        requireOptionObject(node);
        DeliveryOptions d = new DeliveryOptions();
        d.setTlsPolicy(parseOptionString(node.path("TlsPolicy"), "TlsPolicy"));
        d.setSendingPoolName(parseOptionString(node.path("SendingPoolName"), "SendingPoolName"));
        JsonNode max = node.path("MaxDeliverySeconds");
        if (!max.isMissingNode() && !max.isNull()) {
            if (!max.isNumber()) {
                throw new AwsException("BadRequestException",
                        "MaxDeliverySeconds must be a number.", 400);
            }
            if (!max.isIntegralNumber()) {
                throw new AwsException("BadRequestException",
                        "MaxDeliverySeconds must be an integer.", 400);
            }
            d.setMaxDeliverySeconds(max.asLong());
        }
        if (d.getTlsPolicy() == null && d.getSendingPoolName() == null && d.getMaxDeliverySeconds() == null) {
            return null;
        }
        return d;
    }

    private static ArchivingOptions parseArchivingOptions(JsonNode node) {
        requireOptionObject(node);
        ArchivingOptions a = new ArchivingOptions();
        a.setArchiveArn(parseOptionString(node.path("ArchiveArn"), "ArchiveArn"));
        if (a.getArchiveArn() == null) {
            return null;
        }
        return a;
    }

    private static VdmOptions parseVdmOptions(JsonNode node) {
        requireOptionObject(node);
        VdmOptions v = new VdmOptions();
        JsonNode dashboard = node.path("DashboardOptions");
        if (!dashboard.isMissingNode() && !dashboard.isNull()) {
            requireOptionObject(dashboard);
            String engagementMetrics = parseOptionString(dashboard.path("EngagementMetrics"), "EngagementMetrics");
            if (engagementMetrics != null) {
                DashboardOptions d = new DashboardOptions();
                d.setEngagementMetrics(engagementMetrics);
                v.setDashboardOptions(d);
            }
        }
        JsonNode guardian = node.path("GuardianOptions");
        if (!guardian.isMissingNode() && !guardian.isNull()) {
            requireOptionObject(guardian);
            String optimized = parseOptionString(guardian.path("OptimizedSharedDelivery"), "OptimizedSharedDelivery");
            if (optimized != null) {
                GuardianOptions g = new GuardianOptions();
                g.setOptimizedSharedDelivery(optimized);
                v.setGuardianOptions(g);
            }
        }
        // An all-null block (e.g. an empty PUT body) clears the options rather
        // than persisting an empty object that GetConfigurationSet would echo.
        if (v.getDashboardOptions() == null && v.getGuardianOptions() == null) {
            return null;
        }
        return v;
    }

    @DELETE
    @Path("/configuration-sets/{configurationSetName}")
    public Response deleteConfigurationSet(@Context HttpHeaders headers,
                                            @PathParam("configurationSetName") String name) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.deleteConfigurationSet(name, region);
            LOG.infov("SES V2 DeleteConfigurationSet: {0}", name);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    // ──────────────── Configuration Set Event Destinations ────────────────

    @POST
    @Path("/configuration-sets/{configurationSetName}/event-destinations")
    public Response createConfigurationSetEventDestination(@Context HttpHeaders headers,
                                                           @PathParam("configurationSetName") String configurationSetName,
                                                           String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            String edName = request.path("EventDestinationName").asText(null);
            if (edName == null || edName.isBlank()) {
                throw new AwsException("BadRequestException", "EventDestinationName is required.", 400);
            }
            JsonNode edNode = request.path("EventDestination");
            if (!edNode.isObject()) {
                throw new AwsException("BadRequestException", "EventDestination is required.", 400);
            }
            EventDestination dest = objectMapper.treeToValue(edNode, EventDestination.class);
            sesService.createConfigurationSetEventDestination(configurationSetName, edName, dest, region);
            LOG.infov("SES V2 CreateConfigurationSetEventDestination: {0} on {1}", edName, configurationSetName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/configuration-sets/{configurationSetName}/event-destinations")
    public Response getConfigurationSetEventDestinations(@Context HttpHeaders headers,
                                                         @PathParam("configurationSetName") String configurationSetName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            List<EventDestination> dests =
                    sesService.getConfigurationSetEventDestinations(configurationSetName, region);
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode arr = result.putArray("EventDestinations");
            for (EventDestination ed : dests) {
                arr.add(objectMapper.valueToTree(ed));
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @PUT
    @Path("/configuration-sets/{configurationSetName}/event-destinations/{eventDestinationName}")
    public Response updateConfigurationSetEventDestination(@Context HttpHeaders headers,
                                                           @PathParam("configurationSetName") String configurationSetName,
                                                           @PathParam("eventDestinationName") String eventDestinationName,
                                                           String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            JsonNode edNode = request.path("EventDestination");
            if (!edNode.isObject()) {
                throw new AwsException("BadRequestException", "EventDestination is required.", 400);
            }
            EventDestination dest = objectMapper.treeToValue(edNode, EventDestination.class);
            sesService.updateConfigurationSetEventDestination(configurationSetName, eventDestinationName, dest, region);
            LOG.infov("SES V2 UpdateConfigurationSetEventDestination: {0} on {1}",
                    eventDestinationName, configurationSetName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/configuration-sets/{configurationSetName}/event-destinations/{eventDestinationName}")
    public Response deleteConfigurationSetEventDestination(@Context HttpHeaders headers,
                                                           @PathParam("configurationSetName") String configurationSetName,
                                                           @PathParam("eventDestinationName") String eventDestinationName) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.deleteConfigurationSetEventDestination(configurationSetName, eventDestinationName, region);
            LOG.infov("SES V2 DeleteConfigurationSetEventDestination: {0} on {1}",
                    eventDestinationName, configurationSetName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    // ──────────────────────── Dedicated IP Pools ────────────────────────

    @POST
    @Path("/dedicated-ip-pools")
    public Response createDedicatedIpPool(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            String poolName = readRequiredStringField(request, "PoolName");
            JsonNode scalingNode = request.path("ScalingMode");
            String scalingMode;
            if (scalingNode.isMissingNode() || scalingNode.isNull()) {
                scalingMode = null;
            } else if (!scalingNode.isTextual()) {
                throw new AwsException("BadRequestException",
                        "The ScalingMode parameter is invalid.", 400);
            } else {
                scalingMode = scalingNode.asText();
            }
            sesService.createDedicatedIpPool(poolName, scalingMode, region);
            LOG.infov("SES V2 CreateDedicatedIpPool: {0}", poolName);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/dedicated-ip-pools")
    public Response listDedicatedIpPools(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode pools = result.putArray("DedicatedIpPools");
        sesService.listDedicatedIpPools(region).forEach(pools::add);
        return Response.ok(result).build();
    }

    @GET
    @Path("/dedicated-ip-pools/{poolName}")
    public Response getDedicatedIpPool(@Context HttpHeaders headers,
                                       @PathParam("poolName") String poolName) {
        String region = regionResolver.resolveRegion(headers);
        DedicatedIpPool pool = sesService.getDedicatedIpPool(poolName, region);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("DedicatedIpPool", objectMapper.valueToTree(pool));
        return Response.ok(result).build();
    }

    @DELETE
    @Path("/dedicated-ip-pools/{poolName}")
    public Response deleteDedicatedIpPool(@Context HttpHeaders headers,
                                          @PathParam("poolName") String poolName) {
        String region = regionResolver.resolveRegion(headers);
        sesService.deleteDedicatedIpPool(poolName, region);
        LOG.infov("SES V2 DeleteDedicatedIpPool: {0}", poolName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ──────────────────────────── Account ────────────────────────────

    @GET
    @Path("/account")
    public Response getAccount(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        long sentCount = sesService.getSentEmailCount(region);
        boolean sendingEnabled = sesService.isAccountSendingEnabled(region);
        AccountSuppressionAttributes suppression = sesService.getAccountSuppressionAttributes(region);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("DedicatedIpAutoWarmupEnabled", false);
        result.put("EnforcementStatus", "HEALTHY");
        result.put("ProductionAccessEnabled", true);
        result.put("SendingEnabled", sendingEnabled);

        ObjectNode sendQuota = result.putObject("SendQuota");
        sendQuota.put("Max24HourSend", 200.0);
        sendQuota.put("MaxSendRate", 1.0);
        sendQuota.put("SentLast24Hours", (double) sentCount);

        ObjectNode suppressionAttrs = result.putObject("SuppressionAttributes");
        ArrayNode reasons = suppressionAttrs.putArray("SuppressedReasons");
        for (String r : suppression.getSuppressedReasons()) {
            reasons.add(r);
        }

        return Response.ok(result).build();
    }

    @PUT
    @Path("/account/suppression")
    public Response putAccountSuppressionAttributes(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            JsonNode reasonsNode = request.path("SuppressedReasons");
            List<String> reasons = new ArrayList<>();
            if (!reasonsNode.isMissingNode() && !reasonsNode.isNull()) {
                if (!reasonsNode.isArray()) {
                    throw new AwsException("BadRequestException", "SuppressedReasons must be an array.", 400);
                }
                for (JsonNode r : reasonsNode) {
                    if (r.isNull() || !r.isTextual()) {
                        throw new AwsException("BadRequestException",
                                "SuppressedReasons entries must be strings.", 400);
                    }
                    reasons.add(r.asText());
                }
            }
            sesService.putAccountSuppressionAttributes(region, reasons);
            LOG.infov("SES V2 PutAccountSuppressionAttributes: {0}", reasons);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @PUT
    @Path("/account/sending")
    public Response putAccountSendingAttributes(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            JsonNode sendingEnabledNode = request.get("SendingEnabled");
            if (sendingEnabledNode == null || !sendingEnabledNode.isBoolean()) {
                throw new AwsException("BadRequestException",
                        "SendingEnabled must be present and must be a boolean", 400);
            }
            sesService.setAccountSendingEnabled(region, sendingEnabledNode.booleanValue());
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    // ──────────────────── Suppression list ───────────────────────────

    @PUT
    @Path("/suppression/addresses")
    public Response putSuppressedDestination(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            requireJsonObject(request);
            String emailAddress = readRequiredStringField(request, "EmailAddress");
            String reason = readRequiredStringField(request, "Reason");
            sesService.putSuppressedDestination(region, emailAddress, reason);
            LOG.infov("SES V2 PutSuppressedDestination: {0} ({1})", emailAddress, reason);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    private static String readRequiredStringField(JsonNode request, String fieldName) {
        JsonNode node = request.path(fieldName);
        if (node.isMissingNode() || node.isNull() || !node.isTextual()) {
            throw new AwsException("BadRequestException", fieldName + " is required.", 400);
        }
        return node.asText();
    }

    @GET
    @Path("/suppression/addresses/{emailAddress}")
    public Response getSuppressedDestination(@Context HttpHeaders headers,
                                              @PathParam("emailAddress") String emailAddress) {
        String region = regionResolver.resolveRegion(headers);
        try {
            SuppressedDestination suppressed = sesService.getSuppressedDestination(region, emailAddress);
            ObjectNode result = objectMapper.createObjectNode();
            ObjectNode entry = result.putObject("SuppressedDestination");
            entry.put("EmailAddress", suppressed.getEmailAddress());
            entry.put("Reason", suppressed.getReason());
            if (suppressed.getLastUpdateTime() != null) {
                entry.put("LastUpdateTime", suppressed.getLastUpdateTime().getEpochSecond());
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @DELETE
    @Path("/suppression/addresses/{emailAddress}")
    public Response deleteSuppressedDestination(@Context HttpHeaders headers,
                                                 @PathParam("emailAddress") String emailAddress) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.deleteSuppressedDestination(region, emailAddress);
            LOG.infov("SES V2 DeleteSuppressedDestination: {0}", emailAddress);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @GET
    @Path("/suppression/addresses")
    public Response listSuppressedDestinations(@Context HttpHeaders headers,
                                                @QueryParam("Reason") List<String> reasons) {
        String region = regionResolver.resolveRegion(headers);
        List<SuppressedDestination> entries;
        try {
            entries = sesService.listSuppressedDestinations(region, reasons);
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode summaries = result.putArray("SuppressedDestinationSummaries");
        for (SuppressedDestination s : entries) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("EmailAddress", s.getEmailAddress());
            item.put("Reason", s.getReason());
            if (s.getLastUpdateTime() != null) {
                item.put("LastUpdateTime", s.getLastUpdateTime().getEpochSecond());
            }
            summaries.add(item);
        }
        return Response.ok(result).build();
    }

    // ──────────────────────────── Tags ───────────────────────────────

    @POST
    @Path("/tags")
    public Response tagResource(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (body == null || body.isBlank()) {
                throw new AwsException("BadRequestException", "Request body is required.", 400);
            }
            JsonNode request = objectMapper.readTree(body);
            String arn = request.path("ResourceArn").asText(null);
            if (arn == null || arn.isBlank()) {
                throw new AwsException("BadRequestException", "ResourceArn is required.", 400);
            }
            List<Tag> tags = parseTagsArray(request.path("Tags"));
            if (tags == null) {
                throw new AwsException("BadRequestException", "Tags must be an array.", 400);
            }
            sesService.tagResource(arn, region, tags);
            LOG.infov("SES V2 TagResource: {0}", arn);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/tags")
    public Response untagResource(@Context HttpHeaders headers,
                                   @QueryParam("ResourceArn") String arn,
                                   @QueryParam("TagKeys") List<String> tagKeys) {
        String region = regionResolver.resolveRegion(headers);
        try {
            sesService.untagResource(arn, region, tagKeys);
            LOG.infov("SES V2 UntagResource: {0}", arn);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    @GET
    @Path("/tags")
    public Response listTagsForResource(@Context HttpHeaders headers,
                                         @QueryParam("ResourceArn") String arn) {
        String region = regionResolver.resolveRegion(headers);
        try {
            List<Tag> tags = sesService.listResourceTags(arn, region);
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode arr = result.putArray("Tags");
            for (Tag t : tags) {
                ObjectNode tagNode = objectMapper.createObjectNode();
                tagNode.put("Key", t.key());
                tagNode.put("Value", t.value());
                arr.add(tagNode);
            }
            return Response.ok(result).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode buildFullIdentityResponse(Identity identity) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("IdentityType", toV2IdentityType(identity.getIdentityType()));
        result.put("VerifiedForSendingStatus",
                "Success".equals(identity.getVerificationStatus()));
        result.put("VerificationStatus", toV2Status(identity.getVerificationStatus()));
        result.put("FeedbackForwardingStatus", identity.isFeedbackForwardingEnabled());

        result.set("DkimAttributes", buildDkimAttributes(identity));

        ObjectNode mailFromAttributes = result.putObject("MailFromAttributes");
        mailFromAttributes.put("BehaviorOnMxFailure", v1BehaviorToV2(identity.getBehaviorOnMxFailure()));
        String mailFromDomain = identity.getMailFromDomain();
        if (mailFromDomain != null && !mailFromDomain.isEmpty()) {
            mailFromAttributes.put("MailFromDomain", mailFromDomain);
            mailFromAttributes.put("MailFromDomainStatus", toV2Status(identity.getMailFromDomainStatus()));
        }

        result.putObject("Policies");
        ArrayNode tags = result.putArray("Tags");
        for (Tag t : identity.getTags()) {
            ObjectNode tagNode = objectMapper.createObjectNode();
            tagNode.put("Key", t.key());
            tagNode.put("Value", t.value());
            tags.add(tagNode);
        }

        return result;
    }

    private static String v1BehaviorToV2(String v1) {
        if ("RejectMessage".equals(v1)) {
            return "REJECT_MESSAGE";
        }
        return "USE_DEFAULT_VALUE";
    }

    private static String v2BehaviorToV1(String v2) {
        if (v2 == null) {
            return null;
        }
        if ("REJECT_MESSAGE".equals(v2)) {
            return "RejectMessage";
        }
        if ("USE_DEFAULT_VALUE".equals(v2)) {
            return "UseDefaultValue";
        }
        throw new AwsException("BadRequestException",
                "1 validation error detected: Value at 'behaviorOnMxFailure' failed to satisfy "
                        + "constraint: Member must satisfy enum value set: [REJECT_MESSAGE, USE_DEFAULT_VALUE]", 400);
    }

    private ObjectNode buildDkimAttributes(Identity identity) {
        ObjectNode dkim = objectMapper.createObjectNode();
        dkim.put("SigningEnabled", identity.isDkimEnabled());
        dkim.put("Status", toV2Status(identity.getDkimVerificationStatus()));
        dkim.putArray("Tokens");
        return dkim;
    }

    private static String toV2IdentityType(String v1Type) {
        return "EmailAddress".equals(v1Type) ? "EMAIL_ADDRESS" : "DOMAIN";
    }

    private static String toV2Status(String v1Status) {
        if (v1Status == null) return null;
        return switch (v1Status) {
            case "Success" -> "SUCCESS";
            case "NotStarted" -> "NOT_STARTED";
            case "Pending" -> "PENDING";
            case "Failed" -> "FAILED";
            case "TemporaryFailure" -> "TEMPORARY_FAILURE";
            default -> v1Status;
        };
    }

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

    private EmailTemplate parseTemplateContent(String templateName, JsonNode content) {
        String subject = content.path("Subject").asText(null);
        String text = content.path("Text").asText(null);
        String html = content.path("Html").asText(null);
        return new EmailTemplate(templateName, subject, text, html);
    }

    private ObjectNode buildTemplateResponse(EmailTemplate template) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("TemplateName", template.getTemplateName());
        ObjectNode content = result.putObject("TemplateContent");
        if (template.getSubject() != null) {
            content.put("Subject", template.getSubject());
        }
        if (template.getTextPart() != null) {
            content.put("Text", template.getTextPart());
        }
        if (template.getHtmlPart() != null) {
            content.put("Html", template.getHtmlPart());
        }
        ArrayNode tags = result.putArray("Tags");
        for (Tag t : template.getTags()) {
            ObjectNode tagNode = objectMapper.createObjectNode();
            tagNode.put("Key", t.key());
            tagNode.put("Value", t.value());
            tags.add(tagNode);
        }
        return result;
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

    private static void requireJsonObject(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new AwsException("BadRequestException",
                    "Request body must be a JSON object.", 400);
        }
    }

    private static JsonNode requireObjectOrAbsent(JsonNode parent, String fieldName) {
        JsonNode child = parent.path(fieldName);
        if (!child.isMissingNode() && !child.isNull() && !child.isObject()) {
            throw new AwsException("BadRequestException",
                    fieldName + " must be a JSON object.", 400);
        }
        return child;
    }

    /**
     * Parse a JSON {@code Tags} array node into a list of tag records. Returns {@code null}
     * when the node is missing or null so callers can decide whether that is an error
     * (TagResource) or a no-op (CreateConfigurationSet / CreateEmailTemplate). Throws
     * {@code BadRequestException} when the node is present but not an array.
     */
    private List<Tag> parseTagsArray(JsonNode tagsNode) {
        if (tagsNode.isMissingNode() || tagsNode.isNull()) {
            return null;
        }
        if (!tagsNode.isArray()) {
            throw new AwsException("BadRequestException", "Tags must be an array.", 400);
        }
        List<Tag> out = new ArrayList<>();
        for (JsonNode t : tagsNode) {
            out.add(new Tag(
                    t.path("Key").asText(null),
                    t.path("Value").asText(null)));
        }
        return out;
    }

    /**
     * Parses a {@code SuppressedReasons} JSON array into a list, validating
     * structure only; reason values are validated by the service layer.
     * Structural violations reproduce the AWS deserialization-layer errors
     * (verified against real AWS SES V2 on 2026-06-13): a non-array node and
     * non-string scalar / container elements fail with
     * {@code SerializationException}, while {@code null} elements pass
     * deserialization and are rejected by the service-layer value validation,
     * exactly as AWS does. Missing / null yields an empty list for the PUT
     * path, which AWS treats as an explicit empty override.
     */
    private static List<String> parseSuppressedReasons(JsonNode reasonsNode) {
        List<String> reasons = new ArrayList<>();
        if (!reasonsNode.isMissingNode() && !reasonsNode.isNull()) {
            if (!reasonsNode.isArray()) {
                throw new AwsException("SerializationException", "Expected list or null", 400);
            }
            for (JsonNode r : reasonsNode) {
                if (r.isTextual() || r.isNull()) {
                    reasons.add(r.asText(null));
                } else if (r.isNumber()) {
                    throw new AwsException("SerializationException",
                            "NUMBER_VALUE can not be converted to a String", 400);
                } else if (r.isBoolean()) {
                    throw new AwsException("SerializationException",
                            (r.booleanValue() ? "TRUE_VALUE" : "FALSE_VALUE")
                                    + " can not be converted to a String", 400);
                } else {
                    throw unexpectedStartError(r);
                }
            }
        }
        return reasons;
    }

    /**
     * Reproduces the AWS deserialization behavior for {@code SendingEnabled}
     * (verified against real AWS SES V2 on 2026-06-13): a missing member
     * defaults to {@code false}, any string coerces to {@code true}, and
     * explicit {@code null} or non-boolean scalars fail with
     * {@code SerializationException}.
     */
    private static boolean parseSendingEnabled(JsonNode enabledNode) {
        if (enabledNode.isMissingNode()) {
            return false;
        }
        if (enabledNode.isBoolean()) {
            return enabledNode.booleanValue();
        }
        if (enabledNode.isTextual()) {
            return true;
        }
        if (enabledNode.isNull()) {
            throw new AwsException("SerializationException", null, 400);
        }
        if (enabledNode.isNumber()) {
            throw new AwsException("SerializationException",
                    "NUMBER_VALUE can not be converted to a Boolean", 400);
        }
        throw unexpectedStartError(enabledNode);
    }

    private static AwsException unexpectedStartError(JsonNode node) {
        if (node.isArray()) {
            return new AwsException("SerializationException",
                    "Start of list found where not expected", 400);
        }
        return new AwsException("SerializationException",
                "Start of structure or map found where not expected.", 400);
    }

    /**
     * Parse a V2 SES {@code Content.Simple.Headers} / {@code Content.Template.Headers} array
     * (additional message headers, elements use {@code Name}/{@code Value}). Returns an empty
     * list when the node is absent so callers can pass it through unconditionally.
     */
    private List<MessageHeader> parseHeadersArray(JsonNode headersNode) {
        if (headersNode.isMissingNode() || headersNode.isNull()) {
            return List.of();
        }
        if (!headersNode.isArray()) {
            throw new AwsException("BadRequestException", "Headers must be an array.", 400);
        }
        List<MessageHeader> out = new ArrayList<>();
        for (JsonNode h : headersNode) {
            if (!h.isObject()) {
                throw new AwsException("BadRequestException",
                        "Headers entries must be JSON objects.", 400);
            }
            String name = h.path("Name").asText(null);
            String value = h.path("Value").asText(null);
            if (name == null || name.isBlank()) {
                throw new AwsException("BadRequestException",
                        "The header name must be specified.", 400);
            }
            out.add(new MessageHeader(name, value));
        }
        return out;
    }

    /**
     * Parse a V2 SES {@code EmailTags} / {@code DefaultEmailTags} / {@code ReplacementTags}
     * array (per-message {@link MessageTag} list whose elements use {@code Name}/{@code Value},
     * distinct from the resource-tag {@link Tag} {@code Key}/{@code Value} shape). Note that
     * the per-entry name is {@code ReplacementTags} on the wire — only the top-level field
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

    private static AwsException remapV1Exception(AwsException e) {
        return switch (e.getErrorCode()) {
            case "InvalidParameterValue", "InvalidTemplate",
                 "InvalidRenderingParameter", "MissingRenderingAttribute" ->
                    new AwsException("BadRequestException", e.getMessage(), 400);
            case "TemplateDoesNotExist", "ConfigurationSetDoesNotExist" ->
                    new AwsException("NotFoundException", e.getMessage(), 404);
            case "AlreadyExists", "ConfigurationSetAlreadyExists" ->
                    new AwsException("AlreadyExistsException", e.getMessage(), 400);
            case "ConfigurationSetSendingPausedException" ->
                    new AwsException("SendingPausedException", e.getMessage(), 400);
            default -> e;
        };
    }
}
