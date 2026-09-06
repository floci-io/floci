package io.github.hectorvent.floci.services.inspector2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.inspector2.model.InspectorState;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class Inspector2Controller {
    private final Inspector2Service service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final RequestContext requestContext;

    @Inject
    public Inspector2Controller(Inspector2Service service, RegionResolver regionResolver, ObjectMapper objectMapper,
                                RequestContext requestContext) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.requestContext = requestContext;
    }

    @POST
    @Path("/delegatedadminaccounts/list")
    public Response listDelegatedAdminAccounts(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        if (request.has("maxResults")) {
            JsonNode maxResults = request.get("maxResults");
            if (!maxResults.canConvertToInt() || maxResults.intValue() < 1 || maxResults.intValue() > 5) {
                throw new AwsException("ValidationException", "maxResults must be between 1 and 5.", 400);
            }
        }
        if (request.hasNonNull("nextToken") && !request.path("nextToken").asText().isBlank()) {
            throw new AwsException("ValidationException", "nextToken is invalid.", 400);
        }
        InspectorState state = service.state(region(headers));
        var response = objectMapper.createObjectNode();
        var accounts = response.putArray("delegatedAdminAccounts");
        if (state.getAdminAccountId() != null) {
            accounts.addObject().put("accountId", state.getAdminAccountId()).put("status", "ENABLED");
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/delegatedadminaccounts/enable")
    public Response enableDelegatedAdminAccount(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        String accountId = request.path("delegatedAdminAccountId").asText(null);
        service.enableDelegatedAdmin(region(headers), accountId);
        var response = objectMapper.createObjectNode();
        response.put("delegatedAdminAccountId", accountId);
        return Response.ok(response).build();
    }

    @POST
    @Path("/delegatedadminaccounts/disable")
    public Response disableDelegatedAdminAccount(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        String accountId = request.path("delegatedAdminAccountId").asText(null);
        service.disableDelegatedAdmin(region(headers), accountId);
        var response = objectMapper.createObjectNode();
        response.put("delegatedAdminAccountId", accountId);
        return Response.ok(response).build();
    }

    @POST
    @Path("/status/batch/get")
    public Response batchGetAccountStatus(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        JsonNode accountIds = request.get("accountIds");
        if (accountIds != null && (!accountIds.isArray() || accountIds.size() > 100)) {
            throw new AwsException("ValidationException",
                    "accountIds must contain at most 100 account IDs.", 400);
        }
        InspectorState state = service.accountStatus(region(headers));
        var response = objectMapper.createObjectNode();
        var accounts = response.putArray("accounts");
        java.util.List<String> requestedAccounts = new java.util.ArrayList<>();
        if (accountIds == null || accountIds.isEmpty()) {
            requestedAccounts.add(requestContext.getAccountId());
        } else {
            for (JsonNode accountId : accountIds) {
                Inspector2Service.requireAccountId(accountId.asText(null));
                requestedAccounts.add(accountId.asText());
            }
        }
        for (String accountId : requestedAccounts) {
            var account = accounts.addObject();
            account.put("accountId", accountId);
            account.set("state", stateNode(state.getStatus()));
            var resources = account.putObject("resourceState");
            resources.set("ec2", stateNode(state.getStatus()));
            resources.set("ecr", stateNode(state.getStatus()));
            resources.set("lambda", stateNode(state.getStatus()));
            resources.set("lambdaCode", stateNode(state.getStatus()));
            resources.set("codeRepository", stateNode(state.getStatus()));
        }
        response.putArray("failedAccounts");
        return Response.ok(response).build();
    }

    @POST
    @Path("/enable")
    public Response enable(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        service.enable(region(headers), request);
        var response = objectMapper.createObjectNode();
        var accounts = response.putArray("accounts");
        JsonNode accountIds = request.get("accountIds");
        java.util.List<String> requestedAccounts = new java.util.ArrayList<>();
        if (accountIds == null || accountIds.isEmpty()) {
            requestedAccounts.add(requestContext.getAccountId());
        } else {
            for (JsonNode accountId : accountIds) {
                requestedAccounts.add(accountId.asText());
            }
        }
        for (String accountId : requestedAccounts) {
            var account = accounts.addObject();
            account.put("accountId", accountId);
            account.put("status", "ENABLING");
            var resourceStatus = account.putObject("resourceStatus");
            resourceStatus.put("ec2", "ENABLING");
            resourceStatus.put("ecr", "ENABLING");
            resourceStatus.put("lambda", "ENABLING");
            resourceStatus.put("lambdaCode", "ENABLING");
            resourceStatus.put("codeRepository", "ENABLING");
        }
        response.putArray("failedAccounts");
        return Response.ok(response).build();
    }

    @POST
    @Path("/organizationconfiguration/update")
    public Response updateOrganizationConfiguration(@Context HttpHeaders headers, String body) {
        InspectorState state = service.updateOrganizationConfiguration(
                region(headers), requestContext.getAccountId(), parse(body));
        return Response.ok(organizationConfigurationResponse(state)).build();
    }

    @POST
    @Path("/organizationconfiguration/describe")
    public Response describeOrganizationConfiguration(@Context HttpHeaders headers, String body) {
        InspectorState state = service.organizationConfiguration(region(headers), requestContext.getAccountId());
        return Response.ok(organizationConfigurationResponse(state)).build();
    }

    private com.fasterxml.jackson.databind.node.ObjectNode organizationConfigurationResponse(InspectorState state) {
        var response = objectMapper.createObjectNode();
        var autoEnable = response.putObject("autoEnable");
        autoEnable.put("ec2", state.isAutoEnableEc2());
        autoEnable.put("ecr", state.isAutoEnableEcr());
        autoEnable.put("lambda", state.isAutoEnableLambda());
        autoEnable.put("lambdaCode", state.isAutoEnableLambdaCode());
        autoEnable.put("codeRepository", state.isAutoEnableCodeRepository());
        response.put("maxAccountLimitReached", false);
        return response;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode stateNode(String status) {
        var state = objectMapper.createObjectNode();
        state.put("status", status);
        return state;
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
