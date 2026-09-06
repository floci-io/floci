package io.github.hectorvent.floci.services.accessanalyzer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.accessanalyzer.model.Analyzer;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccessAnalyzerController {
    private final AccessAnalyzerService accessAnalyzerService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public AccessAnalyzerController(AccessAnalyzerService accessAnalyzerService,
                                    RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.accessAnalyzerService = accessAnalyzerService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/analyzer")
    public Response listAnalyzers(@Context HttpHeaders headers,
                                  @QueryParam("type") String type,
                                  @QueryParam("maxResults") String maxResults,
                                  @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        PaginatedResult<Analyzer> page = accessAnalyzerService.listAnalyzers(
                region, type, Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("analyzers");
        page.items().forEach(analyzer -> items.add(analyzerSummary(analyzer)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @PUT
    @Path("/analyzer")
    public Response createAnalyzer(@Context HttpHeaders headers, String body) {
        Analyzer analyzer = accessAnalyzerService.createAnalyzer(readTree(body), regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", analyzer.getArn());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/analyzer/{analyzerName}")
    public Response deleteAnalyzer(@Context HttpHeaders headers, @PathParam("analyzerName") String analyzerName) {
        accessAnalyzerService.deleteAnalyzer(regionResolver.resolveRegion(headers), analyzerName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode analyzerSummary(Analyzer analyzer) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", analyzer.getArn());
        node.put("name", analyzer.getName());
        node.put("type", analyzer.getType());
        node.put("status", analyzer.getStatus());
        node.put("createdAt", analyzer.getCreatedAt());
        if (analyzer.getConfiguration() != null) {
            node.set("configuration", analyzer.getConfiguration());
        }
        node.set("tags", objectMapper.valueToTree(analyzer.getTags()));
        return node;
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
