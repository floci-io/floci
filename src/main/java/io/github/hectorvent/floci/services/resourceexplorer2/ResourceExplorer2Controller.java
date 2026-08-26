package io.github.hectorvent.floci.services.resourceexplorer2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.resourceexplorer2.model.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ResourceExplorer2Controller {

    /**
     * Per-operation MaxResults ceilings. AWS models a different maximum for each of these, and a
     * value above it is a ValidationException — so a single shared constant would either reject
     * page sizes AWS accepts or accept ones it rejects.
     */
    private static final int MAX_RESULTS_RESOURCES = 1000;
    private static final int MAX_RESULTS_INDEXES = 100;
    private static final int MAX_RESULTS_VIEWS = 50;
    private static final int MAX_RESULTS_MEMBER_INDEXES = 10;

    private final ResourceExplorer2Service service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public ResourceExplorer2Controller(ResourceExplorer2Service service,
                                        RegionResolver regionResolver,
                                        ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/re2/ListResources")
    public Response listResources(@Context HttpHeaders headers, String body) throws IOException {
        JsonNode req = parseBody(body);
        String region = regionResolver.resolveRegion(headers);
        String filterString = req.has("Filters") && req.get("Filters").has("FilterString")
                ? req.get("Filters").get("FilterString").asText(null) : null;
        Integer maxResults = validatedMaxResults(req, MAX_RESULTS_RESOURCES);
        String nextToken = optString(req, "NextToken");
        String viewArn = optString(req, "ViewArn");
        try {
            return Response.ok(service.listResources(filterString, maxResults, nextToken, viewArn, region)).build();
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/Search")
    public Response search(@Context HttpHeaders headers, String body) throws IOException {
        JsonNode req = parseBody(body);
        if (!req.has("QueryString")) {
            throw new AwsException("ValidationException", "QueryString is required", 400);
        }
        String queryString = req.get("QueryString").asText(null);
        Integer maxResults = validatedMaxResults(req, MAX_RESULTS_RESOURCES);
        String nextToken = optString(req, "NextToken");
        String viewArn = optString(req, "ViewArn");
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.search(queryString, maxResults, nextToken, viewArn, region)).build();
    }

    @POST
    @Path("/ListSupportedResourceTypes")
    public Response listSupportedResourceTypes(String body) throws IOException {
        JsonNode req = parseBody(body);
        Integer maxResults = validatedMaxResults(req, MAX_RESULTS_RESOURCES);
        String nextToken = optString(req, "NextToken");
        return Response.ok(service.listSupportedResourceTypes(maxResults, nextToken)).build();
    }

    @POST
    @Path("/re2/CreateIndex")
    public Response createIndex(@Context HttpHeaders headers, String body) throws IOException {
        JsonNode req = parseBody(body);
        String region = regionResolver.resolveRegion(headers);
        Map<String, String> tags = parseTags(req);
        Index index = service.createIndex(region, tags);
        var result = objectMapper.createObjectNode();
        result.put("Arn", index.arn());
        result.put("CreatedAt", index.createdAt().toString());
        // Real AWS returns CREATING immediately; we store ACTIVE synchronously but the
        // caller must see CREATING as the initial state per the AWS API contract.
        result.put("State", "CREATING");
        return Response.ok(result).build();
    }

    @POST
    @Path("/re2/GetIndex")
    public Response getIndex(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        Index index = service.getIndex(region);
        var result = objectMapper.createObjectNode();
        result.put("Arn", index.arn());
        result.put("CreatedAt", index.createdAt().toString());
        result.put("LastUpdatedAt", index.lastUpdatedAt().toString());
        result.set("ReplicatingFrom", objectMapper.valueToTree(index.replicatingFrom()));
        result.set("ReplicatingTo", objectMapper.valueToTree(index.replicatingTo()));
        result.put("State", index.state().name());
        result.set("Tags", objectMapper.valueToTree(index.tags()));
        result.put("Type", index.type().name());
        return Response.ok(result).build();
    }

    @POST
    @Path("/re2/DeleteIndex")
    public Response deleteIndex(String body) throws IOException {
        JsonNode req = parseBody(body);
        String indexArn = optString(req, "Arn");
        if (indexArn == null) {
            throw new AwsException("ValidationException", "Arn is required", 400);
        }
        Index deleted = service.deleteIndex(indexArn);
        var result = objectMapper.createObjectNode();
        result.put("Arn", deleted.arn());
        result.put("LastUpdatedAt", deleted.lastUpdatedAt().toString());
        result.put("State", deleted.state().name());
        return Response.ok(result).build();
    }

    @POST
    @Path("/re2/ListIndexes")
    public Response listIndexes(String body) throws IOException {
        JsonNode req = parseBody(body);
        String type = optString(req, "Type");
        List<String> regions = stringArray(req, "Regions");
        Integer maxResults = validatedMaxResults(req, MAX_RESULTS_INDEXES);
        String nextToken = optString(req, "NextToken");
        return Response.ok(service.listIndexes(type, regions, maxResults, nextToken)).build();
    }

    @POST
    @Path("/UpdateIndexType")
    public Response updateIndexType(String body) throws IOException {
        JsonNode req = parseBody(body);
        String arn = optString(req, "Arn");
        if (arn == null) {
            throw new AwsException("ValidationException", "Arn is required", 400);
        }
        if (!req.has("Type")) {
            throw new AwsException("ValidationException", "Type is required", 400);
        }
        IndexType type;
        try {
            type = IndexType.valueOf(req.get("Type").asText());
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationException", "Invalid index type: " + req.get("Type").asText(), 400);
        }
        Index index = service.updateIndexType(arn, type);
        var result = objectMapper.createObjectNode();
        result.put("Arn", index.arn());
        result.put("LastUpdatedAt", index.lastUpdatedAt().toString());
        result.put("State", index.state().name());
        result.put("Type", index.type().name());
        return Response.ok(result).build();
    }

    @POST
    @Path("/CreateView")
    public Response createView(@Context HttpHeaders headers, String body) throws IOException {
        JsonNode req = parseBody(body);
        String region = regionResolver.resolveRegion(headers);
        String viewName = optString(req, "ViewName");
        String scope = optString(req, "Scope");
        SearchFilter filters = parseFilters(req);
        List<IncludedProperty> includedProperties = parseIncludedProperties(req);
        Map<String, String> tags = parseTags(req);
        View view = service.createView(region, viewName, scope, filters, includedProperties, tags);
        var result = objectMapper.createObjectNode();
        result.set("View", service.buildViewNode(view));
        return Response.ok(result).build();
    }

    @POST
    @Path("/GetView")
    public Response getView(String body) throws IOException {
        JsonNode req = parseBody(body);
        String viewArn = optString(req, "ViewArn");
        if (viewArn == null) {
            throw new AwsException("ValidationException", "ViewArn is required", 400);
        }
        View view = service.getView(viewArn);
        var result = objectMapper.createObjectNode();
        result.set("View", service.buildViewNode(view));
        result.set("Tags", objectMapper.valueToTree(
                view.tags() != null ? view.tags() : Map.of()));
        return Response.ok(result).build();
    }

    @POST
    @Path("/DeleteView")
    public Response deleteView(String body) throws IOException {
        JsonNode req = parseBody(body);
        String viewArn = optString(req, "ViewArn");
        if (viewArn == null) {
            throw new AwsException("ValidationException", "ViewArn is required", 400);
        }
        service.deleteView(viewArn);
        var result = objectMapper.createObjectNode();
        result.put("ViewArn", viewArn);
        return Response.ok(result).build();
    }

    @POST
    @Path("/UpdateView")
    public Response updateView(String body) throws IOException {
        JsonNode req = parseBody(body);
        String viewArn = optString(req, "ViewArn");
        if (viewArn == null) {
            throw new AwsException("ValidationException", "ViewArn is required", 400);
        }
        SearchFilter filters = parseFilters(req);
        List<IncludedProperty> includedProperties = parseIncludedProperties(req);
        View view = service.updateView(viewArn, filters, includedProperties);
        var result = objectMapper.createObjectNode();
        result.set("View", service.buildViewNode(view));
        return Response.ok(result).build();
    }

    @POST
    @Path("/ListViews")
    public Response listViews(@Context HttpHeaders headers, String body) throws IOException {
        JsonNode req = parseBody(body);
        Integer maxResults = validatedMaxResults(req, MAX_RESULTS_VIEWS);
        String nextToken = optString(req, "NextToken");
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(service.listViews(region, maxResults, nextToken)).build();
    }

    @POST
    @Path("/BatchGetView")
    public Response batchGetView(String body) throws IOException {
        JsonNode req = parseBody(body);
        return Response.ok(service.batchGetView(stringArray(req, "ViewArns"))).build();
    }

    @POST
    @Path("/AssociateDefaultView")
    public Response associateDefaultView(@Context HttpHeaders headers, String body) throws IOException {
        JsonNode req = parseBody(body);
        String viewArn = optString(req, "ViewArn");
        if (viewArn == null) {
            throw new AwsException("ValidationException", "ViewArn is required", 400);
        }
        service.associateDefaultView(regionResolver.resolveRegion(headers), viewArn);
        var result = objectMapper.createObjectNode();
        result.put("ViewArn", viewArn);
        return Response.ok(result).build();
    }

    @POST
    @Path("/DisassociateDefaultView")
    public Response disassociateDefaultView(@Context HttpHeaders headers) {
        service.disassociateDefaultView(regionResolver.resolveRegion(headers));
        return Response.ok("{}").build();
    }

    @POST
    @Path("/GetDefaultView")
    public Response getDefaultView(@Context HttpHeaders headers) {
        String viewArn = service.getDefaultView(regionResolver.resolveRegion(headers));
        var result = objectMapper.createObjectNode();
        if (viewArn != null) {
            result.put("ViewArn", viewArn);
        }
        return Response.ok(result).build();
    }


    @POST
    @Path("/GetAccountLevelServiceConfiguration")
    public Response getAccountLevelServiceConfiguration() {
        return Response.ok(service.getAccountLevelServiceConfiguration()).build();
    }

    @POST
    @Path("/ListIndexesForMembers")
    public Response listIndexesForMembers(String body) throws IOException {
        JsonNode req = parseBody(body);
        List<String> accountIds = stringArray(req, "AccountIdList");
        if (accountIds.isEmpty()) {
            throw new AwsException("ValidationException", "AccountIdList is required", 400);
        }
        Integer maxResults = validatedMaxResults(req, MAX_RESULTS_MEMBER_INDEXES);
        String nextToken = optString(req, "NextToken");
        return Response.ok(service.listIndexesForMembers(accountIds, maxResults, nextToken)).build();
    }

    @POST
    @Path("/ListManagedViews")
    public Response listManagedViews(String body) throws IOException {
        JsonNode req = parseBody(body);
        Integer maxResults = validatedMaxResults(req, MAX_RESULTS_VIEWS);
        String nextToken = optString(req, "NextToken");
        return Response.ok(service.listManagedViews(maxResults, nextToken)).build();
    }

    @POST
    @Path("/GetManagedView")
    public Response getManagedView(String body) throws IOException {
        JsonNode req = parseBody(body);
        String managedViewArn = optString(req, "ManagedViewArn");
        if (managedViewArn == null) {
            throw new AwsException("ValidationException", "ManagedViewArn is required", 400);
        }
        return Response.ok(service.getManagedView(managedViewArn)).build();
    }

    @POST
    @Path("/ListServiceViews")
    public Response listServiceViews(String body) throws IOException {
        JsonNode req = parseBody(body);
        Integer maxResults = validatedMaxResults(req, MAX_RESULTS_VIEWS);
        String nextToken = optString(req, "NextToken");
        return Response.ok(service.listServiceViews(maxResults, nextToken)).build();
    }

    @POST
    @Path("/GetServiceView")
    public Response getServiceView(String body) throws IOException {
        JsonNode req = parseBody(body);
        String serviceViewArn = optString(req, "ServiceViewArn");
        if (serviceViewArn == null) {
            throw new AwsException("ValidationException", "ServiceViewArn is required", 400);
        }
        return Response.ok(service.getServiceView(serviceViewArn)).build();
    }

    @POST
    @Path("/ListServiceIndexes")
    public Response listServiceIndexes(String body) throws IOException {
        JsonNode req = parseBody(body);
        List<String> regions = stringArray(req, "Regions");
        Integer maxResults = validatedMaxResults(req, MAX_RESULTS_INDEXES);
        String nextToken = optString(req, "NextToken");
        return Response.ok(service.listServiceIndexes(regions, maxResults, nextToken)).build();
    }

    @POST
    @Path("/GetServiceIndex")
    public Response getServiceIndex(@Context HttpHeaders headers) {
        return Response.ok(service.getServiceIndex(regionResolver.resolveRegion(headers))).build();
    }

    @POST
    @Path("/ListStreamingAccessForServices")
    public Response listStreamingAccessForServices(String body) throws IOException {
        JsonNode req = parseBody(body);
        Integer maxResults = validatedMaxResults(req, MAX_RESULTS_VIEWS);
        String nextToken = optString(req, "NextToken");
        return Response.ok(service.listStreamingAccessForServices(maxResults, nextToken)).build();
    }

    @POST
    @Path("/CreateResourceExplorerSetup")
    public Response createResourceExplorerSetup(String body) throws IOException {
        JsonNode req = parseBody(body);
        String taskId = service.createResourceExplorerSetup(
                stringArray(req, "RegionList"),
                optString(req, "ViewName"),
                stringArray(req, "AggregatorRegions"));
        var result = objectMapper.createObjectNode();
        result.put("TaskId", taskId);
        return Response.ok(result).build();
    }

    @POST
    @Path("/DeleteResourceExplorerSetup")
    public Response deleteResourceExplorerSetup(String body) throws IOException {
        JsonNode req = parseBody(body);
        Boolean deleteInAllRegions = req.has("DeleteInAllRegions")
                ? req.get("DeleteInAllRegions").asBoolean() : null;
        String taskId = service.deleteResourceExplorerSetup(
                deleteInAllRegions, stringArray(req, "RegionList"));
        var result = objectMapper.createObjectNode();
        result.put("TaskId", taskId);
        return Response.ok(result).build();
    }

    @POST
    @Path("/GetResourceExplorerSetup")
    public Response getResourceExplorerSetup(String body) throws IOException {
        JsonNode req = parseBody(body);
        String taskId = optString(req, "TaskId");
        if (taskId == null) {
            throw new AwsException("ValidationException", "TaskId is required", 400);
        }
        Integer maxResults = validatedMaxResults(req, MAX_RESULTS_INDEXES);
        String nextToken = optString(req, "NextToken");
        return Response.ok(service.getResourceExplorerSetup(taskId, maxResults, nextToken)).build();
    }

    private static List<String> stringArray(JsonNode req, String field) {
        List<String> values = new ArrayList<>();
        if (req.has(field) && req.get(field).isArray()) {
            for (JsonNode value : req.get(field)) {
                values.add(value.asText());
            }
        }
        return values;
    }

    private static String optString(JsonNode req, String field) {
        return req.has(field) ? req.get(field).asText(null) : null;
    }

    private JsonNode parseBody(String body) throws IOException {
        return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
    }

    private Map<String, String> parseTags(JsonNode req) {
        Map<String, String> tags = new HashMap<>();
        if (req.has("Tags") && req.get("Tags").isObject()) {
            req.get("Tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }

    private SearchFilter parseFilters(JsonNode req) {
        return req.has("Filters") && req.get("Filters").has("FilterString")
                ? new SearchFilter(req.get("Filters").get("FilterString").asText()) : null;
    }

    private List<IncludedProperty> parseIncludedProperties(JsonNode req) {
        List<IncludedProperty> includedProperties = new ArrayList<>();
        if (req.has("IncludedProperties") && req.get("IncludedProperties").isArray()) {
            for (var p : req.get("IncludedProperties")) {
                includedProperties.add(new IncludedProperty(p.has("Name") ? p.get("Name").asText() : ""));
            }
        }
        return includedProperties;
    }

    private Integer validatedMaxResults(JsonNode req, int max) {
        if (!req.has("MaxResults")) {
            return null;
        }
        int value = req.get("MaxResults").intValue();
        if (value < 1 || value > max) {
            throw new AwsException("ValidationException", "MaxResults must be between 1 and " + max, 400);
        }
        return value;
    }

}
