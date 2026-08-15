package io.github.hectorvent.floci.services.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.connect.model.ConnectInstance;
import io.github.hectorvent.floci.services.connect.model.ConnectStorageConfig;
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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;

/**
 * Amazon Connect instance control plane REST-JSON controller.
 *
 * <p>Tag operations live on the shared {@code /tags/{resourceArn}} path and are served by
 * {@code SharedTagsController} through {@link ConnectService}'s {@code TagHandler}. Only the
 * operations declared below are served; anything else falls through to the emulator's
 * not-found handling rather than a stub success.
 */
@Path("/instance")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConnectController {

    private final ConnectService connectService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public ConnectController(ConnectService connectService, RegionResolver regionResolver,
                             ObjectMapper objectMapper) {
        this.connectService = connectService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── Instances ────────────────────────────

    @PUT
    public Response createInstance(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        ConnectInstance instance = connectService.createInstance(
                textOrNull(request, "IdentityManagementType"),
                textOrNull(request, "InstanceAlias"),
                textOrNull(request, "DirectoryId"),
                booleanOrNull(request, "InboundCallsEnabled"),
                booleanOrNull(request, "OutboundCallsEnabled"),
                parseTags(request.get("Tags")),
                region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Id", instance.getId());
        response.put("Arn", instance.getArn());
        return Response.ok(response).build();
    }

    @GET
    @Path("/{instanceId}")
    public Response describeInstance(@PathParam("instanceId") String instanceId,
                                     @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ConnectInstance instance = connectService.describeInstance(instanceId, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Instance", instanceNode(instance, true));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/{instanceId}")
    public Response deleteInstance(@PathParam("instanceId") String instanceId,
                                   @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        connectService.deleteInstance(instanceId, region);
        return Response.ok().build();
    }

    @GET
    public Response listInstances(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("InstanceSummaryList");
        for (ConnectInstance instance : connectService.listInstances(region)) {
            summaries.add(instanceNode(instance, false));
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Attributes ────────────────────────────

    @POST
    @Path("/{instanceId}/attribute/{attributeType}")
    public Response updateInstanceAttribute(@PathParam("instanceId") String instanceId,
                                            @PathParam("attributeType") String attributeType,
                                            @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        connectService.updateInstanceAttribute(instanceId, attributeType,
                textOrNull(request, "Value"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/{instanceId}/attribute/{attributeType}")
    public Response describeInstanceAttribute(@PathParam("instanceId") String instanceId,
                                              @PathParam("attributeType") String attributeType,
                                              @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        String value = connectService.describeInstanceAttribute(instanceId, attributeType, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Attribute", attributeNode(attributeType, value));
        return Response.ok(response).build();
    }

    @GET
    @Path("/{instanceId}/attributes")
    public Response listInstanceAttributes(@PathParam("instanceId") String instanceId,
                                           @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode attributes = response.putArray("Attributes");
        connectService.listInstanceAttributes(instanceId, region)
                .forEach((type, value) -> attributes.add(attributeNode(type, value)));
        return Response.ok(response).build();
    }

    // ──────────────────────── Instance storage configs ────────────────────────

    @PUT
    @Path("/{instanceId}/storage-config")
    public Response associateInstanceStorageConfig(@PathParam("instanceId") String instanceId,
                                                   @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        ConnectStorageConfig config = connectService.associateInstanceStorageConfig(instanceId,
                textOrNull(request, "ResourceType"), request.get("StorageConfig"), region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("AssociationId", config.getAssociationId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/{instanceId}/storage-config/{associationId}")
    public Response describeInstanceStorageConfig(@PathParam("instanceId") String instanceId,
                                                  @PathParam("associationId") String associationId,
                                                  @QueryParam("resourceType") String resourceType,
                                                  @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ConnectStorageConfig config = connectService.describeInstanceStorageConfig(instanceId,
                associationId, resourceType, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("StorageConfig", storageConfigNode(config));
        return Response.ok(response).build();
    }

    @POST
    @Path("/{instanceId}/storage-config/{associationId}")
    public Response updateInstanceStorageConfig(@PathParam("instanceId") String instanceId,
                                                @PathParam("associationId") String associationId,
                                                @QueryParam("resourceType") String resourceType,
                                                @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        connectService.updateInstanceStorageConfig(instanceId, associationId, resourceType,
                request.get("StorageConfig"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/{instanceId}/storage-config/{associationId}")
    public Response disassociateInstanceStorageConfig(@PathParam("instanceId") String instanceId,
                                                      @PathParam("associationId") String associationId,
                                                      @QueryParam("resourceType") String resourceType,
                                                      @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        connectService.disassociateInstanceStorageConfig(instanceId, associationId, resourceType, region);
        return Response.ok().build();
    }

    @GET
    @Path("/{instanceId}/storage-configs")
    public Response listInstanceStorageConfigs(@PathParam("instanceId") String instanceId,
                                               @QueryParam("resourceType") String resourceType,
                                               @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode configs = response.putArray("StorageConfigs");
        for (ConnectStorageConfig config
                : connectService.listInstanceStorageConfigs(instanceId, resourceType, region)) {
            configs.add(storageConfigNode(config));
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    /**
     * {@code Instance} carries {@code Tags}; {@code InstanceSummary} does not — see the
     * AWS model. Everything else is shared between the two shapes.
     */
    private ObjectNode instanceNode(ConnectInstance instance, boolean includeTags) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", instance.getId());
        node.put("Arn", instance.getArn());
        node.put("IdentityManagementType", instance.getIdentityManagementType());
        if (instance.getInstanceAlias() != null) {
            node.put("InstanceAlias", instance.getInstanceAlias());
        }
        node.put("CreatedTime", instance.getCreatedTime().getEpochSecond());
        node.put("ServiceRole", instance.getServiceRole());
        node.put("InstanceStatus", ConnectService.ACTIVE);
        node.put("InboundCallsEnabled", instance.isInboundCallsEnabled());
        node.put("OutboundCallsEnabled", instance.isOutboundCallsEnabled());
        if (instance.getInstanceAccessUrl() != null) {
            node.put("InstanceAccessUrl", instance.getInstanceAccessUrl());
        }
        if (includeTags) {
            node.set("Tags", objectMapper.valueToTree(
                    instance.getTags() != null ? instance.getTags() : Map.of()));
        }
        return node;
    }

    private ObjectNode attributeNode(String attributeType, String value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AttributeType", attributeType);
        node.put("Value", value);
        return node;
    }

    private ObjectNode storageConfigNode(ConnectStorageConfig config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("AssociationId", config.getAssociationId());
        node.put("StorageType", config.getStorageType());
        config.getStorageConfig().fields().forEachRemaining(entry -> {
            if (!"AssociationId".equals(entry.getKey()) && !"StorageType".equals(entry.getKey())) {
                node.set(entry.getKey(), entry.getValue());
            }
        });
        return node;
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

    private Boolean booleanOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asBoolean();
    }

    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new HashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }
}
