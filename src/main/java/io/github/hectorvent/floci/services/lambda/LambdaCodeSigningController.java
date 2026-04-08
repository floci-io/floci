package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Lambda code-signing endpoints — use the /2020-06-30 API version prefix.
 *
 * GetFunctionCodeSigningConfig: GET /2020-06-30/functions/{FunctionName}/code-signing-config
 *
 * Floci does not implement code signing config management, so this always returns
 * an empty CodeSigningConfigArn for existing functions (matching real AWS behaviour
 * when no signing config is attached). Non-existent functions surface a 404 via
 * LambdaService, unblocking Terraform and other tools that call this endpoint as
 * part of their normal Lambda resource lifecycle.
 */
@Path("/2020-06-30")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaCodeSigningController {

    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaCodeSigningController(LambdaService lambdaService,
                                       RegionResolver regionResolver,
                                       ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/functions/{functionName}/code-signing-config")
    public Response getFunctionCodeSigningConfig(@Context HttpHeaders headers,
                                                 @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        LambdaFunction fn = lambdaService.getFunction(region, functionName);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("CodeSigningConfigArn", "");
        root.put("FunctionName", fn.getFunctionName());
        return Response.ok(root).build();
    }
}
