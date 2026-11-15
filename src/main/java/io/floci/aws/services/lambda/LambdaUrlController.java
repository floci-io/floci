package io.floci.aws.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.lambda.model.LambdaUrlConfig;
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
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AWS Lambda Function URL configuration API.
 */
@Path("/2021-10-31/functions/{FunctionName}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaUrlController {

    private static final Logger LOG = Logger.getLogger(LambdaUrlController.class);

    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaUrlController(LambdaService lambdaService, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/url")
    public Response createFunctionUrlConfig(@Context HttpHeaders headers,
                                           @PathParam("FunctionName") String functionName,
                                           @QueryParam("Qualifier") String qualifier,
                                           String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            LambdaUrlConfig config = lambdaService.createFunctionUrlConfig(region, functionName, qualifier, request);
            return Response.status(201).entity(config).build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to create Function URL config", e);
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/url")
    public Response getFunctionUrlConfig(@Context HttpHeaders headers,
                                        @PathParam("FunctionName") String functionName,
                                        @QueryParam("Qualifier") String qualifier) {
        String region = regionResolver.resolveRegion(headers);
        LambdaUrlConfig config = lambdaService.getFunctionUrlConfig(region, functionName, qualifier);
        return Response.ok(config).build();
    }

    @PUT
    @Path("/url")
    public Response updateFunctionUrlConfig(@Context HttpHeaders headers,
                                           @PathParam("FunctionName") String functionName,
                                           @QueryParam("Qualifier") String qualifier,
                                           String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            LambdaUrlConfig config = lambdaService.updateFunctionUrlConfig(region, functionName, qualifier, request);
            return Response.ok(config).build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Failed to update Function URL config", e);
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/url")
    public Response deleteFunctionUrlConfig(@Context HttpHeaders headers,
                                           @PathParam("FunctionName") String functionName,
                                           @QueryParam("Qualifier") String qualifier) {
        String region = regionResolver.resolveRegion(headers);
        lambdaService.deleteFunctionUrlConfig(region, functionName, qualifier);
        return Response.noContent().build();
    }

    @GET
    @Path("/urls")
    public Response listFunctionUrlConfigs(@Context HttpHeaders headers,
                                          @PathParam("FunctionName") String functionName,
                                          @Context UriInfo uriInfo) {
        String region = regionResolver.resolveRegion(headers);
        String maxItems = uriInfo.getQueryParameters().getFirst("MaxItems");
        String marker = uriInfo.getQueryParameters().getFirst("Marker");
        PaginatedResult<LambdaUrlConfig> page =
                lambdaService.listFunctionUrlConfigs(region, functionName, maxItems, marker);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("FunctionUrlConfigs", page.items());
        if (page.nextToken() != null) {
            response.put("NextMarker", page.nextToken());
        }
        return Response.ok(response).build();
    }
}
