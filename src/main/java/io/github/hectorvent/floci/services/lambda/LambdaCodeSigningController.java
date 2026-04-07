package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.RegionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * GetFunctionCodeSigningConfig uses API version 2020-06-30 in the AWS SDK,
 * while most other Lambda endpoints use 2015-03-31.
 */
@Path("/2020-06-30")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaCodeSigningController {

    @Inject
    LambdaService lambdaService;

    @Inject
    RegionResolver regionResolver;

    @Inject
    ObjectMapper objectMapper;

    @GET
    @Path("/functions/{functionName}/code-signing-config")
    public Response getFunctionCodeSigningConfig(@Context HttpHeaders headers,
                                                  @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        lambdaService.getFunction(region, functionName);
        ObjectNode root = objectMapper.createObjectNode();
        root.put("CodeSigningConfigArn", "");
        root.put("FunctionName", functionName);
        return Response.ok(root).build();
    }
}
