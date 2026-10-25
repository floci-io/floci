package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.SesReputationEntityService.ReputationEntity;
import io.github.hectorvent.floci.services.ses.model.ReputationStatusRecord;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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

/**
 * SES V2 reputation entity endpoints ({@code /v2/email/reputation/entities}). The reference is an
 * ARN, which the SDKs send percent-encoded as one path segment. The router matches the encoded
 * path, so the reference stays one segment there and is decoded only as a parameter; a reference
 * sent with literal slashes reaches no route, as on SES, and so cannot pass the IAM route rules
 * as an unknown action.
 */
@Path("/v2/email/reputation/entities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesReputationEntityController {

    /** In the order SES prints them when it refuses a key. */
    private static final List<String> FILTER_KEYS =
            List.of("ENTITY_REFERENCE_PREFIX", "REPUTATION_IMPACT", "ENTITY_TYPE", "SENDING_STATUS");
    private static final int FILTER_VALUE_MAX = 512;

    private final SesReputationEntityService reputationService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesReputationEntityController(SesReputationEntityService reputationService,
                                         RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.reputationService = reputationService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    public Response listReputationEntities(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readOptionBody(objectMapper, body);
        SesV2RequestShapes.check("ListReputationEntities", request);
        Map<String, String> filter = filterValues(stringMapMemberOrAbsent(request, "Filter"), FILTER_KEYS,
                FILTER_VALUE_MAX);
        Integer pageSize = intMemberOrAbsent(request, "PageSize");
        String nextToken = stringMemberOrAbsent(request, "NextToken");
        PaginatedResult<ReputationEntity> page = reputationService.list(filter, pageSize, nextToken, region);
        ObjectNode result = objectMapper.createObjectNode();
        result.put("NextToken", page.nextToken());
        ArrayNode entities = result.putArray("ReputationEntities");
        for (ReputationEntity entity : page.items()) {
            entities.add(entityJson(entity));
        }
        return Response.ok(result).build();
    }

    @GET
    @Path("/{type}/{reference}")
    public Response getReputationEntity(@Context HttpHeaders headers, @PathParam("type") String type,
                                        @PathParam("reference") String reference) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode result = objectMapper.createObjectNode();
        result.set("ReputationEntity", entityJson(reputationService.get(type, reference, region)));
        return Response.ok(result).build();
    }

    @PUT
    @Path("/{type}/{reference}/policy")
    public Response updateReputationEntityPolicy(@Context HttpHeaders headers, @PathParam("type") String type,
                                                 @PathParam("reference") String reference, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readOptionBody(objectMapper, body);
        SesV2RequestShapes.check("UpdateReputationEntityPolicy", request);
        String policy = stringMemberOrAbsent(request, "ReputationEntityPolicy");
        reputationService.updatePolicy(type, reference, policy, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @PUT
    @Path("/{type}/{reference}/customer-managed-status")
    public Response updateReputationEntityCustomerManagedStatus(@Context HttpHeaders headers,
                                                                @PathParam("type") String type,
                                                                @PathParam("reference") String reference,
                                                                String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readOptionBody(objectMapper, body);
        SesV2RequestShapes.check("UpdateReputationEntityCustomerManagedStatus", request);
        String status = stringMemberOrAbsent(request, "SendingStatus");
        reputationService.updateCustomerManagedStatus(type, reference, status, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    /** SES renders every member, an unset one as an explicit null. */
    private ObjectNode entityJson(ReputationEntity entity) {
        ObjectNode node = objectMapper.createObjectNode();
        node.putNull("AwsSesManagedStatus");
        ReputationStatusRecord status = entity.customerManagedStatus();
        if (status == null) {
            node.putNull("CustomerManagedStatus");
        } else {
            ObjectNode record = node.putObject("CustomerManagedStatus");
            record.put("Cause", status.cause());
            putTimestamp(record, "LastUpdatedTimestamp", status.lastUpdatedTimestamp());
            record.put("Status", status.status());
        }
        node.put("ReputationEntityReference", entity.reference());
        node.put("ReputationEntityType", SesReputationEntityService.ENTITY_TYPE);
        node.put("ReputationImpact", SesReputationEntityService.NO_IMPACT);
        node.put("ReputationManagementPolicy", entity.policy());
        node.put("SendingStatusAggregate", entity.sendingStatusAggregate());
        return node;
    }
}
