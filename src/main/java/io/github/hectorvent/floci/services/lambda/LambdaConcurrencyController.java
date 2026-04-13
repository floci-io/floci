package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * Lambda reserved concurrency endpoints. These span two API version prefixes:
 *
 * PutFunctionConcurrency:    PUT    /2017-10-31/functions/{FunctionName}/concurrency
 * DeleteFunctionConcurrency: DELETE /2017-10-31/functions/{FunctionName}/concurrency
 * GetFunctionConcurrency:    GET    /2019-09-30/functions/{FunctionName}/concurrency
 *
 * The class-level {@code @Path("/")} lets each method declare its own absolute
 * version prefix rather than inheriting a single one.
 *
 * The stored value is enforced at invocation time by
 * {@link LambdaConcurrencyLimiter}; Put also validates against the per-region
 * unreserved-minimum.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaConcurrencyController {

    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaConcurrencyController(LambdaService lambdaService,
                                       RegionResolver regionResolver,
                                       ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @PUT
    @Path("/2017-10-31/functions/{functionName}/concurrency")
    public Response putFunctionConcurrency(@Context HttpHeaders headers,
                                           @PathParam("functionName") String functionName,
                                           String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = objectMapper.readValue(body, Map.class);
            if (!request.containsKey("ReservedConcurrentExecutions")
                    || request.get("ReservedConcurrentExecutions") == null) {
                throw new AwsException("InvalidParameterValueException",
                        "ReservedConcurrentExecutions is required", 400);
            }
            Object raw = request.get("ReservedConcurrentExecutions");
            // Jackson parses JSON integer literals as Integer or Long. Anything else
            // (Double, BigDecimal, String, etc.) must be rejected — AWS does not
            // silently truncate 1.5 to 1.
            if (!(raw instanceof Integer) && !(raw instanceof Long)) {
                throw new AwsException("InvalidParameterValueException",
                        "ReservedConcurrentExecutions must be an integer", 400);
            }
            long longValue = ((Number) raw).longValue();
            if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
                throw new AwsException("InvalidParameterValueException",
                        "ReservedConcurrentExecutions is out of range", 400);
            }
            LambdaFunction fn = lambdaService.putFunctionConcurrency(
                    region, functionName, (int) longValue);
            ObjectNode root = objectMapper.createObjectNode();
            root.put("ReservedConcurrentExecutions", fn.getReservedConcurrentExecutions());
            return Response.ok(root).build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("InvalidParameterValueException", e.getMessage(), 400);
        }
    }

    @GET
    @Path("/2019-09-30/functions/{functionName}/concurrency")
    public Response getFunctionConcurrency(@Context HttpHeaders headers,
                                           @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        Integer reserved = lambdaService.getFunctionConcurrency(region, functionName);
        ObjectNode root = objectMapper.createObjectNode();
        if (reserved != null) {
            root.put("ReservedConcurrentExecutions", reserved);
        }
        return Response.ok(root).build();
    }

    @DELETE
    @Path("/2017-10-31/functions/{functionName}/concurrency")
    public Response deleteFunctionConcurrency(@Context HttpHeaders headers,
                                              @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        lambdaService.deleteFunctionConcurrency(region, functionName);
        return Response.noContent().build();
    }
}
