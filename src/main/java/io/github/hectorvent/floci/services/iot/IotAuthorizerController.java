package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iot.model.IotAuthorizer;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST-JSON routes for AWS IoT custom authorizers: CreateAuthorizer, DescribeAuthorizer,
 * UpdateAuthorizer, DeleteAuthorizer, ListAuthorizers, SetDefaultAuthorizer,
 * DescribeDefaultAuthorizer and ClearDefaultAuthorizer, on the paths and shapes the AWS SDKs use.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IotAuthorizerController {

    private final IotAuthorizerService authorizerService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public IotAuthorizerController(IotAuthorizerService authorizerService, RegionResolver regionResolver,
                                   ObjectMapper objectMapper) {
        this.authorizerService = authorizerService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/authorizer/{authorizerName}")
    public Response createAuthorizer(@Context HttpHeaders headers, @PathParam("authorizerName") String authorizerName,
                                     String body) {
        return Response.ok(nameAndArn(authorizerService.createAuthorizer(
                authorizerName, readJson(body), regionResolver.resolveRegion(headers)))).build();
    }

    @GET
    @Path("/authorizer/{authorizerName}")
    public Response describeAuthorizer(@Context HttpHeaders headers, @PathParam("authorizerName") String authorizerName) {
        return described(authorizerService.describeAuthorizer(authorizerName, regionResolver.resolveRegion(headers)));
    }

    @PUT
    @Path("/authorizer/{authorizerName}")
    public Response updateAuthorizer(@Context HttpHeaders headers, @PathParam("authorizerName") String authorizerName,
                                     String body) {
        return Response.ok(nameAndArn(authorizerService.updateAuthorizer(
                authorizerName, readJson(body), regionResolver.resolveRegion(headers)))).build();
    }

    @DELETE
    @Path("/authorizer/{authorizerName}")
    @Consumes(MediaType.WILDCARD)
    public Response deleteAuthorizer(@Context HttpHeaders headers, @PathParam("authorizerName") String authorizerName) {
        authorizerService.deleteAuthorizer(authorizerName, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/authorizers")
    public Response listAuthorizers(@Context HttpHeaders headers,
                                    @QueryParam("pageSize") Integer pageSize,
                                    @QueryParam("marker") String marker,
                                    @QueryParam("isAscendingOrder") boolean ascending,
                                    @QueryParam("status") String status) {
        IotService.Page<IotAuthorizer> page = authorizerService.listAuthorizers(
                regionResolver.resolveRegion(headers), status, ascending, marker, pageSize);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode authorizers = response.putArray("authorizers");
        page.items().forEach(authorizer -> authorizers.add(nameAndArn(authorizer)));
        response.put("nextMarker", page.nextToken());
        return Response.ok(response).build();
    }

    @POST
    @Path("/default-authorizer")
    public Response setDefaultAuthorizer(@Context HttpHeaders headers, String body) {
        return Response.ok(nameAndArn(authorizerService.setDefaultAuthorizer(
                readJson(body).path("authorizerName").asText(null), regionResolver.resolveRegion(headers)))).build();
    }

    @GET
    @Path("/default-authorizer")
    public Response describeDefaultAuthorizer(@Context HttpHeaders headers) {
        return described(authorizerService.describeDefaultAuthorizer(regionResolver.resolveRegion(headers)));
    }

    @DELETE
    @Path("/default-authorizer")
    @Consumes(MediaType.WILDCARD)
    public Response clearDefaultAuthorizer(@Context HttpHeaders headers) {
        authorizerService.clearDefaultAuthorizer(regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode nameAndArn(IotAuthorizer authorizer) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("authorizerName", authorizer.getAuthorizerName());
        response.put("authorizerArn", authorizer.getAuthorizerArn());
        return response;
    }

    /** Every member, as AWS sends them: an unset token key name and key map are JSON null, tags never appear. */
    private Response described(IotAuthorizer authorizer) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode description = response.putObject("authorizerDescription");
        description.put("authorizerArn", authorizer.getAuthorizerArn());
        description.put("authorizerFunctionArn", authorizer.getAuthorizerFunctionArn());
        description.put("authorizerName", authorizer.getAuthorizerName());
        description.put("creationDate", authorizer.getCreationDate().toEpochMilli() / 1000.0);
        description.put("enableCachingForHttp", authorizer.isEnableCachingForHttp());
        description.put("lastModifiedDate", authorizer.getLastModifiedDate().toEpochMilli() / 1000.0);
        description.put("signingDisabled", authorizer.isSigningDisabled());
        description.put("status", authorizer.getStatus());
        description.put("tokenKeyName", authorizer.getTokenKeyName());
        description.set("tokenSigningPublicKeys", objectMapper.valueToTree(authorizer.getTokenSigningPublicKeys()));
        return Response.ok(response).build();
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidRequestException", e.getMessage(), 400);
        }
    }
}
