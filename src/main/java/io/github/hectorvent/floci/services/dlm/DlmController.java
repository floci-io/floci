package io.github.hectorvent.floci.services.dlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.dlm.model.LifecyclePolicy;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path(DlmRouteFilter.INTERNAL_PREFIX)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DlmController {

    private final DlmService dlmService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public DlmController(DlmService dlmService, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.dlmService = dlmService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/policies")
    public Response createLifecyclePolicy(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        LifecyclePolicy policy = dlmService.createLifecyclePolicy(readTree(body), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("PolicyId", policy.getPolicyId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/policies/{policyId}")
    public Response getLifecyclePolicy(@PathParam("policyId") String policyId,
                                       @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Policy", policyNode(dlmService.getLifecyclePolicy(policyId, region)));
        return Response.ok(response).build();
    }

    @GET
    @Path("/policies")
    public Response getLifecyclePolicies(@Context HttpHeaders headers,
                                         @QueryParam("policyIds") List<String> policyIds,
                                         @QueryParam("state") String state,
                                         @QueryParam("resourceTypes") List<String> resourceTypes,
                                         @QueryParam("targetTags") List<String> targetTags,
                                         @QueryParam("tagsToAdd") List<String> tagsToAdd,
                                         @QueryParam("defaultPolicyType") String defaultPolicyType) {
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode policies = response.putArray("Policies");
        for (LifecyclePolicy policy : dlmService.getLifecyclePolicies(region, policyIds, state,
                resourceTypes, targetTags, tagsToAdd, defaultPolicyType)) {
            policies.add(policySummaryNode(policy));
        }
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/policies/{policyId}")
    public Response updateLifecyclePolicy(@PathParam("policyId") String policyId,
                                          @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        dlmService.updateLifecyclePolicy(policyId, readTree(body), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/policies/{policyId}")
    public Response deleteLifecyclePolicy(@PathParam("policyId") String policyId,
                                          @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        dlmService.deleteLifecyclePolicy(policyId, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode policyNode(LifecyclePolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("PolicyId", policy.getPolicyId());
        node.put("Description", policy.getDescription());
        node.put("State", policy.getState());
        node.put("ExecutionRoleArn", policy.getExecutionRoleArn());
        node.put("DateCreated", policy.getDateCreated());
        node.put("DateModified", policy.getDateModified());
        if (policy.getPolicyDetails() != null) {
            node.set("PolicyDetails", policy.getPolicyDetails());
        }
        node.set("Tags", objectMapper.valueToTree(
                policy.getTags() != null ? policy.getTags() : Map.of()));
        node.put("PolicyArn", policy.getPolicyArn());
        node.put("DefaultPolicy", policy.getDefaultPolicyType() != null);
        return node;
    }

    private ObjectNode policySummaryNode(LifecyclePolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("PolicyId", policy.getPolicyId());
        node.put("Description", policy.getDescription());
        node.put("State", policy.getState());
        node.set("Tags", objectMapper.valueToTree(
                policy.getTags() != null ? policy.getTags() : Map.of()));
        JsonNode details = policy.getPolicyDetails();
        String policyType = details == null ? null : details.path("PolicyType").textValue();
        if (policyType == null) {
            policyType = "EBS_SNAPSHOT_MANAGEMENT";
        }
        node.put("PolicyType", policyType);
        node.put("DefaultPolicy", policy.getDefaultPolicyType() != null);
        return node;
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
