package io.github.hectorvent.floci.services.detective;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.detective.model.DetectiveMember;
import io.github.hectorvent.floci.services.detective.model.DetectiveState;
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

import java.util.List;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DetectiveController {
    private final DetectiveService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public DetectiveController(DetectiveService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/orgs/adminAccountslist")
    public Response listOrganizationAdminAccounts(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        validatePageRequest(request);
        DetectiveState state = service.state(region(headers));
        var response = objectMapper.createObjectNode();
        var administrators = response.putArray("Administrators");
        if (state.getAdminAccountId() != null) {
            administrators.addObject().put("AccountId", state.getAdminAccountId());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/orgs/enableAdminAccount")
    public Response enableOrganizationAdminAccount(@Context HttpHeaders headers, String body) {
        service.enableAdmin(region(headers), regionResolver.getAccountId(),
                parse(body).path("AccountId").asText(null));
        return Response.ok().build();
    }

    @POST
    @Path("/graphs/list")
    public Response listGraphs(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        validatePageRequest(parse(body));
        DetectiveState state = service.state(region);
        var response = objectMapper.createObjectNode();
        var graphs = response.putArray("GraphList");
        if (state.isGraph()) {
            graphs.addObject().put("Arn", service.graphArn(region));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/orgs/describeOrganizationConfiguration")
    public Response describeOrganizationConfiguration(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        JsonNode request = parse(body);
        service.requireGraphArn(region, request.path("GraphArn").asText(null));
        DetectiveState state = service.requireGraph(region);
        var response = objectMapper.createObjectNode();
        response.put("AutoEnable", state.isAutoEnable());
        return Response.ok(response).build();
    }

    @POST
    @Path("/orgs/updateOrganizationConfiguration")
    public Response updateOrganizationConfiguration(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        Boolean autoEnable = null;
        if (request.has("AutoEnable") && !request.get("AutoEnable").isNull()) {
            if (!request.get("AutoEnable").isBoolean()) {
                throw new AwsException("ValidationException", "AutoEnable must be a boolean.", 400);
            }
            autoEnable = request.get("AutoEnable").booleanValue();
        }
        service.updateOrganizationConfiguration(region(headers), request.path("GraphArn").asText(null), autoEnable);
        return Response.ok().build();
    }

    @POST
    @Path("/graph/members/list")
    public Response listMembers(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        JsonNode request = parse(body);
        List<DetectiveMember> all = service.listMembers(region, request.path("GraphArn").asText(null));
        Integer maxResults = integer(request, "MaxResults");
        int limit = maxResults == null ? 200 : maxResults;
        if (limit < 1 || limit > 200) {
            throw new AwsException("ValidationException", "MaxResults must be between 1 and 200.", 400);
        }
        int offset = offset(request.path("NextToken").asText(null), all.size());
        int end = Math.min(all.size(), offset + limit);
        var response = objectMapper.createObjectNode();
        var members = response.putArray("MemberDetails");
        for (DetectiveMember member : all.subList(offset, end)) {
            members.add(memberNode(region, member));
        }
        if (end < all.size()) {
            response.put("NextToken", Integer.toString(end));
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/graph/members")
    public Response createMembers(@Context HttpHeaders headers, String body) {
        String region = region(headers);
        JsonNode request = parse(body);
        String graphArn = request.path("GraphArn").asText(null);
        JsonNode accounts = request.get("Accounts");
        if (accounts == null || !accounts.isArray() || accounts.isEmpty() || accounts.size() > 50) {
            throw new AwsException("ValidationException", "Accounts must contain between 1 and 50 members.", 400);
        }
        List<String> accountIds = new java.util.ArrayList<>(accounts.size());
        for (JsonNode account : accounts) {
            accountIds.add(account.path("AccountId").asText(null));
        }
        service.validateCreateMembers(region, graphArn, accountIds);

        var response = objectMapper.createObjectNode();
        var members = response.putArray("Members");
        var unprocessed = response.putArray("UnprocessedAccounts");
        for (JsonNode account : accounts) {
            String accountId = account.path("AccountId").asText(null);
            try {
                DetectiveMember member = service.createMember(region, graphArn,
                        accountId, account.path("EmailAddress").asText(null));
                members.add(memberNode(region, member));
            } catch (AwsException e) {
                if (!"ConflictException".equals(e.getErrorCode())) {
                    throw e;
                }
                unprocessed.addObject()
                        .put("AccountId", accountId)
                        .put("Reason", "The account is already a member of the behavior graph.");
            }
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/graph/member/monitoringstate")
    public Response startMonitoringMember(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        service.startMonitoring(region(headers), request.path("AccountId").asText(null),
                request.path("GraphArn").asText(null));
        return Response.ok().build();
    }

    private com.fasterxml.jackson.databind.node.ObjectNode memberNode(String region, DetectiveMember member) {
        var node = objectMapper.createObjectNode();
        node.put("AccountId", member.getAccountId());
        node.put("EmailAddress", member.getEmailAddress());
        node.put("Status", member.getStatus());
        node.put("GraphArn", service.graphArn(region));
        node.put("AdministratorId", regionResolver.getAccountId());
        node.put("InvitationType", "ORGANIZATION");
        return node;
    }

    private static Integer integer(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw new AwsException("ValidationException", field + " must be an integer.", 400);
        }
        return value.intValue();
    }

    private static int offset(String nextToken, int size) {
        if (nextToken == null || nextToken.isBlank()) {
            return 0;
        }
        try {
            int value = Integer.parseInt(nextToken);
            if (value < 0 || value > size) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationException", "NextToken is invalid.", 400);
        }
    }

    private static void validatePageRequest(JsonNode request) {
        Integer maxResults = integer(request, "MaxResults");
        if (maxResults != null && (maxResults < 1 || maxResults > 200)) {
            throw new AwsException("ValidationException", "MaxResults must be between 1 and 200.", 400);
        }
        JsonNode token = request.get("NextToken");
        if (token != null && !token.isNull()) {
            if (!token.isTextual() || token.textValue().isEmpty() || token.textValue().length() > 1024) {
                throw new AwsException("ValidationException", "NextToken is invalid.", 400);
            }
        }
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
