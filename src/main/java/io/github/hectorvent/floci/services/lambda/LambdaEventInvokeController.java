package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.FunctionEventInvokeConfig;
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

import java.util.List;
import java.util.Map;

/**
 * Lambda function event invoke configuration endpoints.
 *
 * PutFunctionEventInvokeConfig:    PUT    /2019-09-25/functions/{FunctionName}/event-invoke-config
 * UpdateFunctionEventInvokeConfig: POST   /2019-09-25/functions/{FunctionName}/event-invoke-config
 * GetFunctionEventInvokeConfig:    GET    /2019-09-25/functions/{FunctionName}/event-invoke-config
 * DeleteFunctionEventInvokeConfig: DELETE /2019-09-25/functions/{FunctionName}/event-invoke-config
 * ListFunctionEventInvokeConfigs:  GET    /2019-09-25/functions/{FunctionName}/event-invoke-config/list
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaEventInvokeController {

    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaEventInvokeController(LambdaService lambdaService,
                                       RegionResolver regionResolver,
                                       ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @PUT
    @Path("/2019-09-25/functions/{functionName}/event-invoke-config")
    public Response putFunctionEventInvokeConfig(@Context HttpHeaders headers,
                                                 @PathParam("functionName") String functionName,
                                                 @QueryParam("Qualifier") String qualifier,
                                                 String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            FunctionEventInvokeConfig cfg = lambdaService.putEventInvokeConfig(region, functionName, qualifier, request);
            return Response.ok(cfg).build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    @POST
    @Path("/2019-09-25/functions/{functionName}/event-invoke-config")
    public Response updateFunctionEventInvokeConfig(@Context HttpHeaders headers,
                                                    @PathParam("functionName") String functionName,
                                                    @QueryParam("Qualifier") String qualifier,
                                                    String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            FunctionEventInvokeConfig cfg = lambdaService.updateEventInvokeConfig(region, functionName, qualifier, request);
            return Response.ok(cfg).build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/2019-09-25/functions/{functionName}/event-invoke-config")
    public Response getFunctionEventInvokeConfig(@Context HttpHeaders headers,
                                                 @PathParam("functionName") String functionName,
                                                 @QueryParam("Qualifier") String qualifier) {
        String region = regionResolver.resolveRegion(headers);
        FunctionEventInvokeConfig cfg = lambdaService.getEventInvokeConfig(region, functionName, qualifier);
        return Response.ok(cfg).build();
    }

    @DELETE
    @Path("/2019-09-25/functions/{functionName}/event-invoke-config")
    public Response deleteFunctionEventInvokeConfig(@Context HttpHeaders headers,
                                                    @PathParam("functionName") String functionName,
                                                    @QueryParam("Qualifier") String qualifier) {
        String region = regionResolver.resolveRegion(headers);
        lambdaService.deleteEventInvokeConfig(region, functionName, qualifier);
        return Response.noContent().build();
    }

    @GET
    @Path("/2019-09-25/functions/{functionName}/event-invoke-config/list")
    public Response listFunctionEventInvokeConfigs(@Context HttpHeaders headers,
                                                   @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        List<FunctionEventInvokeConfig> configs = lambdaService.listEventInvokeConfigs(region, functionName);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("FunctionEventInvokeConfigs");
        for (FunctionEventInvokeConfig cfg : configs) {
            items.addPOJO(cfg);
        }
        return Response.ok(root).build();
    }
}
