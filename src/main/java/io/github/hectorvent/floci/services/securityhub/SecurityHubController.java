package io.github.hectorvent.floci.services.securityhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubAssociation;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubState;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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

import java.time.Instant;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SecurityHubController {
    private final SecurityHubService securityHubService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SecurityHubController(SecurityHubService securityHubService,
                                 RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.securityHubService = securityHubService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/organization/admin")
    public Response listOrganizationAdminAccounts(@Context HttpHeaders headers) {
        SecurityHubState state = securityHubService.state(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        var accounts = response.putArray("AdminAccounts");
        if (state.getAdminAccountId() != null) {
            accounts.addObject().put("AccountId", state.getAdminAccountId()).put("Status", "ENABLED");
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/organization/admin/enable")
    public Response enableOrganizationAdminAccount(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        securityHubService.enableOrganizationAdminAccount(region(headers), request.path("AdminAccountId").asText(null));
        return empty();
    }

    @GET
    @Path("/accounts")
    public Response describeHub(@Context HttpHeaders headers) {
        String region = region(headers);
        securityHubService.requireEnabled(region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("HubArn", securityHubService.hubArn(region));
        response.put("AutoEnableControls", true);
        response.put("ControlFindingGenerator", "SECURITY_CONTROL");
        return Response.ok(response).build();
    }

    @POST
    @Path("/accounts")
    public Response enableSecurityHub(@Context HttpHeaders headers, String body) {
        securityHubService.enableSecurityHub(region(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/findingAggregator/list")
    public Response listFindingAggregators(@Context HttpHeaders headers) {
        SecurityHubState state = securityHubService.state(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        var items = response.putArray("FindingAggregators");
        if (state.getAggregatorArn() != null) {
            items.addObject().put("FindingAggregatorArn", state.getAggregatorArn());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/findingAggregator/create")
    public Response createFindingAggregator(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        return aggregator(securityHubService.createFindingAggregator(region, readTree(body)), region);
    }

    @GET
    @Path("/findingAggregator/get/{findingAggregatorArn: .+}")
    public Response getFindingAggregator(@Context HttpHeaders headers,
                                         @PathParam("findingAggregatorArn") String findingAggregatorArn) {
        String region = region(headers);
        return aggregator(securityHubService.getFindingAggregator(region, findingAggregatorArn), region);
    }

    @PATCH
    @Path("/findingAggregator/update")
    public Response updateFindingAggregator(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        SecurityHubState state = securityHubService.updateFindingAggregator(region, readTree(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FindingAggregatorArn", state.getAggregatorArn());
        return Response.ok(response).build();
    }

    @GET
    @Path("/organization/configuration")
    public Response describeOrganizationConfiguration(@Context HttpHeaders headers) {
        SecurityHubState state = securityHubService.organizationConfiguration(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AutoEnable", false);
        response.put("AutoEnableStandards", "NONE");
        response.put("MemberAccountLimitReached", false);
        ObjectNode configuration = response.putObject("OrganizationConfiguration");
        configuration.put("ConfigurationType", state.getOrganizationConfigurationType());
        configuration.put("Status", state.getOrganizationConfigurationStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/organization/configuration")
    public Response updateOrganizationConfiguration(@Context HttpHeaders headers, String body) {
        securityHubService.updateOrganizationConfiguration(region(headers), readTree(body));
        return empty();
    }

    @GET
    @Path("/configurationPolicy/list")
    public Response listConfigurationPolicies(@Context HttpHeaders headers) {
        String region = region(headers);
        ObjectNode response = objectMapper.createObjectNode();
        var items = response.putArray("ConfigurationPolicySummaries");
        securityHubService.policies(region).forEach(entry -> items.add(policySummary(region, entry.getKey(), entry.getValue())));
        return Response.ok(response).build();
    }

    @POST
    @Path("/configurationPolicy/create")
    public Response createConfigurationPolicy(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        JsonNode request = readTree(body);
        String id = securityHubService.createConfigurationPolicy(region, request);
        return Response.ok(policyDocument(region, id, request)).build();
    }

    @GET
    @Path("/configurationPolicy/get/{id: .+}")
    public Response getConfigurationPolicy(@Context HttpHeaders headers, @PathParam("id") String id) {
        String region = region(headers);
        return Response.ok(policyDocument(region, id, securityHubService.getConfigurationPolicy(region, id))).build();
    }

    @PATCH
    @Path("/configurationPolicy/{id: .+}")
    public Response updateConfigurationPolicy(@Context HttpHeaders headers, @PathParam("id") String id, String body) {
        String region = region(headers);
        return Response.ok(policyDocument(region, id,
                securityHubService.updateConfigurationPolicy(region, id, readTree(body)))).build();
    }

    @POST
    @Path("/configurationPolicyAssociation/get")
    public Response getConfigurationPolicyAssociation(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        JsonNode request = readTree(body);
        return Response.ok(association(securityHubService.association(region, request))).build();
    }

    @POST
    @Path("/configurationPolicyAssociation/associate")
    public Response startConfigurationPolicyAssociation(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        JsonNode request = readTree(body);
        return Response.ok(association(securityHubService.associate(region, request))).build();
    }

    @POST
    @Path("/configurationPolicyAssociation/disassociate")
    public Response startConfigurationPolicyDisassociation(@Context HttpHeaders headers, String body) {
        securityHubService.disassociate(region(headers), readTree(body));
        return empty();
    }

    @POST
    @Path("/configurationPolicyAssociation/list")
    public Response listConfigurationPolicyAssociations(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        ObjectNode response = objectMapper.createObjectNode();
        var items = response.putArray("ConfigurationPolicyAssociationSummaries");
        securityHubService.associations(region).forEach(entry -> items.add(association(entry)));
        return Response.ok(response).build();
    }

    @GET
    @Path("/tags/{resource: .+}")
    public Response listTagsForResource(@Context HttpHeaders headers, @PathParam("resource") String resource) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode tags = response.putObject("Tags");
        securityHubService.tagsForResource(region(headers), resource).forEach(tags::put);
        return Response.ok(response).build();
    }

    private Response aggregator(SecurityHubState state, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FindingAggregatorArn", state.getAggregatorArn());
        response.put("FindingAggregationRegion", region);
        response.put("RegionLinkingMode", state.getRegionLinkingMode());
        if (state.getRegions() != null) response.set("Regions", state.getRegions());
        return Response.ok(response).build();
    }

    private ObjectNode policySummary(String region, String id, JsonNode policy) {
        int slash = id == null ? -1 : id.lastIndexOf('/');
        String policyId = slash >= 0 ? id.substring(slash + 1) : id;
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Arn", securityHubService.policyArn(region, policyId));
        response.put("Id", policyId);
        response.put("Name", policy.path("Name").asText());
        if (policy.hasNonNull("Description")) response.put("Description", policy.path("Description").asText());
        response.put("UpdatedAt", Instant.now().toString());
        return response;
    }

    private ObjectNode policyDocument(String region, String id, JsonNode policy) {
        ObjectNode response = policySummary(region, id, policy);
        if (policy.has("ConfigurationPolicy")) response.set("ConfigurationPolicy", policy.get("ConfigurationPolicy"));
        response.put("CreatedAt", Instant.now().toString());
        return response;
    }

    private ObjectNode association(SecurityHubAssociation association) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("AssociationStatus", association.getStatus());
        if (association.getStatusMessage() != null) {
            response.put("AssociationStatusMessage", association.getStatusMessage());
        }
        response.put("AssociationType", "APPLIED");
        response.put("ConfigurationPolicyId", association.getPolicyId());
        response.put("TargetId", association.getTargetId());
        response.put("TargetType", association.getTargetType());
        response.put("UpdatedAt", Instant.now().toString());
        return response;
    }

    private String region(HttpHeaders headers) { return regionResolver.resolveRegion(headers); }
    private Response empty() { return Response.ok(objectMapper.createObjectNode()).build(); }
    private JsonNode readTree(String body) {
        try { return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body); }
        catch (Exception e) { throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse()); }
    }
}
