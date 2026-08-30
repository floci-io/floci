package io.github.hectorvent.floci.services.guardduty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.guardduty.model.AdminAccount;
import io.github.hectorvent.floci.services.guardduty.model.Detector;
import io.github.hectorvent.floci.services.guardduty.model.DetectorFeature;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationConfiguration;
import io.github.hectorvent.floci.services.guardduty.model.OrganizationFeature;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * GuardDuty (Smithy restJson1) — detector lifecycle and organization configuration.
 *
 * <p>The literal {@code /detector} and {@code /admin} paths take JAX-RS precedence over S3's
 * {@code /{bucket}} and {@code /{bucket}/{key}} template routes, so these routes win with no
 * extra routing wiring. Tag operations ({@code /tags/{arn}}) are served by
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
                regionResolver.resolveRegion(headers), maxResults, nextToken);
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

    @GET
    @Path("/admin")
    public Response listOrganizationAdminAccounts(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        GuardDutyService.Page<AdminAccount> page = service.listOrganizationAdminAccounts(
                regionResolver.resolveRegion(headers), maxResults, nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode accounts = response.putArray("adminAccounts");
        for (AdminAccount account : page.items()) {
            accounts.add(objectMapper.valueToTree(account));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }
}
