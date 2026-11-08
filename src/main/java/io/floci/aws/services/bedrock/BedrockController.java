package io.floci.aws.services.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.JsonErrorResponseUtils;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.bedrock.model.Guardrail;
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

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Amazon Bedrock control plane REST-JSON controller.
 *
 * <p>Only the operations declared below are served. The data plane
 * ({@code /model/{modelId}/...}) belongs to the separate bedrock-runtime service,
 * which signs under the same {@code bedrock} name.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockController {

    private final BedrockService bedrockService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockController(BedrockService bedrockService, RegionResolver regionResolver,
                             ObjectMapper objectMapper) {
        this.bedrockService = bedrockService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // Guardrails

    @POST
    @Path("/guardrails")
    public Response createGuardrail(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        Guardrail guardrail = bedrockService.createGuardrail(readTree(body), region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("guardrailId", guardrail.getGuardrailId());
        response.put("guardrailArn", guardrail.getGuardrailArn());
        response.put("version", guardrail.getVersion());
        response.put("createdAt", iso8601(guardrail.getCreatedAt()));
        return Response.status(202).entity(response).build();
    }

    @GET
    @Path("/guardrails/{guardrailIdentifier}")
    public Response getGuardrail(@PathParam("guardrailIdentifier") String guardrailIdentifier,
                                 @QueryParam("guardrailVersion") String guardrailVersion,
                                 @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        Guardrail guardrail = bedrockService.getGuardrail(guardrailIdentifier, guardrailVersion, region);
        return Response.ok(guardrailNode(guardrail)).build();
    }

    @PUT
    @Path("/guardrails/{guardrailIdentifier}")
    public Response updateGuardrail(@PathParam("guardrailIdentifier") String guardrailIdentifier,
                                    @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        Guardrail guardrail = bedrockService.updateGuardrail(guardrailIdentifier, readTree(body), region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("guardrailId", guardrail.getGuardrailId());
        response.put("guardrailArn", guardrail.getGuardrailArn());
        response.put("version", guardrail.getVersion());
        response.put("updatedAt", iso8601(guardrail.getUpdatedAt()));
        return Response.status(202).entity(response).build();
    }

    @POST
    @Path("/guardrails/{guardrailIdentifier}")
    public Response createGuardrailVersion(@PathParam("guardrailIdentifier") String guardrailIdentifier,
                                           @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        Guardrail version = bedrockService.createGuardrailVersion(guardrailIdentifier,
                textOrNull(request, "description"), region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("guardrailId", version.getGuardrailId());
        response.put("version", version.getVersion());
        return Response.status(202).entity(response).build();
    }

    @DELETE
    @Path("/guardrails/{guardrailIdentifier}")
    public Response deleteGuardrail(@PathParam("guardrailIdentifier") String guardrailIdentifier,
                                    @QueryParam("guardrailVersion") String guardrailVersion,
                                    @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        bedrockService.deleteGuardrail(guardrailIdentifier, guardrailVersion, region);
        return Response.status(202).build();
    }

    @GET
    @Path("/guardrails")
    public Response listGuardrails(@QueryParam("guardrailIdentifier") String guardrailIdentifier,
                                   @QueryParam("maxResults") String maxResults,
                                   @QueryParam("nextToken") String nextToken,
                                   @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        PaginatedResult<Guardrail> page = bedrockService.listGuardrails(guardrailIdentifier,
                Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("guardrails");
        for (Guardrail guardrail : page.items()) {
            summaries.add(guardrailSummaryNode(guardrail));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    // Foundation models and inference profiles

    @GET
    @Path("/foundation-models")
    public Response listFoundationModels(@QueryParam("byProvider") String byProvider,
                                         @QueryParam("byOutputModality") String byOutputModality,
                                         @QueryParam("byInferenceType") String byInferenceType,
                                         @QueryParam("byCustomizationType") String byCustomizationType,
                                         @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("modelSummaries");
        for (BedrockModelCatalog.FoundationModel model : bedrockService.listFoundationModels(byProvider,
                byOutputModality, byInferenceType, byCustomizationType, region)) {
            summaries.add(foundationModelNode(model, region));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/foundation-models/{modelIdentifier: .+}")
    public Response getFoundationModel(@PathParam("modelIdentifier") String modelIdentifier,
                                       @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("modelDetails", foundationModelNode(bedrockService.getFoundationModel(modelIdentifier, region),
                region));
        return Response.ok(response).build();
    }

    @GET
    @Path("/inference-profiles")
    public Response listInferenceProfiles(@QueryParam("type") String typeEquals,
                                          @QueryParam("maxResults") String maxResults,
                                          @QueryParam("nextToken") String nextToken,
                                          @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        PaginatedResult<BedrockModelCatalog.InferenceProfile> page = bedrockService.listInferenceProfiles(typeEquals,
                Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("inferenceProfileSummaries");
        for (BedrockModelCatalog.InferenceProfile profile : page.items()) {
            summaries.add(inferenceProfileNode(profile, region));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/inference-profiles/{inferenceProfileIdentifier: .+}")
    public Response getInferenceProfile(@PathParam("inferenceProfileIdentifier") String inferenceProfileIdentifier,
                                        @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(inferenceProfileNode(
                bedrockService.getInferenceProfile(inferenceProfileIdentifier, region), region)).build();
    }

    // Resource policies

    @POST
    @Path("/resource-policy")
    public Response putResourcePolicy(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        String resourceArn = textOrNull(request, "resourceArn");
        bedrockService.putResourcePolicy(resourceArn, textOrNull(request, "resourcePolicy"), region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("resourceArn", resourceArn);
        return Response.status(201).entity(response).build();
    }

    @GET
    @Path("/resource-policy/{resourceArn: .+}")
    public Response getResourcePolicy(@PathParam("resourceArn") String resourceArn, @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        String policy = bedrockService.getResourcePolicy(resourceArn, region);

        ObjectNode response = objectMapper.createObjectNode();
        if (policy != null) {
            response.put("resourcePolicy", policy);
        }
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/resource-policy/{resourceArn: .+}")
    public Response deleteResourcePolicy(@PathParam("resourceArn") String resourceArn, @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        bedrockService.deleteResourcePolicy(resourceArn, region);
        return Response.ok().build();
    }

    // Tags

    @POST
    @Path("/tagResource")
    public Response tagResource(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        bedrockService.tagResource(textOrNull(request, "resourceARN"),
                BedrockService.parseTagList(request.get("tags")), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/untagResource")
    public Response untagResource(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        List<String> tagKeys = new ArrayList<>();
        JsonNode keys = request.get("tagKeys");
        if (keys != null && keys.isArray()) {
            keys.forEach(key -> tagKeys.add(key.asText()));
        }
        bedrockService.untagResource(textOrNull(request, "resourceARN"), tagKeys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/listTagsForResource")
    public Response listTagsForResource(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        Map<String, String> tags = bedrockService.listTags(textOrNull(request, "resourceARN"), region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("tags");
        tags.forEach((key, value) -> {
            ObjectNode tag = array.addObject();
            tag.put("key", key);
            tag.put("value", value);
        });
        return Response.ok(response).build();
    }

    // Helpers

    private ObjectNode guardrailNode(Guardrail guardrail) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", guardrail.getName());
        if (guardrail.getDescription() != null) {
            node.put("description", guardrail.getDescription());
        }
        node.put("guardrailId", guardrail.getGuardrailId());
        node.put("guardrailArn", guardrail.getGuardrailArn());
        node.put("version", guardrail.getVersion());
        node.put("status", BedrockService.READY);
        setIfPresent(node, "topicPolicy", guardrail.getTopicPolicy());
        setIfPresent(node, "contentPolicy", guardrail.getContentPolicy());
        setIfPresent(node, "wordPolicy", guardrail.getWordPolicy());
        setIfPresent(node, "sensitiveInformationPolicy", guardrail.getSensitiveInformationPolicy());
        setIfPresent(node, "contextualGroundingPolicy", guardrail.getContextualGroundingPolicy());
        setIfPresent(node, "automatedReasoningPolicy", guardrail.getAutomatedReasoningPolicy());
        setIfPresent(node, "crossRegionDetails", guardrail.getCrossRegionDetails());
        node.put("createdAt", iso8601(guardrail.getCreatedAt()));
        node.put("updatedAt", iso8601(guardrail.getUpdatedAt()));
        node.put("blockedInputMessaging", guardrail.getBlockedInputMessaging());
        node.put("blockedOutputsMessaging", guardrail.getBlockedOutputsMessaging());
        if (guardrail.getKmsKeyArn() != null) {
            node.put("kmsKeyArn", guardrail.getKmsKeyArn());
        }
        return node;
    }

    private ObjectNode foundationModelNode(BedrockModelCatalog.FoundationModel model, String region) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("modelArn", foundationModelArn(model.modelId(), region));
        node.put("modelId", model.modelId());
        node.put("modelName", model.modelName());
        node.put("providerName", model.providerName());
        model.inputModalities().forEach(node.putArray("inputModalities")::add);
        model.outputModalities().forEach(node.putArray("outputModalities")::add);
        node.put("responseStreamingSupported", model.responseStreamingSupported());
        node.putArray("customizationsSupported");
        ArrayNode inferenceTypes = node.putArray("inferenceTypesSupported");
        if (model.servedInRegion(region)) {
            inferenceTypes.add("ON_DEMAND");
        }
        node.putObject("modelLifecycle").put("status", model.lifecycleStatus());
        return node;
    }

    private ObjectNode inferenceProfileNode(BedrockModelCatalog.InferenceProfile profile, String region) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("inferenceProfileName", profile.inferenceProfileName());
        node.put("inferenceProfileArn", regionResolver.buildArn("bedrock", region,
                "inference-profile/" + profile.inferenceProfileId()));
        node.put("inferenceProfileId", profile.inferenceProfileId());
        node.put("createdAt", iso8601(profile.createdAt()));
        node.put("updatedAt", iso8601(profile.createdAt()));
        ArrayNode models = node.putArray("models");
        for (String destination : profile.routes().get(region)) {
            models.addObject().put("modelArn", foundationModelArn(profile.modelId(), destination));
        }
        node.put("status", "ACTIVE");
        node.put("type", "SYSTEM_DEFINED");
        return node;
    }

    private String foundationModelArn(String modelId, String region) {
        return regionResolver.buildArn("bedrock", region, "", "foundation-model/" + modelId);
    }

    private ObjectNode guardrailSummaryNode(Guardrail guardrail) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", guardrail.getGuardrailId());
        node.put("arn", guardrail.getGuardrailArn());
        node.put("status", BedrockService.READY);
        node.put("name", guardrail.getName());
        if (guardrail.getDescription() != null) {
            node.put("description", guardrail.getDescription());
        }
        node.put("version", guardrail.getVersion());
        node.put("createdAt", iso8601(guardrail.getCreatedAt()));
        node.put("updatedAt", iso8601(guardrail.getUpdatedAt()));
        setIfPresent(node, "crossRegionDetails", guardrail.getCrossRegionDetails());
        return node;
    }

    private void setIfPresent(ObjectNode node, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            node.set(field, value);
        }
    }

    private String iso8601(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant);
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
}
