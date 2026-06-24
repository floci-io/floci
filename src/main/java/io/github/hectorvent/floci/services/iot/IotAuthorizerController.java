package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iot.model.AuthorizerResult;
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

import java.io.IOException;

import static io.github.hectorvent.floci.services.iot.IotJson.putEpoch;
import static io.github.hectorvent.floci.services.iot.IotJson.putToken;
import static io.github.hectorvent.floci.services.iot.IotJson.readStringMap;
import static io.github.hectorvent.floci.services.iot.IotJson.text;

/**
 * AWS IoT custom-authorizer control plane (REST JSON) plus {@code TestInvokeAuthorizer},
 * which exercises the full auth loop (invoke authorizer Lambda + evaluate its policy)
 * without requiring an MQTT connection.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IotAuthorizerController {

    private final IotAuthorizerService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public IotAuthorizerController(IotAuthorizerService service, RegionResolver regionResolver,
                                   ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/authorizer/{authorizerName}")
    public Response createAuthorizer(@Context HttpHeaders headers,
                                     @PathParam("authorizerName") String authorizerName,
                                     String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = parseBody(body);
        IotAuthorizer authorizer = service.createAuthorizer(
                authorizerName,
                text(req, "authorizerFunctionArn"),
                text(req, "tokenKeyName"),
                readStringMap(req.path("tokenSigningPublicKeys")),
                text(req, "status"),
                req.path("signingDisabled").asBoolean(false),
                req.path("enableCachingForHttp").asBoolean(false),
                region);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("authorizerName", authorizer.getAuthorizerName());
        resp.put("authorizerArn", authorizer.getAuthorizerArn());
        return Response.ok(resp).build();
    }

    @GET
    @Path("/authorizer/{authorizerName}")
    public Response describeAuthorizer(@PathParam("authorizerName") String authorizerName) {
        IotAuthorizer authorizer = service.getAuthorizer(authorizerName);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("authorizerDescription", authorizerNode(authorizer));
        return Response.ok(resp).build();
    }

    @PUT
    @Path("/authorizer/{authorizerName}")
    public Response updateAuthorizer(@PathParam("authorizerName") String authorizerName, String body) throws IOException {
        JsonNode req = parseBody(body);
        IotAuthorizer authorizer = service.updateAuthorizer(
                authorizerName,
                text(req, "authorizerFunctionArn"),
                text(req, "tokenKeyName"),
                req.path("tokenSigningPublicKeys").isMissingNode() ? null : readStringMap(req.path("tokenSigningPublicKeys")),
                text(req, "status"));
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("authorizerName", authorizer.getAuthorizerName());
        resp.put("authorizerArn", authorizer.getAuthorizerArn());
        return Response.ok(resp).build();
    }

    @DELETE
    @Path("/authorizer/{authorizerName}")
    public Response deleteAuthorizer(@PathParam("authorizerName") String authorizerName) {
        service.deleteAuthorizer(authorizerName);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/authorizers")
    public Response listAuthorizers(@QueryParam("status") String status,
                                    @QueryParam("marker") String marker,
                                    @QueryParam("pageSize") Integer pageSize,
                                    @QueryParam("ascendingOrder") Boolean ascendingOrder) {
        IotPaging.Page<IotAuthorizer> page = service.listAuthorizers(status, marker, pageSize,
                ascendingOrder == null || ascendingOrder);
        ObjectNode resp = objectMapper.createObjectNode();
        ArrayNode authorizers = resp.putArray("authorizers");
        page.items().forEach(authorizer -> {
            ObjectNode node = authorizers.addObject();
            node.put("authorizerName", authorizer.getAuthorizerName());
            node.put("authorizerArn", authorizer.getAuthorizerArn());
        });
        putToken(resp, "nextMarker", page.nextToken());
        return Response.ok(resp).build();
    }

    @POST
    @Path("/default-authorizer")
    public Response setDefaultAuthorizer(String body) throws IOException {
        JsonNode req = parseBody(body);
        String authorizerName = text(req, "authorizerName");
        service.setDefaultAuthorizer(authorizerName);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("authorizerName", authorizerName);
        resp.put("authorizerArn", service.getAuthorizer(authorizerName).getAuthorizerArn());
        return Response.ok(resp).build();
    }

    @POST
    @Path("/authorizer/{authorizerName}/test")
    public Response testInvokeAuthorizer(@Context HttpHeaders headers,
                                         @PathParam("authorizerName") String authorizerName,
                                         String body) throws IOException {
        String region = regionResolver.resolveRegion(headers);
        JsonNode req = parseBody(body);
        IotAuthorizer authorizer = service.resolveAuthorizer(authorizerName);
        JsonNode mqtt = req.path("mqttContext");
        IotAuthorizerService.AuthInput input = new IotAuthorizerService.AuthInput(
                text(req, "token"),
                text(req, "tokenSignature"),
                text(mqtt, "clientId"),
                text(mqtt, "username"),
                text(mqtt, "password"));
        AuthorizerResult result = service.invokeAuthorizer(authorizer, region, input);

        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("isAuthenticated", result.isAuthenticated());
        if (result.principalId() != null) {
            resp.put("principalId", result.principalId());
        }
        ArrayNode docs = resp.putArray("policyDocuments");
        for (String doc : result.policyDocuments()) {
            docs.add(doc);
        }
        if (result.disconnectAfterSeconds() != null) {
            resp.put("disconnectAfterInSeconds", result.disconnectAfterSeconds());
        }
        if (result.refreshAfterSeconds() != null) {
            resp.put("refreshAfterInSeconds", result.refreshAfterSeconds());
        }
        return Response.ok(resp).build();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode authorizerNode(IotAuthorizer authorizer) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("authorizerName", authorizer.getAuthorizerName());
        node.put("authorizerArn", authorizer.getAuthorizerArn());
        node.put("authorizerFunctionArn", authorizer.getAuthorizerFunctionArn());
        if (authorizer.getTokenKeyName() != null) {
            node.put("tokenKeyName", authorizer.getTokenKeyName());
        }
        ObjectNode keys = node.putObject("tokenSigningPublicKeys");
        authorizer.getTokenSigningPublicKeys().forEach(keys::put);
        node.put("status", authorizer.getStatus());
        node.put("signingDisabled", authorizer.isSigningDisabled());
        node.put("enableCachingForHttp", authorizer.isEnableCachingForHttp());
        putEpoch(node, "creationDate", authorizer.getCreationDate());
        putEpoch(node, "lastModifiedDate", authorizer.getLastModifiedDate());
        return node;
    }

    private JsonNode parseBody(String body) throws IOException {
        return IotJson.parseBody(objectMapper, body);
    }
}
