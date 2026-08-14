package io.github.hectorvent.floci.services.amp;

import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.amp.model.PrometheusScraper;
import io.github.hectorvent.floci.services.amp.model.PrometheusWorkspace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.HashMap;
import java.util.Map;

/**
 * Amazon Managed Service for Prometheus REST-JSON controller (endpoint prefix
 * {@code aps}). Statuses are ACTIVE immediately so SDK/provider waiters
 * complete on their first poll.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AmpController {

    private static final String ACTIVE = "ACTIVE";

    private final AmpService ampService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public AmpController(AmpService ampService, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.ampService = ampService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── Workspaces ────────────────────────────

    @POST
    @Path("/workspaces")
    public Response createWorkspace(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        PrometheusWorkspace workspace = ampService.createWorkspace(
                textOrNull(request, "alias"),
                textOrNull(request, "kmsKeyArn"),
                parseTags(request.get("tags")),
                region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("workspaceId", workspace.getWorkspaceId());
        response.put("arn", workspace.getArn());
        response.set("status", status(ACTIVE));
        response.set("tags", objectMapper.valueToTree(workspace.getTags()));
        if (workspace.getKmsKeyArn() != null) {
            response.put("kmsKeyArn", workspace.getKmsKeyArn());
        }
        return Response.status(202).entity(response).build();
    }

    @GET
    @Path("/workspaces")
    public Response listWorkspaces(@QueryParam("alias") String alias, @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        var array = response.putArray("workspaces");
        for (PrometheusWorkspace workspace : ampService.listWorkspaces(alias, region)) {
            array.add(workspaceNode(workspace));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}")
    public Response describeWorkspace(@PathParam("workspaceId") String workspaceId,
                                      @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        PrometheusWorkspace workspace = ampService.describeWorkspace(workspaceId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("workspace", workspaceNode(workspace));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/workspaces/{workspaceId}")
    public Response deleteWorkspace(@PathParam("workspaceId") String workspaceId,
                                    @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ampService.deleteWorkspace(workspaceId, region);
        return Response.status(202).build();
    }

    // ─────────────────────── Alert manager definition ───────────────────────

    @POST
    @Path("/workspaces/{workspaceId}/alertmanager/definition")
    public Response createAlertManagerDefinition(@PathParam("workspaceId") String workspaceId,
                                                 @Context HttpHeaders headers, String body) {
        return upsertAlertManagerDefinition(workspaceId, headers, body);
    }

    @PUT
    @Path("/workspaces/{workspaceId}/alertmanager/definition")
    public Response putAlertManagerDefinition(@PathParam("workspaceId") String workspaceId,
                                              @Context HttpHeaders headers, String body) {
        return upsertAlertManagerDefinition(workspaceId, headers, body);
    }

    private Response upsertAlertManagerDefinition(String workspaceId, HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        ampService.createAlertManagerDefinition(workspaceId, textOrNull(request, "data"), region);
        return Response.status(202).entity(statusResponse(ACTIVE)).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}/alertmanager/definition")
    public Response describeAlertManagerDefinition(@PathParam("workspaceId") String workspaceId,
                                                   @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        PrometheusWorkspace workspace = ampService.describeAlertManagerDefinition(workspaceId, region);

        ObjectNode definition = objectMapper.createObjectNode();
        definition.set("status", status(ACTIVE));
        definition.put("data", workspace.getAlertManagerData());
        definition.put("createdAt", workspace.getAlertManagerCreatedAt().getEpochSecond());
        definition.put("modifiedAt", workspace.getAlertManagerModifiedAt().getEpochSecond());

        ObjectNode response = objectMapper.createObjectNode();
        response.set("alertManagerDefinition", definition);
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/workspaces/{workspaceId}/alertmanager/definition")
    public Response deleteAlertManagerDefinition(@PathParam("workspaceId") String workspaceId,
                                                 @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ampService.deleteAlertManagerDefinition(workspaceId, region);
        return Response.status(202).build();
    }

    // ─────────────────────── Query logging configuration ───────────────────────

    @POST
    @Path("/workspaces/{workspaceId}/logging/query")
    public Response createQueryLoggingConfiguration(@PathParam("workspaceId") String workspaceId,
                                                    @Context HttpHeaders headers, String body) {
        return upsertQueryLoggingConfiguration(workspaceId, headers, body);
    }

    @PUT
    @Path("/workspaces/{workspaceId}/logging/query")
    public Response updateQueryLoggingConfiguration(@PathParam("workspaceId") String workspaceId,
                                                    @Context HttpHeaders headers, String body) {
        return upsertQueryLoggingConfiguration(workspaceId, headers, body);
    }

    private Response upsertQueryLoggingConfiguration(String workspaceId, HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        ampService.createQueryLoggingConfiguration(workspaceId, request.get("destinations"), region);
        return Response.status(202).entity(statusResponse(ACTIVE)).build();
    }

    @GET
    @Path("/workspaces/{workspaceId}/logging/query")
    public Response describeQueryLoggingConfiguration(@PathParam("workspaceId") String workspaceId,
                                                      @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        PrometheusWorkspace workspace = ampService.describeQueryLoggingConfiguration(workspaceId, region);

        ObjectNode configuration = objectMapper.createObjectNode();
        configuration.set("status", status(ACTIVE));
        configuration.put("workspace", workspace.getWorkspaceId());
        configuration.set("destinations", workspace.getQueryLoggingDestinations());
        configuration.put("createdAt", workspace.getQueryLoggingCreatedAt().getEpochSecond());
        configuration.put("modifiedAt", workspace.getQueryLoggingModifiedAt().getEpochSecond());

        ObjectNode response = objectMapper.createObjectNode();
        response.set("queryLoggingConfiguration", configuration);
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/workspaces/{workspaceId}/logging/query")
    public Response deleteQueryLoggingConfiguration(@PathParam("workspaceId") String workspaceId,
                                                    @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ampService.deleteQueryLoggingConfiguration(workspaceId, region);
        return Response.status(202).build();
    }

    // ──────────────────────────── Scrapers ────────────────────────────

    @POST
    @Path("/scrapers")
    public Response createScraper(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        PrometheusScraper scraper = ampService.createScraper(
                textOrNull(request, "alias"),
                request.get("scrapeConfiguration"),
                request.get("source"),
                request.get("destination"),
                request.get("roleConfiguration"),
                parseTags(request.get("tags")),
                region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("scraperId", scraper.getScraperId());
        response.put("arn", scraper.getArn());
        response.set("status", status(ACTIVE));
        response.set("tags", objectMapper.valueToTree(scraper.getTags()));
        return Response.status(202).entity(response).build();
    }

    @GET
    @Path("/scrapers")
    public Response listScrapers(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        var array = response.putArray("scrapers");
        for (PrometheusScraper scraper : ampService.listScrapers(region)) {
            array.add(scraperNode(scraper));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/scrapers/{scraperId}")
    public Response describeScraper(@PathParam("scraperId") String scraperId,
                                    @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        PrometheusScraper scraper = ampService.describeScraper(scraperId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("scraper", scraperNode(scraper));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/scrapers/{scraperId}")
    public Response deleteScraper(@PathParam("scraperId") String scraperId,
                                  @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ampService.deleteScraper(scraperId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("scraperId", scraperId);
        response.set("status", status("DELETING"));
        return Response.status(202).entity(response).build();
    }

    // ─────────────────── Scraper logging configuration ───────────────────

    @PUT
    @Path("/scrapers/{scraperId}/logging-configuration")
    public Response updateScraperLoggingConfiguration(@PathParam("scraperId") String scraperId,
                                                      @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        ampService.updateScraperLoggingConfiguration(scraperId,
                request.get("loggingDestination"), request.get("scraperComponents"), region);
        return Response.status(202).entity(statusResponse(ACTIVE)).build();
    }

    @GET
    @Path("/scrapers/{scraperId}/logging-configuration")
    public Response describeScraperLoggingConfiguration(@PathParam("scraperId") String scraperId,
                                                        @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        PrometheusScraper scraper = ampService.describeScraperLoggingConfiguration(scraperId, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("status", status(ACTIVE));
        response.put("scraperId", scraper.getScraperId());
        response.set("loggingDestination", scraper.getLoggingDestination());
        if (scraper.getLoggingScraperComponents() != null) {
            response.set("scraperComponents", scraper.getLoggingScraperComponents());
        } else {
            response.putArray("scraperComponents");
        }
        response.put("modifiedAt", scraper.getLoggingModifiedAt().getEpochSecond());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/scrapers/{scraperId}/logging-configuration")
    public Response deleteScraperLoggingConfiguration(@PathParam("scraperId") String scraperId,
                                                      @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ampService.deleteScraperLoggingConfiguration(scraperId, region);
        return Response.status(202).build();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode workspaceNode(PrometheusWorkspace workspace) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("workspaceId", workspace.getWorkspaceId());
        if (workspace.getAlias() != null) {
            node.put("alias", workspace.getAlias());
        }
        node.put("arn", workspace.getArn());
        node.set("status", status(ACTIVE));
        node.put("prometheusEndpoint", workspace.getPrometheusEndpoint());
        node.put("createdAt", workspace.getCreatedAt().getEpochSecond());
        node.set("tags", objectMapper.valueToTree(workspace.getTags()));
        if (workspace.getKmsKeyArn() != null) {
            node.put("kmsKeyArn", workspace.getKmsKeyArn());
        }
        return node;
    }

    private ObjectNode scraperNode(PrometheusScraper scraper) {
        ObjectNode node = objectMapper.createObjectNode();
        if (scraper.getAlias() != null) {
            node.put("alias", scraper.getAlias());
        }
        node.put("scraperId", scraper.getScraperId());
        node.put("arn", scraper.getArn());
        node.put("roleArn", scraper.getRoleArn());
        node.set("status", status(ACTIVE));
        node.put("createdAt", scraper.getCreatedAt().getEpochSecond());
        node.put("lastModifiedAt", scraper.getLastModifiedAt().getEpochSecond());
        node.set("tags", objectMapper.valueToTree(scraper.getTags()));
        node.set("scrapeConfiguration", scraper.getScrapeConfiguration());
        node.set("source", scraper.getSource());
        node.set("destination", scraper.getDestination());
        if (scraper.getRoleConfiguration() != null) {
            node.set("roleConfiguration", scraper.getRoleConfiguration());
        }
        return node;
    }

    private ObjectNode status(String statusCode) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("statusCode", statusCode);
        return node;
    }

    private ObjectNode statusResponse(String statusCode) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("status", status(statusCode));
        return node;
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new jakarta.ws.rs.WebApplicationException(
                    JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }
}
