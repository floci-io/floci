package io.github.hectorvent.floci.services.resourcegroupstagging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.services.resourcegroupstagging.model.ResourceTagMapping;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.*;

@ApplicationScoped
public class ResourceGroupsTaggingJsonHandler {

    private final ResourceGroupsTaggingService service;
    private final ObjectMapper objectMapper;

    @Inject
    public ResourceGroupsTaggingJsonHandler(ResourceGroupsTaggingService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "GetResources"   -> handleGetResources(request, region);
            case "TagResources"   -> handleTagResources(request, region);
            case "UntagResources" -> handleUntagResources(request, region);
            case "GetTagKeys"     -> handleGetTagKeys(request, region);
            case "GetTagValues"   -> handleGetTagValues(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation",
                            "Operation " + action + " is not supported."))
                    .build();
        };
    }

    // ─── GetResources ──────────────────────────────────────────────────────────

    private Response handleGetResources(JsonNode request, String region) {
        List<String> arnList = toStringList(request.path("ResourceARNList"));
        List<ResourceGroupsTaggingService.TagFilter> tagFilters = parseTagFilters(request.path("TagFilters"));
        List<String> resourceTypeFilters = toStringList(request.path("ResourceTypeFilters"));
        String paginationToken = request.path("PaginationToken").asText(null);
        int resourcesPerPage = request.path("ResourcesPerPage").asInt(0);

        ResourceGroupsTaggingService.PageResult result = service.getResources(
                arnList, tagFilters, resourceTypeFilters, paginationToken, resourcesPerPage, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = objectMapper.createArrayNode();
        for (ResourceTagMapping mapping : result.items()) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("ResourceARN", mapping.getResourceArn());
            item.set("Tags", tagsToArray(mapping.getTags()));
            list.add(item);
        }
        response.set("ResourceTagMappingList", list);
        response.put("PaginationToken", result.nextPaginationToken() != null ? result.nextPaginationToken() : "");
        return Response.ok(response).build();
    }

    // ─── TagResources ──────────────────────────────────────────────────────────

    private Response handleTagResources(JsonNode request, String region) {
        List<String> arns = toStringList(request.path("ResourceARNList"));
        Map<String, String> tags = new LinkedHashMap<>();
        request.path("Tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));

        service.tagResources(arns, tags, region);

        ObjectNode response = objectMapper.createObjectNode();
        // FailedResourcesMap is empty on success
        response.set("FailedResourcesMap", objectMapper.createObjectNode());
        return Response.ok(response).build();
    }

    // ─── UntagResources ────────────────────────────────────────────────────────

    private Response handleUntagResources(JsonNode request, String region) {
        List<String> arns = toStringList(request.path("ResourceARNList"));
        List<String> tagKeys = toStringList(request.path("TagKeys"));

        service.untagResources(arns, tagKeys, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("FailedResourcesMap", objectMapper.createObjectNode());
        return Response.ok(response).build();
    }

    // ─── GetTagKeys ────────────────────────────────────────────────────────────

    private Response handleGetTagKeys(JsonNode request, String region) {
        String paginationToken = request.path("PaginationToken").asText(null);
        int maxResults = request.path("MaxResults").asInt(0);

        ResourceGroupsTaggingService.PageResult result = service.getTagKeys(paginationToken, maxResults, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode keys = objectMapper.createArrayNode();
        result.items().forEach(m -> keys.add(m.getResourceArn()));  // ARN field repurposed for key string
        response.set("TagKeys", keys);
        response.put("PaginationToken", result.nextPaginationToken() != null ? result.nextPaginationToken() : "");
        return Response.ok(response).build();
    }

    // ─── GetTagValues ──────────────────────────────────────────────────────────

    private Response handleGetTagValues(JsonNode request, String region) {
        String key = request.path("Key").asText();
        String paginationToken = request.path("PaginationToken").asText(null);
        int maxResults = request.path("MaxResults").asInt(0);

        ResourceGroupsTaggingService.PageResult result = service.getTagValues(key, paginationToken, maxResults, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode values = objectMapper.createArrayNode();
        result.items().forEach(m -> values.add(m.getResourceArn()));  // ARN field repurposed for value string
        response.set("TagValues", values);
        response.put("PaginationToken", result.nextPaginationToken() != null ? result.nextPaginationToken() : "");
        return Response.ok(response).build();
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private List<String> toStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    private List<ResourceGroupsTaggingService.TagFilter> parseTagFilters(JsonNode node) {
        List<ResourceGroupsTaggingService.TagFilter> result = new ArrayList<>();
        if (node == null || !node.isArray()) return result;
        for (JsonNode filter : node) {
            String key = filter.path("Key").asText();
            List<String> values = toStringList(filter.path("Values"));
            result.add(new ResourceGroupsTaggingService.TagFilter(key, values));
        }
        return result;
    }

    private ArrayNode tagsToArray(Map<String, String> tags) {
        ArrayNode arr = objectMapper.createArrayNode();
        tags.forEach((k, v) -> {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", k);
            tag.put("Value", v);
            arr.add(tag);
        });
        return arr;
    }
}
