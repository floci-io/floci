package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * ListFunctionsByCodeSigningConfig: GET /2020-04-22/code-signing-configs/{CodeSigningConfigArn}/functions.
 *
 * <p>A separate class from {@link LambdaCodeSigningController} because it lives
 * under a different API version prefix (/2020-04-22 vs /2020-06-30) — JAX-RS
 * class-level {@code @Path} can only be one literal prefix, and a class without
 * one falls through to a more general catch-all route (S3's bucket-path matcher)
 * instead of matching here.</p>
 *
 * <p>Floci does not implement code signing config management — there is no
 * CreateCodeSigningConfig / PutFunctionCodeSigningConfig, so no function can ever
 * actually have a signing config attached. An always-empty function list is the
 * honest answer, not a stub to fill in later.</p>
 */
@Path("/2020-04-22")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaCodeSigningConfigFunctionsController {

    private final ObjectMapper objectMapper;

    @Inject
    public LambdaCodeSigningConfigFunctionsController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/code-signing-configs/{codeSigningConfigArn}/functions")
    public Response listFunctionsByCodeSigningConfig(
            @PathParam("codeSigningConfigArn") String codeSigningConfigArn) {
        ObjectNode root = objectMapper.createObjectNode();
        root.putArray("FunctionArns");
        return Response.ok(root).build();
    }
}
