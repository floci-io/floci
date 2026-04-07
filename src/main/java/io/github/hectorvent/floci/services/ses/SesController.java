package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.Identity;
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

            Identity identity = emailIdentity.contains("@")
                    ? sesService.verifyEmailIdentity(emailIdentity, region)
                    : sesService.verifyDomainIdentity(emailIdentity, region);

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

            String fromEmailAddress = request.path("FromEmailAddress").asText(null);

            JsonNode destination = request.path("Destination");
            List<String> toAddresses = jsonArrayToList(destination.path("ToAddresses"));
            List<String> ccAddresses = jsonArrayToList(destination.path("CcAddresses"));
            List<String> bccAddresses = jsonArrayToList(destination.path("BccAddresses"));

            JsonNode content = request.path("Content");
            String messageId;

            if (content.has("Raw")) {
                String rawData = content.path("Raw").path("Data").asText(null);
                if (rawData == null || rawData.isBlank()) {
                    throw new AwsException("BadRequestException",
                            "Content.Raw.Data is required.", 400);
                }
                if (fromEmailAddress == null || fromEmailAddress.isBlank()) {
                    throw new AwsException("BadRequestException",
                            "FromEmailAddress is required.", 400);
                }
                List<String> allDestinations = mergeLists(toAddresses, ccAddresses, bccAddresses);
                if (allDestinations.isEmpty()) {
                    throw new AwsException("BadRequestException",
                            "At least one destination address is required.", 400);
                }
                messageId = sesService.sendRawEmail(fromEmailAddress, allDestinations, rawData, region);
            } else if (content.has("Simple")) {
                JsonNode simple = content.path("Simple");
                String subject = simple.path("Subject").path("Data").asText("");
                String bodyText = simple.path("Body").path("Text").path("Data").asText(null);
                String bodyHtml = simple.path("Body").path("Html").path("Data").asText(null);
                messageId = sesService.sendEmail(fromEmailAddress, toAddresses, ccAddresses,
                        bccAddresses, subject, bodyText, bodyHtml, region);
            } else if (content.has("Template")) {
                String subject = content.path("Template").path("TemplateName").asText("(template)");
                String templateData = content.path("Template").path("TemplateData").asText("");
                messageId = sesService.sendEmail(fromEmailAddress, toAddresses, ccAddresses,
                        bccAddresses, subject, templateData, null, region);
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

    // ──────────────────────────── Account ────────────────────────────

    @GET
    @Path("/account")
    public Response getAccount(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        long sentCount = sesService.getSentEmailCount(region);
        boolean sendingEnabled = sesService.isAccountSendingEnabled(region);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("DedicatedIpAutoWarmupEnabled", false);
        result.put("EnforcementStatus", "HEALTHY");
        result.put("ProductionAccessEnabled", true);
        result.put("SendingEnabled", sendingEnabled);

        ObjectNode sendQuota = result.putObject("SendQuota");
        sendQuota.put("Max24HourSend", 200.0);
        sendQuota.put("MaxSendRate", 1.0);
        sendQuota.put("SentLast24Hours", (double) sentCount);

        return Response.ok(result).build();
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
        mailFromAttributes.put("MailFromDomain", "");
        mailFromAttributes.put("MailFromDomainStatus", "NOT_STARTED");
        mailFromAttributes.put("BehaviorOnMxFailure", "USE_DEFAULT_VALUE");

        result.putObject("Policies");
        result.putArray("Tags");

        return result;
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

    private static AwsException remapV1Exception(AwsException e) {
        if ("InvalidParameterValue".equals(e.getErrorCode())) {
            return new AwsException("BadRequestException", e.getMessage(), 400);
        }
        return e;
    }
}
