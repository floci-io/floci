package io.github.hectorvent.floci.services.macie2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.macie2.model.MacieMember;
import io.github.hectorvent.floci.services.macie2.model.MacieState;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MacieController {
    private final MacieService macieService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public MacieController(MacieService macieService, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.macieService = macieService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public Response listAdminInternal(String region) {
        MacieState state = macieService.state(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode accounts = response.putArray("adminAccounts");
        if (state.getAdminAccountId() != null) {
            accounts.addObject().put("accountId", state.getAdminAccountId()).put("status", "ENABLED");
        }
        return Response.ok(response).build();
    }

    @POST @Path("/admin")
    public Response enableOrganizationAdminAccount(@Context HttpHeaders headers, String body) {
        macieService.enableOrganizationAdminAccount(
                region(headers), readTree(body).path("adminAccountId").asText(null));
        return empty();
    }

    @GET @Path("/macie")
    public Response getMacieSession(@Context HttpHeaders headers) {
        macieService.requireSession(region(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", "ENABLED");
        response.put("serviceRole", "arn:aws:iam::" + regionResolver.getAccountId()
                + ":role/aws-service-role/macie.amazonaws.com/AWSServiceRoleForAmazonMacie");
        return Response.ok(response).build();
    }

    @POST @Path("/macie")
    public Response enableMacie(@Context HttpHeaders headers, String body) {
        macieService.enableMacie(region(headers));
        return empty();
    }

    @POST @Path("/members")
    public Response createMember(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        JsonNode account = request.get("account");
        if (account == null || !account.isObject()) {
            throw new AwsException(
                    "ValidationException", "account is required.", 400);
        }
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode tagsNode = request.get("tags");
        if (tagsNode != null && !tagsNode.isNull()) {
            if (!tagsNode.isObject()) {
                throw new AwsException(
                        "ValidationException", "tags must be an object.", 400);
            }
            tagsNode.fields().forEachRemaining(entry -> tags.put(
                    entry.getKey(), entry.getValue().isTextual() ? entry.getValue().asText() : null));
        }
        MacieMember member = macieService.createMember(
                region(headers),
                regionResolver.getAccountId(),
                account.path("accountId").asText(null),
                account.path("email").asText(null),
                tags);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("arn", member.arn());
        return Response.ok(response).build();
    }

    @GET @Path("/members")
    public Response listMembers(@Context HttpHeaders headers,
                                @QueryParam("maxResults") String maxResults,
                                @QueryParam("nextToken") String nextToken,
                                @QueryParam("onlyAssociated") String onlyAssociated) {
        MacieService.Page<MacieMember> page = macieService.listMembers(
                region(headers), regionResolver.getAccountId(), maxResults, nextToken, onlyAssociated);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("members", objectMapper.valueToTree(page.items()));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @PATCH @Path("/admin/configuration")
    public Response updateOrganizationConfiguration(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        if (!request.has("autoEnable") || !request.get("autoEnable").isBoolean()) {
            throw new AwsException("ValidationException", "autoEnable is required.", 400);
        }
        macieService.updateOrganizationConfiguration(
                region(headers), regionResolver.getAccountId(), request.path("autoEnable").asBoolean());
        return empty();
    }

    @GET @Path("/admin/configuration")
    public Response describeOrganizationConfiguration(@Context HttpHeaders headers) {
        MacieState state = macieService.requireAdministratorSession(region(headers), regionResolver.getAccountId());
        ObjectNode response = objectMapper.createObjectNode();
        response.put("autoEnable", state.isAutoEnable());
        return Response.ok(response).build();
    }

    private String region(HttpHeaders headers) { return regionResolver.resolveRegion(headers); }
    private Response empty() { return Response.ok(objectMapper.createObjectNode()).build(); }
    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
