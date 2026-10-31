package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.services.ses.SesRecommendationService.Recommendation;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.ses.SesV2Json.filterValues;
import static io.github.hectorvent.floci.services.ses.SesV2Json.intMemberOrAbsent;
import static io.github.hectorvent.floci.services.ses.SesV2Json.putTimestamp;
import static io.github.hectorvent.floci.services.ses.SesV2Json.readOptionBody;
import static io.github.hectorvent.floci.services.ses.SesV2Json.stringMapMemberOrAbsent;
import static io.github.hectorvent.floci.services.ses.SesV2Json.stringMemberOrAbsent;

/** SES V2 Virtual Deliverability Manager advisor recommendations ({@code /v2/email/vdm/recommendations}). */
@Path("/v2/email/vdm/recommendations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesRecommendationController {

    /** In the model's order. */
    private static final List<String> FILTER_KEYS = List.of("TYPE", "IMPACT", "STATUS", "RESOURCE_ARN");
    private static final int FILTER_MAX_KEYS = 2;
    private static final int FILTER_VALUE_MAX = 512;

    private final SesRecommendationService recommendationService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesRecommendationController(SesRecommendationService recommendationService,
                                       RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.recommendationService = recommendationService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    public Response listRecommendations(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readOptionBody(objectMapper, body);
        SesV2RequestShapes.check("ListRecommendations", request);
        Map<String, String> requested = stringMapMemberOrAbsent(request, "Filter");
        if (requested != null) {
            String violation = SmithyValidationErrors.sizeOutOfRange("filter", requested.size(), 1, FILTER_MAX_KEYS);
            if (violation != null) {
                throw SmithyValidationErrors.badRequest(violation);
            }
        }
        Map<String, String> filter = filterValues(requested, FILTER_KEYS, FILTER_VALUE_MAX);
        Integer pageSize = intMemberOrAbsent(request, "PageSize");
        String nextToken = stringMemberOrAbsent(request, "NextToken");
        PaginatedResult<Recommendation> page = recommendationService.list(filter, pageSize, nextToken, region);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("NextToken", page.nextToken());
        ArrayNode recommendations = result.putArray("Recommendations");
        for (Recommendation recommendation : page.items()) {
            ObjectNode node = recommendations.addObject();
            node.put("ResourceArn", recommendation.resourceArn());
            node.put("Type", recommendation.type());
            node.put("Description", recommendation.description());
            node.put("Status", recommendation.status());
            putTimestamp(node, "CreatedTimestamp", recommendation.createdTimestamp());
            putTimestamp(node, "LastUpdatedTimestamp", recommendation.lastUpdatedTimestamp());
            node.put("Impact", recommendation.impact());
        }
        return Response.ok(result).build();
    }
}
