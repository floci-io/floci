package io.floci.aws.services.appsync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.appsync.model.Api;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.Map;

/**
 * AppSync Events API management: {@code CreateApi}, {@code GetApi}, {@code UpdateApi},
 * {@code DeleteApi} and {@code ListApis}. AWS serves them on {@code /v2/apis}, which API Gateway
 * v2 also claims, so {@link AppSyncEventsRouteFilter} moves an appsync-signed request onto this
 * controller's internal prefix.
 */
@Path(AppSyncEventsRouteFilter.INTERNAL_PREFIX + "/v2/apis")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppSyncApiController {

    private final AppSyncService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public AppSyncApiController(AppSyncService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    public Response createApi(@Context HttpHeaders headers, String body) throws IOException {
        Api api = service.createApi(readRequest(body), regionResolver.resolveRegion(headers));
        return Response.ok(wrap(api)).build();
    }

    @GET
    public Response listApis(@QueryParam("maxResults") Integer maxResults,
                             @QueryParam("nextToken") String nextToken) {
        AppSyncService.Page<Api> page = service.listApis(maxResults, nextToken);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("apis");
        for (Api api : page.items()) {
            items.add(objectMapper.valueToTree(api));
        }
        if (page.nextToken() != null) {
            root.put("nextToken", page.nextToken());
        }
        return Response.ok(root).build();
    }

    @GET
    @Path("/{apiId}")
    public Response getApi(@PathParam("apiId") String apiId) {
        return Response.ok(wrap(service.getApi(apiId))).build();
    }

    @POST
    @Path("/{apiId}")
    public Response updateApi(@PathParam("apiId") String apiId, String body) throws IOException {
        return Response.ok(wrap(service.updateApi(apiId, readRequest(body)))).build();
    }

    @DELETE
    @Path("/{apiId}")
    public Response deleteApi(@PathParam("apiId") String apiId) {
        service.deleteApi(apiId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode wrap(Api api) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("api", objectMapper.valueToTree(api));
        return root;
    }

    private Map<String, Object> readRequest(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        Map<String, Object> request = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        if (request == null) {
            throw new AwsException("BadRequestException", "The request body must be a JSON object", 400);
        }
        return request;
    }
}
