package io.github.hectorvent.floci.services.controltower;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.controltower.model.EnabledBaseline;
import io.github.hectorvent.floci.services.controltower.model.LandingZone;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;

/**
 * AWS Control Tower (Smithy restJson1) — the operations that unblock LZA's Prepare stage with
 * {@code controlTower.enable: true}: the landing-zone lifecycle (list/get/create/update/delete/reset),
 * operation polling for both landing zones and baselines, and the baseline surface
 * (list, list/get enabled, enable, reset, update).
 *
 * <p>Exists so LZA's {@code setup-landing-zone} and {@code register-organizational-unit} modules,
 * which call {@code controltower:ListLandingZones}/{@code GetLandingZone}/etc., get a JSON
 * response instead of falling through to the S3 catch-all ({@code @Path("/{bucket}")}).
 *
 * <p>The literal single-segment paths (e.g. {@code /list-landingzones}) take JAX-RS precedence
 * over S3's {@code /{bucket}} template route, so these routes win with no extra routing wiring
 * (see {@link io.github.hectorvent.floci.services.rum.RumController}).
 *
 * <p>Note that every landing-zone URI spells "landingzone" as ONE word, {@code /create-landingzone}
 * included — that is how the AWS Control Tower model defines them.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ControlTowerController {

    private final ControlTowerService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final RequestContext requestContext;

    @Inject
    public ControlTowerController(
            ControlTowerService service,
            ObjectMapper objectMapper,
            RegionResolver regionResolver,
            RequestContext requestContext) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.requestContext = requestContext;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }

    @POST
    @Path("/list-landingzones")
    @Consumes(MediaType.WILDCARD)
    public Response listLandingZones(@Context HttpHeaders headers) {
        List<LandingZone> landingZones = service.listLandingZones(
                requestContext.getAccountId(), regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        var array = response.putArray("landingZones");
        for (LandingZone lz : landingZones) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("arn", lz.getArn());
            array.add(node);
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/get-landingzone")
    public Response getLandingZone(@Context HttpHeaders headers, String body) {
        LandingZone lz = service.getLandingZone(
                requestContext.getAccountId(), regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("landingZone", landingZoneNode(lz));
        return Response.ok(response).build();
    }

    @POST
    @Path("/update-landingzone")
    public Response updateLandingZone(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        String opId = service.updateLandingZone(
                requestContext.getAccountId(), regionResolver.resolveRegion(headers), request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("operationIdentifier", opId);
        return Response.ok(response).build();
    }

    @POST
    @Path("/delete-landingzone")
    public Response deleteLandingZone(@Context HttpHeaders headers, String body) {
        String opId = service.deleteLandingZone(
                requestContext.getAccountId(), regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("operationIdentifier", opId);
        return Response.ok(response).build();
    }

    @POST
    @Path("/reset-landingzone")
    public Response resetLandingZone(@Context HttpHeaders headers, String body) {
        String opId = service.resetLandingZone(
                requestContext.getAccountId(), regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("operationIdentifier", opId);
        return Response.ok(response).build();
    }

    @POST
    @Path("/create-landingzone")
    public Response createLandingZone(@Context HttpHeaders headers, String body) {
        ControlTowerService.CreateLandingZoneResult result = service.createLandingZone(
                requestContext.getAccountId(), regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", result.arn());
        response.put("operationIdentifier", result.operationIdentifier());
        return Response.ok(response).build();
    }

    @POST
    @Path("/get-landingzone-operation")
    public Response getLandingZoneOperation(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        String opId = requireText(request, "operationIdentifier");
        String operationType = service.getOperationType(
                requestContext.getAccountId(), regionResolver.resolveRegion(headers), opId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("operationDetails", operationDetailsNode(opId, operationType));
        return Response.ok(response).build();
    }

    @POST
    @Path("/list-landingzone-operations")
    public Response listLandingZoneOperations(@Context HttpHeaders headers, String body) {
        ControlTowerService.ListLandingZoneOperationsResult result = service.listLandingZoneOperations(
                requestContext.getAccountId(), regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        var array = response.putArray("landingZoneOperations");
        for (ControlTowerService.LandingZoneOperationSummary operation : result.landingZoneOperations()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("operationIdentifier", operation.operationIdentifier());
            node.put("operationType", operation.operationType());
            node.put("status", operation.status());
            array.add(node);
        }
        if (result.nextToken() != null) {
            response.put("nextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/list-baselines")
    @Consumes(MediaType.WILDCARD)
    public Response listBaselines(@Context HttpHeaders headers) {
        List<ObjectNode> baselines = service.listBaselines(regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        var array = response.putArray("baselines");
        array.addAll(baselines);
        return Response.ok(response).build();
    }

    @POST
    @Path("/list-enabled-baselines")
    @Consumes(MediaType.WILDCARD)
    public Response listEnabledBaselines(@Context HttpHeaders headers, String body) {
        ControlTowerService.ListEnabledBaselinesResult result = service.listEnabledBaselines(
                requestContext.getAccountId(), regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        var array = response.putArray("enabledBaselines");
        for (EnabledBaseline entry : result.enabledBaselines()) {
            array.add(enabledBaselineNode(entry));
        }
        if (result.nextToken() != null) {
            response.put("nextToken", result.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/get-enabled-baseline")
    public Response getEnabledBaseline(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        String identifier = requireText(request, "enabledBaselineIdentifier");
        EnabledBaseline entry = service.getEnabledBaseline(
                requestContext.getAccountId(), regionResolver.resolveRegion(headers), identifier);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("enabledBaselineDetails", enabledBaselineDetailsNode(entry));
        return Response.ok(response).build();
    }

    @POST
    @Path("/enable-baseline")
    public Response enableBaseline(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        ControlTowerService.EnableBaselineResult result = service.enableBaseline(
                requestContext.getAccountId(), regionResolver.resolveRegion(headers), request);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("operationIdentifier", result.operationIdentifier());
        response.put("arn", result.arn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/reset-enabled-baseline")
    public Response resetEnabledBaseline(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        String identifier = requireText(request, "enabledBaselineIdentifier");
        String opId = service.resetEnabledBaseline(
                requestContext.getAccountId(), regionResolver.resolveRegion(headers), identifier);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("operationIdentifier", opId);
        return Response.ok(response).build();
    }

    @POST
    @Path("/update-enabled-baseline")
    public Response updateEnabledBaseline(@Context HttpHeaders headers, String body) {
        String opId = service.updateEnabledBaseline(
                requestContext.getAccountId(), regionResolver.resolveRegion(headers), parse(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("operationIdentifier", opId);
        return Response.ok(response).build();
    }

    @POST
    @Path("/get-baseline-operation")
    public Response getBaselineOperation(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        String opId = requireText(request, "operationIdentifier");
        String operationType = service.getBaselineOperationType(
                requestContext.getAccountId(), regionResolver.resolveRegion(headers), opId);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("baselineOperation", operationDetailsNode(opId, operationType));
        return Response.ok(response).build();
    }

    private ObjectNode landingZoneNode(LandingZone lz) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", lz.getArn());
        node.put("version", lz.getVersion());
        node.put("latestAvailableVersion", lz.getLatestAvailableVersion());
        node.put("status", lz.getStatus());
        ObjectNode driftStatus = objectMapper.createObjectNode();
        driftStatus.put("status", lz.getDriftStatus());
        node.set("driftStatus", driftStatus);
        node.set("manifest", lz.getManifest());
        if (lz.getRemediationTypes() != null) {
            var remediationTypes = node.putArray("remediationTypes");
            lz.getRemediationTypes().forEach(remediationTypes::add);
        }
        return node;
    }

    private ObjectNode enabledBaselineNode(EnabledBaseline entry) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", entry.getArn());
        node.put("baselineIdentifier", entry.getBaselineIdentifier());
        node.put("baselineVersion", entry.getBaselineVersion());
        node.put("targetIdentifier", entry.getTargetIdentifier());
        ObjectNode statusSummary = objectMapper.createObjectNode();
        statusSummary.put("status", entry.getStatus());
        if (entry.getLastOperationIdentifier() != null) {
            statusSummary.put("lastOperationIdentifier", entry.getLastOperationIdentifier());
        }
        node.set("statusSummary", statusSummary);
        if (entry.getParentIdentifier() != null) {
            node.put("parentIdentifier", entry.getParentIdentifier());
        }
        if (entry.getDriftStatus() != null) {
            node.set("driftStatusSummary", driftStatusNode(entry));
        }
        return node;
    }

    private ObjectNode enabledBaselineDetailsNode(EnabledBaseline entry) {
        ObjectNode node = enabledBaselineNode(entry);
        if (entry.getParameters() != null) {
            node.set("parameters", entry.getParameters());
        }
        return node;
    }

    private ObjectNode driftStatusNode(EnabledBaseline entry) {
        ObjectNode inheritance = objectMapper.createObjectNode();
        inheritance.put("status", entry.getDriftStatus());
        ObjectNode types = objectMapper.createObjectNode();
        types.set("inheritance", inheritance);
        ObjectNode summary = objectMapper.createObjectNode();
        summary.set("types", types);
        return summary;
    }

    private ObjectNode operationDetailsNode(String operationIdentifier, String operationType) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("operationIdentifier", operationIdentifier);
        node.put("operationType", operationType);
        node.put("status", ControlTowerService.OP_SUCCEEDED);
        String now = Instant.now().toString();
        node.put("startTime", now);
        node.put("endTime", now);
        return node;
    }

    private static String requireText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual()) {
            throw new AwsException("ValidationException", field + " must be a string.", 400);
        }
        return value.textValue();
    }
}
