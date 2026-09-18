package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.AccountSuppressionAttributes;
import io.github.hectorvent.floci.services.ses.model.AccountVdmAttributes;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

import static io.github.hectorvent.floci.services.ses.SesV2Json.coerceBoolean;
import static io.github.hectorvent.floci.services.ses.SesV2Json.readOptionBody;
import static io.github.hectorvent.floci.services.ses.SesV2Json.remapV1Exception;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireJsonObject;
import static io.github.hectorvent.floci.services.ses.SesV2Json.requireObjectOrAbsent;
import static io.github.hectorvent.floci.services.ses.SesV2Json.stringMemberOrAbsent;

/**
 * SES V2 account-level endpoints ({@code /v2/email/account}). The settings live in
 * {@link SesAccountService}; {@code GetAccount} also reads the account
 * suppression attributes from {@link SesSuppressionService} and the sent-mail count from
 * {@link SesSentEmailService}, a read-only composition that needs no facade orchestration, so this
 * controller does not depend on {@link SesService}.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesAccountController {

    private static final Logger LOG = Logger.getLogger(SesAccountController.class);

    private final SesAccountService accountService;
    private final SesSuppressionService suppressionService;
    private final SesSentEmailService sentEmailService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesAccountController(SesAccountService accountService,
                                SesSuppressionService suppressionService,
                                SesSentEmailService sentEmailService,
                                RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.accountService = accountService;
        this.suppressionService = suppressionService;
        this.sentEmailService = sentEmailService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/account")
    public Response getAccount(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        long sentCount = sentEmailService.countInRegion(region);
        boolean sendingEnabled = accountService.isAccountSendingEnabled(region);
        AccountSuppressionAttributes suppression =
                suppressionService.getAccountSuppressionAttributes(region);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("DedicatedIpAutoWarmupEnabled",
                accountService.isDedicatedIpAutoWarmupEnabled(region));
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

        // AWS only surfaces VdmAttributes once VDM has been configured for the region (an untouched
        // region omits the key entirely), and only adds the Dashboard/Guardian sub-attributes while
        // VdmEnabled is ENABLED.
        accountService.findAccountVdmAttributes(region).ifPresent(vdm -> {
            ObjectNode vdmAttrs = result.putObject("VdmAttributes");
            vdmAttrs.put("VdmEnabled", featureStatus(vdm.vdmEnabled()));
            if (vdm.vdmEnabled()) {
                vdmAttrs.putObject("DashboardAttributes")
                        .put("EngagementMetrics", featureStatus(vdm.engagementMetrics()));
                vdmAttrs.putObject("GuardianAttributes")
                        .put("OptimizedSharedDelivery", featureStatus(vdm.optimizedSharedDelivery()));
            }
        });

        // Like VdmAttributes, AWS omits Details until PutAccountDetails has run for the region.
        accountService.findAccountDetails(region).ifPresent(details -> {
            ObjectNode d = result.putObject("Details");
            d.put("MailType", details.mailType());
            d.put("WebsiteURL", details.websiteUrl());
            if (details.contactLanguage() != null) {
                d.put("ContactLanguage", details.contactLanguage());
            }
            if (details.useCaseDescription() != null) {
                d.put("UseCaseDescription", details.useCaseDescription());
            }
            if (details.additionalContactEmailAddresses() != null
                    && !details.additionalContactEmailAddresses().isEmpty()) {
                ArrayNode addrs = d.putArray("AdditionalContactEmailAddresses");
                details.additionalContactEmailAddresses().forEach(addrs::add);
            }
            ObjectNode review = d.putObject("ReviewDetails");
            review.put("Status", details.reviewStatus());
            review.put("CaseId", details.caseId());
        });

        return Response.ok(result).build();
    }

    @PUT
    @Path("/account/vdm")
    public Response putAccountVdmAttributes(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);
            JsonNode vdm = request.path("VdmAttributes");
            if (!vdm.isObject()) {
                throw new AwsException("BadRequestException", "VdmAttributes is required.", 400);
            }
            boolean vdmEnabled = parseFeatureStatus(vdm, "VdmEnabled",
                    "vdmAttributes.vdmEnabled", true);
            boolean engagement = parseFeatureStatus(
                    requireObjectOrAbsent(vdm, "DashboardAttributes"), "EngagementMetrics",
                    "vdmAttributes.dashboardAttributes.engagementMetrics", false);
            boolean osd = parseFeatureStatus(
                    requireObjectOrAbsent(vdm, "GuardianAttributes"), "OptimizedSharedDelivery",
                    "vdmAttributes.guardianAttributes.optimizedSharedDelivery", false);
            accountService.putAccountVdmAttributes(region,
                    new AccountVdmAttributes(vdmEnabled, engagement, osd));
            LOG.infov("SES V2 PutAccountVdmAttributes: enabled={0}, engagement={1}, osd={2}",
                    vdmEnabled, engagement, osd);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/account/details")
    public Response putAccountDetails(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
            requireJsonObject(request);

            // Parse every member first (rejecting wrong JSON types as a serialization error, the way
            // AWS does before validation), then validate the parsed values together so all constraint
            // violations are aggregated into one response.
            String mailType = stringMemberOrAbsent(request, "MailType");
            String websiteUrl = stringMemberOrAbsent(request, "WebsiteURL");
            String contactLanguage = stringMemberOrAbsent(request, "ContactLanguage");
            String useCaseDescription = stringMemberOrAbsent(request, "UseCaseDescription");

            List<String> additionalContacts = null;
            JsonNode contacts = request.path("AdditionalContactEmailAddresses");
            if (!contacts.isMissingNode() && !contacts.isNull()) {
                // A typed list member: reject a non-array, and reject non-string elements, rather than
                // coercing (asText would turn 123 into "123"), matching how AWS rejects type mismatches.
                if (!contacts.isArray()) {
                    throw new AwsException("SerializationException", null, 400);
                }
                additionalContacts = new ArrayList<>();
                for (JsonNode node : contacts) {
                    if (!node.isTextual()) {
                        throw new AwsException("SerializationException", null, 400);
                    }
                    additionalContacts.add(node.textValue());
                }
            }
            JsonNode productionAccess = request.path("ProductionAccessEnabled");
            if (!productionAccess.isMissingNode() && !productionAccess.isNull() && !productionAccess.isBoolean()) {
                throw new AwsException("SerializationException", null, 400);
            }
            boolean productionAccessEnabled = productionAccess.asBoolean(false);

            // The service owns validation and the synthetic review/case so they can't be bypassed; the
            // controller only parses the REST JSON and rejects wrong JSON types.
            accountService.putAccountDetails(region, mailType, websiteUrl, contactLanguage,
                    useCaseDescription, additionalContacts, productionAccessEnabled);
            LOG.infov("SES V2 PutAccountDetails: region={0}, mailType={1}", region, mailType);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
            // AWS reports a malformed JSON body as a SerializationException, the same error type used
            // for wrong-typed members above.
            throw new AwsException("SerializationException", null, 400);
        }
    }

    @PUT
    @Path("/account/dedicated-ips/warmup")
    public Response putAccountDedicatedIpWarmupAttributes(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode request = readOptionBody(objectMapper, body);
            JsonNode enabledNode = request.path("AutoWarmupEnabled");
            // AutoWarmupEnabled has a default of false: the SDK omits it when false, so a missing
            // member is treated as false rather than rejected. A present value goes through the
            // shared SES v2 boolean coercion (string→true, null/number/container→SerializationException).
            boolean enabled = enabledNode.isMissingNode() ? false : coerceBoolean(enabledNode);
            accountService.setDedicatedIpAutoWarmup(region, enabled);
            LOG.infov("SES V2 PutAccountDedicatedIpWarmupAttributes: {0}", enabled);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        }
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
            suppressionService.putAccountSuppressionAttributes(region, reasons);
            LOG.infov("SES V2 PutAccountSuppressionAttributes: {0}", reasons);
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (JsonProcessingException e) {
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
            accountService.setAccountSendingEnabled(region, sendingEnabledNode.booleanValue());
            return Response.ok(objectMapper.createObjectNode()).build();
        } catch (AwsException e) {
            throw remapV1Exception(e);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    private static String featureStatus(boolean enabled) {
        return enabled ? "ENABLED" : "DISABLED";
    }

    // Parse an AWS FeatureStatus (ENABLED/DISABLED) field. A required member that is absent, or any
    // value outside the enum, is a Smithy BadRequestException the way AWS returns it; an absent
    // optional member defaults to DISABLED (false).
    private static boolean parseFeatureStatus(JsonNode parent, String field, String path, boolean required) {
        JsonNode node = parent.path(field);
        if (node.isMissingNode() || node.isNull()) {
            if (required) {
                throw new AwsException("BadRequestException",
                        "1 validation error detected: Value null at '" + path
                                + "' failed to satisfy constraint: Member must not be null", 400);
            }
            return false;
        }
        if (node.isTextual()) {
            String value = node.asText();
            if ("ENABLED".equals(value)) {
                return true;
            }
            if ("DISABLED".equals(value)) {
                return false;
            }
        }
        throw new AwsException("BadRequestException",
                "1 validation error detected: Value at '" + path
                        + "' failed to satisfy constraint: Member must satisfy enum value set: [ENABLED, DISABLED]",
                400);
    }
}
