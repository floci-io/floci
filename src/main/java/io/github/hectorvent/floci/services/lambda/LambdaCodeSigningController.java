package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * Lambda code-signing endpoints — use the /2020-06-30 API version prefix.
 *
 * GET, PUT and DELETE /2020-06-30/functions/{FunctionName}/code-signing-config manage the
 * function's code-signing attachment.
 *
 * The attachment is stored on the function's {@code $LATEST} record. Lambda's code-signing
 * enforcement is not emulated, but the attachment lifecycle and readback are supported.
 *
 * The class-level prefix matters: without it, requests here fall through to a more
 * general catch-all route elsewhere (S3's bucket-path matcher) instead of this
 * controller — see {@link LambdaCodeSigningConfigFunctionsController} for the
 * separate /2020-04-22 endpoint, which needs its own class for the same reason.
 */
@Path("/2020-06-30")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaCodeSigningController {

    private final LambdaService lambdaService;
    private final LambdaCodeSigningConfigService configService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaCodeSigningController(LambdaService lambdaService,
                                       LambdaCodeSigningConfigService configService,
                                       RegionResolver regionResolver,
                                       ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.configService = configService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/functions/{functionName}/code-signing-config")
    public Response getFunctionCodeSigningConfig(@Context HttpHeaders headers,
                                                 @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        LambdaFunction fn = lambdaService.getFunctionForCodeSigningConfig(region, functionName);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("CodeSigningConfigArn", fn.getCodeSigningConfigArn() == null
                ? "" : fn.getCodeSigningConfigArn());
        root.put("FunctionName", fn.getFunctionName());
        return Response.ok(root).build();
    }

    @PUT
    @Path("/functions/{functionName}/code-signing-config")
    public Response putFunctionCodeSigningConfig(@Context HttpHeaders headers,
                                                  @PathParam("functionName") String functionName,
                                                  ObjectNode body) {
        String region = regionResolver.resolveRegion(headers);
        String arn = body == null || !body.hasNonNull("CodeSigningConfigArn")
                ? null : body.get("CodeSigningConfigArn").asText();
        LambdaCodeSigningConfigService.validateArn(arn);
        return configService.withConfigLock(arn, () -> {
            configService.requireForAttachment(region, regionResolver.getAccountId(), arn);
            LambdaFunction fn = lambdaService.putFunctionCodeSigningConfig(region, functionName, arn);

            ObjectNode root = objectMapper.createObjectNode();
            root.put("CodeSigningConfigArn", arn);
            root.put("FunctionName", fn.getFunctionName());
            return Response.ok(root).build();
        });
    }

    @DELETE
    @Path("/functions/{functionName}/code-signing-config")
    public Response deleteFunctionCodeSigningConfig(@Context HttpHeaders headers,
                                                     @PathParam("functionName") String functionName) {
        String region = regionResolver.resolveRegion(headers);
        lambdaService.deleteFunctionCodeSigningConfig(region, functionName);
        return Response.noContent().build();
    }
}
