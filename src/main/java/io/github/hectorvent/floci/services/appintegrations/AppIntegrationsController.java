package io.github.hectorvent.floci.services.appintegrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.appintegrations.model.DataIntegration;
import io.github.hectorvent.floci.services.appintegrations.model.EventIntegration;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * Amazon AppIntegrations REST-JSON controller.
 *
 * <p>Tag operations live on the shared {@code /tags/{resourceArn}} path and are served by
 * {@code SharedTagsController} through {@link AppIntegrationsService}'s {@code TagHandler}.
 * Only the operations declared below are served; anything else falls through to the
 * emulator's not-found handling rather than a stub success.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppIntegrationsController {

    private final AppIntegrationsService appIntegrationsService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public AppIntegrationsController(AppIntegrationsService appIntegrationsService,
                                     RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.appIntegrationsService = appIntegrationsService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────── Event integrations ───────────────────────

    @POST
    @Path("/eventIntegrations")
    public Response createEventIntegration(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        EventIntegration integration = appIntegrationsService.createEventIntegration(
                textOrNull(request, "Name"),
                textOrNull(request, "Description"),
                request.get("EventFilter"),
                textOrNull(request, "EventBridgeBus"),
                parseTags(request.get("Tags")),
                region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("EventIntegrationArn", integration.getEventIntegrationArn());
        return Response.ok(response).build();
    }

    @GET
    @Path("/eventIntegrations/{name}")
    public Response getEventIntegration(@PathParam("name") String name, @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        EventIntegration integration = appIntegrationsService.getEventIntegration(name, region);
        return Response.ok(eventIntegrationNode(integration)).build();
    }

    @PATCH
    @Path("/eventIntegrations/{name}")
    public Response updateEventIntegration(@PathParam("name") String name,
                                           @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        appIntegrationsService.updateEventIntegration(name, textOrNull(request, "Description"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/eventIntegrations/{name}")
    public Response deleteEventIntegration(@PathParam("name") String name, @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        appIntegrationsService.deleteEventIntegration(name, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/eventIntegrations")
    public Response listEventIntegrations(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode integrations = response.putArray("EventIntegrations");
        for (EventIntegration integration : appIntegrationsService.listEventIntegrations(region)) {
            integrations.add(eventIntegrationNode(integration));
        }
        return Response.ok(response).build();
    }

    /**
     * Associations are created by the consuming service (Amazon Connect and friends)
     * when it binds a client to the integration. Floci has no path that creates one, so
     * an existing integration always reports an empty list; a missing one still raises
     * ResourceNotFoundException.
     */
    @GET
    @Path("/eventIntegrations/{name}/associations")
    public Response listEventIntegrationAssociations(@PathParam("name") String name,
                                                     @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        appIntegrationsService.getEventIntegration(name, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("EventIntegrationAssociations");
        return Response.ok(response).build();
    }

    // ─────────────────────── Data integrations ───────────────────────

    @POST
    @Path("/dataIntegrations")
    public Response createDataIntegration(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        DataIntegration integration = appIntegrationsService.createDataIntegration(
                textOrNull(request, "Name"),
                textOrNull(request, "Description"),
                textOrNull(request, "KmsKey"),
                textOrNull(request, "SourceURI"),
                request.get("ScheduleConfig"),
                request.get("FileConfiguration"),
                request.get("ObjectConfiguration"),
                parseTags(request.get("Tags")),
                region);

        ObjectNode response = dataIntegrationNode(integration);
        String clientToken = textOrNull(request, "ClientToken");
        if (clientToken != null) {
            response.put("ClientToken", clientToken);
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/dataIntegrations/{identifier}")
    public Response getDataIntegration(@PathParam("identifier") String identifier,
                                       @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        DataIntegration integration = appIntegrationsService.getDataIntegration(identifier, region);
        return Response.ok(dataIntegrationNode(integration)).build();
    }

    @PATCH
    @Path("/dataIntegrations/{identifier}")
    public Response updateDataIntegration(@PathParam("identifier") String identifier,
                                          @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        appIntegrationsService.updateDataIntegration(identifier, textOrNull(request, "Name"),
                textOrNull(request, "Description"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/dataIntegrations/{identifier}")
    public Response deleteDataIntegration(@PathParam("identifier") String identifier,
                                          @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        appIntegrationsService.deleteDataIntegration(identifier, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/dataIntegrations")
    public Response listDataIntegrations(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode integrations = response.putArray("DataIntegrations");
        for (DataIntegration integration : appIntegrationsService.listDataIntegrations(region)) {
            ObjectNode summary = integrations.addObject();
            summary.put("Arn", integration.getArn());
            summary.put("Name", integration.getName());
            if (integration.getSourceUri() != null) {
                summary.put("SourceURI", integration.getSourceUri());
            }
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode eventIntegrationNode(EventIntegration integration) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Name", integration.getName());
        if (integration.getDescription() != null) {
            node.put("Description", integration.getDescription());
        }
        node.put("EventIntegrationArn", integration.getEventIntegrationArn());
        node.put("EventBridgeBus", integration.getEventBridgeBus());
        node.putObject("EventFilter").put("Source", integration.getEventFilterSource());
        node.set("Tags", objectMapper.valueToTree(
                integration.getTags() != null ? integration.getTags() : Map.of()));
        return node;
    }

    private ObjectNode dataIntegrationNode(DataIntegration integration) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", integration.getArn());
        node.put("Id", integration.getId());
        node.put("Name", integration.getName());
        if (integration.getDescription() != null) {
            node.put("Description", integration.getDescription());
        }
        node.put("KmsKey", integration.getKmsKey());
        if (integration.getSourceUri() != null) {
            node.put("SourceURI", integration.getSourceUri());
        }
        setIfPresent(node, "ScheduleConfiguration", integration.getScheduleConfiguration());
        setIfPresent(node, "FileConfiguration", integration.getFileConfiguration());
        setIfPresent(node, "ObjectConfiguration", integration.getObjectConfiguration());
        node.set("Tags", objectMapper.valueToTree(
                integration.getTags() != null ? integration.getTags() : Map.of()));
        return node;
    }

    private void setIfPresent(ObjectNode node, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            node.set(field, value);
        }
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
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
