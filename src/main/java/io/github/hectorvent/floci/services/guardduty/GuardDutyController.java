package io.github.hectorvent.floci.services.guardduty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.EpochSeconds;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.guardduty.model.Detector;
import io.github.hectorvent.floci.services.guardduty.model.DetectorFeature;
import io.github.hectorvent.floci.services.guardduty.model.MalwareProtectionPlan;
import io.github.hectorvent.floci.services.guardduty.model.MemberAccount;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationFeature;
import io.github.hectorvent.floci.services.guardduty.model.PublishingDestination;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;

/**
 * GuardDuty (Smithy restJson1): detector lifecycle, organization configuration and Malware
 * Protection plans.
 *
 * <p>The literal {@code /detector} and {@code /admin} paths take JAX-RS precedence over S3's
 * {@code /{bucket}} and {@code /{bucket}/{key}} template routes, so these routes win with no
 * extra routing wiring. {@code /malware-protection-plan} would win the same way, so
 * {@link GuardDutyRouteFilter} moves only GuardDuty-signed requests for it to an internal prefix,
 * and a path-style S3 request for a bucket of that name still reaches S3. Tag operations ({@code /tags/{arn}}) are served by
 * {@code SharedTagsController} via {@link GuardDutyTagHandler}.
 *
 * <p>GuardDuty reports every client error as {@code BadRequestException} (HTTP 400); the
 * Terraform AWS provider matches specific message texts to detect missing resources, so those
 * messages must not change.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GuardDutyController {

    private final GuardDutyService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public GuardDutyController(
            GuardDutyService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("BadRequestException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("BadRequestException", "Request body is not valid JSON.", 400);
        }
    }

    @POST
    @Path("/detector")
    public Response createDetector(@Context HttpHeaders headers, String body) {
        Detector detector = service.createDetector(
                regionResolver.resolveRegion(headers), regionResolver.getAccountId(), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("detectorId", detector.getId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/detector")
    public Response listDetectors(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        GuardDutyService.Page<String> page = service.listDetectorIds(
                regionResolver.resolveRegion(headers), regionResolver.getAccountId(), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode ids = response.putArray("detectorIds");
        page.items().forEach(ids::add);
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/detector/{detectorId}")
    public Response getDetector(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        Detector detector = service.getDetector(regionResolver.resolveRegion(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("createdAt", detector.getCreatedAt());
        if (detector.getFindingPublishingFrequency() != null) {
            response.put("findingPublishingFrequency", detector.getFindingPublishingFrequency());
        }
        response.put("serviceRole", detector.getServiceRole());
        response.put("status", detector.getStatus());
        response.put("updatedAt", detector.getUpdatedAt());
        if (detector.getTags() != null && !detector.getTags().isEmpty()) {
            ObjectNode tags = response.putObject("tags");
            detector.getTags().forEach(tags::put);
        }
        if (detector.getFeatures() != null) {
            ArrayNode features = response.putArray("features");
            for (DetectorFeature feature : detector.getFeatures()) {
                features.add(objectMapper.valueToTree(feature));
            }
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}")
    public Response updateDetector(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        service.updateDetector(regionResolver.resolveRegion(headers), detectorId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/detector/{detectorId}")
    public Response deleteDetector(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        service.deleteDetector(regionResolver.resolveRegion(headers), detectorId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/detector/{detectorId}/member")
    public Response listMembers(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
                                @QueryParam("maxResults") String maxResults,
                                @QueryParam("nextToken") String nextToken,
                                @QueryParam("onlyAssociated") String onlyAssociated) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode members = response.putArray("members");
        GuardDutyService.Page<MemberAccount> page = service.listMembers(
                regionResolver.resolveRegion(headers), detectorId, maxResults, nextToken, onlyAssociated);
        for (MemberAccount member : page.items()) {
            ObjectNode node = members.addObject();
            node.put("accountId", member.accountId());
            node.put("administratorId", member.administratorId());
            node.put("masterId", member.administratorId());
            node.put("detectorId", member.detectorId());
            node.put("email", member.email());
            node.put("invitedAt", member.invitedAt());
            node.put("relationshipStatus", member.relationshipStatus());
            node.put("updatedAt", member.updatedAt());
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }
    @POST
    @Path("/detector/{detectorId}/member")
    public Response createMembers(@Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        service.createMembers(regionResolver.resolveRegion(headers), detectorId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("unprocessedAccounts");
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}/publishingDestination")
    public Response createPublishingDestination(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        PublishingDestination destination = service.createPublishingDestination(
                regionResolver.resolveRegion(headers), detectorId, parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("destinationId", destination.destinationId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/detector/{detectorId}/publishingDestination")
    public Response listPublishingDestinations(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        GuardDutyService.Page<PublishingDestination> page = service.listPublishingDestinations(
                regionResolver.resolveRegion(headers), detectorId, maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode destinations = response.putArray("destinations");
        for (PublishingDestination destination : page.items()) {
            ObjectNode node = destinations.addObject();
            node.put("destinationId", destination.destinationId());
            node.put("destinationType", destination.destinationType());
            node.put("status", destination.status());
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/detector/{detectorId}/publishingDestination/{destinationId}")
    public Response describePublishingDestination(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
            @PathParam("destinationId") String destinationId) {
        PublishingDestination destination = service.describePublishingDestination(
                regionResolver.resolveRegion(headers), detectorId, destinationId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("destinationId", destination.destinationId());
        response.put("destinationType", destination.destinationType());
        response.put("status", destination.status());
        response.put("publishingFailureStartTimestamp", 0L);
        ObjectNode properties = response.putObject("destinationProperties");
        if (destination.destinationArn() != null) {
            properties.put("destinationArn", destination.destinationArn());
        }
        if (destination.kmsKeyArn() != null) {
            properties.put("kmsKeyArn", destination.kmsKeyArn());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}/publishingDestination/{destinationId}")
    public Response updatePublishingDestination(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
            @PathParam("destinationId") String destinationId, String body) {
        service.updatePublishingDestination(
                regionResolver.resolveRegion(headers), detectorId, destinationId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/detector/{detectorId}/publishingDestination/{destinationId}")
    public Response deletePublishingDestination(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId,
            @PathParam("destinationId") String destinationId) {
        service.deletePublishingDestination(regionResolver.resolveRegion(headers), detectorId, destinationId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/detector/{detectorId}/admin")
    public Response describeOrganizationConfiguration(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId) {
        OrganizationConfiguration configuration = service.describeOrganizationConfiguration(
                regionResolver.resolveRegion(headers), detectorId);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("autoEnable", Boolean.TRUE.equals(configuration.getAutoEnable()));
        response.put("memberAccountLimitReached", false);
        response.put("autoEnableOrganizationMembers", configuration.getAutoEnableOrganizationMembers());
        ArrayNode features = response.putArray("features");
        if (configuration.getFeatures() != null) {
            for (OrganizationFeature feature : configuration.getFeatures()) {
                features.add(objectMapper.valueToTree(feature));
            }
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/detector/{detectorId}/admin")
    public Response updateOrganizationConfiguration(
            @Context HttpHeaders headers, @PathParam("detectorId") String detectorId, String body) {
        service.updateOrganizationConfiguration(
                regionResolver.resolveRegion(headers), detectorId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/admin/enable")
    public Response enableOrganizationAdminAccount(@Context HttpHeaders headers, String body) {
        service.enableOrganizationAdminAccount(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/admin/disable")
    public Response disableOrganizationAdminAccount(@Context HttpHeaders headers, String body) {
        service.disableOrganizationAdminAccount(regionResolver.resolveRegion(headers), parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path(GuardDutyRouteFilter.INTERNAL_PREFIX + GuardDutyRouteFilter.PLAN_PATH)
    public Response createMalwareProtectionPlan(@Context HttpHeaders headers, String body) {
        MalwareProtectionPlan plan = service.createMalwareProtectionPlan(
                regionResolver.resolveRegion(headers), regionResolver.getAccountId(), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("malwareProtectionPlanId", plan.getId());
        return Response.ok(response).build();
    }

    @GET
    @Path(GuardDutyRouteFilter.INTERNAL_PREFIX + GuardDutyRouteFilter.PLAN_PATH)
    public Response listMalwareProtectionPlans(
            @Context HttpHeaders headers, @QueryParam("nextToken") String nextToken) {
        GuardDutyService.Page<MalwareProtectionPlan> page =
                service.listMalwareProtectionPlans(regionResolver.resolveRegion(headers), nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode plans = response.putArray("malwareProtectionPlans");
        for (MalwareProtectionPlan plan : page.items()) {
            plans.addObject().put("malwareProtectionPlanId", plan.getId());
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path(GuardDutyRouteFilter.INTERNAL_PREFIX + GuardDutyRouteFilter.PLAN_PATH + "/{malwareProtectionPlanId}")
    public Response getMalwareProtectionPlan(
            @Context HttpHeaders headers, @PathParam("malwareProtectionPlanId") String planId) {
        MalwareProtectionPlan plan = service.getMalwareProtectionPlan(regionResolver.resolveRegion(headers), planId);
        ObjectNode response = objectMapper.createObjectNode();
        if (plan.getTaggingStatus() != null) {
            response.putObject("actions").putObject("tagging").put("status", plan.getTaggingStatus());
        }
        response.put("arn", plan.getArn());
        // The model types createdAt as a timestamp, which restJson1 carries as epoch seconds.
        response.put("createdAt", EpochSeconds.fromInstant(Instant.parse(plan.getCreatedAt())));
        ObjectNode bucket = response.putObject("protectedResource").putObject("s3Bucket");
        bucket.put("bucketName", plan.getBucketName());
        if (plan.getObjectPrefixes() != null) {
            ArrayNode prefixes = bucket.putArray("objectPrefixes");
            plan.getObjectPrefixes().forEach(prefixes::add);
        }
        response.put("role", plan.getRole());
        response.put("status", plan.getStatus());
        response.putArray("statusReasons");
        if (plan.getTags() != null && !plan.getTags().isEmpty()) {
            ObjectNode tags = response.putObject("tags");
            plan.getTags().forEach(tags::put);
        }
        return Response.ok(response).build();
    }

    @PATCH
    @Path(GuardDutyRouteFilter.INTERNAL_PREFIX + GuardDutyRouteFilter.PLAN_PATH + "/{malwareProtectionPlanId}")
    public Response updateMalwareProtectionPlan(
            @Context HttpHeaders headers, @PathParam("malwareProtectionPlanId") String planId, String body) {
        service.updateMalwareProtectionPlan(regionResolver.resolveRegion(headers), planId, parse(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path(GuardDutyRouteFilter.INTERNAL_PREFIX + GuardDutyRouteFilter.PLAN_PATH + "/{malwareProtectionPlanId}")
    public Response deleteMalwareProtectionPlan(
            @Context HttpHeaders headers, @PathParam("malwareProtectionPlanId") String planId) {
        service.deleteMalwareProtectionPlan(regionResolver.resolveRegion(headers), planId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }
}
