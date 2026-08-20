package io.github.hectorvent.floci.services.accessanalyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.accessanalyzer.model.Analyzer;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IAM Access Analyzer's REST-JSON surface (backs {@code aws_accessanalyzer_analyzer},
 * lex00/floci#75). Tagging (POST/GET/DELETE {@code /tags/{resourceArn}}) is deliberately NOT
 * routed here — {@link AccessAnalyzerTagHandler} registers with the generic
 * {@code SharedTagsController}, which dispatches those three verbs by the ARN's service
 * segment for every service that uses the standard tag contract, AccessAnalyzer included.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccessAnalyzerController {

    private static final Logger LOG = Logger.getLogger(AccessAnalyzerController.class);

    private final AccessAnalyzerService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public AccessAnalyzerController(AccessAnalyzerService service, RegionResolver regionResolver,
                                     ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @PUT
    @Path("/analyzer")
    public Response createAnalyzer(@Context HttpHeaders headers, String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        String name = textOrNull(req, "analyzerName");
        String type = textOrNull(req, "type");
        Map<String, String> tags = readStringMap(req, "tags");

        Analyzer analyzer = service.createAnalyzer(name, type, tags, region);

        ObjectNode out = objectMapper.createObjectNode();
        out.put("arn", analyzer.getArn());
        return Response.ok(out).build();
    }

    @GET
    @Path("/analyzer/{analyzerName}")
    public Response getAnalyzer(@Context HttpHeaders headers,
                                 @PathParam("analyzerName") String analyzerName) {
        String region = regionResolver.resolveRegion(headers);
        Analyzer analyzer = service.getAnalyzer(analyzerName, region);
        ObjectNode out = objectMapper.createObjectNode();
        out.set("analyzer", objectMapper.valueToTree(analyzer));
        return Response.ok(out).build();
    }

    @GET
    @Path("/analyzer")
    public Response listAnalyzers(@Context HttpHeaders headers, @QueryParam("type") String type) {
        String region = regionResolver.resolveRegion(headers);
        List<Analyzer> analyzers = service.listAnalyzers(region, type);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode list = out.putArray("analyzers");
        analyzers.forEach(list::addPOJO);
        return Response.ok(out).build();
    }

    @DELETE
    @Path("/analyzer/{analyzerName}")
    public Response deleteAnalyzer(@Context HttpHeaders headers,
                                    @PathParam("analyzerName") String analyzerName) {
        String region = regionResolver.resolveRegion(headers);
        service.deleteAnalyzer(analyzerName, region);
        ObjectNode out = objectMapper.createObjectNode();
        return Response.ok(out).build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readStringMap(JsonNode node, String field) {
        JsonNode mapNode = node.path(field);
        if (mapNode.isMissingNode() || mapNode.isNull()) {
            return new HashMap<>();
        }
        return objectMapper.convertValue(mapNode, Map.class);
    }
}
