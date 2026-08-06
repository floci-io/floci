package io.github.hectorvent.floci.services.apigateway;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

/**
 * LocalStack-compatible execute endpoint for deployed REST APIs.
 *
 * <p>Supports the alternative URL format used by LocalStack and compatible tooling:
 * {@code /restapis/{apiId}/{stageName}/_user_request_/{proxy+}}
 *
 * <p>This is equivalent to the standard execute-api path:
 * {@code /execute-api/{apiId}/{stageName}/{proxy+}}
 */
@Path("/restapis/{apiId}/{stageName}/_user_request_")
@Produces(MediaType.WILDCARD)
public class ApiGatewayUserRequestController {

    private final ApiGatewayExecuteController executeController;

    @Inject
    public ApiGatewayUserRequestController(ApiGatewayExecuteController executeController) {
        this.executeController = executeController;
    }

    @GET
    @Path("/{proxy: .*}")
    public Response handleGet(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                              @PathParam("apiId") String apiId,
                              @PathParam("stageName") String stageName,
                              @PathParam("proxy") String proxy) {
        return executeController.dispatch("GET", apiId, stageName, proxy, headers, uriInfo, null);
    }

    @POST
    @Path("/{proxy: .*}")
    public Response handlePost(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                               @PathParam("apiId") String apiId,
                               @PathParam("stageName") String stageName,
                               @PathParam("proxy") String proxy,
                               byte[] body) {
        return executeController.dispatch("POST", apiId, stageName, proxy, headers, uriInfo, body);
    }

    @PUT
    @Path("/{proxy: .*}")
    public Response handlePut(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                              @PathParam("apiId") String apiId,
                              @PathParam("stageName") String stageName,
                              @PathParam("proxy") String proxy,
                              byte[] body) {
        return executeController.dispatch("PUT", apiId, stageName, proxy, headers, uriInfo, body);
    }

    @DELETE
    @Path("/{proxy: .*}")
    public Response handleDelete(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                 @PathParam("apiId") String apiId,
                                 @PathParam("stageName") String stageName,
                                 @PathParam("proxy") String proxy) {
        return executeController.dispatch("DELETE", apiId, stageName, proxy, headers, uriInfo, null);
    }

    @PATCH
    @Path("/{proxy: .*}")
    public Response handlePatch(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                @PathParam("apiId") String apiId,
                                @PathParam("stageName") String stageName,
                                @PathParam("proxy") String proxy,
                                byte[] body) {
        return executeController.dispatch("PATCH", apiId, stageName, proxy, headers, uriInfo, body);
    }
}
