package io.github.hectorvent.floci.services.fis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
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

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FisController {

    private final FisService service;
    private final ObjectMapper objectMapper;
    private final ObjectReader requestReader;
    private final RegionResolver regionResolver;

    @Inject
    public FisController(FisService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.requestReader = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/experimentTemplates")
    public Response createExperimentTemplate(@Context HttpHeaders headers, String body) {
        return ok(service.createExperimentTemplate(region(headers), parse(body)));
    }

    @POST
    @Path("/experimentTemplates/{experimentTemplateId}/targetAccountConfigurations/{accountId}")
    public Response createTargetAccountConfiguration(
            @Context HttpHeaders headers,
            @PathParam("experimentTemplateId") String experimentTemplateId,
            @PathParam("accountId") String accountId,
            String body) {
        return ok(service.createTargetAccountConfiguration(
                region(headers), experimentTemplateId, accountId, parse(body)));
    }

    @DELETE
    @Path("/experimentTemplates/{id}")
    public Response deleteExperimentTemplate(
            @Context HttpHeaders headers, @PathParam("id") String id) {
        return ok(service.deleteExperimentTemplate(region(headers), id));
    }

    @DELETE
    @Path("/experimentTemplates/{experimentTemplateId}/targetAccountConfigurations/{accountId}")
    public Response deleteTargetAccountConfiguration(
            @Context HttpHeaders headers,
            @PathParam("experimentTemplateId") String experimentTemplateId,
            @PathParam("accountId") String accountId) {
        return ok(service.deleteTargetAccountConfiguration(region(headers), experimentTemplateId, accountId));
    }

    @GET
    @Path("/actions/{id}")
    public Response getAction(@Context HttpHeaders headers, @PathParam("id") String id) {
        return ok(service.getAction(region(headers), id));
    }

    @GET
    @Path("/experiments/{id}")
    public Response getExperiment(@Context HttpHeaders headers, @PathParam("id") String id) {
        return ok(service.getExperiment(region(headers), id));
    }

    @GET
    @Path("/experiments/{experimentId}/targetAccountConfigurations/{accountId}")
    public Response getExperimentTargetAccountConfiguration(
            @Context HttpHeaders headers,
            @PathParam("experimentId") String experimentId,
            @PathParam("accountId") String accountId) {
        return ok(service.getExperimentTargetAccountConfiguration(region(headers), experimentId, accountId));
    }

    @GET
    @Path("/experimentTemplates/{id}")
    public Response getExperimentTemplate(
            @Context HttpHeaders headers, @PathParam("id") String id) {
        return ok(service.getExperimentTemplate(region(headers), id));
    }

    @GET
    @Path("/safetyLevers/{id}")
    public Response getSafetyLever(@Context HttpHeaders headers, @PathParam("id") String id) {
        return ok(service.getSafetyLever(region(headers), id));
    }

    @GET
    @Path("/experimentTemplates/{experimentTemplateId}/targetAccountConfigurations/{accountId}")
    public Response getTargetAccountConfiguration(
            @Context HttpHeaders headers,
            @PathParam("experimentTemplateId") String experimentTemplateId,
            @PathParam("accountId") String accountId) {
        return ok(service.getTargetAccountConfiguration(region(headers), experimentTemplateId, accountId));
    }

    @GET
    @Path("/targetResourceTypes/{resourceType}")
    public Response getTargetResourceType(
            @Context HttpHeaders headers, @PathParam("resourceType") String resourceType) {
        return ok(service.getTargetResourceType(region(headers), resourceType));
    }

    @GET
    @Path("/actions")
    public Response listActions(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return ok(service.listActions(region(headers), parseMaxResults(maxResults), nextToken));
    }

    @GET
    @Path("/experiments/{experimentId}/resolvedTargets")
    public Response listExperimentResolvedTargets(
            @Context HttpHeaders headers,
            @PathParam("experimentId") String experimentId,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken,
            @QueryParam("targetName") String targetName) {
        return ok(service.listExperimentResolvedTargets(
                region(headers), experimentId, parseMaxResults(maxResults), nextToken, targetName));
    }

    @GET
    @Path("/experiments/{experimentId}/targetAccountConfigurations")
    public Response listExperimentTargetAccountConfigurations(
            @Context HttpHeaders headers,
            @PathParam("experimentId") String experimentId,
            @QueryParam("nextToken") String nextToken) {
        return ok(service.listExperimentTargetAccountConfigurations(region(headers), experimentId, nextToken));
    }

    @GET
    @Path("/experimentTemplates")
    public Response listExperimentTemplates(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return ok(service.listExperimentTemplates(region(headers), parseMaxResults(maxResults), nextToken));
    }

    @GET
    @Path("/experiments")
    public Response listExperiments(
            @Context HttpHeaders headers,
            @QueryParam("experimentTemplateId") String experimentTemplateId,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return ok(service.listExperiments(
                region(headers), parseMaxResults(maxResults), nextToken, experimentTemplateId));
    }

    @GET
    @Path("/experimentTemplates/{experimentTemplateId}/targetAccountConfigurations")
    public Response listTargetAccountConfigurations(
            @Context HttpHeaders headers,
            @PathParam("experimentTemplateId") String experimentTemplateId,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return ok(service.listTargetAccountConfigurations(
                region(headers), experimentTemplateId, parseMaxResults(maxResults), nextToken));
    }

    @GET
    @Path("/targetResourceTypes")
    public Response listTargetResourceTypes(
            @Context HttpHeaders headers,
            @QueryParam("maxResults") String maxResults,
            @QueryParam("nextToken") String nextToken) {
        return ok(service.listTargetResourceTypes(region(headers), parseMaxResults(maxResults), nextToken));
    }

    @POST
    @Path("/experiments")
    public Response startExperiment(@Context HttpHeaders headers, String body) {
        return ok(service.startExperiment(region(headers), parse(body)));
    }

    @DELETE
    @Path("/experiments/{id}")
    public Response stopExperiment(@Context HttpHeaders headers, @PathParam("id") String id) {
        return ok(service.stopExperiment(region(headers), id));
    }

    @PATCH
    @Path("/experimentTemplates/{id}")
    public Response updateExperimentTemplate(
            @Context HttpHeaders headers, @PathParam("id") String id, String body) {
        return ok(service.updateExperimentTemplate(region(headers), id, parse(body)));
    }

    @PATCH
    @Path("/safetyLevers/{id}/state")
    public Response updateSafetyLeverState(
            @Context HttpHeaders headers, @PathParam("id") String id, String body) {
        return ok(service.updateSafetyLeverState(region(headers), id, parse(body)));
    }

    @PATCH
    @Path("/experimentTemplates/{experimentTemplateId}/targetAccountConfigurations/{accountId}")
    public Response updateTargetAccountConfiguration(
            @Context HttpHeaders headers,
            @PathParam("experimentTemplateId") String experimentTemplateId,
            @PathParam("accountId") String accountId,
            String body) {
        return ok(service.updateTargetAccountConfiguration(
                region(headers), experimentTemplateId, accountId, parse(body)));
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = requestReader.readTree(body);
            if (request == null || !request.isObject()) {
                throw validation("Request body must be a JSON object.");
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw validation("Request body is not valid JSON.");
        }
    }

    private Integer parseMaxResults(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw validation("maxResults must be an integer between 1 and 100.");
        }
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private Response ok(JsonNode entity) {
        return Response.ok(entity).build();
    }

    private AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
