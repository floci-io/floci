package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.Tag;
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

import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.ses.SesV2Json.parseTagsArray;
import static io.github.hectorvent.floci.services.ses.SesV2Json.putTimestamp;
import static io.github.hectorvent.floci.services.ses.SesV2Json.remapV1Exception;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireJsonObject;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireObjectOrAbsent;

/**
 * SES V2 email-identity endpoints ({@code /v2/email/identities}), including the sending
 * authorization policies, DKIM, MAIL FROM, feedback and default configuration set.
 * Reads and the single-domain attribute writes call
 * {@link SesIdentityService} directly; create, delete, the policy operations and the default
 * configuration set go through the {@link SesService} facade, which checks the configuration set
 * exists, guards the delete against tenant associations and cascades the identity's policies.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesIdentityController {

    private static final Logger LOG = Logger.getLogger(SesIdentityController.class);

    private final SesIdentityService identityService;
    private final SesService sesService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesIdentityController(SesIdentityService identityService, SesService sesService,
                                 RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.identityService = identityService;
        this.sesService = sesService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/identities")
    public Response createEmailIdentity(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = objectMapper.readTree(body);
            JsonNode emailIdentityNode = request.path("EmailIdentity");
            if (!emailIdentityNode.isMissingNode() && !emailIdentityNode.isNull()
                    && !emailIdentityNode.isTextual()) {
                // A non-string EmailIdentity is rejected rather than coerced (asText would turn 123
                // into "123"), the same as ConfigurationSetName below.
                throw new AwsException("SerializationException", null, 400);
            }
            String emailIdentity = emailIdentityNode.asText(null);
            if (emailIdentity == null || emailIdentity.isBlank()) {
                throw new AwsException("BadRequestException", "EmailIdentity is required.", 400);
            }
            // ConfigurationSetName must be a String; AWS rejects a non-string, so reject it here too
            // rather than coercing via asText (which would turn 123 into "123"). Floci surfaces this
            // as a 400 SerializationException, rendered as a JSON error body by AwsExceptionMapper.
            JsonNode configSetNode = request.path("ConfigurationSetName");
            String configurationSetName = null;
            if (!configSetNode.isMissingNode() && !configSetNode.isNull()) {
                if (!configSetNode.isTextual()) {
                    throw new AwsException("SerializationException", null, 400);
                }
                configurationSetName = configSetNode.textValue();
            }

            List<Tag> parsedTags = parseTagsArray(request.path("Tags"));

            // Only the empty string means "no default configuration set" (consistent with the
            // PutEmailIdentityConfigurationSetAttributes path); a whitespace-only name flows through
            // name validation and is rejected as invalid input, rather than being silently ignored.
            boolean hasConfigSet = configurationSetName != null && !configurationSetName.isEmpty();

            // The service builds the complete identity (default configuration set and tags included)
            // and persists it with a single write, so any failure (AlreadyExists, invalid tags, a
            // missing configuration set) fails the whole call and creates nothing, matching AWS.
            Identity identity = sesService.createEmailIdentity(emailIdentity,
                    hasConfigSet ? configurationSetName : null, parsedTags, region);

            ObjectNode result = objectMapper.createObjectNode();
            result.put("IdentityType", toV2IdentityType(identity.getIdentityType()));
            result.put("VerifiedForSendingStatus",
                    "Success".equals(identity.getVerificationStatus()));
            result.set("DkimAttributes", buildDkimAttributes(identity, region));

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
        List<Identity> identities = identityService.listIdentities(null, region);

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode items = result.putArray("EmailIdentities");
        for (Identity id : identities) {
            // Only a not-yet-verified domain can still transition (via its DKIM records),
            // so refresh just those; refreshing every identity would scan Route53 per call.
            Identity current = id;
            if ("Domain".equals(id.getIdentityType()) && !"Success".equals(id.getVerificationStatus())) {
                Identity refreshed =
                        identityService.getIdentityVerificationAttributes(id.getIdentity(), region);
                if (refreshed != null) {
                    current = refreshed;
                }
            }
            ObjectNode item = objectMapper.createObjectNode();
            item.put("IdentityType", toV2IdentityType(current.getIdentityType()));
            item.put("IdentityName", current.getIdentity());
            item.put("SendingEnabled", "Success".equals(current.getVerificationStatus()));
            item.put("VerificationStatus", toV2Status(current.getVerificationStatus()));
            items.add(item);
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/identities/{emailIdentity}")
    public Response getEmailIdentity(@Context HttpHeaders headers,
                                     @PathParam("emailIdentity") String emailIdentity) {
        String region = regionResolver.resolveRegion(headers);
        Identity identity =
                identityService.getIdentityVerificationAttributes(emailIdentity, region);
        if (identity == null) {
            throw new AwsException("NotFoundException",
                    "Identity " + emailIdentity + " does not exist.", 404);
        }
        return Response.ok(buildFullIdentityResponse(identity, region)).build();
    }

    @DELETE
    @Path("/identities/{emailIdentity}")
    public Response deleteEmailIdentity(@Context HttpHeaders headers,
                                        @PathParam("emailIdentity") String emailIdentity) {
        String region = regionResolver.resolveRegion(headers);
        if (identityService.getIdentityVerificationAttributes(emailIdentity, region) == null) {
            throw new AwsException("NotFoundException",
                    "Email identity " + emailIdentity + " does not exist.", 404);
        }
        sesService.deleteIdentity(emailIdentity, region);
        LOG.infov("SES V2 DeleteEmailIdentity: {0}", emailIdentity);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/identities/{emailIdentity}/policies/{policyName}")
    public Response createEmailIdentityPolicy(@Context HttpHeaders headers,
                                              @PathParam("emailIdentity") String emailIdentity,
                                              @PathParam("policyName") String policyName, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            String policy = readPolicyBody(body);
            sesService.createEmailIdentityPolicy(emailIdentity, policyName, policy, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @PUT
    @Path("/identities/{emailIdentity}/policies/{policyName}")
    public Response updateEmailIdentityPolicy(@Context HttpHeaders headers,
                                              @PathParam("emailIdentity") String emailIdentity,
                                              @PathParam("policyName") String policyName, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            String policy = readPolicyBody(body);
            sesService.updateEmailIdentityPolicy(emailIdentity, policyName, policy, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/identities/{emailIdentity}/policies")
    public Response getEmailIdentityPolicies(@Context HttpHeaders headers,
                                             @PathParam("emailIdentity") String emailIdentity) {
        String region = regionResolver.resolveRegion(headers);
        Map<String, String> policies = sesService.getEmailIdentityPolicies(emailIdentity, region);
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode policiesNode = result.putObject("Policies");
        policies.forEach(policiesNode::put);
        return Response.ok(result).build();
    }

    @DELETE
    @Path("/identities/{emailIdentity}/policies/{policyName}")
    public Response deleteEmailIdentityPolicy(@Context HttpHeaders headers,
                                              @PathParam("emailIdentity") String emailIdentity,
                                              @PathParam("policyName") String policyName) {
        String region = regionResolver.resolveRegion(headers);
        sesService.deleteEmailIdentityPolicy(emailIdentity, policyName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private String readPolicyBody(String body) throws JsonProcessingException {
        if (body == null || body.isBlank()) {
            throw new AwsException("BadRequestException", "Request body is required.", 400);
        }
        JsonNode request = objectMapper.readTree(body);
        requireJsonObject(request);
        JsonNode policyNode = request.path("Policy");
        if (policyNode.isMissingNode() || policyNode.isNull()) {
            // Verified against AWS: a missing/null required member is a Smithy validation error.
            throw new AwsException("BadRequestException",
                    "1 validation error detected: Value at 'policy' failed to satisfy constraint: "
                            + "Member must not be null", 400);
        }
        if (!policyNode.isTextual()) {
            // Policy is a String; AWS rejects a non-string with an empty-bodied 400. Don't coerce
            // (asText would turn 123 into "123"); surface a serialization error instead.
            throw new AwsException("SerializationException", null, 400);
        }
        return policyNode.textValue();
    }

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
            identityService.setDkimAttributes(emailIdentity, signingEnabled, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @PUT
    @Path("/identities/{emailIdentity}/dkim/signing")
    public Response putEmailIdentityDkimSigningAttributes(@Context HttpHeaders headers,
                                                          @PathParam("emailIdentity") String emailIdentity,
                                                          String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode() : objectMapper.readTree(body);
            requireJsonObject(request);
            String origin = request.path("SigningAttributesOrigin").asText(null);
            if (!"AWS_SES".equals(origin) && !"EXTERNAL".equals(origin)) {
                throw new AwsException("BadRequestException",
                        "SigningAttributesOrigin must be AWS_SES or EXTERNAL.", 400);
            }
            JsonNode attrs = requireObjectOrAbsent(request, "SigningAttributes");
            String selector = attrs.path("DomainSigningSelector").asText(null);
            String nextKeyLength = attrs.path("NextSigningKeyLength").asText(null);
            if (nextKeyLength != null
                    && !"RSA_1024_BIT".equals(nextKeyLength) && !"RSA_2048_BIT".equals(nextKeyLength)) {
                throw new AwsException("BadRequestException",
                        "NextSigningKeyLength must be RSA_1024_BIT or RSA_2048_BIT.", 400);
            }
            String privateKey = attrs.path("DomainSigningPrivateKey").asText(null);
            if ("EXTERNAL".equals(origin)
                    && (selector == null || selector.isBlank()
                        || privateKey == null || privateKey.isBlank())) {
                throw new AwsException("BadRequestException",
                        "EXTERNAL origin requires DomainSigningSelector and DomainSigningPrivateKey.", 400);
            }
            SesIdentityService.DkimSigningResult result = identityService.putDkimSigningAttributes(
                    emailIdentity, origin, selector, nextKeyLength, region);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("DkimStatus", toV2Status(result.dkimStatus()));
            ArrayNode tokens = out.putArray("DkimTokens");
            result.dkimTokens().forEach(tokens::add);
            return Response.ok(out).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

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
            identityService.setMailFromDomain(emailIdentity, mailFromDomain, behaviorV1, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

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
            identityService.setFeedbackForwardingEnabled(emailIdentity, emailForwardingEnabled,
                    region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @PUT
    @Path("/identities/{emailIdentity}/configuration-set")
    public Response putEmailIdentityConfigurationSetAttributes(@Context HttpHeaders headers,
                                                               @PathParam("emailIdentity") String emailIdentity,
                                                               String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            String configurationSetName = null;
            if (body != null && !body.isEmpty()) {
                // Only a truly empty body is "no body". A non-empty body must be a JSON object; a
                // whitespace-only or otherwise unparseable body is a serialization error (verified
                // against real AWS: whitespace-only returns SerializationException and does not
                // clear). Within a valid object, an omitted or explicit-null ConfigurationSetName
                // clears the association (as does an empty body / {}).
                JsonNode request = objectMapper.readTree(body);
                if (request == null || !request.isObject()) {
                    throw new AwsException("SerializationException", null, 400);
                }
                JsonNode node = request.path("ConfigurationSetName");
                if (!node.isMissingNode() && !node.isNull()) {
                    if (!node.isTextual()) {
                        throw new AwsException("BadRequestException",
                                "ConfigurationSetName must be a JSON string.", 400);
                    }
                    configurationSetName = node.asText();
                }
            }
            sesService.setEmailIdentityConfigurationSet(emailIdentity, configurationSetName, region);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("SerializationException", null, 400);
        }
    }

    private ObjectNode buildFullIdentityResponse(Identity identity, String region) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("IdentityType", toV2IdentityType(identity.getIdentityType()));
        result.put("VerifiedForSendingStatus",
                "Success".equals(identity.getVerificationStatus()));
        result.put("VerificationStatus", toV2Status(identity.getVerificationStatus()));
        result.put("FeedbackForwardingStatus", identity.isFeedbackForwardingEnabled());

        result.set("DkimAttributes", buildDkimAttributes(identity, region));

        ObjectNode mailFromAttributes = result.putObject("MailFromAttributes");
        mailFromAttributes.put("BehaviorOnMxFailure", v1BehaviorToV2(identity.getBehaviorOnMxFailure()));
        String mailFromDomain = identity.getMailFromDomain();
        if (mailFromDomain != null && !mailFromDomain.isEmpty()) {
            mailFromAttributes.put("MailFromDomain", mailFromDomain);
            mailFromAttributes.put("MailFromDomainStatus", toV2Status(identity.getMailFromDomainStatus()));
        }

        if (identity.getConfigurationSetName() != null && !identity.getConfigurationSetName().isEmpty()) {
            result.put("ConfigurationSetName", identity.getConfigurationSetName());
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

    private ObjectNode buildDkimAttributes(Identity identity, String region) {
        // An email identity reports its parent domain's DKIM (SigningEnabled / Status / Tokens all
        // inherit from the domain), matching AWS; a domain reports its own.
        Identity src = identityService.effectiveDkimSource(identity, region);
        ObjectNode dkim = objectMapper.createObjectNode();
        dkim.put("SigningEnabled", src.isDkimEnabled());
        dkim.put("Status", toV2Status(src.getDkimVerificationStatus()));
        ArrayNode tokens = dkim.putArray("Tokens");
        if (src.getDkimTokens() != null) {
            for (String token : src.getDkimTokens()) {
                tokens.add(token);
            }
        }
        dkim.put("SigningAttributesOrigin", src.getDkimSigningAttributesOrigin());
        dkim.put("NextSigningKeyLength", src.getDkimNextSigningKeyLength());
        dkim.put("CurrentSigningKeyLength", src.getDkimCurrentSigningKeyLength());
        putTimestamp(dkim, "LastKeyGenerationTimestamp", src.getDkimLastKeyGenerationTimestamp());
        return dkim;
    }

    private static String toV2IdentityType(String v1Type) {
        return "EmailAddress".equals(v1Type) ? "EMAIL_ADDRESS" : "DOMAIN";
    }

    private static String toV2Status(String v1Status) {
        if (v1Status == null) {
            return null;
        }
        return switch (v1Status) {
            case "Success" -> "SUCCESS";
            case "NotStarted" -> "NOT_STARTED";
            case "Pending" -> "PENDING";
            case "Failed" -> "FAILED";
            case "TemporaryFailure" -> "TEMPORARY_FAILURE";
            default -> v1Status;
        };
    }
}
